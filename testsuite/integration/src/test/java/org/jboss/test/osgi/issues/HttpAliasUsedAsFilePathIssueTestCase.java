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
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.http.HttpService;

/**
 * HttpService.registerServlet/registerResources create a directory named after the alias below the server temp
 * directory; a valid alias whose path segment is longer than the file system allows (255 bytes on common file systems)
 * therefore cannot be registered: registerServlet throws NamespaceException "Failed to create context".
 *
 * <h2>Severity</h2>
 * minor. Only aliases with a segment longer than the file name limit are affected; the registration fails with a
 * NamespaceException, which the spec reserves for an alias that is already in use, so callers misread the cause.
 * The alias is used as a file system path without any normalization.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/integration/http, {@code org.jboss.as.osgi.httpservice.HttpServiceImpl.registerInternal}.
 *
 * <h2>Found by</h2>
 * TCK workstream: OSGi Http Service TCK R7 (org.osgi.test.cases.http built from github.com/osgi/osgi tag
 * r7-cmpn-ri-ct-final, transformed javax.servlet -> jakarta.servlet with Eclipse Transformer 1.0.0 because the RTR
 * Http Service API uses jakarta.servlet), deployed into the patched WildFly 41 server (JDK 25, port offset 800):
 * {@code HttpTestBundle1.testLongRegistrationName} errors with
 * {@code org.osgi.service.http.NamespaceException: JBAS018039: Failed to create context ... Caused by:
 * java.lang.RuntimeException: UT000202: Failed to initialize path manager for '<jboss.server.temp.dir>/abcdefghijklmnopqrstuvwxyz...xyz/osgiservlet-root'
 * path ... Caused by: java.nio.file.FileSystemException: ...: File name too long}. The untransformed TCK passes this test
 * on Equinox 3.18.600 and Felix 7.0.5 with Felix Http Jetty 4.0.6 (28/28), and on JBoss OSGi with Felix Http Jetty.
 *
 * <h2>Spec reference</h2>
 * OSGi Compendium R7/R8 102.10.3.3 HttpService.registerServlet
 * (https://docs.osgi.org/specification/osgi.cmpn/8.0.0/service.http.html): "An alias must begin with slash ('/')
 * and must not end with slash ('/'), with the exception that an alias of the form "/" is used to denote the root
 * alias." ... "Throws NamespaceException - if the registration fails because the alias is already in use.
 * javax.servlet.ServletException - if the servlet's init method throws an exception, or the given servlet object has
 * already been registered at a different alias. IllegalArgumentException - if any of the arguments are invalid".
 * A 260 character alias is valid and not in use.
 *
 * <h2>Code location</h2>
 * wildfly/integration/http/src/main/java/org/jboss/as/osgi/httpservice/HttpServiceImpl.java:109-110
 * ({@code File storageDir = new File(serverEnvironment.getServerTempDir() + File.separator + alias + File.separator +
 * "osgiservlet-root"); storageDir.mkdirs();}) and :126 ({@code deploymentBuilder.setDocumentRoot(storageDir)});
 * the failure is wrapped at :163-167 into {@code NamespaceException(WEB_LOGGER.createContextFailed(), ex)}. Last
 * changed in the WF30 port e4d06ba7.
 *
 * <h2>Root cause</h2>
 * The alias, a URI path, is used verbatim as a relative file path for the (empty) document root of the Undertow
 * deployment. Undertow's PathResourceManager cannot be created for a path with a segment longer than the file name
 * limit.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10790 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=800 \
 *     -pl testsuite/integration test -Dtest=HttpAliasUsedAsFilePathIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local
 * Maven repository. The -Darq... options start the managed server with port offset 800.)
 *
 * <h2>Observed</h2>
 * <pre>
 * java.lang.AssertionError: A valid alias with a 260 character segment must be registrable, but registerServlet
 *   failed: org.osgi.service.http.NamespaceException: JBAS018039: Failed to create context
 * </pre>
 *
 * <h2>Expected</h2>
 * The servlet is registered and answers under the long alias (GET returns "long alias").
 *
 * <h2>Reference behaviour</h2>
 * Felix Http Jetty 4.0.6 on Equinox 3.18.600, Felix 7.0.5 and JBoss OSGi: the TCK test testLongRegistrationName
 * passes (servlet registered and reachable under the 260 character alias).
 *
 * <h2>Suggested fix (not applied)</h2>
 * Do not derive a file path from the alias: use one generated directory name per registration (e.g. a counter or a
 * UUID below the server temp dir), or no document root at all for servlet registrations.
 * Not verified with a patched build.
 *
 * <h2>Regression vs WF41</h2>
 * no (measured on a WF41 server built from origin/WF41 77f95be6 on the same patched WildFly 41.0.1, JDK 25: same
 * failure). The code is unchanged since the WF30 port e4d06ba7. On WF41 the bundle jbosgi-wildfly-http 2.12.0.Final
 * starts ("JBOSGI011002: Bundle started: jbosgi-wildfly-http:2.12.0.Final", regression-2
 * srv-results/HttpAliasUsedAsFilePathIssueTestCase.server.log:143): it is module backed
 * (org/jboss/as/osgi/http/main/module.xml), so its javax.servlet Import-Package does not stop it, and the test reaches
 * the defect there: registerServlet with a 260 character alias segment fails with NamespaceException JBAS018039:
 * Failed to create context, exactly as on R8. The earlier reasoning (the bundle imported javax.servlet, did not
 * resolve on WildFly 41 and the code path was unreachable) was wrong; the verdict "no" stands. The WF41 run used
 * harness adaptations only, no change to this test or to the Http Service code: the jacc {@code <policy>} removed from
 * standalone-osgi.xml (as r8 42d6c470 does), the r8 Arquillian JBOSGiApplicationArchiveProcessor (54b65e85), and a
 * provided dependency on jbosgi-wildfly-http in the testsuite/integration pom (as on r8) so the test compiles; see the
 * regression-2 workstream NOTES.md.
 */
@RunWith(Arquillian.class)
public class HttpAliasUsedAsFilePathIssueTestCase {

    private static final String LONG_ALIAS = "/" + "abcdefghijklmnopqrstuvwxyz".repeat(10);

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "http-alias-file-path-tests");
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
        return new String[] { String.valueOf(status), out.toString("UTF-8") };
    }

    @Test
    public void testLongAliasCanBeRegistered() throws Exception {
        Assert.assertEquals("Precondition: alias length", 261, LONG_ALIAS.length());
        ServiceReference<HttpService> sref = context.getServiceReference(HttpService.class);
        Assert.assertNotNull("Precondition: HttpService available", sref);
        HttpService http = context.getService(sref);
        try {
            try {
                http.registerServlet(LONG_ALIAS, new TextServlet("long alias"), null, null);
            } catch (Exception ex) {
                Assert.fail("A valid alias with a 260 character segment must be registrable, but registerServlet failed: " + ex);
            }
            String[] response = get(LONG_ALIAS);
            Assert.assertEquals("Status of GET " + LONG_ALIAS.substring(0, 20) + "...", "200", response[0]);
            Assert.assertEquals("Body of GET under the long alias", "long alias", response[1]);
        } finally {
            try {
                http.unregister(LONG_ALIAS);
            } catch (RuntimeException ex) {
                // not registered
            }
            context.ungetService(sref);
        }
    }
}
