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
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
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
 * Uninstalling a deployed bundle that was the last user of a bundle uninstalled while in use blocks for 60 seconds: the
 * uninstall releases the other bundle while it holds the framework locks, and the undeploy of that bundle's deployment
 * waits for those locks in another thread until they time out.
 *
 * <h3>Severity</h3>
 * major. The calling thread hangs for a minute, every other bundle operation of the framework waits too, and the
 * undeploy ends with WFLYSRV0043 (TimeoutException in BundleInstallProcessor.undeploy). Second half of connect/25
 * (RefreshKeepsUninstalledDeploymentIssueTestCase), which no longer reaches it since the refresh removes the uninstalled
 * bundles of its dependency closure; a refresh that does not include the uninstalled bundle still does.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core: {@code org.jboss.osgi.framework.internal.UserBundleState#uninstallInternal},
 * {@code BundleManagerPlugin#uninstallBundle}; jbosgi umbrella, wildfly/subsystem:
 * {@code org.jboss.as.osgi.service.BundleLifecycleIntegration#undeployRevision},
 * {@code org.jboss.as.osgi.deployment.BundleInstallProcessor#undeploy}.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.4.10 Uninstalling Bundles: the old revision of an uninstalled bundle is released when it is not in use
 * any more; Bundle.uninstall javadoc: the operation completes without waiting for unrelated operations. (Robustness
 * defect of the WildFly integration rather than a spec clause.)
 *
 * <h3>Code location</h3>
 * {@code UserBundleState.uninstallInternal} removes the other uninstalled bundles that are not in use any more while
 * {@code BundleManagerPlugin.uninstallBundle} holds the uninstall locks (the bundle, its transitive closure and the
 * framework wiring lock). {@code BundleLifecycleIntegration.removeRevision} undeploys the released bundle synchronously
 * ({@code undeployRevision}); its {@code BundleInstallProcessor.undeploy} calls {@code bundleManager.removeRevision}
 * -> {@code FrameworkEnvironment.uninstallResources} -> {@code lockResources} on an MSC thread, which waits for the locks
 * of the uninstalling thread until {@code LockManagerImpl} times out (60 s through LockManagerIntegration).
 * Introduced with the "uninstalled while in use" handling of 54b65e85.
 *
 * <h3>Root cause</h3>
 * A synchronous server undeploy runs inside a framework operation that holds the locks the undeploy needs.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=UninstallReleasesBundleUnderLocksIssueTestCase
 * </pre>
 *
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: Uninstalling the importer must not block, but it has not returned after 10 s (it takes 60 s,
 *   until the framework's lock times out)
 * (exploratory variant that waited for it: "importer uninstall took 60032 ms"; server.log: WFLYSRV0043 ...
 *   BundleInstallProcessor ... LockManagerImpl.lockItemsInternal ... Caused by: java.util.concurrent.TimeoutException;
 *   WFLYSRV0028: Stopped deployment stall-exporter ... in 60007ms)
 * </pre>
 *
 * <h3>Expected</h3>
 * The uninstall returns promptly and releases the exporter.
 *
 * <h3>Resolution</h3>
 * The other uninstalled bundles are released after the uninstall released its locks
 * ({@code UserBundleState.removeUnusedUninstalledBundles}, called by {@code BundleManagerPlugin.uninstallBundle}); the
 * undeploy of their deployments then gets the locks. The uninstall of the importer took 15 ms.
 *
 * <h3>Regression vs WF41</h3>
 * no (measured for connect/25 on WF41: same 60 s block).
 */
@RunWith(Arquillian.class)
public class UninstallReleasesBundleUnderLocksIssueTestCase {

    static final String EXPORTER = "stall-exporter";
    static final String IMPORTER = "stall-importer";
    static final String PACKAGE = "org.jboss.test.osgi.issues.stallapi";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "uninstall-under-locks-tests");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(Bundle.class, FrameworkWiring.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Test
    public void testUninstallDoesNotBlock() throws Exception {
        FrameworkWiring wiring = context.getBundle(0).adapt(FrameworkWiring.class);
        Bundle exporter = context.installBundle(EXPORTER, deployer.getDeployment(EXPORTER));
        final Bundle importer = context.installBundle(IMPORTER, deployer.getDeployment(IMPORTER));
        Assert.assertTrue("Precondition: the bundles resolve", wiring.resolveBundles(Arrays.asList(exporter, importer)));

        // The exporter is uninstalled while the importer uses it, only the importer is refreshed
        exporter.uninstall();
        final CountDownLatch latch = new CountDownLatch(1);
        wiring.refreshBundles(Collections.singleton(importer), new FrameworkListener() {
            @Override
            public void frameworkEvent(FrameworkEvent event) {
                latch.countDown();
            }
        });
        Assert.assertTrue("Precondition: refreshBundles completes", latch.await(30, TimeUnit.SECONDS));
        Assert.assertTrue("Precondition: the exporter is removal pending", wiring.getRemovalPendingBundles().contains(exporter));

        // Uninstalling the importer releases the exporter, which undeploys its deployment
        final CountDownLatch uninstalled = new CountDownLatch(1);
        Thread thread = new Thread("uninstall " + IMPORTER) {
            @Override
            public void run() {
                try {
                    importer.uninstall();
                } catch (Exception ex) {
                    // reported by the assertion below
                }
                uninstalled.countDown();
            }
        };
        thread.start();
        Assert.assertTrue("Uninstalling the importer must not block, but it has not returned after 10 s (it takes 60 s, until the framework's lock times out)",
                uninstalled.await(10, TimeUnit.SECONDS));
        Assert.assertFalse("The released exporter is not removal pending", wiring.getRemovalPendingBundles().contains(exporter));
    }

    private static JavaArchive bundle(String name, final String exportPackage, final String importPackage) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name + ".jar");
        archive.addAsResource(new StringAsset(name), (exportPackage != null ? PACKAGE.replace('.', '/') + "/" : name + "/") + "marker.txt");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName().replace(".jar", ""));
                builder.addBundleManifestVersion(2);
                if (exportPackage != null)
                    builder.addExportPackages(exportPackage);
                if (importPackage != null)
                    builder.addImportPackages(importPackage);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = EXPORTER, managed = false, testable = false)
    public static JavaArchive getExporter() {
        return bundle(EXPORTER, PACKAGE, null);
    }

    @Deployment(name = IMPORTER, managed = false, testable = false)
    public static JavaArchive getImporter() {
        return bundle(IMPORTER, null, PACKAGE);
    }
}
