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
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.sql.DataSource;

import jakarta.annotation.Resource;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper;
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

/**
 * After Bundle.update() of an ACTIVE bundle that was deployed to the server, the EE part of the new revision is never
 * activated: a WAB is ACTIVE but its web application is not deployed (HTTP 404) and a BundleActivator that uses
 * resource injection is started without its injections, until somebody calls FrameworkWiring.refreshBundles.
 *
 * <h2>Severity</h2>
 * major. Bundle.update is the standard way to update a bundle (consoles, provisioning, Felix FileInstall-like tools). A
 * WAB disappears after every update although the framework reports it ACTIVE; activators, EJBs and other EE components
 * of the updated revision are not created by the EE container (injection fields null, NullPointerException in user
 * code). The testsuite already contains this scenario as an ignored test:
 * BundleUpdateTestCase.testWebAppBundleUpdate {@code @Ignore("[AS7-6944] Cannot update OSGi WebApp")}.
 *
 * <h2>Component</h2>
 * jbosgi-framework core {@code org.jboss.osgi.framework.internal.UserBundleState.updateInternal} (restarts the updated
 * bundle with {@code BundleManagerPlugin.startBundle}, bypassing the BundleLifecycle plugin); jbosgi (umbrella)
 * wildfly/subsystem {@code BundleLifecycleIntegration.BundleLifecycleImpl.start} (the only place that activates the
 * deferred module phase of a bundle deployment).
 *
 * <h2>Found by</h2>
 * wildfly-ee workstream (deep 2), WAB exploration (update of a WAB installed with installBundle and of a WAB deployed
 * through management) on the patched WildFly 41.0.1 server (JDK 25). server.log after the update: "WFLYSRV0069: Defer
 * FIRST_MODULE_USE for upd-wab-rev1.war making it NEVER", "JBOSGI011002: Bundle started: upd-wab:1.0.1", but no
 * "JBAS011916: Activate deferred module phase" and no "WFLYUT0021: Registered web context"; after refreshBundles the
 * deferred phase is activated and the context is registered.
 *
 * <h2>Spec reference</h2>
 * OSGi Enterprise R7, 128.3.2 Starting the Web Application Bundle: "A WAB's Web Application must be deployed while the
 * WAB is ready. Deployed means that the Web Application is available for web requests. ... Ready is when the WAB: Is in
 * the ACTIVE state, or Has a lazy activation policy and is in the STARTING state." (an update stops and restarts the
 * WAB, 128.3.8 / Core Bundle.update.)
 * Core R8 Bundle.update() javadoc, step 6: "If this bundle's state was originally ACTIVE, the updated bundle is started
 * as described in the Bundle.start method. If Bundle.start throws an exception, a Framework event of type
 * FrameworkEvent.ERROR is fired containing the exception." - i.e. like any other start, which in WildFly activates the
 * deferred module phase. Documented jbosgi behaviour: resource injection into activators (User Guide).
 * https://docs.osgi.org/specification/osgi.enterprise/7.0.0/service.war.html#i1771814
 *
 * <h2>Code location</h2>
 * jbosgi-framework core/src/main/java/org/jboss/osgi/framework/internal/UserBundleState.java:301-309 (updateInternal:
 * {@code getBundleManager().startBundle(this, Bundle.START_TRANSIENT)} instead of the lifecycle start used by
 * Bundle.start, AbstractBundleState.startWithOptions -&gt; BundleManagerPlugin.startBundleLifecycle);
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/BundleLifecycleIntegration.java:293-329 (start: activates the
 * deferred phase of the new revision's deployment, never called for the update restart). Pre-existing (same code on
 * origin/WF41, UserBundleState.java:294/305).
 *
 * <h2>Root cause</h2>
 * The restart that is part of Bundle.update goes around the integration's BundleLifecycle.start, so the server deployment
 * of the updated revision ("&lt;name&gt;-rev1") stays in its deferred state (FIRST_MODULE_USE NEVER): no INSTALL phase,
 * no web context, no EE components; the framework starts the bundle as a plain bundle.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=UpdatedBundleEeContentNotActivatedIssueTestCase \
 *     -Darq.container.jboss.configuration.managementPort=11090 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=1100
 * </pre>
 * (Prerequisites: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork in the local Maven
 * repository; on JDK 24+ the testsuite needs surefire.security.args without -Djava.security.manager, see
 * TestJvmSecurityManagerIssueTestCase.) Deterministic. The HTTP port is jboss.http.port + jboss.socket.binding.port-offset.
 *
 * <h2>Observed</h2>
 * <pre>
 * testWabServedAfterUpdate: org.junit.ComparisonFailure: After Bundle.update the ACTIVE WAB must serve the new
 *   revision expected:&lt;[200 servlet-v2]&gt; but was:&lt;[404 Error 404 - Not Found]&gt;
 * testInjectedActivatorAfterUpdate: org.junit.ComparisonFailure: After Bundle.update the activator of the new revision
 *   must get its @Resource DataSource injected expected:&lt;[true]&gt; but was:&lt;[false]&gt;
 * </pre>
 * On some runs testInjectedActivatorAfterUpdate fails earlier, at its precondition ("Precondition: the updated bundle
 * is ACTIVE expected:&lt;32&gt; but was:&lt;4&gt;", see the issue's run.log): the updated bundle is still RESOLVED;
 * which of the two assertions fails is timing dependent, and the method fails either way (on WF41 too).
 *
 * <h2>Expected</h2>
 * The update restarts the bundle through the same path as Bundle.start: the new revision's deferred phase is activated,
 * the WAB serves version 2, the activator of the new revision is injected.
 *
 * <h2>Reference behaviour</h2>
 * After FrameworkWiring.refreshBundles the same bundles work (refresh redeploys and starts through the lifecycle), and a
 * management redeploy works; Equinox/Felix with a Web Extender (Pax Web) redeploy a WAB on update.
 *
 * <h2>Suggested fix (not applied)</h2>
 * In UserBundleState.updateInternal stop and restart through the BundleLifecycle plugin (as Bundle.stop/start do:
 * BundleManagerPlugin.stopBundleLifecycle/startBundleLifecycle), so that BundleLifecycleIntegration activates the deferred
 * phase of the new revision's deployment. That alone is not enough: trial (framework only, reverted; issue folder
 * trial09-test.log, trial09-server.log): with the restart through startBundleLifecycle the deferred phase of
 * "updated-wab-rev1.war" is activated but fails with "WFLYUT0105: Host and context path are occupied ...
 * default-host./updated-wab.UndertowDeploymentInfoService is already registered" and the bundle ends RESOLVED, because the
 * deployment of the old revision keeps its EE services (web context, and likewise EJB/JNDI names) until a refresh. The
 * EE part of the old revision's deployment also has to be deactivated when the bundle is stopped for the update.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing; AS7-6944).
 */
