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
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * On Java 25 the system bundle does not export javax.sound, a package of the running JDK (java.desktop, new class
 * javax.sound.SoundClip): the javax.* system packages are a fixed list for older JDKs.
 *
 * <h2>Severity</h2>
 * minor. Only bundles that use the new Java 25 API javax.sound.SoundClip are affected: the class cannot be loaded. The same
 * fixed list will miss every javax package a later JDK adds (and keep exporting packages a later JDK removes).
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem, {@code org.jboss.as.osgi.service.SystemPackagesIntegration.JAVAX_API_PACKAGES}
 * used as the default of org.osgi.framework.system.packages by {@code FrameworkBootstrapService}.
 *
 * <h2>Found by</h2>
 * wildfly workstream: the testsuite run on JDK 25 fails the existing
 * org.jboss.as.test.integration.osgi.classloading.SystemPackagesTestCase.testSystemPackages
 * ("system.bundle does not contain all javax.api packages", the only difference is javax.sound); passes on JDK 21.
 * {@code Class.forName("javax.sound.SoundClip")} succeeds on JDK 25.0.4 and fails on JDK 21.
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8, 10.1.15.73 Constants.FRAMEWORK_SYSTEMPACKAGES: "Framework launching property identifying packages which
 * the system bundle must export. If this property is not specified then the framework must calculate a reasonable
 * default value for the current execution environment." and 4.2.2 Launching Properties: "the framework should export
 * the JRE packages as system packages". The property is not configured in standalone-osgi.xml, so the default must
 * fit the running JDK. The R8 branch already does this for java.* (commit 9df5185d: "The java.* packages are taken
 * from the running Java platform by the framework"), not for javax.*.
 * https://docs.osgi.org/specification/osgi.core/8.0.0/framework.api.html#org.osgi.framework.Constants.FRAMEWORK_SYSTEMPACKAGES
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/SystemPackagesIntegration.java:82-200
 * ("Keep in sync with module javax.api", javax.sound.midi/.spi and javax.sound.sampled/.spi only, lines 132-135);
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/FrameworkBootstrapService.java:194-205 (default of
 * org.osgi.framework.system.packages). The module javax.api of WildFly 41 does export the path javax/sound on JDK 25
 * (it is computed from the JDK), so only the OSGi export is missing.
 *
 * <h2>Root cause</h2>
 * The javax.* part of the system packages is a hard-coded list; it is not derived from the running JDK (or from the
 * exported paths of the javax.api module, which the integration already depends on).
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=JavaxPackagesOfRunningJdkIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository. The two -Darq... options start the managed server with port offset 500, so the test cannot connect to another server on 9990 (arquillian.xml has allowConnectingToRunningServer=true); they can be left out when no other server runs.)
 * <h2>Observed</h2>
 * <pre>
 * java.lang.AssertionError: Expected javax.sound.SoundClip of the running Java 25 to be loadable through the import of
 * javax.sound from the system bundle, but the import is wired to 'org.glassfish.jaxb' and the load fails:
 * java.lang.ClassNotFoundException: javax.sound.SoundClip from [Module "deployment.javax-sound-importer" from Service Module Loader]
 * </pre>
 * The system bundle exports javax.sound.midi(.spi) and javax.sound.sampled(.spi) but not javax.sound. The import resolves
 * anyway, to the module-adapted bundle org.glassfish.jaxb, which advertises every package visible to its module
 * (here javax.sound from its non-exported dependency java.desktop) without exporting it; that second defect is
 * reported separately as ModuleAdaptorOverExportIssueTestCase.
 * <pre>
 * </pre>
 *
 * <h2>Expected</h2>
 * The import of javax.sound is wired to the system bundle and javax.sound.SoundClip can be loaded through it.
 *
 * <h2>Reference behaviour</h2>
 * Not checked against Equinox/Felix in this server context.
 *
 * <h2>Suggested fix (not applied)</h2>
 * Compute the javax.* (and org.w3c/org.xml/org.ietf ...) packages at boot from the exported paths of the javax.api
 * module (or from {@code ModuleLayer.boot()} packages of the java.se modules), like the framework does for java.*;
 * keep the fixed list only as a fallback. Workaround: {@code <property name="org.osgi.framework.system.packages.extra">javax.sound</property>}
 * in the osgi subsystem. Verified: with that property (CLI {@code /subsystem=osgi/property=org.osgi.framework.system.packages.extra:add(value=javax.sound)}
 * and reload) the test passes, the import is then wired to the system bundle.
 *
 * <h2>Regression vs WF41</h2>
 * no (the same fixed list on origin/WF41); it only shows on JDK 25.
 */
@RunWith(Arquillian.class)
public class JavaxPackagesOfRunningJdkIssueTestCase {

    static final String SOUND_CLIP_BUNDLE = "javax-sound-importer";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "javax-packages-tests");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(Bundle.class, FrameworkWiring.class, PackageNamespace.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Test
    public void testBundleImportingJavaxSoundResolves() throws Exception {
        Assume.assumeTrue("javax.sound exists from Java 25 on", Runtime.version().feature() >= 25);
        Bundle bundle = context.installBundle(SOUND_CLIP_BUNDLE, deployer.getDeployment(SOUND_CLIP_BUNDLE));
        try {
            context.getBundle(0).adapt(FrameworkWiring.class).resolveBundles(Collections.singleton(bundle));
            Assert.assertTrue("Expected a bundle importing javax.sound (a package of the running Java " + Runtime.version().feature()
                    + ") to resolve, but it stayed INSTALLED", bundle.getState() != Bundle.INSTALLED);
            String provider = "none";
            long providerId = -1;
            BundleWiring wiring = bundle.adapt(BundleWiring.class);
            for (BundleWire wire : wiring.getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE)) {
                provider = wire.getProvider().getSymbolicName();
                providerId = wire.getProvider().getBundle().getBundleId();
            }
            try {
                loadClass(bundle, "javax.sound.SoundClip");
            } catch (ClassNotFoundException ex) {
                Assert.fail("Expected javax.sound.SoundClip of the running Java " + Runtime.version().feature()
                        + " to be loadable through the import of javax.sound from the system bundle, but the import is wired to '"
                        + provider + "' and the load fails: " + ex);
            }
            // The system bundle is bundle 0, its symbolic name is the implementation name with the alias system.bundle
            Assert.assertEquals("Expected the import of javax.sound to be wired to the system bundle, but it is wired to " + provider, 0, providerId);
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

    @Deployment(name = SOUND_CLIP_BUNDLE, managed = false, testable = false)
    public static JavaArchive getSoundClipBundle() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, SOUND_CLIP_BUNDLE);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages("javax.sound");
                return builder.openStream();
            }
        });
        return archive;
    }
}
