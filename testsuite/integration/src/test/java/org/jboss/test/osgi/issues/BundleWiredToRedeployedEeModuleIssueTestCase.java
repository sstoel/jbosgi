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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.OperationBuilder;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper;
import org.jboss.dmr.ModelNode;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.ByteArrayAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A bundle that imports a package of a non-OSGi deployment (a library JAR or EJB-JAR, which the subsystem registers as
 * a module bundle) stays wired to the old deployment module after that deployment is redeployed or undeployed: the old
 * module is not reported by FrameworkWiring.getRemovalPendingBundles, refreshBundles(null) does not re-wire the bundle,
 * and the bundle keeps loading classes from the undeployed deployment's class loader.
 *
 * <h2>Severity</h2>
 * major. Bundles that call EJBs or use the API classes of a library deployment (the "bundle uses EE services" case)
 * break silently after any routine redeploy of that deployment: in the exploration a bundle importing the local business
 * interface of an EJB-JAR got the redeployed bean's proxy from java:global, but {@code Greeter.class.isInstance(proxy)}
 * was false (ClassCastException for real code) because the bundle still had the old deployment's Greeter class; the
 * standard OSGi recovery (refreshBundles(null), what consoles and provisioning agents call) did nothing. After an
 * undeploy the bundle stays RESOLVED and keeps the undeployed deployment's ModuleClassLoader (and all its classes)
 * reachable.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem, {@code org.jboss.as.osgi.service.ModuleRegistrationTracker}
 * ({@code unregisterInternal}), {@code org.jboss.as.osgi.deployment.ModuleRegisterProcessor.undeploy}; with
 * jbosgi-framework {@code FrameworkWiringImpl.getRemovalPendingBundles}.
 *
 * <h2>Found by</h2>
 * wildfly-ee workstream (deep 2), exploration "bundles using EE services": a bundle importing the business interface
 * package of an EJB-JAR deployment, then full-replace / undeploy of the EJB-JAR on the patched WildFly 41.0.1 server
 * (JDK 25). The test uses a plain library JAR deployment (no EJB) to keep it minimal; the behaviour is the same.
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 FrameworkWiring.getRemovalPendingBundles javadoc: "Returns the bundles that have non-current, in use
 * bundle wirings. This is typically the bundles which have been updated or uninstalled since the last call to
 * refreshBundles(Collection, FrameworkListener...)." FrameworkWiring.refreshBundles: "Compute the dependency closure of
 * the specified bundles. If no bundles are specified, compute the dependency closure of the removal pending bundles."
 * ... "Each bundle in the dependency closure that is in the RESOLVED state is unresolved and thus moved to the INSTALLED
 * state." FrameworkWiring.getDependencyClosure: "A graph of bundles is computed starting with the specified bundles.
 * The graph is expanded by adding any bundle that is either wired to a package that is currently exported by a bundle
 * in the graph or requires a bundle in the graph." Core R8 7.5.1 Refreshing: "The update of bundles will create new
 * Bundle Revisions while the existing Bundle Wirings remain wired to their previous Bundle Revisions. This stale wiring
 * must be cleaned up and the refreshBundles(Collection,FrameworkListener...) method achieves this."
 * https://docs.osgi.org/specification/osgi.core/8.0.0/framework.wiring.html#org.osgi.framework.wiring.FrameworkWiring
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/ModuleRegistrationTracker.java:149-153
 * ({@code unregisterInternal}: {@code env.uninstallResources(reg.brev)} only; the module bundle's revision and wiring
 * leave the environment although bundles are wired to it, called from ModuleRegisterProcessor.undeploy, line 81);
 * jbosgi-framework core/src/main/java/org/jboss/osgi/framework/internal/FrameworkWiringImpl.java:292-310
 * ({@code getRemovalPendingBundles} only iterates {@code bundleManager.getBundles(null)}, i.e. resources still in the
 * environment, so a removed module bundle is never found). Unchanged since WF41 (pre-existing code).
 *
 * <h2>Root cause</h2>
 * Module bundles registered for EE/library deployments are dropped from the environment on undeploy without treating
 * their in-use wiring as removal pending; nothing re-wires (refreshes) the importing bundles, and the framework no longer
 * knows the old provider, so refreshBundles(null) has nothing to refresh.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=BundleWiredToRedeployedEeModuleIssueTestCase \
 *     -Darq.container.jboss.configuration.managementPort=11090 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=1100
 * </pre>
 * (Prerequisites: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork in the local Maven
 * repository; on JDK 24+ the testsuite needs surefire.security.args without -Djava.security.manager, see
 * TestJvmSecurityManagerIssueTestCase.) Deterministic: deploy a plain JAR with class {@code eemodule.api.Api}
 * (generated in the test), install a bundle importing {@code eemodule.api} and load the class, then full-replace (or
 * undeploy) the JAR and call refreshBundles(null).
 *
 * <h2>Observed</h2>
 * <pre>
 * testRedeployOfImportedDeployment: java.lang.AssertionError: After a redeploy of the deployment ee-module.jar that
 *   the bundle imports from: [the old module bundle of ee-module.jar must be removal pending (in use, not current), but
 *   getRemovalPendingBundles() is [], after refreshBundles(null) the bundle must load eemodule.api.Api from the
 *   redeployed module, but it still gets the class of the old module (ModuleClassLoader for Module
 *   "deployment.ee-module.jar" from Service Module Loader@...)]
 * testUndeployOfImportedDeployment: java.lang.AssertionError: After an undeploy of the deployment ee-module.jar that
 *   the bundle imports from: [the old module bundle of ee-module.jar must be removal pending (in use, not current), but
 *   getRemovalPendingBundles() is [], after refreshBundles(null) the bundle must not be RESOLVED any more (no exporter of
 *   eemodule.api left), but it is RESOLVED and still loads eemodule.api.Api from the undeployed module]
 * exploration with an EJB-JAR (Greeter local interface): after full-replace, java:global/ee-ejb/GreeterBean!Greeter
 *   proxy isInstance(bundle's Greeter) = false; after refreshBundles(null) still false; only refreshBundles(client) helps
 * </pre>
 *
 * <h2>Expected</h2>
 * When a deployment whose module bundle is wired to bundles goes away, its module bundle stays available to them as a
 * removal pending bundle (or the importers are refreshed as part of the undeploy, like WildFly restarts deployments
 * with a module dependency on it); refreshBundles(null) re-wires the importers to the redeployed module, or unresolves
 * them when no exporter is left.
 *
 * <h2>Reference behaviour</h2>
 * Equinox/Felix: an uninstalled/updated exporter that is in use is listed by getRemovalPendingBundles and
 * refreshBundles(null) re-wires its importers (OSGi TCK FrameworkWiring tests). WildFly itself restarts a deployment
 * whose module dependency (deployment.ee-module.jar) is redeployed.
 *
 * <h2>Suggested fix (not applied)</h2>
 * In ModuleRegistrationTracker.unregisterInternal check whether the module bundle's wiring is in use; if so keep it as
 * a removal pending bundle (mark the revision uninstalled/not current but keep its bundle known to the BundleManager so
 * getRemovalPendingBundles and refreshBundles find it), or refresh the wired requirers before/after uninstalling the
 * resource. Let getRemovalPendingBundles also consider removed module revisions that are still in use.
 *
 * <h2>Regression vs WF41</h2>
 * no (measured on a WF41 server built from origin/WF41 77f95be6 on the same patched WildFly 41.0.1, JDK 25: same
 * failure). ModuleRegistrationTracker and getRemovalPendingBundles have the same logic on origin/WF41. The WF41 run
 * used harness adaptations only, no change to this test or to the code under test: the jacc {@code <policy>} removed
 * from standalone-osgi.xml, as r8 42d6c470 does (without it org.wildfly.security.policy fails on WildFly 41 and every
 * deferred start fails with JBAS011969), and the r8 Arquillian JBOSGiApplicationArchiveProcessor from 54b65e85
 * (in-container ManagementClient lookup); see the regression-2 workstream NOTES.md.
 */
@RunWith(Arquillian.class)
public class BundleWiredToRedeployedEeModuleIssueTestCase {

    static final String MODULE = "ee-module.jar";
    static final String CLIENT = "ee-module-client";
    static final String API_CLASS = "eemodule.api.Api";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @ArquillianResource
    ManagementClient managementClient;

    @Deployment
    public static JavaArchive testDeployment() {
        final JavaArchive jar = ShrinkWrap.create(JavaArchive.class, "bundle-wired-to-ee-module-tests");
        jar.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(jar.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(ManagementClient.class, ModelControllerClient.class, ClientConstants.class, ServerDeploymentHelper.class, ModelNode.class);
                builder.addImportPackages(FrameworkWiring.class);
                return builder.openStream();
            }
        });
        return jar;
    }

    @Test
    public void testRedeployOfImportedDeployment() throws Exception {
        new ServerDeploymentHelper(managementClient.getControllerClient()).deploy(MODULE, deployer.getDeployment(MODULE));
        Bundle client = context.installBundle(CLIENT, deployer.getDeployment(CLIENT));
        try {
            Class<?> before = client.loadClass(API_CLASS);
            Assert.assertTrue("Precondition: the class comes from the deployment module, but " + before.getClassLoader(),
                    String.valueOf(before.getClassLoader()).contains("deployment." + MODULE));

            fullReplace(MODULE);

            List<String> failures = new ArrayList<String>();
            Collection<Bundle> pending = getFrameworkWiring().getRemovalPendingBundles();
            if (pending.isEmpty())
                failures.add("the old module bundle of " + MODULE + " must be removal pending (in use, not current), but getRemovalPendingBundles() is " + pending);
            refreshAll();
            Class<?> after = client.loadClass(API_CLASS);
            if (after == before)
                failures.add("after refreshBundles(null) the bundle must load " + API_CLASS + " from the redeployed module, but it still gets the class of the old module ("
                        + before.getClassLoader() + "@" + Integer.toHexString(System.identityHashCode(before.getClassLoader())) + ")");
            Assert.assertTrue("After a redeploy of the deployment " + MODULE + " that the bundle imports from: " + failures, failures.isEmpty());
        } finally {
            client.uninstall();
            undeploy(MODULE);
        }
    }

    @Test
    public void testUndeployOfImportedDeployment() throws Exception {
        new ServerDeploymentHelper(managementClient.getControllerClient()).deploy(MODULE, deployer.getDeployment(MODULE));
        Bundle client = context.installBundle(CLIENT, deployer.getDeployment(CLIENT));
        try {
            client.loadClass(API_CLASS);
            Assert.assertEquals("Precondition: the client is resolved", Bundle.RESOLVED, client.getState());

            undeploy(MODULE);

            List<String> failures = new ArrayList<String>();
            Collection<Bundle> pending = getFrameworkWiring().getRemovalPendingBundles();
            if (pending.isEmpty())
                failures.add("the old module bundle of " + MODULE + " must be removal pending (in use, not current), but getRemovalPendingBundles() is " + pending);
            refreshAll();
            if (client.getState() == Bundle.RESOLVED) {
                String loaded;
                try {
                    loaded = "still loads " + API_CLASS + " from the undeployed module (" + client.loadClass(API_CLASS).getClassLoader() + ")";
                } catch (ClassNotFoundException ex) {
                    loaded = "cannot load " + API_CLASS;
                }
                failures.add("after refreshBundles(null) the bundle must not be RESOLVED any more (no exporter of eemodule.api left), but it is RESOLVED and " + loaded);
            }
            Assert.assertTrue("After an undeploy of the deployment " + MODULE + " that the bundle imports from: " + failures, failures.isEmpty());
        } finally {
            client.uninstall();
            undeploy(MODULE);
        }
    }

    private FrameworkWiring getFrameworkWiring() {
        return context.getBundle(0).adapt(FrameworkWiring.class);
    }

    private void refreshAll() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        getFrameworkWiring().refreshBundles(null, new FrameworkListener() {
            @Override
            public void frameworkEvent(FrameworkEvent event) {
                latch.countDown();
            }
        });
        Assert.assertTrue("Precondition: refreshBundles completes", latch.await(30, TimeUnit.SECONDS));
    }

    private void fullReplace(String name) throws Exception {
        ModelNode op = new ModelNode();
        op.get(ClientConstants.OP).set(ClientConstants.DEPLOYMENT_FULL_REPLACE_OPERATION);
        op.get(ClientConstants.NAME).set(name);
        op.get("enabled").set(true);
        op.get(ClientConstants.CONTENT).add().get(ClientConstants.INPUT_STREAM_INDEX).set(0);
        ModelNode result = managementClient.getControllerClient().execute(OperationBuilder.create(op).addInputStream(deployer.getDeployment(name)).build());
        Assert.assertEquals("Precondition: full-replace-deployment of " + name + " succeeds: " + result, ClientConstants.SUCCESS, result.get(ClientConstants.OUTCOME).asString());
    }

    private void undeploy(String name) {
        try {
            new ServerDeploymentHelper(managementClient.getControllerClient()).undeploy(name);
        } catch (Exception ex) {
            // ignore, already undeployed
        }
    }

    // A public class eemodule.api.Api (class file version 52, no members)
    static byte[] apiClass() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(0xCAFEBABE);
            out.writeShort(0);
            out.writeShort(52);
            out.writeShort(5);
            out.writeByte(7);
            out.writeShort(2);
            out.writeByte(1);
            out.writeUTF(API_CLASS.replace('.', '/'));
            out.writeByte(7);
            out.writeShort(4);
            out.writeByte(1);
            out.writeUTF("java/lang/Object");
            out.writeShort(0x21);
            out.writeShort(1);
            out.writeShort(3);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(0);
            out.close();
            return bos.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // A plain (non-OSGi) library deployment, the subsystem registers its module as a module bundle
    @Deployment(name = MODULE, managed = false, testable = false)
    public static JavaArchive getModule() {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, MODULE);
        archive.add(new ByteArrayAsset(apiClass()), API_CLASS.replace('.', '/') + ".class");
        return archive;
    }

    @Deployment(name = CLIENT, managed = false, testable = false)
    public static JavaArchive getClient() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, CLIENT + ".jar");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(CLIENT);
                builder.addBundleManifestVersion(2);
                builder.addImportPackages("eemodule.api");
                return builder.openStream();
            }
        });
        return archive;
    }
}
