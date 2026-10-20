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
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * A server shutdown (SIGTERM, i.e. {@code docker stop}, systemd, Ctrl-C) that arrives while the framework is still
 * raising its start level at boot does not stop that raise: the boot start-level loop keeps starting bundles in
 * parallel with the shutdown, including bundles that the shutdown has just stopped, so their
 * {@code BundleActivator.start} runs again while the server goes down and the shutdown waits for every remaining
 * activator start of the boot.
 *
 * <h3>Severity</h3>
 * major. Any server stop in the boot window (a container restarted by an orchestrator, a failed health check, an
 * operator's Ctrl-C) restarts deployed bundles after their {@code BundleActivator.stop}: the activator opens its
 * resources (threads, sockets, schedulers, registrations) a second time while the server tears down, and is only
 * stopped again when the shutdown uninstalls the deployment (shutdown/16); the shutdown itself is delayed by all
 * activator starts the boot had not done yet (serialized on one MSC thread), so a slow start at a higher start level
 * pushes the stop past the 10 s grace of {@code docker stop}. Every bundle the loop reaches after its deployment was
 * removed adds an ERROR (JBOSGI011026 with JBOSGI011210 "Bundle already uninstalled" or JBOSGI011254/JBOSGI011217
 * "Cannot load module").
 *
 * <h3>Component</h3>
 * jbosgi-framework core: {@code org.jboss.osgi.framework.internal.FrameworkActive.createServiceValue} (runs
 * {@code StartLevelManagerImpl.increaseFrameworkStartLevel} synchronously inside the MSC start of the FrameworkActive
 * service) and {@code StartLevelManagerImpl.increaseFrameworkStartLevel} (starts every persistently started bundle of
 * each level without any abort condition); jbosgi umbrella wildfly/subsystem:
 * {@code org.jboss.as.osgi.deployment.BundleActivateProcessor.BundleActivateService.stop} (stops the deployed bundle
 * with {@code STOP_TRANSIENT}, so its autostart setting stays "started" and the running raise starts it again).
 *
 * <h3>Found by</h3>
 * sd-soak workstream (shutdown round), boot/shutdown soak with a shutdown-trigger delay sweep: 24 deployed bundles,
 * framework beginning start level 5, activators with 0-3 s start/stop delays, SIGTERM 1-6 s after the process start.
 * In those cycles the console shows the deployment's {@code Bundle stopped: soak-c1} on the shutdown's MSC thread and
 * one second later {@code start-begin soak-c1} / {@code Bundle started: soak-c1} from
 * {@code StartLevelManagerImpl.increaseFrameworkStartLevel(StartLevelManagerImpl.java:168) <-
 * FrameworkActive.createServiceValue(FrameworkActive.java:151)}; soak-a12, soak-c2, soak-c6, soak-ds2 the same; the
 * shutdown took 17 s instead of 10-12 s (soak evidence: round-shutdown/issues/17-startlevel-raise-restarts-stopped-bundle/soak-evidence/).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.2.6 Stopping a Framework: "When the framework is shut down, it first enters the STOPPING state. All
 * ACTIVE bundles are stopped ... The framework then moves to start level 0". 9.3.3 Shutdown Sequence: "When the
 * Framework shuts down, the requested start level must be set to zero. The Framework must then follow the process
 * described in Changing the Active Start Level to make the active start level equal to zero."
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.startlevel.html) A pending move to a higher level is
 * superseded by the move to zero; no bundle may be started by it once the stop began.
 *
 * <h3>Code location</h3>
 * <ul>
 * <li>jbosgi-framework core/src/main/java/org/jboss/osgi/framework/internal/FrameworkActive.java:151
 * ({@code startLevelPlugin.increaseFrameworkStartLevel(getBeginningStartLevel(bundleManager))} in
 * {@code createServiceValue}, i.e. in the MSC start task; MSC cannot stop FrameworkActive before this returns, but it
 * stops every deployment meanwhile).</li>
 * <li>core/src/main/java/org/jboss/osgi/framework/internal/StartLevelManagerImpl.java:139-176
 * ({@code increaseFrameworkStartLevel}: iterates a snapshot of all bundles per level and calls
 * {@code bundle.start(START_TRANSIENT)} for every one with {@code state.isStarted()}; no check of the framework/server
 * state, of the bundle state, or of a stop request).</li>
 * <li>jbosgi wildfly/subsystem/src/main/java/org/jboss/as/osgi/deployment/BundleActivateProcessor.java:149
 * ({@code bundleManager.stopBundle(bundle, Bundle.STOP_TRANSIENT)} in {@code BundleActivateService.stop}).</li>
 * </ul>
 * Unchanged since WF41 (the same code in jbosgi-framework origin/WF41).
 *
 * <h3>Root cause</h3>
 * The boot start-level raise is not cancellable: the server shutdown has no way to stop it, and the framework never
 * enters STOPPING during a server shutdown (connect/27), so the raise runs to the beginning start level while the
 * deployments are being stopped and uninstalled.<br>
 * Related: shutdown/04 (StartLevelChangeDuringShutdownIssueTestCase: a queued start level change runs after
 * {@code Framework.stop()} reached level 0) and connect/27 (ReloadLeaksFrameworkIssueTestCase: the server never stops
 * the framework). Neither fix removes this defect: shutdown/04's fix discards start level changes once the framework
 * is STOPPING, and connect/27's suggested fix stops the framework in {@code FrameworkBootstrapService.stop}, which MSC
 * runs only after all deployments (its dependents) are stopped, i.e. after the raise has already restarted them; the
 * fix site here is the raise itself (abortable when the server/container stops) or the order of the server shutdown
 * (framework to level 0 first, before the deployments are torn down).<br>
 * Also related: lifecycle/02 (ShutdownStopOrderIssueTestCase: the server shutdown stops deployed bundles in MSC
 * teardown order, not by descending start level) and ee/20 (BootActivatorStartedTwiceIssueTestCase: at boot the
 * deployment Activate service and the start level raise both start a bundle). The fix of the second variant above
 * (lower the framework start level before tearing down deployments) also fixes lifecycle/02; design it together with
 * ee/20's fix, which decides who owns bundle activation at boot.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=StartLevelRaiseRestartsStoppedBundleIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork in the local Maven
 * repository; on JDK 24+ the testsuite pom edit of wildfly/03. The test is not an Arquillian test: it copies the
 * testsuite server to target/issues-shutdown-raise-server and starts it itself with port offset 2300 (management 12290),
 * override with {@code -Dissue.port.offset=<n>}; it kills only the processes it started.)
 * (The runs below used {@code -Dissue.port.offset=2340} because a soak server used 2300.)
 * Deterministic: two deployed bundles, both at start level 1. {@code raise-blocker} (lower bundle id) is started by the
 * boot raise; when armed (a marker file in the data dir, created by the test before the second boot) its activator makes
 * sure {@code raise-victim} is ACTIVE (started by its deployment, or started by the blocker if the raise was faster) and
 * then waits - up to 60 s - until the shutdown has stopped the victim, standing in for any activator start that takes
 * some seconds. The test sends SIGTERM to the server JVM once the blocker reports "armed", and checks the order of the
 * victim's activator calls in the server console.
 *
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: Expected a bundle stopped by the server shutdown (SIGTERM during the boot start-level raise)
 *   not to be started again, but the victim's activator calls after SIGTERM were [victim stop [MSC service thread 1-9],
 *   blocker released, victim state 16 [MSC service thread 1-5], victim stop end [MSC service thread 1-9],
 *   victim start via StartLevelManagerImpl.increaseFrameworkStartLevel (boot start-level raise) [MSC service thread 1-7],
 *   victim stop [MSC service thread 1-3], victim stop end [MSC service thread 1-3]] (a start after the stop: true);
 *   the JVM exited 4478 ms after SIGTERM
 * raise thread in the console:
 *   12:16:06,670 (Thread-1) WFLYSRV0220: Server shutdown has been requested via an OS signal
 *   12:16:08,687 (MSC service thread 1-9) JBOSGI011003: Bundle stopped: raise-victim:0.0.0
 *   12:16:08,688 (MSC service thread 1-7) RAISE-PROBE victim start via StartLevelManagerImpl.increaseFrameworkStartLevel
 *   12:16:08,688 (MSC service thread 1-7) JBOSGI011002: Bundle started: raise-victim:0.0.0
 *   12:16:10,702 (MSC service thread 1-3) JBOSGI011003: Bundle stopped: raise-victim:0.0.0   (second stop, at the uninstall)
 *   12:16:10,816 WFLYSRV0050: ... stopped in 4144ms
 * </pre>
 * 3 of 3 runs on the R8 server (JDK 25), identical sequence. Without the 2 s stop of the victim the raise reaches the
 * victim after the shutdown already uninstalled it and logs {@code JBOSGI011026: Framework Error:
 * IllegalStateException: JBOSGI011210: Bundle already uninstalled: raise-victim} instead (also seen in the soak).
 *
 * <h3>Expected</h3>
 * After the shutdown stopped a bundle, nothing starts it again: the victim's activator sees start, stop and no further
 * start; the boot raise is abandoned when the server stops.
 *
 * <h3>Reference behaviour</h3>
 * A plain jbosgi framework (and Equinox/Felix) moves to start level 0 at {@code Framework.stop()}; a raise that is still
 * running is ended by the stop (shutdown/04 covers the jbosgi-framework queued-request case).
 *
 * <h3>Suggested fix (not applied)</h3>
 * Make the boot raise abortable and abort it when the server stops: check a "stopping" flag (set by a stop listener on
 * the container/FrameworkActive, or by the subsystem at the start of the server shutdown) in the level and bundle loops
 * of {@code increaseFrameworkStartLevel}, and skip bundles that are no longer RESOLVED/STARTING (stopped or uninstalled
 * meanwhile); better, stop the framework (level 0) first when the server shuts down, before the deployments' services are
 * stopped (connect/27), and run the boot raise outside the MSC start task.
 *
 * Trial (not applied; fix.diff in the issue folder): {@code increaseFrameworkStartLevel} stops raising once
 * {@code bundleManager.hasStopped()} or the hosting {@code ServiceContainer.isShutdown()}: the test passes 2 of 2 (the
 * victim gets start, stop and no further start; the server stops 2.1 s after SIGTERM instead of 4.1 s).
 *
 * <h3>Regression vs WF41</h3>
 * no (measured: the same test against the WF41 server tree built from origin/WF41 77f95be6 on the same patched WildFly
 * 41.0.1, JDK 25, fails the same way - the raise restarts the victim after its stop, wf41-run.log; harness adaptation
 * only: the jacc {@code <policy>} removed from standalone-osgi.xml as r8 42d6c470 does, run with
 * {@code -Djboss.home=<WF41 server copy>}).
 */
