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
 * The server exports org.osgi.util.promise but not org.osgi.util.function, which the Promise API is built on: a bundle
 * that imports both packages, as the Promise specification requires for map/filter/flatMap/recover, never resolves.
 *
 * <h2>Severity</h2>
 * major. Every bundle that uses Promise.map, filter, flatMap, recover or recoverWith needs org.osgi.util.function
 * (bnd adds the import automatically), so it cannot be installed and started in the server. A bundle that only imports
 * org.osgi.util.promise resolves, but fails with NoClassDefFoundError: org/osgi/util/function/Function as soon as it
 * calls one of these methods.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem, {@code org.jboss.as.osgi.service.SystemPackagesIntegration}
 * (DEFAULT_INTEGRATION_PACKAGES), together with the module org.jboss.osgi.repository that provides the classes.
 *
 * <h2>Found by</h2>
 * wildfly workstream, Docker smoke test on eclipse-temurin:25-jdk: a bundle with
 * {@code Import-Package: org.osgi.util.promise;version="[1.0,2)",org.osgi.util.function;version="[1.0,2)"} that calls
 * {@code Promises.resolved("abc").map(String::length)} stays INSTALLED:
 * <pre>
 * WARN  [org.jboss.as.osgi] JBAS011910: Cannot resolve requirements: [XPackageRequirement[dirs={filter=(&amp;(osgi.wiring.package=org.osgi.util.function)(&amp;(version&gt;=1.0.0)(!(version&gt;=2.0.0))))},[smoke.fn:1.0.0]]]
 * </pre>
 * Then a review of the system packages against the content of the module org.jboss.osgi.repository.
 *
 * <h2>Spec reference</h2>
 * OSGi Enterprise R7 (osgi.enterprise 7.0.0, the API the build uses), 705.12 org.osgi.util.function: "Bundles wishing
 * to use this package must list the package in the Import-Package header of the bundle's manifest." (same text for
 * org.osgi.util.promise in 705.11, and in Compendium R8 705.11/705.12). The Promise interface that the server exports
 * declares {@code <R> Promise<R> map(org.osgi.util.function.Function<? super T, ? extends R>)},
 * {@code filter(org.osgi.util.function.Predicate)}, {@code flatMap}, {@code recover} and {@code recoverWith} with
 * types of org.osgi.util.function, so a bundle that follows the specification imports org.osgi.util.function, and
 * nothing in the server provides it. OSGi Core R8 3.6.4 (Export-Package, uses directive) also requires the exporter to
 * declare the packages that its exported API uses, here {@code uses:="org.osgi.util.function"}.
 * https://docs.osgi.org/specification/osgi.enterprise/7.0.0/util.promise.html#org.osgi.util.function
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/SystemPackagesIntegration.java:67
 * ({@code "org.osgi.util.promise;version=1.0"} without org.osgi.util.function; added to the integration packages by
 * 2d2b169c "Export org.osgi.util.promise from the integration packages"). On origin/WF41 the framework exported the same
 * package the same way (jbosgi-framework core SystemPaths "org.osgi.util.promise;version=1.0", removed from the framework
 * on the R8 branch). The classes come from jbosgi-repository-core-7.8.0.Final.jar (module org.jboss.osgi.repository),
 * which contains org/osgi/util/promise (20 classes) AND org/osgi/util/function (Function, Predicate).
 *
 * <h2>Root cause</h2>
 * Only the promise package of the embedded Promise 1.0 API is listed in the system packages; its sibling package
 * org.osgi.util.function, which is in the same jar and module and on which the promise API depends, is not.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=PromiseWithoutFunctionPackageIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository. The two -Darq... options start the managed server with port offset 500, so the test cannot connect to another server on 9990 (arquillian.xml has allowConnectingToRunningServer=true); they can be left out when no other server runs.)
 * <h2>Observed</h2>
 * <pre>
 * testPromiseConsumerResolves: Expected a bundle importing org.osgi.util.promise and org.osgi.util.function
 *   (as the Promise spec requires) to resolve, but it stayed INSTALLED
 * testSystemBundleExportsFunctionPackage: Expected the system bundle to export org.osgi.util.function next to
 *   org.osgi.util.promise [version=1.0.0], but it exports: []
 * </pre>
 *
 * <h2>Expected</h2>
 * The bundle resolves; the system bundle exports org.osgi.util.function (version 1.0, the version of the embedded API)
 * and the promise export declares {@code uses:="org.osgi.util.function"}.
 *
 * <h2>Reference behaviour</h2>
 * Not applicable (WildFly integration). The OSGi reference bundles org.osgi.util.promise/org.osgi.util.function and
 * Felix/Equinox distributions always deploy both packages together.
 *
 * <h2>Suggested fix (not applied)</h2>
 * In SystemPackagesIntegration.DEFAULT_INTEGRATION_PACKAGES add {@code "org.osgi.util.function;version=1.0"} and change
 * the promise entry to {@code "org.osgi.util.promise;version=1.0;uses:=\"org.osgi.util.function\""} (the module
 * org.jboss.osgi.repository already exports the path, it is in DEFAULT_SYSTEM_MODULES). Note that the server only
 * offers Promise 1.0: bundles built against osgi.enterprise 7 / Compendium 8 import promise and function
 * {@code [1.1,2)} / {@code [1.2,2)} and will not resolve either; shipping the org.osgi.util.promise and
 * org.osgi.util.function 1.2 bundles would cover those.
 * (verified: with the two entries changed in a local copy both test methods pass.)
 *
 * <h2>Regression vs WF41</h2>
 * no (WF41 exported org.osgi.util.promise 1.0 from the framework, also without org.osgi.util.function).
 */
