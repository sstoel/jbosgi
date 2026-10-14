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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.client.helpers.standalone.DeploymentPlanBuilder;
import org.jboss.dmr.ModelNode;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * In WildFly, {@code FrameworkWiring.refreshBundles} does not release a deployed bundle that was uninstalled while in
 * use: it stays removal pending and deployed after the refresh, and the later uninstall of its last user blocks for 60
 * seconds on a lock timeout while the framework undeploys it from inside that uninstall.
 *
 * <h3>Severity</h3>
 * major. The standard management sequence "uninstall, then refresh" (Felix/Equinox consoles, Apache Karaf-style
 * provisioning, bnd launchers) leaves the uninstalled bundle's deployment, module, class loader and MSC services in
 * the server; getRemovalPendingBundles never becomes empty; and the next uninstall of a bundle that was wired to it hangs
 * the calling thread for a minute and ends with WFLYSRV0043 (TimeoutException in BundleInstallProcessor.undeploy).
 *
 * <h3>Component</h3>
 * jbosgi umbrella, wildfly/subsystem: {@code org.jboss.as.osgi.deployment.BundleInstallProcessor.undeploy},
 * {@code org.jboss.as.osgi.service.BundleLifecycleIntegration} ({@code RecreateCurrentRevisionPolicy},
 * {@code removeRevision}/{@code undeployRevision}); compare jbosgi-framework
 * {@code UserBundleState.refresh}/{@code uninstallInternal}.
 *
 * <h3>Found by</h3>
 * connect workstream, round 2 (WildFly SPI overrides: uninstall/refresh of deployed bundles, "uninstalled while in
 * use" handling), Arquillian test on the patched WildFly 41 server (JDK 25); the same sequence in a plain jbosgi
 * framework empties the removal-pending list.
 *
 * <h3>Spec reference</h3>
 * org.osgi.framework.wiring.FrameworkWiring.refreshBundles javadoc (osgi.core 8.0.0): "Each bundle in the dependency
 * closure that is in the UNINSTALLED state is removed from the Framework ... " / "If no bundles are specified, this
 * method will refresh the removal pending bundles."; getRemovalPendingBundles: "Returns the bundles that have
 * non-current, in use bundle wirings."; OSGi Core R8, 4.4.10 Uninstalling Bundles: capabilities of in-use revisions
 * remain available "until the bundle is refreshed".
 *
 * <h3>Code location</h3>
 * The refresh of the importer is done by {@code RecreateCurrentRevisionPolicy} as an undeploy and redeploy of its
 * deployment ({@code BundleLifecycleIntegration.java:524-575}); in the undeploy,
 * {@code BundleInstallProcessor.java:112-117} removes the old importer revision with
 * {@code bundleManager.removeRevision(brev, 0)}, which does not release the uninstalled exporter that the old revision was
 * wired to (the framework's {@code UserBundleState.uninstallInternal} removes such "aux" bundles, the plain
 * {@code removeRevision} does not), so the exporter stays removal pending and deployed. When the importer is later
 * uninstalled, the exporter is released inside that uninstall: {@code BundleLifecycleIntegration.removeRevision}
 * (line 334) undeploys it synchronously ({@code undeployRevision}, line 372) while the calling thread holds the
 * framework's resource locks, and the undeploy's {@code BundleInstallProcessor.undeploy} -> {@code removeRevision}
 * waits for those locks until {@code LockManagerImpl} times out (60 s). Introduced with the "uninstalled while in use"
 * handling of 54b65e85 on top of the WF41 refresh policy.
 *
 * <h3>Root cause</h3>
 * The WildFly bundle refresh path removes revisions without releasing the uninstalled providers they kept in use, and
 * the release that happens later runs a synchronous server undeploy under framework locks.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=RefreshKeepsUninstalledDeploymentIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository. The two -Darq... options start the managed server with port offset 500, so the test cannot connect to another server on 9990 (arquillian.xml has allowConnectingToRunningServer=true); they can be left out when no other server runs.)
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: Expected the refresh to release the uninstalled bundle, but: [after refreshBundles(null) the
 *   uninstalled exporter must not be removal pending any more, but the removal pending bundles are
 *   [refresh-exporter:0.0.0], after refreshBundles(null) the deployment of the uninstalled exporter must be undeployed,
 *   but it is still deployed, uninstalling the importer must not block, but it has not returned after 10 s (it takes
 *   60 s, until the framework's lock times out)]
 * (an exploratory variant measured the uninstall: 60031 ms; while it blocks, every other bundle operation of the
 *   framework waits too; server.log: WFLYSRV0043 ... BundleInstallProcessor ... undeploy phase REGISTER ... LockManagerImpl.lockItemsInternal
 *   ... Caused by: java.util.concurrent.TimeoutException; WFLYSRV0028: Stopped deployment ... in 60011ms)
 * </pre>
 *
 * <h3>Expected</h3>
 * After the refresh the exporter is gone (not removal pending, its deployment undeployed); uninstalling the importer
 * returns promptly.
 *
 * <h3>Reference behaviour</h3>
 * The same install/start/uninstall/refresh sequence in a plain jbosgi framework (itest) gives an empty removal-pending
 * list after the refresh.
 *
 * <h3>Note on the test</h3>
 * Only the exporter is checked in the removal pending bundles: an undeployed framework extension stays removal pending
 * until the server is reloaded (RefreshWithUndeployedExtensionIssueTestCase), whatever test undeployed it.
 * The blocked uninstall of the importer is left running in a background thread (it completes after the 60 s lock
 * timeout, which undeploys the exporter); the remote test call cannot wait that long. A test that runs right after it
 * (verified with BundleUninstallTestCase) is not affected.
 *
 * <h3>Suggested fix</h3>
 * When BundleInstallProcessor.undeploy (or the refresh policy) removes a non-current revision, also release the
 * uninstalled bundles that are no longer in use (as {@code UserBundleState.uninstallInternal} does), and run the
 * undeploy of such a released bundle asynchronously (the undeploy executor) whenever the caller may hold framework
 * locks, not only during a management undeploy.
 *
 * <h3>Regression vs WF41</h3>
 * no (measured on a WF41 server built from origin/WF41 77f95be6 on the same patched WildFly 41.0.1, JDK 25: same
 * failure). The 60 s block comes from the synchronous undeploy that 54b65e85 kept for the non-management path. The
 * WF41 run used harness adaptations only, no change to this test or to the code under test: the jacc {@code <policy>}
 * removed from standalone-osgi.xml, as r8 42d6c470 does (without it org.wildfly.security.policy fails on WildFly 41
 * and every deferred start fails with JBAS011969), and the r8 Arquillian JBOSGiApplicationArchiveProcessor from
 * 54b65e85 (in-container ManagementClient lookup); see the regression-2 workstream NOTES.md.
 */
@RunWith(Arquillian.class)
public class RefreshKeepsUninstalledDeploymentIssueTestCase {

    static final String PACKAGE = "org.jboss.test.osgi.issues.refreshapi";
    static final String EXPORTER = "refresh-exporter";
    static final String IMPORTER = "refresh-importer";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    ManagementClient managementClient;

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "refresh-uninstalled-tests");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(ClientConstants.class, ModelControllerClient.class, ManagementClient.class, DeploymentPlanBuilder.class, ModelNode.class);
                builder.addImportPackages(FrameworkWiring.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Test
    public void testRefreshReleasesUninstalledBundle() throws Exception {
        Bundle exporter = context.installBundle("refresh-exporter.jar", deployer.getDeployment(EXPORTER));
        Bundle importer = context.installBundle("refresh-importer.jar", deployer.getDeployment(IMPORTER));
        importer.start();
        exporter.uninstall();
        Assert.assertTrue("Precondition: the uninstalled exporter is removal pending", getRemovalPendingBundles().contains(exporter));

        refreshAll();

        // All three checks are made before the test fails, the last one leaves a blocked uninstall behind that holds the
        // framework's locks for a minute, so it has to come last and cannot be cleaned up
        List<String> failures = new ArrayList<String>();
        Collection<Bundle> pending = getRemovalPendingBundles();
        // Framework extensions that earlier tests undeployed stay removal pending until the server is reloaded
        if (pending.contains(exporter))
            failures.add("after refreshBundles(null) the uninstalled exporter must not be removal pending any more, but the removal pending bundles are " + pending);
        if (isDeployed("refresh-exporter.jar"))
            failures.add("after refreshBundles(null) the deployment of the uninstalled exporter must be undeployed, but it is still deployed");

        // The uninstall runs in another thread, the test cannot wait the full minute (the remote test call times out)
        Thread uninstall = uninstallInBackground(importer);
        uninstall.join(10000);
        if (uninstall.isAlive())
            failures.add("uninstalling the importer must not block, but it has not returned after 10 s (it takes 60 s, until the framework's lock times out)");

        Assert.assertTrue("Expected the refresh to release the uninstalled bundle, but: " + failures, failures.isEmpty());
    }

    private static Thread uninstallInBackground(final Bundle bundle) {
        Thread thread = new Thread("uninstall " + bundle.getSymbolicName()) {
            @Override
            public void run() {
                try {
                    bundle.uninstall();
                } catch (Exception ex) {
                    // ignore
                }
            }
        };
        thread.start();
        return thread;
    }

    private Collection<Bundle> getRemovalPendingBundles() {
        return context.getBundle(0).adapt(FrameworkWiring.class).getRemovalPendingBundles();
    }

    private void refreshAll() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        context.getBundle(0).adapt(FrameworkWiring.class).refreshBundles(null, new FrameworkListener() {
            @Override
            public void frameworkEvent(FrameworkEvent event) {
                latch.countDown();
            }
        });
        Assert.assertTrue("Precondition: refreshBundles completes", latch.await(30, TimeUnit.SECONDS));
    }

    private boolean isDeployed(String name) throws Exception {
        ModelNode op = new ModelNode();
        op.get(ClientConstants.OP).set(ClientConstants.READ_RESOURCE_OPERATION);
        op.get(ClientConstants.OP_ADDR).add(ClientConstants.DEPLOYMENT, name);
        ModelNode result = managementClient.getControllerClient().execute(op);
        return ClientConstants.SUCCESS.equals(result.get(ClientConstants.OUTCOME).asString());
    }

    private static JavaArchive bundle(String name, final String symbolicName, final boolean exporter) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.addAsResource(new StringAsset(name), exporter ? PACKAGE.replace('.', '/') + "/marker.txt" : "importer.txt");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(symbolicName);
                builder.addBundleManifestVersion(2);
                if (exporter)
                    builder.addExportPackages(PACKAGE);
                else
                    builder.addImportPackages(PACKAGE);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = EXPORTER, managed = false, testable = false)
    public static JavaArchive getExporter() {
        return bundle("refresh-exporter.jar", "refresh-exporter", true);
    }

    @Deployment(name = IMPORTER, managed = false, testable = false)
    public static JavaArchive getImporter() {
        return bundle("refresh-importer.jar", "refresh-importer", false);
    }
}
