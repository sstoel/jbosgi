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
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.client.helpers.standalone.DeploymentPlanBuilder;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StopContext;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

/**
 * When a bundle is redeployed while the module of its previous, still used revision has a dependent service that takes
 * time to stop, the module take-over blocks the MSC thread of the redeployment (and with it every other deployment
 * operation of the server) until those services are removed, for up to 30 seconds; after 30 seconds the redeployment
 * fails with a DuplicateServiceException.
 *
 * <h3>Severity</h3>
 * major. Any service or deployment that depends on the module of a bundle (for example a WAR or EAR that names the
 * bundle's module "deployment.&lt;name&gt;" as a dependency, whose undeploy takes a few seconds) makes the redeploy of that
 * bundle stall the server's deployment processing: an unrelated deployment started meanwhile waits as long as the
 * take-over; if the dependent needs more than 30 s, the redeploy fails ("Service jboss.module.spec.service
 * \"deployment.&lt;name&gt;\" is already registered").
 *
 * <h3>Component</h3>
 * jbosgi umbrella, wildfly/subsystem, {@code org.jboss.as.osgi.service.ModuleLoaderIntegration}
 * ({@code FrameworkModuleLoaderImpl.addModuleSpec} / {@code removeModuleServices}).
 *
 * <h3>Found by</h3>
 * connect workstream, round 3 (code-review suspicion from round 2 turned into a measurement on the patched WildFly 41
 * server, JDK 25). The dependent with the slow stop is a test-only MSC service that requires the old module service.
 *
 * <h3>Spec reference</h3>
 * No OSGi clause is violated directly; this is a robustness defect of the WildFly integration. Related: OSGi Core R8,
 * 4.4.10 (an in-use revision of an uninstalled bundle stays available "until the bundle is refreshed", a new install of
 * the same location is a different bundle and must not depend on the old revision's resources). The management
 * operation of the redeploy, and with it every other deployment operation, waits for the take-over because deployment
 * operations are serialized by the server's model controller.
 *
 * <h3>Code location</h3>
 * {@code wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/ModuleLoaderIntegration.java:255-258}: when the
 * identifier was owned by another revision, {@code addModuleSpec} calls {@code removeModuleServices(identifier, true)},
 * which sets the old module services to REMOVE and waits on a latch for up to 30 s (line 409) on the calling thread (an
 * MSC thread in phase CONFIGURE_MODULE); after a timeout it goes on and installs the new module spec service under the
 * same name (line 261), which fails because the old one still exists. Introduced by c4bbcd55 (redeploy while the
 * previous revision is in use, JBOSGI-800).
 *
 * <h3>Root cause</h3>
 * A synchronous, bounded wait for MSC service removal inside an MSC service start, and the reuse of the same service
 * names for the old and the new module.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=ModuleTakeoverBlocksDeploymentsIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository. The two -Darq... options start the managed server with port offset 500, so the test cannot connect to another server on 9990 (arquillian.xml has allowConnectingToRunningServer=true); they can be left out when no other server runs.)
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: Expected the redeploy to finish without waiting for the old module's dependents, but:
 *   [the redeploy took 8029 ms, the unrelated deployment started 1 s later took 7037 ms]
 * (exploratory variant with a 35 s stop: the redeploy failed after 35 s with WFLYSRV0153 ... CONFIGURE_MODULE ...
 *   DuplicateServiceException: Service jboss.module.spec.service."deployment.takeover-exp.jar" is already registered,
 *   and the unrelated deployment took 34034 ms)
 * </pre>
 *
 * <h3>Expected</h3>
 * The redeploy and the unrelated deployment complete in their normal time (well under a second here); the old
 * module's services are removed asynchronously or the new revision gets module services under its own name.
 *
 * <h3>Reference behaviour</h3>
 * n/a (WildFly integration).
 *
 * <h3>Suggested fix</h3>
 * Give the module of each revision its own identifier (e.g. include the revision id, as for updates
 * "&lt;name&gt;-revN"), so that no take-over is needed; or install the new module spec through a service that depends on
 * the removal of the old one instead of waiting on a latch in the MSC thread.
 *
 * <h3>Note on the test</h3>
 * The dependent service stops in 8 s; the test removes it and undeploys everything at the end.
 *
 * <h3>Regression vs WF41</h3>
 * new R8 behaviour: on the WF41 server (origin/WF41 77f95be6, same patched WildFly 41.0.1, JDK 25) the redeploy of the
 * in-use exporter fails outright (CONFIGURE_MODULE: DuplicateServiceException ... is already registered) instead of
 * waiting for the old module's dependents; the blocking comes from the module take-over in ModuleLoaderIntegration,
 * which is new on the R8 branch; the defect is in behaviour WF41 never had, so it is not a regression of working
 * functionality.
 */
