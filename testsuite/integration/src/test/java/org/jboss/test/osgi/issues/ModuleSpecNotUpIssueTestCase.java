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
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StopContext;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

/**
 * The first class load from a deployed bundle right after it resolves fails intermittently with
 * "JBOSGI011217: Cannot load module" because the module spec service is not UP yet.
 *
 * <h2>Severity</h2>
 * major. Intermittent in practice and not JDK specific: without a trigger 15 of 60 install/loadClass cycles failed on
 * JDK 25 (the coordinator's independent re-run: 1-4 of 60 in 4 of 6 runs), the testsuite's own
 * {@code org.jboss.test.osgi.ds.ServiceListenerTestCase} fails with it in about one of eight runs on JDK 25 and on
 * JDK 21. Any bundle whose first class load (Bundle.loadClass, DynamicImport-Package, a Require-Bundle/Import-Package
 * wire followed by a class load, an Activator) triggers the resolve of a deployed bundle can hit it.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem, {@code org.jboss.as.osgi.service.ModuleLoaderIntegration}
 * (FrameworkModuleLoaderImpl.addModuleSpec / DelegatingModuleLoader.preloadModule), in combination with
 * wildfly-core {@code org.jboss.as.server.moduleservice.ServiceModuleLoader.findModule}.
 *
 * <h2>Found by</h2>
 * wildfly workstream: the testsuite run on JDK 25 had one error in testsuite/functional
 * (ServiceListenerTestCase.testServiceAccess, "JBOSGI011217: Cannot load module: deployment.bundleSL"). Re-runs:
 * 1 of 8 runs failed on JDK 25 and 1 of 8 on JDK 21. Root-caused with TRACE logging of org.jboss.as.osgi and
 * org.jboss.as.server.moduleservice in a server that the test connected to.
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8, Bundle.loadClass javadoc (org.osgi.framework.Bundle, osgi.core 8.0.0): "If this bundle's state is
 * INSTALLED, this method must attempt to resolve this bundle before attempting to load the class." and the only
 * failures it allows are "ClassNotFoundException If no such class can be found ..." and "IllegalStateException If this
 * bundle has been uninstalled." The bundle resolves, is not uninstalled and contains the class, so the class must be
 * returned; here an IllegalStateException is thrown instead.
 * https://docs.osgi.org/specification/osgi.core/8.0.0/framework.api.html#org.osgi.framework.Bundle.loadClass-String-
 *
 * <h2>Code location</h2>
 * <ul>
 * <li>wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/ModuleLoaderIntegration.java:247-267
 * (addModuleSpec installs the jboss.module.spec.service."deployment.X" service with {@code sb.install()} and returns
 * immediately; the service is started asynchronously by an MSC thread). Unchanged in this respect since the WF30 port
 * (e4d06ba7), which replaced ValueService with ModuleDefinitionService; also present on origin/WF41.</li>
 * <li>wildfly-core 33.0.1.Final(-osgi) server/src/main/java/org/jboss/as/server/moduleservice/ServiceModuleLoader.java:132-152
 * (findModule returns the spec only if the controller is already UP or START_FAILED; otherwise it adds a listener,
 * counts its own latch down and returns {@code null} without waiting).</li>
 * <li>jbosgi-framework core/src/main/java/org/jboss/osgi/framework/internal/BundleStateRevision.java:177-187
 * (getModuleClassLoader turns the ModuleNotFoundException into IllegalStateException JBOSGI011217).</li>
 * </ul>
 *
 * <h2>Root cause</h2>
 * When the framework resolves a deployed bundle, ModuleManagerImpl.addModule calls ModuleLoaderIntegration.addModuleSpec,
 * which only installs the module spec service. The class load that follows in the same thread calls
 * ModuleLoader.preloadModule(identifier, ServiceModuleLoader), and ServiceModuleLoader.findModule does not wait for a
 * spec service that is still STARTING: it returns null, so the module is "not found". Server log of a failing run
 * (TRACE):
 * <pre>
 * 20:08:24,646 TRACE [org.jboss.as.osgi] (pool-13-thread-1) Add module spec to loader: deployment.bundleSL
 * 20:08:24,646 DEBUG [org.jboss.as.osgi] (pool-13-thread-1) Cannot obtain module for: deployment.bundleSL
 * 20:08:24,647 TRACE [org.jboss.as.server.moduleservice] (MSC service thread 1-3) serviceStopping: Controller for service jboss.module.spec.service."deployment.bundleSL"
 * </pre>
 * In a passing run the "serviceStarted" of the spec service comes before the module is requested. The failed load is
 * not retried: the bundle stays RESOLVED, but the class load and every DynamicImport through it failed.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=ModuleSpecNotUpIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository. The two -Darq... options start the managed server with port offset 500, so the test cannot connect to another server on 9990 (arquillian.xml has allowConnectingToRunningServer=true); they can be left out when no other server runs.)
 * <h2>Observed</h2>
 * <pre>
 * java.lang.AssertionError: Expected Bundle.loadClass to succeed in every one of 3 installs of a deployed bundle while the
 * MSC threads are busy, but it failed 3 times, first: iteration 0: java.lang.IllegalStateException: JBOSGI011217:
 * Cannot load module: deployment.module-spec-not-up-payload expected:&lt;0&gt; but was:&lt;3&gt;
 * </pre>
 *
 * <h2>Expected</h2>
 * Bundle.loadClass returns the class of the resolved bundle in every iteration.
 *
 * <h2>Reference behaviour</h2>
 * Not applicable (WildFly integration specific). Equinox/Felix define the class loader synchronously on resolve.
 *
 * <h2>Suggested fix (not applied)</h2>
 * Make addModuleSpec wait until the module spec service is UP before it returns, e.g.
 * <pre>
 * ServiceController&lt;?&gt; controller = sb.install();
 * StabilityMonitor monitor = new StabilityMonitor();
 * monitor.addController(controller);
 * try { monitor.awaitStability(30, TimeUnit.SECONDS); } finally { monitor.removeController(controller); }
 * </pre>
 * (verified: with this change in a local copy the test passes; it waits for the blockers to be released). Alternatively let the DelegatingModuleLoader
 * wait for the spec service before delegating to the ServiceModuleLoader.
 *
 * <h2>Regression vs WF41</h2>
 * no. The same test fails on origin/WF41 (3 of 3; the earlier trigger-less version: 8 of 60), umbrella 2.12.0.Final
 * with the x.6.0.Final support artifacts on the same patched WildFly 41.0.1.Final.
 */
