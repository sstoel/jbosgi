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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

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
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * /subsystem=osgi:read-resource(recursive=true,include-runtime=true) fails with WFLYCTL0216 "Management resource
 * [subsystem=osgi, bundle=X] not found" when a bundle is uninstalled while the read runs (deployments, undeployments,
 * bundles installed/uninstalled by other bundles).
 *
 * <h3>Severity</h3>
 * minor. Monitoring tools and the admin console that poll the subsystem recursively get intermittent failures whenever
 * the bundle set changes; a CLI script with such a read aborts. Seen with plain CLI deploy/undeploy of 30 bundles
 * against 150 recursive reads (failed on the 7th read: bundle=conc0.jar not found).
 *
 * <h3>Component</h3>
 * jbosgi (umbrella), wildfly/subsystem: org.jboss.as.osgi.management.OSGiRuntimeResource (live children computed from
 * the BundleManager; requireChild throws NoSuchResourceException for a bundle that disappeared after its name was
 * listed).
 *
 * <h3>Found by</h3>
 * services workstream round 4 (concurrent management operations during deployments), CLI on the patched server
 * (offset 400), then this test.
 *
 * <h3>Spec reference</h3>
 * WildFly management conventions for runtime-only child resources: a read of the parent must be consistent with the
 * children it lists (read-resource builds on getChildren/requireChild of the same Resource); runtime resources whose
 * children can vanish must tolerate that (snapshot the children, or return a placeholder resource for a child that is
 * gone) instead of failing the whole read.
 *
 * <h3>Code location</h3>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/management/OSGiRuntimeResource.java: getChildrenNames(BUNDLE) lists
 * the current bundles, requireChild/hasChild re-check them later
 * ({@code if (hasBundle(element)) return OSGiBundleResource.INSTANCE; throw new NoSuchResourceException(element);}).
 * Same code on WF41.
 *
 * <h3>Root cause</h3>
 * The child names and the child lookups are evaluated at different times against the live bundle set: the controller
 * lists the bundle names, then navigates to each child (hasChild/requireChild re-check the bundle and throw
 * NoSuchResourceException, WFLYCTL0216), and the per-attribute read handler looks the bundle up again for every
 * attribute (BundleResourceHandler.getTargetBundle throws IllegalArgumentException JBAS011960, which becomes WFLYCTL0158).
 * Both windows have to be closed.

 * <h3>Reproduction</h3>
 * JDK 25, own umbrella worktree (see SubsystemChangesNotFlaggedIssueTestCase):
 * {@code $SP/bin/mvn25 -B -o ... -pl testsuite/integration -Dtest=RecursiveReadDuringBundleChurnIssueTestCase -Dsurefire.failIfNoSpecifiedTests=false clean install}
 * The deployed helper bundle installs and uninstalls a small bundle in a loop for 30 s; the test reads the subsystem
 * recursively for 20 s.
 *
 * <h3>Observed</h3>
 * <pre>
 * testRecursiveReadWhileBundlesChange: a recursive read of /subsystem=osgi must not fail while bundles are installed and
 *   uninstalled, failed read N: WFLYCTL0216: Management resource '[ ("subsystem" => "osgi"), ("bundle" => "churn-...") ]' not found
 * </pre>
 *
 * <h3>Expected</h3>
 * Every recursive read succeeds (a bundle that disappears is either omitted or shown with its last known values).
 *
 * <h3>Reference behaviour</h3>
 * Not applicable.
 *
 * <h3>Suggested fix</h3>
 * Tolerate a bundle that vanishes during a read: in OSGiRuntimeResource let hasChild/getChild/requireChild return
 * OSGiBundleResource.INSTANCE for any bundle name, and in BundleResourceHandler wrap the read-attribute dispatch in
 * {@code try { ... } catch (IllegalArgumentException | IllegalStateException ex) { context.getResult().set(new ModelNode()); }}
 * (the bundle was uninstalled after it was listed). Side effect of this minimal fix: a read of a bundle address that
 * never existed succeeds with undefined attributes instead of failing; a cleaner fix keeps requireChild strict for
 * direct addressing and only relaxes it for the children enumerated by the same read (e.g. a per-operation snapshot of
 * the bundle set attached to the OperationContext).
 *
 * Verified: with the minimal fix above (only the first half, OSGiRuntimeResource, is not enough: the read then fails
 * with WFLYCTL0158 JBAS011960 Cannot obtain bundle resource for: churn-88) the test passes. Patch:
 * $SP/agents/services/r4-i27-fix.diff.

 * <h3>Regression vs WF41</h3>
 * no.
 */
@RunWith(Arquillian.class)
@RunAsClient
public class RecursiveReadDuringBundleChurnIssueTestCase {

    /** Installs and uninstalls a bundle in a loop for 30 seconds. */
    public static class ChurnActivator implements BundleActivator {
        private volatile boolean running = true;

        @Override
        public void start(final BundleContext context) throws Exception {
            final byte[] content = jar("churn-bundle");
            Thread thread = new Thread(() -> {
                long end = System.currentTimeMillis() + 30000;
                int i = 0;
                while (running && System.currentTimeMillis() < end) {
                    try {
                        Bundle bundle = context.installBundle("churn-" + (i++), new ByteArrayInputStream(content));
                        bundle.uninstall();
                    } catch (Exception ex) {
                        // keep going
                    }
                }
            }, "bundle-churn");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void stop(BundleContext context) {
            running = false;
        }

        private static byte[] jar(String bsn) throws Exception {
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Bundle-ManifestVersion", "2");
            manifest.getMainAttributes().putValue("Bundle-SymbolicName", bsn);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new JarOutputStream(out, manifest).close();
            return out.toByteArray();
        }
    }

    @ArquillianResource
    ManagementClient managementClient;

    @Deployment(testable = false)
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "recursive-read-bundle-churn");
        archive.addClasses(ChurnActivator.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addBundleActivator(ChurnActivator.class);
                builder.addImportPackages(BundleActivator.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Test
    public void testRecursiveReadWhileBundlesChange() throws Exception {
        ModelNode op = new ModelNode();
        op.get("operation").set("read-resource");
        op.get("address").add("subsystem", "osgi");
        op.get("recursive").set(true);
        op.get("include-runtime").set(true);
        long end = System.currentTimeMillis() + 20000;
        int reads = 0;
        while (System.currentTimeMillis() < end) {
            ModelNode response = managementClient.getControllerClient().execute(op);
            reads++;
            if ("success".equals(response.get("outcome").asString()) == false)
                Assert.fail("a recursive read of /subsystem=osgi must not fail while bundles are installed and uninstalled, failed read " + reads + ": "
                        + response.get("failure-description"));
        }
    }
}
