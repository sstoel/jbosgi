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
import java.io.File;
import java.io.FileOutputStream;
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
import org.osgi.framework.BundleException;

/**
 * The webbundle: URL handler ignores most of 128.4: it replaces the manifest by a fixed one (losing the headers of an
 * input WAB, ignoring Bundle-Version, WEB-INF/lib and other parameters), does not validate parameters, treats parameter
 * names case-sensitively and fails with NullPointerException instead of BundleException.
 *
 * <h2>Severity</h2>
 * major. Installing WARs/WABs through {@code webbundle:} URLs (the standard way to deploy a plain WAR into an OSGi
 * framework, used by management agents and Apache Aries/Karaf-style tooling) produces wrong bundles: a WAB loses its
 * symbolic name, version and imports and its web application is not deployed; WARs with libraries in WEB-INF/lib miss
 * those classes; requested versions are dropped; invalid input is accepted, or crashes with NullPointerException.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/integration/web, {@code org.jboss.as.osgi.web.WebBundleURIParser} (used by
 * DeploymentProviderIntegration / WebBundleURLStreamHandler).
 *
 * <h2>Found by</h2>
 * wildfly workstream, Enterprise R7 128.4 (Web URL Handler) spec alignment (exploratory checks U4-U10 of
 * ExploreWebbundleUrlTestCase).
 *
 * <h2>Spec reference</h2>
 * OSGi Enterprise R7 128.4: "The URL handler must validate query parameters, and ensure that the manifest rewriting
 * results in valid OSGi headers. Any validation failures must result in Bundle Exception being thrown and the bundle
 * install must fail." 128.4.1: "The parameter names are case insensitive, but their values must be treated as case
 * sensitive." Table 128.3: "Bundle-Version - The version of the resulting WAB. The value of this parameter must follow
 * the OSGi versioning syntax." "Bundle-ManifestVersion - ... the only valid value for this parameter is 2."
 * "Web-ContextPath ... This parameter must be specified." 128.4.4: "For WAB Modification, the Web URL Handler must only
 * support the Web-ContextPath parameter and it must not modify any existing headers other than the Web-ContextPath. Any
 * other parameter given must result in a Bundle Exception." 128.4.5: "Bundle-SymbolicName - Generated in an
 * implementation specific way. Bundle-ClassPath - Must consist of: WEB-INF/classes, All JARs from the WEB-INF/lib
 * directory in the WAR." "Any other manifest headers defined as a parameter or WAR manifest header not described in
 * this section must be copied to the WAB manifest". https://docs.osgi.org/specification/osgi.enterprise/7.0.0/service.war.html
 *
 * <h2>Code location</h2>
 * wildfly/integration/web/src/main/java/org/jboss/as/osgi/web/WebBundleURIParser.java:55-113: only parameters that start
 * with the exact strings "Bundle-SymbolicName" and "Web-ContextPath" are read (lines 79-88); without Web-ContextPath the
 * context path is derived from {@code uri.getHost()}, which is null for file: URLs -&gt; NullPointerException at line 93;
 * the returned manifest is always the same fixed set (lines 103-110: Bundle-ManifestVersion 2, the given symbolic name or
 * none, Web-ContextPath, fixed jakarta.servlet/org.osgi.framework imports, Bundle-ClassPath WEB-INF/classes), whatever the
 * input archive's manifest or WEB-INF/lib contain.
 *
 * <h2>Root cause</h2>
 * The handler synthesizes a manifest from two parameters instead of transforming the input manifest as 128.4.4/128.4.5
 * describe, and does no validation.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * # JDK 24+: in testsuite/pom.xml set &lt;surefire.security.args&gt;-Dno.sm=true&lt;/surefire.security.args&gt; (wildfly/03, TestJvmSecurityManagerIssueTestCase); a -D override on the command line does not reach the @{...} argLine
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/example test -Dtest=WebbundleUrlHandlerIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository. The two -Darq... options start the managed server with port offset 500, so the test cannot connect to another server on 9990 (arquillian.xml has allowConnectingToRunningServer=true); they can be left out when no other server runs.)
 * <h2>Observed</h2>
 * <pre>
 * testMissingContextPathIsBundleException: Expected BundleException for a webbundle: URL without Web-ContextPath, but got
 *   java.lang.NullPointerException: Cannot invoke "String.endsWith(String)" because "contextPath" is null
 * testParameterNamesCaseInsensitive: Expected the parameter web-contextpath (lower case) to be accepted, but the install
 *   failed with java.lang.NullPointerException ...
 * testBundleVersionParameter: Expected Bundle-Version 2.3.4 from the URL parameter, but the WAB has Bundle-Version null
 * testInvalidParametersAreBundleException: Expected BundleException for Bundle-Version=not.a.version, but the bundle was installed
 * testWabModificationKeepsHeaders: Expected the WAB modified through webbundle: to keep Bundle-SymbolicName webbundle-input.jar
 *   and Bundle-Version 1.0.0, but it has null and null
 * testWabModificationRejectsOtherParameters: Expected BundleException for a Bundle-Version parameter on a WAB input, but the bundle was installed
 * testWebInfLibOnBundleClassPath: Expected Bundle-ClassPath to contain WEB-INF/lib/library.jar, but it is WEB-INF/classes
 * </pre>
 *
 * <h2>Expected</h2>
 * As quoted above: BundleException for missing or invalid parameters, case-insensitive parameter names, parameters
 * applied as headers, WAB headers preserved except Web-ContextPath, WEB-INF/lib jars on the Bundle-ClassPath.
 *
 * <h2>Reference behaviour</h2>
 * Not checked (Pax Web's and Aries' webbundle handlers implement the transformation).
 *
 * <h2>Suggested fix (not applied)</h2>
 * Rewrite WebBundleURIParser to read the input archive: if it carries OSGi headers, accept only Web-ContextPath and
 * replace that header; otherwise copy the WAR manifest, apply all parameters (case-insensitive names) as headers,
 * validate Bundle-Version/Bundle-ManifestVersion/Web-ContextPath (throw BundleException), add WEB-INF/classes and all
 * WEB-INF/lib/*.jar to Bundle-ClassPath.
 *
 * <h2>Regression vs WF41</h2>
 * no (WebBundleURIParser unchanged since the WF18 port, b73edaed).
 */