@RunWith(Arquillian.class)
public class PromiseWithoutFunctionPackageIssueTestCase {

    static final String CONSUMER = "promise-function-consumer";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "promise-function-tests");
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
    public void testPromiseConsumerResolves() throws Exception {
        Bundle bundle = context.installBundle(CONSUMER, deployer.getDeployment(CONSUMER));
        try {
            context.getBundle(0).adapt(FrameworkWiring.class).resolveBundles(java.util.Collections.singleton(bundle));
            Assert.assertTrue("Expected a bundle importing org.osgi.util.promise and org.osgi.util.function (as the Promise spec requires) to resolve, but it stayed "
                    + stateName(bundle.getState()), (bundle.getState() & (Bundle.RESOLVED | Bundle.STARTING | Bundle.ACTIVE)) != 0);
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testSystemBundleExportsFunctionPackage() throws Exception {
        BundleRevision sysrev = context.getBundle(0).adapt(BundleRevision.class);
        List<String> promise = new ArrayList<String>();
        List<String> function = new ArrayList<String>();
        for (BundleCapability cap : sysrev.getDeclaredCapabilities(PackageNamespace.PACKAGE_NAMESPACE)) {
            Object pkg = cap.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE);
            Object version = cap.getAttributes().get(PackageNamespace.CAPABILITY_VERSION_ATTRIBUTE);
            if ("org.osgi.util.promise".equals(pkg))
                promise.add("version=" + version);
            if ("org.osgi.util.function".equals(pkg))
                function.add("version=" + version);
        }
        Assert.assertFalse("Precondition: the system bundle exports org.osgi.util.promise", promise.isEmpty());
        Assert.assertFalse("Expected the system bundle to export org.osgi.util.function next to org.osgi.util.promise " + promise
                + ", but it exports: " + function, function.isEmpty());
    }

    private static String stateName(int state) {
        switch (state) {
            case Bundle.INSTALLED: return "INSTALLED";
            case Bundle.RESOLVED: return "RESOLVED";
            case Bundle.STARTING: return "STARTING";
            case Bundle.ACTIVE: return "ACTIVE";
            case Bundle.STOPPING: return "STOPPING";
            default: return "UNINSTALLED";
        }
    }

    @Deployment(name = CONSUMER, managed = false, testable = false)
    public static JavaArchive getConsumer() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, CONSUMER);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                // What bnd generates for a consumer of Promise.map(Function) built against the Promise 1.0 API
                builder.addImportPackages("org.osgi.util.promise;version=\"[1.0,2)\"", "org.osgi.util.function;version=\"[1.0,2)\"");
                return builder.openStream();
            }
        });
        return archive;
    }
}
