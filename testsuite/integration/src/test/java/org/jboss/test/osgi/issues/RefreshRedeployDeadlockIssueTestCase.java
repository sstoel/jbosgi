/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2026, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.test.osgi.issues;

import java.io.InputStream;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.client.helpers.standalone.DeploymentPlanBuilder;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * Refreshing a deployed bundle while another deployment operation runs hangs the server: the refresh holds the
 * framework locks and waits for a management operation, the management operation holds the controller lock and waits
 * for the framework locks. Every deployment and osgi management operation then fails after 60 s lock timeouts, and the
 * refresh itself can stay blocked for many minutes.
 *
 * <h2>Severity</h2>
 * critical. Found by the concurrent-deployment soak (round 3): with one bundle calling
 * FrameworkWiring.refreshBundles(null) every 2-6 s next to 6 clients deploying/undeploying a set of bundles, the
 * throughput dropped from ~140 to 16 operations/min, 69 of 420 operations hit the 60 s client timeout, and the
 * "Framework Refresh Thread" was stuck for more than 600 s (25 thread dumps, $SP/agents/wildfly/soak-r8). Any agent that
 * refreshes bundles (provisioning, Felix web console, a bundle update + refresh) while deployments happen
 * (deployment scanner, CI, management clients) wedges the OSGi layer of the server.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem, {@code org.jboss.as.osgi.service.BundleLifecycleIntegration}
 * ({@code RecreateCurrentRevisionPolicy}, {@code undeployRevision}), together with jbosgi-framework
 * {@code FrameworkWiringImpl.refreshBundlesInternal} (refresh under the FrameworkWiringLock) and
 * {@code BundleInstallProcessor.undeploy} (uninstall under the framework locks from an MSC stop).
 *
 * <h2>Found by</h2>
 * wildfly workstream, server soak under concurrent deployment load (JDK 25, Docker eclipse-temurin:25-jdk). Typical
 * thread dump:
 * <pre>
 * "Framework Refresh Thread" WAITING  ServerDeploymentHelper.deploy/undeploy
 *     &lt;- BundleLifecycleIntegration$...$RecreateCurrentRevisionPolicy.refreshCurrentRevision / startBundleRefresh
 *     &lt;- UserBundleState.refresh &lt;- FrameworkWiringImpl.refreshBundlesInternal      (holds FrameworkWiringLock + bundle locks)
 * "pool-13-thread-3" WAITING  ModelControllerLock.lockInterruptibly &lt;- DeploymentAddHandler   (the refresh's own deploy op)
 * "MSC service thread 1-3" TIMED_WAITING  LockManagerImpl.lockItemsInternal &lt;- BundleManagerPlugin.uninstallBundle
 *     &lt;- BundleInstallProcessor.undeploy &lt;- DeploymentUnitPhaseService.stop      (stop of a concurrently undeployed bundle,
 *                                                                                  whose operation holds the controller lock)
 * "External Management Request Threads -- n" WAITING  ModelControllerLock (all other management requests)
 * </pre>
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 FrameworkWiring.refreshBundles javadoc: "This method returns to the caller immediately and then performs
 * the following steps on a separate thread" ... "When this process completes after the bundles are refreshed, the
 * Framework will fire a Framework event of type FrameworkEvent.PACKAGES_REFRESHED"; the refresh must complete, and
 * unrelated lifecycle operations (Bundle.uninstall of another bundle, here through the server deployment) must not be
 * blocked indefinitely by it (Core 4.4.x: lifecycle operations may only wait a bounded time, then throw a
 * BundleException). https://docs.osgi.org/specification/osgi.core/8.0.0/framework.wiring.html
 *
 * <h2>Code location</h2>
 * jbosgi-framework core/src/main/java/org/jboss/osgi/framework/internal/FrameworkWiringImpl.java:188
 * ({@code lockManager.lockItems(Method.REFRESH, items)}, held while the bundles are stopped and refreshed);
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/BundleLifecycleIntegration.java:524-575
 * (RecreateCurrentRevisionPolicy: startBundleRefresh -&gt; undeployRevision -&gt; {@code server.undeploy(runtimeName)},
 * line 360; refreshCurrentRevision -&gt; {@code server.deploy(runtimeName, input)}, line 562 - synchronous management
 * operations through the ModelControllerClient, without a timeout, called while the refresh holds the framework locks);
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/deployment/BundleInstallProcessor.java:105
 * ({@code bundleManager.uninstallBundle} in an MSC stop of a deployment operation that holds the controller lock;
 * waits up to 60 s, LockManagerIntegration). Pre-existing: the same refresh policy with synchronous
 * {@code server.deploy} exists on origin/WF41 (line 491).
 *
 * <h2>Root cause</h2>
 * Lock order inversion across two lock systems: refresh = framework locks, then the management controller lock (via
 * the synchronous deploy/undeploy); deployment operation = controller lock, then framework locks (via the MSC stop of
 * the deployment). Same family as connect/25 (synchronous server undeploy under framework locks), but on the plain
 * refresh path, without any bundle uninstalled while in use.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full \
 *     -pl testsuite/integration test -Dtest=RefreshRedeployDeadlockIssueTestCase
 * </pre>
 * Deterministic: the refreshed bundle's activator needs 3 s to stop (while the refresh holds the locks); 1 s after the
 * refresh started the test undeploys an unrelated bundle through the ModelControllerClient. The test waits 25 s and
 * reports the state. Afterwards the server needs several minutes to recover (lock timeouts), so run it alone.
 * (on JDK 24+ the testsuite needs surefire.security.args without -Djava.security.manager, see TestJvmSecurityManagerIssueTestCase)
 *
 * <h2>Observed</h2>
 * <pre>
 * java.lang.AssertionError: Expected a refresh and a concurrent unrelated undeploy to complete within 25 s, but after
 *   27024 ms the refresh has not completed and the undeploy of the bystander has not returned;
 *   Framework Refresh Thread (WAITING):
 *     at ...ServerDeploymentHelper.undeploy(ServerDeploymentHelper.java:95)
 *     at ...BundleLifecycleIntegration$BundleLifecycleImpl$1.run(BundleLifecycleIntegration.java:360)
 *     at ...BundleLifecycleIntegration$BundleLifecycleImpl.undeployRevision(BundleLifecycleIntegration.java:372)
 *     at ...BundleLifecycleIntegration$BundleLifecycleImpl$RecreateCurrentRevisionPolicy.startBundleRefresh(BundleLifecycleIntegration.java:543)
 *     at ...UserBundleState.refresh(UserBundleState.java:406)
 *     at ...FrameworkWiringImpl.refreshBundlesInternal(FrameworkWiringImpl.java:223)
 * </pre>
 * Server log of the same run: the bystander's undeploy took 122 s (two 60 s LockException timeouts,
 * WFLYSRV0043 during undeploy phase REGISTER) and the management operation failed.
 *
 * <h2>Expected</h2>
 * Both operations complete within seconds; PACKAGES_REFRESHED is fired.
 *
 * <h2>Reference behaviour</h2>
 * Not applicable (WildFly integration).
 *
 * <h2>Suggested fix (not applied)</h2>
 * Do not run management operations synchronously while holding framework locks: let the refresh policy release the
 * framework locks before the server undeploy/redeploy (or perform the redeploy asynchronously after the refresh and
 * fire PACKAGES_REFRESHED when it completed), or refresh the bundle in place (new revision through the
 * BundleLifecycle without a server redeploy). At least give ServerDeploymentHelper calls a timeout.
 *
 * <h2>Regression vs WF41</h2>
 * no (measured on a WF41 server built from origin/WF41 77f95be6 on the same patched WildFly 41.0.1, JDK 25: same
 * failure): after 25 s the refresh has not completed and the undeploy of the bystander has not returned. Same refresh
 * policy on origin/WF41. With the unmodified WF41 standalone-osgi.xml most deferred starts fail (JBAS011969, jacc
 * {@code <policy>}); the WF41 run used harness adaptations only, no change to this test or to the code under test: the
 * jacc {@code <policy>} removed from standalone-osgi.xml, as r8 42d6c470 does (without it org.wildfly.security.policy
 * fails on WildFly 41 and every deferred start fails with JBAS011969), and the r8 Arquillian
 * JBOSGiApplicationArchiveProcessor from 54b65e85 (in-container ManagementClient lookup); see the regression-2
 * workstream NOTES.md.
 */