@RunWith(Arquillian.class)
public class WebbundleUrlHandlerIssueTestCase {

    static final String WAR = "webbundle-input.war";
    static final String WAR_WITH_LIB = "webbundle-input-lib.war";
    static final String WAB = "webbundle-input.jar";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @WebServlet("/info")
    public static class PlainServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
            res.getWriter().print("plain servlet");
        }
    }

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "webbundle-url-tests");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = WAR, managed = false, testable = false)
    public static WebArchive getWar() {
        WebArchive war = ShrinkWrap.create(WebArchive.class, WAR);
        war.addClasses(PlainServlet.class);
        return war;
    }

    @Deployment(name = WAR_WITH_LIB, managed = false, testable = false)
    public static WebArchive getWarWithLib() {
        WebArchive war = ShrinkWrap.create(WebArchive.class, WAR_WITH_LIB);
        war.addClasses(PlainServlet.class);
        war.addAsLibrary(ShrinkWrap.create(JavaArchive.class, "library.jar").addAsResource(new StringAsset("lib"), "library.txt"));
        return war;
    }

    @Deployment(name = WAB, managed = false, testable = false)
    public static JavaArchive getWab() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, WAB);
        archive.addClasses(PlainServlet.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addBundleVersion("1.0.0");
                builder.addManifestHeader("Web-ContextPath", "/webbundle-original");
                builder.addImportPackages("jakarta.servlet", "jakarta.servlet.http", "jakarta.servlet.annotation");
                return builder.openStream();
            }
        });
        return archive;
    }

    private String fileUrl(String deployment, String suffix) throws Exception {
        File file = File.createTempFile("webbundle-issue", suffix);
        file.deleteOnExit();
        try (InputStream in = deployer.getDeployment(deployment); FileOutputStream out = new FileOutputStream(file)) {
            in.transferTo(out);
        }
        return file.toURI().toURL().toString();
    }

    /** Installs the location; returns the bundle or throws what installBundle threw */
    private Bundle install(String location) throws Exception {
        return context.installBundle(location);
    }

    private static void uninstallQuietly(Bundle bundle) {
        if (bundle != null) {
            try {
                bundle.uninstall();
            } catch (Exception ex) {
                // ignore
            }
        }
    }

    private void assertInstallFails(String location, String what) throws Exception {
        Bundle bundle = null;
        try {
            bundle = install(location);
            Assert.fail("Expected BundleException for " + what + ", but the bundle was installed");
        } catch (BundleException ex) {
            // expected
        } catch (Exception ex) {
            Assert.fail("Expected BundleException for " + what + ", but got " + ex);
        } finally {
            uninstallQuietly(bundle);
        }
    }

    static String get(String path) throws IOException {
        int port = Integer.getInteger("jboss.http.port", 8080) + Integer.getInteger("jboss.socket.binding.port-offset", 0);
        HttpURLConnection con = (HttpURLConnection) new URL("http://localhost:" + port + path).openConnection();
        int status = con.getResponseCode();
        InputStream in = status < 400 ? con.getInputStream() : con.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            in.transferTo(out);
            in.close();
        }
        return status + " " + out.toString("UTF-8");
    }

    @Test
    public void testMissingContextPathIsBundleException() throws Exception {
        assertInstallFails("webbundle:" + fileUrl(WAR, ".war"), "a webbundle: URL without Web-ContextPath");
    }

    @Test
    public void testParameterNamesCaseInsensitive() throws Exception {
        Bundle bundle = null;
        try {
            bundle = install("webbundle:" + fileUrl(WAR, ".war") + "?web-contextpath=/webbundle-lower");
            Assert.assertEquals("Expected the Web-ContextPath header from the lower case parameter", "/webbundle-lower", bundle.getHeaders().get("Web-ContextPath"));
        } catch (Exception ex) {
            Assert.fail("Expected the parameter web-contextpath (lower case) to be accepted, but the install failed with " + ex);
        } finally {
            uninstallQuietly(bundle);
        }
    }

    @Test
    public void testBundleVersionParameter() throws Exception {
        Bundle bundle = install("webbundle:" + fileUrl(WAR, ".war") + "?Web-ContextPath=/webbundle-version&Bundle-Version=2.3.4");
        try {
            Assert.assertEquals("Expected Bundle-Version 2.3.4 from the URL parameter, but the WAB has Bundle-Version " + bundle.getHeaders().get("Bundle-Version"),
                    "2.3.4", bundle.getHeaders().get("Bundle-Version"));
        } finally {
            uninstallQuietly(bundle);
        }
    }

    @Test
    public void testInvalidParametersAreBundleException() throws Exception {
        String war = fileUrl(WAR, ".war");
        assertInstallFails("webbundle:" + war + "?Web-ContextPath=/webbundle-badversion&Bundle-Version=not.a.version", "Bundle-Version=not.a.version");
        assertInstallFails("webbundle:" + war + "?Web-ContextPath=/webbundle-badmv&Bundle-ManifestVersion=1", "Bundle-ManifestVersion=1");
    }

    @Test
    public void testWabModificationKeepsHeaders() throws Exception {
        Bundle bundle = install("webbundle:" + fileUrl(WAB, ".jar") + "?Web-ContextPath=/webbundle-modified");
        try {
            Object bsn = bundle.getHeaders().get("Bundle-SymbolicName");
            Object version = bundle.getHeaders().get("Bundle-Version");
            Assert.assertTrue("Expected the WAB modified through webbundle: to keep Bundle-SymbolicName " + WAB + " and Bundle-Version 1.0.0, but it has "
                    + bsn + " and " + version, WAB.equals(bsn) && "1.0.0".equals(version));
            Assert.assertEquals("Expected the modified Web-ContextPath", "/webbundle-modified", bundle.getHeaders().get("Web-ContextPath"));
        } finally {
            uninstallQuietly(bundle);
        }
    }

    @Test
    public void testWabModificationRejectsOtherParameters() throws Exception {
        assertInstallFails("webbundle:" + fileUrl(WAB, ".jar") + "?Web-ContextPath=/webbundle-wabparam&Bundle-Version=3.0.0", "a Bundle-Version parameter on a WAB input");
    }

    @Test
    public void testWebInfLibOnBundleClassPath() throws Exception {
        Bundle bundle = install("webbundle:" + fileUrl(WAR_WITH_LIB, ".war") + "?Web-ContextPath=/webbundle-lib");
        try {
            String bcp = String.valueOf(bundle.getHeaders().get("Bundle-ClassPath"));
            Assert.assertTrue("Expected Bundle-ClassPath to contain WEB-INF/lib/library.jar, but it is " + bcp, bcp.contains("WEB-INF/lib/library.jar"));
        } finally {
            uninstallQuietly(bundle);
        }
    }
}
