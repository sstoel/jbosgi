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
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;

import jakarta.annotation.Resource;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

/**
 * Bundle.start() of a deployed bundle whose deferred module phase is not active yet hangs forever when it is called from
 * a service start (the BundleActivator.start of another deployed bundle on an MSC thread, a WAR ServletContextListener,
 * an EJB @Startup @PostConstruct): the deploy operation never completes, the server can no longer shut down, and at boot
 * the server fails and its JVM never exits.
 *
 * <h2>Severity</h2>
 * critical. A permanent hang reachable by ordinary bundles and EE applications: the thread that runs the start is lost for
 * good; at deploy time the operation only returns because of the management blocking timeout (rolled back, "restart
 * required") and the next server shutdown (:shutdown, :reload, SIGTERM/Ctrl-C) never finishes (the MSC container cannot
 * stop a service whose start never returns); at boot (persisted deployments) the boot fails after the blocking timeouts
 * (WFLYSRV0056 "Server boot has failed in an unrecoverable manner; exiting") and the JVM then never exits. One concrete
 * way to get the "WildFly shutdown hangs" symptom.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem, {@code org.jboss.as.osgi.service.BundleLifecycleIntegration.BundleLifecycleImpl}
 * ({@code start} -&gt; {@code activateDeferredPhase}).
 *
 * <h2>Found by</h2>
 * shutdown round: workstream sd-msc (code-driven hunt for blocking waits on MSC threads; activator case) and,
 * independently, workstream sd-srv-ee (EE callers: WAR ServletContextListener, @Startup @Singleton @PostConstruct; the
 * persistent-stop trigger and the boot case; the revised fix). Merged into this class.
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8, Bundle.start(int) javadoc (framework.api, org.osgi.framework.Bundle), step 1: "If this bundle is in the
 * process of being activated or deactivated then this method must wait for activation or deactivation to complete before
 * continuing. If this does not occur in a reasonable time, a {@code BundleException} is thrown to indicate this bundle was
 * unable to be started." Starting a bundle from another bundle's activator or from an application is ordinary usage
 * (provisioning/management bundles, EE applications that use their bundles; Bundle-ActivationPolicy lazy activation
 * triggered by a class load reaches the same code), and Bundle.start may block only a bounded time.
 * https://docs.osgi.org/specification/osgi.core/8.0.0/framework.api.html
 * WildFly/MSC side: a Service.start() (or a thread such a start waits for) that waits for the stability of the whole
 * container it runs in can never return.
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/BundleLifecycleIntegration.java:428-433
 * <pre>
 * phaseService.setMode(Mode.ACTIVE);
 * phaseService.getServiceContainer().awaitStability(failed, problems);   // whole container, no timeout
 * </pre>
 * reached from BundleLifecycleImpl.start (line 293-328) for every bundle deployment whose FIRST_MODULE_USE phase was
 * deferred by DeferredPhaseProcessor, i.e. the bundle was not resolved when it was deployed. The natural way to get such a
 * deployment: a deployed bundle that was stopped persistently (Bundle.stop() or /subsystem=osgi/bundle=X:stop) is not
 * auto-started after the next restart, so BundleResolveProcessor does not resolve it and its phase is deferred
 * (WFLYSRV0069 "Defer FIRST_MODULE_USE for X making it NEVER"); also bundles deployed while an import is missing,
 * bundles installed through BundleContext.installBundle, refreshed bundles (redeployed with autostart false).
 * Introduced by RTR commit 16261b69 ("Fixes + jakarta change", 2023-10-24), which replaced a StabilityMonitor on the
 * deployment's own two controllers (the {@code StabilityMonitor} import is still there, unused) by
 * {@code ServiceContainer.awaitStability}. Unchanged on WF41-osgi-r8 (f025b420).
 *
 * <h2>Root cause</h2>
 * {@code ServiceContainerImpl.awaitStability(Set, Set)} (jboss-msc 1.5.6) waits until the container has no service in
 * transition at all, without a timeout. When the caller runs inside a Service.start() (here
 * {@code BundleActivateProcessor.BundleActivateService.start} -&gt; BundleActivator.start of the starter bundle on an MSC
 * thread), or on a thread such a start waits for (the asynchronous UndertowDeploymentService start runs the
 * ServletContextListener on a "ServerService Thread Pool" thread), its own service is in transition (STARTING) until the
 * call returns: the container can never become stable, the thread waits forever (self-deadlock). At boot every boot
 * operation step then times out and rolls back, the server gives up (WFLYSRV0056) and calls System.exit, whose shutdown
 * hook waits for the MSC container forever.
 * <br>Cycle: thread "MSC service thread 1-N" / "ServerService Thread Pool -- N" runs the start of starter.jar.Activate /
 * starter.war undertow-deployment (service STARTING) and waits in ServiceContainerImpl.awaitStability for
 * "unstableServices == 0"; that counter includes the service it is running.
 *
 * <h2>Reproduction</h2>
 * Each method starts its own copy of the testsuite server (target/wildfly-41.0.1.Final, port offset 2200, override with
 * -Dissue.port.offset=N; jboss.as.management.blocking.timeout=20 so that the deploy operation returns):
 * <ul>
 * <li>testStartOfDeferredBundleFromActivator: deploys deferred.jar (imports a package nobody exports yet -&gt; deferred),
 * api.jar (exports it), starter.jar (its BundleActivator.start calls start() on deferred.jar), then :shutdown.</li>
 * <li>testStartOfDeferredBundleFromServletContextListener: the same with starter.war, a WAR whose
 * ServletContextListener.contextInitialized (BundleContext injected with @Resource) calls start().</li>
 * <li>testStartAtBootOfPersistentlyStoppedBundle: deploys deferred-target.jar (resolvable) and
 * deferred-starter-listener.war (same listener; works, the bundle is not deferred yet), stops the bundle with
 * /subsystem=osgi/bundle=deferred-target.jar:stop, restarts the server: the bundle is deferred now and the WAR starts it
 * during the boot.</li>
 * </ul>
 * <pre>
 * cd jbosgi
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full \
 *     -pl testsuite/integration test -Dtest=DeferredStartFromActivatorHangIssueTestCase
 * </pre>
 * (on JDK 24+ the testsuite needs surefire.security.args without -Djava.security.manager, see
 * TestJvmSecurityManagerIssueTestCase). Run the class alone (about 7 minutes, the hung servers are killed by PID).
 * Deterministic: no timing involved, the hang happens on every run.
 *
 * <h2>Observed</h2>
 * JDK 25, WF41-osgi-r8 (jbosgi f025b420, framework d7b20d08): all three methods fail on every run (activator method
 * 5 of 5 runs, WAR and boot methods 2 of 2):
 * <pre>
 * testStartOfDeferredBundleFromActivator: java.lang.AssertionError: Expected the deploy of a bundle whose
 *   BundleActivator.start calls Bundle.start() on a deployed bundle with a deferred module phase to succeed (target ACTIVE)
 *   and the server to shut down afterwards, but: deferred.jar before: INSTALLED; deploy of starter.jar after 30051 ms:
 *   failed WFLYCTL0344: Operation timed out awaiting service container stability; deferred.jar after: ACTIVE; server still
 *   running 90 s after :shutdown
 *   "MSC service thread 1-4" WAITING (on object monitor)
 *     at org.jboss.msc.service.ServiceContainerImpl.awaitStability(ServiceContainerImpl.java:285)
 *     at org.jboss.as.osgi.service.BundleLifecycleIntegration$BundleLifecycleImpl.activateDeferredPhase(BundleLifecycleIntegration.java:430)
 *     at org.jboss.as.osgi.service.BundleLifecycleIntegration$BundleLifecycleImpl.start(BundleLifecycleIntegration.java:328)
 *     at org.jboss.osgi.framework.internal.BundleManagerPlugin.startBundleLifecycle(BundleManagerPlugin.java:603)
 *     at org.jboss.osgi.framework.internal.AbstractBundleState.start(AbstractBundleState.java:476)
 *     at ...DeferredStartFromActivatorHangIssueTestCase$TargetStarter.startTarget
 *     at ...DeferredStartFromActivatorHangIssueTestCase$StarterActivator.start
 *     at org.jboss.osgi.framework.internal.UserBundleState.startInternalNow(UserBundleState.java:615)
 *     at org.jboss.osgi.framework.internal.BundleManagerPlugin.startBundle(BundleManagerPlugin.java:612)
 *     at org.jboss.as.osgi.deployment.BundleActivateProcessor$BundleActivateService.start(BundleActivateProcessor.java:131)
 *   90 s after :shutdown: the same MSC thread still in awaitStability; "Thread-1" WAITING CountDownLatch.await &lt;-
 *   org.jboss.as.server.BootstrapImpl$ShutdownHook.shutdown(BootstrapImpl.java:281); "Management Triggered Shutdown"
 *   Thread.join &lt;- ApplicationShutdownHooks.runHooks &lt;- System.exit
 * testStartOfDeferredBundleFromServletContextListener: ... deploy of starter.war after 30058 ms: failed WFLYCTL0344 ...
 *   server still running 90 s after :shutdown ("ServerService Thread Pool -- N" in awaitStability
 *   &lt;- StarterListener.contextInitialized &lt;- io.undertow.servlet.core.ApplicationListeners.contextInitialized)
 * testStartAtBootOfPersistentlyStoppedBundle: ... restart with the persistently stopped bundle and the WAR whose listener
 *   starts it: boot failed (WFLYSRV0056 Server boot has failed in an unrecoverable manner; exiting) after 106462 ms; the
 *   server JVM did not exit within 60 s
 *   "ServerService Thread Pool -- 84" WAITING ServiceContainerImpl.awaitStability &lt;- activateDeferredPhase:430
 *     &lt;- ... StarterListener.contextInitialized; "Controller Boot Thread" Thread.join &lt;- ApplicationShutdownHooks.runHooks
 *     &lt;- System.exit &lt;- SystemExiter
 * </pre>
 * The target bundle itself is started by the activated phases ("Bundle started: deferred-target"); only the waiting caller
 * never returns.
 *
 * <h2>Expected</h2>
 * The deployments succeed, the target bundle is ACTIVE, the server boots without errors and shuts down normally.
 *
 * <h2>Reference behaviour</h2>
 * n/a: WildFly OSGi subsystem integration (MSC services and management operations); Equinox and Felix have no
 * equivalent of this code path.
 *
 * <h2>Suggested fix</h2>
 * Wait only for this deployment, never for the whole container, and with a bound: after
 * {@code phaseService.setMode(Mode.ACTIVE)} wait until the deployment's {@code Activate} service
 * ({@code depUnit.getServiceName().append("Activate")}) is UP (success) or START_FAILED, or one of its phase services
 * (FIRST_MODULE_USE, POST_MODULE, INSTALL) is START_FAILED (failure, reported through the existing failed/problems
 * handling), with a timeout that becomes a BundleException (Bundle.start may block only "a reasonable time"); restore the
 * interrupt status. Verified (sd-srv-ee's trial, fix.diff in the issue folder, subsystem jar replaced in a server copy):
 * all three methods pass (fix-run.log, 21 s; the boot method 5 of 5 more runs: WFLYSRV0025, bundle ACTIVE).
 * Note: the first trial, a {@code StabilityMonitor} on the deployment and phase controllers with a 60 s timeout
 * (fix-stabilitymonitor.diff, the pre-16261b69 approach), passes both deploy-time methods but is racy at boot: the boot
 * method failed 2 of 5 runs ("booted with errors (WFLYSRV0026)", JBAS011969 Cannot activate deferred module phase, the WAR
 * fails, the bundle stays RESOLVED) because the monitor reports the deployment as stable with "problems" while its phase
 * services still wait for dependencies that are not up yet during the boot.
 *
 * <h2>Regression vs WF41</h2>
 * no (measured: the same class on the WF41 server built from origin/WF41 77f95be6, framework 6.6.0.Final, same patched
 * WildFly 41.0.1, JDK 25, jacc policy removed from standalone-osgi.xml as r8 42d6c470 does: all three methods fail the same
 * way; boot failed after 111291 ms and the JVM did not exit). The container-wide wait exists since RTR commit 16261b69
 * (2023).
 *
 * <h2>Related</h2>
 * The same wait is reached with the FrameworkWiringLock and the bundle locks held: FrameworkWiringImpl.refreshBundlesInternal
 * restarts the refreshed bundles (their redeployed deployments are always deferred, autostart false); and on the MSC
 * thread of FrameworkActive (start level increase). Same fix site. Different from lifecycle/04 (installBundle from an
 * activator: nested management operation) and wildfly/26 (synchronous server deploy/undeploy under the refresh locks).
 */
