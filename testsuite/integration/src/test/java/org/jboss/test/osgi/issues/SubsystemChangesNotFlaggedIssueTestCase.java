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
import org.jboss.dmr.ModelNode;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Configuration changes of the osgi subsystem are neither applied at runtime nor flagged: property add/remove,
 * capability add/remove and writing the activation attribute leave the server "running" without
 * operation-requires-reload / restart, although they only take effect after a reload; and the
 * read-resource-description flags contradict the behaviour (activation says restart-required=jvm, a property value
 * says no-services but its write sets reload-required).
 *
 * <h3>Severity</h3>
 * major. An administrator who adds a capability (e.g. to provide a module to OSGi bundles) or a framework property
 * through the CLI or the console gets "success", sees the change in the configuration, but the running server does not
 * use it and nothing says that a reload is needed. Tools that rely on the description flags (the console, CLI
 * batch scripts) mis-handle the attributes.
 *
 * <h3>Component</h3>
 * jbosgi (umbrella), wildfly/subsystem: org.jboss.as.osgi.parser.OSGiFrameworkPropertyAdd/Remove/Write,
 * OSGiCapabilityAdd/Remove, FrameworkPropertyResource (VALUE flags), OSGiRootResource (ACTIVATION flags),
 * org.jboss.as.osgi.management.ActivationAttributeHandler.
 *
 * <h3>Found by</h3>
 * services workstream round 4 (osgi subsystem management model), CLI probing of the patched server with port
 * offset 400, then this ModelControllerClient test.
 *
 * <h3>Spec reference</h3>
 * WildFly management conventions (org.jboss.as.controller AbstractAddStepHandler / AbstractWriteAttributeHandler
 * javadoc and the attribute flags): an operation that changes the persistent configuration and is not applied to the
 * runtime must put the process into reload-required (context.reloadRequired()) or restart-required
 * (context.restartRequired()); AttributeAccess.Flag.RESTART_NONE means "a modification ... does not require a restart
 * of services"; RESTART_JVM means "a modification ... requires a restart of the JVM" and the write handler must then
 * report it (operation-requires-restart). The subsystem's own description: activation "restart-required" => "jvm",
 * property value "restart-required" => "no-services".
 *
 * <h3>Code location</h3>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/parser/OSGiFrameworkPropertyAdd.java (performRuntime only calls
 * SubsystemState.setProperty), OSGiFrameworkPropertyRemove.java, OSGiCapabilityAdd.java, OSGiCapabilityRemove.java
 * (same pattern; SubsystemState.notifyObservers has no observers, so nothing reacts), OSGiFrameworkPropertyWrite.java
 * (applyUpdateToRuntime returns true = reload required, while VALUE has Flag.RESTART_NONE),
 * management/ActivationAttributeHandler.java (ModelOnlyWriteAttributeHandler for an attribute with Flag.RESTART_JVM).
 * Same code on WF41 (the r8 commits only touched StartLevelHandler and ActivateOperationHandler here).
 *
 * <h3>Root cause</h3>
 * The runtime part of the handlers updates the SubsystemState bean, which nobody observes; the handlers do not call
 * context.reloadRequired(). The attribute flags were not aligned with the handlers.
 *
 * <h3>Reproduction</h3>
 * JDK 25, own umbrella worktree (testsuite/pom.xml surefire.security.args replaced, see wildfly/03; arquillian.xml
 * allowConnectingToRunningServer=false, managementPort 9990+offset, -Djboss.socket.binding.port-offset=offset):
 * {@code $SP/bin/mvn25 -B -o -Dmaven.repo.local=<own repo> -Dmaven.repo.local.tail=$SP/m2-wf,$SP/m2,$HOME/.m2/repository
 * -pl testsuite/integration -Dtest=SubsystemChangesNotFlaggedIssueTestCase -Dsurefire.failIfNoSpecifiedTests=false clean install}
 *
 * <h3>Observed</h3>
 * <pre>
 * testPropertyAddRequiresReload: property add is not applied at runtime, it must report reload-required; response was {"outcome" => "success"}
 * testCapabilityAddRequiresReload: capability add is not applied at runtime (no bundle resource ...), it must report reload-required; ...
 * testCapabilityRemoveRequiresReload / testPropertyRemoveRequiresReload: (same, {"outcome" => "success"})
 * testActivationWriteRequiresReloadOrRestart: activation is only read at boot (restart-required=jvm in the description) ...
 * testPropertyValueDescriptionMatchesWrite: a write of a property value sets reload-required, the description must not say no-services
 * </pre>
 * (CLI: after each of these operations :read-attribute(name=server-state) is still "running".)
 *
 * <h3>Expected</h3>
 * Either the change is applied to the running framework or the response carries operation-requires-reload
 * (process-state reload-required); attribute flags that match what the handlers do (RESTART_ALL_SERVICES for
 * property value, and for activation either a handler that reports the restart or a reload flag).
 *
 * <h3>Reference behaviour</h3>
 * Other WildFly subsystems with boot-only settings use AbstractWriteAttributeHandler / ReloadRequiredWriteAttributeHandler
 * and ReloadRequiredAddStepHandler / ReloadRequiredRemoveStepHandler, which set reload-required.
 *
 * <h3>Suggested fix</h3>
 * Call {@code context.reloadRequired()} (with revertReloadRequired on rollback) in the add/remove handlers of property
 * and capability, give FrameworkPropertyResource.VALUE Flag.RESTART_ALL_SERVICES, and replace
 * ActivationAttributeHandler by a ReloadRequiredWriteAttributeHandler (and the flag by RESTART_ALL_SERVICES).
 *
 * Verified: with context.reloadRequired()/revertReloadRequired() in the four add/remove handlers, VALUE and ACTIVATION
 * flagged RESTART_ALL_SERVICES and ActivationAttributeHandler extending ReloadRequiredWriteAttributeHandler (patched
 * jbosgi-wildfly-service jar in the testsuite server) all six methods pass (21 s). Keeping RESTART_JVM on activation
 * with a reporting handler would put the server into restart-required, which a reload cannot clear.
 *
 * <h3>Regression vs WF41</h3>
 * no.
 */
