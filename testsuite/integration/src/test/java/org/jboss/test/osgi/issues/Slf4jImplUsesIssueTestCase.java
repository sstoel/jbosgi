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
import java.util.Collections;
import java.util.List;

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
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * The system bundle exports {@code org.slf4j.impl} (the slf4j 1.x binding package of slf4j-jboss-logmanager, whose
 * classes are linked against the server's slf4j 2.0 {@code org.slf4j}) without {@code uses:="org.slf4j"}: a real
 * slf4j-api 1.7 bundle resolves against it and every logger lookup through that bundle fails with a LinkageError.
 *
 * <h2>Severity</h2>
 * major. Deploying the standard slf4j-api 1.7.x bundle (many applications ship it; it exports org.slf4j 1.7.x and
 * imports {@code org.slf4j.impl;version=1.6.0}) succeeds, but any bundle wired to its org.slf4j gets
 * {@code java.lang.LinkageError: loader constraint violation: when resolving method 'org.slf4j.ILoggerFactory
 * org.slf4j.impl.StaticLoggerBinder.getLoggerFactory()'} at the first {@code LoggerFactory.getLogger} call, instead of
 * a resolution error at install/start.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem, {@code org.jboss.as.osgi.service.SystemPackagesIntegration}
 * (DEFAULT_INTEGRATION_PACKAGES).
 *
 * <h2>Found by</h2>
 * wildfly workstream, round 2 ("check the slf4j 1.7 export with a real slf4j-api 1.7 bundle"), Docker smoke test on
 * eclipse-temurin:25-jdk:
 * <ul>
 * <li>a consumer importing {@code org.slf4j;version="[1.7,2)"} without any slf4j bundle: works (wired to the system
 * bundle, Slf4jLogger of the org.slf4j 2.0.18 module);</li>
 * <li>with the real slf4j-api-1.7.36.jar deployed (Bundle-SymbolicName slf4j.api, ACTIVE) the same consumer is still
 * wired to the system bundle (provider preference, see resolver/13) and works;</li>
 * <li>a consumer importing {@code org.slf4j;version="[1.7.36,2)"}, i.e. wired to the slf4j.api bundle:
 * {@code SMOKE slf4j FAILED java.lang.LinkageError: loader constraint violation: when resolving method
 * 'org.slf4j.ILoggerFactory org.slf4j.impl.StaticLoggerBinder.getLoggerFactory()' the class loader
 * 'deployment.slf4j-api-1.7.36.jar' of the current class, org/slf4j/LoggerFactory, and the class loader ... for the
 * method's defining class, org/slf4j/impl/StaticLoggerBinder, have different Class objects for the type
 * org/slf4j/ILoggerFactory} at org.slf4j.LoggerFactory.getILoggerFactory(LoggerFactory.java:423).</li>
 * </ul>
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 3.7.6 Package Constraints: "Classes can depend on classes in other packages. For example, when they
 * extend classes from another package, or these other classes appear in method signatures. ... These inter-package
 * dependencies are modeled with the uses directive on the Export-Package header." and 3.9.5 (Parent Delegation, system
 * packages): "Exposing packages from the parent class loader in this fashion must also take into account any uses
 * directives of the underlying packages." StaticLoggerBinder.getLoggerFactory() returns org.slf4j.ILoggerFactory, so
 * org.slf4j.impl uses org.slf4j. https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/SystemPackagesIntegration.java:75
 * ({@code "org.slf4j.impl;version=1.7"}, no uses directive; the org.slf4j.spi/helpers 1.7 and 2.0 entries at 68-74 have
 * none either). Pre-existing (same entry on origin/WF41).
 *
 * <h2>Root cause</h2>
 * The binding package is exported without its dependency on org.slf4j, so the resolver cannot see that wiring it to a
 * bundle that has its own org.slf4j creates an inconsistent class space.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=Slf4jImplUsesIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository. The two -Darq... options start the managed server with port offset 500, so the test cannot connect to another server on 9990 (arquillian.xml has allowConnectingToRunningServer=true); they can be left out when no other server runs.)
 * <h2>Observed</h2>
 * <pre>
 * testSlf4jImplExportDeclaresUses: Expected the system bundle's org.slf4j.impl export to declare uses:="org.slf4j",
 *   but its uses directive is null
 * testSlf4jApi17BundleNotWiredToBinding: Expected a bundle with the headers of slf4j-api 1.7.36 (exports its own
 *   org.slf4j, imports org.slf4j.impl;version=1.6.0) not to resolve against the server's slf4j 2 binding, but it resolved
 * </pre>
 *
 * <h2>Expected</h2>
 * {@code org.slf4j.impl;version=1.7;uses:="org.slf4j"}; the slf4j-api 1.7 bundle then fails to resolve with a uses
 * conflict (or, if the server wants to support it, is offered a binding that matches its own org.slf4j).
 *
 * <h2>Reference behaviour</h2>
 * Not applicable (WildFly integration exports).
 *
 * <h2>Suggested fix (not applied)</h2>
 * Add {@code uses:="org.slf4j"} to the org.slf4j.impl entry (and {@code uses:="org.slf4j"} to org.slf4j.spi/helpers,
 * whose APIs reference org.slf4j types). Verified: with the org.slf4j.impl entry changed in a local copy (service jar replaced
 * in a server copy) both test methods pass.
 *
 * <h2>Regression vs WF41</h2>
 * no (same entry on origin/WF41).
 */
