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

import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServlet;

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
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.http.HttpService;

/**
 * The fallback of an Http Service resource registration to the next shorter alias forwards the request to a web
 * application with the context path of that alias on another virtual host.
 *
 * <h2>Severity</h2>
 * critical (security). The forward reaches the web application of the other host without its security constraints,
 * which apply to the original request only, and without the WEB-INF check, which applies to REQUEST dispatches only:
 * without the Elytron default domain its protected content and its WEB-INF/web.xml are served on the Http Service's
 * host. With the default configuration the forward fails with a 500, which also breaks the legitimate fallback.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/integration/http: {@code ResourceServlet.dispatchToShorterAlias}.
 *
 * <h2>Found by</h2>
 * R8 fix review, regression 1 (umb-a-U05, umb-b-06).
 *
 * <h2>Spec reference</h2>
 * OSGi Compendium R7 102.4 Mapping HTTP Requests to Servlet and Resource Registrations: a request that the longest
 * matching alias cannot serve goes to the registration of the next shorter alias, of the same Http Service.
 *
 * <h2>Code location</h2>
 * wildfly/integration/http/src/main/java/org/jboss/as/osgi/httpservice/ResourceServlet.java:142-155 (f49fa4d9).
 *
 * <h2>Root cause</h2>
 * The context of the shorter alias was looked up with {@code ServletContext.getContext(alias)}, which Undertow resolves
 * by context path over all virtual hosts.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=HttpAliasFallbackOtherHostIssueTestCase
 * </pre>
 * The aliases /hfb (resources www/), /hfb/static and /hfb/WEB-INF (no resources) on default-host; a host hfb-host
 * (alias hfb.example) with a WAR whose context path is /hfb.
 *
 * <h2>Expected</h2>
 * The fallback of /hfb/static serves www/static/a.txt of /hfb, the WAR content and its WEB-INF/web.xml are not served
 * on default-host.
 *
 * <h2>Regression vs WF41</h2>
 * yes, introduced by f49fa4d9.
 */
@RunWith(Arquillian.class)
public class HttpAliasFallbackOtherHostIssueTestCase {

    static final String HOST = "hfb-host";
    static final String HOST_ALIAS = "hfb.example";

