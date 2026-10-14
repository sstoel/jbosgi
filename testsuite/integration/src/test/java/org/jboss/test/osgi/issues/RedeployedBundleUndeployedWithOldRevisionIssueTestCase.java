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

import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.client.helpers.standalone.DeploymentPlanBuilder;
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

/**
 * Undeploying a bundle that still used the previous revision of a redeployed bundle also undeploys the redeployed
 * bundle: the deferred undeploy of the old, uninstalled-while-in-use revision identifies "its" deployment by name and
 * finds the new deployment of the same name.
 *
 * <h3>Severity</h3>
 * critical. A management operation on one deployment silently undeploys a different, healthy deployment (the
 * redeployed library bundle disappears from the server's deployments and from the framework), e.g. redeploying a shared
 * library bundle and later undeploying one of the applications that used the old version takes the new library down.
 *
 * <h3>Component</h3>
 * jbosgi umbrella, wildfly/subsystem, {@code org.jboss.as.osgi.service.BundleLifecycleIntegration}
 * ({@code BundleLifecycleImpl.removeRevision}/{@code undeployRevision}/{@code isDeployed}); same name-based lookup in
 * {@code ModuleLoaderIntegration.deactivateDeferredPhase}.
 *
 * <h3>Found by</h3>
 * connect workstream, round 2 (WildFly SPI overrides, "uninstalled while in use" handling of commits 54b65e85,
 * c4bbcd55, f025b420), Arquillian test on the patched WildFly 41 server (JDK 25).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.4.10 Uninstalling Bundles
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html#framework.lifecycle.uninstallingbundles):
 * "If the uninstalled bundle has one or more revisions with isInUse() bundle wirings then all capabilities provided by
 * the old in use bundle wirings must remain available for existing bundles and future resolves until the bundle is
 * refreshed ..." - the removal of the old revision must only release that revision; and 4.4.3: "Every bundle is
 * uniquely identified by its location string" - the redeployed bundle is a different bundle that must not be affected.
 * BundleLifecycle SPI contract of {@code removeRevision(XBundleRevision, int)}: remove the given revision only.
 *
 * <h3>Code location</h3>
 * {@code wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/BundleLifecycleIntegration.java:347-382}:
 * {@code undeployRevision} runs the undeploy of a revision that was uninstalled while in use on the undeploy executor
 * (line 370) and only checks {@code isDeployed(deployment)} (lines 375-382), which looks the deployment unit service up
 * by its service name ({@code depUnit.getServiceName()}, i.e. "jboss.deployment.unit.&lt;runtime name&gt;"); after a
 * redeploy that name belongs to the new deployment, so {@code server.undeploy(runtimeName)} undeploys it.
 * {@code ModuleLoaderIntegration.java:423-437} ({@code deactivateDeferredPhase}) computes the FIRST_MODULE_USE phase
 * service name from the old deployment unit in the same way, so for deferred deployments it deactivates the phase of
 * the new deployment. Introduced by 54b65e85 (deferred undeploy) together with c4bbcd55 (redeploy while the previous
 * revision is in use).
 *
 * <h3>Root cause</h3>
 * Deployment identity is taken from the runtime name / service name, which is reused by the redeployment, instead of
 * the deployment unit instance of the revision.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=RedeployedBundleUndeployedWithOldRevisionIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository. The two -Darq... options start the managed server with port offset 500, so the test cannot connect to another server on 9990 (arquillian.xml has allowConnectingToRunningServer=true); they can be left out when no other server runs.)
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: Undeploying redeploy-importer.jar must not undeploy the redeployed redeploy-exporter.jar,
 *   but the deployment is gone (bundle state 1)
 * server.log: (pool-N-thread-1) WFLYSRV0009: Undeployed "redeploy-exporter.jar" right after
 *   (management-handler-thread - 2) WFLYSRV0009: Undeployed "redeploy-importer.jar"
 * </pre>
 *
 * <h3>Expected</h3>
 * The redeployed exporter stays deployed and ACTIVE; only the old revision (already undeployed) is released.
 *
 * <h3>Reference behaviour</h3>
 * n/a (WildFly integration).
 *
 * <h3>Suggested fix</h3>
 * Remember the {@code DeploymentUnit} (or its ServiceController instance) of the revision and, in the deferred undeploy,
 * only undeploy when the controller found under the name is that same instance (or skip the undeploy entirely when the
 * deployment was already undeployed by a management operation, which is the case for every revision that was
 * uninstalled while in use through an undeploy); look the deferred phase service up through the old deployment unit's
 * own controller in {@code deactivateDeferredPhase} as well.
 *
 * <h3>Regression vs WF41</h3>
 * new R8 behaviour: on the WF41 server (origin/WF41 77f95be6, same patched WildFly 41.0.1, JDK 25) the redeploy of the
 * in-use exporter fails outright (CONFIGURE_MODULE: DuplicateServiceException: Service
 * jboss.module.spec.service."deployment.redeploy-exporter.jar" is already registered, JBOSGI-800), so the test errors
 * before its assertion; the redeploy take-over and the name-based deferred undeploy are new on the R8 branch
 * (c4bbcd55, 54b65e85); the defect is in behaviour WF41 never had, so it is not a regression of working functionality.
 */
