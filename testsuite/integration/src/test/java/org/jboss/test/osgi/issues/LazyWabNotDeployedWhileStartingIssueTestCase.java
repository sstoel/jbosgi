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
import org.osgi.framework.BundleContext;

/**
 * A Web Application Bundle with {@code Bundle-ActivationPolicy: lazy} is not deployed while it waits for its lazy
 * activation (bundle state STARTING): the deployment reports OK, the bundle is STARTING, but every request to its
 * context path answers 404. The web integration starts the web context only on the transition to ACTIVE.
 *
 * <h2>Severity</h2>
 * major. 128.3.2 makes a lazily started WAB ready and requires it to be served; serving static content must not even
 * activate it. A lazy WAB that serves only static content (or whose classes are only loaded by a request) is never
 * reachable: the 404 itself prevents the class load that would activate it. Lazy activation is the common policy for
 * bundles built with bnd/Eclipse PDE tooling.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/integration/web: {@code org.jboss.as.osgi.web.WebContextLifecycleInterceptor.invoke}
 * (starts the Undertow context in {@code case Bundle.ACTIVE} only) together with wildfly/subsystem
 * {@code BundleActivateProcessor} (starts deployed bundles with {@code START_ACTIVATION_POLICY}, which leaves a lazy
 * bundle in STARTING).
 *
 * <h2>Found by</h2>
 * wildfly-ee-2 workstream (deep 2), target "lazy activation of EE-using bundles": a lazy WAB with only static content
 * deployed through management: deployment OK, bundle STARTING (8), GET of the static file 404, no "WFLYUT0021:
 * Registered web context" in server.log. The same WAB without the lazy policy is served (200).
 *
 * <h2>Spec reference</h2>
 * OSGi Compendium R8 128.3.2 Starting the Web Application Bundle
 * (https://docs.osgi.org/specification/osgi.cmpn/8.0.0/service.war.html): "A WAB's Web Application must be deployed while
 * the WAB is ready. Deployed means that the Web Application is available for web requests. ... Ready is when the WAB: Is
 * in the ACTIVE state, or Has a lazy activation policy and is in the STARTING state. The Web Extender should ensure that
 * serving static content from the WAB does not activate the WAB when it has a lazy activation policy."
 *
 * <h2>Code location</h2>
 * wildfly/integration/web/src/main/java/org/jboss/as/osgi/web/WebContextLifecycleInterceptor.java:87-103
 * ({@code switch (state) { case Bundle.ACTIVE: activator.startContext() ... case Bundle.RESOLVED: stopContext }}: no
 * handling of a lazy STARTING bundle); wildfly/subsystem/src/main/java/org/jboss/as/osgi/deployment/BundleActivateProcessor.java:131
 * ({@code startBundle(bundle, Bundle.START_ACTIVATION_POLICY)}). Pre-existing (AS7 code, unchanged on WF41-osgi-r8).
 *
 * <h2>Root cause</h2>
 * The web context of a WAB is tied to the ACTIVE state through a lifecycle interceptor; a lazy WAB stays STARTING until a
 * class is loaded from it, so the context is never started and nothing can trigger the class load.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=LazyWabNotDeployedWhileStartingIssueTestCase \
 *     -Darq.container.jboss.configuration.managementPort=11090 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=1100
 * </pre>
 * (Prerequisites: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork in the local Maven
 * repository; on JDK 24+ the testsuite needs surefire.security.args without -Djava.security.manager, see
 * TestJvmSecurityManagerIssueTestCase.) Deterministic.
 *
 * <h2>Observed</h2>
 * <pre>
 * java.lang.AssertionError: Expected the lazy WAB (bundle state 8 = STARTING, deployment OK) to serve its static
 *   content, but GET /lazy-static-wab/static.txt answered: 404 &lt;html&gt;&lt;head&gt;&lt;title&gt;Error&lt;/title&gt;...404 - Not Found...
 * </pre>
 *
 * <h2>Expected</h2>
 * 200 with the static content while the bundle is STARTING (and preferably still STARTING afterwards).
 *
 * <h2>Reference behaviour</h2>
 * The same WAB without Bundle-ActivationPolicy is served right after the deployment (precondition of the test). A WAB with
 * classes and the lazy policy is ACTIVE right after the deployment but also 404: the EE deployment processors load its
 * classes, which activates it before the context activator is attached (the "missed ACTIVE transition" root cause of
 * lifecycle/03 WabNotServedAfterRestartIssueTestCase, see the note there).
 *
 * <h2>Suggested fix (not applied)</h2>
 * Start the web context when the WAB becomes ready: also when a bundle with lazy activation policy enters STARTING
 * (LAZY_ACTIVATION event / lifecycle interceptor state STARTING with the lazy policy in use), and when the context activator
 * is attached to a bundle that is already ready (ACTIVE or lazy STARTING; see lifecycle/03). Serve static content
 * without loading bundle classes, so that the WAB is not activated by it.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing).
 */
