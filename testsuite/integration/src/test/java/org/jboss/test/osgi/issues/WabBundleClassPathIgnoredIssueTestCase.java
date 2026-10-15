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

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * WildFly builds the web application of a WAB from the WAR layout instead of the Bundle-ClassPath: a JAR on the
 * Bundle-ClassPath outside WEB-INF/lib is not treated like a WEB-INF/lib JAR (its @WebServlet annotations and its tag
 * libraries are ignored), and a JAR in WEB-INF/lib that is not on the Bundle-ClassPath is scanned anyway, so an annotated
 * servlet in it makes the whole WAB fail to deploy with ClassNotFoundException.
 *
 * <h2>Severity</h2>
 * major. WABs that are not plain WARs with a manifest (bnd/maven-bundle-plugin layouts such as
 * {@code Bundle-ClassPath: WEB-INF/classes, lib/x.jar}, or WEB-INF/lib JARs left out of the class path on purpose) get
 * servlets, filters, listeners and tags silently dropped, or do not deploy at all. 128.3.7 of the specification uses
 * exactly this layout as its example.
 *
 * <h2>Component</h2>
 * RTR WildFly fork (41.0.1.Final-osgi), undertow: {@code WarStructureDeploymentProcessor} (resource roots and annotation
 * index of WEB-INF/lib for WABs), {@code WarMetaDataProcessor} (annotations only from "classes" or the deployment root),
 * TLD scanning ({@code TldParsingDeploymentProcessor}); jbosgi wildfly/integration/web (no WAB-specific mapping of the
 * Bundle-ClassPath to web resource roots).
 *
 * <h2>Found by</h2>
 * wildfly-ee workstream (deep 2), WAB feature exploration with the content-serving example of 128.3.7
 * (Bundle-ClassPath: WEB-INF/classes, LIB/bar.jar plus WEB-INF/lib/foo.jar) on the patched WildFly 41.0.1 server (JDK 25).
 *
 * <h2>Spec reference</h2>
 * OSGi Enterprise R7, 128.3.6 Dynamic Content: "JARs that are specified in the Bundle-ClassPath header are treated like
 * JARs in the WEB-INF/lib/ directory of the Servlet specification. Similarly, any directory that is part of the
 * Bundle-ClassPath header is treated like WEB-INF/classes directory of the Servlet specification." 128.3.7 Content
 * Serving Example (Bundle-ClassPath: WEB-INF/classes, LIB/bar.jar; WEB-INF/lib/foo.jar): "the tag classes in bar.jar must
 * be found (if JSP is supported) but the tag classes in foo.jar must not because foo.jar is not part of the bundle class
 * path." 128.6.4: if metadata-complete is not set "the container should process the class files of the Web Application
 * for annotations". Servlet 6.0, 8.2: annotations of WEB-INF/lib JARs are processed.
 * https://docs.osgi.org/specification/osgi.enterprise/7.0.0/service.war.html#i1771870
 *
 * <h2>Code location</h2>
 * WildFly fork undertow/src/main/java/org/wildfly/extension/undertow/deployment/WarStructureDeploymentProcessor.java:93-118
 * (for a WAB only the handling of the deployment root is changed; WEB-INF/lib/*.jar are still added as indexed resource
 * roots, Bundle-ClassPath entries outside WEB-INF are not); WarMetaDataProcessor.java:231-235 (annotated metadata from
 * "classes" or, for a WAB without WEB-INF/classes, the deployment root only). Class loading of the WAB uses the
 * Bundle-ClassPath (jbosgi HostBundleClassLoader), so the indexed WEB-INF/lib classes cannot be loaded.
 *
 * <h2>Root cause</h2>
 * The web metadata (annotation index, TLDs, servlet classes) and the class loader of a WAB come from two different
 * views of the archive: the WAR layout for the web container, the Bundle-ClassPath for the OSGi class loader.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=WabBundleClassPathIgnoredIssueTestCase \
 *     -Darq.container.jboss.configuration.managementPort=11090 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=1100
 * </pre>
 * (Prerequisites: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork in the local Maven
 * repository; on JDK 24+ the testsuite needs surefire.security.args without -Djava.security.manager, see
 * TestJvmSecurityManagerIssueTestCase.) Deterministic. The HTTP port is jboss.http.port + jboss.socket.binding.port-offset.
 *
 * <h2>Observed</h2>
 * <pre>
 * testAnnotatedServletInBundleClassPathJar: org.junit.ComparisonFailure: The @WebServlet in LIB/bar.jar (on the
 *   Bundle-ClassPath, to be treated like a WEB-INF/lib JAR) must be deployed expected:&lt;[200 bar-servlet]&gt; but
 *   was:&lt;[404 Error Not Found]&gt;   (the same jar as WEB-INF/lib/bar.jar: 200 bar-servlet, checked first)
 * testTagLibraryInBundleClassPathJar: org.junit.ComparisonFailure: The tag library of LIB/bar.jar (on the
 *   Bundle-ClassPath) must be found by a JSP (128.3.7) expected:&lt;[200 bar-tag]&gt; but was:&lt;[500 ...
 *   org.apache.jasper.JasperException: JBWEB004113: The absolute uri: urn:wab-bar cannot be resolved in either web.xml
 *   or the jar files deployed with this application]&gt;   (as WEB-INF/lib/bar.jar: 200 bar-tag)
 * testAnnotatedServletInJarOutsideBundleClassPath: java.lang.AssertionError: A WAB with an annotated servlet in
 *   WEB-INF/lib/foo.jar, which is not on its Bundle-ClassPath, must deploy (foo.jar is not part of the web
 *   application), but the deployment failed: java.lang.Exception: {"WFLYCTL0080: Failed services" =&gt;
 *   {"jboss.deployment.unit.\"wab-not-on-bcp.war\".undertow-deployment.UndertowDeploymentInfoService" =&gt;
 *   "java.lang.ClassNotFoundException: org.jboss.test.osgi.issues.WabBundleClassPathIgnoredIssueTestCase$FooServlet
 *   from [Module \"deployment.wab-not-on-bcp.war\" from Service Module Loader]"}}
 * </pre>
 *
 * <h2>Expected</h2>
 * /wab-bcp/bar (servlet annotated in LIB/bar.jar) returns 200, the JSP using the tag file of LIB/bar.jar's TLD renders
 * the tag; wab-not-on-bcp.war deploys and ignores WEB-INF/lib/foo.jar (its servlet is not deployed).
 *
 * <h2>Reference behaviour</h2>
 * Not run here. Web Extenders built on the Bundle-ClassPath (e.g. Pax Web) map Bundle-ClassPath JARs to WEB-INF/lib for
 * annotation and TLD scanning.
 *
 * <h2>Suggested fix (not applied)</h2>
 * For a WAB derive the web resource roots from the Bundle-ClassPath: directories as WEB-INF/classes, JARs as WEB-INF/lib
 * JARs (index them, scan TLDs, web-fragment.xml and ServletContainerInitializers), and do not add WEB-INF/lib JARs that are
 * not on the Bundle-ClassPath.
 *
 * <h2>Regression vs WF41</h2>
 * no (measured on a WF41 server built from origin/WF41 77f95be6 on the same patched WildFly 41.0.1, JDK 25: same
 * failure), all three methods. The WF41 run used harness adaptations only, no change to this test or to the code under
 * test: the jacc {@code <policy>} removed from standalone-osgi.xml, as r8 42d6c470 does (without it
 * org.wildfly.security.policy fails on WildFly 41 and every deferred start fails with JBAS011969), and the r8
 * Arquillian JBOSGiApplicationArchiveProcessor from 54b65e85 (in-container ManagementClient lookup); see the
 * regression-2 workstream NOTES.md.
 */
@RunWith(Arquillian.class)
public class WabBundleClassPathIgnoredIssueTestCase {

    static final String WAB = "wab-bcp.war";
    static final String WAB_NOT_ON_BCP = "wab-not-on-bcp.war";
    static final String WAB_WEB_INF_LIB = "wab-weblib.war";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    ManagementClient managementClient;

    @WebServlet("/bar")
    public static class BarServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().print("bar-servlet");
        }
    }

    @WebServlet("/foo")
    public static class FooServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().print("foo-servlet");
        }
    }

    @Deployment
    public static JavaArchive testDeployment() {
        final JavaArchive jar = ShrinkWrap.create(JavaArchive.class, "wab-bundle-classpath-tests");
        jar.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(jar.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(ManagementClient.class, ModelControllerClient.class, ServerDeploymentHelper.class);
                return builder.openStream();
            }
        });
        return jar;
    }

    // Control: the same bar.jar in WEB-INF/lib (and on the Bundle-ClassPath) works
    private void assertControlWab(String path, String expected) throws Exception {
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        server.deploy(WAB_WEB_INF_LIB, deployer.getDeployment(WAB_WEB_INF_LIB));
        try {
            Assert.assertEquals("Precondition: with bar.jar in WEB-INF/lib " + path + " works", expected, get("/wab-weblib" + path));
        } finally {
            server.undeploy(WAB_WEB_INF_LIB);
        }
    }

    @Test
    public void testAnnotatedServletInBundleClassPathJar() throws Exception {
        assertControlWab("/bar", "200 bar-servlet");
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        server.deploy(WAB, deployer.getDeployment(WAB));
        try {
            Assert.assertEquals("Precondition: the WAB is deployed", "200 static", get("/wab-bcp/static.txt"));
            Assert.assertEquals("The @WebServlet in LIB/bar.jar (on the Bundle-ClassPath, to be treated like a WEB-INF/lib JAR) must be deployed",
                    "200 bar-servlet", get("/wab-bcp/bar"));
        } finally {
            server.undeploy(WAB);
        }
    }

    @Test
    public void testTagLibraryInBundleClassPathJar() throws Exception {
        assertControlWab("/bartag.jsp", "200 bar-tag");
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        server.deploy(WAB, deployer.getDeployment(WAB));
        try {
            Assert.assertEquals("Precondition: JSP works in the WAB", "200 plain-jsp", get("/wab-bcp/plain.jsp"));
            Assert.assertEquals("The tag library of LIB/bar.jar (on the Bundle-ClassPath) must be found by a JSP (128.3.7)", "200 bar-tag", get("/wab-bcp/bartag.jsp"));
        } finally {
            server.undeploy(WAB);
        }
    }

    @Test
    public void testAnnotatedServletInJarOutsideBundleClassPath() throws Exception {
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        String failure = null;
        try {
            server.deploy(WAB_NOT_ON_BCP, deployer.getDeployment(WAB_NOT_ON_BCP));
        } catch (Exception ex) {
            failure = String.valueOf(ex.getCause() != null ? ex.getCause() : ex);
        }
        try {
            Assert.assertNull("A WAB with an annotated servlet in WEB-INF/lib/foo.jar, which is not on its Bundle-ClassPath, must deploy (foo.jar is not part of the web application), but the deployment failed: "
                    + failure, failure);
            Assert.assertTrue("The servlet in WEB-INF/lib/foo.jar (not on the Bundle-ClassPath) must not be deployed", get("/wab-not-on-bcp/foo").startsWith("404"));
        } finally {
            try {
                server.undeploy(WAB_NOT_ON_BCP);
            } catch (Exception ex) {
                // not deployed
            }
        }
    }

    private static String get(String path) throws Exception {
        int port = Integer.getInteger("jboss.http.port", 8080) + Integer.getInteger("jboss.socket.binding.port-offset", 0);
        HttpURLConnection con = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        con.setConnectTimeout(5000);
        con.setReadTimeout(10000);
        int code = con.getResponseCode();
        InputStream in = code < 400 ? con.getInputStream() : con.getErrorStream();
        String body = "";
        if (in != null) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            in.close();
            body = out.toString().replaceAll("(?s)<style>.*?</style>", "").replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        }
        return code + " " + (body.length() > 200 ? body.substring(0, 200) + "..." : body);
    }

    private static Asset manifest(final String symbolicName, final String bundleClassPath) {
        return new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(symbolicName);
                builder.addBundleManifestVersion(2);
                builder.addBundleClasspath(bundleClassPath);
                builder.addManifestHeader("Web-ContextPath", "/" + symbolicName);
                builder.addImportPackages("jakarta.servlet", "jakarta.servlet.http", "jakarta.servlet.annotation");
                return builder.openStream();
            }
        };
    }

    private static JavaArchive barJar() {
        JavaArchive bar = ShrinkWrap.create(JavaArchive.class, "bar.jar");
        bar.addClasses(BarServlet.class);
        bar.add(new StringAsset("<taglib xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"3.0\"><tlib-version>1.0</tlib-version><short-name>bar</short-name>"
                + "<uri>urn:wab-bar</uri><tag-file><name>hello</name><path>/META-INF/tags/hello.tag</path></tag-file></taglib>"), "META-INF/bar.tld");
        bar.add(new StringAsset("bar-tag"), "META-INF/tags/hello.tag");
        return bar;
    }

    private static WebArchive webContent(String name) {
        WebArchive war = ShrinkWrap.create(WebArchive.class, name);
        war.add(new StringAsset("static"), "static.txt");
        war.add(new StringAsset("<%= \"plain-jsp\" %>"), "plain.jsp");
        war.add(new StringAsset("<%@ taglib uri=\"urn:wab-bar\" prefix=\"b\" %><b:hello/>"), "bartag.jsp");
        return war;
    }

    // The layout of the 128.3.7 example: Bundle-ClassPath: WEB-INF/classes, LIB/bar.jar
    @Deployment(name = WAB, managed = false, testable = false)
    public static WebArchive getWab() {
        WebArchive war = webContent(WAB);
        war.add(barJar(), "LIB", ZipExporter.class);
        war.setManifest(manifest("wab-bcp", "WEB-INF/classes,LIB/bar.jar"));
        return war;
    }

    // Control: the same jar as WEB-INF/lib/bar.jar
    @Deployment(name = WAB_WEB_INF_LIB, managed = false, testable = false)
    public static WebArchive getWabWebInfLib() {
        WebArchive war = webContent(WAB_WEB_INF_LIB);
        war.addAsLibrary(barJar());
        war.setManifest(manifest("wab-weblib", "WEB-INF/classes,WEB-INF/lib/bar.jar"));
        return war;
    }

    // WEB-INF/lib/foo.jar is not on the Bundle-ClassPath
    @Deployment(name = WAB_NOT_ON_BCP, managed = false, testable = false)
    public static WebArchive getWabNotOnBundleClassPath() {
        WebArchive war = ShrinkWrap.create(WebArchive.class, WAB_NOT_ON_BCP);
        war.add(new StringAsset("static"), "static.txt");
        war.addAsLibrary(ShrinkWrap.create(JavaArchive.class, "foo.jar").addClasses(FooServlet.class));
        war.setManifest(manifest("wab-not-on-bcp", "WEB-INF/classes"));
        return war;
    }
}
