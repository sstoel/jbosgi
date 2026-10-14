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
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.wiring.BundleRevision;

/**
 * Every deployed bundle revision keeps a private copy of the whole management model alive: the revision stores the
 * deployment phase's ServiceTarget, which in WildFly is a RequirementServiceTarget that references the deploy
 * operation's OperationContext capability support and through it that operation's ManagementModel (a full copy of the
 * resource tree). Each deployment operation leaves one more model copy (~350 KB in standalone-osgi) per bundle revision,
 * for as long as the revision lives; together with the revision leak of wildfly/06 the server runs out of heap.
 *
 * <h2>Severity</h2>
 * critical. Found by the concurrent-deployment soak (round 3, period 3, resolve-only helper): after ~4 min of
 * deploy/redeploy/undeploy churn (about 3400 operations/min, 6 clients) the 512 MB heap was exhausted:
 * {@code java.lang.OutOfMemoryError: Java heap space} in management operations and MSC deployment phases, every later
 * deployment operation rolled back, the HTTP management interface stopped answering (connection reset, also from
 * jboss-cli inside the container), the server had to be killed. Heap after the OOM (live objects, 535 MB): 1489
 * {@code ModelControllerImpl$ManagementModelImpl}, 1486 {@code RequirementServiceTarget$CapabilityServiceSupportTarget},
 * 2.36 M ModelNode, 482 K BasicResource, 55 K ExtensionResource (= 1489 model copies of 37 extensions) next to 1405
 * HostBundleRevision / 1496 UserBundleState. Shortest GC-root paths (scripts/hprof_paths.py):
 * <pre>
 * ExtensionResource &lt;- ... BasicResource &lt;- .rootResource ModelControllerImpl$ManagementModelImpl
 *   &lt;- .managementModel OperationContextImpl$CapabilityServiceSupportImpl &lt;- .support
 *   RequirementServiceTarget$CapabilityServiceSupportTarget &lt;- .serviceTarget HostBundleRevision
 *   &lt;- .requirer AbstractBundleWire &lt;- ... .provided AbstractBundleWiring &lt;- ... SystemBundleRevision   (wildfly/06, 15375 instances)
 *   &lt;- .initialRevision UserBundleState &lt;- .key ... FrameworkEventsImpl.serviceListeners                   (wildfly/22, resolver/20, 10150)
 * </pre>
 * Without the model copy a leaked revision costs a few KB (class loader, wiring); with it ~350 KB, so the OOM comes from
 * this issue and wildfly/06 / wildfly/22 together. Even without any leak the cost is real: N bundles deployed in N
 * operations pin N model copies.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella) wildfly/subsystem {@code BundleInstallProcessor} together with jbosgi-framework core
 * {@code UserBundleRevision} (field {@code serviceTarget}).
 *
 * <h2>Found by</h2>
 * wildfly workstream, server soak under concurrent deployment load (JDK 25, Docker eclipse-temurin:25-jdk, 512 MB heap
 * default of standalone.conf).
 *
 * <h2>Spec reference</h2>
 * No OSGi spec clause; WildFly's contract for DeploymentPhaseContext/OperationContext: the capability-aware service
 * target belongs to the operation (its {@code CapabilityServiceSupport} answers from the operation's model snapshot) and
 * must not be retained after the operation completed. OSGi Core R8 4.4.9 / 10.1.6 (uninstalled bundles and their
 * revisions must become collectable once no longer in use) for the combined effect.
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/deployment/BundleInstallProcessor.java:85
 * ({@code bundleManager.installBundleRevision(syscontext, deployment, phaseContext.getServiceTarget())});
 * jbosgi-framework core/src/main/java/org/jboss/osgi/framework/internal/UserBundleRevision.java:56,65
 * ({@code private final ServiceTarget serviceTarget}, kept for the life of the revision); used later by
 * FrameworkModuleLoaderImpl.createModuleService:216 (module service of the revision at resolve time).
 *
 * <h2>Root cause</h2>
 * The revision keeps the deploy operation's capability service target, which strongly references the operation's
 * ManagementModel copy.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full \
 *     -pl testsuite/integration test -Dtest=ManagementModelRetainedByRevisionIssueTestCase
 * </pre>
 * Deterministic: deploys three bundles in three deployment operations, then follows the fields of each revision's
 * {@code serviceTarget} (reflection, objects outside java.* only, depth 4) and reports the ManagementModel objects it
 * reaches.
 * (on JDK 24+ the testsuite needs surefire.security.args without -Djava.security.manager, see TestJvmSecurityManagerIssueTestCase)
 *
 * <h2>Observed</h2>
 * <pre>
 * java.lang.AssertionError: Expected no bundle revision to reference a management model after its deployment, but the
 *   3 revisions reach 3 distinct ManagementModel copies: [model-retained-a.jar: revision.serviceTarget
 *   -&gt; RequirementServiceTarget$CapabilityServiceSupportTarget.support
 *   -&gt; OperationContextImpl$CapabilityServiceSupportImpl.managementModel = org.jboss.as.controller.ModelControllerImpl$ManagementModelImpl,
 *   model-retained-b.jar: (same), model-retained-c.jar: (same)]
 * </pre>
 *
 * <h2>Expected</h2>
 * No bundle revision references a ManagementModel after its deployment operation completed.
 *
 * <h2>Reference behaviour</h2>
 * Not applicable (WildFly integration).
 *
 * <h2>Suggested fix (not applied)</h2>
 * Do not hand the operation-scoped target to the framework: in BundleInstallProcessor pass a plain MSC target that is
 * not bound to the OperationContext (e.g. a {@code subTarget()} of the service container, and remove the revision's
 * module services when the deployment is undeployed, which BundleInstallProcessor.undeploy already drives), or let
 * UserBundleRevision drop the reference ({@code serviceTarget = null}) once its module service was created / the
 * deployment phase completed. Fixing wildfly/06 and wildfly/22 removes the unbounded growth but not the per-bundle cost.
 *
 * <h2>Regression vs WF41</h2>
 * no (BundleInstallProcessor and UserBundleRevision are unchanged from origin/WF41 on the same WildFly 41 core).
 */
