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


import jakarta.annotation.Resource;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.dmr.ModelNode;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.SynchronousBundleListener;

/**
 * Server :shutdown uninstalls every deployed bundle (transiently): bundle listeners get UNRESOLVED and UNINSTALLED for
 * bundles that stay installed, and while EE deployments are still being torn down the bundles are already gone from
 * getBundles() / in state UNINSTALLED.
 *
 * <h3>Severity</h3>
 * major (spec violation every bundle listener sees on every server shutdown: BundleListener/BundleTracker based
 * extenders and applications treat UNINSTALLED as a permanent removal and run their uninstall clean-up - e.g. delete
 * per-bundle caches, data or registrations - for bundles that come back at the next boot; EE teardown code
 * (contextDestroyed, @PreDestroy) cannot find or use the deployed bundles any more).
 *
 * <h3>Component</h3>
 * jbosgi umbrella, wildfly/subsystem: {@code org.jboss.as.osgi.deployment.BundleInstallProcessor.undeploy} with
 * {@code getUninstallOptions} (STOP_TRANSIENT only keeps the storage; the bundle still goes through
 * {@code UserBundleState.uninstallInternal}).
 *
 * <h3>Found by</h3>
 * sd-srv-ee workstream (shutdown round), mixed EE + OSGi teardown on the patched WildFly 41.0.1 + jbosgi R8 server
 * (JDK 25): a WAR's @PreDestroy and contextDestroyed calling getBundles() during :shutdown no longer found the deployed
 * bundles ("no bundle sdee-svc", scenarios s07/s13 of explore/sd-srv-ee/scen/batch1.py); a SynchronousBundleListener
 * registered through the WAR's (system) BundleContext then recorded, for each deployed bundle, STOPPING, STOPPED,
 * UNRESOLVED, UNINSTALLED at every :shutdown.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, {@code Framework.stop()} javadoc
 * (https://docs.osgi.org/javadoc/osgi.core/8.0.0/org/osgi/framework/launch/Framework.html#stop--): "1. This
 * Framework's state is set to STOPPING. 2. All installed bundles must be stopped without changing each bundle's
 * persistent autostart setting. The start level of this Framework is moved to start level zero (0) ... 3. Unregister
 * all services registered by this Framework. 4. Event handling is disabled. 5. This Framework's state is set to
 * RESOLVED. 6. All resources held by this Framework are released." - stopping the framework (which a server shutdown
 * is, for the bundles) stops bundles; it does not uninstall them. BundleEvent.UNINSTALLED javadoc: "The bundle has been
 * uninstalled." Bundle.uninstall() javadoc step 5: "This bundle and any persistent storage area provided for this
 * bundle by the Framework are removed." - none of that is true here.
 *
 * <h3>Code location</h3>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/deployment/BundleInstallProcessor.java:94-118 (f025b420): the
 * undeploy of every bundle deployment calls {@code bundleManager.uninstallBundle(bundle, options)}; on server stop
 * {@code getUninstallOptions} (lines 153-158) only adds STOP_TRANSIENT, which jbosgi-framework
 * {@code UserBundleState.uninstallInternal} (core, 443-518, d7b20d08) uses only to skip the storage deletion: it still
 * unresolves the bundle (UNRESOLVED event), sets UNINSTALLED and fires BundleEvent.UNINSTALLED. Pre-existing (same
 * code on origin/WF41); R8 54b65e85 only wrapped it with setDeploymentUndeploy.
 *
 * <h3>Root cause</h3>
 * The subsystem maps "deployment service stops" to "bundle uninstall" also when the reason is the server stopping,
 * instead of treating the server stop as a framework stop.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=ShutdownUninstallsDeployedBundlesIssueTestCase
 * </pre>
 * (Prerequisites as for every umbrella testsuite run: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz in the
 * local repository and, on JDK 24+, the surefire.security.args edit of issue wildfly/03. Scripted test, not Arquillian:
 * it copies the testsuite server to target/issues-shutdownuninstall-server and starts it with port offset 2100
 * (management 12090; override with -Dissue.port.offset=n), deploys shutdown-target.jar and a WAR whose
 * ServletContextListener registers a SynchronousBundleListener through its @Resource BundleContext (the system
 * context, so the listener outlives the WAR), runs :shutdown and reads the recorded events of shutdown-target from
 * standalone/data/issue-shutdown-bundle-events.txt. Bounded waits; kills only what it started. Run time about 6 s.)
 *
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: Expected a server shutdown (which stops the OSGi framework) to only stop the deployed bundle -
 *   bundle events STOPPING, STOPPED - and to keep it installed, but the bundle events of shutdown-target during
 *   :shutdown were [STOPPING, STOPPED, UNRESOLVED, UNINSTALLED]
 * </pre>
 * At the next boot the bundle is INSTALLED again (with its storage), so listeners see INSTALLED/RESOLVED/STARTED for a
 * bundle they were told was uninstalled.
 *
 * <h3>Expected</h3>
 * Only STOPPING and STOPPED during the shutdown; the bundles stay installed (getState() RESOLVED) until the framework
 * goes away.
 *
 * <h3>Reference behaviour</h3>
 * A plain jbosgi framework (and Equinox/Felix) fire only STOPPING/STOPPED on Framework.stop().
 *
 * <h3>Suggested fix</h3>
 * In BundleInstallProcessor.undeploy, when the server is stopping, stop the bundle transiently and do not uninstall it
 * (the framework instance is discarded anyway). Trial (diff in the issue folder, applied only to a copy of the server's
 * subsystem jar): {@code if ((options & Bundle.STOP_TRANSIENT) != 0) { stopBundle(bundle, STOP_TRANSIENT) if ACTIVE or
 * STARTING; return; }} - the test passes (events [STOPPING, STOPPED]); a restart sanity run with the trial (bundles,
 * a persistently stopped bundle, a WAR and a WAB, two restarts) kept every state and served both web contexts.
 * :reload needs the same treatment once lifecycle/01 makes it keep the storage.
 * Related: lifecycle/01 (ReloadUninstallsDeployedBundlesIssueTestCase: on :reload the same uninstall is not even
 * transient and deletes the storage - a different fix in getUninstallOptions; fixing lifecycle/01 alone keeps these
 * events), connect/27 (the framework itself is never stopped on server stop).
 *
 * <h3>Regression vs WF41</h3>
 * no (measured: the same class against a copy of the WF41 server built from origin/WF41 77f95be6 on the same patched
 * WildFly 41.0.1, jbosgi-framework 6.6.0, JDK 25, offset 2150: same events [STOPPING, STOPPED, UNRESOLVED,
 * UNINSTALLED]; harness adaptation only: jacc {@code <policy>} removed from standalone-osgi.xml as r8 42d6c470 does).
 */
