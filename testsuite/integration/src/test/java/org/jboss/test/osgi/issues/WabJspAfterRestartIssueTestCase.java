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
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

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
 * After Bundle.stop() and Bundle.start() of a Web Application Bundle every JSP that had been compiled before the stop
 * fails with HTTP 500 on every request ("JBWEB005015: The JSP container needs a valid work directory",
 * JasperException: ClassNotFoundException org.apache.jsp.index_jsp): stopping the WAB deletes the web application's
 * temp directory (the JSP work directory with the compiled JSP classes), starting it again does not recreate it, and
 * Jasper still believes the JSPs are compiled.
 *
 * <h2>Severity</h2>
 * major. Restarting a WAB is the normal OSGi way to restart a web application (consoles, provisioning, a bundle update
 * of a dependency, start-level changes). Afterwards every JSP that users had already requested returns 500 until the
 * deployment is redeployed through management (a further stop/start only moves the failure to the JSPs compiled in the
 * meantime); JSPs first requested after the restart, servlets and static content work, so the failure is easy to miss. Everything else that uses the
 * servlet temp directory (jakarta.servlet.context.tempdir, multipart uploads without an explicit location) is affected
 * the same way.
 *
 * <h2>Component</h2>
 * RTR WildFly fork (41.0.1.Final-osgi), undertow:
 * {@code org.wildfly.extension.undertow.deployment.UndertowDeploymentService} ({@code stopContext} /
 * {@code startContext}, driven for WABs by {@code ContextActivatorImpl}); jbosgi wildfly/integration/web
 * (WebContextLifecycleInterceptor, start/stop of the WAB context).
 *
 * <h2>Found by</h2>
 * wildfly-ee workstream (deep 2), WAB feature exploration (servlet/filter/listener annotations, async servlet, CDI,
 * JAX-RS, JSP, web.xml with metadata-complete, fragments, static content) including a stop/start cycle, on the patched
 * WildFly 41.0.1 server (JDK 25). Everything else in that exploration works after the restart.
 *
 * <h2>Spec reference</h2>
 * OSGi Enterprise R7, 128.3.8 Stopping the Web Application Bundle: "A web application is stopped by stopping the
 * corresponding WAB. In response to a WAB STOPPING event, the Web Extender must undeploy the corresponding Web
 * Application ..."; 128.3.2 Starting the Web Application Bundle: "A WAB's Web Application must be deployed while the
 * WAB is ready. Deployed means that the Web Application is available for web requests. ... Ready is when the WAB: Is in
 * the ACTIVE state, or Has a lazy activation policy and is in the STARTING state." (a restarted WAB is ready again);
 * 128.6.5: JSP is supported when available in the Web Runtime (it is: the same JSP works before the restart). A
 * restarted WAB must behave like a newly started one.
 * https://docs.osgi.org/specification/osgi.enterprise/7.0.0/service.war.html
 *
 * <h2>Code location</h2>
 * WildFly fork undertow/src/main/java/org/wildfly/extension/undertow/deployment/UndertowDeploymentService.java:132-151
 * ({@code stopContext}: {@code recursiveDelete(deploymentInfo.getTempDir())}) and :83-113 ({@code startContext}: reuses the
 * same DeploymentInfo, whose temp directory was created once by the deployment unit processors and is not recreated);
 * :189-208 ({@code ContextActivatorImpl.startContext/stopContext}, added by the fork for OSGi web applications). For a
 * plain WAR, stop is always followed by a new deployment (new DeploymentInfo and temp directory), so only WABs restart the
 * same context.
 *
 * <h2>Root cause</h2>
 * The fork reuses the stop/start methods of a WAR deployment service for the OSGi start/stop of a WAB, but stop removes a
 * resource (the temp/JSP work directory) that start does not create.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=WabJspAfterRestartIssueTestCase \
 *     -Darq.container.jboss.configuration.managementPort=11090 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=1100
 * </pre>
 * (Prerequisites: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork in the local Maven
 * repository; on JDK 24+ the testsuite needs surefire.security.args without -Djava.security.manager, see
 * TestJvmSecurityManagerIssueTestCase.) Deterministic. The HTTP port is jboss.http.port + jboss.socket.binding.port-offset.
 *
 * <h2>Observed</h2>
 * <pre>
 * java.lang.AssertionError: After Bundle.stop() + Bundle.start() of the WAB its JSP must work as before, but:
 *   [/wab-jsp/index.jsp (compiled before the restart) returned 500 ERROR Error processing request Context Path: /wab-jsp
 *   Servlet Path: /index.jsp ... org.apache.jasper.JasperException: java.lang.ClassNotFoundException: org...., the second
 *   request returned 500 ...]
 * exploration: after the restart other.jsp and third.jsp (never requested before) return 200; index.jsp keeps returning
 *   500; after a second stop/start index.jsp works again and third.jsp (compiled in between) returns 500
 * server.log: ERROR [org.apache.jasper] JBWEB005015: The JSP container needs a valid work directory
 *   [.../standalone/tmp/wab-jsp.war]
 *   ERROR [io.undertow.request] UT005023: Exception handling request to /wab-jsp/index.jsp:
 *   org.apache.jasper.JasperException: java.lang.ClassNotFoundException: org.apache.jsp.index_jsp
 * </pre>
 *
 * <h2>Expected</h2>
 * index.jsp returns 200 with its content after the restart, as before it.
 *
 * <h2>Reference behaviour</h2>
 * Not applicable (Equinox/Felix have no WildFly web container). In WildFly a redeploy of the same WAB through
 * management works, because it creates a new DeploymentInfo with a new temp directory.
 *
 * <h2>Suggested fix (not applied)</h2>
 * In UndertowDeploymentService.startContext recreate the temp directory ({@code deploymentInfo.getTempDir().mkdirs()})
 * before deploying, or only delete it in the MSC stop (deployment removal) and not in the context stop used by
 * ContextActivatorImpl.stopContext.
 *
 * <h2>Regression vs WF41</h2>
 * no (measured on a WF41 server built from origin/WF41 77f95be6 on the same patched WildFly 41.0.1, JDK 25: same
 * failure). The WF41 run used harness adaptations only, no change to this test or to the code under test: the jacc
 * {@code <policy>} removed from standalone-osgi.xml, as r8 42d6c470 does (without it org.wildfly.security.policy fails
 * on WildFly 41 and every deferred start fails with JBAS011969), and the r8 Arquillian
 * JBOSGiApplicationArchiveProcessor from 54b65e85 (in-container ManagementClient lookup); see the regression-2
 * workstream NOTES.md.
 */
