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
import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.service.log.LogService;

/**
 * Every uninstalled bundle that imported a package from a long-lived exporter (e.g. org.osgi.framework from the system
 * bundle) stays in memory forever with its class loader: the exporter's wiring keeps the stale wire, which references
 * the requirer revision. Each redeploy of a bundle leaks its class loader.
 *
 * <h2>Severity</h2>
 * critical (memory leak in the running server, unbounded). Measured in the Docker smoke test (eclipse-temurin:25-jdk,
 * patched WildFly 41.0.1.Final + jbosgi R8 overlay): redeploying a bundle with an activator ({@code deploy --force})
 * retained one more copy of the bundle per redeploy: 1, 11, 21, 41, 61, 81 Activator classes after 0/10/20/40/60/80
 * redeploys (GC.class_histogram after two GC.run), the same for a WAB; 92 UserBundleState / 91 HostBundleClassLoader
 * instances alive with 24 installed bundles. Bundles that are redeployed during the life of the server (the normal
 * development and update cycle) exhaust the heap/metaspace eventually.
 *
 * <h2>Component</h2>
 * jbosgi-resolver api, {@code org.jboss.osgi.resolver.spi.AbstractWiring} (the {@code provided} wire map of a wiring
 * only grows: addProvidedWire, no removal) and {@code AbstractBundleWiring.getProvidedWires}; reached through
 * jbosgi-framework when a revision is removed (uninstall + refresh / not in use any more).
 *
 * <h2>Found by</h2>
 * wildfly workstream: Docker smoke test redeploy loop with GC.class_histogram, then a heap dump analysed with a small
 * HPROF path-to-GC-root script ($SP/agents/wildfly/scripts/hprof_paths.py). 100 of 115 retained UserBundleState were
 * reachable only via:
 * <pre>
 * UserBundleState &lt;- .bundle HostBundleRevision &lt;- .requirer AbstractBundleWire &lt;- ArrayList &lt;- HashMap .provided
 *   &lt;- AbstractBundleWiring (system bundle) &lt;- AtomicReference .wiring &lt;- AbstractBundleWiringSupport .wiringSupport
 *   &lt;- SystemBundleRevision &lt;- SystemBundleState &lt;- SystemBundleContext &lt;- URLHandlerSupportImpl ... (static)
 * </pre>
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8, BundleWiring.isInUse javadoc (osgi.core 8.0.0): "A bundle wiring is in use if it is the current wiring
 * or if some other in use bundle wiring is dependent upon it. Once a bundle wiring is no longer in use, it is
 * considered stale and is discarded by the framework." and 7.2.x/FrameworkWiring.refreshBundles: after a refresh the
 * removal-pending revisions are removed. A stale wiring (and its wires) must be discarded; here it is kept reachable
 * from the provider's wiring for the life of the provider (for the system bundle: forever).
 * https://docs.osgi.org/specification/osgi.core/8.0.0/framework.wiring.html
 *
 * <h2>Code location</h2>
 * jbosgi-resolver api/src/main/java/org/jboss/osgi/resolver/spi/AbstractWiring.java:56 ({@code provided} map),
 * :99-130 (addProvidedWire, the only writer; no method removes a wire); api/src/main/java/org/jboss/osgi/resolver/spi/AbstractBundleWiring.java:153-161
 * (R8 commit adds a filter "A wire stays with the provider after the requirer wiring is no longer in use" that hides
 * the stale wires from getProvidedWires but keeps them in memory). Pre-existing on WF41 (the filter is new, the
 * accumulation is old).
 *
 * <h2>Root cause</h2>
 * When a requirer revision is removed, its required wires are not removed from the provider wirings. The system bundle
 * (and every exporter that is never refreshed, e.g. the Felix/Aries capability bundles) accumulates one wire per
 * imported package per removed requirer, each holding the requirer revision, its bundle state, its module class loader
 * and all its classes.
 *
 * <h2>Fix location (added by the coordinator)</h2>
 * The framework-level reproduction resolver/19 (jbosgi-framework itest StaleRequirerWiresLeakIssueTestCase, same root
 * cause) shows that removing the wires in {@code AbstractEnvironment.uninstallResources} alone is NOT enough: with
 * update + refresh the old requirer wiring is already discarded when uninstallResources runs. The wires of every
 * discarded requirer wiring must be removed from the provider wirings in {@code AbstractWiringSupport.refresh()}, the
 * single place every discarded wiring passes (verified with a trial patch: the resolver suite stays green, all leak
 * variants collect 0 of N classes). AbstractBundleState.initialRevision retains one more revision per updated bundle
 * (resolver/20).
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=StaleProvidedWireLeakIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository. The two -Darq... options start the managed server with port offset 500, so the test cannot connect to another server on 9990 (arquillian.xml has allowConnectingToRunningServer=true); they can be left out when no other server runs.)
 * <h2>Observed</h2>
 * <pre>
 * java.lang.AssertionError: Expected the class of an uninstalled and refreshed bundle to be garbage collected, but it
 * is still reachable after 10 GCs (stale wires of the exporter keep the bundle revision and its class loader)
 * </pre>
 *
 * <h2>Expected</h2>
 * The class (and with it the bundle revision and class loader) of the uninstalled, refreshed bundle is collectable.
 *
 * <h2>Reference behaviour</h2>
 * Not checked against Equinox/Felix (the scenario needs the WildFly deployment path to observe the leak).
 *
 * <h2>Suggested fix (not applied)</h2>
 * When a revision's wiring is discarded (removal after uninstall/refresh, in jbosgi-framework where the revision is
 * removed from the environment), remove each of its required wires from the provider's wiring (add a
 * {@code removeProvidedWire} to XWiring/AbstractWiring), and drop the getProvidedWires filter afterwards.
 * Verified with a trial patch of jbosgi-resolver api only (AbstractEnvironment.uninstallResources removes the
 * resource's required wires from the provider wirings before {@code getWiringSupport().refresh()}; new
 * AbstractWiring.removeProvidedWire): this test passes and 20 redeploys of a bundle with an activator leave exactly one
 * copy of its Activator class (instead of 21).
 *
 * <h2>Regression vs WF41</h2>
 * no. The same test fails on origin/WF41 (umbrella 2.12.0.Final with resolver 7.6.0.Final on the same patched WildFly);
 * AbstractWiring never removed provided wires. The R8 filter only hides the stale wires in getProvidedWires.
 */
