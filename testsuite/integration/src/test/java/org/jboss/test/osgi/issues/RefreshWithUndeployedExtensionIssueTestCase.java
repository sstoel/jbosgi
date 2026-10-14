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
import java.util.concurrent.atomic.AtomicInteger;

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
 * After a framework extension bundle was undeployed, every {@code FrameworkWiring.refreshBundles(null, ...)} in the
 * server silently does nothing: no bundle is refreshed, the listeners are never notified and no framework event is
 * fired, until the server is restarted.
 *
 * <h3>Severity</h3>
 * major. Bundles that were uninstalled while in use are never removed (their revisions, class loaders and classes stay
 * in memory) and a caller that waits for PACKAGES_REFRESHED hangs or times out, for every refresh after the first
 * undeploy of an extension bundle. Found while fixing wildfly/06: StaleProvidedWireLeakIssueTestCase failed with
 * "Precondition: refreshBundles completes" whenever ExtensionContentLeakedPerReloadIssueTestCase (which deploys and
 * undeploys an extension) ran before it.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkWiringImpl#refreshBundles} and
 * {@code BundleManagerPlugin#shutdownManager}; seen in the jbosgi umbrella WildFly integration.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.15.5 (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html): "When a RESOLVED
 * extension bundle is updated or UNINSTALLED, it is not allowed to re-enter the RESOLVED state. If the extension
 * bundle is refreshed then the Framework must shutdown and the framework must be re-launched. In this case a Framework
 * Event is fired of type STOPPED_SYSTEM_REFRESHED." FrameworkWiring.refreshBundles javadoc: "listeners - Zero or more
 * listeners to be notified when the bundle refresh has been completed. The specified listeners do not need to be
 * otherwise registered with the framework. If a specified listener is already registered with the framework, it will
 * be notified twice."
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkWiringImpl.java:109-119}: when the
 * refresh includes an attached extension (the undeployed extension stays removal-pending, so every
 * {@code refreshBundles(null)} includes it), the method starts a thread that calls
 * {@code bundleManager.shutdownManager(STOPPED_SYSTEM_REFRESHED)} and returns without refreshing anything.
 * {@code BundleManagerPlugin.shutdownManager} returns at once unless the manager state is STARTING or ACTIVE, which only
 * {@code FrameworkProxy} (the Framework object of a launcher) sets; the WildFly subsystem creates the framework services
 * itself, the manager state stays INSTALLED. Introduced by 8d863429 "Implement OSGi Core R7".
 *
 * <h3>Root cause</h3>
 * The refresh of an extension relies on a framework stop and re-launch, which does not happen when the framework is
 * not launched through its Framework object.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=RefreshWithUndeployedExtensionIssueTestCase
 * </pre>
 *
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: Expected refreshBundles(null) to notify its listener with PACKAGES_REFRESHED after an
 *   extension bundle was undeployed, but the listener was not called within 30 seconds
 * </pre>
 * The system bundle stays ACTIVE, no framework event is fired, no bundle is refreshed (the importer of the uninstalled
 * exporter stays RESOLVED).
 *
 * <h3>Expected</h3>
 * The refresh completes for the other bundles and notifies the listeners; the extension content stays attached until
 * the server is reloaded or restarted (decision for the WildFly integration, see Resolution).
 *
 * <h3>Reference behaviour</h3>
 * A framework launched through its Framework object stops with STOPPED_SYSTEM_REFRESHED (spec 3.15.5); this case is
 * unchanged.
 *
 * <h3>Resolution</h3>
 * A framework that is not launched through its Framework object cannot be launched again by a refresh: the attached
 * extensions (and the system bundle) are left out of the refresh with a warning, their content stays attached until
 * the framework is launched again, and the other bundles are refreshed.
 *
 * <h3>Regression vs WF41</h3>
 * no (new feature) - WF41 did not support framework extensions.
 */
@RunWith(Arquillian.class)
public class RefreshWithUndeployedExtensionIssueTestCase {

    static final String EXTENSION = "refresh-extension";
    static final String EXPORTER = "refresh-exporter";
    static final String IMPORTER = "refresh-importer";
    static final String PACKAGE = "org.jboss.test.osgi.issues.refreshapi";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "refresh-undeployed-extension-tests");
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
    public void testRefreshAfterExtensionUndeploy() throws Exception {
        FrameworkWiring wiring = context.getBundle(0).adapt(FrameworkWiring.class);
        Bundle extension = context.installBundle(EXTENSION, deployer.getDeployment(EXTENSION));
        Bundle exporter = context.installBundle(EXPORTER, deployer.getDeployment(EXPORTER));
        Bundle importer = context.installBundle(IMPORTER, deployer.getDeployment(IMPORTER));
        try {
            Assert.assertTrue("Precondition: the bundles resolve", wiring.resolveBundles(Arrays.asList(exporter, importer)));
            Assert.assertEquals("Precondition: the extension is attached", Bundle.RESOLVED, extension.getState());

            // The extension and the exporter that the importer still uses are uninstalled, both are removal pending
            extension.uninstall();
            exporter.uninstall();
            Assert.assertTrue("Precondition: the exporter is removal pending", wiring.getRemovalPendingBundles().contains(exporter));

            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicInteger type = new AtomicInteger();
            wiring.refreshBundles(null, new FrameworkListener() {
                @Override
                public void frameworkEvent(FrameworkEvent event) {
                    type.set(event.getType());
                    latch.countDown();
                }
            });
            Assert.assertTrue("Expected refreshBundles(null) to notify its listener with PACKAGES_REFRESHED after an extension bundle was undeployed, "
                    + "but the listener was not called within 30 seconds", latch.await(30, TimeUnit.SECONDS));
            Assert.assertEquals("The listener is notified with PACKAGES_REFRESHED", FrameworkEvent.PACKAGES_REFRESHED, type.get());
            Assert.assertEquals("The other bundles are refreshed: the importer of the uninstalled exporter must be unresolved", Bundle.INSTALLED, importer.getState());
            Assert.assertEquals("The framework stays active", Bundle.ACTIVE, context.getBundle(0).getState());
        } finally {
            if (importer.getState() != Bundle.UNINSTALLED)
                importer.uninstall();
        }
    }

    private static JavaArchive bundle(String name, final String exportPackage, final String importPackage, final boolean extension) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name + ".jar");
        archive.addAsResource(new StringAsset(name), (exportPackage != null ? PACKAGE.replace('.', '/') + "/" : name + "/") + "marker.txt");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName().replace(".jar", ""));
                builder.addBundleManifestVersion(2);
                if (extension)
                    builder.addFragmentHost("system.bundle; extension:=framework");
                if (exportPackage != null)
                    builder.addExportPackages(exportPackage);
                if (importPackage != null)
                    builder.addImportPackages(importPackage);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = EXTENSION, managed = false, testable = false)
    public static JavaArchive getExtension() {
        return bundle(EXTENSION, null, null, true);
    }

    @Deployment(name = EXPORTER, managed = false, testable = false)
    public static JavaArchive getExporter() {
        return bundle(EXPORTER, PACKAGE, null, false);
    }

    @Deployment(name = IMPORTER, managed = false, testable = false)
    public static JavaArchive getImporter() {
        return bundle(IMPORTER, null, PACKAGE, false);
    }
}
