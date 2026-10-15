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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
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
import org.osgi.framework.ServiceReference;
import org.osgi.service.http.HttpContext;
import org.osgi.service.http.HttpService;
import org.osgi.service.http.NamespaceException;

/**
 * HttpService.registerServlet does not throw ServletException when the servlet's init method fails; the broken
 * registration stays in place and blocks the alias.
 *
 * <h2>Severity</h2>
 * major. The registering bundle believes the servlet is up (no exception), clients get errors, and the bundle can
 * never register a working servlet under the alias again (NamespaceException "mapping already exists") until it
 * unregisters the alias it thinks is broken.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/integration/http, {@code org.jboss.as.osgi.httpservice.HttpServiceImpl.registerInternal}.
 *
 * <h2>Found by</h2>
 * wildfly workstream, Compendium/Enterprise spec alignment of the Http Service (exploratory checks H5/H5b).
 *
 * <h2>Spec reference</h2>
 * OSGi Compendium R7 102.10.3.3 HttpService.registerServlet: "The Http Service will call the servlet's init method
 * before returning." ... "Throws ... ServletException - if the servlet's init method throws an exception, or the
 * given servlet object has already been registered at a different alias." and 102.2: "Servlet objects are initialized
 * by the Http Service when they are registered". https://docs.osgi.org/specification/osgi.cmpn/7.0.0/service.http.html
 *
 * <h2>Code location</h2>
 * wildfly/integration/http/src/main/java/org/jboss/as/osgi/httpservice/HttpServiceImpl.java:157-176: the servlet is
 * initialised by Undertow during {@code deploymentController.start()} ({@code wrapper.setForceInit(true)}); a failing
 * init does not make start() throw, and any exception would be turned into a NamespaceException (lines 167, 172), never
 * a ServletException; {@code registry.register(...)} (line 174) records the alias in any case.
 *
 * <h2>Root cause</h2>
 * The result of the servlet initialisation is not checked: Undertow logs the init failure and keeps the deployment,
 * and HttpServiceImpl registers the alias.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=HttpServletInitFailureIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository. The two -Darq... options start the managed server with port offset 500, so the test cannot connect to another server on 9990 (arquillian.xml has allowConnectingToRunningServer=true); they can be left out when no other server runs.)
 * <h2>Observed</h2>
 * <pre>
 * testInitFailureThrowsServletException: Expected registerServlet to throw ServletException when Servlet.init throws,
 *   but it returned normally
 * testAliasFreeAfterFailedInit: Expected the alias of a registration whose init failed to be free again, but registering
 *   a working servlet there failed: org.osgi.service.http.NamespaceException: JBAS011967: HttpService mapping already exists: /hinit-free
 * </pre>
 *
 * <h2>Expected</h2>
 * registerServlet throws ServletException (with the init exception as cause) and the alias is not registered.
 *
 * <h2>Reference behaviour</h2>
 * Not checked.
 *
 * <h2>Suggested fix (not applied)</h2>
 * Call {@code servlet.init(config)} explicitly (or check the Undertow ManagedServlet start result) before
 * registry.register; on failure stop/destroy the deployment controller and throw a ServletException.

 * Not verified with a patched build.
 *
 * <h2>Regression vs WF41</h2>
 * no (measured on a WF41 server built from origin/WF41 77f95be6 on the same patched WildFly 41.0.1, JDK 25: same
 * failure). The Http Service code (wildfly/integration/http, last changed in the WF30 port e4d06ba7) is unchanged on
 * the R8 branch. On WF41 the bundle jbosgi-wildfly-http 2.12.0.Final starts ("JBOSGI011002: Bundle started:
 * jbosgi-wildfly-http:2.12.0.Final", regression-2 srv-results/HttpServletInitFailureIssueTestCase.server.log:143): it
 * is module backed (org/jboss/as/osgi/http/main/module.xml), so its javax.servlet Import-Package does not stop it, and
 * the test reaches the defect there: registerServlet returns normally when Servlet.init throws and the alias stays
 * taken (JBAS011967), exactly as on R8. The earlier reasoning (the bundle imported javax.servlet, did not resolve on
 * WildFly 41 and the code path was unreachable) was wrong; the verdict "no" stands. The WF41 run used harness
 * adaptations only, no change to this test or to the Http Service code: the jacc {@code <policy>} removed from
 * standalone-osgi.xml (as r8 42d6c470 does), the r8 Arquillian JBOSGiApplicationArchiveProcessor (54b65e85), and a
 * provided dependency on jbosgi-wildfly-http in the testsuite/integration pom (as on r8) so the test compiles; see the
 * regression-2 workstream NOTES.md.
 */
