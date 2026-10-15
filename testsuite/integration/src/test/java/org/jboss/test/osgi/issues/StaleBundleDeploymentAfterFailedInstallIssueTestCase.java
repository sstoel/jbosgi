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

import java.io.File;
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
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;

/**
 * When {@code BundleContext.installBundle} fails in the STRUCTURE phase of the server deployment, the bundle
 * Deployment stays in the subsystem's static deployment map; the next server deployment with the same name picks up that
 * stale Deployment (whose content is already closed) instead of its own content and fails.
 *
 * <h3>Severity</h3>
 * major. After one failed install (e.g. a bundle with an invalid jboss-deployment-structure.xml) the administrator's
 * next deployment of a corrected archive with the same name fails with "Cannot setup storage for: /streamNNN (No such
 * file or directory)" although it is valid; the stale entry also keeps the failed Deployment (metadata, attachments)
 * in memory until a deployment of that name comes along.
 *
 * <h3>Component</h3>
 * jbosgi umbrella, wildfly/subsystem, {@code org.jboss.as.osgi.service.BundleLifecycleIntegration} (static
 * {@code deploymentMap}), {@code org.jboss.as.osgi.deployment.BundleDeploymentProcessor},
 * {@code OSGiManifestStructureProcessor}, {@code OSGiMetaDataStructureProcessor}, {@code OSGiBundleInfoParseProcessor}.
 *
 * <h3>Found by</h3>
 * connect workstream, round 2 (WildFly SPI overrides: install path of BundleLifecycleIntegration.createBundleRevision,
 * resource leaks after failed deployments), Arquillian test on the patched WildFly 41 server (JDK 25).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.4.3 Installing Bundles
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html#framework.lifecycle.installingbundles):
 * "Atomic - The install method must completely install the bundle or, if the installation fails, the OSGi framework
 * must be left in the same state as it was in before the method was called." (here the failed install leaves state
 * behind that breaks a later, unrelated deployment).
 *
 * <h3>Code location</h3>
 * {@code wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/BundleLifecycleIntegration.java:242} puts the
 * Deployment into the static {@code deploymentMap} under the runtime name before {@code server.deploy} (line 247); the
 * only removal is {@code BundleDeploymentProcessor.java:82} in the PARSE phase. A deployment that fails in STRUCTURE
 * never reaches it, and the {@code catch} in {@code createBundleRevision} does not remove the entry either. The next
 * deployment with that name finds it in {@code OSGiManifestStructureProcessor.java:59},
 * {@code OSGiMetaDataStructureProcessor.java:58} and {@code BundleDeploymentProcessor.java:82} and uses the stale
 * Deployment. Unchanged since WF41.
 *
 * <h3>Root cause</h3>
 * The hand-over map entry is not cleaned up when the server deployment fails before the processor that consumes it.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=StaleBundleDeploymentAfterFailedInstallIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository. The two -Darq... options start the managed server with port offset 500, so the test cannot connect to another server on 9990 (arquillian.xml has allowConnectingToRunningServer=true); they can be left out when no other server runs.)
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: The deployment of a valid archive named stale.jar must succeed after a failed
 *   installBundle with that location, but it failed: ServerDeploymentException ... "WFLYSRV0153: Failed to process phase
 *   REGISTER of deployment \"stale.jar\" Caused by: ... JBOSGI011263: Cannot setup storage for: \"/stream...\" Caused
 *   by: java.io.FileNotFoundException: /stream... (No such file or directory)"
 * </pre>
 *
 * <h3>Expected</h3>
 * The failed install leaves nothing behind; the later deployment installs the bundle from its own content
 * (symbolic name stale-new).
 *
 * <h3>Reference behaviour</h3>
 * n/a (WildFly integration).
 *
 * <h3>Note on the test</h3>
 * The failing deployment also leaves an incomplete bundle directory in the OSGi storage area (connect/24); the test
 * deletes it in a {@code finally} block so that the server stays usable for the tests that run after it.
 *
 * <h3>Suggested fix</h3>
 * In {@code createBundleRevision} remove the map entry in a {@code finally} block (or in the catch) after
 * {@code server.deploy} returns or fails, and let the processors compare the entry with the deployment unit's content
 * (e.g. keep the Deployment in an operation attachment instead of a static map keyed by name).
 *
 * <h3>Regression vs WF41</h3>
 * no (measured on a WF41 server built from origin/WF41 77f95be6 on the same patched WildFly 41.0.1, JDK 25: same
 * failure). The code is unchanged since WF41. The WF41 run used harness adaptations only, no change to this test or to
 * the code under test: the jacc {@code <policy>} removed from standalone-osgi.xml, as r8 42d6c470 does (without it
 * org.wildfly.security.policy fails on WildFly 41 and every deferred start fails with JBAS011969), and the r8
 * Arquillian JBOSGiApplicationArchiveProcessor from 54b65e85 (in-container ManagementClient lookup); see the
 * regression-2 workstream NOTES.md.
 */
