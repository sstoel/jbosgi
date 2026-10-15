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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper;
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
import org.osgi.framework.BundleException;

/**
 * In WildFly every deployed bundle reuses one BundleActivator object for all its starts: after Bundle.stop() has called
 * BundleActivator.stop, the next Bundle.start() calls start on the same, already stopped object instead of a new
 * instance. Activators that release resources in stop (the usual pattern) fail on restart, e.g. an activator with an
 * ExecutorService field shut down in stop gets RejectedExecutionException in the next start.
 *
 * <h2>Severity</h2>
 * major. Affects every bundle with a Bundle-Activator that is deployed to the server (management, deployment scanner,
 * BundleContext.installBundle) and is restarted through the OSGi API (consoles, provisioning, start-level changes,
 * refresh of an exporter): state that the activator initialises in field initializers or its constructor is not
 * re-initialised; resources closed in stop stay closed. The same bundle works on Equinox/Felix and in a standalone jbosgi
 * framework.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem: {@code org.jboss.as.osgi.deployment.OSGiComponentParseProcessor} (the
 * Bundle-Activator class becomes an EE component, for resource injection), {@code BundleActivateProcessor.BundleActivateService}
 * (creates one component instance and attaches it as {@code IntegrationConstants.BUNDLE_ACTIVATOR_KEY}); jbosgi-framework
 * {@code UserBundleState.startInternal} (uses the attached instance for every start).
 *
 * <h2>Found by</h2>
 * wildfly-ee workstream (deep 2), exploration of resource injection into bundle activators (EE services used by
 * bundles) with stop/start cycles on the patched WildFly 41.0.1 server (JDK 25): the identity hash code of the activator
 * object was the same for every start, also for activators without any EE annotation.
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8, 4.4.6 Activation: "The Framework must instantiate a new object of this class and cast it to a
 * BundleActivator instance. It must then call the BundleActivator.start method to start the bundle." 4.4.8 Stopping
 * Bundles: "The Framework must guarantee that if a BundleActivator.start method has executed successfully, that same
 * BundleActivator object must be called with its BundleActivator.stop method when the bundle is deactivated. After
 * calling the stop method, that particular BundleActivator object must never be used again."
 * https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html#framework.lifecycle.activation
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/deployment/BundleActivateProcessor.java:124-127
 * ({@code activatorComponent.createInstance()} once, {@code deployment.putAttachment(BUNDLE_ACTIVATOR_KEY, instance)},
 * only if no instance is attached yet; the attachment is never removed); jbosgi-framework
 * core/src/main/java/org/jboss/osgi/framework/internal/UserBundleState.java:605-613 ({@code bundleActivator =
 * getDeployment().getAttachment(BUNDLE_ACTIVATOR_KEY)}, a new instance only when none is attached).
 * OSGiComponentParseProcessor.java (every bundle with a Bundle-Activator gets an EE component). Pre-existing (same code
 * on WF41).
 *
 * <h2>Root cause</h2>
 * The EE component instance that carries the resource injections is created once per deployment and handed to the
 * framework as the activator for all starts, instead of creating (and injecting) a new component instance for each start
 * and dropping it after stop.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=BundleActivatorReusedAfterStopIssueTestCase \
 *     -Darq.container.jboss.configuration.managementPort=11090 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=1100
 * </pre>
 * (Prerequisites: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork in the local Maven
 * repository; on JDK 24+ the testsuite needs surefire.security.args without -Djava.security.manager, see
 * TestJvmSecurityManagerIssueTestCase.) Deterministic.
 *
 * <h2>Observed</h2>
 * <pre>
 * testNewActivatorForEveryStart: java.lang.AssertionError: Bundle.start() after Bundle.stop() must use a new
 *   BundleActivator object (4.4.6/4.4.8), but it called start on the stopped object again: starts=[start@5ab1c3d2
 *   start@5ab1c3d2(afterStop)]
 * testActivatorWithResourcesRestarts: java.lang.AssertionError: Restarting a bundle whose activator shuts down its
 *   executor in stop must work (a new activator object), but start failed: org.osgi.framework.BundleException:
 *   JBOSGI011254: Cannot start bundle: activator-executor:0.0.0 caused by
 *   java.util.concurrent.RejectedExecutionException: Task java.util.concurrent.FutureTask@... rejected from
 *   java.util.concurrent.ThreadPoolExecutor@...[Terminated, ...]
 * </pre>
 *
 * <h2>Expected</h2>
 * Every Bundle.start() uses a newly instantiated (and, for EE injection, newly injected) activator; the restart of the
 * executor activator succeeds.
 *
 * <h2>Reference behaviour</h2>
 * Equinox and Felix instantiate the Bundle-Activator class on every start (Equinox BundleContextImpl.loadBundleActivator,
 * Felix Felix.createBundleActivator); so does the standalone jbosgi framework (no BUNDLE_ACTIVATOR_KEY attached).
 *
 * <h2>Suggested fix (not applied)</h2>
 * Do not attach a component instance to the Deployment; instead give the framework a factory (e.g. an attachment
 * {@code Supplier<BundleActivator>}) that calls {@code activatorComponent.createInstance()} for each start, and destroy
 * the ComponentInstance after BundleActivator.stop (pre-destroy callbacks, release of injected references).
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing: the same BUNDLE_ACTIVATOR_KEY handling exists on origin/WF41).
 */