    @ArquillianResource
    Bundle bundle;

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    ManagementClient managementClient;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "http-alias-fallback-host-tests");
        archive.addAsResource(new StringAsset("content-a"), "www/a.txt");
        archive.addAsResource(new StringAsset("content-fallback"), "www/static/a.txt");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(HttpService.class, Servlet.class, HttpServlet.class);
                builder.addImportPackages(ManagementClient.class, ModelControllerClient.class, ClientConstants.class, ServerDeploymentHelper.class, ModelNode.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = "secured-war", managed = false, testable = false)
    public static WebArchive securedWar() {
        return war("hfb-secured.war", true);
    }

    @Deployment(name = "open-war", managed = false, testable = false)
    public static WebArchive openWar() {
        return war("hfb-open.war", false);
    }

    static WebArchive war(String name, boolean secured) {
        WebArchive war = ShrinkWrap.create(WebArchive.class, name);
        war.addAsWebResource(new StringAsset("hfb-secret"), "secret.txt");
        war.addAsWebResource(new StringAsset("hfb-secret-static"), "static/secret.txt");
        String constraint = !secured ? "" : "  <security-constraint><web-resource-collection><web-resource-name>all</web-resource-name><url-pattern>/*</url-pattern></web-resource-collection>\n"
                + "    <auth-constraint><role-name>admin</role-name></auth-constraint></security-constraint>\n"
                + "  <login-config><auth-method>BASIC</auth-method></login-config>\n"
                + "  <security-role><role-name>admin</role-name></security-role>\n";
        war.addAsWebInfResource(new StringAsset("<?xml version=\"1.0\"?>\n<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.0\">\n" + constraint
                + "  <!-- hfb-web-xml-marker -->\n</web-app>\n"), "web.xml");
        war.addAsWebInfResource(new StringAsset("<jboss-web><context-root>/hfb</context-root><virtual-host>" + HOST + "</virtual-host></jboss-web>"), "jboss-web.xml");
        return war;
    }

    @Test
    public void testFallbackDoesNotReachSecuredWarOfOtherHost() throws Exception {
        assertFallback("hfb-secured.war", "secured-war", true);
    }

    @Test
    public void testFallbackDoesNotReachOpenWarOfOtherHost() throws Exception {
        assertFallback("hfb-open.war", "open-war", false);
    }

    private void assertFallback(String warName, String deploymentName, boolean secured) throws Exception {
        BundleContext context = bundle.getBundleContext();
        ServiceReference<HttpService> sref = context.getServiceReference(HttpService.class);
        Assert.assertNotNull("HttpService available", sref);
        HttpService http = context.getService(sref);
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        http.registerResources("/hfb", "/www", null);
        http.registerResources("/hfb/static", "/static", null);
        http.registerResources("/hfb/WEB-INF", "/nothing", null);
        boolean hostAdded = false;
        boolean warDeployed = false;
        try {
            Assert.assertEquals("GET /hfb/a.txt", "200 content-a", get("localhost", "/hfb/a.txt"));
            Assert.assertEquals("The fallback of /hfb/static serves www/static/a.txt of /hfb", "200 content-fallback", get("localhost", "/hfb/static/a.txt"));

            ModelNode add = new ModelNode();
            add.get(ClientConstants.OP).set(ClientConstants.ADD);
            add.get(ClientConstants.OP_ADDR).set(hostAddress());
            add.get("alias").add(HOST_ALIAS);
            ModelNode result = managementClient.getControllerClient().execute(add);
            Assert.assertEquals("Host added: " + result, "success", result.get("outcome").asString());
            hostAdded = true;

            server.deploy(warName, deployer.getDeployment(deploymentName));
            warDeployed = true;
            String direct = get(HOST_ALIAS, "/hfb/secret.txt");
            Assert.assertTrue("The WAR answers on its host (401 when secured): " + direct, secured ? direct.startsWith("401") : direct.startsWith("200"));

            Assert.assertEquals("The fallback still serves the alias content", "200 content-fallback", get("localhost", "/hfb/static/a.txt"));
            String secret = get("localhost", "/hfb/static/secret.txt");
            Assert.assertFalse("The protected content of the WAR on " + HOST + " is not served on default-host: " + secret, secret.contains("hfb-secret"));
            Assert.assertTrue("The alias has no such resource: " + secret, secret.startsWith("404"));
            String webxml = get("localhost", "/hfb/WEB-INF/web.xml");
            Assert.assertFalse("The WEB-INF/web.xml of the WAR on " + HOST + " is not served on default-host: " + webxml, webxml.contains("hfb-web-xml-marker"));
        } finally {
            if (warDeployed)
                server.undeploy(warName);
            if (hostAdded) {
                ModelNode remove = new ModelNode();
                remove.get(ClientConstants.OP).set(ClientConstants.REMOVE_OPERATION);
                remove.get(ClientConstants.OP_ADDR).set(hostAddress());
                remove.get(ClientConstants.OPERATION_HEADERS).get("allow-resource-service-restart").set(true);
                managementClient.getControllerClient().execute(remove);
            }
            http.unregister("/hfb/static");
            http.unregister("/hfb/WEB-INF");
            http.unregister("/hfb");
            context.ungetService(sref);
        }
    }

    private static ModelNode hostAddress() {
        ModelNode address = new ModelNode();
        address.add("subsystem", "undertow");
        address.add("server", "default-server");
        address.add("host", HOST);
        return address;
    }

    // "status body" of a GET with the given Host header, HttpURLConnection does not let us choose the virtual host
    private static String get(String host, String path) throws IOException {
        int port = Integer.getInteger("jboss.http.port", 8080) + Integer.getInteger("jboss.socket.binding.port-offset", 0);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 5000);
            socket.setSoTimeout(15000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.0\r\nHost: " + host + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(buffer);
            String text = buffer.toString(StandardCharsets.UTF_8);
            int eol = text.indexOf("\r\n");
            String[] status = (eol > 0 ? text.substring(0, eol) : text).split(" ");
            int separator = text.indexOf("\r\n\r\n");
            String body = separator >= 0 ? text.substring(separator + 4).trim() : "";
            return (status.length > 1 ? status[1] : text) + " " + (body.length() > 200 ? body.substring(0, 200) : body);
        }
    }
}
