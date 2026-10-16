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
package org.jboss.test.osgi.issues.shutdown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.dmr.ModelNode;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A refresh ({@code FrameworkWiring.refreshBundles}) of a bundle that was deployed by the deployment scanner turns the
 * scanner's deployment into a persistent management deployment of the same name: the refresh undeploys it and
 * re-deploys it with {@code ServerDeploymentHelper.deploy}, which adds a {@code <deployment>} element to
 * standalone.xml. At the next start the deployment scanner adds the same name again and the server cannot boot:
 * "WFLYCTL0212: Duplicate resource [("deployment" =&gt; "...")]" followed by "WFLYSRV0056: Server boot has failed in
 * an unrecoverable manner; exiting". The server stays down until standalone.xml is edited or the file is removed
 * from the deployments directory.
 *
 * <h3>Severity</h3>
 * critical (after an ordinary refresh - a bundle refreshing its dependants, a management console / Gogo / JMX
 * "refresh packages", an update of a scanner-deployed bundle - the next restart of the server fails permanently; no
 * error is visible until that restart).
 *
 * <h3>Component</h3>
 * jbosgi umbrella, wildfly/subsystem: {@code org.jboss.as.osgi.service.BundleLifecycleIntegration
 * .BundleLifecycleImpl.RecreateCurrentRevisionPolicy} (startBundleRefresh -&gt; undeployRevision,
 * refreshCurrentRevision -&gt; {@code ServerDeploymentHelper.deploy(runtimeName, input)}).
 *
 * <h3>Found by</h3>
 * sd-srv-fuzz workstream (shutdown round), randomized lifecycle fuzzer against the patched WildFly 41.0.1 + jbosgi R8
 * server (JDK 25), seed 3 iteration 4: a probe bundle's activator called refreshBundles while scan-fz.b4.jar was
 * deployed by the scanner; server.log showed "(pool-13-thread-1) WFLYSRV0009: Undeployed "scan-fz.b4.jar"" and
 * "(pool-13-thread-2) WFLYSRV0010: Deployed "scan-fz.b4.jar"" (the BundleLifecycleIntegration client threads), and the
 * restart after the following SIGTERM died with WFLYCTL0212 / WFLYSRV0056. Shrunk to the two-bundle scenario of this
 * test (explore/sd-srv-fuzz/scen/s14b-async-refresh-scanner-bundle.json).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, {@code FrameworkWiring.refreshBundles} javadoc
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.wiring.html): "Refreshes the specified bundles. This
 * forces the update (replacement) or removal of packages exported by the specified bundles." - a refresh re-wires
 * bundles; it does not install anything new or change how a bundle is installed. 4.4.2 Bundle State: "When a bundle is
 * installed, it is stored in the persistent storage of the Framework and remains there until it is explicitly
 * uninstalled." - here the refresh changes what is restored at the next start (the server cannot start at all).
 * WildFly deployment scanner contract: scanner-managed deployments are added with persistent=false and an owner, and
 * are never written to the configuration.
 *
 * <h3>Code location</h3>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/BundleLifecycleIntegration.java:524-571
 * ({@code RecreateCurrentRevisionPolicy}): {@code startBundleRefresh} undeploys the deployment through the management
 * client (:543 undeployRevision), {@code refreshCurrentRevision} re-adds it with {@code ServerDeploymentHelper.deploy}
 * (:561-562), i.e. a full "add + deploy" composite with default {@code persistent=true}, regardless of whether the
 * original deployment was persistent (scanner deployments are added with persistent=false). Present since the
 * subsystem was forked (e4fe0811, 2013).
 *
 * <h3>Root cause</h3>
 * The refresh recreates the deployment instead of restarting the existing one, and recreates it with different
 * attributes (persistent, owner) than the original: the deployment silently changes from "owned by the deployment
 * scanner" to "management deployment", which collides with the scanner's own add at the next boot.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=ScannerDeploymentRefreshBreaksBootIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz in the local Maven repository, and on JDK 24+
 * the wildfly/03 surefire.security.args edit. Not an Arquillian test: it copies the testsuite server to
 * target/issues-scannerrefresh-server, starts it with port offset 2000 (management 11990), drops a bundle into
 * standalone/deployments, deploys a second bundle whose activator refreshes the first one from a background thread,
 * then restarts the server; it kills the servers it started. -Dissue.port.offset=n overrides the offset,
 * -Dissue.jboss.home=&lt;server dir&gt; runs it against another server build. Manually: drop any bundle into
 * standalone/deployments, refresh it (e.g. FrameworkWiring.refreshBundles from any bundle), restart.)
 *
 * <h3>Observed</h3>
 * JDK 25, WF41-osgi-r8 (jbosgi f025b420), patched WildFly 41.0.1 (run.log):
 * <pre>
 * java.lang.AssertionError: Expected the server to restart after a refresh of the scanner-deployed bundle
 *   refresh-scanned.jar, but the boot failed: the server process ended (exit code 1); standalone-osgi.xml now contains
 *   &lt;deployment name="refresh-scanned.jar"&gt; after the refresh; console:
 *   ERROR [org.jboss.as.controller.management-operation] (Controller Boot Thread) WFLYCTL0013: Operation ("add") failed
 *     - address: ([("deployment" =&gt; "refresh-scanned.jar")]) - Stage: (MODEL) - failure description: "WFLYCTL0212:
 *     Duplicate resource [(\"deployment\" =&gt; \"refresh-scanned.jar\")]"
 *   FATAL [org.jboss.as.server] (Controller Boot Thread) WFLYSRV0056: Server boot has failed in an unrecoverable
 *     manner; exiting.
 * </pre>
 *
 * <h3>Expected</h3>
 * After the refresh the scanner deployment is still a scanner (non-persistent) deployment, standalone.xml has no
 * {@code <deployment name="refresh-scanned.jar">}, and the server restarts normally with the bundle deployed by the
 * scanner.
 *
 * <h3>Reference behaviour</h3>
 * A plain jbosgi framework, Equinox and Felix refresh in place without changing how a bundle was installed. In the
 * server, management-deployed bundles keep their single deployment through a refresh (only the name collision with the
 * scanner breaks the boot).
 *
 * <h3>Suggested fix</h3>
 * Refresh deployments without recreating them: restart the existing deployment's services (e.g. via
 * {@code :redeploy} of the existing deployment resource, which keeps its persistent flag and owner), or, if it must be
 * recreated, read the original deployment's attributes ({@code persistent}, {@code owner}, unmanaged/managed content)
 * and add it back with the same ones. Related: shutdown/11 (the same policy redeploys a deployment that was removed
 * meanwhile), ee/10 and lifecycle/06 (update+refresh leaves -revN deployments): all in RecreateCurrentRevisionPolicy,
 * but each with a different defect; fixing shutdown/11's "deployment still exists" check does not fix this one.
 * Trial (fix.diff in the issue folder, never applied to a checkout): startBundleRefresh reads the deployment resource
 * before undeploying it, refreshCurrentRevision re-adds a non-persistent deployment with persistent=false and the same
 * owner (raw "add" with an input stream instead of ServerDeploymentHelper.deploy): this test passes (1 of 1).
 *
 * <h3>Regression vs WF41</h3>
 * no (measured: the same test against a WF41 server built from origin/WF41 77f95be6 on the same patched WildFly 41.0.1,
 * JDK 25, -Dissue.jboss.home=&lt;WF41 server&gt;: same failure, WFLYCTL0212/WFLYSRV0056 at the restart; only harness
 * adaptation: the jacc policy removed from standalone-osgi.xml as r8 42d6c470 does). The refresh policy predates WF41.
 */
public class ScannerDeploymentRefreshBreaksBootIssueTestCase {

    static final int OFFSET = Integer.getInteger("issue.port.offset", 2000);
    static final int MGMT_PORT = 9990 + OFFSET;
    static final String SCANNED = "refresh-scanned";
    static final String TRIGGER = "refresh-trigger";

    /** Refreshes the scanner-deployed bundle from a background thread (not an MSC thread) and writes a marker file */
    public static class RefreshingActivator implements BundleActivator {
        @Override
        public void start(final BundleContext context) {
            new Thread(() -> {
                try {
                    for (Bundle b : context.getBundles()) {
                        if (SCANNED.equals(b.getSymbolicName())) {
                            final File done = new File(System.getProperty("jboss.server.data.dir"), TRIGGER + ".done");
                            // lambda, not an anonymous class: only RefreshingActivator is packaged into the bundle
                            FrameworkListener listener = event -> {
                                try {
                                    Files.write(done.toPath(), String.valueOf(event.getType()).getBytes(StandardCharsets.UTF_8));
                                } catch (IOException ex) {
                                    // ignore
                                }
                            };
                            context.getBundle(0).adapt(FrameworkWiring.class).refreshBundles(Collections.singleton(b), listener);
                        }
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }, "refresh-trigger").start();
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
    public void testRestartAfterRefreshOfScannerDeployment() throws Exception {
        prepareServer("issues-scannerrefresh-server");
        assertEquals("Precondition: first boot", "ok", startServer("console-1.out"));

        // the deployment scanner deploys the first bundle
        File deployments = new File(home, "standalone/deployments");
        Files.write(new File(deployments, SCANNED + ".jar").toPath(), bundle(SCANNED, null));
        File deployed = new File(deployments, SCANNED + ".jar.deployed");
        long deadline = System.currentTimeMillis() + 60000;
        while (!deployed.exists() && System.currentTimeMillis() < deadline)
            Thread.sleep(200);
        assertTrue("Precondition: the scanner deployed " + SCANNED + ".jar", deployed.exists());
        String configBefore = new String(Files.readAllBytes(new File(home, "standalone/configuration/standalone-osgi.xml").toPath()), StandardCharsets.UTF_8);
        assertFalse("Precondition: scanner deployment not in the configuration", configBefore.contains("name=\"" + SCANNED + ".jar\""));

        // a second bundle refreshes it
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            ModelNode add = new ModelNode();
            add.get("operation").set("add");
            add.get("address").add("deployment", TRIGGER + ".jar");
            add.get("content").add().get("bytes").set(bundle(TRIGGER, RefreshingActivator.class));
            add.get("enabled").set(true);
            ModelNode result = client.execute(add);
            assertEquals("Precondition: deploy " + TRIGGER + ": " + result, "success", result.get("outcome").asString());
        }
        File done = new File(home, "standalone/data/" + TRIGGER + ".done");
        deadline = System.currentTimeMillis() + 60000;
        while (!done.exists() && System.currentTimeMillis() < deadline)
            Thread.sleep(200);
        assertTrue("Precondition: the refresh of " + SCANNED + " completed (FrameworkListener called) within 60 s", done.exists());
        Thread.sleep(1000);
        String config = new String(Files.readAllBytes(new File(home, "standalone/configuration/standalone-osgi.xml").toPath()), StandardCharsets.UTF_8);
        boolean persisted = config.contains("name=\"" + SCANNED + ".jar\"");

        // restart
        stopServer();
        String boot = startServer("console-2.out");
        String console = new String(Files.readAllBytes(new File(home, "console-2.out").toPath()), StandardCharsets.UTF_8);
        StringBuilder fatal = new StringBuilder();
        for (String line : console.split("\n"))
            if (line.contains("WFLYCTL0212") || line.contains("WFLYSRV0056"))
                fatal.append("  ").append(line.replaceAll("\u001B\\[[0-9;]*m", "").trim()).append('\n');
        assertEquals("Expected the server to restart after a refresh of the scanner-deployed bundle " + SCANNED + ".jar, but the boot "
                + boot + "; standalone-osgi.xml " + (persisted ? "now contains" : "does not contain") + " <deployment name=\"" + SCANNED
                + ".jar\"> after the refresh; console:\n" + fatal, "ok", boot);
        assertFalse("Expected the scanner deployment " + SCANNED + ".jar not to be persisted in standalone-osgi.xml by the refresh", persisted);
    }

    private static byte[] bundle(final String name, final Class<?> activator) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name + ".jar");
        if (activator != null)
            archive.addClasses(activator);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                if (activator != null) {
                    builder.addBundleActivator(activator);
                    builder.addImportPackages(BundleActivator.class, FrameworkWiring.class);
                }
                return builder.openStream();
            }
        });
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        archive.as(ZipExporter.class).exportTo(out);
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- server handling

    private File prepareServer(String name) throws IOException {
        // issue.jboss.home: run the same test against another server build (e.g. the WF41 baseline)
        File source = new File(System.getProperty("issue.jboss.home", System.getProperty("jboss.home", "target/wildfly-41.0.1.Final")));
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
        new File(target, "bin/standalone.sh").setExecutable(true);
        return home = target.getCanonicalFile();
    }

    /** @return "ok" when the server reports WFLYSRV0025/26, otherwise a description of what happened */
    private String startServer(String log) throws Exception {
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
                return "ok";
            if (process.isAlive() == false)
                return "failed: the server process ended (exit code " + process.exitValue() + ")";
            Thread.sleep(500);
        }
        return "did not finish within 180 s";
    }

    private void stopServer() throws Exception {
        if (process == null)
            return;
        // only the processes this test started: the script and its children
        List<ProcessHandle> handles = new ArrayList<ProcessHandle>();
        process.toHandle().descendants().forEach(handles::add);
        handles.add(process.toHandle());
        if (process.isAlive()) {
            try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
                ModelNode op = new ModelNode();
                op.get("operation").set("shutdown");
                client.execute(op);
            } catch (Exception ex) {
                // already down
            }
        }
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
