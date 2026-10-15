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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServlet;

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
import org.osgi.service.http.HttpService;

/**
 * Resource registrations answer every HTTP method with 200 and the resource content (PUT, DELETE, OPTIONS, unknown
 * methods) and ignore If-Modified-Since although they send Last-Modified.
 *
 * <h2>Severity</h2>
 * minor. A client that sends {@code DELETE /static/a.txt} or {@code PUT /static/a.txt} receives 200 OK, i.e. a success
 * status for an operation that did not happen (RFC 9110: 405 or 501); OPTIONS returns the file instead of an Allow
 * header; unknown or WebDAV methods (PROPFIND, MKCOL, FOO) download the file. Caches and browsers revalidate with
 * If-Modified-Since against the Last-Modified the server sent and always get the full body back instead of 304, so
 * static resources are never revalidated cheaply.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/integration/http, {@code org.jboss.as.osgi.httpservice.ResourceServlet}.
 *
 * <h2>Found by</h2>
 * fuzz-http workstream (seeded HTTP request fuzzing of the Http Service in the patched WildFly 41.0.1 + R8 server,
 * JDK 25), seed 7001, variant classes "methods" (HEAD, OPTIONS, TRACE, PUT, DELETE, POST, PATCH, PROPFIND, MKCOL,
 * FOO, get, CONNECT on servlet and resource aliases) and "range-cond" (Range, If-Modified-Since, If-None-Match,
 * If-Unmodified-Since, If-Match on resources): every non-GET method on a resource returned 200 with the file
 * (TRACE excepted, rejected by Undertow), If-Modified-Since in the future returned 200 with the body. Differential:
 * Felix Http Jetty 4.0.6 on Felix 7.0.5 answers PUT/DELETE/POST with 405, PROPFIND/MKCOL/FOO with 501, OPTIONS with
 * 200 "Allow: GET, HEAD, TRACE, OPTIONS" and no body, and If-Modified-Since with 304.
 *
 * <h2>Spec reference</h2>
 * RFC 9110 HTTP Semantics, 9.1 Methods Overview (https://www.rfc-editor.org/rfc/rfc9110#section-9.1): "An origin
 * server that receives a request method that is unrecognized or not implemented SHOULD respond with the 501 (Not
 * Implemented) status code. An origin server that receives a request method that is recognized and implemented, but
 * not allowed for the target resource, SHOULD respond with the 405 (Method Not Allowed) status code." 13.2.1 When to
 * Evaluate: "Except when excluded below, a recipient cache or origin server MUST evaluate received request
 * preconditions after it has successfully performed its normal request checks and just before it would process the
 * request content (if any) or perform the action associated with the request method." 13.1.3 If-Modified-Since
 * defines the 304 response for a selected representation not modified since the given date. OSGi Compendium R8 102.4
 * only requires that "the Http Service must return the contents of the URL to the client completing the HTTP
 * request"; the resource registration is described as serving files (102.3 "Registering Resources"), and
 * jakarta.servlet.http.HttpServlet (the base class of the ResourceServlet) implements exactly this method dispatch and
 * conditional GET (doGet/getLastModified) when service() is not overridden.
 *
 * <h2>Code location</h2>
 * wildfly/integration/http/src/main/java/org/jboss/as/osgi/httpservice/ResourceServlet.java:55-84: the class overrides
 * {@code service(HttpServletRequest, HttpServletResponse)} and serves the resource for any method; it sets
 * {@code last-modified} from {@code URLConnection.getLastModified()} (line 78-80) but never reads
 * If-Modified-Since. Last changed in the WF30 port e4d06ba7.
 *
 * <h2>Root cause</h2>
 * ResourceServlet overrides HttpServlet.service() instead of doGet()/getLastModified(), bypassing the method dispatch
 * and conditional GET handling of HttpServlet.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=11290 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=1300 \
 *     -pl testsuite/integration test -Dtest=HttpResourceRequestMethodIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local
 * Maven repository and the wildfly/03 surefire argLine change on JDK 24+.)
 *
 * <h2>Observed</h2>
 * <pre>
 * testDeleteOnResourceRejected: Expected DELETE /hmeth/a.txt to be rejected with 405 or 501 (a resource registration
 *   cannot delete), but got "HTTP/1.1 200 OK" with the resource content "content-a"
 * testPutOnResourceRejected: Expected PUT /hmeth/a.txt to be rejected with 405 or 501, but got "HTTP/1.1 200 OK" with
 *   the resource content "content-a"
 * testUnknownMethodOnResourceRejected: Expected FOO /hmeth/a.txt to be rejected with 501 or 405, but got
 *   "HTTP/1.1 200 OK" with the resource content "content-a"
 * testIfModifiedSinceHonoured: Expected 304 for GET /hmeth/a.txt with If-Modified-Since equal to the Last-Modified
 *   the server sent (Tue, 13 Oct 2026 23:20:38 GMT), but got "HTTP/1.1 200 OK" with the full body
 * </pre>
 *
 * <h2>Expected</h2>
 * PUT/DELETE: 405 (with Allow), unknown methods: 501 (or 405); OPTIONS: Allow header; If-Modified-Since not older
 * than the resource: 304 without body. GET and HEAD unchanged.
 *
 * <h2>Reference behaviour</h2>
 * Felix 7.0.5 + Felix Http Jetty 4.0.6 (javax build of the same fuzz bundle, {@code registerResources("/fzres",
 * "/www", ctx)}): PUT/DELETE/POST 405, PROPFIND/MKCOL/FOO/CONNECT 501, OPTIONS 200 with {@code Allow: GET, HEAD,
 * TRACE, OPTIONS} and Content-Length 0, If-Modified-Since in the future 304.
 *
 * <h2>Suggested fix (not applied)</h2>
 * Implement {@code doGet} (HEAD is then derived by HttpServlet) and {@code getLastModified(HttpServletRequest)}
 * (returning the URL's last-modified time) instead of overriding {@code service}. Verified: with ResourceServlet
 * changed to doGet + getLastModified (installed into the testsuite server in a local copy) all four test methods pass.
 *
 * <h2>Regression vs WF41</h2>
 * no (measured on a WF41 server built from origin/WF41 77f95be6 on the same patched WildFly 41.0.1, JDK 25: same
 * failure). The Http Service code is unchanged on the R8 branch. On WF41 the bundle jbosgi-wildfly-http 2.12.0.Final
 * starts ("JBOSGI011002: Bundle started: jbosgi-wildfly-http:2.12.0.Final", regression-2
 * srv-results/HttpResourceRequestMethodIssueTestCase.server.log:143): it is module backed
 * (org/jboss/as/osgi/http/main/module.xml), so its javax.servlet Import-Package does not stop it, and the test reaches
 * the defect there: PUT, DELETE and FOO on a resource get 200 with the resource content and If-Modified-Since is
 * ignored, exactly as on R8. The earlier reasoning (the bundle imported javax.servlet, did not resolve on WildFly 41
 * and the code path was unreachable) was wrong; the verdict "no" stands. The WF41 run used harness adaptations only,
 * no change to this test or to the Http Service code: the jacc {@code <policy>} removed from standalone-osgi.xml (as
 * r8 42d6c470 does), the r8 Arquillian JBOSGiApplicationArchiveProcessor (54b65e85), and a provided dependency on
 * jbosgi-wildfly-http in the testsuite/integration pom (as on r8) so the test compiles; see the regression-2
 * workstream NOTES.md.
 */
