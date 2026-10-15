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
 * The HttpContext of a registration is never asked for MIME types, and the ServletContext of a registered servlet
 * does not delegate getMimeType/getResource/getResourceAsStream to the HttpContext.
 *
 * <h2>Severity</h2>
 * major. Resources are served with the content type guessed by java.net.URLConnection (e.g. text/plain or
 * content/unknown) instead of the type the bundle's HttpContext defines; servlets that load their templates or static
 * files through {@code getServletContext().getResource(...)} get null, because the ServletContext looks into an empty
 * temp directory instead of asking the HttpContext (i.e. the bundle).
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/integration/http, {@code org.jboss.as.osgi.httpservice.ResourceServlet} and
 * {@code HttpServiceImpl.registerInternal}.
 *
 * <h2>Found by</h2>
 * wildfly workstream, Compendium/Enterprise spec alignment of the Http Service (exploratory checks H14, H19, H20).
 *
 * <h2>Spec reference</h2>
 * OSGi Compendium R7, 102.4: "The translated target name, as opposed to the original requested URI, must also be used
 * as the argument to HttpContext.getMimeType." 102.10.2.4 HttpContext.getMimeType: "Called by the Http Service to
 * determine the MIME type for the specified name. For servlets, the Http Service will call this method to support the
 * ServletContext method getMimeType. For resources, the Http Service will call this method to determine the MIME type
 * for the Content-Type header in the response." HttpService.registerServlet: "The Http Service will call the context
 * argument to support the ServletContext methods getResource, getResourceAsStream and getMimeType". https://docs.osgi.org/specification/osgi.cmpn/7.0.0/service.http.html
 *
 * <h2>Code location</h2>
 * wildfly/integration/http/src/main/java/org/jboss/as/osgi/httpservice/ResourceServlet.java:63-70 (content type taken
 * from {@code URLConnection.getContentType()}, HttpContext.getMimeType never called);
 * HttpServiceImpl.java:110-150 (the Undertow deployment gets a fixed mime mapping list and an empty document root
 * {@code <server tmp>/<alias>/osgiservlet-root}; nothing connects the ServletContext to the HttpContext).
 *
 * <h2>Root cause</h2>
 * The HttpContext is only used for handleSecurity (servlets) and getResource (resource servlet); the ServletContext is
 * Undertow's standard one for an empty web application.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=HttpContextMimeTypeAndResourceIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository. The two -Darq... options start the managed server with port offset 500, so the test cannot connect to another server on 9990 (arquillian.xml has allowConnectingToRunningServer=true); they can be left out when no other server runs.)
 * <h2>Observed</h2>
 * <pre>
 * testResourceMimeTypeFromHttpContext: Expected HttpContext.getMimeType("/www/a.txt") to be called and its value used as
 *   Content-Type, but getMimeType calls were [] and Content-Type was text/plain
 * testServletContextGetMimeTypeDelegates: Expected ServletContext.getMimeType("foo.bar") to return the HttpContext's
 *   type application/x-osgi-test, but got null (HttpContext calls [])
 * testServletContextGetResourceDelegates: Expected ServletContext.getResource("/b.txt") to be answered by the
 *   HttpContext, but got null (HttpContext calls [])
 * </pre>
 *
 * <h2>Expected</h2>
 * Resource responses carry the HttpContext's MIME type (the Http Service default only when it returns null); the
 * ServletContext methods delegate to the HttpContext.
 *
 * <h2>Reference behaviour</h2>
 * Not checked.
 *
 * <h2>Suggested fix (not applied)</h2>
 * ResourceServlet: {@code String type = context.getMimeType(target); if (type == null) type = getServletContext().getMimeType(target);}
 * ServletContext: wrap Undertow's ServletContext (the fork's ApplicationContextWrapper hook is available) so that
 * getMimeType/getResource/getResourceAsStream go to the HttpContext first.

 * Verified: with the ResourceServlet change above in a local copy testResourceMimeTypeFromHttpContext passes; the
 * ServletContext delegation was not tried.
 *
 * <h2>Regression vs WF41</h2>
 * no (measured on a WF41 server built from origin/WF41 77f95be6 on the same patched WildFly 41.0.1, JDK 25: same
 * failure). The Http Service code (wildfly/integration/http, last changed in the WF30 port e4d06ba7) is unchanged on
 * the R8 branch. On WF41 the bundle jbosgi-wildfly-http 2.12.0.Final starts ("JBOSGI011002: Bundle started:
 * jbosgi-wildfly-http:2.12.0.Final", regression-2
 * srv-results/HttpContextMimeTypeAndResourceIssueTestCase.server.log:143): it is module backed
 * (org/jboss/as/osgi/http/main/module.xml), so its javax.servlet Import-Package does not stop it, and the test reaches
 * the defect there: HttpContext.getMimeType is never called (Content-Type text/plain) and ServletContext.getResource
 * and getMimeType return null without asking the HttpContext, exactly as on R8. The earlier reasoning (the bundle
 * imported javax.servlet, did not resolve on WildFly 41 and the code path was unreachable) was wrong; the verdict "no"
 * stands. The WF41 run used harness adaptations only, no change to this test or to the Http Service code: the jacc
 * {@code <policy>} removed from standalone-osgi.xml (as r8 42d6c470 does), the r8 Arquillian
 * JBOSGiApplicationArchiveProcessor (54b65e85), and a provided dependency on jbosgi-wildfly-http in the
 * testsuite/integration pom (as on r8) so the test compiles; see the regression-2 workstream NOTES.md.
 */
