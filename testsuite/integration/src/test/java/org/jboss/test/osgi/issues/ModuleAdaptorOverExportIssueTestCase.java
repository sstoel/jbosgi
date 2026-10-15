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

import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A WildFly module registered as a bundle (an osgi subsystem "capability", e.g. org.glassfish.jaxb) advertises an
 * osgi.wiring.package capability for every package VISIBLE to its module, including packages of non-exported
 * dependencies such as JDK modules; imports get wired to it and the classes then cannot be loaded.
 *
 * <h2>Severity</h2>
 * major. With the shipped standalone-osgi.xml the module bundle org.glassfish.jaxb alone declares 631 package
 * capabilities, among them about 400 that no other bundle exports: JDK internals (jdk.internal.*, sun.nio.ch, sun.awt,
 * sun.security.*, com.sun.org.apache.xerces.internal.*, com.sun.tools.javac.*, ...), java.awt.peer, and on Java 25
 * javax.sound. Consequences for real bundles:
 * <ul>
 * <li>imports that must fail resolve instead: e.g. Netty-style {@code Import-Package: sun.nio.ch;resolution:=optional}
 * gets wired to org.glassfish.jaxb, so the bundle's "is the optional package there" logic is misled;</li>
 * <li>a bundle gets JDK-internal classes that the system bundle deliberately does not export (jdk.internal.misc.VM
 * loads through the wire), bypassing org.osgi.framework.system.packages/bootdelegation;</li>
 * <li>or the wire is useless: the import of javax.sound on Java 25 is wired to org.glassfish.jaxb and
 * javax.sound.SoundClip cannot be loaded (ClassNotFoundException, see JavaxPackagesOfRunningJdkIssueTestCase).</li>
 * </ul>
 *
 * <h2>Component</h2>
 * jbosgi-resolver api, {@code org.jboss.osgi.resolver.spi.OSGiMetaDataProcessor.ModuleExportPackagesCollector} (used by
 * {@code AbstractResourceBuilder.loadFrom(Module)}), for the module bundles that the WildFly osgi subsystem installs
 * from its {@code <capability name="..."/>} entries.
 *
 * <h2>Found by</h2>
 * wildfly workstream, while root-causing JavaxPackagesOfRunningJdkIssueTestCase on Java 25: the import of javax.sound
 * was wired to org.glassfish.jaxb, whose module only depends on java.desktop without re-exporting it.
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 3.6.5 Export-Package: "The Export-Package header contains a declaration of exported packages." and 3.9.4
 * (class loading): "If the class or resource is in a package that is imported using Import-Package ... the request is
 * delegated to the exporting bundle's class loader" (Figure 3.12 step 4: "the search ends ... if the class/resource is
 * not found"). A wire to an exporter that cannot load the classes of the wired package breaks this delegation model:
 * resolution must only use capabilities the provider actually satisfies (Core 3.8, Resolving: a package import is
 * resolved to an exporter of that package).
 * https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html
 *
 * <h2>Code location</h2>
 * jbosgi-resolver api/src/main/java/org/jboss/osgi/resolver/spi/OSGiMetaDataProcessor.java:33-41
 * ({@code for (String path : module.getExportedPaths())}); api/src/main/java/org/jboss/osgi/resolver/spi/AbstractResourceBuilder.java:329-336.
 * jboss-modules 2.1.6 Module.getExportedPaths() returns {@code getPathsUnchecked().keySet()}, i.e. ALL paths of the
 * module's linkage, imported ones included, not only the exported ones. Present since 2012 (053fab6); a fix attempt
 * (8bfcc70 "ResourceBuilder should iterate over resources instead of getting exported paths") was reverted (8c212b9).
 *
 * <h2>Root cause</h2>
 * The capability list of a module bundle is built from the module's visible paths instead of the paths the module
 * exports (its own resource roots plus dependencies declared with export="true"). The importer's module dependency on
 * the module bundle only sees the really exported paths, so class loading through the wire fails.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=ModuleAdaptorOverExportIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository. The two -Darq... options start the managed server with port offset 500, so the test cannot connect to another server on 9990 (arquillian.xml has allowConnectingToRunningServer=true); they can be left out when no other server runs.)
 * <h2>Observed</h2>
 * <pre>
 * testModuleBundleOnlyExportsWhatItsModuleExports: Expected the module bundle org.glassfish.jaxb not to export
 *   javax.swing (a package of its non-exported dependency java.desktop), but it declares an osgi.wiring.package capability for it
 * testImportOfPackageThatNoBundleExports: Expected the import of jdk.internal.misc (an internal JDK package that is no
 *   system package and that no bundle exports) not to resolve, but it was wired to org.glassfish.jaxb; loading
 *   jdk.internal.misc.VM through it loads class jdk.internal.misc.VM
 * </pre>
 *
 * <h2>Expected</h2>
 * Module bundles declare only the packages their module exports (own resource roots and re-exported dependencies); a
 * bundle importing jdk.internal.misc stays INSTALLED with a resolution error.
 *
 * <h2>Reference behaviour</h2>
 * Not applicable (jboss-modules specific). In Equinox/Felix a bundle exports only what its Export-Package declares.
 *
 * <h2>Suggested fix (not applied)</h2>
 * Compute the exported packages from the module's own resource loaders (iterate the resources of its ResourceLoaders,
 * as attempted in 8bfcc70) plus the dependencies that the module re-exports, or from the module's export filter;
 * never from Module.getExportedPaths(). (Not verified with a patched resolver; the expected behaviour follows from the spec.)
 *
 * <h2>Regression vs WF41</h2>
 * no (same code on origin/WF41).
 */