@RunWith(Arquillian.class)
public class LazyWabNotDeployedWhileStartingIssueTestCase {

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @ArquillianResource
    ManagementClient managementClient;

    @Deployment
    public static JavaArchive testDeployment() {
        final JavaArchive jar = ShrinkWrap.create(JavaArchive.class, "lazy-wab-tests");
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

    static WebArchive wab(final String name, final boolean lazy) {
        final WebArchive war = ShrinkWrap.create(WebArchive.class, name + ".wab");
        war.add(new StringAsset("static content"), "static.txt");
        war.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(name);
                builder.addBundleManifestVersion(2);
                builder.addBundleClasspath("WEB-INF/classes");
                if (lazy)
                    builder.addBundleActivationPolicy("lazy");
                builder.addManifestHeader("Web-ContextPath", "/" + name);
                return builder.openStream();
            }
        });
        return war;
    }

    @Deployment(name = "lazy-wab", managed = false, testable = false)
    public static WebArchive lazyWab() {
        return wab("lazy-static-wab", true);
    }

    @Deployment(name = "eager-wab", managed = false, testable = false)
    public static WebArchive eagerWab() {
        return wab("eager-static-wab", false);
    }

    @Test
    public void testLazyWabServedWhileStarting() throws Exception {
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        // Control: without the lazy policy the WAB is served
        server.deploy("eager-static-wab.wab", deployer.getDeployment("eager-wab"));
        try {
            Assert.assertEquals("Precondition: the WAB without lazy policy is served", "200 static content", get("/eager-static-wab/static.txt"));
        } finally {
            server.undeploy("eager-static-wab.wab");
        }

        server.deploy("lazy-static-wab.wab", deployer.getDeployment("lazy-wab"));
        try {
            int state = bundleState("lazy-static-wab");
            String response = get("/lazy-static-wab/static.txt");
            Assert.assertEquals("Expected the lazy WAB (bundle state " + state + " = STARTING, deployment OK) to serve its static content, but GET /lazy-static-wab/static.txt answered: "
                    + response, "200 static content", response);
        } finally {
            server.undeploy("lazy-static-wab.wab");
        }
    }

    private int bundleState(String symbolicName) {
        for (Bundle b : context.getBundles())
            if (symbolicName.equals(b.getSymbolicName()))
                return b.getState();
        return -1;
    }

    private static String get(String path) throws IOException {
        int port = Integer.getInteger("jboss.http.port", 8080) + Integer.getInteger("jboss.socket.binding.port-offset", 0);
        HttpURLConnection con = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        con.setConnectTimeout(5000);
        con.setReadTimeout(10000);
        int status = con.getResponseCode();
        InputStream in = status < 400 ? con.getInputStream() : con.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            in.transferTo(out);
            in.close();
        }
        String body = out.toString("UTF-8");
        return status + " " + (body.length() > 100 ? body.substring(0, 100) + "..." : body);
    }
}
