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
 * /subsystem=osgi/capability=X:add validates neither X nor its startlevel: a module that does not exist is accepted
 * (and on the next reload/restart the whole osgi subsystem fails to boot, so no bundle is available any more), and a
 * startlevel of 0 or below is accepted (the capability bundle then reports start level 0, the system bundle's level).
 *
 * <h3>Severity</h3>
 * minor. A typo in a capability name passes the management operation and silently breaks all OSGi deployments at the
 * next reload ("JBAS011955: Failed to process initial capability: ...", WFLYCTL0013 for the osgi subsystem add);
 * invalid start levels are accepted although the framework start level attribute of the same subsystem enforces >= 1.
 *
 * <h3>Component</h3>
 * jbosgi (umbrella), wildfly/subsystem: org.jboss.as.osgi.parser.FrameworkCapabilityResource (STARTLEVEL without
 * validator), OSGiCapabilityAdd (no check of the module identifier).
 *
 * <h3>Found by</h3>
 * services workstream round 4 (osgi subsystem management model), CLI probing on the patched server (offset 400):
 * {@code /subsystem=osgi/capability=does.not.exist.module:add} -> success; {@code :reload} -> osgi subsystem boot
 * fails, {@code /subsystem=osgi/bundle=org.apache.felix.scr} not found; {@code capability=org.apache.commons.io:add(startlevel=-5)}
 * -> success, after reload the bundle is ACTIVE with startlevel 0.
 *
 * <h3>Spec reference</h3>
 * WildFly management conventions: attribute and operation parameter values are validated in the model stage
 * (AttributeDefinition validators; AbstractAddStepHandler.populateModel), so that an invalid configuration is
 * rejected by the operation that introduces it instead of failing the next boot. OSGi Core R8
 * BundleStartLevel.setStartLevel: "@throws IllegalArgumentException If the specified start level is less than or
 * equal to zero"; the subsystem's own framework startlevel attribute declares "min" => 1.
 *
 * <h3>Code location</h3>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/parser/FrameworkCapabilityResource.java (STARTLEVEL built
 * without setValidator), OSGiCapabilityAdd.java (populateModel only validates STARTLEVEL's type; performRuntime adds
 * the identifier to SubsystemState without resolving the module). Same code on WF41.
 *
 * <h3>Root cause</h3>
 * No range validator on the capability start level and no existence check for the module/bundle identifier.
 *
 * <h3>Reproduction</h3>
 * JDK 25, own umbrella worktree (see SubsystemChangesNotFlaggedIssueTestCase for the arquillian.xml / pom adjustments):
 * {@code $SP/bin/mvn25 -B -o ... -pl testsuite/integration -Dtest=CapabilityNotValidatedIssueTestCase -Dsurefire.failIfNoSpecifiedTests=false clean install}
 *
 * <h3>Observed</h3>
 * <pre>
 * testNonExistentModuleRejected: adding a capability for a module that does not exist must fail, response was {"outcome" => "success"}
 * testStartLevelBelowOneRejected: a capability startlevel of 0 must be rejected, response was {"outcome" => "success"}
 * </pre>
 *
 * <h3>Expected</h3>
 * outcome failed with a message for both (the test removes the capability again if the add succeeded; it never
 * reloads, so the server is not broken by the test).
 *
 * <h3>Reference behaviour</h3>
 * Not applicable (WildFly subsystem specific).
 *
 * <h3>Suggested fix</h3>
 * Add {@code new IntRangeValidator(1, true, true)} to FrameworkCapabilityResource.STARTLEVEL; in OSGiCapabilityAdd
 * check in the model stage that the identifier names an existing module (Module.getBootModuleLoader() /
 * the server's module loader) or bundle repository entry and fail otherwise.
 *
 * Verified: with an IntRangeValidator(1, true, true) on STARTLEVEL and a model-stage check in OSGiCapabilityAdd (bundles/<name>/main directory or a module loadable by the boot module loader, else OperationFailedException) both methods pass and the shipped standalone-osgi.xml still boots.
 *
 * <h3>Regression vs WF41</h3>
 * no.
 */
@RunWith(Arquillian.class)
@RunAsClient
public class CapabilityNotValidatedIssueTestCase {

    @ArquillianResource
    ManagementClient managementClient;

    @Deployment(testable = false)
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "capability-not-validated");
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
    public void testNonExistentModuleRejected() throws Exception {
        String name = "org.jboss.test.osgi.issues.no.such.module";
        ModelNode response = execute(capabilityOperation("add", name));
        try {
            Assert.assertEquals("adding a capability for a module that does not exist must fail, response was " + response, "failed",
                    response.get("outcome").asString());
        } finally {
            if ("success".equals(response.get("outcome").asString()))
                execute(capabilityOperation("remove", name));
        }
    }

    @Test
    public void testStartLevelBelowOneRejected() throws Exception {
        String name = "org.apache.commons.io";
        ModelNode add = capabilityOperation("add", name);
        add.get("startlevel").set(0);
        ModelNode response = execute(add);
        try {
            Assert.assertEquals("a capability startlevel of 0 must be rejected, response was " + response, "failed", response.get("outcome").asString());
        } finally {
            if ("success".equals(response.get("outcome").asString()))
                execute(capabilityOperation("remove", name));
        }
    }

    private static ModelNode capabilityOperation(String name, String capability) {
        ModelNode op = new ModelNode();
        op.get("operation").set(name);
        op.get("address").add("subsystem", "osgi");
        op.get("address").add("capability", capability);
        return op;
    }

    private ModelNode execute(ModelNode op) throws Exception {
        return managementClient.getControllerClient().execute(op);
    }
}
