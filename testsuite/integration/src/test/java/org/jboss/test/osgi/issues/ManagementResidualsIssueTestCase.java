/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013, Red Hat, Inc., and individual contributors
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

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper;
import org.jboss.dmr.ModelNode;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Management residuals of the OSGi subsystem: the rollback of a remove of a framework property or capability leaves
 * the server reload-required, a bundle that was undeployed is still a child of the subsystem for a minute, and the
 * capability of the Felix Web Console, which cannot run, can still be added.
 *
 * <h3>Severity</h3>
 * minor. Scripts see a reload-required server or a bundle resource that is gone, a configuration names a console that
 * does not start.
 *
 * <h3>Component</h3>
 * jbosgi/wildfly/subsystem: {@code OSGiFrameworkPropertyRemove}, {@code OSGiCapabilityRemove},
 * {@code OSGiRuntimeResource}, {@code OSGiCapabilityAdd}, {@code RetiredCapabilities}.
 *
 * <h3>Found by</h3>
 * R8 fix review, residuals of services/21 (umb-a-M01), services/27 (umb-b-02) and security/02.
 *
 * <h3>Spec reference</h3>
 * WildFly management: a rolled back operation leaves no reload-required (OperationContext.revertReloadRequired).
 *
 * <h3>Code location</h3>
 * OSGiFrameworkPropertyRemove.java and OSGiCapabilityRemove.java (performRuntime completed the step with a rollback
 * handler that the remove handler replaced), OSGiRuntimeResource.java:50,213-245 (60 s retention).
 *
 * <h3>Root cause</h3>
 * The rollback handler of the remove handlers was never called. The listed bundles were kept by wall clock for every
 * operation. The Web Console jar was shipped and its capability accepted.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi/testsuite/integration
 * mvn test -Dtest=ManagementResidualsIssueTestCase
 * </pre>
 *
 * <h3>Expected</h3>
 * The server is running after the rolled back removes, the bundle child is gone right after the undeploy, the web
 * console capability cannot be added.
 *
 * <h3>Regression vs WF41</h3>
 * no.
 */
@RunWith(Arquillian.class)
@RunAsClient
public class ManagementResidualsIssueTestCase {

    private static final String TEST_PROPERTY = "org.jboss.test.osgi.issues.rollback";
    private static final String TEST_MODULE = "org.apache.commons.io";
    private static final String LISTED_BUNDLE = "residual-listed-bundle.jar";

    @ArquillianResource
    ManagementClient managementClient;

    @Deployment(testable = false)
    public static JavaArchive deployment() {
        return bundle("management-residuals");
    }

    @Test
    public void testRolledBackPropertyRemoveLeavesNoReload() throws Exception {
        reloadIfRequired();
        try {
            assertSuccess(execute(with(operation("add", "property", TEST_PROPERTY), "value", "a")));
            reloadIfRequired();
            ModelNode response = execute(compositeWithFailure(operation("remove", "property", TEST_PROPERTY)));
            Assert.assertEquals("Precondition: the composite is rolled back: " + response, "failed", response.get("outcome").asString());
            Assert.assertEquals("The rolled back remove of the property leaves the server running", "running", serverState());
            assertSuccess(execute(operation("read-resource", "property", TEST_PROPERTY)));
        } finally {
            removeQuietly("property", TEST_PROPERTY);
            reloadIfRequired();
        }
    }

    @Test
    public void testRolledBackCapabilityRemoveLeavesNoReload() throws Exception {
        reloadIfRequired();
        try {
            assertSuccess(execute(operation("add", "capability", TEST_MODULE)));
            reloadIfRequired();
            ModelNode response = execute(compositeWithFailure(operation("remove", "capability", TEST_MODULE)));
            Assert.assertEquals("Precondition: the composite is rolled back: " + response, "failed", response.get("outcome").asString());
            Assert.assertEquals("The rolled back remove of the capability leaves the server running", "running", serverState());
        } finally {
            removeQuietly("capability", TEST_MODULE);
            reloadIfRequired();
        }
    }

