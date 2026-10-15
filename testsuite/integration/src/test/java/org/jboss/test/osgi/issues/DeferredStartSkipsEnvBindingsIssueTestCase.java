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
import org.osgi.framework.BundleException;

/**
 * A bundle installed with BundleContext.installBundle (its server deployment gets a deferred module phase) cannot be
 * started when one of its EE components (here the BundleActivator) uses resource injection: Bundle.start() fails with "JBAS011969: Cannot activate
 * deferred module phase", because the first deferred activation already counts as a repeated activation and the
 * java:module/env bindings of the injection points are never created.
 *
 * <h2>Severity</h2>
 * major. Resource injection into bundle activators and EJBs in bundles is a documented feature (User Guide: "Resource
 * Injection ... ResourceInjectionActivator @Resource(name = "java:jboss/datasources/ExampleDS")"; EJB3 Integration). It
 * works only when the bundle is deployed through management and started by the deployment; the same bundle installed
 * through the OSGi API (provisioning, consoles, Repository/ResourceInstaller, installBundle from another bundle) or whose
 * deferred phase is activated later never starts, with a message that does not name the cause.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem {@code org.jboss.as.osgi.service.BundleLifecycleIntegration.activateDeferredPhase}
 * (increments {@code DEFERRED_ACTIVATION_COUNT} before the first activation) together with the RTR WildFly fork
 * ee {@code ModuleJndiBindingProcessor} ({@code DeploymentUtils.skipRepeatedActivation(deploymentUnit, 0)}) and
 * wildfly-core fork {@code DeploymentUtils.skipRepeatedActivation}.
 *
 * <h2>Found by</h2>
 * wildfly-ee workstream (deep 2), exploration of EE resources used by bundles (DataSource injected into a
 * BundleActivator): deployed through management the bundle starts (ds injected); installed with installBundle (with or
 * without .jar in the location, with or without importing jakarta.annotation) every start fails. Plain activators
 * without injection start in both cases. The same failure for a WAB installed with installBundle
 * whose servlet has {@code @Resource BundleContext} (JBAS011969, web context never registered, 404). MSC state captured while the start ran:
 * {@code jboss.deployment.unit."act-probe2-api.jar".component.BundleActivator.START ... missing=[service
 * jboss.naming.context.java.module.act-probe2-api.act-probe2-api.env."...ProbeActivator".ds]}.
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8, 4.4.5 Starting Bundles / Bundle.start(): the bundle must be started the same way regardless of how it was
 * installed (BundleContext.installBundle is the standard way, 4.4.3). Documented jbosgi behaviour: resource injection
 * into BundleActivators (User Guide, Resource Injection, ResourceInjectionTestCase) and into EJBs of bundles (EJB3
 * Integration, StatelessBeanTestCase). Jakarta EE Platform: @Resource injection points get a java:comp/env (here
 * java:module/env) binding that the component start depends on.
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/BundleLifecycleIntegration.java:423
 * ({@code depUnit.getAttachment(Attachments.DEFERRED_ACTIVATION_COUNT).incrementAndGet()} before
 * {@code phaseService.setMode(Mode.ACTIVE)}, so the first activation runs with count 1; the counter starts at 0 in
 * DeferredPhaseProcessor.java:60); RTR WildFly fork ee/src/main/java/org/jboss/as/ee/component/deployers/ModuleJndiBindingProcessor.java:86
 * ({@code if (moduleConfiguration == null || DeploymentUtils.skipRepeatedActivation(deploymentUnit, 0)) return;} - skips
 * every binding when the count is &gt; 0); wildfly-core fork server/.../DeploymentUtils.java:75-78.
 *
 * <h2>Root cause</h2>
 * Off-by-one between the counter and its check: the deferred activation increments the counter before the phase runs,
 * the JNDI binding processor treats any count &gt; 0 as "already activated before" and installs no java:module/env
 * bindings, so the component start service waits for bindings that are never installed and the deferred phase fails
 * with problems.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=DeferredStartSkipsEnvBindingsIssueTestCase \
 *     -Darq.container.jboss.configuration.managementPort=11090 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=1100
 * </pre>
 * (Prerequisites: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork in the local Maven
 * repository; on JDK 24+ the testsuite needs surefire.security.args without -Djava.security.manager, see
 * TestJvmSecurityManagerIssueTestCase.) Deterministic.
 *
 * <h2>Observed</h2>
 * <pre>
 * java.lang.AssertionError: Bundle.start() of an installed bundle whose activator has a @Resource DataSource must
 *   succeed, but it failed: org.osgi.framework.BundleException: JBAS011969: Cannot activate deferred module phase for:
 *   deferred-env-activator:0.0.0
 * server.log: WARN [org.jboss.as.osgi] JBAS011917: Deactivate deferred module phase for: deferred-env-activator:0.0.0
 *   (no cause logged; the component START service waits for the missing
 *   jboss.naming.context.java.module.&lt;app&gt;.&lt;module&gt;.env."...InjectedActivator".ds binding)
 * </pre>
 *
 * <h2>Expected</h2>
 * Bundle.start() succeeds and the activator gets the DataSource injected.
 *
 * <h2>Reference behaviour</h2>
 * The same activator bundle deployed through management starts and gets the DataSource injected (precondition checked
 * in testInjectedActivatorOfInstalledBundle).
 *
 * <h2>Suggested fix (not applied)</h2>
 * Increment DEFERRED_ACTIVATION_COUNT after the activation (or compare with 1 in ModuleJndiBindingProcessor), so that
 * only a second activation of the same deployment skips the bindings that the first one installed; better, remove the
 * bindings when the deferred phase is deactivated so that every activation installs them.
 * Trial (reverted): moving the incrementAndGet() in activateDeferredPhase behind the activation (after awaitStability)
 * makes this test pass (issue folder trial07-test.log). A second activation after a deactivation would then skip the
 * bindings again, so the bindings should be removed with the deactivated phase and recreated each time.
 *
 * <h2>Regression vs WF41</h2>
 * no (measured on a WF41 server built from origin/WF41 77f95be6 on the same patched WildFly 41.0.1, JDK 25: same
 * failure). The counter handling in BundleLifecycleIntegration is unchanged since WF41; the check lives in the WildFly
 * fork. The WF41 run used harness adaptations only, no change to this test or to the code under test: the jacc {@code
 * <policy>} removed from standalone-osgi.xml, as r8 42d6c470 does (without it org.wildfly.security.policy fails on
 * WildFly 41 and every deferred start fails with JBAS011969), and the r8 Arquillian JBOSGiApplicationArchiveProcessor
 * from 54b65e85 (in-container ManagementClient lookup); see the regression-2 workstream NOTES.md.
 */
