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
 * With activation="lazy", after the framework has been activated (by :activate or the first bundle deployment) the
 * subsystem's startlevel attribute reads undefined and write-attribute(name=startlevel) reports success without
 * changing the framework start level.
 *
 * <h3>Severity</h3>
 * minor. With the documented default activation (lazy), the framework start level cannot be read or changed through
 * the management model at all, and the write silently claims success.
 *
 * <h3>Component</h3>
 * jbosgi (umbrella), wildfly/subsystem: org.jboss.as.osgi.management.StartLevelHandler (READ_HANDLER/WRITE_HANDLER and
 * execute()).
 *
 * <h3>Found by</h3>
 * services workstream round 4 (osgi subsystem management model, r8 change in StartLevelHandler / ActivateOperationHandler),
 * CLI on the patched server (offset 400): activation=lazy, :reload, :activate -> bundles listed and ACTIVE, but
 * {@code :read-attribute(name=startlevel)} = undefined and {@code :write-attribute(name=startlevel,value=3)} ->
 * {"outcome" => "success", "result" => undefined}, bundle start levels unchanged.
 *
 * <h3>Spec reference</h3>
 * The subsystem's description of startlevel: "The current Start Level of the OSGi Framework. Changing this value will
 * change the Start Level of the Framework accordingly" (storage runtime, restart-required no-services). WildFly
 * management conventions: a write that cannot be applied must fail (OperationFailedException), not report success.
 *
 * <h3>Code location</h3>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/management/StartLevelHandler.java, execute():
 * {@code ServiceController<?> controller = context.getServiceRegistry(false).getService(Services.SYSTEM_BUNDLE);
 * if (controller != null && controller.getState() == ServiceController.State.UP) {...} else { context.getResult().set(new ModelNode()); }}
 * In lazy mode the SYSTEM_BUNDLE service is not UP after the framework was activated, and the write takes the same
 * "non-metric read" branch. The r8 commit only added the IllegalStateException catch inside the UP branch.
 *
 * <h3>Root cause</h3>
 * The handler depends on the SYSTEM_BUNDLE MSC service being UP instead of on the framework being active, and treats
 * the write like a read when it is not.
 *
 * <h3>Reproduction</h3>
 * JDK 25, own umbrella worktree (see SubsystemChangesNotFlaggedIssueTestCase):
 * {@code $SP/bin/mvn25 -B -o ... -pl testsuite/integration -Dtest=LazyActivationStartLevelIssueTestCase -Dsurefire.failIfNoSpecifiedTests=false clean install}
 * The test switches the subsystem to lazy, reloads, runs :activate, and restores the original activation (with a
 * reload) at the end.
 *
 * <h3>Observed</h3>
 * <pre>
 * testStartLevelAfterLazyActivation: after :activate the framework is active (bundles ACTIVE), the startlevel attribute
 *   must be defined, but was undefined
 * </pre>
 *
 * <h3>Expected</h3>
 * startlevel reads the framework start level (1) once the framework is active; a write either changes it or fails.
 *
 * <h3>Reference behaviour</h3>
 * With activation="eager" the same operations work (read 1, write 3 then read 3).
 *
 * <h3>Suggested fix</h3>
 * Obtain the system bundle from the FRAMEWORK_CREATE / BUNDLE_MANAGER service (as BundleResourceHandler does) or demand
 * SYSTEM_BUNDLE; in WRITE_HANDLER throw OperationFailedException when the framework is not active.
 *
 * Verified: with a fallback to the FRAMEWORK_CREATE service (BundleContext.getBundle()) when SYSTEM_BUNDLE is not UP the test passes (read 1, write 2 read back 2).
 *
 * <h3>Regression vs WF41</h3>
 * no (same execute() on WF41).
 */
@RunWith(Arquillian.class)
@RunAsClient
public class LazyActivationStartLevelIssueTestCase {

    @ArquillianResource
    ManagementClient managementClient;

    @Deployment(testable = false)
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "lazy-activation-startlevel");
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
    public void testStartLevelAfterLazyActivation() throws Exception {
        String original = execute(subsystem("read-attribute", "name", "activation")).get("result").asString();
        try {
            assertSuccess(execute(subsystem("write-attribute", "name", "activation", "value", "lazy")));
            reload();
            assertSuccess(execute(subsystem("activate")));
            // wait until the framework is active: the capability bundles are listed and ACTIVE
            String state = null;
            for (int i = 0; i < 100 && "ACTIVE".equals(state) == false; i++) {
                Thread.sleep(100);
                ModelNode op = subsystem("read-attribute", "name", "state");
                op.get("address").add("bundle", "org.apache.felix.scr");
                ModelNode response = execute(op);
                state = response.get("outcome").asString().equals("success") ? response.get("result").asString() : null;
            }
            Assert.assertEquals("Precondition: the framework is active after :activate", "ACTIVE", state);

            ModelNode read = execute(subsystem("read-attribute", "name", "startlevel"));
            Assert.assertTrue("after :activate the framework is active (bundles ACTIVE), the startlevel attribute must be defined, but was "
                    + read.get("result"), read.get("result").isDefined());

            ModelNode write = execute(subsystem("write-attribute", "name", "startlevel", "value", "2"));
            if ("success".equals(write.get("outcome").asString())) {
                Thread.sleep(1000);
                Assert.assertEquals("a successful write of startlevel=2 must change the framework start level", 2,
                        execute(subsystem("read-attribute", "name", "startlevel")).get("result").asInt(-1));
                execute(subsystem("write-attribute", "name", "startlevel", "value", "1"));
            }
        } finally {
            execute(subsystem("write-attribute", "name", "activation", "value", original));
            reload();
        }
    }

    private static ModelNode subsystem(String name, String... keyValues) {
        ModelNode op = new ModelNode();
        op.get("operation").set(name);
        op.get("address").add("subsystem", "osgi");
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

    private void reload() throws Exception {
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
