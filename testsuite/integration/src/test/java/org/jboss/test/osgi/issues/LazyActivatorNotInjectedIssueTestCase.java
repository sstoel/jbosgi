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

import javax.sql.DataSource;

import jakarta.annotation.Resource;

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

/**
 * The BundleActivator of a bundle with {@code Bundle-ActivationPolicy: lazy} that is deployed through management gets no
 * resource injection: the EE deployment processors load the activator class through the bundle class loader in the
 * POST_MODULE phase, which activates the lazy bundle right there, before the integration has created and attached the
 * injected activator instance; the framework then instantiates the activator itself and its {@code @Resource} fields
 * stay null. The lazy policy is lost as well (the bundle is ACTIVE when the deploy operation returns).
 *
 * <h2>Severity</h2>
 * major. Resource injection into bundle activators is a documented jbosgi feature (User Guide "Resource Injection",
 * ResourceInjectionTestCase); with the lazy policy, which bnd and PDE tooling set by default for many bundles, the
 * activator silently runs with null resources (NullPointerException in start or later), and the bundle is activated at
 * deploy time although it asked to be activated on first use.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem {@code org.jboss.as.osgi.deployment.BundleActivateProcessor} (creates the injected
 * activator component instance and attaches it as BUNDLE_ACTIVATOR_KEY only in the INSTALL phase) and
 * {@code org.jboss.as.osgi.service.BundleLifecycleIntegration.BundleLifecycleImpl.start}; jbosgi-framework
 * {@code UserBundleRevisionFactory}/{@code UserBundleState.initLazyActivation} and {@code HostBundleClassLoader.postDefine}
 * (lazy trigger armed at install, see ee/23).
 *
 * <h2>Found by</h2>
 * wildfly-ee-2 workstream (deep 2), target "activator with @Resource injection under lazy activation". Stack of the
 * activator start (ds == null):
 * <pre>
 * InjectedLazyActivator.start
 *  UserBundleState.startInternalNow(UserBundleState.java:615)
 *  BundleManagerPlugin.startBundle(BundleManagerPlugin.java:612)
 *  BundleLifecycleIntegration$BundleLifecycleImpl.start(BundleLifecycleIntegration.java:308)
 *  BundleManagerPlugin.startBundleLifecycle(BundleManagerPlugin.java:603)
 *  UserBundleState.activateLazily(UserBundleState.java:166)
 *  LazyActivationTracker.processActivationStack(LazyActivationTracker.java:90) / postDefineClass(58)
 *  HostBundleClassLoader.postDefine(HostBundleClassLoader.java:111)
 *  ... ModuleClassLoader.loadClass
 *  org.jboss.as.ee.utils.ClassLoadingUtils.loadClass(ClassLoadingUtils.java:26)
 *  org.jboss.as.ee.component.deployers.InterceptorAnnotationProcessor.processComponentConfig(InterceptorAnnotationProcessor.java:67)
 *  DeploymentUnitPhaseService.start (POST_MODULE)
 * </pre>
 * The same bundle without the lazy policy gets the DataSource injected (control in the test).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 4.4.6.2 Lazy Activation Policy: "A lazy activation policy indicates that the bundle, once started, must
 * not be activated until it receives the first request to load a class." (here the bundle is activated by the
 * container's own class loading before it was started); 4.4.5 the activator is the object the framework calls for this
 * bundle - jbosgi documents that activators of deployed bundles are EE components with resource injection (User Guide,
 * Resource Injection: "@Resource(name = "java:jboss/datasources/ExampleDS") DataSource ds" in a BundleActivator).
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/deployment/BundleActivateProcessor.java:120-126 (the injected instance
 * is created and attached in BundleActivateService.start, INSTALL phase, after POST_MODULE has already loaded the class);
 * jbosgi-framework core/src/main/java/org/jboss/osgi/framework/internal/UserBundleRevisionFactory.java:98
 * ({@code userBundle.initLazyActivation()} at install arms the trigger), HostBundleClassLoader.java:91-111 (activation on
 * the first class definition, also for a bundle that was never started).
 *
 * <h2>Root cause</h2>
 * The integration relies on the activator being started by its own Activate service, after it attached the injected
 * instance. For a lazy bundle, any class load arms an activation, and the EE subsystem loads the activator class while
 * processing the deployment, so the framework activates the bundle with a reflectively created activator first.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=LazyActivatorNotInjectedIssueTestCase \
 *     -Darq.container.jboss.configuration.managementPort=11090 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=1100
 * </pre>
 * (Prerequisites: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork in the local Maven
 * repository; on JDK 24+ the testsuite needs surefire.security.args without -Djava.security.manager, see
 * TestJvmSecurityManagerIssueTestCase.) Deterministic.
 *
 * <h2>Observed</h2>
 * <pre>
 * java.lang.AssertionError: Expected the DataSource to be injected into the activator of the lazy bundle when its
 *   activation is triggered, but the activator ran without it (bundle state right after the deploy: 32)
 *   expected:&lt;true&gt; but was:&lt;false&gt;
 * </pre>
 *
 * <h2>Expected</h2>
 * The activator is the injected EE component also for lazily activated bundles (ds != null), and the bundle is activated
 * by the first class load of a user, not by the deployment processing (state STARTING after the deploy).
 *
 * <h2>Reference behaviour</h2>
 * Without the lazy policy the same activator gets the DataSource (precondition). Felix 7.0.5 and Equinox 3.21 (with
 * osgi.compatibility.eagerStart.LazyActivation=false) do not activate a lazy bundle that was not started when a class is
 * loaded from it (see ee/23).
 *
 * <h2>Suggested fix (not applied)</h2>
 * Either arm the lazy trigger only when the bundle is started with START_ACTIVATION_POLICY (ee/23; the Activate service
 * then attaches the injected instance before the bundle can be activated), or create and attach the injected activator
 * instance before POST_MODULE processing can load bundle classes (or let BundleLifecycleImpl.start wait for / create the
 * component instance when the activation comes from a class load).
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing).
 *
 * <h2>Relation to ee/23</h2>
 * related: consequence of ee/23; counts with it as one root cause. Verified with a trial framework build that contains
 * only the ee/23 fix ({@code UserBundleState.activateLazily} returns unless the bundle is STARTING; issue folder
 * ee/23 trial-fix.diff, trial.log): on the patched server this test passes - the bundle is STARTING (8) after the deploy,
 * and the loadClass activates it with the injected activator (ds != null). Reverted afterwards.
 */