@RunWith(Arquillian.class)
public class UpdatedBundleEeContentNotActivatedIssueTestCase {

    static final String INJECTED = "updated-bundle-ee.injected";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @ArquillianResource
    ManagementClient managementClient;

    @WebServlet("/version")
    public static class VersionServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            try (InputStream in = getClass().getResourceAsStream("version.txt")) {
                resp.getWriter().print("servlet-" + (in == null ? "none" : new String(in.readAllBytes()).trim()));
            }
        }
    }

    public static class InjectedActivator implements BundleActivator {
        @Resource(lookup = "java:jboss/datasources/ExampleDS")
        DataSource ds;

        @Override
        public void start(BundleContext context) throws Exception {
            System.setProperty(INJECTED, String.valueOf(ds != null));
        }

        @Override
        public void stop(BundleContext context) throws Exception {
        }
    }

    @Deployment
    public static JavaArchive testDeployment() {
        final JavaArchive jar = ShrinkWrap.create(JavaArchive.class, "updated-bundle-ee-tests");
        jar.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(jar.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(ManagementClient.class, ModelControllerClient.class, ServerDeploymentHelper.class);
                return builder.openStream();
            }
        });
        return jar;
    }

    @Test
    public void testWabServedAfterUpdate() throws Exception {
        Bundle wab = context.installBundle("updated-wab.war", deployer.getDeployment("wab-v1"));
        try {
            wab.start();
            Assert.assertEquals("Precondition: version 1 is served", "200 servlet-v1", get("/updated-wab/version"));
            wab.update(deployer.getDeployment("wab-v2"));
            Assert.assertEquals("Precondition: the updated WAB is ACTIVE", Bundle.ACTIVE, wab.getState());
            Assert.assertEquals("After Bundle.update the ACTIVE WAB must serve the new revision", "200 servlet-v2", get("/updated-wab/version"));
        } finally {
            wab.uninstall();
        }
    }

    @Test
    public void testInjectedActivatorAfterUpdate() throws Exception {
        System.clearProperty(INJECTED);
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        server.deploy("updated-activator.jar", deployer.getDeployment("activator-v1"));
        Bundle bundle = null;
        try {
            for (Bundle aux : context.getBundles()) {
                if ("updated-activator".equals(aux.getSymbolicName()))
                    bundle = aux;
            }
            Assert.assertEquals("Precondition: the deployed activator gets the DataSource", "true", System.getProperty(INJECTED));
            System.clearProperty(INJECTED);
            bundle.update(deployer.getDeployment("activator-v2"));
            Assert.assertEquals("Precondition: the updated bundle is ACTIVE", Bundle.ACTIVE, bundle.getState());
            Assert.assertEquals("After Bundle.update the activator of the new revision must get its @Resource DataSource injected", "true", System.getProperty(INJECTED));
        } finally {
            System.clearProperty(INJECTED);
            server.undeploy("updated-activator.jar");
            // the update created a deployment for the new revision, see BundleUsedByWarUndeployRollbackIssueTestCase
            for (String name : new String[] { "updated-activator-rev1.jar" }) {
                try {
                    server.undeploy(name);
                } catch (Exception ex) {
                    // not there
                }
            }
            if (bundle != null && bundle.getState() != Bundle.UNINSTALLED)
                bundle.uninstall();
        }
    }

    private static String get(String path) throws Exception {
        int port = Integer.getInteger("jboss.http.port", 8080) + Integer.getInteger("jboss.socket.binding.port-offset", 0);
        HttpURLConnection con = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        con.setConnectTimeout(5000);
        con.setReadTimeout(10000);
        int code = con.getResponseCode();
        InputStream in = code < 400 ? con.getInputStream() : con.getErrorStream();
        String body = "";
        if (in != null) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            in.close();
            body = out.toString().replaceAll("(?s)<style>.*?</style>", "").replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        }
        return code + " " + (body.length() > 200 ? body.substring(0, 200) + "..." : body);
    }

    private static WebArchive wab(final String version) {
        final WebArchive war = ShrinkWrap.create(WebArchive.class, "updated-wab-" + version + ".war");
        war.addClasses(VersionServlet.class);
        war.addAsResource(new StringAsset(version), "org/jboss/test/osgi/issues/version.txt");
        war.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName("updated-wab");
                builder.addBundleVersion(version.equals("v1") ? "1.0.0" : "2.0.0");
                builder.addBundleManifestVersion(2);
                builder.addBundleClasspath("WEB-INF/classes");
                builder.addManifestHeader("Web-ContextPath", "/updated-wab");
                builder.addImportPackages("jakarta.servlet", "jakarta.servlet.http", "jakarta.servlet.annotation");
                return builder.openStream();
            }
        });
        return war;
    }

    private static JavaArchive activatorBundle(final String version) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "updated-activator-" + version + ".jar");
        archive.addClasses(InjectedActivator.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName("updated-activator");
                builder.addBundleVersion(version.equals("v1") ? "1.0.0" : "2.0.0");
                builder.addBundleManifestVersion(2);
                builder.addBundleActivator(InjectedActivator.class);
                builder.addImportPackages(BundleActivator.class, DataSource.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = "wab-v1", managed = false, testable = false)
    public static WebArchive getWabV1() {
        return wab("v1");
    }

    @Deployment(name = "wab-v2", managed = false, testable = false)
    public static WebArchive getWabV2() {
        return wab("v2");
    }

    @Deployment(name = "activator-v1", managed = false, testable = false)
    public static JavaArchive getActivatorV1() {
        return activatorBundle("v1");
    }

    @Deployment(name = "activator-v2", managed = false, testable = false)
    public static JavaArchive getActivatorV2() {
        return activatorBundle("v2");
    }
}
