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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper;
import org.jboss.modules.Module;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A Jakarta EE deployment that depends on a deployed bundle (WAR with {@code Dependencies: deployment.<bundle>}) is not
 * known to the framework as a user of the bundle: Bundle.update makes the new revision effective at once, the old
 * revision is not removal pending, FrameworkWiring.refreshBundles(null) does nothing for the WAR, and the WAR keeps the
 * old revision's classes while the bundle's services come from the new revision (ClassCastException in the WAR).
 *
 * <h2>Severity</h2>
 * major. "WAR uses the API and services of a bundle" is the main EE-to-OSGi integration pattern (User Guide CDI/EJB/web
 * examples, ModuleAccessesBundleServiceTestCase). After the standard OSGi update sequence (update, then refresh of the
 * removal pending bundles - Felix/Equinox consoles, provisioning agents) the WAR fails with ClassCastException on every
 * use of the bundle's services until it is redeployed by hand, and it keeps the old revision's class loader alive.
 * A management full-replace of the bundle deployment, in contrast, restarts the WAR correctly.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem: {@code ModuleLoaderIntegration} (module of a bundle revision used by EE
 * deployments through jboss-modules dependencies), {@code BundleLifecycleIntegration} (update/refresh policy); with
 * jbosgi-framework {@code UserBundleState.updateInternal} / {@code FrameworkWiringImpl.getRemovalPendingBundles} (the
 * in-use test only looks at OSGi wires).
 *
 * <h2>Found by</h2>
 * wildfly-ee workstream (deep 2), exploration "EE deployment uses a bundle": WAR with Dependencies on the bundle and a
 * servlet that loads the bundle's class and gets its service through the injected BundleContext; after
 * Bundle.update: "class=v1 ... service=v2 svc-instanceof=false svc-cl=... deployment.ee-lib-rev1.jar", removal pending
 * [], the same after refreshBundles(null); only refreshBundles(lib) or a management redeploy restarts the WAR.
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 Bundle.update() javadoc: "If this bundle has exported any packages that are imported by another bundle,
 * these packages must remain exported until the FrameworkWiring.refreshBundles method has been called or the Framework
 * is relaunched." FrameworkWiring.getRemovalPendingBundles: "Returns the bundles that have non-current, in use bundle
 * wirings." FrameworkWiring.refreshBundles(null): "Compute the dependency closure of the specified bundles. If no
 * bundles are specified, compute the dependency closure of the removal pending bundles." The WAR uses the old revision exactly like an importing bundle (its classes are loaded from the old
 * revision's module), so the old revision is in use and must be refreshed - with its dependents - by
 * refreshBundles(null). WildFly: deployments with a module dependency on a replaced deployment are restarted.
 * https://docs.osgi.org/specification/osgi.core/8.0.0/framework.wiring.html#org.osgi.framework.wiring.FrameworkWiring
 *
 * <h2>Code location</h2>
 * The WAR depends on the jboss-modules module {@code deployment.<bundle>} of the bundle's first revision
 * (wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/ModuleLoaderIntegration.java:247-270 addModuleSpec,
 * :339-361 createModuleService); the update installs the new revision as deployment {@code <bundle>-rev1} with module
 * {@code deployment.<bundle>-rev1} (BundleLifecycleIntegration.createBundleRevision, :226-262) and the framework treats
 * the old revision as not in use (jbosgi-framework UserBundleState.updateInternal, wiring.isInUse() only counts bundle
 * wires; FrameworkWiringImpl.java:292-310), so nothing ties the WAR to the bundle's refresh.
 *
 * <h2>Root cause</h2>
 * Module dependencies of EE deployments on bundle modules are invisible to the OSGi framework: they neither keep the
 * old revision in use (removal pending) nor make the WAR a dependent that a refresh restarts.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=WarStaleAfterBundleUpdateIssueTestCase \
 *     -Darq.container.jboss.configuration.managementPort=11090 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=1100
 * </pre>
 * (Prerequisites: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork in the local Maven
 * repository; on JDK 24+ the testsuite needs surefire.security.args without -Djava.security.manager, see
 * TestJvmSecurityManagerIssueTestCase.) Deterministic. The test reads the class through the WAR's module class loader
 * instead of a servlet.
 *
 * <h2>Observed</h2>
 * <pre>
 * java.lang.AssertionError: A WAR that depends on an updated bundle must be consistent after update +
 *   refreshBundles(null): [after the update the old revision, which the WAR still uses, must be removal pending, but
 *   getRemovalPendingBundles() is [], after refreshBundles(null) the WAR must see the bundle's current classes, but its
 *   LibService comes from HostBundleClassLoader for Module "deployment.ee11-lib.jar" from Service Module Loader and the
 *   bundle's service from HostBundleClassLoader for Module "deployment.ee11-lib-rev1.jar" from Service Module Loader
 *   (ClassCastException in the WAR)]
 * </pre>
 *
 * <h2>Expected</h2>
 * After update + refreshBundles(null) the WAR loads LibService from the bundle's current revision (it was restarted as
 * a dependent, as after a management full-replace), so the bundle's service is an instance of the WAR's LibService;
 * until the refresh the old revision is reported as removal pending.
 *
 * <h2>Reference behaviour</h2>
 * Management full-replace of the bundle deployment restarts the WAR and it sees the new classes (exploration, same
 * server). Equinox/Felix: an importing bundle of an updated exporter is re-wired by refreshBundles(null).
 *
 * <h2>Suggested fix (not applied)</h2>
 * Treat the module dependency of an EE deployment on a bundle module as a use of that revision: keep the old revision
 * removal pending while EE deployments depend on its module, and include those deployments in the refresh (restart them
 * when the bundle's module is replaced, as WildFly does for deployment-to-deployment module dependencies).
 * Related: ee/02 (BundleWiredToRedeployedEeModuleIssueTestCase), the converse direction (bundle -&gt; EE module; here EE
 * module -&gt; bundle). Both are "jboss-modules dependencies invisible to the framework's in-use/refresh logic", but the
 * fix sites differ (ee/02: ModuleRegistrationTracker.unregisterInternal; ee/11: the ModuleLoaderIntegration /
 * BundleLifecycleIntegration update path and the UserBundleState in-use check), so they are not duplicates. Also
 * related to lifecycle/06 (BundleUpdateNotPersistedIssueTestCase): the update creates the separate deployment
 * {@code <bundle>-rev1}.
 *
 * <h2>Regression vs WF41</h2>
 * no (measured on a WF41 server built from origin/WF41 77f95be6 on the same patched WildFly 41.0.1, JDK 25: same
 * failure). The update/refresh code paths are unchanged since WF41. The WF41 run used harness adaptations only, no
 * change to this test or to the code under test: the jacc {@code <policy>} removed from standalone-osgi.xml, as r8
 * 42d6c470 does (without it org.wildfly.security.policy fails on WildFly 41 and every deferred start fails with
 * JBAS011969), and the r8 Arquillian JBOSGiApplicationArchiveProcessor from 54b65e85 (in-container ManagementClient
 * lookup); see the regression-2 workstream NOTES.md.
 */
@RunWith(Arquillian.class)
public class WarStaleAfterBundleUpdateIssueTestCase {

    static final String LIB = "ee11-lib.jar";
    static final String WEB = "ee11-web.war";
    static final String LIB_SERVICE = "org.jboss.test.osgi.issues.WarStaleAfterBundleUpdateIssueTestCase$LibService";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @ArquillianResource
    ManagementClient managementClient;

    public static class LibService {
    }

    public static class LibActivator implements BundleActivator {
        @Override
        public void start(BundleContext context) throws Exception {
            context.registerService(LibService.class, new LibService(), new Hashtable<String, Object>());
        }

        @Override
        public void stop(BundleContext context) throws Exception {
        }
    }

    @Deployment
    public static JavaArchive testDeployment() {
        final JavaArchive jar = ShrinkWrap.create(JavaArchive.class, "war-stale-after-update-tests");
        jar.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(jar.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(ManagementClient.class, ModelControllerClient.class, ServerDeploymentHelper.class);
                builder.addImportPackages(FrameworkWiring.class, Module.class);
                return builder.openStream();
            }
        });
        return jar;
    }

    @Test
    public void testWarAfterBundleUpdateAndRefresh() throws Exception {
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        server.deploy(LIB, deployer.getDeployment("lib-v1"));
        server.deploy(WEB, deployer.getDeployment("web"));
        Bundle lib = getLib();
        try {
            Assert.assertTrue("Precondition: the WAR sees the bundle's service class", warLibService().isInstance(getService()));

            lib.update(deployer.getDeployment("lib-v2"));
            List<String> failures = new ArrayList<String>();
            Collection<Bundle> pending = context.getBundle(0).adapt(FrameworkWiring.class).getRemovalPendingBundles();
            if (pending.isEmpty())
                failures.add("after the update the old revision, which the WAR still uses, must be removal pending, but getRemovalPendingBundles() is " + pending);

            refreshAll();
            Class<?> warClass = warLibService();
            Object service = getService();
            if (!warClass.isInstance(service))
                failures.add("after refreshBundles(null) the WAR must see the bundle's current classes, but its LibService comes from " + warClass.getClassLoader()
                        + " and the bundle's service from " + service.getClass().getClassLoader() + " (ClassCastException in the WAR)");
            Assert.assertTrue("A WAR that depends on an updated bundle must be consistent after update + refreshBundles(null): " + failures, failures.isEmpty());
        } finally {
            undeploy(server, WEB);
            undeploy(server, "ee11-lib-rev1.jar");
            undeploy(server, LIB);
            Bundle leftover = getLib();
            if (leftover != null)
                leftover.uninstall();
        }
    }

    private Class<?> warLibService() throws Exception {
        Module war = Module.getCallerModuleLoader().loadModule("deployment." + WEB);
        return war.getClassLoader().loadClass(LIB_SERVICE);
    }

    private Object getService() {
        ServiceReference<?> sref = context.getServiceReference(LIB_SERVICE);
        Assert.assertNotNull("Precondition: LibService registered", sref);
        return context.getService(sref);
    }

    private Bundle getLib() {
        for (Bundle bundle : context.getBundles()) {
            if ("ee11-lib".equals(bundle.getSymbolicName()))
                return bundle;
        }
        return null;
    }

    private void refreshAll() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        context.getBundle(0).adapt(FrameworkWiring.class).refreshBundles(null, new FrameworkListener() {
            @Override
            public void frameworkEvent(FrameworkEvent event) {
                latch.countDown();
            }
        });
        Assert.assertTrue("Precondition: refreshBundles completes", latch.await(30, TimeUnit.SECONDS));
    }

    private static void undeploy(ServerDeploymentHelper server, String name) {
        try {
            server.undeploy(name);
        } catch (Exception ex) {
            // not deployed
        }
    }

    private static JavaArchive lib(String archiveName, final String version) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, archiveName);
        archive.addClasses(LibService.class, LibActivator.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName("ee11-lib");
                builder.addBundleVersion(version);
                builder.addBundleManifestVersion(2);
                builder.addBundleActivator(LibActivator.class);
                builder.addExportPackages(LibService.class.getPackage().getName() + ";ee11=true");
                builder.addImportPackages(BundleActivator.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = "lib-v1", managed = false, testable = false)
    public static JavaArchive getLibV1() {
        return lib("ee11-lib-v1.jar", "1.0.0");
    }

    @Deployment(name = "lib-v2", managed = false, testable = false)
    public static JavaArchive getLibV2() {
        return lib("ee11-lib-v2.jar", "2.0.0");
    }

    @Deployment(name = "web", managed = false, testable = false)
    public static WebArchive getWeb() {
        WebArchive war = ShrinkWrap.create(WebArchive.class, WEB);
        war.addAsWebResource(new StringAsset("ee11"), "index.html");
        war.addAsManifestResource(new StringAsset("Manifest-Version: 1.0\nDependencies: deployment." + LIB + "\n"), "MANIFEST.MF");
        return war;
    }
}
