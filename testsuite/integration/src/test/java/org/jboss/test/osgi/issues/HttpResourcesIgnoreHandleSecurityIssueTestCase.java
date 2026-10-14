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
 * HttpService.registerResources ignores the HttpContext's handleSecurity: resources are served to every client even
 * when the registering bundle's HttpContext denies the request.
 *
 * <h2>Severity</h2>
 * critical (security). A bundle that protects its static resources with HttpContext.handleSecurity (the only access
 * control mechanism of the Http Service, typically checking an Authorization header or the scheme) exposes them to
 * unauthenticated clients. handleSecurity is never even called for resource registrations.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/integration/http, {@code org.jboss.as.osgi.httpservice.HttpServiceImpl} (bundle
 * jbosgi-wildfly-http, module org.jboss.as.osgi.http).
 *
 * <h2>Found by</h2>
 * wildfly workstream, Compendium/Enterprise spec alignment of the Http Service (exploratory check H10 of
 * ExploreHttpServiceSpecTestCase: securityCalls=0, status 200, body "content-a" with a denying HttpContext).
 *
 * <h2>Spec reference</h2>
 * OSGi Enterprise R7 / Compendium R7, 102.4 Mapping HTTP Requests to Servlet and Resource Registrations: "If the
 * registration corresponds to a resource, the authorization is verified by calling the handleSecurity method of the
 * associated HttpContext object. See Authentication. If the request is authorized, a target resource name is
 * constructed ..." and 102.7 Authentication: "If the method returns false, the request must not be processed."
 * HttpService.registerResources javadoc: "The Http Service will call the HttpContext argument to map resource names to
 * URLs and MIME types and to handle security for requests." https://docs.osgi.org/specification/osgi.cmpn/7.0.0/service.http.html
 *
 * <h2>Code location</h2>
 * wildfly/integration/http/src/main/java/org/jboss/as/osgi/httpservice/HttpServiceImpl.java:97-105: registerResources
 * gives the caller's HttpContext only to the ResourceServlet (for getResource) and calls
 * {@code registerInternal(alias, servlet, null, null, Type.RESOURCE)} with a null context; registerInternal (lines
 * 116-120) then creates {@code new DefaultHttpContext(bundle)} and wraps the servlet in
 * {@code SecurityServletWrapper(servlet, httpContext)} (line 144) with that default context, whose handleSecurity
 * always returns true (DefaultHttpContext.java).
 *
 * <h2>Root cause</h2>
 * The HttpContext of a resource registration is not passed on to registerInternal, so the security wrapper uses a
 * fresh default HttpContext.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=HttpResourcesIgnoreHandleSecurityIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository. The two -Darq... options start the managed server with port offset 500, so the test cannot connect to another server on 9990 (arquillian.xml has allowConnectingToRunningServer=true); they can be left out when no other server runs.)
 * <h2>Observed</h2>
 * <pre>
 * testHandleSecurityCalledForResources: Expected handleSecurity of the HttpContext of a resource registration to be
 *   called for a request to /hsec-res/a.txt, but it was called 0 times (status 200)
 * testDeniedResourceNotServed: Expected a resource request denied by HttpContext.handleSecurity (403) not to be served,
 *   but got status 200 with body 'content-a'
 * </pre>
 *
 * <h2>Expected</h2>
 * handleSecurity of the registration's HttpContext is called for every resource request; when it returns false, the
 * resource is not served (the context set 403).
 *
 * <h2>Reference behaviour</h2>
 * Felix Http Jetty and Pax Web call HttpContext.handleSecurity for resource registrations (not run here).
 *
 * <h2>Suggested fix (not applied)</h2>
 * In registerResources pass the (possibly defaulted) HttpContext to registerInternal:
 * {@code registerInternal(alias, servlet, null, httpContext, Type.RESOURCE)}.

 * Verified: with this one-line change in a local copy (jar replaced in a server copy) both test methods pass.
 *
 * <h2>Regression vs WF41</h2>
 * no (measured on a WF41 server built from origin/WF41 77f95be6 on the same patched WildFly 41.0.1, JDK 25: same
 * failure). The Http Service code (wildfly/integration/http, last changed in the WF30 port e4d06ba7) is unchanged on
 * the R8 branch. On WF41 the bundle jbosgi-wildfly-http 2.12.0.Final starts ("JBOSGI011002: Bundle started:
 * jbosgi-wildfly-http:2.12.0.Final", regression-2
 * srv-results/HttpResourcesIgnoreHandleSecurityIssueTestCase.server.log:143): it is module backed
 * (org/jboss/as/osgi/http/main/module.xml), so its javax.servlet Import-Package does not stop it, and the test reaches
 * the defect there: a resource request that handleSecurity denies gets status 200 'content-a' and handleSecurity is
 * called 0 times, exactly as on R8. The earlier reasoning (the bundle imported javax.servlet, did not resolve on
 * WildFly 41 and the code path was unreachable) was wrong; the verdict "no" stands. The WF41 run used harness
 * adaptations only, no change to this test or to the Http Service code: the jacc {@code <policy>} removed from
 * standalone-osgi.xml (as r8 42d6c470 does), the r8 Arquillian JBOSGiApplicationArchiveProcessor (54b65e85), and a
 * provided dependency on jbosgi-wildfly-http in the testsuite/integration pom (as on r8) so the test compiles; see the
 * regression-2 workstream NOTES.md.
 */
@RunWith(Arquillian.class)
public class HttpResourcesIgnoreHandleSecurityIssueTestCase {

    @ArquillianResource
    BundleContext context;

    @ArquillianResource
    Bundle bundle;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "http-resources-security-tests");
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

    @Test
    public void testHandleSecurityCalledForResources() throws Exception {
        HttpService http = httpService();
        RecordingContext allowing = new RecordingContext(bundle, true, false);
        http.registerResources("/hsec-res", "/www", allowing);
        try {
            String[] res = get("/hsec-res/a.txt");
            Assert.assertTrue("Expected handleSecurity of the HttpContext of a resource registration to be called for a request to /hsec-res/a.txt, but it was called "
                    + allowing.securityCalls.get() + " times (status " + res[0] + ")", allowing.securityCalls.get() > 0);
        } finally {
            unregisterQuietly(http, "/hsec-res");
        }
    }

    @Test
    public void testDeniedResourceNotServed() throws Exception {
        HttpService http = httpService();
        RecordingContext denying = new RecordingContext(bundle, false, false);
        http.registerResources("/hsec-deny", "/www", denying);
        try {
            String[] res = get("/hsec-deny/a.txt");
            Assert.assertFalse("Expected a resource request denied by HttpContext.handleSecurity (403) not to be served, but got status " + res[0]
                    + " with body '" + res[1] + "'", "200".equals(res[0]) || res[1].contains("content-a"));
        } finally {
            unregisterQuietly(http, "/hsec-deny");
        }
    }
}