@RunWith(Arquillian.class)
public class DeferredStartSkipsEnvBindingsIssueTestCase {

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
            System.setProperty("deferred-env.injected", String.valueOf(ds != null));
        }

        @Override
        public void stop(BundleContext context) throws Exception {
        }
    }

    @Deployment
    public static JavaArchive testDeployment() {
        final JavaArchive jar = ShrinkWrap.create(JavaArchive.class, "deferred-env-tests");
        jar.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(jar.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages("javax.naming");
                builder.addImportPackages(ManagementClient.class, ModelControllerClient.class, ServerDeploymentHelper.class);
                return builder.openStream();
            }
        });
        return jar;
    }

    @Test
    public void testInjectedActivatorOfInstalledBundle() throws Exception {
        // Control: the same bundle deployed through management starts and gets the DataSource
        System.clearProperty("deferred-env.injected");
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        server.deploy("deferred-env-control.jar", deployer.getDeployment("activator"));
        server.undeploy("deferred-env-control.jar");
        Assert.assertEquals("Precondition: deployed through management the activator gets the DataSource", "true", System.getProperty("deferred-env.injected"));

        System.clearProperty("deferred-env.injected");
        Bundle bundle = context.installBundle("deferred-env-activator.jar", deployer.getDeployment("activator"));
        try {
            try {
                bundle.start();
            } catch (BundleException ex) {
                Assert.fail("Bundle.start() of an installed bundle whose activator has a @Resource DataSource must succeed, but it failed: " + ex);
            }
            Assert.assertEquals("The DataSource must be injected into the activator", "true", System.getProperty("deferred-env.injected"));
        } finally {
            bundle.uninstall();
            System.clearProperty("deferred-env.injected");
        }
    }

    @Deployment(name = "activator", managed = false, testable = false)
    public static JavaArchive getActivatorBundle() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "deferred-env-activator.jar");
        archive.addClasses(InjectedActivator.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName("deferred-env-activator");
                builder.addBundleManifestVersion(2);
                builder.addBundleActivator(InjectedActivator.class);
                builder.addImportPackages(BundleActivator.class, DataSource.class);
                return builder.openStream();
            }
        });
        return archive;
    }

}
