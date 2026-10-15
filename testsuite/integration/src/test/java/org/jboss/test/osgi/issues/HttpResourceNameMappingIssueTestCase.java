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
 * Resource registrations do not map requests as specified in 102.4: an internal name "/" yields "//x", an alias
 * that names a single resource fails with 500, a missing resource gives 500 instead of 404, and a null URL from
 * getResource does not fall back to a shorter alias.
 *
 * <h2>Severity</h2>
 * major. Common registrations break: {@code registerResources("/static", "/", ctx)} (serve the bundle root) asks the
 * HttpContext for "//file" (the default HttpContext's Bundle.getResource("//file") happens to work, custom
 * HttpContexts that map names to files or check prefixes do not); single-file aliases such as
 * {@code registerResources("/favicon.ico", "web/favicon.ico", ctx)} always fail with 500; every request for a missing
 * file produces 500 plus a NullPointerException in the server log instead of 404.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/integration/http, {@code org.jboss.as.osgi.httpservice.ResourceServlet}.
 *
 * <h2>Found by</h2>
 * wildfly workstream, Compendium/Enterprise spec alignment of the Http Service (exploratory checks H12, H13, H15, H16).
 *
 * <h2>Spec reference</h2>
 * OSGi Compendium R7, 102.4: "a target resource name is constructed from the requested URI by substituting the alias
 * from the registration with the internal name from the registration if the alias is not "/". ... An internal name of
 * "/" is considered to have the value of the empty string ("") during this process." Table 102.1 rows
 * (alias /fudd, internal name /, URI /fudd/bugs -&gt; getResource("/bugs")) and (alias /fudd/bugs/x.gif, internal name
 * tmp/y.gif, URI /fudd/bugs/x.gif -&gt; getResource("tmp/y.gif")). "If the returned URL object is null, the Http
 * Service continues as if there was no match." ... "Otherwise, the Http Service must return
 * HttpServletResponse.SC_NOT_FOUND(404) to the client." https://docs.osgi.org/specification/osgi.cmpn/7.0.0/service.http.html
 *
 * <h2>Code location</h2>
 * wildfly/integration/http/src/main/java/org/jboss/as/osgi/httpservice/ResourceServlet.java:57-63:
 * {@code String path = req.getPathInfo(); URL resurl = context.getResource(name + path);} (name "/" + "/b.txt" =
 * "//b.txt"; for an exact alias match getPathInfo() is null, giving "www/a.txtnull"); when resurl is null it calls
 * {@code res.sendError(404, ...)} but does not return, and {@code resurl.openConnection()} throws
 * NullPointerException; there is no fallback to shorter aliases (each alias is its own Undertow context).
 *
 * <h2>Root cause</h2>
 * The target name is built by plain concatenation of the internal name and the path info, without the "/"-as-empty
 * rule and without handling a null path info; the null URL case falls through.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=HttpResourceNameMappingIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository. The two -Darq... options start the managed server with port offset 500, so the test cannot connect to another server on 9990 (arquillian.xml has allowConnectingToRunningServer=true); they can be left out when no other server runs.)
 * <h2>Observed</h2>
 * <pre>
 * testRootInternalName: Expected getResource("/b.txt") for internal name "/" and request /hmap-root/b.txt, but the
 *   HttpContext was asked for [//b.txt]
 * testExactAliasResource: Expected /hmap-exact/x.txt (alias of a single resource, internal name www/a.txt) to serve
 *   www/a.txt, but got status 500, getResource calls [www/a.txtnull]
 * testMissingResourceIs404: Expected 404 for a resource the HttpContext does not have, but got status 500
 * testNullResourceFallsBackToShorterAlias: Expected /hmap-fb/res/zz.txt to be served by the servlet at /hmap-fb after
 *   getResource returned null, but got status 500
 * </pre>
 *
 * <h2>Expected</h2>
 * Target names as in Table 102.1; 404 for missing resources; the next shorter alias is tried when getResource
 * returns null.
 *
 * <h2>Reference behaviour</h2>
 * Not checked.
 *
 * <h2>Suggested fix (not applied)</h2>
 * In ResourceServlet: {@code String prefix = "/".equals(name) ? "" : name; String pathInfo = req.getPathInfo();
 * String target = pathInfo == null ? name : prefix + pathInfo;} and {@code return} after sendError(404). The
 * fallback to shorter aliases needs a dispatch to the next matching registration (e.g. a single Undertow deployment
 * that does the alias matching itself) instead of one Undertow context per alias.

 * Verified: with the ResourceServlet change above in a local copy testRootInternalName, testExactAliasResource and
 * testMissingResourceIs404 pass; testNullResourceFallsBackToShorterAlias then gets 404 and needs the dispatch change.
 *
 * <h2>Regression vs WF41</h2>
 * no (measured on a WF41 server built from origin/WF41 77f95be6 on the same patched WildFly 41.0.1, JDK 25: same
 * failure). The Http Service code (wildfly/integration/http, last changed in the WF30 port e4d06ba7) is unchanged on
 * the R8 branch. On WF41 the bundle jbosgi-wildfly-http 2.12.0.Final starts ("JBOSGI011002: Bundle started:
 * jbosgi-wildfly-http:2.12.0.Final", regression-2 srv-results/HttpResourceNameMappingIssueTestCase.server.log:143): it
 * is module backed (org/jboss/as/osgi/http/main/module.xml), so its javax.servlet Import-Package does not stop it, and
 * the test reaches the defect there: the exact-alias resource and a missing resource get status 500, the shorter-alias
 * fallback is not taken and the HttpContext is asked for "//b.txt" for the root internal name, exactly as on R8. The
 * earlier reasoning (the bundle imported javax.servlet, did not resolve on WildFly 41 and the code path was
 * unreachable) was wrong; the verdict "no" stands. The WF41 run used harness adaptations only, no change to this test
 * or to the Http Service code: the jacc {@code <policy>} removed from standalone-osgi.xml (as r8 42d6c470 does), the
 * r8 Arquillian JBOSGiApplicationArchiveProcessor (54b65e85), and a provided dependency on jbosgi-wildfly-http in the
 * testsuite/integration pom (as on r8) so the test compiles; see the regression-2 workstream NOTES.md.
 */
@RunWith(Arquillian.class)
public class HttpResourceNameMappingIssueTestCase {

    @ArquillianResource
    BundleContext context;

    @ArquillianResource
    Bundle bundle;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "http-resource-mapping-tests");
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
    public void testRootInternalName() throws Exception {
        HttpService http = httpService();
        RecordingContext ctx = new RecordingContext(bundle, true, false);
        http.registerResources("/hmap-root", "/", ctx);
        try {
            get("/hmap-root/b.txt");
            Assert.assertTrue("Expected getResource(\"/b.txt\") for internal name \"/\" and request /hmap-root/b.txt, but the HttpContext was asked for "
                    + ctx.resources, ctx.resources.contains("/b.txt"));
        } finally {
            unregisterQuietly(http, "/hmap-root");
        }
    }

    @Test
    public void testExactAliasResource() throws Exception {
        HttpService http = httpService();
        RecordingContext ctx = new RecordingContext(bundle, true, false);
        http.registerResources("/hmap-exact/x.txt", "www/a.txt", ctx);
        try {
            String[] res = get("/hmap-exact/x.txt");
            Assert.assertTrue("Expected /hmap-exact/x.txt (alias of a single resource, internal name www/a.txt) to serve www/a.txt, but got status "
                    + res[0] + ", getResource calls " + ctx.resources, "200".equals(res[0]) && "content-a".equals(res[1]) && ctx.resources.contains("www/a.txt"));
        } finally {
            unregisterQuietly(http, "/hmap-exact/x.txt");
        }
    }

    @Test
    public void testMissingResourceIs404() throws Exception {
        HttpService http = httpService();
        RecordingContext ctx = new RecordingContext(bundle, true, true);
        http.registerResources("/hmap-missing", "/www", ctx);
        try {
            String[] res = get("/hmap-missing/zz.txt");
            Assert.assertEquals("Expected 404 for a resource the HttpContext does not have, but got status " + res[0], "404", res[0]);
        } finally {
            unregisterQuietly(http, "/hmap-missing");
        }
    }

    @Test
    public void testNullResourceFallsBackToShorterAlias() throws Exception {
        HttpService http = httpService();
        RecordingContext ctx = new RecordingContext(bundle, true, true);
        http.registerServlet("/hmap-fb", new TextServlet("fallback servlet"), null, null);
        try {
            http.registerResources("/hmap-fb/res", "/www", ctx);
            String[] res = get("/hmap-fb/res/zz.txt");
            Assert.assertTrue("Expected /hmap-fb/res/zz.txt to be served by the servlet at /hmap-fb after getResource returned null, but got status " + res[0],
                    "200".equals(res[0]) && "fallback servlet".equals(res[1]));
        } finally {
            unregisterQuietly(http, "/hmap-fb/res", "/hmap-fb");
        }
    }
}
