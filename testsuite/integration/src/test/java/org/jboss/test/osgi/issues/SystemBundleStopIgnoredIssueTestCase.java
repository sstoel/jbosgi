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
 * /subsystem=osgi/bundle=0:stop (the system bundle) reports success but does nothing: in the server the framework's
 * BundleManager stays in state INSTALLED, so Bundle.stop() on the system bundle is silently ignored.
 *
 * <h3>Severity</h3>
 * minor. The management operation claims success for an action that did not happen. The same silent no-op applies to
 * Bundle.stop()/update() of the system bundle called by bundle code inside the server, and to every other code path
 * that relies on BundleManagerPlugin.shutdownManager (see note).
 *
 * <h3>Component</h3>
 * jbosgi (umbrella) wildfly/subsystem: org.jboss.as.osgi.management.BundleResourceHandler (reports success);
 * jbosgi-framework core: BundleManagerPlugin manager state, which only FrameworkProxy (the launch API) sets; the WildFly
 * integration starts the framework through MSC services and never moves it to STARTING/ACTIVE.
 *
 * <h3>Found by</h3>
 * services workstream round 4 (osgi subsystem management model, robustness: stopping the system bundle via
 * management). A probe bundle in the server printed
 * {@code system bundle class=org.jboss.osgi.framework.internal.SystemBundleState state=32 managerState=2}
 * (Bundle.INSTALLED); a standalone framework (aggregated jar) goes to STOPPING after the same stop().
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.6 The System Bundle / Bundle.stop javadoc for the system bundle: "stop - Returns immediately and
 * shuts down the Framework on another thread." WildFly management conventions: an operation handler reports failure
 * (OperationFailedException / failure-description) when the requested action is not performed.
 *
 * <h3>Code location</h3>
 * jbosgi-framework core/src/main/java/org/jboss/osgi/framework/internal/SystemBundleState.java:140-143
 * ({@code stopWithOptions -> getBundleManager().shutdownManager(false)}), BundleManagerPlugin.java:932-937
 * ({@code if (state != Bundle.STARTING && state != Bundle.ACTIVE) return;}), the manager state is only set in
 * FrameworkProxy.java:149/196/201/246; jbosgi wildfly/subsystem management/BundleResourceHandler.java handleOperation
 * (STOP: {@code bundle.stop()} and completeStep).
 *
 * <h3>Root cause</h3>
 * In the server the framework is created and started by the subsystem's MSC services, not through FrameworkProxy, so the
 * BundleManager's state remains INSTALLED and shutdownManager returns immediately without an exception.
 *
 * <h3>Reproduction</h3>
 * JDK 25, own umbrella worktree (see SubsystemChangesNotFlaggedIssueTestCase):
 * {@code $SP/bin/mvn25 -B -o ... -pl testsuite/integration -Dtest=SystemBundleStopIgnoredIssueTestCase -Dsurefire.failIfNoSpecifiedTests=false clean install}
 *
 * <h3>Observed</h3>
 * <pre>
 * testStopSystemBundleIsNotSilentlyIgnored: stopping the system bundle through management must either fail or stop it,
 *   but the operation succeeded ({"outcome" => "success"}) and the system bundle is still ACTIVE
 * </pre>
 *
 * <h3>Expected</h3>
 * The operation fails with a message (preferred inside an application server), or the framework is actually stopped.
 *
 * <h3>Reference behaviour</h3>
 * Standalone JBoss OSGi / Equinox: Bundle.stop() on the system bundle stops the framework.
 *
 * <h3>Suggested fix</h3>
 * Reject start/stop of bundle 0 in BundleResourceHandler (OperationFailedException); in the framework, set the manager
 * state when the MSC-driven framework becomes active (FrameworkActive service) so that system bundle stop and the
 * shutdown path work. Note: the same early return may be why the framework is never shut down on :reload (connect/27).
 *
 * Verified: rejecting stop of bundle 0 in BundleResourceHandler (failure description) makes the test pass.
 *
 * <h3>Regression vs WF41</h3>
 * no (same code).
 */
@RunWith(Arquillian.class)
@RunAsClient
public class SystemBundleStopIgnoredIssueTestCase {

    @ArquillianResource
    ManagementClient managementClient;

    @Deployment(testable = false)
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "system-bundle-stop-ignored");
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
    public void testStopSystemBundleIsNotSilentlyIgnored() throws Exception {
        ModelNode response = execute(bundleOperation("stop"));
        if ("success".equals(response.get("outcome").asString())) {
            Thread.sleep(3000);
            ModelNode state = bundleOperation("read-attribute");
            state.get("name").set("state");
            ModelNode stateResponse = execute(state);
            String value = "success".equals(stateResponse.get("outcome").asString()) ? stateResponse.get("result").asString() : String.valueOf(stateResponse);
            Assert.assertNotEquals("stopping the system bundle through management must either fail or stop it, but the operation succeeded (" + response
                    + ") and the system bundle is still ACTIVE", "ACTIVE", value);
        }
    }

    private static ModelNode bundleOperation(String name) {
        ModelNode op = new ModelNode();
        op.get("operation").set(name);
        op.get("address").add("subsystem", "osgi");
        op.get("address").add("bundle", "0");
        return op;
    }

    private ModelNode execute(ModelNode op) throws Exception {
        return managementClient.getControllerClient().execute(op);
    }
}