@RunWith(Arquillian.class)
public class HttpServletInitFailureIssueTestCase {

    @ArquillianResource
    BundleContext context;

    @ArquillianResource
    Bundle bundle;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "http-init-failure-tests");
        archive.addAsResource(new StringAsset("content-a"), "www/a.txt");
        archive.addAsResource(new StringAsset("content-b"), "b.txt");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(HttpService.class, Servlet.class, HttpServlet.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    private HttpService httpService() {
        ServiceReference<HttpService> sref = context.getServiceReference(HttpService.class);
        Assert.assertNotNull("Precondition: HttpService available", sref);
        return context.getService(sref);
    }

    private static void unregisterQuietly(HttpService http, String... aliases) {
        for (String alias : aliases) {
            try {
                http.unregister(alias);
            } catch (RuntimeException ex) {
                // not registered
            }
        }
    }

    /** GET on the server's HTTP port (jboss.http.port + jboss.socket.binding.port-offset, default 8080); returns {status, body, content type} */
    private static String[] get(String path) throws IOException {
        int port = Integer.getInteger("jboss.http.port", 8080) + Integer.getInteger("jboss.socket.binding.port-offset", 0);
        HttpURLConnection con = (HttpURLConnection) new URL("http://localhost:" + port + path).openConnection();
        con.setConnectTimeout(5000);
        con.setReadTimeout(5000);
        int status = con.getResponseCode();
        InputStream in = status < 400 ? con.getInputStream() : con.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            in.transferTo(out);
            in.close();
        }
        String body = out.toString("UTF-8");
        return new String[] { String.valueOf(status), body.length() > 120 ? body.substring(0, 120) + "..." : body, String.valueOf(con.getContentType()) };
    }

    static class TextServlet extends HttpServlet {
        private final String text;
        TextServlet(String text) {
            this.text = text;
        }
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
            res.getWriter().print(text);
        }
    }

    /** An HttpContext that records its calls */
    static class RecordingContext implements HttpContext {
        final List<String> resources = Collections.synchronizedList(new ArrayList<String>());
        final List<String> mimeTypes = Collections.synchronizedList(new ArrayList<String>());
        final AtomicInteger securityCalls = new AtomicInteger();
        private final Bundle bundle;
        private final boolean allow;
        private final boolean noResources;
        RecordingContext(Bundle bundle, boolean allow, boolean noResources) {
            this.bundle = bundle;
            this.allow = allow;
            this.noResources = noResources;
        }
        @Override
        public boolean handleSecurity(HttpServletRequest request, HttpServletResponse response) throws IOException {
            securityCalls.incrementAndGet();
            if (!allow) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return false;
            }
            return true;
        }
        @Override
        public URL getResource(String name) {
            resources.add(name);
            return noResources ? null : bundle.getResource(name);
        }
        @Override
        public String getMimeType(String name) {
            mimeTypes.add(name);
            return "application/x-osgi-test";
        }
    }

    static class FailingServlet extends HttpServlet {
        @Override
        public void init(ServletConfig config) throws ServletException {
            throw new ServletException("init fails on purpose");
        }
    }

    @Test
    public void testInitFailureThrowsServletException() throws Exception {
        HttpService http = httpService();
        try {
            http.registerServlet("/hinit-throw", new FailingServlet(), null, null);
            Assert.fail("Expected registerServlet to throw ServletException when Servlet.init throws, but it returned normally");
        } catch (ServletException ex) {
            // expected
        } finally {
            unregisterQuietly(http, "/hinit-throw");
        }
    }

    @Test
    public void testAliasFreeAfterFailedInit() throws Exception {
        HttpService http = httpService();
        try {
            try {
                http.registerServlet("/hinit-free", new FailingServlet(), null, null);
            } catch (ServletException ex) {
                // expected, the alias must not be registered
            }
            try {
                http.registerServlet("/hinit-free", new TextServlet("working"), null, null);
            } catch (NamespaceException ex) {
                Assert.fail("Expected the alias of a registration whose init failed to be free again, but registering a working servlet there failed: " + ex);
            }
            String[] res = get("/hinit-free");
            Assert.assertEquals("Expected the working servlet to answer", "working", res[1]);
        } finally {
            unregisterQuietly(http, "/hinit-free");
        }
    }
}