@RunWith(Arquillian.class)
public class BundleActivatorReusedAfterStopIssueTestCase {

    static final String STARTS = "activator-reuse.starts";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @ArquillianResource
    ManagementClient managementClient;

    // Records the identity of the object for every start
    public static class RecordingActivator implements BundleActivator {
        private boolean stopped;

        @Override
        public void start(BundleContext context) throws Exception {
            String entry = "start@" + Integer.toHexString(System.identityHashCode(this)) + (stopped ? "(afterStop)" : "");
            System.setProperty(STARTS, (System.getProperty(STARTS, "") + " " + entry).trim());
        }

        @Override
        public void stop(BundleContext context) throws Exception {
            stopped = true;
        }
    }

    // The common pattern: a resource created with the activator and released in stop
    public static class ExecutorActivator implements BundleActivator {
        private final ExecutorService executor = Executors.newSingleThreadExecutor();

        @Override
        public void start(BundleContext context) throws Exception {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                }
            }).get();
        }

        @Override
        public void stop(BundleContext context) throws Exception {
            executor.shutdownNow();
        }
    }

    @Deployment
    public static JavaArchive testDeployment() {
        final JavaArchive jar = ShrinkWrap.create(JavaArchive.class, "activator-reuse-tests");
        jar.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(jar.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(ManagementClient.class, ModelControllerClient.class, ServerDeploymentHelper.class);
                return builder.openStream();
            }
        });
        return jar;
    }

    @Test
    public void testNewActivatorForEveryStart() throws Exception {
        System.clearProperty(STARTS);
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        server.deploy("activator-recording.jar", deployer.getDeployment("recording"));
        try {
            Bundle bundle = getBundle("activator-recording");
            Assert.assertEquals("Precondition: the deployed bundle is ACTIVE", Bundle.ACTIVE, bundle.getState());
            bundle.stop();
            bundle.start();
            String starts = System.getProperty(STARTS);
            String[] entries = starts.split(" ");
            Assert.assertEquals("Precondition: two starts recorded: " + starts, 2, entries.length);
            Assert.assertFalse("Bundle.start() after Bundle.stop() must use a new BundleActivator object (4.4.6/4.4.8), but it called start on the stopped object again: starts=["
                    + starts + "]", entries[1].endsWith("(afterStop)"));
        } finally {
            server.undeploy("activator-recording.jar");
            System.clearProperty(STARTS);
        }
    }

    @Test
    public void testActivatorWithResourcesRestarts() throws Exception {
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        server.deploy("activator-executor.jar", deployer.getDeployment("executor"));
        try {
            Bundle bundle = getBundle("activator-executor");
            Assert.assertEquals("Precondition: the deployed bundle is ACTIVE", Bundle.ACTIVE, bundle.getState());
            bundle.stop();
            try {
                bundle.start();
            } catch (BundleException ex) {
                Throwable cause = ex;
                while (cause.getCause() != null)
                    cause = cause.getCause();
                Assert.fail("Restarting a bundle whose activator shuts down its executor in stop must work (a new activator object), but start failed: " + ex + " caused by " + cause);
            }
        } finally {
            server.undeploy("activator-executor.jar");
        }
    }

    private Bundle getBundle(String symbolicName) {
        for (Bundle bundle : context.getBundles()) {
            if (symbolicName.equals(bundle.getSymbolicName()))
                return bundle;
        }
        return null;
    }

    private static JavaArchive bundle(String name, final String symbolicName, final Class<?> activator) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.addClasses(activator);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(symbolicName);
                builder.addBundleManifestVersion(2);
                builder.addBundleActivator(activator);
                builder.addImportPackages(BundleActivator.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = "recording", managed = false, testable = false)
    public static JavaArchive getRecording() {
        return bundle("activator-recording.jar", "activator-recording", RecordingActivator.class);
    }

    @Deployment(name = "executor", managed = false, testable = false)
    public static JavaArchive getExecutor() {
        JavaArchive archive = bundle("activator-executor.jar", "activator-executor", ExecutorActivator.class);
        archive.addClass(ExecutorActivator.class.getName() + "$1");
        return archive;
    }
}