@RunWith(Arquillian.class)
public class RedeployedBundleUndeployedWithOldRevisionIssueTestCase {

    static final String EXPORTER = "redeploy-exporter.jar";
    static final String IMPORTER = "redeploy-importer.jar";
    static final String PACKAGE = "org.jboss.test.osgi.issues.redeployapi";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    ManagementClient managementClient;

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "redeployed-bundle-undeploy-tests");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(ClientConstants.class, ModelControllerClient.class, ManagementClient.class, DeploymentPlanBuilder.class, ModelNode.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Test
    public void testUndeployOfOldRevisionUserKeepsRedeployedBundle() throws Exception {
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        String exporterName = server.deploy(EXPORTER, deployer.getDeployment(EXPORTER));
        String importerName = server.deploy(IMPORTER, deployer.getDeployment(IMPORTER));
        Bundle oldExporter = findBundle("redeploy-exporter");
        Assert.assertEquals("Precondition: the importer is active and wired to the exporter", Bundle.ACTIVE, findBundle("redeploy-importer").getState());

        // The exporter is undeployed while the importer is wired to it, then deployed again
        server.undeploy(exporterName);
        Assert.assertEquals("Precondition: the old exporter is uninstalled", Bundle.UNINSTALLED, oldExporter.getState());
        exporterName = server.deploy(EXPORTER, deployer.getDeployment(EXPORTER));
        Bundle newExporter = findBundle("redeploy-exporter");
        Assert.assertNotSame("Precondition: a new bundle for the redeployed exporter", oldExporter, newExporter);
        Assert.assertEquals("Precondition: the redeployed exporter is active", Bundle.ACTIVE, newExporter.getState());

        try {
            // Undeploying the importer releases the old exporter revision
            server.undeploy(importerName);
            Thread.sleep(3000);

            Assert.assertTrue("Undeploying " + IMPORTER + " must not undeploy the redeployed " + EXPORTER + ", but the deployment is gone (bundle state "
                    + newExporter.getState() + ")", isDeployed(EXPORTER));
            Assert.assertEquals("The redeployed exporter must still be active", Bundle.ACTIVE, newExporter.getState());
        } finally {
            if (isDeployed(EXPORTER))
                server.undeploy(exporterName);
        }
    }

    private Bundle findBundle(String symbolicName) {
        for (Bundle bundle : context.getBundles()) {
            if (symbolicName.equals(bundle.getSymbolicName()))
                return bundle;
        }
        return null;
    }

    private boolean isDeployed(String name) throws Exception {
        ModelNode op = new ModelNode();
        op.get(ClientConstants.OP).set(ClientConstants.READ_RESOURCE_OPERATION);
        op.get(ClientConstants.OP_ADDR).add(ClientConstants.DEPLOYMENT, name);
        ModelNode result = managementClient.getControllerClient().execute(op);
        return ClientConstants.SUCCESS.equals(result.get(ClientConstants.OUTCOME).asString());
    }

    @Deployment(name = EXPORTER, managed = false, testable = false)
    public static JavaArchive getExporter() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, EXPORTER);
        archive.addAsResource(new StringAsset("exported"), PACKAGE.replace('.', '/') + "/marker.txt");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName("redeploy-exporter");
                builder.addBundleManifestVersion(2);
                builder.addExportPackages(PACKAGE);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = IMPORTER, managed = false, testable = false)
    public static JavaArchive getImporter() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, IMPORTER);
        archive.addAsResource(new StringAsset("importer"), "importer.txt");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName("redeploy-importer");
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(PACKAGE);
                return builder.openStream();
            }
        });
        return archive;
    }
}
