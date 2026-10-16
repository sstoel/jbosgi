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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
import java.util.List;
import java.util.concurrent.TimeUnit;


import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.dmr.ModelNode;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * Bundle.uninstall() of a deployed bundle that another bundle still uses is undone by the next server restart: the
 * deployment is never removed, and after a clean :shutdown and boot the "uninstalled" bundle is installed again
 * (fresh id reuse, empty data area) and started.
 *
 * <h3>Severity</h3>
 * major (wrong persistent state after a shutdown: an uninstalled bundle - e.g. one removed by a provisioning/management
 * bundle because it is obsolete or broken - comes back and runs again after every restart, with a new, empty
 * persistent data area; the management model shows the deployment as deployed while the bundle is uninstalled).
 *
 * <h3>Component</h3>
 * jbosgi umbrella, wildfly/subsystem ({@code BundleLifecycleIntegration} / {@code BundleInstallProcessor}) together with
 * jbosgi-framework core {@code UserBundleState.uninstallInternal}: the server deployment of a bundle is only removed
 * through {@code BundleLifecycle.removeRevision}, which the framework calls at uninstall time only when the bundle
 * is not in use.
 *
 * <h3>Found by</h3>
 * sd-srv-ee workstream (shutdown round), restart checks of mixed deployments on the patched WildFly 41.0.1 + jbosgi R8
 * server (JDK 25), scenario u2 (explore/sd-srv-ee/scen/batch8.py): exporter sdee-svc.jar used by importer
 * sdee-svc2.jar, Bundle.uninstall() of the exporter from a servlet request; after a restart the exporter was ACTIVE again.
 * Control u3: uninstalling a bundle nobody uses removes its deployment and it stays away after the restart.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, {@code Bundle.uninstall()} javadoc
 * (https://docs.osgi.org/javadoc/osgi.core/8.0.0/org/osgi/framework/Bundle.html#uninstall--): "This method causes the
 * Framework to notify other bundles that this bundle is being uninstalled, and then puts this bundle into the
 * UNINSTALLED state. The Framework must remove any resources related to this bundle that it is able to remove. If this
 * bundle has exported any packages, the Framework must continue to make these packages available to their importing
 * bundles until the FrameworkWiring.refreshBundles method has been called or the Framework is relaunched." and step 5
 * "This bundle and any persistent storage area provided for this bundle by the Framework are removed." - after a
 * relaunch the bundle must be gone; a server restart relaunches the framework.
 *
 * <h3>Code location</h3>
 * <ul>
 * <li>jbosgi-framework core/src/main/java/org/jboss/osgi/framework/internal/UserBundleState.java:467-490 (d7b20d08):
 * with active wires the bundle is set UNINSTALLED and its storage is deleted (482-485), but
 * {@code bundleManager.removeBundle(this, options)} (and with it {@code BundleLifecycle.removeRevision}) is skipped
 * (487-490); it happens later only when the revision is released (refresh, FrameworkWiringImpl:212, or the
 * "uninstalled bundles not in use any more" loop of a later uninstall, 493-512).</li>
 * <li>jbosgi wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/BundleLifecycleIntegration.java:336-373
 * (f025b420): {@code removeRevision -> undeployRevision -> server.undeploy(runtimeName)} is the only place where the
 * deployment of an uninstalled bundle is removed; nothing records "uninstalled while in use".</li>
 * <li>wildfly/subsystem/.../deployment/BundleInstallProcessor.java:94-118: at :shutdown the undeploy of the stale
 * deployment does nothing for the UNINSTALLED bundle; at the next boot BundleInstallProcessor.deploy (73-91) finds no
 * storage state (deleted at uninstall) and installs the bundle as a new one, autostarted.</li>
 * </ul>
 *
 * <h3>Root cause</h3>
 * The removal-pending state of a deployed bundle exists only in memory. The persistent model still contains its
 * deployment, and the framework storage that could tell "uninstalled" was deleted, so the framework relaunch at server
 * start installs it again instead of dropping it.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=UninstalledInUseBundleRestoredAfterRestartIssueTestCase
 * </pre>
 * (Prerequisites as for every umbrella testsuite run: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz in the
 * local repository and, on JDK 24+, the surefire.security.args edit of issue wildfly/03. Scripted test, not Arquillian:
 * it copies the testsuite server to target/issues-uninstallrestart-server, starts it with port offset 2100 (management
 * 12090; override with -Dissue.port.offset=n), deploys uninstalled-exporter.jar (exports issue.uninstalled.api),
 * uninstalled-importer.jar (imports it, ACTIVE, i.e. wired) and uninstalling.jar whose activator calls
 * Bundle.uninstall() on the exporter once, shuts the server down with :shutdown, starts it again and reads
 * /subsystem=osgi/bundle=uninstalled-exporter.jar. All waits bounded; only the processes it started are killed.
 * Run time about 10 s.)
 *
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: Expected a bundle uninstalled with Bundle.uninstall() to stay uninstalled after the server
 *   (and so the framework) is restarted, but it was installed again. After Bundle.uninstall() of the exporter: bundle
 *   resource WFLYCTL0216: Management resource '[("subsystem" =&gt; "osgi"), ("bundle" =&gt; "uninstalled-exporter.jar")]'
 *   not found, deployment present (status OK). After a clean :shutdown and a restart: bundle resource ACTIVE,
 *   deployment present (status OK), importer ACTIVE.
 * </pre>
 * Manual variant: the same with Bundle.uninstall() called from a servlet (HttpService) request; the exporter's storage
 * directory data/osgi-store/bundle-27 is deleted at uninstall, and after the restart a bundle with the same id 27
 * (connect/14) and an empty data area is ACTIVE again.
 *
 * <h3>Expected</h3>
 * After the restart the uninstalled bundle is not installed (no bundle resource), and its deployment is removed from the
 * model (at the latest when the framework is relaunched).
 *
 * <h3>Reference behaviour</h3>
 * A plain jbosgi framework (and Equinox/Felix) relaunched on the same storage does not reinstall a bundle uninstalled
 * while in use: its storage is gone and nothing else records it. In the server the same uninstall of a bundle nobody
 * uses works (control u3: the deployment is undeployed at once and stays away after the restart).
 *
 * <h3>Suggested fix</h3>
 * Make "uninstalled while in use" persistent for deployed bundles: e.g. let the framework notify the BundleLifecycle on
 * every uninstall (also with active wires), and let BundleLifecycleIntegration remove the deployment from the
 * persistent configuration while keeping the revision's module/content alive until it is released (see wildfly/28 for
 * the content part); or keep a persistent "uninstalled" marker in the bundle storage instead of deleting it at once, and
 * let BundleInstallProcessor at boot drop (and asynchronously undeploy) a deployment whose bundle is marked uninstalled.
 * Not trialled (needs changes in two repositories).
 * Related: connect/25 (refreshBundles does not release such a bundle either - the stale deployment also survives a
 * refresh), wildfly/28 (content of a removal-pending exporter deleted at once), ee/01 (uninstall of a bundle a WAR
 * depends on: the server undeploy is rolled back), lifecycle/01 (:reload uninstalls all deployed bundles), connect/14
 * (ids reused). None of them covers the restart: their fixes do not remove the stale deployment.
 *
 * <h3>Regression vs WF41</h3>
 * no (measured: the same class against a copy of the WF41 server built from origin/WF41 77f95be6 on the same patched
 * WildFly 41.0.1, jbosgi-framework 6.6.0, JDK 25, offset 2150: same failure "bundle resource ACTIVE, deployment present";
 * harness adaptation only: jacc {@code <policy>} removed from standalone-osgi.xml as r8 42d6c470 does).
 */