@RunWith(Arquillian.class)
public class WabJspAfterRestartIssueTestCase {

    static final String WAB = "wab-jsp.war";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @ArquillianResource
    ManagementClient managementClient;

    @Deployment
    public static JavaArchive testDeployment() {
        final JavaArchive jar = ShrinkWrap.create(JavaArchive.class, "wab-jsp-restart-tests");
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
    public void testJspAfterWabRestart() throws Exception {
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        server.deploy(WAB, deployer.getDeployment(WAB));
        try {
            Bundle wab = null;
            for (Bundle bundle : context.getBundles()) {
                if ("wab-jsp".equals(bundle.getSymbolicName()))
                    wab = bundle;
            }
            Assert.assertNotNull("Precondition: WAB installed", wab);
            Assert.assertEquals("Precondition: the JSP works after the deployment", "200 index-jsp", get("/wab-jsp/index.jsp"));

            wab.stop();
            Assert.assertTrue("Precondition: the web application is undeployed when the WAB is stopped", get("/wab-jsp/index.jsp").startsWith("404"));
            wab.start();
            Assert.assertEquals("Precondition: the WAB is ACTIVE again", Bundle.ACTIVE, wab.getState());

            List<String> failures = new ArrayList<String>();
            String index = get("/wab-jsp/index.jsp");
            if (!"200 index-jsp".equals(index))
                failures.add("/wab-jsp/index.jsp (compiled before the restart) returned " + index);
            index = get("/wab-jsp/index.jsp");
            if (!"200 index-jsp".equals(index))
                failures.add("the second request returned " + index);
            Assert.assertTrue("After Bundle.stop() + Bundle.start() of the WAB its JSP must work as before, but: " + failures, failures.isEmpty());
        } finally {
            server.undeploy(WAB);
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

    @Deployment(name = WAB, managed = false, testable = false)
    public static WebArchive getWab() {
        final WebArchive war = ShrinkWrap.create(WebArchive.class, WAB);
        war.add(new StringAsset("<%= \"index-jsp\" %>"), "index.jsp");
        war.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName("wab-jsp");
                builder.addBundleManifestVersion(2);
                builder.addBundleClasspath("WEB-INF/classes");
                builder.addManifestHeader("Web-ContextPath", "/wab-jsp");
                builder.addImportPackages("jakarta.servlet", "jakarta.servlet.http");
                return builder.openStream();
            }
        });
        return war;
    }
}