public class StartLevelRaiseRestartsStoppedBundleIssueTestCase {

    static final int OFFSET = Integer.getInteger("issue.port.offset", 2300);
    static final int MGMT_PORT = 9990 + OFFSET;
    static final String ARM_FILE = "raise-blocker.arm";

    /** Victim: records every activator call; its stop takes 2 s */
    public static class VictimActivator implements BundleActivator {
        static void log(String s) {
            System.out.println("RAISE-PROBE " + s + " [" + Thread.currentThread().getName() + "]");
        }

        @Override
        public void start(BundleContext context) {
            String caller = "other";
            for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                if (e.getMethodName().equals("increaseFrameworkStartLevel")) {
                    caller = "StartLevelManagerImpl.increaseFrameworkStartLevel (boot start-level raise)";
                    break;
                }
                if (e.getClassName().endsWith("BundleActivateService")) {
                    caller = "BundleActivateService.start (deployment)";
                    break;
                }
            }
            log("victim start via " + caller);
        }

        @Override
        public void stop(BundleContext context) throws Exception {
            log("victim stop");
            // a stop that takes a moment (closing a connection, draining a queue)
            Thread.sleep(2000);
            log("victim stop end");
        }
    }

    /**
     * Blocker: when armed, waits in BundleActivator.start (which the boot start-level raise calls) until the server
     * shutdown has stopped the victim
     */
    public static class BlockerActivator implements BundleActivator {
        static void log(String s) {
            System.out.println("RAISE-PROBE " + s + " [" + Thread.currentThread().getName() + "]");
        }

        @Override
        public void start(BundleContext context) throws Exception {
            File arm = new File(System.getProperty("jboss.server.data.dir"), "raise-blocker.arm");
            if (arm.exists() == false) {
                log("blocker start (not armed)");
                return;
            }
            arm.delete();
            Bundle victim = null;
            for (Bundle b : context.getBundles()) {
                if ("raise-victim".equals(b.getSymbolicName()))
                    victim = b;
            }
            if (victim == null) {
                log("blocker no victim");
                return;
            }
            long deadline = System.currentTimeMillis() + 5000;
            while (victim.getState() != Bundle.ACTIVE && System.currentTimeMillis() < deadline)
                Thread.sleep(50);
            if (victim.getState() != Bundle.ACTIVE)
                victim.start(Bundle.START_TRANSIENT);
            log("blocker armed, victim state " + victim.getState());
            // released as soon as the shutdown is stopping the victim
            deadline = System.currentTimeMillis() + 60000;
            while (victim.getState() == Bundle.ACTIVE && System.currentTimeMillis() < deadline)
                Thread.sleep(10);
            log("blocker released, victim state " + victim.getState());
        }

        @Override
        public void stop(BundleContext context) {
            log("blocker stop");
        }
    }

    private File home;
    private Process process;

    @After
    public void tearDown() throws Exception {
        killServer();
    }

    @Test
    public void testShutdownDuringBootStartLevelRaise() throws Exception {
        prepareServer("issues-shutdown-raise-server");
        startServer("console-1.out", true);
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            assertSuccess(client, deploy("raise-blocker.jar", bundle("raise-blocker", BlockerActivator.class)), "Precondition: deploy raise-blocker.jar");
            assertSuccess(client, deploy("raise-victim.jar", bundle("raise-victim", VictimActivator.class)), "Precondition: deploy raise-victim.jar");
            ModelNode op = new ModelNode();
            op.get("operation").set("shutdown");
            try {
                client.execute(op);
            } catch (IOException ex) {
                // connection closed by the shutdown
            }
        }
        assertTrue("Precondition: first server stopped", waitForExit(serverJvm(), 60));
        process.waitFor(30, TimeUnit.SECONDS);
        process = null;

        // second boot: the blocker holds the boot start-level raise until the shutdown stopped the victim
        Files.write(new File(home, "standalone/data/" + ARM_FILE).toPath(), new byte[0]);
        File console = startServer("console-2.out", false);
        long deadline = System.currentTimeMillis() + 120000;
        while (read(console).contains("blocker armed") == false) {
            if (System.currentTimeMillis() > deadline || process.isAlive() == false)
                fail("Precondition: the blocker did not report 'armed' within 120 s: " + tail(read(console), 30));
            Thread.sleep(100);
        }
        ProcessHandle jvm = serverJvm();
        assertTrue("Precondition: server JVM found", jvm != null);
        int mark = read(console).length();
        long begin = System.currentTimeMillis();
        jvm.destroy(); // SIGTERM
        String dump = "";
        boolean exited = waitForExit(jvm, 30);
        if (exited == false) {
            dump = threadDump(jvm, "jstack-shutdown.txt");
            exited = waitForExit(jvm, 60);
        }
        long millis = System.currentTimeMillis() - begin;
        String after = read(console).substring(mark);
        List<String> victimCalls = new ArrayList<String>();
        boolean stopped = false;
        boolean restarted = false;
        for (String line : after.split("\n")) {
            if (line.contains("RAISE-PROBE victim ") || line.contains("RAISE-PROBE blocker released")) {
                String call = line.substring(line.indexOf("RAISE-PROBE ") + 12).trim();
                victimCalls.add(call);
                if (call.startsWith("victim stop end"))
                    stopped = true;
                else if (call.startsWith("victim start") && stopped)
                    restarted = true;
            }
        }
        if (restarted || exited == false) {
            fail("Expected a bundle stopped by the server shutdown (SIGTERM during the boot start-level raise) not to be started again, but the "
                    + "victim's activator calls after SIGTERM were " + victimCalls + " (a start after the stop: " + restarted + "); "
                    + (exited ? "the JVM exited " + millis + " ms after SIGTERM" : "the JVM was still running after " + millis + " ms")
                    + "\nraise thread in the console: " + raiseLines(after)
                    + (dump.isEmpty() ? "" : "\nThreads (server thread dump 30 s after SIGTERM, trimmed):\n" + dump));
        }
        assertTrue("Precondition: the shutdown stopped the victim (calls after SIGTERM: " + victimCalls + ")", stopped);
    }

    private static String raiseLines(String text) {
        StringBuilder result = new StringBuilder();
        for (String line : text.split("\n")) {
            if (line.contains("JBOSGI011002: Bundle started: raise-victim") || line.contains("JBOSGI011003: Bundle stopped: raise-victim")
                    || line.contains("increaseFrameworkStartLevel") || line.contains("WFLYSRV0220") || line.contains("WFLYSRV0050"))
                result.append("\n  ").append(line.replaceAll("\u001B\\[[0-9;]*m", "").trim());
        }
        return result.toString();
    }

    // ---------------------------------------------------------------- bundles

    private static byte[] bundle(final String bsn, final Class<? extends BundleActivator> activator) {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, bsn + ".jar");
        archive.addClasses(activator);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(bsn);
                builder.addBundleActivator(activator);
                builder.addImportPackages(BundleActivator.class);
                return builder.openStream();
            }
        });
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        archive.as(ZipExporter.class).exportTo(out);
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- management

    private static ModelNode deploy(String name, byte[] content) {
        ModelNode add = new ModelNode();
        add.get("operation").set("add");
        add.get("address").add("deployment", name);
        add.get("content").add().get("bytes").set(content);
        add.get("enabled").set(true);
        return add;
    }

    private static void assertSuccess(ModelControllerClient client, ModelNode op, String what) throws IOException {
        ModelNode result = client.execute(op);
        assertTrue(what + ": " + result, "success".equals(result.get("outcome").asString()));
    }

    // ---------------------------------------------------------------- server process

    private static String read(File file) throws IOException {
        return file.exists() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "";
    }

    private static String tail(String text, int lines) {
        String[] all = text.split("\n");
        StringBuilder result = new StringBuilder();
        for (int i = Math.max(0, all.length - lines); i < all.length; i++)
            result.append(all[i]).append('\n');
        return result.toString();
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

    private File startServer(String log, boolean waitForBoot) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(new File(home, "bin/standalone.sh").getPath(), "-c", "standalone-osgi.xml",
                "-Djboss.socket.binding.port-offset=" + OFFSET);
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        builder.environment().remove("JAVA_OPTS");
        builder.redirectErrorStream(true);
        File out = new File(home, log);
        builder.redirectOutput(out);
        process = builder.start();
        if (waitForBoot == false)
            return out;
        long deadline = System.currentTimeMillis() + 180000;
        while (System.currentTimeMillis() < deadline) {
            String text = read(out);
            if (text.contains("WFLYSRV0025") || text.contains("WFLYSRV0026"))
                return out;
            if (process.isAlive() == false)
                fail("Precondition: server process ended: " + text);
            Thread.sleep(500);
        }
        fail("Precondition: server did not start in time, see " + out);
        return out;
    }

    private ProcessHandle serverJvm() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30000;
        while (process != null && System.currentTimeMillis() < deadline) {
            ProcessHandle jvm = process.toHandle().descendants().filter(h -> h.info().command().map(c -> c.endsWith("java")).orElse(false)).findFirst()
                    .orElse(null);
            if (jvm != null)
                return jvm;
            Thread.sleep(100);
        }
        return null;
    }

    private static boolean waitForExit(ProcessHandle jvm, int seconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (jvm != null && jvm.isAlive() && System.currentTimeMillis() < deadline)
            Thread.sleep(100);
        return jvm == null || jvm.isAlive() == false;
    }

    /** jstack of the server JVM (full dump saved in the server copy), only the threads that run OSGi or shutdown code */
    private String threadDump(ProcessHandle jvm, String file) {
        try {
            File jstack = new File(System.getProperty("java.home"), "bin/jstack");
            Process p = new ProcessBuilder(jstack.getPath(), String.valueOf(jvm.pid())).redirectErrorStream(true).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                in.transferTo(out);
            }
            p.waitFor(30, TimeUnit.SECONDS);
            String full = out.toString(StandardCharsets.UTF_8);
            Files.write(new File(home, file).toPath(), full.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (String thread : full.split("\n\n")) {
                if (thread.contains("org.jboss.osgi") || thread.contains("org.jboss.as.osgi") || thread.contains("ShutdownHook"))
                    result.append(trim(thread, 30)).append("\n\n");
            }
            return result.toString();
        } catch (Exception ex) {
            return "(jstack failed: " + ex + ")";
        }
    }

    private static String trim(String thread, int maxLines) {
        String[] lines = thread.split("\n");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length && i < maxLines; i++)
            result.append(lines[i]).append('\n');
        if (lines.length > maxLines)
            result.append("\t... ").append(lines.length - maxLines).append(" more\n");
        return result.toString();
    }

    /** kills only the processes this test started: the script and its children */
    private void killServer() throws Exception {
        if (process == null)
            return;
        List<ProcessHandle> handles = new ArrayList<ProcessHandle>();
        process.toHandle().descendants().forEach(handles::add);
        handles.add(process.toHandle());
        for (ProcessHandle handle : handles) {
            if (handle.isAlive())
                handle.destroyForcibly();
        }
        for (ProcessHandle handle : handles) {
            long deadline = System.currentTimeMillis() + 30000;
            while (handle.isAlive() && System.currentTimeMillis() < deadline)
                Thread.sleep(200);
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