public class UninstalledInUseBundleRestoredAfterRestartIssueTestCase {

    static final int OFFSET = Integer.getInteger("issue.port.offset", 2100);
    static final int MGMT_PORT = 9990 + OFFSET;
    static final int BLOCKING_TIMEOUT = 60;
    static final int SHUTDOWN_WAIT = 45;

    static final String EXPORTER = "uninstalled-exporter";

    /** Uninstalls the exporter when it starts, as a provisioning or management bundle would do */
    public static class UninstallingActivator implements BundleActivator {
        @Override
        public void start(BundleContext context) throws Exception {
            // only once: this bundle is a persistent deployment too and is started again after the restart
            java.io.File done = context.getDataFile("done");
            if (done.exists())
                return;
            done.createNewFile();
            for (Bundle bundle : context.getBundles()) {
                if (EXPORTER.equals(bundle.getSymbolicName())) {
                    System.out.println("ISSUE uninstalling " + bundle);
                    bundle.uninstall();
                    System.out.println("ISSUE uninstalled " + bundle + " state " + bundle.getState());
                }
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
        killServer();
    }

    @Test
    public void testUninstallOfUsedBundleSurvivesRestart() throws Exception {
        prepareServer("issues-uninstallrestart-server");
        startServer("console.out");
        StringBuilder report = new StringBuilder();
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            ModelNode result = deploy(client, EXPORTER + ".jar", bundle(EXPORTER, "Export-Package", false));
            assertTrue("Precondition: deploy exporter: " + result, "success".equals(result.get("outcome").asString()));
            result = deploy(client, "uninstalled-importer.jar", bundle("uninstalled-importer", "Import-Package", false));
            assertTrue("Precondition: deploy importer: " + result, "success".equals(result.get("outcome").asString()));
            assertTrue("Precondition: importer ACTIVE (wired to the exporter)", "ACTIVE".equals(bundleState(client, "uninstalled-importer.jar")));
            // Bundle.uninstall() of the exporter, which the importer uses (removal pending)
            result = deploy(client, "uninstalling.jar", bundle("uninstalling", null, true));
            assertTrue("Precondition: deploy the bundle that uninstalls the exporter: " + result, "success".equals(result.get("outcome").asString()));
            String uninstalledState = bundleState(client, EXPORTER + ".jar");
            report.append("After Bundle.uninstall() of the exporter: bundle resource ").append(uninstalledState)
                    .append(", deployment ").append(deploymentState(client, EXPORTER + ".jar")).append(". ");
            // The exporter stays removal pending while the importer uses it: its bundle resource is gone (WFLYCTL0216) or
            // has no attributes (undefined), depending on whether the framework still lists the uninstalled bundle
            assertTrue("Precondition: the exporter is uninstalled (no bundle resource, or one without attributes), but " + report,
                    uninstalledState.startsWith("WFLYCTL0216") || "undefined".equals(uninstalledState));
        }
        shutdownOperation();
        assertTrue("Precondition: :shutdown completes", waitForExit(SHUTDOWN_WAIT));
        process = null;
        startServer("console-restart.out");
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            String state = bundleState(client, EXPORTER + ".jar");
            report.append("After a clean :shutdown and a restart: bundle resource ").append(state).append(", deployment ")
                    .append(deploymentState(client, EXPORTER + ".jar")).append(", importer ").append(bundleState(client, "uninstalled-importer.jar")).append(".");
            System.out.println(report);
            assertTrue("Expected a bundle uninstalled with Bundle.uninstall() to stay uninstalled after the server (and so the framework) is "
                    + "restarted, but it was installed again. " + report, state.startsWith("WFLYCTL0216"));
        }
    }