    @Test
    public void testUndeployedBundleIsNotAChild() throws Exception {
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        server.deploy(LISTED_BUNDLE, bundle(LISTED_BUNDLE).as(ZipExporter.class).exportAsInputStream());
        boolean undeployed = false;
        try {
            ModelNode read = with(operation("read-resource"), "recursive", "true", "include-runtime", "true");
            ModelNode response = execute(read);
            assertSuccess(response);
            Assert.assertTrue("Precondition: the bundle is listed: " + response.get("result", "bundle").keys(), response.get("result", "bundle").has(LISTED_BUNDLE));
            server.undeploy(LISTED_BUNDLE);
            undeployed = true;
            ModelNode child = execute(operation("read-resource", "bundle", LISTED_BUNDLE));
            Assert.assertEquals("The bundle of the undeployed deployment is not a child of the subsystem any more: " + child, "failed", child.get("outcome").asString());
        } finally {
            if (!undeployed)
                server.undeploy(LISTED_BUNDLE);
        }
    }

    @Test
    public void testWebConsoleCapabilityRefused() throws Exception {
        ModelNode response = execute(operation("add", "capability", "org.apache.felix.webconsole"));
        try {
            Assert.assertEquals("The capability of the Felix Web Console is refused: " + response, "failed", response.get("outcome").asString());
            Assert.assertTrue("The failure names the retired capability: " + response, response.get("failure-description").asString().contains("JBAS011981"));
        } finally {
            if ("success".equals(response.get("outcome").asString()))
                removeQuietly("capability", "org.apache.felix.webconsole");
            reloadIfRequired();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static JavaArchive bundle(final String name) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
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

    // The operation and one that fails, in one composite: the operation is rolled back
    private static ModelNode compositeWithFailure(ModelNode op) {
        ModelNode composite = new ModelNode();
        composite.get("operation").set("composite");
        composite.get("address").setEmptyList();
        composite.get("steps").add(op);
        ModelNode failing = new ModelNode();
        failing.get("operation").set("read-resource");
        failing.get("address").add("subsystem", "osgi").add("property", "org.jboss.test.osgi.issues.does-not-exist");
        composite.get("steps").add(failing);
        return composite;
    }

    private static ModelNode operation(String name, String... addressPairs) {
        ModelNode op = new ModelNode();
        op.get("operation").set(name);
        ModelNode address = op.get("address");
        address.add("subsystem", "osgi");
        for (int i = 0; i + 1 < addressPairs.length; i += 2)
            address.add(addressPairs[i], addressPairs[i + 1]);
        return op;
    }

    private static ModelNode with(ModelNode op, String... keyValues) {
        for (int i = 0; i + 1 < keyValues.length; i += 2)
            op.get(keyValues[i]).set(keyValues[i + 1]);
        return op;
    }

    private ModelNode execute(ModelNode op) throws Exception {
        return managementClient.getControllerClient().execute(op);
    }

    private static void assertSuccess(ModelNode response) {
        Assert.assertEquals("Precondition: operation succeeds: " + response, "success", response.get("outcome").asString());
    }

    private void removeQuietly(String type, String name) {
        try {
            execute(operation("remove", type, name));
        } catch (Exception ex) {
            // not there
        }
    }

    private String serverState() {
        try {
            ModelNode op = new ModelNode();
            op.get("operation").set("read-attribute");
            op.get("address").setEmptyList();
            op.get("name").set("server-state");
            ModelNode response = execute(op);
            return response.get("outcome").asString().equals("success") ? response.get("result").asString() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private void reloadIfRequired() throws Exception {
        String state = serverState();
        if ("running".equals(state))
            return;
        Assert.assertNotEquals("A restart-required server cannot be recovered by this test (it needs a JVM restart)", "restart-required", state);
        ModelNode reload = new ModelNode();
        reload.get("operation").set("reload");
        reload.get("address").setEmptyList();
        try {
            execute(reload);
        } catch (Exception ex) {
            // the connection may drop while reloading
        }
        long end = System.currentTimeMillis() + 120000;
        Thread.sleep(2000);
        while (System.currentTimeMillis() < end && "running".equals(serverState()) == false)
            Thread.sleep(500);
        Assert.assertEquals("Server running after reload", "running", serverState());
    }
}