@RunWith(Arquillian.class)
public class StaleProvidedWireLeakIssueTestCase {

    static final String LEAKER = "stale-provided-wire-leaker";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "stale-provided-wire-tests");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(Bundle.class, FrameworkWiring.class, LogService.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Test
    public void testUninstalledBundleIsCollected() throws Exception {
        WeakReference<Class<?>> classRef = installLoadUninstall();

        // The Log Service keeps the last 100 entries, which reference the bundle; push them out
        ServiceReference<LogService> sref = context.getServiceReference(LogService.class);
        LogService log = context.getService(sref);
        for (int i = 0; i < 250; i++)
            log.log(LogService.LOG_DEBUG, "flush log history " + i);
        context.ungetService(sref);

        for (int i = 0; i < 10 && classRef.get() != null; i++) {
            System.gc();
            Thread.sleep(200);
        }
        Assert.assertNull("Expected the class of an uninstalled and refreshed bundle to be garbage collected, but it is still reachable after 10 GCs "
                + "(stale wires of the exporter keep the bundle revision and its class loader)", classRef.get());
    }

    private WeakReference<Class<?>> installLoadUninstall() throws Exception {
        Bundle bundle = context.installBundle(LEAKER, deployer.getDeployment(LEAKER));
        WeakReference<Class<?>> classRef;
        try {
            Class<?> clazz = loadClass(bundle, Payload.class.getName());
            Assert.assertNotSame("Precondition: the class comes from the installed bundle, not from the test bundle", Payload.class, clazz);
            classRef = new WeakReference<Class<?>>(clazz);
        } finally {
            bundle.uninstall();
        }
        final CountDownLatch latch = new CountDownLatch(1);
        context.getBundle(0).adapt(FrameworkWiring.class).refreshBundles(null, new FrameworkListener() {
            @Override
            public void frameworkEvent(FrameworkEvent event) {
                latch.countDown();
            }
        });
        Assert.assertTrue("Precondition: refreshBundles completes", latch.await(30, TimeUnit.SECONDS));
        Assert.assertEquals("Precondition: the bundle is uninstalled", Bundle.UNINSTALLED, bundle.getState());
        return classRef;
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

    @Deployment(name = LEAKER, managed = false, testable = false)
    public static JavaArchive getLeaker() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, LEAKER);
        archive.addClasses(Payload.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages("org.osgi.framework");
                return builder.openStream();
            }
        });
        return archive;
    }

    public static class Payload {
    }
}