public class DeferredStartFromActivatorHangIssueTestCase {

    static final int OFFSET = Integer.getInteger("issue.port.offset", 2200);
    static final int MGMT_PORT = 9990 + OFFSET;
    /** jboss.as.management.blocking.timeout of the test server (default 300 s), so that the deploy operation returns */
    static final int BLOCKING_TIMEOUT = 20;
    /** deployment names of the boot scenario (sd-srv-ee's names; with them the WAR starts the bundle while the bundle deployment is still being processed at boot) */
    static final String BOOT_TARGET = "deferred-target.jar";
    static final String API_PACKAGE = "org.jboss.test.osgi.issues.shutdown.deferredapi";

    /** Starts the bundle "deferred-target" from its own start, as provisioning/management bundles do */
    public static class StarterActivator implements BundleActivator {
        @Override
        public void start(BundleContext context) throws Exception {
            TargetStarter.startTarget(context);
        }

        @Override
        public void stop(BundleContext context) {
        }
    }

    /** Starts the bundle "deferred-target" when the web application starts (an EE application "kicking" its bundles) */
    @WebListener
    public static class StarterListener implements ServletContextListener {
        @Resource
        BundleContext context;

        @Override
        public void contextInitialized(ServletContextEvent event) {
            try {
                TargetStarter.startTarget(context);
            } catch (BundleException ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public void contextDestroyed(ServletContextEvent event) {
        }
    }

    /** Shared by the starters: Bundle.start() of the bundle with symbolic name "deferred-target" */
    public static class TargetStarter {
        static void startTarget(BundleContext context) throws BundleException {
            for (Bundle bundle : context.getBundles()) {
                if ("deferred-target".equals(bundle.getSymbolicName()))
                    bundle.start();
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
    public void testStartOfDeferredBundleFromActivator() throws Exception {
        runDeployScenario("a bundle whose BundleActivator.start", "starter.jar", starterBundle());
    }

    @Test
    public void testStartOfDeferredBundleFromServletContextListener() throws Exception {
        runDeployScenario("a WAR whose ServletContextListener.contextInitialized", "starter.war", starterWar());
    }

    /**
     * The natural trigger: a deployed bundle that was stopped persistently (Bundle.stop() or /subsystem=osgi/bundle=X:stop)
     * is not auto-started after a restart, so its deployment is not resolved at boot and its FIRST_MODULE_USE phase is
     * deferred; a persisted WAR whose ServletContextListener starts it hangs the boot.
     */
    @Test
    public void testStartAtBootOfPersistentlyStoppedBundle() throws Exception {
        prepareServer("issues-shutdown-deferredstart-server");
        startServer("console.out");
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            assertSuccess(client, deploy(BOOT_TARGET, plainTargetBundle()), "Precondition: deploy " + BOOT_TARGET + " (resolvable)");
            assertSuccess(client, deploy("deferred-starter-listener.war", starterWar()), "Precondition: deploy the WAR while the bundle is not deferred");
            ModelNode stop = new ModelNode();
            stop.get("operation").set("stop");
            stop.get("address").add("subsystem", "osgi").add("bundle", BOOT_TARGET);
            ModelNode result = client.execute(stop);
            assertTrue("Precondition: persistent stop of " + BOOT_TARGET + ": " + result + ", state " + bundleState(client, BOOT_TARGET),
                    "success".equals(result.get("outcome").asString()) && "RESOLVED".equals(bundleState(client, BOOT_TARGET)));
        }
        assertTrue("Precondition: the first :shutdown completes", shutdownServer(60));
        assertTrue("Precondition: the server script of the first run ends", waitForExit(30));

        // Restart: deferred.jar is persistently stopped -> deferred (WFLYSRV0069 ... NEVER); the WAR starts it during the boot
        StringBuilder observed = new StringBuilder("restart with the persistently stopped bundle and the WAR whose listener starts it: ");
        File out = new File(home, "console-restart.out");
        long begin = System.currentTimeMillis();
        startProcess(out);
        String outcome = null;
        long deadline = begin + BLOCKING_TIMEOUT * 1000L * 6 + 60000;
        while (outcome == null && System.currentTimeMillis() < deadline) {
            String text = out.exists() ? new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8) : "";
            if (text.contains("WFLYSRV0025"))
                outcome = "booted (WFLYSRV0025)";
            else if (text.contains("WFLYSRV0026"))
                outcome = "booted with errors (WFLYSRV0026)";
            else if (text.contains("WFLYSRV0056"))
                outcome = "boot failed (WFLYSRV0056 Server boot has failed in an unrecoverable manner; exiting)";
            else if (process.isAlive() == false)
                outcome = "server process ended";
            else
                Thread.sleep(500);
        }
        observed.append(outcome == null ? "no boot outcome" : outcome).append(" after ").append(System.currentTimeMillis() - begin).append(" ms");
        String targetState = null;
        String dump = "";
        boolean stopped;
        if (outcome != null && outcome.startsWith("booted")) {
            try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
                targetState = bundleState(client, BOOT_TARGET);
                observed.append(", " + BOOT_TARGET + " ").append(targetState);
            }
            stopped = shutdownServer(60);
            observed.append(stopped ? "; :shutdown completed" : "; server still running 60 s after :shutdown");
        } else {
            // a failed boot exits the JVM by itself (System.exit)
            if (outcome == null)
                dump = threadDump("jstack-boot.txt", true);
            stopped = waitForExit(60);
            observed.append(stopped ? "; the server JVM exited" : "; the server JVM did not exit within 60 s");
        }
        if (!stopped)
            dump = threadDump("jstack-after-boot.txt", true);
        if (outcome == null || !outcome.startsWith("booted (WFLYSRV0025)") || !"ACTIVE".equals(targetState) || !stopped) {
            fail("Expected the server to boot without errors with a persisted WAR whose ServletContextListener starts a persistently "
                    + "stopped (deferred) bundle deployment (bundle ACTIVE) and to shut down afterwards, but: " + observed
                    + (dump.isEmpty() ? "" : "\nThreads (server thread dump, trimmed):\n" + dump));
        }
    }

    private void runDeployScenario(String starterDescription, String starterName, byte[] starter) throws Exception {
        prepareServer("issues-shutdown-deferredstart-server");
        startServer("console.out");
        StringBuilder observed = new StringBuilder();
        boolean deployOk;
        String targetState;
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            assertSuccess(client, deploy("deferred.jar", deferredBundle()), "Precondition: deploy deferred.jar");
            assertSuccess(client, deploy("api.jar", apiBundle()), "Precondition: deploy api.jar");
            observed.append("deferred.jar before: ").append(bundleState(client, "deferred.jar")).append("; ");

            long begin = System.currentTimeMillis();
            Future<ModelNode> future = executor.submit(() -> client.execute(deploy(starterName, starter)));
            ModelNode result;
            try {
                result = future.get(BLOCKING_TIMEOUT * 3 + 30, TimeUnit.SECONDS);
            } catch (TimeoutException ex) {
                result = null;
            }
            long millis = System.currentTimeMillis() - begin;
            deployOk = result != null && "success".equals(result.get("outcome").asString());
            observed.append("deploy of ").append(starterName).append(" after ").append(millis).append(" ms: ")
                    .append(result == null ? "no answer" : result.get("outcome").asString() + " " + result.get("failure-description").asString())
                    .append("; ");
            targetState = bundleState(client, "deferred.jar");
            observed.append("deferred.jar after: ").append(targetState).append("; ");
        }

        String dump = threadDump("jstack-after-deploy.txt", false);
        boolean shutdownOk = shutdownServer(90);
        observed.append(shutdownOk ? "server shut down normally" : "server still running 90 s after :shutdown");
        String shutdownDump = shutdownOk ? "" : threadDump("jstack-after-shutdown.txt", true);

        if (!deployOk || !"ACTIVE".equals(targetState) || !shutdownOk) {
            fail("Expected the deploy of " + starterDescription + " calls Bundle.start() on a deployed bundle with a deferred "
                    + "module phase to succeed (target ACTIVE) and the server to shut down afterwards, but: " + observed
                    + "\nThreads (server thread dump after the deploy, trimmed):\n" + dump
                    + (shutdownOk ? "" : "\nThreads (server thread dump 90 s after :shutdown, trimmed):\n" + shutdownDump));
        }
    }