@RunWith(Arquillian.class)
public class StaleBundleDeploymentAfterFailedInstallIssueTestCase {

    static final String BROKEN = "stale-broken";
    static final String VALID = "stale-valid";
    static final String NAME = "stale.jar";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    ManagementClient managementClient;

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "stale-bundle-deployment-tests");
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
    public void testDeployAfterFailedInstall() throws Exception {
        try {
            Bundle bundle = context.installBundle(NAME, deployer.getDeployment(BROKEN));
            bundle.uninstall();
            Assert.fail("Precondition: the install of a bundle with an invalid jboss-deployment-structure.xml fails");
        } catch (BundleException ex) {
            // expected
        }

        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        String runtimeName;
        try {
            runtimeName = server.deploy(NAME, deployer.getDeployment(VALID));
        } catch (Exception ex) {
            Assert.fail("The deployment of a valid archive named " + NAME + " must succeed after a failed installBundle with that location, but it failed: " + ex);
            return;
        } finally {
            // The failed deployment leaves an incomplete bundle directory (FailedDeploymentLeavesIncompleteStorageIssueTestCase)
            removeIncompleteStorage();
        }
        try {
            Bundle deployed = null;
            for (Bundle bundle : context.getBundles()) {
                if (NAME.equals(bundle.getLocation()))
                    deployed = bundle;
            }
            Assert.assertNotNull("The bundle of the deployment is installed", deployed);
            Assert.assertEquals("The bundle is installed from the deployed content", "stale-new", deployed.getSymbolicName());
        } finally {
            server.undeploy(runtimeName);
        }
    }

    @Deployment(name = BROKEN, managed = false, testable = false)
    public static JavaArchive getBroken() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "stale-broken.jar");
        archive.addAsResource(new StringAsset("old"), "old.txt");
        archive.addAsManifestResource(new StringAsset("<jboss-deployment-structure><this-is-not-valid/></jboss-deployment-structure>"), "jboss-deployment-structure.xml");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName("stale-old");
                builder.addBundleManifestVersion(2);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = VALID, managed = false, testable = false)
    public static JavaArchive getValid() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "stale-valid.jar");
        archive.addAsResource(new StringAsset("new"), "new.txt");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName("stale-new");
                builder.addBundleManifestVersion(2);
                return builder.openStream();
            }
        });
        return archive;
    }

    /**
     * Remove the bundle directories without bundle-persistent.properties that the failed deployment left in the OSGi
     * storage area, so that the server can still start the OSGi subsystem for the tests that run after this one.
     */
    private void removeIncompleteStorage() {
        File storage = new File(context.getProperty(Constants.FRAMEWORK_STORAGE));
        File[] dirs = storage.listFiles();
        if (dirs == null)
            return;
        for (File dir : dirs) {
            if (dir.getName().startsWith("bundle-") && new File(dir, "bundle-persistent.properties").isFile() == false)
                deleteRecursive(dir);
        }
    }

    private static void deleteRecursive(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children)
                deleteRecursive(child);
        }
        file.delete();
    }
}
