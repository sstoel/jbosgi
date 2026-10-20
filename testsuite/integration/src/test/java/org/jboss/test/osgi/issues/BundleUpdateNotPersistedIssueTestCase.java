/*
 * #%L
 * JBossOSGi
 * %%
 * Copyright (C) 2026 RTR
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 * #L%
 */
package org.jboss.test.osgi.issues;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.ByteArrayAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * A Bundle.update of a deployed bundle is not persisted as an update: after a server restart the original deployment
 * comes back with the OLD content (version 1.0.0, original location) and the new revision comes back as a second,
 * independent bundle with location {@code <name>-rev1.jar} - two bundles with the same symbolic name, the update
 * effectively reverted, and the persistent data area of the updated bundle is lost.
 *
 * <h3>Severity</h3>
 * major (after the next restart an updated application silently runs its old version next to a copy of the new one;
 * both are ACTIVE, services and exports are duplicated, data written by the bundle is gone).
 *
 * <h3>Component</h3>
 * jbosgi umbrella, wildfly/subsystem: {@code org.jboss.as.osgi.service.BundleLifecycleIntegration.BundleLifecycleImpl}
 * ({@code createBundleRevision} + {@code getRuntimeName}: an update is deployed as an additional management deployment
 * {@code <name>-revN.jar}; the original deployment keeps its old content) and the boot path
 * ({@code BundleDeploymentProcessor}/{@code BundleInstallProcessor}) that installs every persisted deployment as a bundle
 * of its own.
 *
 * <h3>Found by</h3>
 * wildfly-lifecycle workstream, restart persistence checks on the patched WildFly 41.0.1 + jbosgi R8 server (JDK 25): a
 * CLI-deployed bundle updated through Bundle.update(InputStream) from another bundle (with and without a following
 * refreshBundles), then :shutdown and start.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8 4.4.1 Bundle Identifiers (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html):
 * Bundle identifier "a Framework assigned unique identifier for the full lifetime of a bundle, even if it is updated or
 * the Framework is restarted"; Bundle location "A location string uniquely identifies a bundle and must not change when
 * a bundle is updated." 4.4.9 Updating Bundles: "The update process supports migration from one revision of a bundle to
 * a newer revision of the same bundle. ... If the old bundle revision has an isInUse() bundle wiring then all
 * capabilities provided by the old bundle wiring must remain available for existing bundles and future resolves until
 * the bundle is refreshed, see Refreshing, or the Framework is restarted." 4.5.2: the persistent storage area belongs
 * to the installed bundle.
 *
 * <h3>Code location</h3>
 * <ul>
 * <li>jbosgi wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/BundleLifecycleIntegration.java:226-247
 * ({@code createBundleRevision} deploys the update stream as a new server deployment) and :486-510 ({@code getRuntimeName}:
 * {@code runtimeName += "-rev" + revid + suffix} for updates). The deployment {@code <name>.jar} with the original content
 * stays in the model, next to {@code <name>-rev1.jar}; nothing replaces its content. Same logic on WF41
 * (BundleLifecycleIntegration.java:218/439 there).</li>
 * <li>On the next boot both deployments are installed: {@code <name>.jar} as a bundle with the original content at the
 * original location, {@code <name>-rev1.jar} as a second bundle whose location is the runtime name.</li>
 * </ul>
 *
 * <h3>Root cause</h3>
 * The update is persisted as an additional deployment instead of as new content of the bundle's deployment, and the
 * boot treats each deployment as an independent bundle. Related: ee/10 (UpdateRefreshLeavesFailedRevisionDeployment,
 * the refresh after an update leaves {@code <name>-rev1} FAILED in the model, "zombie bundle copy after restart"); this
 * test shows the duplicate and the reverted update without any refresh and with both deployments in status OK, so
 * fixing the refresh rollback of ee/10 does not fix it.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=BundleUpdateNotPersistedIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local
 * Maven repository. Not an Arquillian test: it copies the testsuite server to target/issues-bundleupdate-server and
 * starts/stops it with port offset 1200 (management 11190). The update runs in a thread of a helper bundle because an
 * update from BundleActivator.start itself hangs, see lifecycle/04.)
 * The port offset can be overridden with the system property issue.port.offset (default 1200; management 9990+n),
 * e.g. append {@code -Dissue.port.offset=300} to the mvn command to run the test beside a server that uses
 * offset 1200.
 *
 * <h3>Observed</h3>
 * <pre>
 * org.junit.ComparisonFailure: Expected the bundle updated with Bundle.update ([update-target.jar 2.0.0]) to come back
 *   after a server restart as that one bundle, at the new revision and its original location, but the bundles with
 *   symbolic name update-target are [update-target-rev1.jar 2.0.0, update-target.jar 1.0.0]
 * (manual runs, with and without refreshBundles after the update: `ls deployment` shows rec-a.jar and rec-a-rev1.jar,
 *  both status OK; after the restart lc.rec.a 1.0.0 at rec-a.jar and lc.rec.a 2.0.0 at rec-a-rev1.jar are both ACTIVE,
 *  and both start with an empty data area although the updated bundle had written a file into it.)
 * </pre>
 *
 * <h3>Expected</h3>
 * After the restart exactly one bundle {@code update-target} at location {@code update-target.jar}, version 2.0.0, with
 * its bundle id and data area.
 *
 * <h3>Reference behaviour</h3>
 * A plain jbosgi framework (and Equinox/Felix) relaunch the updated bundle with the new content from storage.
 *
 * <h3>Suggested fix</h3>
 * Persist an update by replacing the content of the bundle's own deployment (e.g. {@code full-replace-deployment} of
 * {@code <name>.jar} keeping the runtime name, or an equivalent management operation) instead of adding
 * {@code <name>-revN.jar}; or keep the revision deployments out of the persistent configuration and restore the updated
 * content from the framework storage at boot. (No trial fix: the change touches the update and refresh policies, see
 * also ee/10.) This fix also closes ee/10: its failure needs the two coexisting deployments created here, so ee/10 is
 * counted with this issue as one root cause.
 *
 * <h3>Regression vs WF41</h3>
 * no (pre-existing; the same update-as-new-deployment logic is on WF41).
 */
