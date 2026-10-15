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
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.Servlet;
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

/**
 * Requests whose path below an alias starts with a WEB-INF or META-INF segment (any case) never reach the servlet or
 * resource registered at that alias: the server answers 404 without calling handleSecurity or the servlet.
 *
 * <h2>Severity</h2>
 * minor. The Http Service has no protected directories, but every alias is deployed as its own Undertow web
 * application, so Undertow's WAR protection of /WEB-INF and /META-INF applies relative to each alias: a servlet at
 * {@code /api} never sees {@code /api/meta-inf/x} or {@code /api/WEB-INF/x}, and a resource registration that serves a
 * tree containing META-INF (e.g. {@code registerResources("/bundle", "", ctx)} to expose a bundle's
 * META-INF/MANIFEST.MF, or a mirror of jar contents) cannot serve those files. Requests one segment deeper
 * ({@code /api/x/WEB-INF/y}) are dispatched normally, so the behaviour depends on where the alias boundary is.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/integration/http, {@code org.jboss.as.osgi.httpservice.HttpServiceImpl.registerInternal}
 * (one Undertow deployment per alias), with Undertow's {@code io.undertow.servlet.handlers.ServletInitialHandler}.
 *
 * <h2>Found by</h2>
 * fuzz-http workstream (seeded HTTP request fuzzing of the Http Service in the patched WildFly 41.0.1 + R8 server,
 * JDK 25), seed 7002, variant class "protected" (paths below servlet and resource aliases starting with WEB-INF,
 * META-INF, web-inf, Meta-Inf, OSGI-INF): every WEB-INF/META-INF variant directly below an alias returned 404 instead
 * of the oracle's dispatch (20 of 40 requests; OSGI-INF and deeper WEB-INF segments were dispatched). Differential:
 * Felix Http Jetty 4.0.6 on Felix 7.0.5 dispatches all of them to the registered servlet or resource.
 *
 * <h2>Spec reference</h2>
 * OSGi Compendium R8, 102.4 Mapping HTTP Requests to Servlet and Resource Registrations
 * (https://docs.osgi.org/specification/osgi.cmpn/8.0.0/service.http.html): "When an HTTP request comes in from a
 * client, the Http Service checks to see if the requested URI matches any registered aliases. ... If it does match, a
 * matching registration takes place, which is processed as follows: If the registration corresponds to a servlet, the
 * authorization is verified by calling the handleSecurity method of the associated HttpContext object. ... If the
 * request is authorized, the servlet must be called by its service method to complete the HTTP request. If the
 * registration corresponds to a resource, the authorization is verified by calling the handleSecurity method ... a
 * target resource name is constructed from the requested URI by substituting the alias ... The target resource name
 * must be passed to the getResource method of the associated HttpContext object." ... "If there is no match, the Http
 * Service must attempt to match sub-strings of the requested URI to registered aliases." The chapter defines no
 * protected paths (unlike 128.3.5 for Web Application Bundles).
 *
 * <h2>Code location</h2>
 * wildfly/integration/http/src/main/java/org/jboss/as/osgi/httpservice/HttpServiceImpl.java:108-173
 * ({@code deploymentBuilder.setContextRoot(alias)}, {@code wrapper.addUrlMapping("/*")},
 * {@code virtualHost.addWebDeployment(deploymentBuilder)}): each alias becomes a web application whose context path is
 * the alias. Undertow (undertow-servlet, {@code io.undertow.servlet.handlers.ServletInitialHandler.handleRequest}:
 * {@code if (Paths.isForbidden(path)) { exchange.setStatusCode(NOT_FOUND); return; }}, {@code Paths.isForbidden}
 * compares the first segment of the context-relative path with META-INF/WEB-INF ignoring case) rejects the request
 * before any servlet or filter runs. Last changed in the WF30 port e4d06ba7.
 *
 * <h2>Root cause</h2>
 * The Http Service maps aliases onto servlet contexts, so the servlet container's WAR protection of WEB-INF/META-INF
 * is applied to the path below every alias.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=11290 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=1300 \
 *     -pl testsuite/integration test -Dtest=HttpAliasProtectedPathIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local
 * Maven repository and the wildfly/03 surefire argLine change on JDK 24+.)
 *
 * <h2>Observed</h2>
 * <pre>
 * testServletReceivesWebInfPath: Expected GET /hprot/WEB-INF/x to be dispatched to the servlet registered at /hprot
 *   (handleSecurity called, body "served /WEB-INF/x"), but got status 404, body "&lt;html&gt;...404 - Not Found...",
 *   handleSecurity calls 0
 * testServletReceivesLowerCaseMetaInfPath: Expected GET /hprot/meta-inf/y to be dispatched to the servlet registered at
 *   /hprot, but got status 404, handleSecurity calls 0
 * testResourceBelowMetaInf: Expected GET /hprot-res/META-INF/MANIFEST.MF (registerResources("/hprot-res", "", ctx))
 *   to serve the bundle manifest, but got status 404, getResource calls 0
 * </pre>
 *
 * <h2>Expected</h2>
 * The requests are dispatched like any other path below the alias: servlet called (after handleSecurity), resource
 * name /META-INF/MANIFEST.MF passed to the HttpContext.
 *
 * <h2>Reference behaviour</h2>
 * Felix 7.0.5 + Felix Http Jetty 4.0.6 (javax build of the same fuzz bundle): {@code /fz/WEB-INF/x},
 * {@code /fz/meta-inf/x}, {@code /fz/a/WEB-INF/x} reach the servlets, {@code /fzroot/META-INF/MANIFEST.MF} and
 * {@code /fzres/WEB-INF/w.txt} are served (200).
 *
 * <h2>Suggested fix (not applied)</h2>
 * Do not let the container's WAR protection apply to Http Service registrations: e.g. register all aliases as servlets
 * of one Undertow deployment that does the 102.4 alias matching itself (the context-relative path then starts with the
 * alias, never with WEB-INF/META-INF unless the alias does), or install an inner handler for these deployments that
 * dispatches before {@code ServletInitialHandler}'s forbidden-path check.
 *
 * <h2>Regression vs WF41</h2>
 * no (measured on a WF41 server built from origin/WF41 77f95be6 on the same patched WildFly 41.0.1, JDK 25: same
 * failure). The Http Service code is unchanged on the R8 branch. On WF41 the bundle jbosgi-wildfly-http 2.12.0.Final
 * starts ("JBOSGI011002: Bundle started: jbosgi-wildfly-http:2.12.0.Final", regression-2
 * srv-results/HttpAliasProtectedPathIssueTestCase.server.log:143): it is module backed
 * (org/jboss/as/osgi/http/main/module.xml), so its javax.servlet Import-Package does not stop it, and the test reaches
 * the defect there: GET /hprot/WEB-INF/x, /hprot/meta-inf/y and /hprot-res/META-INF/MANIFEST.MF are answered with 404,
 * exactly as on R8. The earlier reasoning (the bundle imported javax.servlet, did not resolve on WildFly 41 and the
 * code path was unreachable) was wrong; the verdict "no" stands. The WF41 run used harness adaptations only, no change
 * to this test or to the Http Service code: the jacc {@code <policy>} removed from standalone-osgi.xml (as r8 42d6c470
 * does), the r8 Arquillian JBOSGiApplicationArchiveProcessor (54b65e85), and a provided dependency on
 * jbosgi-wildfly-http in the testsuite/integration pom (as on r8) so the test compiles; see the regression-2
 * workstream NOTES.md.
 */
