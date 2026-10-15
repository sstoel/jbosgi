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
import java.util.Arrays;
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
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper;
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
 * Bundle.update() followed by FrameworkWiring.refreshBundles() of a bundle that was deployed through management leaves a
 * FAILED deployment {@code <name>-rev1.jar} in the management model: the refresh's undeploy of the updated revision's
 * deployment is rolled back by the controller, the subsystem ignores the rollback, and the failed deployment is persisted
 * in standalone*.xml, so that the next server start installs a second copy of the bundle from it.
 *
 * <h2>Severity</h2>
 * major. Update + refresh is the standard OSGi way to update a bundle (consoles, provisioning, Felix FileInstall-style
 * tools). Every such update of a deployed bundle adds a broken deployment to the server configuration (WFLYCTL0186
 * "Services which failed to start" in every later management operation report) and after a restart the persisted
 * {@code <name>-rev1.jar} is deployed: in the exploration the zombie deployment installed "ee-lib:2.0.0" as an
 * additional bundle next to the regular deployment.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem {@code org.jboss.as.osgi.service.BundleLifecycleIntegration}
 * ({@code RecreateCurrentRevisionPolicy.startBundleRefresh/refreshCurrentRevision}, {@code undeployRevision}),
 * {@code org.jboss.as.osgi.deployment.BundleInstallProcessor.undeploy}, {@code ModuleLoaderIntegration.removeModule}.
 *
 * <h2>Found by</h2>
 * wildfly-ee workstream (deep 2), first seen with a WAR that depends on the bundle (see
 * BundleUsedByWarUndeployRollbackIssueTestCase), then reproduced without any EE dependent, for a plain bundle and for a
 * WAB, on the patched WildFly 41.0.1 server (JDK 25). Related observation (same two-deployments-per-bundle state after an
 * update, not asserted here): undeploying {@code ee-lib-rev1.jar} before {@code ee-lib.jar} is rolled back the same way,
 * and after undeploying both deployments the bundle ee-lib:2.0.0 stays installed and ACTIVE without any deployment.
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 FrameworkWiring.refreshBundles javadoc: "Each bundle in the dependency closure that is in the
 * UNINSTALLED state is removed from the dependency closure and is now completely removed from the Framework." and "Each
 * bundle in the dependency closure that was in the ACTIVE state prior to Step 2 is started as described in the
 * Bundle.start method" - after a refresh only the current revision of the bundle remains; Core 4.4.9 Updating Bundles
 * (the old revision is removed at refresh). Documented jbosgi/WildFly behaviour: the bundle deployment follows the
 * bundle (testsuite BundleUpdateTestCase/BundleRefreshTestCase); a failed management operation must leave the
 * configuration unchanged (WildFly management rollback semantics).
 * https://docs.osgi.org/specification/osgi.core/8.0.0/framework.wiring.html#org.osgi.framework.wiring.FrameworkWiring
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/BundleLifecycleIntegration.java:529-544
 * ({@code startBundleRefresh} -&gt; {@code undeployRevision(brev)} of the current revision, i.e. a management undeploy of
 * {@code <name>-rev1.jar}) and :347-373 ({@code undeployRevision}: {@code server.undeploy(runtimeName)}; the result of
 * the plan is not checked, a rollback "with no failure message" is not reported by ServerDeploymentHelper);
 * :547-571 ({@code refreshCurrentRevision} then deploys {@code <name>.jar} again).
 *
 * <h2>Root cause</h2>
 * Not an independent root cause: this is the combination of lifecycle/06 (BundleUpdateNotPersistedIssueTestCase) and
 * ee/01 (BundleUsedByWarUndeployRollbackIssueTestCase), seen from another angle, and it counts with lifecycle/06 as one
 * root cause. The test is kept as a separate regression test for the update + refresh path. The failure needs (1) the
 * two coexisting deployments of lifecycle/06: Bundle.update is deployed as an additional server deployment
 * {@code <name>-revN<suffix>} (BundleLifecycleIntegration.createBundleRevision :226-247 -&gt; {@code server.deploy};
 * getRuntimeName :486-510, {@code runtimeName += "-rev" + revid + suffix} at :510) while {@code <name>.jar} keeps the
 * original content; and (2) the ignored plan result of ee/01: undeployRevision (:347-373) does not check the outcome,
 * and ServerDeploymentHelper.undeploy only throws on a deployment exception, not on a rollback.
 * RecreateCurrentRevisionPolicy.startBundleRefresh (:529-544) only triggers the combination. <br>
 * In detail: the update installs the new revision as a second server deployment ({@code <name>-rev1.jar}) while the
 * original deployment ({@code <name>.jar}) stays deployed with its active FIRST_MODULE_USE phase depending on the
 * module service {@code deployment.<name>.jar} of the old revision. The refresh undeploys {@code <name>-rev1.jar} in
 * its own management operation; the old revision's module services are removed in that operation, so the controller
 * finds a new missing dependency (WFLYCTL0184 ... jboss.module.service."deployment.&lt;name&gt;.jar" (missing)
 * dependents: [... "&lt;name&gt;.jar".FIRST_MODULE_USE]) and rolls the undeploy back; the rollback's redeploy of
 * {@code <name>-rev1.jar} fails (JBOSGI011240 Bundle name and version already installed), the deployment stays FAILED,
 * and the subsystem carries on because it does not see the rollback.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=UpdateRefreshLeavesFailedRevisionDeploymentIssueTestCase \
 *     -Darq.container.jboss.configuration.managementPort=11090 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=1100
 * </pre>
 * (Prerequisites: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork in the local Maven
 * repository; on JDK 24+ the testsuite needs surefire.security.args without -Djava.security.manager, see
 * TestJvmSecurityManagerIssueTestCase.) Deterministic. The test removes the leftover deployment, otherwise it would be
 * deployed again at the next start of the test server.
 *
 * <h2>Observed</h2>
 * <pre>
 * java.lang.AssertionError: After Bundle.update + refreshBundles of the deployed bundle the server must only have the
 *   deployment update-refresh.jar, but it also has [update-refresh-rev1.jar (status FAILED)]
 * server.log: WFLYSRV0008: Undeploy of deployment "update-refresh-rev1.jar" was rolled back with no failure message
 *   ... "update-refresh-rev1.jar".REGISTER ... JBOSGI011240: Bundle name and version already installed:
 *   [update-refresh:2.0.0]
 * </pre>
 *
 * <h2>Expected</h2>
 * After the refresh the bundle (version 2, ACTIVE - this part works) is backed by exactly one deployment and the
 * configuration contains no failed deployment.
 *
 * <h2>Reference behaviour</h2>
 * The same update + refresh of a bundle installed with BundleContext.installBundle leaves no extra deployment (the
 * integration testsuite's BundleUpdateTestCase/BundleRefreshTestCase use that path).
 *
 * <h2>Suggested fix (not applied)</h2>
 * The fix of lifecycle/06 closes this issue too: persist the update as new content of the same deployment (replace /
 * full-replace {@code <name>.jar}) instead of creating the second deployment {@code <name>-revN<suffix>}
 * (createBundleRevision :226-247, getRuntimeName :510); without the {@code -rev1} deployment there is nothing for the
 * refresh to undeploy separately. Fixing only ee/01's result check in undeployRevision (:347-373) turns this failure
 * into a clean BundleException but still breaks the refresh. Alternatives within the current design: replace the
 * original deployment and the -rev deployment in one composite operation (undeploy both, deploy the refreshed revision
 * under the bundle's deployment name), or deactivate the original deployment's deferred phase before its module is
 * removed; check the outcome of every management operation the subsystem runs and turn a rollback into a
 * BundleException/FrameworkEvent.ERROR.
 *
 * <h2>Regression vs WF41</h2>
 * no (measured on a WF41 server built from origin/WF41 77f95be6 on the same patched WildFly 41.0.1, JDK 25: same
 * failure). The refresh policy is unchanged since WF41; the module take-over code in ModuleLoaderIntegration is new in
 * R8, but WF41, which does not have it, fails the same way. The WF41 run used harness adaptations only, no change to
 * this test or to the code under test: the jacc {@code <policy>} removed from standalone-osgi.xml, as r8 42d6c470 does
 * (without it org.wildfly.security.policy fails on WildFly 41 and every deferred start fails with JBAS011969), and the
 * r8 Arquillian JBOSGiApplicationArchiveProcessor from 54b65e85 (in-container ManagementClient lookup); see the
 * regression-2 workstream NOTES.md.
 */
@RunWith(Arquillian.class)
public class UpdateRefreshLeavesFailedRevisionDeploymentIssueTestCase {

    static final String NAME = "update-refresh.jar";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @ArquillianResource
    ManagementClient managementClient;

    @Deployment
    public static JavaArchive testDeployment() {
        final JavaArchive jar = ShrinkWrap.create(JavaArchive.class, "update-refresh-tests");
        jar.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(jar.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(ManagementClient.class, ModelControllerClient.class, ClientConstants.class, ServerDeploymentHelper.class, ModelNode.class);
                builder.addImportPackages(FrameworkWiring.class);
                return builder.openStream();
            }
        });
        return jar;
    }

    @Test
    public void testUpdateAndRefreshOfDeployedBundle() throws Exception {
        removeLeftovers();
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        server.deploy(NAME, deployer.getDeployment("v1"));
        try {
            Bundle bundle = getBundle();
            Assert.assertNotNull("Precondition: bundle installed", bundle);
            bundle.update(deployer.getDeployment("v2"));
            refresh(bundle);
            Assert.assertEquals("Precondition: the refreshed bundle is version 2", "2.0.0", getBundle().getVersion().toString());

            List<String> extra = new ArrayList<String>();
            for (String name : getDeploymentNames()) {
                if (name.matches("update-refresh-rev[0-9]+\\.jar"))
                    extra.add(name + " (status " + getStatus(name) + ")");
            }
            Assert.assertTrue("After Bundle.update + refreshBundles of the deployed bundle the server must only have the deployment " + NAME + ", but it also has " + extra,
                    extra.isEmpty());
        } finally {
            removeLeftovers();
        }
    }

    private void removeLeftovers() throws Exception {
        for (String name : getDeploymentNames()) {
            if (name.matches("update-refresh-rev[0-9]+\\.jar"))
                undeploy(name);
        }
        undeploy(NAME);
        Bundle bundle = getBundle();
        if (bundle != null)
            bundle.uninstall();
    }

    private Bundle getBundle() {
        for (Bundle bundle : context.getBundles()) {
            if ("update-refresh".equals(bundle.getSymbolicName()))
                return bundle;
        }
        return null;
    }

    private void refresh(Bundle bundle) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        context.getBundle(0).adapt(FrameworkWiring.class).refreshBundles(Arrays.asList(bundle), new FrameworkListener() {
            @Override
            public void frameworkEvent(FrameworkEvent event) {
                latch.countDown();
            }
        });
        Assert.assertTrue("Precondition: refreshBundles completes", latch.await(30, TimeUnit.SECONDS));
    }

    private List<String> getDeploymentNames() throws Exception {
        ModelNode op = new ModelNode();
        op.get(ClientConstants.OP).set("read-children-names");
        op.get(ClientConstants.OP_ADDR).setEmptyList();
        op.get("child-type").set(ClientConstants.DEPLOYMENT);
        ModelNode result = managementClient.getControllerClient().execute(op);
        List<String> names = new ArrayList<String>();
        for (ModelNode node : result.get(ClientConstants.RESULT).asList())
            names.add(node.asString());
        return names;
    }

    private String getStatus(String name) throws Exception {
        ModelNode op = new ModelNode();
        op.get(ClientConstants.OP).set(ClientConstants.READ_ATTRIBUTE_OPERATION);
        op.get(ClientConstants.OP_ADDR).add(ClientConstants.DEPLOYMENT, name);
        op.get(ClientConstants.NAME).set("status");
        ModelNode result = managementClient.getControllerClient().execute(op);
        return ClientConstants.SUCCESS.equals(result.get(ClientConstants.OUTCOME).asString()) ? result.get(ClientConstants.RESULT).asString() : null;
    }

    private void undeploy(String name) {
        try {
            if (getStatus(name) != null)
                new ServerDeploymentHelper(managementClient.getControllerClient()).undeploy(name);
        } catch (Exception ex) {
            // ignore
        }
    }

    private static JavaArchive bundle(String archiveName, final String version) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, archiveName);
        archive.addAsResource(new StringAsset(version), "update-refresh/version.txt");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName("update-refresh");
                builder.addBundleVersion(version);
                builder.addBundleManifestVersion(2);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = "v1", managed = false, testable = false)
    public static JavaArchive getV1() {
        return bundle("update-refresh-v1.jar", "1.0.0");
    }

    @Deployment(name = "v2", managed = false, testable = false)
    public static JavaArchive getV2() {
        return bundle("update-refresh-v2.jar", "2.0.0");
    }
}
