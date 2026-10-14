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
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * In the server, a bundle that was uninstalled with Bundle.uninstall while another bundle used it is not removed by
 * FrameworkWiring.refreshBundles: it stays removal pending, its revision keeps its wiring and its deployment stays
 * deployed, after the bundles that used it were refreshed.
 *
 * <h3>Severity</h3>
 * major. Every bundle uninstalled through the OSGi API while in use stays in the server (revision, class loader,
 * deployment) after the refresh that must release it; a later uninstall of one of the former users tries to remove it
 * and stalls for 60 s in BundleInstallProcessor.undeploy. Same cause as the failing
 * BundleUninstallTestCase.testDependentJarRedeploy ("BundleWiring null").
 *
 * <h3>Related</h3>
 * Same defect as connect/25 (RefreshKeepsUninstalledDeploymentIssueTestCase, filed earlier by the verification), which
 * also covers the 60 s block of the later uninstall (UninstallReleasesBundleUnderLocksIssueTestCase); this test only
 * checks the refresh.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkWiringImpl#refreshBundlesInternal}; framework
 * level reproduction: UninstalledRevisionNotRemovedByRefreshIssueTestCase (jbosgi-framework itest).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 7.5.1 Refreshing (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.wiring.html): "The
 * refresh must then unresolve all the bundles in the considered collection. Unresolving will cause all the removal
 * pending Bundle Wirings to become no longer in use because there are no longer any bundles requiring them."
 * FrameworkWiring.getRemovalPendingBundles javadoc: "Returns the bundles that have non-current, in use bundle wirings."
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkWiringImpl.java}
 * ({@code refreshBundlesInternal}) passes an uninstalled bundle of the dependency closure to
 * {@code bundleManager.uninstallBundle}, and {@code UserBundleState.uninstallInternal} returns at once for a bundle that
 * is already UNINSTALLED: the revisions are never removed. In a standalone framework the bundle at least stops being
 * removal pending once its users are refreshed; in the server the users are refreshed by an undeploy and deploy of
 * their deployments and the uninstalled bundle stays removal pending. Pre-existing.
 *
 * <h3>Root cause</h3>
 * The refresh relies on the uninstall of the bundle to remove its revisions, which does nothing for a bundle that is
 * uninstalled already.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=StaleUninstalledExporterAfterRefreshIssueTestCase
 * </pre>
 *
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: The refresh must remove the uninstalled exporter, but it is still removal pending: [stale-exporter:0.0.0]
 * server.log at cleanup: WFLYSRV0028: Stopped deployment stale-exporter (runtime-name: stale-exporter) in 60005ms
 * </pre>
 *
 * <h3>Expected</h3>
 * After the refresh the uninstalled exporter is not removal pending and its revision has no wiring.
 *
 * <h3>Resolution</h3>
 * The refresh unresolves the uninstalled bundles of the dependency closure and removes their revisions (through the
 * bundle lifecycle, which undeploys their deployments in the server) after the bundles that depend on them are
 * refreshed.
 *
 * <h3>Regression vs WF41</h3>
 * no (pre-existing).
 */
@RunWith(Arquillian.class)
public class StaleUninstalledExporterAfterRefreshIssueTestCase {

    static final String EXPORTER = "stale-exporter";
    static final String IMPORTER = "stale-importer";
    static final String PACKAGE = "org.jboss.test.osgi.issues.staleexporterapi";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "stale-uninstalled-exporter-tests");
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
    public void testUninstalledExporterRemovedByRefresh() throws Exception {
        FrameworkWiring wiring = context.getBundle(0).adapt(FrameworkWiring.class);
        Bundle exporter = context.installBundle(EXPORTER, deployer.getDeployment(EXPORTER));
        Bundle importer = context.installBundle(IMPORTER, deployer.getDeployment(IMPORTER));
        try {
            Assert.assertTrue("Precondition: the bundles resolve", wiring.resolveBundles(Arrays.asList(exporter, importer)));
            BundleRevision exporterRevision = exporter.adapt(BundleRevision.class);

            // The exporter is uninstalled while the importer is wired to it, it is removal pending
            exporter.uninstall();
            Assert.assertTrue("Precondition: the exporter is removal pending", wiring.getRemovalPendingBundles().contains(exporter));

            final CountDownLatch latch = new CountDownLatch(1);
            wiring.refreshBundles(null, new FrameworkListener() {
                @Override
                public void frameworkEvent(FrameworkEvent event) {
                    latch.countDown();
                }
            });
            Assert.assertTrue("Precondition: refreshBundles completes", latch.await(30, TimeUnit.SECONDS));
            Assert.assertEquals("Precondition: the importer is refreshed", Bundle.INSTALLED, importer.getState());

            Assert.assertFalse("The refresh must remove the uninstalled exporter, but it is still removal pending: " + wiring.getRemovalPendingBundles(),
                    wiring.getRemovalPendingBundles().contains(exporter));
            Assert.assertNull("The wiring of the removed exporter revision must be gone", exporterRevision.getWiring());
        } finally {
            if (importer.getState() != Bundle.UNINSTALLED)
                importer.uninstall();
        }
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