@RunWith(Arquillian.class)
public class HttpAliasProtectedPathIssueTestCase {

    @ArquillianResource
    BundleContext context;

    @ArquillianResource
    Bundle bundle;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "http-alias-protected-path-tests");
        archive.addAsResource(new StringAsset("content-a"), "www/a.txt");
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

    /** The Http Service as seen by the test bundle (its default HttpContext maps names to the test bundle) */
    private HttpService httpService() {
        BundleContext bc = bundle.getBundleContext();
        ServiceReference<HttpService> sref = bc.getServiceReference(HttpService.class);
        Assert.assertNotNull("Precondition: HttpService available", sref);
        return bc.getService(sref);
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

    /** GET on the server's HTTP port (jboss.http.port + jboss.socket.binding.port-offset, default 8080); returns {status, body} */
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
        return new String[] { String.valueOf(status), body.length() > 100 ? body.substring(0, 100) + "..." : body };
    }

    static class PathServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
            res.setContentType("text/plain");
            res.getWriter().print("served " + req.getPathInfo());
        }
    }

    /** An HttpContext that counts its calls */
    static class CountingContext implements HttpContext {
        final AtomicInteger securityCalls = new AtomicInteger();
        final AtomicInteger resourceCalls = new AtomicInteger();
        private final Bundle bundle;

        CountingContext(Bundle bundle) {
            this.bundle = bundle;
        }

        @Override
        public boolean handleSecurity(HttpServletRequest request, HttpServletResponse response) throws IOException {
            securityCalls.incrementAndGet();
            return true;
        }

        @Override
        public URL getResource(String name) {
            resourceCalls.incrementAndGet();
            return bundle.getResource(name);
        }

        @Override
        public String getMimeType(String name) {
            return null;
        }
    }

    private void assertServletDispatch(String requestPath, String expectedPathInfo) throws Exception {
        HttpService http = httpService();
        CountingContext ctx = new CountingContext(bundle);
        http.registerServlet("/hprot", new PathServlet(), null, ctx);
        try {
            String[] ok = get("/hprot/x/y");
            Assert.assertEquals("Precondition: GET /hprot/x/y reaches the servlet", "served /x/y", ok[1]);
            ctx.securityCalls.set(0);
            String[] res = get(requestPath);
            Assert.assertTrue("Expected GET " + requestPath + " to be dispatched to the servlet registered at /hprot (handleSecurity called, body \"served "
                    + expectedPathInfo + "\"), but got status " + res[0] + ", body \"" + res[1] + "\", handleSecurity calls " + ctx.securityCalls.get(),
                    "200".equals(res[0]) && ("served " + expectedPathInfo).equals(res[1]) && ctx.securityCalls.get() > 0);
        } finally {
            unregisterQuietly(http, "/hprot");
        }
    }

    @Test
    public void testServletReceivesWebInfPath() throws Exception {
        assertServletDispatch("/hprot/WEB-INF/x", "/WEB-INF/x");
    }

    @Test
    public void testServletReceivesLowerCaseMetaInfPath() throws Exception {
        assertServletDispatch("/hprot/meta-inf/y", "/meta-inf/y");
    }

    @Test
    public void testResourceBelowMetaInf() throws Exception {
        HttpService http = httpService();
        CountingContext ctx = new CountingContext(bundle);
        http.registerResources("/hprot-res", "", ctx);
        try {
            String[] ok = get("/hprot-res/www/a.txt");
            Assert.assertEquals("Precondition: GET /hprot-res/www/a.txt serves www/a.txt", "content-a", ok[1]);
            ctx.resourceCalls.set(0);
            String[] res = get("/hprot-res/META-INF/MANIFEST.MF");
            Assert.assertTrue("Expected GET /hprot-res/META-INF/MANIFEST.MF (registerResources(\"/hprot-res\", \"\", ctx)) to serve the bundle manifest, but got status "
                    + res[0] + ", body \"" + res[1] + "\", getResource calls " + ctx.resourceCalls.get(),
                    "200".equals(res[0]) && res[1].contains("Bundle-SymbolicName") && ctx.resourceCalls.get() > 0);
        } finally {
            unregisterQuietly(http, "/hprot-res");
        }
    }
}
