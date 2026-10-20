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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
 * Bundle.uninstall() of another deployed bundle from a BundleActivator.stop (the usual cleanup of a provisioning bundle)
 * blocks the server shutdown and every undeploy of that bundle for the full management blocking timeout (300 s by
 * default): the uninstall runs a nested, synchronous server undeploy operation from the MSC thread that is stopping the
 * deployment, and that operation waits for the MSC container that cannot become stable while it waits.
 *
 * <h2>Severity</h2>
 * major. Server shutdown (:shutdown, :reload, SIGTERM) completes only after the blocking timeout: measured 305 s with
 * the default jboss.as.management.blocking.timeout=300 (manual run, WFLYSRV0050 "stopped in 305059ms"), 35 s with 30 s in
 * this test. An undeploy of the provisioning bundle fails after the timeout (WFLYCTL0344), leaves the server in
 * restart-required and leaves both deployments in the model although the child bundle was uninstalled.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem: {@code org.jboss.as.osgi.service.BundleLifecycleIntegration.BundleLifecycleImpl}
 * ({@code removeRevision} -&gt; {@code undeployRevision}) and
 * {@code org.jboss.as.osgi.deployment.BundleActivateProcessor.BundleActivateService.stop}.
 *
 * <h2>Found by</h2>
 * shutdown round, workstream sd-msc: code reading of the R8 deferred-undeploy mechanism ({@code deploymentUndeploy} /
 * {@code undeployExecutor}, set only by BundleInstallProcessor.undeploy), confirmed with a probe bundle on a server copy
 * (activator.stop -&gt; uninstall of another bundle, :shutdown took 307 s) and with this test.
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 Bundle.uninstall() javadoc: the uninstall is a synchronous framework operation ("This method causes the
 * Framework to notify other bundles that this bundle is being uninstalled, and then puts this bundle into the UNINSTALLED
 * state"); calling it from a BundleActivator.stop is allowed. 4.4.9 / 4.2.6: stopping the framework stops all bundles
 * (their activators run); nothing allows the stop to block indefinitely on the container that hosts it.
 * https://docs.osgi.org/specification/osgi.core/8.0.0/framework.api.html#org.osgi.framework.Bundle.uninstall--
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/BundleLifecycleIntegration.java:347-373
 * ({@code undeployRevision}: synchronous {@code ServerDeploymentHelper.undeploy(runtimeName)} - a nested management
 * operation, {@code future.get()} without timeout - unless the thread is marked by
 * {@code setDeploymentUndeploy(true)}, which only {@code BundleInstallProcessor.undeploy} (line 102) does);
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/deployment/BundleActivateProcessor.java:140-149
 * ({@code BundleActivateService.stop} calls {@code bundleManager.stopBundle} -&gt; BundleActivator.stop on the MSC thread
 * without the mark). The synchronous undeploy exists since WF41 (WF41 has no deferral at all); the R8 deferral
 * (commit c4bbcd55 / f025b420) covers the BundleInstallProcessor path only.
 *
 * <h2>Root cause</h2>
 * A nested, synchronous management operation from an MSC thread: the undeploy operation of child.jar removes its
 * deployment services and then waits for container stability (ModelControllerImpl.awaitContainerStability), while the
 * MSC thread that issued it is inside {@code BundleActivateService.stop} of cleanup.jar and keeps that service in
 * transition until the nested operation returns. The nested operation only returns after its blocking timeout (it then
 * fails: "WFLYCTL0343 ... Restart is required"). During an undeploy of cleanup.jar the outer operation additionally holds
 * the controller lock.
 * <br>Cycle: "MSC service thread 1-n" (stop of cleanup.jar.Activate, service STOPPING) waits in
 * ServerDeploymentPlanResultFuture.get &lt;- BundleLifecycleIntegration.undeployRevision:360; the nested undeploy operation
 * ("pool-13-thread-1") waits in ServiceContainerImpl.awaitStability (ContainerStateMonitor, OperationContextImpl.removeService)
 * for all services to settle, including cleanup.jar.Activate.
 * Same design flaw as lifecycle/04 (installBundle from BundleActivator.start: nested deploy) and wildfly/26 / ee/01
 * (synchronous server deploy/undeploy from BundleLifecycleIntegration), but a different trigger and fix site: the
 * activator stop path and the deferral that R8 introduced for the BundleInstallProcessor path only.
 *
 * <h2>Reproduction</h2>
 * Starts its own copy of the testsuite server (target/wildfly-41.0.1.Final, port offset 2200, override with
 * -Dissue.port.offset=N; jboss.as.management.blocking.timeout=30), deploys child.jar (plain bundle "provisioned-child")
 * and cleanup.jar (activator stop() uninstalls "provisioned-child"), then :shutdown (testServerShutdown) or undeploys
 * cleanup.jar (testUndeploy). Run it alone:
 * <pre>
 * cd jbosgi
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full \
 *     -pl testsuite/integration test -Dtest=UninstallFromActivatorStopIssueTestCase
 * </pre>
 * (on JDK 24+ the testsuite needs surefire.security.args without -Djava.security.manager, see
 * TestJvmSecurityManagerIssueTestCase). Deterministic, no timing involved.
 *
 * <h2>Observed</h2>
 * JDK 25, WF41-osgi-r8 (jbosgi f025b420, framework d7b20d08):
 * <pre>
 * testServerShutdown: java.lang.AssertionError: Expected :shutdown of a server with a bundle whose BundleActivator.stop
 *   uninstalls another deployed bundle to finish within 20 s, but the server was still running after 20 s (it exited
 *   35539 ms after the :shutdown request)
 *   "MSC service thread 1-3" WAITING (parking) CompletableFuture.get
 *     at org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper.undeploy(ServerDeploymentHelper.java:95)
 *     at org.jboss.as.osgi.service.BundleLifecycleIntegration$BundleLifecycleImpl$1.run(BundleLifecycleIntegration.java:360)
 *     at org.jboss.as.osgi.service.BundleLifecycleIntegration$BundleLifecycleImpl.undeployRevision(BundleLifecycleIntegration.java:372)
 *     at org.jboss.as.osgi.service.BundleLifecycleIntegration$BundleLifecycleImpl.removeRevision(BundleLifecycleIntegration.java:339)
 *     at org.jboss.osgi.framework.internal.BundleManagerPlugin.removeRevisionLifecycle(BundleManagerPlugin.java:693)
 *     at org.jboss.osgi.framework.internal.UserBundleState.uninstallInternal(UserBundleState.java:489)
 *     at org.jboss.osgi.framework.internal.AbstractBundleState.uninstall(AbstractBundleState.java:540)
 *     at ...UninstallFromActivatorStopIssueTestCase$CleanupActivator.stop
 *     at org.jboss.osgi.framework.internal.BundleManagerPlugin.stopBundle(BundleManagerPlugin.java:633)
 *     at org.jboss.as.osgi.deployment.BundleActivateProcessor$BundleActivateService.stop(BundleActivateProcessor.java:144)
 *   "pool-13-thread-1" TIMED_WAITING ServiceContainerImpl.awaitStability &lt;- ContainerStateMonitor.awaitStability
 *     &lt;- ModelControllerImpl.awaitContainerStability &lt;- OperationContextImpl.removeService &lt;- DeploymentHandlerUtil$5.execute
 *   "Thread-1" WAITING CountDownLatch.await &lt;- org.jboss.as.server.BootstrapImpl$ShutdownHook.shutdown(BootstrapImpl.java:281)
 * testUndeploy: java.lang.AssertionError: Expected the undeploy of a bundle whose BundleActivator.stop uninstalls another
 *   deployed bundle to succeed within 20 s, but: undeploy of cleanup.jar answered after 40055 ms: failed WFLYCTL0344:
 *   Operation timed out awaiting service container stability; server-state restart-required; deployments
 *   ["child.jar", "cleanup.jar"]  (same MSC thread stack)
 * </pre>
 * With the default blocking timeout (manual run): "WFLYCTL0013: Operation ("undeploy") failed - address: ([("deployment"
 * =&gt; "t1.jar")]) ... WFLYCTL0343: The service container has been destabilized by a previous operation", "JBAS011905:
 * Cannot undeploy bundle revision", then "WFLYSRV0050: WildFly 41.0.1.Final ... stopped in 305059ms".
 *
 * <h2>Expected</h2>
 * The shutdown finishes in about a second (the uninstall completes, the child deployment is removed by the same
 * shutdown); the undeploy of cleanup.jar succeeds and child.jar is undeployed as well (or at least the operation does not
 * block on itself).
 *
 * <h2>Reference behaviour</h2>
 * n/a: WildFly OSGi subsystem integration (MSC services and management operations); Equinox and Felix have no
 * equivalent of this code path.
 *
 * <h2>Suggested fix</h2>
 * Never run the server undeploy synchronously on an MSC thread: mark the thread in {@code BundleActivateService.stop}
 * (and start) like {@code BundleInstallProcessor.undeploy} does ({@code BundleLifecycleIntegration.setDeploymentUndeploy(true)}),
 * so that {@code undeployRevision} hands the undeploy to the undeploy executor; and while that undeploy is pending, keep
 * the revision's module services (do not call {@code bundleManager.removeRevision} in {@code removeRevision} and do not
 * unresolve the bundle) so that the still deployed child deployment has no missing dependency.
 * Trial (fix.diff in the issue folder, subsystem jar replaced in a server copy): marking BundleActivateService.stop and
 * skipping the early revision removal makes testServerShutdown pass (shutdown immediate) but testUndeploy then fails
 * at once (27 ms) with "WFLYCTL0441 ... New missing/unsatisfied dependencies: service jboss.module.service."deployment.child.jar"
 * (missing) dependents: [service jboss.deployment.unit."child.jar".FIRST_MODULE_USE]" because uninstallInternal
 * unresolves the bundle (BundleManagerPlugin.unresolveBundle removes its module) while the deployment is still deployed.
 * So the complete fix also has to defer the module removal (or the deployment's dependency on it) until the deferred
 * undeploy ran.
 *
 * <h2>Regression vs WF41</h2>
 * no (measured: the same test on the WF41 server built from origin/WF41 77f95be6, framework 6.6.0.Final, same patched
 * WildFly 41.0.1, JDK 25, jacc policy removed from standalone-osgi.xml as r8 42d6c470 does: both methods fail the same
 * way, shutdown 35526 ms, undeploy 40049 ms WFLYCTL0344 restart-required).
 *
 * <h2>Related</h2>
 * lifecycle/04 (ActivatorInstallBundleDeadlockIssueTestCase: nested deploy from BundleActivator.start), ee/01 and
 * security/03 (undeployRevision ignores the undeploy result), wildfly/26 (refresh redeploy under framework locks).
 * shutdown/03 (DeferredStartFromActivatorHangIssueTestCase) is a different root cause (container-wide stability wait).
 */
public class UninstallFromActivatorStopIssueTestCase {

    static final int OFFSET = Integer.getInteger("issue.port.offset", 2200);
    static final int MGMT_PORT = 9990 + OFFSET;
    /** jboss.as.management.blocking.timeout of the test server (default 300 s), so that the blocked operations end in time */
    static final int BLOCKING_TIMEOUT = 30;

    /** Uninstalls the bundle it provisioned ("provisioned-child") when it is stopped, as provisioning bundles do */
    public static class CleanupActivator implements BundleActivator {
        @Override
        public void start(BundleContext context) {
        }

        @Override
        public void stop(BundleContext context) throws Exception {
            for (Bundle bundle : context.getBundles()) {
                if ("provisioned-child".equals(bundle.getSymbolicName()))
                    bundle.uninstall();
            }
        }
    }

    private File home;
    private Process process;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @After
    public void tearDown() throws Exception {
        executor.shutdownNow();
        killServer();
    }

    @Test
    public void testServerShutdown() throws Exception {
        prepareServer("issues-shutdown-uninstallonstop-server");
        startServer("console-shutdown.out");
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            assertSuccess(client, deploy("child.jar", childBundle()), "Precondition: deploy child.jar");
            assertSuccess(client, deploy("cleanup.jar", cleanupBundle()), "Precondition: deploy cleanup.jar");
        }
        long begin = System.currentTimeMillis();
        boolean stopped = shutdownServer(BLOCKING_TIMEOUT + 60, 20);
        long millis = System.currentTimeMillis() - begin;
        String dump = stopped ? "" : lastDump;
        if (!stopped) {
            fail("Expected :shutdown of a server with a bundle whose BundleActivator.stop uninstalls another deployed bundle to finish within 20 s, "
                    + "but the server was still running after 20 s (" + describeEnd(millis) + ")\nThreads (server thread dump 20 s after :shutdown, trimmed):\n" + dump);
        }
    }

    @Test
    public void testUndeploy() throws Exception {
        prepareServer("issues-shutdown-uninstallonstop-server");
        startServer("console-undeploy.out");
        String observed;
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            assertSuccess(client, deploy("child.jar", childBundle()), "Precondition: deploy child.jar");
            assertSuccess(client, deploy("cleanup.jar", cleanupBundle()), "Precondition: deploy cleanup.jar");

            ModelNode undeploy = new ModelNode();
            undeploy.get("operation").set("undeploy");
            undeploy.get("address").add("deployment", "cleanup.jar");
            long begin = System.currentTimeMillis();
            Future<ModelNode> future = executor.submit(() -> client.execute(undeploy));
            String dump = "";
            ModelNode result = null;
            try {
                result = future.get(20, TimeUnit.SECONDS);
            } catch (TimeoutException ex) {
                dump = threadDump("jstack-undeploy.txt", false);
                result = future.get(BLOCKING_TIMEOUT * 3 + 30, TimeUnit.SECONDS);
            }
            long millis = System.currentTimeMillis() - begin;
            String outcome = result.get("outcome").asString();
            observed = "undeploy of cleanup.jar answered after " + millis + " ms: " + outcome + " " + result.get("failure-description").asString()
                    + "; server-state " + serverState(client) + "; deployments " + deployments(client)
                    + "\nThreads (server thread dump 20 s after the undeploy request, trimmed):\n" + dump;
            if ("success".equals(outcome) && millis < 20000 && dump.isEmpty())
                return;
        }
        fail("Expected the undeploy of a bundle whose BundleActivator.stop uninstalls another deployed bundle to succeed within 20 s, but: " + observed);
    }

    private String lastDump = "";
    private long exitMillis = -1;

    private String describeEnd(long millis) {
        return exitMillis >= 0 ? "it exited " + exitMillis + " ms after the :shutdown request" : "it did not exit within " + millis + " ms and was killed";
    }

    // ---------------------------------------------------------------- bundles

    private static byte[] childBundle() {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "child.jar");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName("provisioned-child");
                return builder.openStream();
            }
        });
        return bytes(archive);
    }

    private static byte[] cleanupBundle() {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "cleanup.jar");
        archive.addClasses(CleanupActivator.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName("provisioning-cleanup");
                builder.addBundleActivator(CleanupActivator.class);
                builder.addImportPackages(BundleActivator.class);
                return builder.openStream();
            }
        });
        return bytes(archive);
    }

    private static byte[] bytes(JavaArchive archive) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        archive.as(ZipExporter.class).exportTo(out);
        return out.toByteArray();
    }

    private static String serverState(ModelControllerClient client) {
        try {
            ModelNode read = new ModelNode();
            read.get("operation").set("read-attribute");
            read.get("name").set("server-state");
            return client.execute(read).get("result").asString();
        } catch (Exception ex) {
            return ex.toString();
        }
    }

    private static String deployments(ModelControllerClient client) {
        try {
            ModelNode read = new ModelNode();
            read.get("operation").set("read-children-names");
            read.get("child-type").set("deployment");
            return client.execute(read).get("result").toString().replace('\n', ' ');
        } catch (Exception ex) {
            return ex.toString();
        }
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

    private static String bundleState(ModelControllerClient client, String location) {
        try {
            ModelNode read = new ModelNode();
            read.get("operation").set("read-attribute");
            read.get("address").add("subsystem", "osgi").add("bundle", location);
            read.get("name").set("state");
            ModelNode result = client.execute(read);
            return "success".equals(result.get("outcome").asString()) ? result.get("result").asString() : result.get("failure-description").asString();
        } catch (Exception ex) {
            return ex.toString();
        }
    }

    // ---------------------------------------------------------------- server process

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

    private ProcessHandle serverJvm() {
        if (process == null)
            return null;
        return process.toHandle().descendants().filter(h -> h.info().command().map(c -> c.endsWith("java")).orElse(false)).findFirst().orElse(null);
    }

    /**
     * jstack of the server JVM (full dump saved in the server copy), only the threads that run OSGi code or wait for
     * container stability, plus the shutdown threads when asked for
     */
    private String threadDump(String file, boolean shutdownThreads) {
        ProcessHandle jvm = serverJvm();
        if (jvm == null)
            return "(no server JVM)";
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
                boolean shutdown = thread.contains("awaitTermination") || thread.contains("Shutdown") || thread.contains("shutdown");
                if (thread.contains("org.jboss.osgi") || thread.contains("org.jboss.as.osgi") || thread.contains("awaitStability") || (shutdownThreads && shutdown))
                    result.append(trim(thread, thread.contains("osgi") ? 40 : 16)).append("\n\n");
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

    /**
     * :shutdown, then wait up to the given seconds for the server JVM to exit; a thread dump is taken when it is still
     * running after dumpAfter seconds
     */
    private boolean shutdownServer(int seconds, int dumpAfter) throws Exception {
        ProcessHandle jvm = serverJvm();
        long begin = System.currentTimeMillis();
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            ModelNode op = new ModelNode();
            op.get("operation").set("shutdown");
            Future<ModelNode> future = executor.submit(() -> client.execute(op));
            try {
                future.get(5, TimeUnit.SECONDS);
            } catch (Exception ex) {
                // the connection is closed by the shutdown
            }
        }
        long deadline = begin + seconds * 1000L;
        boolean dumped = false;
        while (jvm != null && jvm.isAlive() && System.currentTimeMillis() < deadline) {
            if (!dumped && System.currentTimeMillis() - begin >= dumpAfter * 1000L) {
                lastDump = threadDump("jstack-shutdown.txt", true);
                dumped = true;
            }
            Thread.sleep(200);
        }
        if (jvm == null || jvm.isAlive() == false)
            exitMillis = System.currentTimeMillis() - begin;
        return exitMillis >= 0 && !dumped;
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