@RunWith(Arquillian.class)
public class Slf4jImplUsesIssueTestCase {

    static final String SLF4J_API_17 = "slf4j-api-17-headers";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "slf4j-uses-tests");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(Bundle.class, PackageNamespace.class, BundleCapability.class, FrameworkWiring.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Test
    public void testSlf4jImplExportDeclaresUses() throws Exception {
        List<String> uses = new ArrayList<String>();
        for (BundleCapability cap : context.getBundle(0).adapt(BundleRevision.class).getDeclaredCapabilities(PackageNamespace.PACKAGE_NAMESPACE)) {
            if ("org.slf4j.impl".equals(cap.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE)))
                uses.add(cap.getDirectives().get(PackageNamespace.CAPABILITY_USES_DIRECTIVE));
        }
        Assert.assertFalse("Precondition: the system bundle exports org.slf4j.impl", uses.isEmpty());
        for (String value : uses) {
            Assert.assertTrue("Expected the system bundle's org.slf4j.impl export to declare uses:=\"org.slf4j\", but its uses directive is " + value,
                    value != null && value.contains("org.slf4j"));
        }
    }

    @Test
    public void testSlf4jApi17BundleNotWiredToBinding() throws Exception {
        Bundle bundle = context.installBundle(SLF4J_API_17, deployer.getDeployment(SLF4J_API_17));
        try {
            context.getBundle(0).adapt(FrameworkWiring.class).resolveBundles(Collections.singleton(bundle));
            Assert.assertEquals("Expected a bundle with the headers of slf4j-api 1.7.36 (exports its own org.slf4j, imports org.slf4j.impl;version=1.6.0) "
                    + "not to resolve against the server's slf4j 2 binding, but it resolved", Bundle.INSTALLED, bundle.getState());
        } finally {
            bundle.uninstall();
        }
    }

    /** The OSGi headers of slf4j-api-1.7.36.jar (Maven Central), without classes */
    @Deployment(name = SLF4J_API_17, managed = false, testable = false)
    public static JavaArchive getSlf4jApi17() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, SLF4J_API_17);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName("slf4j.api.headers");
                builder.addBundleManifestVersion(2);
                builder.addBundleVersion("1.7.36");
                builder.addExportPackages("org.slf4j;version=1.7.36", "org.slf4j.spi;version=1.7.36", "org.slf4j.helpers;version=1.7.36", "org.slf4j.event;version=1.7.36");
                builder.addImportPackages("org.slf4j.impl;version=1.6.0");
                return builder.openStream();
            }
        });
        return archive;
    }
}