    // ---------------------------------------------------------------- bundles

    private static byte[] deferredBundle() {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "deferred.jar");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName("deferred-target");
                builder.addImportPackages(API_PACKAGE);
                return builder.openStream();
            }
        });
        return bytes(archive);
    }

    private static byte[] apiBundle() {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "api.jar");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName("deferred-api");
                builder.addExportPackages(API_PACKAGE);
                return builder.openStream();
            }
        });
        return bytes(archive);
    }

    private static byte[] starterBundle() {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "starter.jar");
        archive.addClasses(StarterActivator.class, TargetStarter.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName("deferred-starter");
                builder.addBundleActivator(StarterActivator.class);
                builder.addImportPackages(BundleActivator.class);
                return builder.openStream();
            }
        });
        return bytes(archive);
    }

    private static byte[] plainTargetBundle() {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "deferred.jar");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName("deferred-target");
                return builder.openStream();
            }
        });
        return bytes(archive);
    }

    private static byte[] starterWar() {
        WebArchive war = ShrinkWrap.create(WebArchive.class, "starter.war");
        war.addClasses(StarterListener.class, TargetStarter.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        war.as(ZipExporter.class).exportTo(out);
        return out.toByteArray();
    }

    private static byte[] bytes(JavaArchive archive) {
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

    private void startProcess(File out) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(new File(home, "bin/standalone.sh").getPath(), "-c", "standalone-osgi.xml",
                "-Djboss.socket.binding.port-offset=" + OFFSET, "-Djboss.as.management.blocking.timeout=" + BLOCKING_TIMEOUT);
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        builder.environment().remove("JAVA_OPTS");
        builder.redirectErrorStream(true);
        builder.redirectOutput(out);
        process = builder.start();
    }

    /** waits for the server JVM (and script) started by this test to end */
    private boolean waitForExit(int seconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (process != null && process.isAlive() && System.currentTimeMillis() < deadline)
            Thread.sleep(500);
        return process == null || process.isAlive() == false;
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
                    result.append(trim(thread, shutdownThreads ? 16 : 40)).append("\n\n");
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

    /** :shutdown, then wait up to the given seconds for the server JVM to exit */
    private boolean shutdownServer(int seconds) throws Exception {
        ProcessHandle jvm = serverJvm();
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            ModelNode op = new ModelNode();
            op.get("operation").set("shutdown");
            Future<ModelNode> future = executor.submit(() -> client.execute(op));
            try {
                future.get(10, TimeUnit.SECONDS);
            } catch (Exception ex) {
                // the connection is closed by the shutdown, or the shutdown hangs
            }
        }
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (jvm != null && jvm.isAlive() && System.currentTimeMillis() < deadline)
            Thread.sleep(500);
        return jvm == null || jvm.isAlive() == false;
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
