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
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.wiring.FrameworkWiring;
import java.util.Collections;

/**
 * A refresh of a deployed bundle makes every management operation that runs at the same time fail and roll back
 * ("WFLYCTL0441 ... New missing/unsatisfied dependencies: service jboss.module.service."deployment.X" (missing)"): the
 * refresh removes the bundle's module service while the bundle's deployment, which depends on it, is still deployed.
 * With the framework locks still held by the refresh, the rollback then blocks for 2 x 60 s (wildfly/26).
 *
 * <h2>Severity</h2>
 * major. Any refresh (FrameworkWiring.refreshBundles after an update or uninstall, provisioning agents, web console)
 * makes concurrent, unrelated deployments, undeployments and other runtime operations fail at random (deployment
 * scanner, CI, :reload-time redeploys); measured: the unrelated deploy fails after 125 s (rollback blocked 120 s on
 * framework lock timeouts), and 5 s even with a trial of the wildfly/26 fix.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem: {@code org.jboss.as.osgi.service.ModuleLoaderIntegration.FrameworkModuleLoaderImpl}
 * ({@code removeModule} / {@code deactivateDeferredPhase}) and {@code org.jboss.as.osgi.deployment.DeferredPhaseProcessor},
 * together with jbosgi-framework {@code UserBundleState.refresh} (unresolve before the server undeploy).
 *
 * <h2>Found by</h2>
 * shutdown round, workstream sd-msc, while building the deterministic test for shutdown/11 (a slow unrelated deployment
 * used to hold the controller lock during a refresh was rolled back with WFLYCTL0441).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 FrameworkWiring.refreshBundles: refreshes only the bundles in the dependency closure ("the bundles ... are
 * stopped, unresolved ... and then restarted"); it must not affect unrelated bundles or, in WildFly, unrelated
 * deployments. WildFly management: an operation fails only for problems it caused (the verify stage reports services
 * that became missing during the operation, which here are services of another deployment).
 *
 * <h2>Code location</h2>
 * jbosgi-framework core/src/main/java/org/jboss/osgi/framework/internal/UserBundleState.java:402
 * ({@code refresh}: {@code bundleManager.unresolveBundle(this)} -&gt; ModuleManager.removeModule -&gt;
 * ModuleLoaderIntegration.removeModule, before {@code refreshPolicy.startBundleRefresh} undeploys the deployment at
 * line 406); wildfly/subsystem/src/main/java/org/jboss/as/osgi/deployment/DeferredPhaseProcessor.java:55-56 (every bundle
 * deployment's FIRST_MODULE_USE phase depends on {@code jboss.module.service."deployment.X"});
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/ModuleLoaderIntegration.java:423-438
 * ({@code deactivateDeferredPhase}, R8 f025b420: sets the phase to NEVER before the module is removed, but only for
 * deployments whose phase was deferred - line 429; a deployment that was resolved at deploy time keeps an ACTIVE phase
 * with a missing dependency). Pre-existing on WF41 (no deactivation at all).
 *
 * <h2>Root cause</h2>
 * The module service of a still deployed bundle deployment is removed (refresh: unresolve first, server undeploy later),
 * so for a moment the deployment has a missing dependency. WildFly's verify stage of whatever operation is running at
 * that moment attributes the new missing dependency to that operation and rolls it back. The rollback has to stop the
 * unrelated deployment's bundle, which needs the framework locks the refresh still holds (60 s LockException, twice),
 * while the refresh waits for the controller lock for its own server undeploy (wildfly/26).
 *
 * <h2>Reproduction</h2>
 * Starts its own copy of the testsuite server (target/wildfly-41.0.1.Final, port offset 2200, override with
 * -Dissue.port.offset=N; jboss.as.management.blocking.timeout=120). Deploys target.jar (plain bundle) and trigger.jar
 * (refreshes target 2 s after its start, from a thread), then immediately deploys unrelated.jar whose activator start
 * takes 5 s: the refresh happens while that deploy operation runs. Deterministic (2 s vs 5 s windows).
 * <pre>
 * cd jbosgi
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full \
 *     -pl testsuite/integration test -Dtest=RefreshFailsConcurrentDeploymentIssueTestCase
 * </pre>
 * (on JDK 24+ the testsuite needs surefire.security.args without -Djava.security.manager, see
 * TestJvmSecurityManagerIssueTestCase)
 *
 * <h2>Observed</h2>
 * JDK 25, WF41-osgi-r8 (jbosgi f025b420, framework d7b20d08), 2 of 2 runs:
 * <pre>
 * java.lang.AssertionError: Expected the deployment of an unrelated bundle to succeed while another deployed bundle is
 *   refreshed, but the deploy of unrelated.jar answered after 125058 ms: failed WFLYCTL0441: Operation has resulted in
 *   failed or missing services  WFLYCTL0184:    New missing/unsatisfied dependencies:
 *   service jboss.module.service."deployment.target.jar" (missing) dependents: [service jboss.deployment.unit."target.jar".FIRST_MODULE_USE]
 *   ; deployments afterwards ["target.jar", "trigger.jar"], target.jar bundle ACTIVE
 * server log: 11:00:29 (Framework Refresh Thread) Bundle stopped: refresh-target; 11:00:32 WFLYSRV0021: Deploy of deployment
 *   "unrelated.jar" was rolled back ...; 11:02:32 WFLYSRV0043 ... BundleInstallProcessor ... (LockException, rollback);
 *   "Stopped deployment unrelated.jar ... in 120018ms"; then the refresh's own undeploy/redeploy of target.jar.
 * </pre>
 * With a trial of wildfly/26's suggested fix (refresh server operations asynchronous, outside the framework locks;
 * shutdown/11 issue folder wildfly26-trial.diff) the stall disappears but the deploy still fails after 5040 ms with the
 * same WFLYCTL0441 (wildfly26-trial-run.log).
 *
 * <h2>Expected</h2>
 * unrelated.jar is deployed successfully; the refresh of target.jar completes.
 *
 * <h2>Reference behaviour</h2>
 * n/a: WildFly OSGi subsystem integration (MSC services and management operations); Equinox and Felix have no
 * equivalent of this code path.
 *
 * <h2>Suggested fix</h2>
 * Never leave a deployed bundle deployment with a missing module dependency: before the module services of a revision
 * whose deployment is still deployed are removed, set its FIRST_MODULE_USE phase to NEVER for every deployment (not only
 * deferred ones), or undeploy the deployment first and unresolve afterwards. Verified: removing the "deferred only"
 * condition in ModuleLoaderIntegration.deactivateDeferredPhase (fix.diff in the issue folder, subsystem jar replaced in
 * a server copy) makes the test pass in 14.5 s (unrelated deploy succeeds, the refresh completes: undeploy + redeploy of
 * target.jar, PACKAGES_REFRESHED). The same change addresses the missing-module failure seen in the partial trial of
 * shutdown/05.
 *
 * <h2>Regression vs WF41</h2>
 * no (measured: the same test on the WF41 server built from origin/WF41 77f95be6, framework 6.6.0.Final, same patched
 * WildFly 41.0.1, JDK 25, jacc policy removed from standalone-osgi.xml as r8 42d6c470 does: same failure after 125056 ms).
 *
 * <h2>Related</h2>
 * wildfly/26 (refresh deadlock with a concurrent operation: the 120 s here; its fix alone does not prevent this failure),
 * shutdown/11 (refresh redeploys an undeployed bundle), ee/01 (undeploy of a bundle in use rolled back), shutdown/05.<br>
 * Fix cluster (measured by sd-verify): this issue's fix.diff together with shutdown/05's fix-partial.diff makes both
 * methods of shutdown/05 pass (12's fix alone does not); 12's fix alone also makes the main campaign's ee/10 pass.
 * Cluster {shutdown/12, shutdown/05, ee/10}.
 */