@RunWith(Arquillian.class)
public class ManagementModelRetainedByRevisionIssueTestCase {

    static final String[] BUNDLES = { "model-retained-a.jar", "model-retained-b.jar", "model-retained-c.jar" };

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "model-retained-tests");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(BundleRevision.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Test
    public void testRevisionsDoNotRetainManagementModel() throws Exception {
        for (String name : BUNDLES)
            deployer.deploy(name);
        try {
            Map<Object, Object> models = new IdentityHashMap<Object, Object>();
            List<String> findings = new ArrayList<String>();
            for (String name : BUNDLES) {
                Bundle bundle = null;
                for (Bundle b : context.getBundles())
                    if (name.equals(b.getSymbolicName()))
                        bundle = b;
                Assert.assertNotNull("Precondition: bundle deployed: " + name, bundle);
                BundleRevision brev = bundle.adapt(BundleRevision.class);
                Object target = readField(brev, "serviceTarget");
                Assert.assertNotNull("Precondition: revision has a serviceTarget field: " + brev.getClass(), target);
                String path = find(target, "revision.serviceTarget", 0, new IdentityHashMap<Object, Boolean>(), models);
                if (path != null)
                    findings.add(name + ": " + path);
            }
            Assert.assertTrue("Expected no bundle revision to reference a management model after its deployment, but the "
                    + BUNDLES.length + " revisions reach " + models.size() + " distinct ManagementModel copies: " + findings,
                    findings.isEmpty());
        } finally {
            for (String name : BUNDLES)
                deployer.undeploy(name);
        }
    }

    // Depth-limited walk over the fields of non-JDK objects; returns the first path to a ManagementModel
    private static String find(Object obj, String path, int depth, Map<Object, Boolean> seen, Map<Object, Object> models) {
        if (obj == null || seen.put(obj, Boolean.TRUE) != null)
            return null;
        String cname = obj.getClass().getName();
        if (cname.endsWith("$ManagementModelImpl") || cname.endsWith(".ManagementModel")) {
            models.put(obj, obj);
            return path + " = " + cname;
        }
        if (depth == 4 || cname.startsWith("java.") || cname.startsWith("jdk.") || obj.getClass().isArray())
            return null;
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive())
                    continue;
                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(obj);
                } catch (Exception ex) {
                    continue;
                }
                String found = find(value, path + " -> " + simple(obj) + "." + field.getName(), depth + 1, seen, models);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static String simple(Object obj) {
        String name = obj.getClass().getName();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static Object readField(Object obj, String fieldName) throws Exception {
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(obj);
            } catch (NoSuchFieldException ex) {
                // try the super class
            }
        }
        return null;
    }

    @Deployment(name = "model-retained-a.jar", managed = false, testable = false)
    public static JavaArchive getA() {
        return bundle("model-retained-a.jar");
    }

    @Deployment(name = "model-retained-b.jar", managed = false, testable = false)
    public static JavaArchive getB() {
        return bundle("model-retained-b.jar");
    }

    @Deployment(name = "model-retained-c.jar", managed = false, testable = false)
    public static JavaArchive getC() {
        return bundle("model-retained-c.jar");
    }

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
}
