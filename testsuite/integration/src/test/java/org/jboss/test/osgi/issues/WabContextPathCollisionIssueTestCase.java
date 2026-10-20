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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper;
import org.jboss.dmr.ModelNode;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * A WAB whose Web-ContextPath is the context path of a deployed web application replaces the handler of that web
 * application; when the WAB is undeployed the web application answers 404.
 *
 * <h2>Severity</h2>
 * major. The WAR silently loses its requests to the WAB (only an INFO line), and after the WAB is undeployed it is
 * unreachable although it is still deployed. A WAR that is deployed on the context path of a WAB that started after
 * waiting for another WAB replaces the WAB the same way.
 *
 * <h2>Component</h2>
 * WildFly fork, undertow: {@code UndertowDeploymentProcessor} (90639e0510) and {@code Host.registerDeployment};
 * jbosgi (umbrella), wildfly/integration/web: {@code WebContextLifecycleInterceptor.deployContext} (f49fa4d9).
 *
 * <h2>Found by</h2>
 * R8 fix review, regression 8 (umb-a-T01, umb-b-05).
 *
 * <h2>Spec reference</h2>
 * OSGi Enterprise R7 128.3.2: the Web-ContextPath must not be the context path of any other currently deployed web
 * application, else the WAB is not deployed (FAILED).
 *
 * <h2>Code location</h2>
 * wildfly undertow/src/main/java/org/wildfly/extension/undertow/deployment/UndertowDeploymentProcessor.java:272-274;
 * jbosgi wildfly/integration/web/src/main/java/org/jboss/as/osgi/web/WebContextLifecycleInterceptor.java:144-172.
 *
 * <h2>Root cause</h2>
 * A WAB whose context path is used is installed without the service names of the host and context path, which were
 * the only guard against two web applications on one path; the collision check of the WABs only knows WABs. The host
 * replaced the handler of the path when the WAB's web context was started.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=WabContextPathCollisionIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * The web application that serves the path keeps it: the WAB, or the WAR that is deployed later, does not get it.
 *
 * <h2>Regression vs WF41</h2>
 * yes, introduced by the WildFly fork 90639e0510 with f49fa4d9.
 */
@RunWith(Arquillian.class)
public class WabContextPathCollisionIssueTestCase {

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    ManagementClient managementClient;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "wab-collision-tests");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(ManagementClient.class, ModelControllerClient.class, ClientConstants.class, ServerDeploymentHelper.class, ModelNode.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    static WebArchive war(String name, String contextPath, String content) {
        WebArchive war = ShrinkWrap.create(WebArchive.class, name);
        war.addAsWebResource(new StringAsset(content), "page.txt");
        war.addAsWebInfResource(new StringAsset("<jboss-web><context-root>" + contextPath + "</context-root></jboss-web>"), "jboss-web.xml");
        return war;
    }

    static WebArchive wab(final String name, final String contextPath, String content) {
        final WebArchive wab = ShrinkWrap.create(WebArchive.class, name);
        wab.add(new StringAsset(content), "page.txt");
        wab.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(name.substring(0, name.indexOf('.')));
                builder.addBundleManifestVersion(2);
                builder.addBundleClasspath("WEB-INF/classes");
                builder.addManifestHeader("Web-ContextPath", contextPath);
                return builder.openStream();
            }
        });
        return wab;
    }

    @Deployment(name = "war", managed = false, testable = false)
    public static WebArchive warOnPath() {
        return war("wabcol.war", "/wabcol", "from-war");
    }

    @Deployment(name = "wab", managed = false, testable = false)
    public static WebArchive wabOnWarPath() {
        return wab("wabcol-wab.jar", "/wabcol", "from-wab");
    }

    @Deployment(name = "wab-a", managed = false, testable = false)
    public static WebArchive wabA() {
        return wab("wabcola.jar", "/wabcolb", "from-wab-a");
    }

    @Deployment(name = "wab-b", managed = false, testable = false)
    public static WebArchive wabB() {
        return wab("wabcolb.jar", "/wabcolb", "from-wab-b");
    }

    @Deployment(name = "war-b", managed = false, testable = false)
    public static WebArchive warOnWabPath() {
        return war("wabcol-other.war", "/wabcolb", "from-war-b");
    }

    @Test
    public void testWabDoesNotTakeOverWarContext() throws Exception {
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        server.deploy("wabcol.war", deployer.getDeployment("war"));
        try {
            Assert.assertEquals("The WAR serves its path", "200 from-war", get("/wabcol/page.txt"));
            try {
                server.deploy("wabcol-wab.jar", deployer.getDeployment("wab"));
            } catch (Exception ex) {
                // The WAB fails, the deployment may fail with it
            }
            Assert.assertEquals("With the WAB deployed the WAR keeps its path", "200 from-war", get("/wabcol/page.txt"));
            undeployQuietly(server, "wabcol-wab.jar");
            Assert.assertEquals("After the WAB is undeployed the WAR keeps its path", "200 from-war", get("/wabcol/page.txt"));
        } finally {
            undeployQuietly(server, "wabcol-wab.jar");
            server.undeploy("wabcol.war");
        }
    }

    @Test
    public void testWarDoesNotTakeOverContextOfWaitingWab() throws Exception {
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        try {
            server.deploy("wabcola.jar", deployer.getDeployment("wab-a"));
            Assert.assertEquals("WAB A serves the path", "200 from-wab-a", get("/wabcolb/page.txt"));
            // WAB B waits for the path, it gets it when WAB A is undeployed
            server.deploy("wabcolb.jar", deployer.getDeployment("wab-b"));
            Assert.assertEquals("WAB A keeps the path", "200 from-wab-a", get("/wabcolb/page.txt"));
            server.undeploy("wabcola.jar");
            Assert.assertEquals("WAB B gets the path", "200 from-wab-b", awaitContent("/wabcolb/page.txt", "from-wab-b"));
            try {
                server.deploy("wabcol-other.war", deployer.getDeployment("war-b"));
            } catch (Exception ex) {
                // The WAR fails, the path is used
            }
            Assert.assertEquals("WAB B keeps the path when a WAR on the path is deployed", "200 from-wab-b", get("/wabcolb/page.txt"));
        } finally {
            undeployQuietly(server, "wabcol-other.war");
            undeployQuietly(server, "wabcolb.jar");
            undeployQuietly(server, "wabcola.jar");
        }
    }

    private static void undeployQuietly(ServerDeploymentHelper server, String name) {
        try {
            server.undeploy(name);
        } catch (Exception ex) {
            // not deployed
        }
    }

    private static String awaitContent(String path, String content) throws Exception {
        String result = get(path);
        long deadline = System.currentTimeMillis() + 10000;
        while (result.endsWith(content) == false && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            result = get(path);
        }
        return result;
    }

    // "status body" of a GET of the path
    private static String get(String path) throws IOException {
        int port = Integer.getInteger("jboss.http.port", 8080) + Integer.getInteger("jboss.socket.binding.port-offset", 0);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 5000);
            socket.setSoTimeout(15000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(buffer);
            String text = buffer.toString(StandardCharsets.UTF_8);
            int eol = text.indexOf("\r\n");
            String[] status = (eol > 0 ? text.substring(0, eol) : text).split(" ");
            int separator = text.indexOf("\r\n\r\n");
            String body = separator >= 0 ? text.substring(separator + 4).trim() : "";
            return (status.length > 1 ? status[1] : text) + " " + (body.length() > 100 ? body.substring(0, 100) : body);
        }
    }
}