public class RefreshFailsConcurrentDeploymentIssueTestCase {

    static final int OFFSET = Integer.getInteger("issue.port.offset", 2200);
    static final int MGMT_PORT = 9990 + OFFSET;
    /** jboss.as.management.blocking.timeout of the test server */
    static final int BLOCKING_TIMEOUT = 120;

    /** Refreshes the bundle "refresh-target" from a thread, 2 s after it was started (e.g. a provisioning agent after an update) */
    public static class RefreshingActivator implements BundleActivator, Runnable, FrameworkListener {
        private BundleContext context;

        @Override
        public void start(BundleContext context) {
            this.context = context;
            new Thread(this, "refresh-trigger").start();
        }

        @Override
        public void run() {
            try {
                Thread.sleep(2000);
                for (Bundle bundle : context.getBundles()) {
                    if ("refresh-target".equals(bundle.getSymbolicName())) {
                        System.out.println("refresh-trigger: refreshing " + bundle);
                        context.getBundle(0).adapt(FrameworkWiring.class).refreshBundles(Collections.singleton(bundle), this);
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        @Override
        public void frameworkEvent(FrameworkEvent event) {
            System.out.println("refresh-trigger: refresh done, event type " + event.getType());
        }

        @Override
        public void stop(BundleContext context) {
        }
    }

    /** A deployment whose start takes 5 s (any slow application start) */
    public static class SlowStartActivator implements BundleActivator {
        @Override
        public void start(BundleContext context) throws Exception {
            Thread.sleep(5000);
        }

        @Override
        public void stop(BundleContext context) {
        }
    }

    private File home;
    private Process process;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @After
    public void tearDown() throws Exception {
        executor.shutdownNow();
        stopServerQuietly();
    }

    @Test
    public void testDeployDuringRefresh() throws Exception {
        prepareServer("issues-shutdown-refreshconcurrent-server");
        startServer("console.out");
        String observed;
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            assertSuccess(client, deploy("target.jar", bundle("target.jar", "refresh-target", null)), "Precondition: deploy target.jar");
            assertSuccess(client, deploy("trigger.jar", bundle("trigger.jar", "refresh-trigger", RefreshingActivator.class)), "Precondition: deploy trigger.jar");

            // The refresh of refresh-target starts 2 s from now, while this unrelated deployment is starting (5 s)
            long begin = System.currentTimeMillis();
            ModelNode result = client.execute(deploy("unrelated.jar", bundle("unrelated.jar", "unrelated-app", SlowStartActivator.class)));
            long millis = System.currentTimeMillis() - begin;
            Thread.sleep(5000);
            observed = "the deploy of unrelated.jar answered after " + millis + " ms: " + result.get("outcome").asString() + " "
                    + result.get("failure-description").asString().replace('\n', ' ') + "; deployments afterwards " + deployments(client)
                    + ", target.jar bundle " + bundleState(client, "target.jar");
            if ("success".equals(result.get("outcome").asString()))
                return;
        }
        fail("Expected the deployment of an unrelated bundle to succeed while another deployed bundle is refreshed, but " + observed);
    }

    // ---------------------------------------------------------------- bundles and management

    private static byte[] bundle(String name, final String bsn, final Class<? extends BundleActivator> activator) {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        if (activator != null)
            archive.addClasses(activator);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(bsn);
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

    private static ModelNode deploy(String name, byte[] content) {
        ModelNode add = new ModelNode();
        add.get("operation").set("add");
        add.get("address").add("deployment", name);
        add.get("content").add().get("bytes").set(content);
        add.get("enabled").set(true);
        return add;
    }

    /** what the CLI "undeploy name" does: a composite of undeploy and remove */
    private static ModelNode undeployAndRemove(String name) {
        ModelNode composite = new ModelNode();
        composite.get("operation").set("composite");
        composite.get("address").setEmptyList();
        ModelNode steps = composite.get("steps");
        ModelNode undeploy = steps.add();
        undeploy.get("operation").set("undeploy");
        undeploy.get("address").add("deployment", name);
        ModelNode remove = steps.add();
        remove.get("operation").set("remove");
        remove.get("address").add("deployment", name);
        return composite;
    }

    private static String deployments(ModelControllerClient client) {
        try {
            ModelNode read = new ModelNode();
            read.get("operation").set("read-children-names");
            read.get("child-type").set("deployment");
            return client.execute(read).get("result").asList().toString().replace('\n', ' ');
        } catch (Exception ex) {
            return ex.toString();
        }
    }

    private void stopServerQuietly() throws Exception {
        if (process == null)
            return;
        try {
            shutdownServer(30, 30);
        } catch (Exception ex) {
            // killed below
        }
        killServer();
    }

    private String lastDump = "";
    private long exitMillis = -1;

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
