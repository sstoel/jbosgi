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
 * A WAB whose Web-ContextPath collides with a deployed WAB cannot be started (BundleException), and when the first WAB
 * is stopped the colliding WAB is not deployed.
 *
 * <h2>Severity</h2>
 * major. Starting the second WAB fails, so the bundle (and everything else it provides, services, activator) is down
 * just because its web part collides; and after the first application is undeployed (e.g. during an update) the
 * other application never takes over the path. The testsuite has the scenario as a skipped test
 * (WebAppSpecTestCase.testCollidingContextPath, "@Ignore [AS7-5653] Cannot restart webapp bundle after activation failure").
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/integration/web ({@code WebContextLifecycleInterceptor}: the web context is started inside
 * the bundle start and a failure fails the start); WildFly fork undertow (one context per path).
 *
 * <h2>Found by</h2>
 * wildfly workstream, Enterprise R7 128.3.2/128.3.8 spec alignment (exploratory WAB checks: second start threw
 * "JBAS011969: Cannot activate deferred module phase", the path was not taken over after the first WAB stopped).
 *
 * <h2>Spec reference</h2>
 * OSGi Enterprise R7 128.3.2: "Wait for the WAB to become ready. The following steps can take place asynchronously with
 * the starting of the WAB. ... If the Context Path value is already in use by another Web Application, then the Web
 * Application must not be deployed, and the deployment fails, see Failure. ... If the prior Web Application with the
 * same Context Path is undeployed later, this Web Application should be considered as a candidate". 128.3.3: "The
 * situation after the failure must be as if the WAB was never deployed." (the WAB itself stays ACTIVE). 128.3.8: "If
 * such colliding WABs exists then the Web Extender must attempt to deploy the colliding WAB with the lowest bundle
 * id." https://docs.osgi.org/specification/osgi.enterprise/7.0.0/service.war.html
 *
 * <h2>Code location</h2>
 * wildfly/integration/web/src/main/java/org/jboss/as/osgi/web/WebContextLifecycleInterceptor.java:86-104
 * ({@code if (!activator.startContext()) throw new LifecycleInterceptorException(...)} on the ACTIVE transition, which
 * aborts the bundle start; no bookkeeping of colliding WABs, nothing is deployed when a context is stopped).
 *
 * <h2>Root cause</h2>
 * The web application deployment is part of the bundle start transaction instead of an extender reaction to the
 * bundle becoming ready, and collisions are not tracked.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * # JDK 24+: in testsuite/pom.xml set &lt;surefire.security.args&gt;-Dno.sm=true&lt;/surefire.security.args&gt; (wildfly/03, TestJvmSecurityManagerIssueTestCase); a -D override on the command line does not reach the @{...} argLine
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/example test -Dtest=WabContextPathCollisionIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository. The two -Darq... options start the managed server with port offset 500, so the test cannot connect to another server on 9990 (arquillian.xml has allowConnectingToRunningServer=true); they can be left out when no other server runs.)
 * <h2>Observed</h2>
 * <pre>
 * testCollidingWabStartsAndTakesOverPath: Expected the colliding WAB to start (its web application just not being
 *   deployed), but start() failed: org.osgi.framework.BundleException: JBAS011969: Cannot activate deferred module phase for: wab-collision-2.jar:1.0.0
 * </pre>
 * (with the start exception ignored, the follow-up assertion fails too: after the first WAB stopped,
 * GET /wab-collision/info gave 404 instead of the second WAB's answer.)
 *
 * <h2>Expected</h2>
 * The second WAB becomes ACTIVE without a deployed web application (FAILED event with collision properties); when the
 * first WAB is stopped, the second one's web application is deployed at /wab-collision.
 *
 * <h2>Reference behaviour</h2>
 * Not checked.
 *
 * <h2>Suggested fix (not applied)</h2>
 * Deploy the web application asynchronously after the bundle has become ACTIVE (do not fail the start), keep a list of
 * collided WABs per context path and deploy the one with the lowest bundle id when the path becomes free.
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
public class WabContextPathCollisionIssueTestCase {

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

    static final String WAB1 = "wab-collision-1.jar";
    static final String WAB2 = "wab-collision-2.jar";

    @Deployment
    public static JavaArchive deployment() {
        return testBundle("wab-collision-tests");
    }

    @Deployment(name = WAB1, managed = false, testable = false)
    public static JavaArchive getWab1() {
        return wab(WAB1, "/wab-collision");
    }

    @Deployment(name = WAB2, managed = false, testable = false)
    public static JavaArchive getWab2() {
        return wab(WAB2, "/wab-collision");
    }

    @Test
    public void testCollidingWabStartsAndTakesOverPath() throws Exception {
        Bundle first = context.installBundle(WAB1, deployer.getDeployment(WAB1));
        Bundle second = context.installBundle(WAB2, deployer.getDeployment(WAB2));
        try {
            first.start();
            Assert.assertEquals("Precondition: the first WAB serves the path", "bundle=" + WAB1, get("/wab-collision/info")[1]);
            try {
                second.start();
            } catch (Exception ex) {
                Assert.fail("Expected the colliding WAB to start (its web application just not being deployed), but start() failed: " + ex);
            }
            Assert.assertEquals("Expected the first WAB to keep the path", "bundle=" + WAB1, get("/wab-collision/info")[1]);
            first.stop();
            String[] res = null;
            for (int i = 0; i < 50; i++) {
                res = get("/wab-collision/info");
                if ("200".equals(res[0]))
                    break;
                Thread.sleep(100);
            }
            Assert.assertEquals("Expected the colliding WAB to be deployed at /wab-collision after the first one was stopped, but GET gave " + res[0],
                    "bundle=" + WAB2, res[1]);
        } finally {
            second.uninstall();
            first.uninstall();
        }
    }
}
