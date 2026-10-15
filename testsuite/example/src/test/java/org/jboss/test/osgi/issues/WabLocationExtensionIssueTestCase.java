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
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;

import jakarta.servlet.ServletContext;
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
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

/**
 * A bundle with a Web-ContextPath header is only deployed as a Web Application when its location (deployment name)
 * ends with ".jar" (or ".wab"/"webbundle:"); a WAB installed under any other location is never deployed.
 *
 * <h2>Severity</h2>
 * major. Bundle locations are arbitrary strings in OSGi (URLs such as {@code mvn:...}, {@code file:/.../app.wab?x},
 * or plain names chosen by a management agent). A valid WAB installed with BundleContext.installBundle under such a
 * location starts without error, but its Web Application is never deployed and no ServletContext service appears.
 *
 * <h2>Component</h2>
 * WildFly fork (41.0.1.Final-osgi): web-common {@code org.jboss.as.web.common.WebApplicationBundleUtils} and undertow
 * {@code WarDeploymentInitializingProcessor}; used by jbosgi wildfly/integration/web (RemountDeploymentRootProcessor).
 *
 * <h2>Found by</h2>
 * wildfly workstream, Enterprise R7 128 (Web Applications) spec alignment: exploratory WAB checks failed for helper
 * WABs whose location had no extension (404, no ServletContext service) and passed after renaming them to *.jar.
 *
 * <h2>Spec reference</h2>
 * OSGi Enterprise R7 128.3.1 WAB Definition: "A WAB is differentiated from non Web Application bundles through the
 * specification of the additional manifest header: Web-ContextPath". 128.3.2: "A WAB's Web Application must be
 * deployed while the WAB is ready. ... Ready is when the WAB: Is in the ACTIVE state, or Has a lazy activation policy
 * and is in the STARTING state." Nothing depends on the bundle location. https://docs.osgi.org/specification/osgi.enterprise/7.0.0/service.war.html
 *
 * <h2>Code location</h2>
 * WildFly fork web-common/src/main/java/org/jboss/as/web/common/WebApplicationBundleUtils.java:35-50
 * ({@code if (manifest != null && deploymentName.endsWith(".jar")) { if (hasMainAttributeValue(manifest, "Web-ContextPath")) return true; }}
 * plus {@code deploymentName.startsWith("webbundle:")}); undertow WarDeploymentInitializingProcessor.java:29-36
 * (".war"/".wab" by name). The deployment name of a bundle installed through the BundleContext is its location.
 *
 * <h2>Root cause</h2>
 * WAB detection keys on the deployment name instead of only on the Web-ContextPath header of the bundle manifest.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * # JDK 24+: in testsuite/pom.xml set &lt;surefire.security.args&gt;-Dno.sm=true&lt;/surefire.security.args&gt; (wildfly/03, TestJvmSecurityManagerIssueTestCase); a -D override on the command line does not reach the @{...} argLine
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/example test -Dtest=WabLocationExtensionIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository. The two -Darq... options start the managed server with port offset 500, so the test cannot connect to another server on 9990 (arquillian.xml has allowConnectingToRunningServer=true); they can be left out when no other server runs.)
 * <h2>Observed</h2>
 * <pre>
 * java.lang.AssertionError: Expected the started WAB installed with location 'wab-location-without-extension'
 *   (Web-ContextPath /wab-location) to be deployed, but GET /wab-location/info gave 404 and no ServletContext
 *   service was registered
 * </pre>
 *
 * <h2>Expected</h2>
 * The Web Application is deployed (GET returns the servlet's answer) and the ServletContext service is registered,
 * whatever the location is.
 *
 * <h2>Reference behaviour</h2>
 * Not checked (Pax Web and Equinox/Jetty based extenders detect WABs by the header only).
 *
 * <h2>Suggested fix (not applied)</h2>
 * In WebApplicationBundleUtils.isWebApplicationBundle return true whenever the OSGi manifest has a Web-ContextPath
 * header, independent of the name.
 *
 * <h2>Regression vs WF41</h2>
 * no (measured on a WF41 server built from origin/WF41 77f95be6 on the same patched WildFly 41.0.1, JDK 25: same
 * failure). The WAB integration (wildfly/integration/web, last changed in the WF39 port) and the WildFly fork's
 * web-common/undertow patch are unchanged by the R8 branch (only the pom version). With the unmodified WF41
 * standalone-osgi.xml every deferred bundle start fails there (JBAS011969: org.wildfly.security.policy fails on
 * WildFly 41 because of the jacc {@code <policy>} element, and the WF41 deferred-start check counts every failed
 * service in the container, which R8 commit 6da002c5 narrowed); with that element removed, as r8 42d6c470 does, and
 * the r8 Arquillian JBOSGiApplicationArchiveProcessor (54b65e85), the WF41 server runs this test and it fails with the
 * same assertion as on R8. So the earlier statement that WABs could not be started on WF41 at all only holds for the
 * unmodified configuration.
 */
@RunWith(Arquillian.class)
public class WabLocationExtensionIssueTestCase {

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    /** The servlet of the helper WABs: answers with the symbolic name of its bundle */
    @WebServlet("/info")
    public static class InfoServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
            res.getWriter().print("bundle=" + FrameworkUtil.getBundle(getClass()).getSymbolicName());
        }
    }

    /** GET on the server's HTTP port (jboss.http.port + jboss.socket.binding.port-offset, default 8080); returns {status, body} */
    static String[] get(String path) throws IOException {
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
        return new String[] { String.valueOf(status), body.length() > 120 ? body.substring(0, 120) + "..." : body };
    }

    ServiceReference<ServletContext> servletContextService(Bundle bundle) throws Exception {
        Collection<ServiceReference<ServletContext>> refs = context.getServiceReferences(ServletContext.class, null);
        for (ServiceReference<ServletContext> ref : refs) {
            if (ref.getBundle() != null && ref.getBundle().getBundleId() == bundle.getBundleId())
                return ref;
        }
        return null;
    }

    static JavaArchive wab(final String name, final String contextPath) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.addClasses(InfoServlet.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addBundleVersion("1.0.0");
                if (contextPath != null)
                    builder.addManifestHeader("Web-ContextPath", contextPath);
                builder.addImportPackages(HttpServlet.class, WebServlet.class, ServletContext.class, FrameworkUtil.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    static JavaArchive testBundle(final String name, final Class<?>... extraImports) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(ServletContext.class);
                builder.addImportPackages(extraImports);
                return builder.openStream();
            }
        });
        return archive;
    }

    static final String WAB = "wab-location-without-extension";

    @Deployment
    public static JavaArchive deployment() {
        return testBundle("wab-location-tests");
    }

    @Deployment(name = WAB, managed = false, testable = false)
    public static JavaArchive getWab() {
        return wab(WAB, "/wab-location");
    }

    @Test
    public void testWabWithoutJarExtensionIsDeployed() throws Exception {
        Bundle bundle = context.installBundle(WAB, deployer.getDeployment(WAB));
        try {
            bundle.start();
            String[] res = get("/wab-location/info");
            ServiceReference<ServletContext> sref = servletContextService(bundle);
            Assert.assertTrue("Expected the started WAB installed with location '" + WAB + "' (Web-ContextPath /wab-location) to be deployed, but GET /wab-location/info gave "
                    + res[0] + (sref == null ? " and no ServletContext service was registered" : ""), "200".equals(res[0]) && sref != null);
        } finally {
            bundle.uninstall();
        }
    }
}