@RunWith(Arquillian.class)
public class HttpContextMimeTypeAndResourceIssueTestCase {

    @ArquillianResource
    BundleContext context;

    @ArquillianResource
    Bundle bundle;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "http-mime-resource-tests");
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

    static class ContextServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
            res.getWriter().print("ok");
        }
    }

    @Test
    public void testResourceMimeTypeFromHttpContext() throws Exception {
        HttpService http = httpService();
        RecordingContext ctx = new RecordingContext(bundle, true, false);
        http.registerResources("/hmime-res", "/www", ctx);
        try {
            String[] res = get("/hmime-res/a.txt");
            Assert.assertTrue("Expected HttpContext.getMimeType(\"/www/a.txt\") to be called and its value used as Content-Type, but getMimeType calls were "
                    + ctx.mimeTypes + " and Content-Type was " + res[2], ctx.mimeTypes.contains("/www/a.txt") && res[2].startsWith("application/x-osgi-test"));
        } finally {
            unregisterQuietly(http, "/hmime-res");
        }
    }

    @Test
    public void testServletContextGetMimeTypeDelegates() throws Exception {
        HttpService http = httpService();
        RecordingContext ctx = new RecordingContext(bundle, true, false);
        ContextServlet servlet = new ContextServlet();
        http.registerServlet("/hmime-sc", servlet, null, ctx);
        try {
            String type = servlet.getServletContext().getMimeType("foo.bar");
            Assert.assertEquals("Expected ServletContext.getMimeType(\"foo.bar\") to return the HttpContext's type application/x-osgi-test, but got " + type
                    + " (HttpContext calls " + ctx.mimeTypes + ")", "application/x-osgi-test", type);
        } finally {
            unregisterQuietly(http, "/hmime-sc");
        }
    }

    @Test
    public void testServletContextGetResourceDelegates() throws Exception {
        HttpService http = httpService();
        RecordingContext ctx = new RecordingContext(bundle, true, false);
        ContextServlet servlet = new ContextServlet();
        http.registerServlet("/hres-sc", servlet, null, ctx);
        try {
            URL url = servlet.getServletContext().getResource("/b.txt");
            Assert.assertTrue("Expected ServletContext.getResource(\"/b.txt\") to be answered by the HttpContext, but got " + url + " (HttpContext calls "
                    + ctx.resources + ")", url != null && ctx.resources.contains("/b.txt"));
        } finally {
            unregisterQuietly(http, "/hres-sc");
        }
    }
}