@RunWith(Arquillian.class)
public class ModuleTakeoverBlocksDeploymentsIssueTestCase {

    static final String PACKAGE = "org.jboss.test.osgi.issues.takeoverapi";
    static final long STOP_MILLIS = 8000;

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    ManagementClient managementClient;

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "module-takeover-tests");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(ClientConstants.class, ModelControllerClient.class, ManagementClient.class, DeploymentPlanBuilder.class, ModelNode.class);
                builder.addImportPackages(ServiceContainer.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testRedeployDoesNotWaitForOldModuleDependents() throws Exception {
        final ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        String exporter = server.deploy("takeover-exp.jar", deployer.getDeployment("takeover-exp"));
        String importer = server.deploy("takeover-imp.jar", deployer.getDeployment("takeover-imp"));

        // The exporter is undeployed while the importer uses it, its module stays
        server.undeploy(exporter);

        // A service that depends on the old module and needs time to stop (as a dependent deployment's services may)
        ServiceContainer container = serviceContainer();
        ServiceBuilder<Void> builder = container.addService(ServiceName.of("module-takeover-test", "slow-dependent"), new Service<Void>() {
            @Override
            public void start(StartContext ctx) {
            }

            @Override
            public void stop(StopContext ctx) {
                try {
                    Thread.sleep(STOP_MILLIS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public Void getValue() {
                return null;
            }
        });
        builder.requires(ServiceName.parse("jboss.module.service.\"deployment.takeover-exp.jar\""));
        ServiceController<Void> dependent = builder.install();

        final InputStream exporterContent = deployer.getDeployment("takeover-exp");
        final InputStream otherContent = deployer.getDeployment("takeover-other");
        final AtomicLong otherMillis = new AtomicLong(-1);
        final AtomicReference<String> otherName = new AtomicReference<String>();
        Thread other = new Thread("deploy takeover-other") {
            @Override
            public void run() {
                try {
                    Thread.sleep(1000);
                    long start = System.currentTimeMillis();
                    otherName.set(server.deploy("takeover-other.jar", otherContent));
                    otherMillis.set(System.currentTimeMillis() - start);
                } catch (Exception ex) {
                    // reported through otherMillis
                }
            }
        };
        other.start();

        String redeployed = null;
        List<String> failures = new ArrayList<String>();
        try {
            long start = System.currentTimeMillis();
            try {
                redeployed = server.deploy("takeover-exp.jar", exporterContent);
            } catch (Exception ex) {
                failures.add("the redeploy failed: " + ex);
            }
            long redeployMillis = System.currentTimeMillis() - start;
            other.join(60000);
            if (redeployMillis > 3000)
                failures.add("the redeploy took " + redeployMillis + " ms");
            if (otherMillis.get() < 0 || otherMillis.get() > 3000)
                failures.add("the unrelated deployment started 1 s later took " + otherMillis.get() + " ms");
            Assert.assertTrue("Expected the redeploy to finish without waiting for the old module's dependents, but: " + failures, failures.isEmpty());
        } finally {
            dependent.setMode(ServiceController.Mode.REMOVE);
            if (otherName.get() != null)
                server.undeploy(otherName.get());
            if (redeployed != null)
                server.undeploy(redeployed);
            server.undeploy(importer);
        }
    }

    private ServiceContainer serviceContainer() throws Exception {
        // BundleManager is not an exported package, get the container through the system bundle adaptor
        Bundle sysbundle = context.getBundle(0);
        Class<?> bmClass = sysbundle.loadClass("org.jboss.osgi.framework.spi.BundleManager");
        Object bundleManager = sysbundle.adapt(bmClass);
        return (ServiceContainer) bmClass.getMethod("getServiceContainer").invoke(bundleManager);
    }

    private static JavaArchive bundle(String name, final String symbolicName, final Boolean exporter) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.addAsResource(new StringAsset(name), Boolean.TRUE.equals(exporter) ? PACKAGE.replace('.', '/') + "/marker.txt" : symbolicName + ".txt");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(symbolicName);
                builder.addBundleManifestVersion(2);
                if (Boolean.TRUE.equals(exporter))
                    builder.addExportPackages(PACKAGE);
                if (Boolean.FALSE.equals(exporter))
                    builder.addImportPackages(PACKAGE);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = "takeover-exp", managed = false, testable = false)
    public static JavaArchive getExporter() {
        return bundle("takeover-exp.jar", "takeover-exp", Boolean.TRUE);
    }

    @Deployment(name = "takeover-imp", managed = false, testable = false)
    public static JavaArchive getImporter() {
        return bundle("takeover-imp.jar", "takeover-imp", Boolean.FALSE);
    }

    @Deployment(name = "takeover-other", managed = false, testable = false)
    public static JavaArchive getOther() {
        return bundle("takeover-other.jar", "takeover-other", null);
    }
}
