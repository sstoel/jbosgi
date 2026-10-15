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
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Hashtable;

import javax.naming.InitialContext;
import javax.naming.NameNotFoundException;

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
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.wiring.BundleWiring;

/**
 * In WildFly, JNDI lookups with the {@code osgi:} URL scheme through {@code new InitialContext()} are always performed
 * with the system bundle's BundleContext instead of the caller's: a bundle gets services that are not compatible with
 * its class space (a proxy it cannot cast), and the services are gotten (and never released) by the system bundle
 * instead of the bundle that looked them up.
 *
 * <h2>Severity</h2>
 * major. The traditional JNDI client model ({@code new InitialContext().lookup("osgi:service/...")}) is the way
 * non-OSGi-aware libraries and EE-style code in bundles use OSGi services. Every such lookup ignores the class space of
 * the bundle (ClassCastException instead of NameNotFoundException when two bundles have different versions of an API)
 * and the service accounting is wrong: the services stay in use by the system bundle (getUsingBundles = [system.bundle])
 * after the calling bundle stopped, ServiceFactory services are created for the system bundle only, and the calling
 * bundle's stop does not release them.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/integration/naming: {@code org.jboss.as.osgi.naming.JNDIServiceListener},
 * {@code NamingExtension.startSystemServices}.
 *
 * <h2>Found by</h2>
 * wildfly-ee workstream (deep 2), exploration of the naming integration (EE code and bundles using osgi:service lookups)
 * on the patched WildFly 41.0.1 server (JDK 25): with the bundle's class loader as thread context class loader the lookup
 * returned the provider's service although the bundle has its own, incompatible copy of the interface; with the same
 * bundle's context passed as osgi.service.jndi.bundleContext the lookup correctly fails with NameNotFoundException.
 *
 * <h2>Spec reference</h2>
 * OSGi Enterprise R7, 126.6 OSGi URL Scheme: "The URL Context must use the owning bundle to perform the service queries.
 * The owning bundle is the bundle that requested the initial Context from the JNDI Context Manager service or received
 * its Context through the InitialContext class. The returned objects must not be incompatible with the class space of
 * the owning bundle." 126.6.1 Service Proxies: "If an interface is not compatible with the owning bundle's class space
 * then it must not be implemented on the proxy". 126.7.3 Caller's Bundle Context: "Look in the JNDI environment
 * properties for a property called osgi.service.jndi.bundleContext ... Obtain the Thread Context Class Loader; if it, or
 * an ancestor class loader, implements the BundleReference interface, call its getBundle method to get the client's
 * Bundle ... Walk the call stack until the invoker is found."
 * https://docs.osgi.org/specification/osgi.enterprise/7.0.0/service.jndi.html#d0e83010
 *
 * <h2>Code location</h2>
 * wildfly/integration/naming/src/main/java/org/jboss/as/osgi/naming/JNDIServiceListener.java:79-91
 * ({@code handleJNDIRegistration}: {@code InitialContext.addUrlContextFactory(scheme, (ObjectFactory)
 * bundleContext.getService(ref))} with the system BundleContext passed in by NamingExtension.startSystemServices,
 * line 96). Aries JNDI registers its osgi: URL context factory as a ServiceFactory that binds the factory to the
 * requesting bundle; jbosgi requests it once for the system bundle and installs that single instance as WildFly's global
 * URL context factory, so the caller's bundle (126.7.3) is never determined. Unchanged since WF41 (pre-existing).
 *
 * <h2>Root cause</h2>
 * WildFly owns the NamingManager InitialContextFactoryBuilder, so Aries JNDI cannot find the caller itself; the bridge
 * that hands OSGi URL context factories to WildFly's InitialContext resolves them once with the system bundle instead of
 * per caller (environment property, thread context class loader, call stack).
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=OsgiUrlSchemeIgnoresCallerBundleIssueTestCase \
 *     -Darq.container.jboss.configuration.managementPort=11090 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=1100
 * </pre>
 * (Prerequisites: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork in the local Maven
 * repository; on JDK 24+ the testsuite needs surefire.security.args without -Djava.security.manager, see
 * TestJvmSecurityManagerIssueTestCase.) Aries JNDI is a default capability of standalone-osgi.xml. Deterministic.
 *
 * <h2>Observed</h2>
 * <pre>
 * testLookupUsesCallersClassSpace: java.lang.AssertionError: osgi:service lookup with the thread context class loader
 *   of a bundle that has its own copy of the Svc interface must fail with NameNotFoundException (no service compatible
 *   with its class space; the same lookup with osgi.service.jndi.bundleContext does), but it returned a proxy that does
 *   not implement the bundle's Svc: jdk.proxy10.$Proxy42 implementing [interface
 *   org.jboss.test.osgi.issues.OsgiUrlSchemeIgnoresCallerBundleIssueTestCase$Svc from HostBundleClassLoader for Module
 *   "deployment.jndi-scheme-provider" ...]
 * testServiceIsUsedByCallingBundle: java.lang.AssertionError: after an osgi:service lookup and call from bundle
 *   jndi-scheme-importer the service must be in use by that bundle, but getUsingBundles() is [system.bundle:0.0.0]
 * </pre>
 *
 * <h2>Expected</h2>
 * The owning bundle is determined per lookup as in 126.7.3 (environment property, else the thread context class loader,
 * else the call stack); service queries, gets and the proxy interfaces use that bundle's BundleContext and class space.
 *
 * <h2>Reference behaviour</h2>
 * Not run on Equinox/Felix for this scenario: there Aries JNDI installs its own InitialContextFactoryBuilder and
 * ObjectFactoryBuilder singletons and resolves the caller bundle itself (126.7.3); in WildFly those singletons belong to
 * WildFly and jbosgi bridges the URL context factories. In WildFly only the explicit environment property path works
 * (verified in the first test as a precondition).
 *
 * <h2>Suggested fix (not applied)</h2>
 * Register a delegating ObjectFactory with WildFly's InitialContext per scheme that determines the caller's
 * BundleContext on each call (environment property, TCCL BundleReference, call stack via StackWalker) and obtains the
 * URL context factory service through that BundleContext (ungetting it when the bundle stops); or put the caller's
 * BundleContext into the environment (osgi.service.jndi.bundleContext) before delegating to the system-bundle factory.
 *
 * <h2>Regression vs WF41</h2>
 * no (JNDIServiceListener and NamingExtension are unchanged since WF41; pre-existing).
 */