@RunWith(Arquillian.class)
public class HttpResourceRequestMethodIssueTestCase {

    @ArquillianResource
    BundleContext context;

    @ArquillianResource
    Bundle bundle;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "http-resource-request-method-tests");
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

    /** Raw HTTP/1.1 request on the server's HTTP port (jboss.http.port + jboss.socket.binding.port-offset); returns the whole response */
    private static String request(String method, String path, String extraHeaders, String body) throws IOException {
        int port = Integer.getInteger("jboss.http.port", 8080) + Integer.getInteger("jboss.socket.binding.port-offset", 0);
        try (Socket s = new Socket("localhost", port)) {
            s.setSoTimeout(10000);
            StringBuilder req = new StringBuilder();
            req.append(method).append(' ').append(path).append(" HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n");
            if (extraHeaders != null)
                req.append(extraHeaders);
            if (body != null)
                req.append("Content-Type: text/plain\r\nContent-Length: ").append(body.length()).append("\r\n");
            req.append("\r\n");
            if (body != null)
                req.append(body);
            OutputStream out = s.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return new String(s.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private static String statusLine(String response) {
        int i = response.indexOf("\r\n");
        return i < 0 ? response : response.substring(0, i);
    }

    private static int status(String response) {
        String[] parts = statusLine(response).split(" ");
        return parts.length > 1 ? Integer.parseInt(parts[1]) : -1;
    }

    private static String header(String response, String name) {
        for (String line : response.substring(0, Math.max(0, response.indexOf("\r\n\r\n"))).split("\r\n")) {
            int i = line.indexOf(':');
            if (i > 0 && line.substring(0, i).trim().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT)))
                return line.substring(i + 1).trim();
        }
        return null;
    }

    private static String body(String response) {
        int i = response.indexOf("\r\n\r\n");
        return i < 0 ? "" : response.substring(i + 4);
    }

    private void assertRejected(String method, String body, String expectedCodes) throws Exception {
        HttpService http = httpService();
        http.registerResources("/hmeth", "/www", null);
        try {
            String get = request("GET", "/hmeth/a.txt", null, null);
            Assert.assertEquals("Precondition: GET /hmeth/a.txt serves www/a.txt", "content-a", body(get));
            String res = request(method, "/hmeth/a.txt", null, body);
            int st = status(res);
            Assert.assertTrue("Expected " + method + " /hmeth/a.txt to be rejected with " + expectedCodes + ", but got \"" + statusLine(res) + "\""
                    + (body(res).contains("content-a") ? " with the resource content \"content-a\"" : ""), st == 405 || st == 501);
        } finally {
            unregisterQuietly(http, "/hmeth");
        }
    }

    @Test
    public void testDeleteOnResourceRejected() throws Exception {
        assertRejected("DELETE", null, "405 or 501 (a resource registration cannot delete)");
    }

    @Test
    public void testPutOnResourceRejected() throws Exception {
        assertRejected("PUT", "replacement", "405 or 501");
    }

    @Test
    public void testUnknownMethodOnResourceRejected() throws Exception {
        assertRejected("FOO", null, "501 or 405");
    }

    @Test
    public void testIfModifiedSinceHonoured() throws Exception {
        HttpService http = httpService();
        http.registerResources("/hmeth", "/www", null);
        try {
            String get = request("GET", "/hmeth/a.txt", null, null);
            String lastModified = header(get, "Last-Modified");
            Assert.assertNotNull("Precondition: GET /hmeth/a.txt sends Last-Modified, response: " + get, lastModified);
            String res = request("GET", "/hmeth/a.txt", "If-Modified-Since: " + lastModified + "\r\n", null);
            Assert.assertEquals("Expected 304 for GET /hmeth/a.txt with If-Modified-Since equal to the Last-Modified the server sent (" + lastModified
                    + "), but got \"" + statusLine(res) + "\"" + (body(res).contains("content-a") ? " with the full body" : ""), 304, status(res));
        } finally {
            unregisterQuietly(http, "/hmeth");
        }
    }
}