@RunWith(Arquillian.class)
public class ModuleSpecNotUpIssueTestCase {

    static final int ITERATIONS = 3;

    static final String PAYLOAD_BUNDLE = "module-spec-not-up-payload";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "module-spec-not-up-tests");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(Bundle.class, ServiceContainer.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Test
    public void testLoadClassRightAfterInstall() throws Exception {
        ServiceContainer container = serviceContainer();
        int failures = 0;
        String firstFailure = null;
        for (int i = 0; i < ITERATIONS; i++) {
            Bundle bundle = context.installBundle(PAYLOAD_BUNDLE, deployer.getDeployment(PAYLOAD_BUNDLE));
            // Keep the MSC threads busy for a moment, so that the module spec service that the resolve installs
            // cannot be started before the module is loaded. This only delays the asynchronous start, it does not
            // change any product code.
            CountDownLatch release = new CountDownLatch(1);
            List<ServiceController<?>> blockers = blockMscThreads(container, release, "iteration" + i);
            Timer timer = new Timer(true);
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    release.countDown();
                }
            }, 2000);
            try {
                bundle.loadClass(Payload.class.getName());
            } catch (Throwable th) {
                failures++;
                if (firstFailure == null)
                    firstFailure = "iteration " + i + ": " + th;
            } finally {
                release.countDown();
                timer.cancel();
                for (ServiceController<?> blocker : blockers)
                    blocker.setMode(ServiceController.Mode.REMOVE);
                bundle.uninstall();
            }
        }
        Assert.assertEquals("Expected Bundle.loadClass to succeed in every one of " + ITERATIONS
                + " installs of a deployed bundle while the MSC threads are busy, but it failed " + failures + " times, first: " + firstFailure, 0, failures);
    }

    private ServiceContainer serviceContainer() throws Exception {
        // BundleManager is not an exported package, get the container through the system bundle adaptor
        Bundle sysbundle = context.getBundle(0);
        Class<?> bmClass = sysbundle.loadClass("org.jboss.osgi.framework.spi.BundleManager");
        Object bundleManager = sysbundle.adapt(bmClass);
        return (ServiceContainer) bmClass.getMethod("getServiceContainer").invoke(bundleManager);
    }

    @SuppressWarnings("deprecation")
    private static List<ServiceController<?>> blockMscThreads(ServiceContainer container, final CountDownLatch release, String suffix) throws Exception {
        int count = 4 * Runtime.getRuntime().availableProcessors() + 16;
        final CountDownLatch started = new CountDownLatch(1);
        List<ServiceController<?>> controllers = new ArrayList<ServiceController<?>>();
        for (int i = 0; i < count; i++) {
            ServiceName name = ServiceName.of("module-spec-not-up-test", "blocker", suffix, String.valueOf(i));
            controllers.add(container.addService(name, new Service<Void>() {
                @Override
                public void start(StartContext ctx) {
                    started.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }

                @Override
                public void stop(StopContext ctx) {
                }

                @Override
                public Void getValue() {
                    return null;
                }
            }).install());
        }
        Assert.assertTrue("Precondition: the MSC threads picked up the blocker services", started.await(10, TimeUnit.SECONDS));
        return controllers;
    }

    @Deployment(name = PAYLOAD_BUNDLE, managed = false, testable = false)
    public static JavaArchive payloadBundle() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, PAYLOAD_BUNDLE);
        archive.addClasses(Payload.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                return builder.openStream();
            }
        });
        return archive;
    }

    public static class Payload {
    }
}