@RunWith(Arquillian.class)
@RunAsClient
public class SubsystemChangesNotFlaggedIssueTestCase {

    private static final String TEST_PROPERTY = "org.jboss.test.osgi.issues.flagged";
    private static final String TEST_MODULE = "org.apache.commons.io";

    @ArquillianResource
    ManagementClient managementClient;

    @Deployment(testable = false)
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "subsystem-changes-not-flagged");
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

    @Test
    public void testPropertyAddRequiresReload() throws Exception {
        try {
            ModelNode response = execute(with(operation("add", "property", TEST_PROPERTY), "value", "a"));
            assertSuccess(response);
            Assert.assertTrue("property add is not applied at runtime, it must report reload-required; response was " + response, requiresReload(response));
        } finally {
            removeQuietly("property", TEST_PROPERTY);
            reloadIfRequired();
        }
    }

    @Test
    public void testPropertyRemoveRequiresReload() throws Exception {
        try {
            assertSuccess(execute(with(operation("add", "property", TEST_PROPERTY), "value", "a")));
            reloadIfRequired();
            ModelNode response = execute(operation("remove", "property", TEST_PROPERTY));
            assertSuccess(response);
            Assert.assertTrue("property remove is not applied at runtime, it must report reload-required; response was " + response, requiresReload(response));
        } finally {
            removeQuietly("property", TEST_PROPERTY);
            reloadIfRequired();
        }
    }

    @Test
    public void testCapabilityAddRequiresReload() throws Exception {
        try {
            ModelNode response = execute(operation("add", "capability", TEST_MODULE));
            assertSuccess(response);
            boolean applied = execute(operation("read-resource", "bundle", TEST_MODULE)).get("outcome").asString().equals("success");
            Assert.assertTrue("capability add is not applied at runtime (bundle resource present: " + applied + "), it must report reload-required; response was "
                    + response, applied || requiresReload(response));
        } finally {
            removeQuietly("capability", TEST_MODULE);
            reloadIfRequired();
        }
    }

    @Test
    public void testCapabilityRemoveRequiresReload() throws Exception {
        try {
            assertSuccess(execute(operation("add", "capability", TEST_MODULE)));
            reloadIfRequired();
            ModelNode response = execute(operation("remove", "capability", TEST_MODULE));
            assertSuccess(response);
            Assert.assertTrue("capability remove is not applied at runtime, it must report reload-required; response was " + response, requiresReload(response));
        } finally {
            removeQuietly("capability", TEST_MODULE);
            reloadIfRequired();
        }
    }

    @Test
    public void testActivationWriteRequiresReloadOrRestart() throws Exception {
        ModelNode read = execute(with(operation("read-attribute"), "name", "activation"));
        String original = read.get("result").asString();
        String other = "eager".equals(original) ? "lazy" : "eager";
        try {
            ModelNode response = execute(with(operation("write-attribute"), "name", "activation", "value", other));
            assertSuccess(response);
            Assert.assertTrue("activation is only read at boot (restart-required=jvm in the description), the write must report reload- or restart-required; response was "
                    + response, requiresReload(response) || requiresRestart(response));
        } finally {
            execute(with(operation("write-attribute"), "name", "activation", "value", original));
            reloadIfRequired();
        }
    }

    @Test
    public void testPropertyValueDescriptionMatchesWrite() throws Exception {
        ModelNode op = operation("read-resource-description");
        op.get("address").add("property", "*");
        ModelNode description = execute(op);
        String flag = description.get("result").get(0).get("result", "attributes", "value", "restart-required").asString();
        try {
            assertSuccess(execute(with(operation("add", "property", TEST_PROPERTY), "value", "a")));
            reloadIfRequired();
            ModelNode response = execute(with(operation("write-attribute", "property", TEST_PROPERTY), "name", "value", "value", "b"));
            assertSuccess(response);
            if (requiresReload(response))
                Assert.assertNotEquals("a write of a property value sets reload-required, the description must not say restart-required=no-services", "no-services", flag);
        } finally {
            removeQuietly("property", TEST_PROPERTY);
            reloadIfRequired();
        }
    }

    // ------------------------------------------------------------------ helpers

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

    private static boolean requiresReload(ModelNode response) {
        ModelNode headers = response.get("response-headers");
        return headers.get("operation-requires-reload").asBoolean(false) || "reload-required".equals(headers.get("process-state").asString(""));
    }

    private static boolean requiresRestart(ModelNode response) {
        ModelNode headers = response.get("response-headers");
        return headers.get("operation-requires-restart").asBoolean(false) || "restart-required".equals(headers.get("process-state").asString(""));
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