@RunWith(Arquillian.class)
public class OsgiUrlSchemeIgnoresCallerBundleIssueTestCase {

    static final String SVC = "org.jboss.test.osgi.issues.OsgiUrlSchemeIgnoresCallerBundleIssueTestCase$Svc";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    public interface Svc {
        String name();
    }

    public static class SvcImpl implements Svc {
        @Override
        public String name() {
            return "svc";
        }
    }

    public static class ProviderActivator implements BundleActivator {
        @Override
        public void start(BundleContext context) throws Exception {
            context.registerService(Svc.class.getName(), new SvcImpl(), null);
        }

        @Override
        public void stop(BundleContext context) throws Exception {
        }
    }

    @Deployment
    public static JavaArchive testDeployment() {
        final JavaArchive jar = ShrinkWrap.create(JavaArchive.class, "osgi-url-scheme-tests");
        jar.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(jar.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages("javax.naming");
                builder.addImportPackages(BundleWiring.class);
                return builder.openStream();
            }
        });
        return jar;
    }

    @Test
    public void testLookupUsesCallersClassSpace() throws Exception {
        Bundle provider = context.installBundle("jndi-scheme-provider", deployer.getDeployment("provider"));
        Bundle ownCopy = context.installBundle("jndi-scheme-own-copy", deployer.getDeployment("own-copy"));
        try {
            provider.start();
            ownCopy.start();
            Class<?> ownSvc = ownCopy.loadClass(SVC);
            Assert.assertNotSame("Precondition: the bundle has its own copy of Svc", provider.loadClass(SVC), ownSvc);

            // The documented environment property gives the expected result
            Hashtable<String, Object> env = new Hashtable<String, Object>();
            env.put("osgi.service.jndi.bundleContext", ownCopy.getBundleContext());
            try {
                new InitialContext(env).lookup("osgi:service/" + SVC);
                Assert.fail("Precondition: the lookup with osgi.service.jndi.bundleContext fails with NameNotFoundException");
            } catch (NameNotFoundException ex) {
                // expected
            }

            // The traditional client: the thread context class loader identifies the calling bundle
            Object result;
            ClassLoader old = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(ownCopy.adapt(BundleWiring.class).getClassLoader());
            try {
                result = new InitialContext().lookup("osgi:service/" + SVC);
            } catch (NameNotFoundException ex) {
                result = null;
            } finally {
                Thread.currentThread().setContextClassLoader(old);
            }
            if (result != null) {
                StringBuilder ifaces = new StringBuilder();
                for (Class<?> iface : result.getClass().getInterfaces())
                    ifaces.append(iface).append(" from ").append(iface.getClassLoader()).append(", ");
                Assert.fail("osgi:service lookup with the thread context class loader of a bundle that has its own copy of the Svc interface must fail with "
                        + "NameNotFoundException (no service compatible with its class space; the same lookup with osgi.service.jndi.bundleContext does), but it returned "
                        + (ownSvc.isInstance(result) ? "a compatible object " : "a proxy that does not implement the bundle's Svc: ") + result.getClass().getName()
                        + " implementing [" + ifaces + "]");
            }
        } finally {
            ownCopy.uninstall();
            provider.uninstall();
        }
    }

    @Test
    public void testServiceIsUsedByCallingBundle() throws Exception {
        Bundle provider = context.installBundle("jndi-scheme-provider", deployer.getDeployment("provider"));
        Bundle importer = context.installBundle("jndi-scheme-importer", deployer.getDeployment("importer"));
        try {
            provider.start();
            importer.start();
            ClassLoader old = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(importer.adapt(BundleWiring.class).getClassLoader());
            try {
                Object svc = new InitialContext().lookup("osgi:service/" + SVC);
                Class<?> importerSvc = importer.loadClass(SVC);
                Assert.assertSame("Precondition: the importer is wired to the provider", provider.loadClass(SVC), importerSvc);
                Assert.assertTrue("Precondition: the importer gets a compatible service object", importerSvc.isInstance(svc));
                Method name = importerSvc.getMethod("name");
                Assert.assertEquals("Precondition: the service can be called", "svc", name.invoke(svc));
            } finally {
                Thread.currentThread().setContextClassLoader(old);
            }
            ServiceReference<?> sref = provider.getBundleContext().getServiceReference(SVC);
            Bundle[] users = sref.getUsingBundles();
            Assert.assertTrue("after an osgi:service lookup and call from bundle jndi-scheme-importer the service must be in use by that bundle, but getUsingBundles() is "
                    + (users == null ? null : Arrays.asList(users)), users != null && Arrays.asList(users).contains(importer));
        } finally {
            importer.uninstall();
            provider.uninstall();
        }
    }

    @Deployment(name = "provider", managed = false, testable = false)
    public static JavaArchive getProvider() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "jndi-scheme-provider.jar");
        archive.addClasses(Svc.class, SvcImpl.class, ProviderActivator.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName("jndi-scheme-provider");
                builder.addBundleManifestVersion(2);
                builder.addBundleActivator(ProviderActivator.class);
                // the attribute makes sure that the importer is wired to this bundle (module bundles over-export, see ModuleAdaptorOverExportIssueTestCase)
                builder.addExportPackages(Svc.class.getPackage().getName() + ";jndi-scheme-test=true");
                builder.addImportPackages(BundleActivator.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    // Has its own copy of Svc and does not import the provider's package
    @Deployment(name = "own-copy", managed = false, testable = false)
    public static JavaArchive getOwnCopy() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "jndi-scheme-own-copy.jar");
        archive.addClasses(Svc.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName("jndi-scheme-own-copy");
                builder.addBundleManifestVersion(2);
                builder.addImportPackages("javax.naming");
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = "importer", managed = false, testable = false)
    public static JavaArchive getImporter() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "jndi-scheme-importer.jar");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName("jndi-scheme-importer");
                builder.addBundleManifestVersion(2);
                builder.addImportPackages("javax.naming");
                builder.addImportPackages(Svc.class.getPackage().getName() + ";jndi-scheme-test=true");
                return builder.openStream();
            }
        });
        return archive;
    }
}