@RunWith(Arquillian.class)
public class LazyActivatorNotInjectedIssueTestCase {

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @ArquillianResource
    ManagementClient managementClient;

    public static class InjectedActivator implements BundleActivator {
        @Resource(lookup = "java:jboss/datasources/ExampleDS")
        DataSource ds;

        @Override
        public void start(BundleContext context) throws Exception {
            System.setProperty("lazy-injected." + context.getBundle().getSymbolicName(), String.valueOf(ds != null));
        }

        @Override
        public void stop(BundleContext context) throws Exception {
        }
    }

    /** a class of the lazy bundle whose loading triggers the activation */
    public static class Trigger {
    }

    @Deployment
    public static JavaArchive testDeployment() {
        final JavaArchive jar = ShrinkWrap.create(JavaArchive.class, "lazy-injected-tests");
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

    static JavaArchive activatorBundle(final String name, final boolean lazy) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name + ".jar");
        archive.addClasses(InjectedActivator.class, Trigger.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(name);
                builder.addBundleManifestVersion(2);
                builder.addBundleActivator(InjectedActivator.class);
                if (lazy)
                    builder.addBundleActivationPolicy("lazy");
                builder.addImportPackages(BundleActivator.class, DataSource.class, Resource.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = "eager", managed = false, testable = false)
    public static JavaArchive eagerBundle() {
        return activatorBundle("eager-injected", false);
    }

    @Deployment(name = "lazy", managed = false, testable = false)
    public static JavaArchive lazyBundle() {
        return activatorBundle("lazy-injected", true);
    }

    @Test
    public void testLazyActivatorInjected() throws Exception {
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        System.clearProperty("lazy-injected.eager-injected");
        server.deploy("eager-injected.jar", deployer.getDeployment("eager"));
        server.undeploy("eager-injected.jar");
        Assert.assertEquals("Precondition: without the lazy policy the activator gets the DataSource", "true", System.getProperty("lazy-injected.eager-injected"));

        System.clearProperty("lazy-injected.lazy-injected");
        server.deploy("lazy-injected.jar", deployer.getDeployment("lazy"));
        try {
            Bundle bundle = null;
            for (Bundle b : context.getBundles())
                if ("lazy-injected".equals(b.getSymbolicName()))
                    bundle = b;
            Assert.assertNotNull("Precondition: the lazy bundle is installed", bundle);
            int stateAfterDeploy = bundle.getState();
            // trigger the lazy activation (if it did not happen yet)
            bundle.loadClass("org.jboss.test.osgi.issues.LazyActivatorNotInjectedIssueTestCase$Trigger");
            Assert.assertEquals("Expected the DataSource to be injected into the activator of the lazy bundle when its activation is triggered, but the activator ran without it (bundle state right after the deploy: "
                    + stateAfterDeploy + ")", "true", System.getProperty("lazy-injected.lazy-injected"));
        } finally {
            server.undeploy("lazy-injected.jar");
            System.clearProperty("lazy-injected.lazy-injected");
            System.clearProperty("lazy-injected.eager-injected");
        }
    }
}