@RunWith(Arquillian.class)
public class ModuleAdaptorOverExportIssueTestCase {

    static final String IMPORTER = "internal-jdk-package-importer";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "module-adaptor-tests");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(Bundle.class, PackageNamespace.class, BundleCapability.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Test
    public void testModuleBundleOnlyExportsWhatItsModuleExports() throws Exception {
        Bundle jaxb = null;
        for (Bundle bundle : context.getBundles()) {
            if ("org.glassfish.jaxb".equals(bundle.getSymbolicName()))
                jaxb = bundle;
        }
        Assert.assertNotNull("Precondition: the module bundle org.glassfish.jaxb is installed (standalone-osgi.xml capability)", jaxb);
        boolean ownPackage = false;
        boolean swing = false;
        for (BundleCapability cap : jaxb.adapt(BundleRevision.class).getDeclaredCapabilities(PackageNamespace.PACKAGE_NAMESPACE)) {
            Object pkg = cap.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE);
            ownPackage |= "org.glassfish.jaxb.runtime.v2".equals(pkg);
            swing |= "javax.swing".equals(pkg);
        }
        Assert.assertTrue("Precondition: org.glassfish.jaxb exports its own package org.glassfish.jaxb.runtime.v2", ownPackage);
        Assert.assertFalse("Expected the module bundle org.glassfish.jaxb not to export javax.swing (a package of its non-exported dependency java.desktop), "
                + "but it declares an osgi.wiring.package capability for it", swing);
    }

    @Test
    public void testImportOfPackageThatNoBundleExports() throws Exception {
        Bundle bundle = context.installBundle(IMPORTER, deployer.getDeployment(IMPORTER));
        try {
            context.getBundle(0).adapt(FrameworkWiring.class).resolveBundles(Collections.singleton(bundle));
            if (bundle.getState() == Bundle.INSTALLED)
                return; // correct: nothing exports the package
            String provider = "none";
            for (BundleWire wire : bundle.adapt(BundleWiring.class).getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE))
                provider = wire.getProvider().getSymbolicName();
            String load;
            try {
                load = "loads " + loadClass(bundle, "jdk.internal.misc.VM");
            } catch (Throwable th) {
                load = "fails: " + th;
            }
            Assert.fail("Expected the import of jdk.internal.misc (an internal JDK package that is no system package and that no bundle exports) not to resolve, "
                    + "but it was wired to " + provider + "; loading jdk.internal.misc.VM through it " + load);
        } finally {
            bundle.uninstall();
        }
    }

    // Retry the "JBOSGI011217 Cannot load module" race of a freshly resolved deployment (ModuleSpecNotUpIssueTestCase)
    private static Class<?> loadClass(Bundle bundle, String name) throws Exception {
        for (int i = 0;; i++) {
            try {
                return bundle.loadClass(name);
            } catch (IllegalStateException ex) {
                if (i == 5)
                    throw ex;
                Thread.sleep(200);
            }
        }
    }

    @Deployment(name = IMPORTER, managed = false, testable = false)
    public static JavaArchive getImporter() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, IMPORTER);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages("jdk.internal.misc");
                return builder.openStream();
            }
        });
        return archive;
    }
}
