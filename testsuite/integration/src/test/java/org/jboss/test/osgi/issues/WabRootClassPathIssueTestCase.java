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
import org.jboss.shrinkwrap.api.asset.ClassAsset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * A WAB whose Bundle-ClassPath is the bundle root, and that has a WEB-INF/classes directory too, gets the web view of
 * WEB-INF/classes: its servlets at the root are not deployed, an annotated servlet in WEB-INF/classes that the bundle
 * cannot load fails the deployment.
 *
 * <h3>Severity</h3>
 * minor. An edge layout, e.g. a WAR repackaged as a bundle that keeps a WEB-INF/classes directory.
 *
 * <h3>Component</h3>
 * WildFly fork, undertow: {@code WarStructureDeploymentProcessor}.
 *
 * <h3>Found by</h3>
 * R8 fix review, residual of ee/05 (umb-a-T03).
 *
 * <h3>Spec reference</h3>
 * OSGi Enterprise R8 128.3.7: the Bundle-ClassPath of a WAB defines its web application classes.
 *
 * <h3>Code location</h3>
 * wildfly undertow/src/main/java/org/wildfly/extension/undertow/deployment/WarStructureDeploymentProcessor.java:99-108,
 * 191-196 (90639e0510).
 *
 * <h3>Root cause</h3>
 * WEB-INF/classes was always a resource root, the bundle root was not indexed when WEB-INF/classes existed.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi/testsuite/integration
 * mvn test -Dtest=WabRootClassPathIssueTestCase
 * </pre>
 *
 * <h3>Expected</h3>
 * The servlet at the root is deployed, the one in WEB-INF/classes is not.
 *
 * <h3>Regression vs WF41</h3>
 * no.
 */
@RunWith(Arquillian.class)
public class WabRootClassPathIssueTestCase {

    static final String WAB = "wab-root-bcp.war";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    ManagementClient managementClient;

    @WebServlet("/root")
    public static class RootServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().print("root-servlet");
        }
    }

    @WebServlet("/leftover")
    public static class LeftoverServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().print("leftover-servlet");
        }
    }

    @Deployment
    public static JavaArchive testDeployment() {
        final JavaArchive jar = ShrinkWrap.create(JavaArchive.class, "wab-root-classpath-tests");
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

    // Bundle-ClassPath: . with the servlet at the root, and a WEB-INF/classes directory that is not on it
    @Deployment(name = WAB, managed = false, testable = false)
    public static WebArchive getWab() {
        WebArchive war = ShrinkWrap.create(WebArchive.class, WAB);
        war.add(new StringAsset("static"), "static.txt");
        war.add(new ClassAsset(RootServlet.class), RootServlet.class.getName().replace('.', '/') + ".class");
        war.addClass(LeftoverServlet.class);
        war.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName("wab-root-bcp");
                builder.addBundleManifestVersion(2);
                builder.addBundleClasspath(".");
                builder.addManifestHeader("Web-ContextPath", "/wab-root-bcp");
                builder.addImportPackages("jakarta.servlet", "jakarta.servlet.http", "jakarta.servlet.annotation");
                return builder.openStream();
            }
        });
        return war;
    }

    @Test
    public void testServletAtBundleRoot() throws Exception {
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        String failure = null;
        try {
            server.deploy(WAB, deployer.getDeployment(WAB));
        } catch (Exception ex) {
            failure = String.valueOf(ex.getCause() != null ? ex.getCause() : ex);
        }
        try {
            Assert.assertNull("The WAB deploys, the servlet in WEB-INF/classes is not on its Bundle-ClassPath: " + failure, failure);
            Assert.assertEquals("The servlet at the bundle root is deployed", "200 root-servlet", get("/wab-root-bcp/root"));
            Assert.assertTrue("The servlet in WEB-INF/classes is not part of the web application", get("/wab-root-bcp/leftover").startsWith("404"));
        } finally {
            try {
                server.undeploy(WAB);
            } catch (Exception ex) {
                // not deployed
            }
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
}