public class BundleUpdateNotPersistedIssueTestCase {

    static final int OFFSET = Integer.getInteger("issue.port.offset", 1200);
    static final int MGMT_PORT = 9990 + OFFSET;
    static final String TARGET = "update-target";

    /** Updates bundle update-target from its embedded v2.jar, in a separate thread (an update from start() itself would hang, see lifecycle/04) */
    public static class UpdatingActivator implements BundleActivator, Runnable {
        private volatile BundleContext context;

        @Override
        public void start(BundleContext context) {
            this.context = context;
            new Thread(this, "issues-updater").start();
        }

        @Override
        public void run() {
            try {
                for (Bundle bundle : context.getBundles()) {
                    if (TARGET.equals(bundle.getSymbolicName())) {
                        try (InputStream in = context.getBundle().getEntry("v2.jar").openStream()) {
                            bundle.update(in);
                        }
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        @Override
        public void stop(BundleContext context) {
        }
    }

    private File home;
    private Process process;

    @After
    public void tearDown() throws Exception {
        stopServer();
    }

    @Test
    public void testUpdatedBundleAfterRestart() throws Exception {
        prepareServer("issues-bundleupdate-server");
        startServer("console-1.out");
        String before;
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            ServerDeploymentHelper server = new ServerDeploymentHelper(client);
            server.deploy(TARGET + ".jar", new java.io.ByteArrayInputStream(target("1.0.0")));
            server.deploy("updater.jar", updater());
            long begin = System.currentTimeMillis();
            while (bundles(client).contains("2.0.0") == false && System.currentTimeMillis() - begin < 30000)
                Thread.sleep(200);
            server.undeploy("updater.jar");
            before = bundles(client);
            assertEquals("Precondition: one updated bundle before the restart", "[" + TARGET + ".jar 2.0.0]", before);
        }
        stopServer();
        startServer("console-2.out");
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            String after = bundles(client);
            assertEquals("Expected the bundle updated with Bundle.update (" + before + ") to come back after a server restart as that one bundle, at the new "
                    + "revision and its original location, but the bundles with symbolic name " + TARGET + " are " + after, before, after);
        }
    }

    /** "location version" of every bundle with the symbolic name update-target */
    private String bundles(ModelControllerClient client) throws IOException {
        ModelNode op = new ModelNode();
        op.get("operation").set("read-children-resources");
        op.get("address").add("subsystem", "osgi");
        op.get("child-type").set("bundle");
        op.get("include-runtime").set(true);
        List<String> result = new ArrayList<String>();
        for (Property property : client.execute(op).get("result").asPropertyList()) {
            ModelNode bundle = property.getValue();
            if (TARGET.equals(bundle.get("symbolic-name").asString()))
                result.add(bundle.get("location").asString() + " " + bundle.get("version").asString());
        }
        java.util.Collections.sort(result);
        return result.toString();
    }

    private static byte[] target(final String version) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, TARGET + ".jar");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(TARGET);
                builder.addBundleVersion(version);
                return builder.openStream();
            }
        });
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        archive.as(ZipExporter.class).exportTo(out);
        return out.toByteArray();
    }

    private static InputStream updater() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "updater.jar");
        archive.addClasses(UpdatingActivator.class);
        archive.add(new ByteArrayAsset(target("2.0.0")), "v2.jar");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName("updater");
                builder.addBundleActivator(UpdatingActivator.class);
                builder.addImportPackages(BundleActivator.class);
                return builder.openStream();
            }
        });
        return archive.as(ZipExporter.class).exportAsInputStream();
    }

    private File prepareServer(String name) throws IOException {
        File source = new File(System.getProperty("jboss.home", "target/wildfly-41.0.1.Final"));
        assertTrue("Precondition: the testsuite server " + source, new File(source, "bin/standalone.sh").isFile());
        File target = new File("target/" + name);
        deleteRecursive(target.toPath());
        Path src = source.toPath();
        Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String rel = src.relativize(dir).toString();
                if (rel.equals("standalone/data") || rel.equals("standalone/log") || rel.equals("standalone/tmp"))
                    return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(target.toPath().resolve(rel));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String rel = src.relativize(file).toString();
                if (rel.startsWith("standalone/deployments/") && rel.endsWith("README.txt") == false)
                    return FileVisitResult.CONTINUE;
                Files.copy(file, target.toPath().resolve(rel), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
        // Without the deployments that the Arquillian server persisted: their content is in standalone/data, which is not
        // copied, and the server would not boot (WFLYSRV0137)
        Path config = target.toPath().resolve("standalone/configuration/standalone-osgi.xml");
        String xml = new String(Files.readAllBytes(config), StandardCharsets.UTF_8);
        Files.write(config, xml.replaceAll("(?s)\\s*<deployments>.*?</deployments>", "").getBytes(StandardCharsets.UTF_8));
        new File(target, "bin/standalone.sh").setExecutable(true);
        return home = target.getCanonicalFile();
    }

    private void startServer(String log) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(new File(home, "bin/standalone.sh").getPath(), "-c", "standalone-osgi.xml",
                "-Djboss.socket.binding.port-offset=" + OFFSET);
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        builder.environment().remove("JAVA_OPTS");
        builder.redirectErrorStream(true);
        File out = new File(home, log);
        builder.redirectOutput(out);
        process = builder.start();
        long deadline = System.currentTimeMillis() + 180000;
        while (System.currentTimeMillis() < deadline) {
            String text = out.exists() ? new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8) : "";
            if (text.contains("WFLYSRV0025") || text.contains("WFLYSRV0026"))
                return;
            if (process.isAlive() == false)
                fail("Precondition: server process ended: " + text);
            Thread.sleep(500);
        }
        fail("Precondition: server did not start in time, see " + out);
    }

    private void stopServer() throws Exception {
        if (process == null)
            return;
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            ModelNode op = new ModelNode();
            op.get("operation").set("shutdown");
            client.execute(op);
        } catch (Exception ex) {
            // already down
        }
        // only the processes this test started: the script and its children
        List<ProcessHandle> handles = new ArrayList<ProcessHandle>();
        process.toHandle().descendants().forEach(handles::add);
        handles.add(process.toHandle());
        long deadline = System.currentTimeMillis() + 60000;
        for (ProcessHandle handle : handles) {
            while (handle.isAlive() && System.currentTimeMillis() < deadline)
                Thread.sleep(200);
            if (handle.isAlive())
                handle.destroyForcibly();
        }
        process = null;
    }

    private static void deleteRecursive(Path path) throws IOException {
        if (Files.exists(path) == false)
            return;
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
