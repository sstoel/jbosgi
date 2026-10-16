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
 * A deployment that an administrator undeploys and removes while its bundle is being refreshed is deployed again by the
 * refresh: the application comes back, is ACTIVE and is persisted in the server configuration (it survives restarts).
 *
 * <h2>Severity</h2>
 * major (wrong persisted state after an ordinary management operation: an application that was removed - e.g. because
 * it is broken or insecure - silently returns and is started again on every boot; the operation reported success).
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem, {@code org.jboss.as.osgi.service.BundleLifecycleIntegration.BundleLifecycleImpl.RecreateCurrentRevisionPolicy}
 * ({@code startBundleRefresh} / {@code refreshCurrentRevision}).
 *
 * <h2>Found by</h2>
 * shutdown round, workstream sd-msc: probe bundles on a server copy (undeploy of a bundle while FrameworkWiring.refreshBundles
 * runs for it), then checked against a trial of the wildfly/26 fix on the coordinator's request.
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 FrameworkWiring.refreshBundles javadoc: the refresh recomputes the wiring of existing bundles ("Each bundle in
 * the dependency graph ... will be stopped ... unresolved ... started again"); it never installs a bundle that was removed
 * meanwhile ("Bundle.uninstall ... the bundle is removed"). WildFly management: a successful
 * {@code undeploy}+{@code remove} of a deployment removes it from the runtime and the persistent configuration.
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/BundleLifecycleIntegration.java:529-571:
 * {@code startBundleRefresh} undeploys the deployment through {@code undeployRevision} (line 543) and ignores the
 * failure ("JBAS011905 Cannot undeploy bundle revision ... WFLYCTL0216: Management resource ... not found" when the
 * deployment is already gone); {@code refreshCurrentRevision} (line 547) then always runs
 * {@code server.deploy(runtimeName, input)} (line 562) with the content captured at the start of the refresh. Pre-existing
 * (same policy on origin/WF41).
 *
 * <h2>Root cause</h2>
 * The refresh policy implements a refresh as "undeploy, then deploy the same content again" through the management API,
 * but does not notice that the deployment was removed by somebody else between the start of the refresh and its own
 * undeploy, and re-creates it. In the test the administrator's undeploy cannot uninstall the bundle (the refresh holds
 * the framework locks, the uninstall times out after 60 s - that stall is wildfly/26), so the bundle is not even marked
 * uninstalled; but the resurrection does not depend on that stall: with a trial of the wildfly/26 suggested fix (server
 * undeploy/redeploy of the refresh moved out of the framework locks to the undeploy executor) the undeploy returns after
 * 10 s, the bundle is uninstalled, and the refresh still deploys target.jar again (evidence: wildfly26-trial-run.log).
 *
 * <h2>Reproduction</h2>
 * Starts its own copy of the testsuite server (target/wildfly-41.0.1.Final, port offset 2200, override with
 * -Dissue.port.offset=N; jboss.as.management.blocking.timeout=120 so that the 60 s lock stall of wildfly/26 does not roll
 * the undeploy back). Deploys target.jar (activator stop takes 5 s) and trigger.jar (refreshes target 2 s after its start
 * from a thread, like a provisioning agent after an update). As soon as target is STOPPING (the refresh stops it first)
 * the test runs the CLI "undeploy target.jar" (composite undeploy + remove), waits for it and 10 s more, then reads the
 * deployments. Deterministic: the undeploy is issued inside the 5 s stop window, detected by polling the bundle state.
 * <pre>
 * cd jbosgi
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full \
 *     -pl testsuite/integration test -Dtest=RefreshRedeploysUndeployedBundleIssueTestCase
 * </pre>
 * (on JDK 24+ the testsuite needs surefire.security.args without -Djava.security.manager, see
 * TestJvmSecurityManagerIssueTestCase)
 *
 * <h2>Observed</h2>
 * JDK 25, WF41-osgi-r8 (jbosgi f025b420, framework d7b20d08), 3 of 3 runs:
 * <pre>
 * java.lang.AssertionError: Expected target.jar to stay undeployed after the administrator removed it while the bundle
 *   was being refreshed, but the undeploy+remove of target.jar succeeded after 65005 ms (deployments then:
 *   ["trigger.jar"]), 10 s later the deployments are ["target.jar", "trigger.jar"] and the bundle at location target.jar
 *   is ACTIVE: the refresh deployed it again (and it is in the persistent configuration)
 * server log:
 *   WFLYSRV0043 ... BundleInstallProcessor ... undeploy phase REGISTER of target.jar   (LockException after 60 s, wildfly/26)
 *   WFLYSRV0009: Undeployed "target.jar"                                   (management-handler-thread)
 *   JBAS011905: Cannot undeploy bundle revision ... WFLYCTL0216 ... not found   (Framework Refresh Thread, startBundleRefresh)
 *   WFLYSRV0010: Deployed "target.jar"                                      (pool-13-thread-2: refreshCurrentRevision)
 * </pre>
 *
 * <h2>Expected</h2>
 * target.jar stays undeployed and removed; the refresh ends for that bundle (it is uninstalled).
 *
 * <h2>Reference behaviour</h2>
 * n/a: WildFly OSGi subsystem integration (MSC services and management operations); Equinox and Felix have no
 * equivalent of this code path.
 *
 * <h2>Suggested fix</h2>
 * In the refresh policy, treat "the deployment no longer exists" as the end of the refresh for that bundle: check the
 * result of the refresh's own undeploy (or the deployment model) and skip {@code refreshCurrentRevision}'s deploy (and
 * uninstall the bundle) when somebody else removed the deployment; better, serialize refresh and deployment operations
 * on the same deployment. Verified: a trial that skips the redeploy when the refresh's own undeploy fails with
 * WFLYCTL0216 (fix.diff in the issue folder, subsystem jar replaced in a server copy) makes the test pass (log: "TRIAL:
 * deployment of refresh-target:0.0.0 was removed during the refresh, not deployed again"); the 60 s stall of wildfly/26
 * remains with that trial.
 *
 * <h2>Regression vs WF41</h2>
 * no (measured: the same test on the WF41 server built from origin/WF41 77f95be6, framework 6.6.0.Final, same patched
 * WildFly 41.0.1, JDK 25, jacc policy removed from standalone-osgi.xml as r8 42d6c470 does: same failure, undeploy after
 * 64991 ms, target.jar deployed again).
 *
 * <h2>Related</h2>
 * wildfly/26 (RefreshRedeployDeadlockIssueTestCase: the refresh's synchronous server operations under the framework
 * locks; causes the 60 s stall seen here, but its fix does not prevent the resurrection, see Root cause); ee/10 and
 * lifecycle/06 (refresh/update persistence of deployed bundles); connect/22 (name-based deferred undeploy).
 */
public class RefreshRedeploysUndeployedBundleIssueTestCase {

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

    /** Needs 5 s to stop (flushes, joins a worker, ...): the refresh stops the bundle first, this is the window of the test */
    public static class SlowStopActivator implements BundleActivator {
        @Override
        public void start(BundleContext context) {
        }

        @Override
        public void stop(BundleContext context) throws Exception {
            Thread.sleep(5000);
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
    public void testUndeployDuringRefresh() throws Exception {
        prepareServer("issues-shutdown-refreshundeployed-server");
        startServer("console.out");
        String observed;
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            assertSuccess(client, deploy("target.jar", bundle("target.jar", "refresh-target", SlowStopActivator.class)), "Precondition: deploy target.jar");
            assertSuccess(client, deploy("trigger.jar", bundle("trigger.jar", "refresh-trigger", RefreshingActivator.class)), "Precondition: deploy trigger.jar");

            // The refresh stops refresh-target first (5 s): wait until it is STOPPING, then the administrator undeploys it
            long deadline = System.currentTimeMillis() + 20000;
            String state = bundleState(client, "target.jar");
            while ("STOPPING".equals(state) == false && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
                state = bundleState(client, "target.jar");
            }
            assertTrue("Precondition: the refresh stops refresh-target, state " + state, "STOPPING".equals(state));

            long begin = System.currentTimeMillis();
            ModelNode result = client.execute(undeployAndRemove("target.jar"));
            long millis = System.currentTimeMillis() - begin;
            assertTrue("Precondition: undeploy and remove of target.jar: " + result, "success".equals(result.get("outcome").asString()));
            String afterUndeploy = deployments(client);

            // The refresh is over a few seconds later
            Thread.sleep(10000);
            String deployments = deployments(client);
            observed = "the undeploy+remove of target.jar succeeded after " + millis + " ms (deployments then: " + afterUndeploy
                    + "), 10 s later the deployments are " + deployments + " and the bundle at location target.jar is "
                    + bundleState(client, "target.jar");
            if (deployments.contains("target.jar") == false)
                return;
        }
        fail("Expected target.jar to stay undeployed after the administrator removed it while the bundle was being refreshed, but "
                + observed + ": the refresh deployed it again (and it is in the persistent configuration)");
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
