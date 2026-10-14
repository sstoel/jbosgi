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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
 * A request for a directory below a resource alias is answered with a JAR archive of the whole directory subtree,
 * including files that the registration's HttpContext refuses to hand out.
 *
 * <h2>Severity</h2>
 * critical (raised from major by the coordinator: remotely triggerable without authentication, bypasses the
 * HttpContext as the access control, and every request writes a temp copy to disk, so repeated requests can fill the
 * disk). Security: information disclosure. One unauthenticated GET such as {@code /static/} or {@code /static/sub}
 * downloads every file below the corresponding bundle directory as a ZIP/JAR (200, Content-Type content/unknown). The
 * HttpContext is asked only for the directory name ({@code getResource("/www/")}), never for the files packed into the
 * archive, so a context that filters names (the spec's documented way to restrict what may be served) is bypassed;
 * with an internal name of "" (bundle root) the archive is the complete bundle, class files, OSGI-INF and secrets
 * included. Each such request also writes a temporary JAR copy of the subtree to the server's tmp/vfs directory
 * (subrepos/14 mechanism, here remotely triggerable).
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/integration/http, {@code org.jboss.as.osgi.httpservice.ResourceServlet}; the archive
 * itself comes from the framework's bundle URL handler (jbosgi-framework core {@code RevisionContent#getBundleURL})
 * and jbosgi-vfs vfs30 {@code VirtualFileAdaptor30#getStreamURL}.
 *
 * <h2>Found by</h2>
 * fuzz-http workstream (seeded HTTP request fuzzing of the Http Service in the patched WildFly 41.0.1 + R8 server,
 * JDK 25): first in the baseline probe ({@code GET /fzres/} returned 200 {@code content/unknown} starting with
 * {@code PK\3\4}), then systematically with seed 7002, variant class "dir" (directory targets with and without a
 * trailing '/', encoded variants): 18 of 40 requests returned a ZIP archive. Differential: Felix Http Jetty 4.0.6
 * on Felix 7.0.5 answers the same requests with 404.
 *
 * <h2>Spec reference</h2>
 * OSGi Compendium R8, 102.3 Registering Resources (https://docs.osgi.org/specification/osgi.cmpn/8.0.0/service.http.html):
 * "More sophisticated implementations of the getResource(String) method could filter the input name, restricting the
 * resources that may be returned or map the input name onto the file system". 102.4: "The target resource name must
 * be passed to the getResource method of the associated HttpContext object. If the returned URL object is not null,
 * the Http Service must return the contents of the URL to the client completing the HTTP request." The resources
 * served are the ones the HttpContext returned for the target name; a directory has no content of its own (Equinox
 * and Felix bundle entry URLs of a directory yield no bytes), and files below it whose names were never passed to
 * getResource must not be served. HttpContext.getResource javadoc (102.10.2.5): "Maps a resource name to a URL.
 * Called by the Http Service to map a resource name to a URL. For servlet registrations, Http Service will call this
 * method to support the ServletContext methods getResource and getResourceAsStream. For resource registrations, Http
 * Service will call this method to locate the named resource."
 *
 * <h2>Code location</h2>
 * wildfly/integration/http/src/main/java/org/jboss/as/osgi/httpservice/ResourceServlet.java:57-83: the URL returned
 * by {@code context.getResource(name + path)} is opened and streamed without checking whether it denotes a directory.
 * For bundle URLs of directories the framework's handler
 * (jbosgi-framework core/src/main/java/org/jboss/osgi/framework/internal/RevisionContent.java:219-229,
 * {@code real.getStreamURL().openConnection()}) delegates to jbosgi-vfs
 * vfs30/src/main/java/org/jboss/osgi/vfs30/VirtualFileAdaptor30.java:167-190, which for a directory writes a JAR of
 * the whole subtree ({@code VirtualJarInputStream}) to a temp dir and returns that file's URL.
 *
 * <h2>Root cause</h2>
 * ResourceServlet treats every non-null URL as a file; the jbosgi bundle URL of a directory streams a JAR of the
 * subtree instead of empty content.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=11290 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=1300 \
 *     -pl testsuite/integration test -Dtest=HttpResourceDirectoryArchiveIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local
 * Maven repository and the wildfly/03 surefire argLine change on JDK 24+. The -Darq options start the managed server
 * with a port offset so the test cannot connect to another running server.)
 *
 * <h2>Observed</h2>
 * <pre>
 * testDirectoryRequestBypassesGetResourceFilter: Expected GET /hdirzip/ not to return the content of
 *   www/private/secret.txt, which the HttpContext refuses to provide (getResource calls [/www/private/secret.txt,
 *   /www/]), but got status 200 with a 414 byte ZIP archive containing it
 * testDirectoryRequestNotServedAsArchive: Expected GET /hdirzip-def/private (a directory of the bundle) to be
 *   answered with 404 like any name without content, but got status 200, Content-Type content/unknown and a 162 byte
 *   ZIP archive of the directory
 * </pre>
 *
 * <h2>Expected</h2>
 * 404 for directory names (as Felix Http Jetty does); in no case content of files whose names were not passed to the
 * HttpContext.
 *
 * <h2>Reference behaviour</h2>
 * Felix 7.0.5 + Felix Http Jetty 4.0.6 (javax build of the same fuzz bundle, same registrations): {@code GET /fzres/},
 * {@code /fzres/sub}, {@code /fzres/sub/} all 404.
 *
 * <h2>Suggested fix (not applied)</h2>
 * In ResourceServlet, answer 404 when the target name or the URL path ends with '/' or the URL denotes a directory
 * (for bundle URLs e.g. {@code bundle.getEntry(name + "/") != null} or a zero-length/directory check on the
 * connection), before opening the stream. Verified: with a ResourceServlet that answers 404 when the target name or
 * the URL path ends with '/' (installed into the testsuite server in a local copy) both test methods pass.
 * Independently, the framework's bundle URL handler should return an empty
 * stream for directory entries as Equinox and Felix do (which also removes the temporary JAR copies of subrepos/14).
 *
 * <h2>Regression vs WF41</h2>
 * no (measured on a WF41 server built from origin/WF41 77f95be6 on the same patched WildFly 41.0.1, JDK 25: same
 * failure). The ResourceServlet and the VFS directory stream are pre-existing and unchanged on the R8 branch. On WF41
 * the bundle jbosgi-wildfly-http 2.12.0.Final starts ("JBOSGI011002: Bundle started:
 * jbosgi-wildfly-http:2.12.0.Final", regression-2
 * srv-results/HttpResourceDirectoryArchiveIssueTestCase.server.log:143): it is module backed
 * (org/jboss/as/osgi/http/main/module.xml), so its javax.servlet Import-Package does not stop it, and the test reaches
 * the defect there: GET /hdirzip/ returns the content of www/private/secret.txt with status 200 and GET
 * /hdirzip-def/private returns a ZIP archive of the directory, exactly as on R8. The earlier reasoning (the bundle
 * imported javax.servlet, did not resolve on WildFly 41 and the code path was unreachable) was wrong; the verdict "no"
 * stands. The WF41 run used harness adaptations only, no change to this test or to the Http Service code: the jacc
 * {@code <policy>} removed from standalone-osgi.xml (as r8 42d6c470 does), the r8 Arquillian
 * JBOSGiApplicationArchiveProcessor (54b65e85), and a provided dependency on jbosgi-wildfly-http in the
 * testsuite/integration pom (as on r8) so the test compiles; see the regression-2 workstream NOTES.md.
 */
@RunWith(Arquillian.class)
public class HttpResourceDirectoryArchiveIssueTestCase {

    private static final String SECRET = "top-secret-content";

    @ArquillianResource
    BundleContext context;

    @ArquillianResource
    Bundle bundle;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "http-resource-directory-archive-tests");
        archive.addAsResource(new StringAsset("public-content"), "www/index.txt");
        archive.addAsResource(new StringAsset(SECRET), "www/private/secret.txt");
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

    /** Response of a GET on the server's HTTP port (jboss.http.port + jboss.socket.binding.port-offset) */
    static class Response {
        int status;
        String contentType;
        byte[] body;

        boolean isZip() {
            return body.length >= 4 && body[0] == 'P' && body[1] == 'K' && body[2] == 3 && body[3] == 4;
        }

        boolean contains(String text) {
            return new String(body, StandardCharsets.ISO_8859_1).contains(text) || zipContains(text);
        }

        private boolean zipContains(String text) {
            if (!isZip())
                return false;
            try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(body))) {
                while (zin.getNextEntry() != null) {
                    if (new String(zin.readAllBytes(), StandardCharsets.ISO_8859_1).contains(text))
                        return true;
                }
            } catch (IOException ex) {
                // not a readable archive
            }
            return false;
        }
    }

    private static Response get(String path) throws IOException {
        int port = Integer.getInteger("jboss.http.port", 8080) + Integer.getInteger("jboss.socket.binding.port-offset", 0);
        HttpURLConnection con = (HttpURLConnection) new URL("http://localhost:" + port + path).openConnection();
        con.setConnectTimeout(5000);
        con.setReadTimeout(5000);
        con.setInstanceFollowRedirects(false);
        Response res = new Response();
        res.status = con.getResponseCode();
        res.contentType = con.getContentType();
        InputStream in = res.status < 400 ? con.getInputStream() : con.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            in.transferTo(out);
            in.close();
        }
        res.body = out.toByteArray();
        return res;
    }

    /** An HttpContext that only hands out names that do not contain "private" */
    static class FilteringContext implements HttpContext {
        final List<String> resources = Collections.synchronizedList(new ArrayList<String>());
        private final Bundle bundle;

        FilteringContext(Bundle bundle) {
            this.bundle = bundle;
        }

        @Override
        public boolean handleSecurity(HttpServletRequest request, HttpServletResponse response) throws IOException {
            return true;
        }

        @Override
        public URL getResource(String name) {
            resources.add(name);
            return name.contains("private") ? null : bundle.getResource(name);
        }

        @Override
        public String getMimeType(String name) {
            return null;
        }
    }

    @Test
    public void testDirectoryRequestBypassesGetResourceFilter() throws Exception {
        HttpService http = httpService();
        FilteringContext ctx = new FilteringContext(bundle);
        http.registerResources("/hdirzip", "/www", ctx);
        try {
            Response direct = get("/hdirzip/private/secret.txt");
            Assert.assertFalse("Precondition: the HttpContext refuses www/private/secret.txt, so GET /hdirzip/private/secret.txt must not serve it",
                    direct.status == 200 && direct.contains(SECRET));
            Response dir = get("/hdirzip/");
            Assert.assertFalse("Expected GET /hdirzip/ not to return the content of www/private/secret.txt, which the HttpContext refuses to "
                    + "provide (getResource calls " + ctx.resources + "), but got status " + dir.status + " with a " + dir.body.length
                    + " byte " + (dir.isZip() ? "ZIP archive" : "body") + " containing it", dir.contains(SECRET));
        } finally {
            unregisterQuietly(http, "/hdirzip");
        }
    }

    @Test
    public void testDirectoryRequestNotServedAsArchive() throws Exception {
        HttpService http = httpService();
        http.registerResources("/hdirzip-def", "/www", null);
        try {
            Response file = get("/hdirzip-def/index.txt");
            Assert.assertEquals("Precondition: GET /hdirzip-def/index.txt serves www/index.txt", 200, file.status);
            Response dir = get("/hdirzip-def/private");
            Assert.assertTrue("Expected GET /hdirzip-def/private (a directory of the bundle) to be answered with 404 like any name without "
                    + "content, but got status " + dir.status + ", Content-Type " + dir.contentType + " and a " + dir.body.length + " byte "
                    + (dir.isZip() ? "ZIP archive of the directory" : "body"), dir.status == 404 && !dir.isZip());
        } finally {
            unregisterQuietly(http, "/hdirzip-def");
        }
    }
}