@RunWith(Arquillian.class)
public class RefreshRedeployDeadlockIssueTestCase {

    static final String TARGET = "refresh-deadlock-target.jar";
    static final String BYSTANDER = "refresh-deadlock-bystander.jar";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @ArquillianResource
    ManagementClient managementClient;

    /** Activator of the refreshed bundle: its stop takes a moment, as many real activators do */
    public static class SlowStopActivator implements BundleActivator {
        @Override
        public void start(BundleContext context) {
        }

        @Override
        public void stop(BundleContext context) throws Exception {
            Thread.sleep(3000);
        }
    }

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "refresh-deadlock-tests");
        archive.addClasses(ServerDeploymentHelper.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(FrameworkWiring.class);
                builder.addImportPackages(ClientConstants.class, ModelControllerClient.class, ManagementClient.class, DeploymentPlanBuilder.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Test
    public void testRefreshConcurrentWithUndeploy() throws Exception {
        deployer.deploy(TARGET);
        deployer.deploy(BYSTANDER);
        Bundle target = null;
        for (Bundle bundle : context.getBundles())
            if (TARGET.equals(bundle.getSymbolicName()))
                target = bundle;
        Assert.assertNotNull("Precondition: target bundle deployed", target);
        Assert.assertEquals("Precondition: target bundle ACTIVE", Bundle.ACTIVE, target.getState());

        // Refresh the target; the framework stops it (3 s) while holding its locks, then redeploys it through the management API
        final long t0 = System.currentTimeMillis();
        final CountDownLatch refreshed = new CountDownLatch(1);
        final AtomicReference<FrameworkEvent> refreshEvent = new AtomicReference<FrameworkEvent>();
        context.getBundle(0).adapt(FrameworkWiring.class).refreshBundles(Collections.singleton(target), new FrameworkListener() {
            @Override
            public void frameworkEvent(FrameworkEvent event) {
                refreshEvent.set(event);
                refreshed.countDown();
            }
        });

        // Meanwhile an unrelated deployment operation: undeploy the bystander bundle
        Thread.sleep(1000);
        final CountDownLatch undeployed = new CountDownLatch(1);
        final AtomicReference<String> undeployResult = new AtomicReference<String>("not returned");
        Thread undeployer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    new ServerDeploymentHelper(managementClient.getControllerClient()).undeploy(BYSTANDER);
                    undeployResult.set("ok");
                } catch (Throwable th) {
                    undeployResult.set(String.valueOf(th));
                }
                undeployed.countDown();
            }
        }, "refresh-deadlock-undeployer");
        undeployer.setDaemon(true);
        undeployer.start();

        boolean undeployDone = undeployed.await(25, TimeUnit.SECONDS);
        boolean refreshDone = refreshed.await(1, TimeUnit.SECONDS);
        String summary = "after " + (System.currentTimeMillis() - t0) + " ms the refresh " + (refreshDone ? "completed (event " + refreshEvent.get().getType() + ")" : "has not completed")
                + " and the undeploy of the bystander " + (undeployDone ? "returned: " + undeployResult.get() : "has not returned") + stackOf("Framework Refresh Thread");
        Assert.assertTrue("Expected a refresh and a concurrent unrelated undeploy to complete within 25 s, but " + summary,
                undeployDone && refreshDone && "ok".equals(undeployResult.get()) && refreshEvent.get().getType() == FrameworkEvent.PACKAGES_REFRESHED);
        deployer.undeploy(TARGET);
    }

    private static String stackOf(String threadName) {
        for (java.util.Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            if (threadName.equals(entry.getKey().getName())) {
                StringBuilder sb = new StringBuilder("; " + threadName + " (" + entry.getKey().getState() + "):");
                int n = 0;
                for (StackTraceElement frame : entry.getValue()) {
                    String text = frame.toString();
                    if (text.contains("osgi") || text.contains("ServerDeploymentHelper")) {
                        sb.append("\n    at ").append(text);
                        if (++n == 6)
                            break;
                    }
                }
                return sb.toString();
            }
        }
        return "";
    }

    @Deployment(name = TARGET, managed = false, testable = false)
    public static JavaArchive getTarget() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, TARGET);
        archive.addClasses(SlowStopActivator.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addBundleActivator(SlowStopActivator.class);
                builder.addImportPackages(BundleActivator.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = BYSTANDER, managed = false, testable = false)
    public static JavaArchive getBystander() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, BYSTANDER);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                return builder.openStream();
            }
        });
        return archive;
    }
}
