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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
 * A bundle deployment that fails while the framework stores the bundle content leaves a bundle directory without
 * {@code bundle-persistent.properties} in the OSGi storage area; at the next server start the OSGi subsystem fails to
 * start ({@code jbosgi.StorageManager} fails with "Required property 'BundleRev' missing").
 *
 * <h3>Severity</h3>
 * critical. One failed deployment makes the OSGi subsystem of the server unusable after the next restart or reload
 * (all OSGi deployments fail, WFLYCTL0186 "Services which failed to start: service jbosgi.StorageManager") until the
 * directory is removed by hand.
 *
 * <h3>Component</h3>
 * jbosgi-framework core, {@code org.jboss.osgi.framework.spi.StorageState.createStorageState} (no clean-up on
 * failure), {@code StorageManagerImpl.createStorageState} / {@code initialize}; triggered in WildFly through the stale
 * deployment of {@code StaleBundleDeploymentAfterFailedInstallIssueTestCase} (connect/23).
 *
 * <h3>Found by</h3>
 * connect workstream, round 2 (resource leaks after failed deployments), Arquillian test on the patched WildFly 41
 * server (JDK 25); the boot failure was observed when the server of the test run was started again with the storage
 * area left by the previous run.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.4.3 Installing Bundles
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html#framework.lifecycle.installingbundles):
 * "Atomic - The install method must completely install the bundle or, if the installation fails, the OSGi framework
 * must be left in the same state as it was in before the method was called."
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/spi/StorageState.java:100-119}: the
 * {@code bundle-N-rev-M.jar} is created (line 107) before the content is copied and before
 * {@code writeProperties()} (line 118); when {@code rootFile.openStream()} or the copy fails, the exception leaves the
 * directory with an empty or partial jar and without properties. {@code StorageManagerImpl.createStorageState} and its
 * callers ({@code UserBundleRevisionFactory.java:141}, {@code UserBundleState.java:381}) do not delete it.
 * {@code StorageManagerImpl.initialize} (line 100, {@code restoreStorageState}) then fails the whole framework for
 * such a directory (see connect/07). Unchanged since WF41.
 *
 * <h3>Root cause</h3>
 * The storage state of a bundle revision is not created atomically and not cleaned up on failure, and an incomplete
 * bundle directory is fatal at framework init.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=FailedDeploymentLeavesIncompleteStorageIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository. The two -Darq... options start the managed server with port offset 500, so the test cannot connect to another server on 9990 (arquillian.xml has allowConnectingToRunningServer=true); they can be left out when no other server runs.)
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: A failed deployment must not leave an incomplete bundle directory in the OSGi storage
 *   area, but these directories have no bundle-persistent.properties: [bundle-27 [bundle-27-rev-0.jar]]
 * server.log of the next start of the same server:
 *   MSC000001: Failed to start service jbosgi.StorageManager ... Caused by: java.lang.IllegalArgumentException:
 *   JBOSGI011202: Required property 'BundleRev' missing in: .../standalone/data/osgi-store/bundle-27
 *   WFLYSRV0026: WildFly 41.0.1.Final ... started (with errors) ... 23 services failed or missing dependencies
 * </pre>
 *
 * <h3>Expected</h3>
 * The failed install removes the directory it created (or writes it atomically); a server restart is not affected.
 *
 * <h3>Reference behaviour</h3>
 * n/a.
 *
 * <h3>Note on the test</h3>
 * After its assertion the test deletes the incomplete bundle directories (in a {@code finally} block), so the server of
 * the testsuite run stays usable for the tests that run after it; without that the OSGi subsystem fails at the next
 * start of the same server.
 *
 * <h3>Suggested fix</h3>
 * In {@code StorageState.createStorageState} write the content into a temporary file and the properties last, and
 * delete the bundle directory when anything fails (or let {@code StorageManagerImpl.createStorageState} do so); let
 * {@code StorageManagerImpl.initialize} log and skip a directory that cannot be restored instead of failing.
 *
 * <h3>Regression vs WF41</h3>
 * no (storage code unchanged since WF41).
 */
@RunWith(Arquillian.class)
public class FailedDeploymentLeavesIncompleteStorageIssueTestCase {

    static final String BROKEN = "incomplete-storage-broken";
    static final String VALID = "incomplete-storage-valid";
    static final String NAME = "incomplete-storage.jar";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    ManagementClient managementClient;

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "incomplete-storage-tests");
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
    public void testFailedDeploymentLeavesNoIncompleteStorage() throws Exception {
        // A failed installBundle leaves its Deployment behind (StaleBundleDeploymentAfterFailedInstallIssueTestCase), so
        // the next deployment with that name fails while the framework stores the content
        try {
            context.installBundle(NAME, deployer.getDeployment(BROKEN)).uninstall();
            Assert.fail("Precondition: the install of a bundle with an invalid jboss-deployment-structure.xml fails");
        } catch (BundleException ex) {
            // expected
        }
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        try {
            server.undeploy(server.deploy(NAME, deployer.getDeployment(VALID)));
        } catch (Exception ex) {
            // this deployment fails because of the stale Deployment, which is reported by the other issue test
        }

        File storage = new File(context.getProperty(Constants.FRAMEWORK_STORAGE));
        List<String> incomplete = new ArrayList<String>();
        try {
            for (File dir : storage.listFiles()) {
                if (dir.getName().startsWith("bundle-") && new File(dir, "bundle-persistent.properties").isFile() == false)
                    incomplete.add(dir.getName() + " " + Arrays.toString(dir.list()));
            }
            Assert.assertTrue("A failed deployment must not leave an incomplete bundle directory in the OSGi storage area, but these directories have no bundle-persistent.properties: "
                    + incomplete, incomplete.isEmpty());
        } finally {
            removeIncompleteStorage();
        }
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


    @Deployment(name = BROKEN, managed = false, testable = false)
    public static JavaArchive getBroken() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "incomplete-storage-broken.jar");
        archive.addAsManifestResource(new StringAsset("<jboss-deployment-structure><this-is-not-valid/></jboss-deployment-structure>"), "jboss-deployment-structure.xml");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName("incomplete-storage-old");
                builder.addBundleManifestVersion(2);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = VALID, managed = false, testable = false)
    public static JavaArchive getValid() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "incomplete-storage-valid.jar");
        archive.addAsResource(new StringAsset("new"), "new.txt");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName("incomplete-storage-new");
                builder.addBundleManifestVersion(2);
                return builder.openStream();
            }
        });
        return archive;
    }
}