    private byte[] bundle(final String bsn, final String header, final boolean uninstaller) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, bsn + ".jar");
        if (uninstaller)
            archive.addClasses(UninstallingActivator.class);
        else
            archive.add(new StringAsset("api"), "issue/uninstalled/api/api.txt");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(bsn);
                if ("Export-Package".equals(header))
                    builder.addExportPackages("issue.uninstalled.api");
                if ("Import-Package".equals(header))
                    builder.addImportPackages("issue.uninstalled.api");
                if (uninstaller) {
                    builder.addBundleActivator(UninstallingActivator.class);
                    builder.addImportPackages(BundleActivator.class);
                }
                return builder.openStream();
            }
        });
        return export(archive);
    }

    private static String bundleState(ModelControllerClient client, String location) throws IOException {
        ModelNode read = new ModelNode();
        read.get("operation").set("read-attribute");
        read.get("address").add("subsystem", "osgi").add("bundle", location);
        read.get("name").set("state");
        ModelNode res = client.execute(read);
        return res.get("result").isDefined() ? res.get("result").asString() : res.get("failure-description").asString();
    }

    private static String deploymentState(ModelControllerClient client, String name) throws IOException {
        ModelNode read = new ModelNode();
        read.get("operation").set("read-attribute");
        read.get("address").add("deployment", name);
        read.get("name").set("status");
        ModelNode res = client.execute(read);
        return res.get("result").isDefined() ? "present (status " + res.get("result").asString() + ")" : "absent";
    }

    private static byte[] export(org.jboss.shrinkwrap.api.Archive<?> archive) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        archive.as(ZipExporter.class).exportTo(out);
        return out.toByteArray();
    }

    private static ModelNode deploy(ModelControllerClient client, String name, byte[] bytes) throws IOException {
        ModelNode add = new ModelNode();
        add.get("operation").set("add");
        add.get("address").add("deployment", name);
        add.get("content").add().get("bytes").set(bytes);
        add.get("enabled").set(true);
        return client.execute(add);
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
        new File(target, "bin/standalone.sh").setExecutable(true);
        return home = target.getCanonicalFile();
    }

    private void startServer(String log) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(new File(home, "bin/standalone.sh").getPath(), "-c", "standalone-osgi.xml",
                "-Djboss.socket.binding.port-offset=" + OFFSET, "-Djboss.as.management.blocking.timeout=" + BLOCKING_TIMEOUT);
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

    private void shutdownOperation() {
        // the answer may never come when the shutdown hangs, so do not wait for it in the test thread
        Thread thread = new Thread(() -> {
            try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
                ModelNode op = new ModelNode();
                op.get("operation").set("shutdown");
                client.execute(op);
            } catch (Exception ex) {
                // connection closed by the exiting server
            }
        }, "issue-shutdown-op");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean waitForExit(int seconds) throws InterruptedException {
        return process.waitFor(seconds, TimeUnit.SECONDS);
    }

    private String jstack() {
        StringBuilder sb = new StringBuilder();
        try {
            for (ProcessHandle handle : process.toHandle().descendants().toArray(ProcessHandle[]::new)) {
                String cmd = handle.info().command().orElse("");
                if (!cmd.endsWith("java"))
                    continue;
                File jstack = new File(System.getProperty("java.home"), "bin/jstack");
                Process p = new ProcessBuilder(jstack.getPath(), "-l", String.valueOf(handle.pid())).redirectErrorStream(true).start();
                byte[] bytes = p.getInputStream().readAllBytes();
                p.waitFor(30, TimeUnit.SECONDS);
                String full = new String(bytes, StandardCharsets.UTF_8);
                Files.write(new File(home, "jstack-shutdown-hang.txt").toPath(), bytes);
                for (String block : full.split("\n\n")) {
                    if (block.contains("awaitStability") || block.contains("BootstrapImpl$ShutdownHook") || block.contains("SystemExiter"))
                        sb.append(block.trim()).append("\n\n");
                }
                sb.append("(full dump: ").append(new File(home, "jstack-shutdown-hang.txt")).append(")");
            }
        } catch (Exception ex) {
            sb.append("jstack failed: ").append(ex);
        }
        return sb.toString();
    }

    private void killServer() throws Exception {
        if (process == null)
            return;
        // only the processes this test started: the script and its children
        List<ProcessHandle> handles = new ArrayList<ProcessHandle>();
        process.toHandle().descendants().forEach(handles::add);
        handles.add(process.toHandle());
        if (process.isAlive()) {
            shutdownOperation();
            process.waitFor(30, TimeUnit.SECONDS);
        }
        for (ProcessHandle handle : handles) {
            if (handle.isAlive())
                handle.destroyForcibly();
        }
        for (ProcessHandle handle : handles) {
            long deadline = System.currentTimeMillis() + 10000;
            while (handle.isAlive() && System.currentTimeMillis() < deadline)
                Thread.sleep(100);
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