public class ShutdownUninstallsDeployedBundlesIssueTestCase {

    static final int OFFSET = Integer.getInteger("issue.port.offset", 2100);
    static final int MGMT_PORT = 9990 + OFFSET;
    static final int BLOCKING_TIMEOUT = 60;
    static final int SHUTDOWN_WAIT = 45;

    static final String TARGET = "shutdown-target";
    static final String EVENTS = "issue-shutdown-bundle-events.txt";

    /** Records every bundle event of {@link #TARGET} in jboss.server.data.dir (registered through the system context) */
    public static class EventRecorder implements SynchronousBundleListener {
        @Override
        public void bundleChanged(BundleEvent event) {
            if (!TARGET.equals(event.getBundle().getSymbolicName()))
                return;
            String type;
            switch (event.getType()) {
                case BundleEvent.INSTALLED: type = "INSTALLED"; break;
                case BundleEvent.RESOLVED: type = "RESOLVED"; break;
                case BundleEvent.STARTING: type = "STARTING"; break;
                case BundleEvent.STARTED: type = "STARTED"; break;
                case BundleEvent.STOPPING: type = "STOPPING"; break;
                case BundleEvent.STOPPED: type = "STOPPED"; break;
                case BundleEvent.UNRESOLVED: type = "UNRESOLVED"; break;
                case BundleEvent.UNINSTALLED: type = "UNINSTALLED"; break;
                default: type = String.valueOf(event.getType());
            }
            try (java.io.FileWriter out = new java.io.FileWriter(new File(System.getProperty("jboss.server.data.dir"), EVENTS), true)) {
                out.write(type + "\n");
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    /** A web application that observes bundle events with its injected (system) BundleContext */
    @WebListener
    public static class ObservingListener implements ServletContextListener {
        @Resource
        BundleContext context;

        @Override
        public void contextInitialized(ServletContextEvent sce) {
            context.addBundleListener(new EventRecorder());
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce) {
        }
    }

    private File home;
    private Process process;

    @After
    public void tearDown() throws Exception {
        killServer();
    }

    @Test
    public void testShutdownDoesNotUninstallBundles() throws Exception {
        prepareServer("issues-shutdownuninstall-server");
        startServer("console.out");
        File events = new File(home, "standalone/data/" + EVENTS);
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            ModelNode result = deploy(client, TARGET + ".jar", targetBundle());
            assertTrue("Precondition: deploy " + TARGET + ": " + result, "success".equals(result.get("outcome").asString()));
            WebArchive war = ShrinkWrap.create(WebArchive.class, "shutdown-observer.war");
            war.addClasses(ObservingListener.class, EventRecorder.class);
            result = deploy(client, "shutdown-observer.war", export(war));
            assertTrue("Precondition: deploy the observing WAR: " + result, "success".equals(result.get("outcome").asString()));
            assertTrue("Precondition: " + TARGET + " ACTIVE", "ACTIVE".equals(bundleState(client, TARGET + ".jar")));
        }
        Files.deleteIfExists(events.toPath());
        shutdownOperation();
        boolean stopped = waitForExit(SHUTDOWN_WAIT);
        assertTrue("Precondition: :shutdown completes", stopped);
        List<String> seen = events.exists() ? Files.readAllLines(events.toPath()) : new ArrayList<String>();
        System.out.println("Bundle events of " + TARGET + " during :shutdown: " + seen);
        assertTrue("Expected a server shutdown (which stops the OSGi framework) to only stop the deployed bundle - bundle events "
                + "STOPPING, STOPPED - and to keep it installed, but the bundle events of " + TARGET + " during :shutdown were " + seen,
                !seen.contains("UNINSTALLED") && !seen.contains("UNRESOLVED") && seen.contains("STOPPED"));
    }

    private byte[] targetBundle() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, TARGET + ".jar");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(TARGET);
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
