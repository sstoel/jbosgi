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
import org.jboss.osgi.provision.ProvisionerSupport;
import org.jboss.osgi.provision.XResourceProvisioner;
import org.jboss.osgi.repository.XRepository;
import org.jboss.osgi.resolver.XResource;
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
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.namespace.IdentityNamespace;
import org.osgi.resource.Resource;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.osgi.service.repository.Repository;

/**
 * The Web Extender posts no Web Application events: with an Event Admin installed, deploying and undeploying a WAB
 * produces none of org/osgi/service/web/DEPLOYING, DEPLOYED, UNDEPLOYING, UNDEPLOYED (nor FAILED).
 *
 * <h2>Severity</h2>
 * major. Management agents and dependent bundles that track WAB availability through these events (the spec's only
 * notification mechanism besides the ServletContext service) never learn about deployments or failures.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/integration/web ({@code WebContextLifecycleInterceptor}, the WAB lifecycle code).
 *
 * <h2>Found by</h2>
 * wildfly workstream, Enterprise R7 128.5 (Events) spec alignment; {@code grep -r "org/osgi/service/web"} finds no
 * event topic in the umbrella or the WildFly fork.
 *
 * <h2>Spec reference</h2>
 * OSGi Enterprise R7 128.5 Events: "The Web Extender must track all WABs in the OSGi framework in which the Web
 * Extender is installed. The Web Extender must post Event Admin events, which is asynchronous, at crucial points in
 * its processing. The topic of the event must be one of the following values: org/osgi/service/web/DEPLOYING ...
 * DEPLOYED ... UNDEPLOYING ... UNDEPLOYED ... FAILED" and 128.3.2/128.3.8 (post DEPLOYING/DEPLOYED, UNDEPLOYING/UNDEPLOYED).
 * https://docs.osgi.org/specification/osgi.enterprise/7.0.0/service.war.html
 *
 * <h2>Code location</h2>
 * wildfly/integration/web/src/main/java/org/jboss/as/osgi/web/WebContextLifecycleInterceptor.java:86-104 (deploys and
 * undeploys the web context on ACTIVE/RESOLVED, posts nothing); no other class posts web events.
 *
 * <h2>Root cause</h2>
 * Not implemented.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * # JDK 24+: in testsuite/pom.xml set &lt;surefire.security.args&gt;-Dno.sm=true&lt;/surefire.security.args&gt; (wildfly/03, TestJvmSecurityManagerIssueTestCase); a -D override on the command line does not reach the @{...} argLine
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/example test -Dtest=WabEventsIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository. The two -Darq... options start the managed server with port offset 500, so the test cannot connect to another server on 9990 (arquillian.xml has allowConnectingToRunningServer=true); they can be left out when no other server runs.)
 * <h2>Observed</h2>
 * <pre>
 * java.lang.AssertionError: Expected the Web Extender to post DEPLOYING, DEPLOYED, UNDEPLOYING, UNDEPLOYED for
 *   wab-events.jar, but the EventHandler for org/osgi/service/web/* received []
 * </pre>
 *
 * <h2>Expected</h2>
 * [DEPLOYING, DEPLOYED, UNDEPLOYING, UNDEPLOYED] for the WAB, with the properties of 128.5.
 *
 * <h2>Reference behaviour</h2>
 * Not checked (Pax Web posts these events).
 *
 * <h2>Suggested fix (not applied)</h2>
 * Track the Event Admin service in the web integration and post the events from WebContextLifecycleInterceptor around
 * activator.startContext()/stopContext() (FAILED when startContext fails, with collision properties).
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
 * unmodified configuration. This run also set org.jboss.osgi.repository.maven.base.urls to https URLs: the WF41
 * default http URLs no longer serve, and the test provisions Felix EventAdmin from Maven.
 */
@RunWith(Arquillian.class)
public class WabEventsIssueTestCase {

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

    static final String WAB = "wab-events.jar";

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "wab-events-tests");
        archive.addAsResource("repository/felix.eventadmin.feature.xml");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(XRepository.class, Repository.class, XResource.class, Resource.class, XResourceProvisioner.class);
                builder.addImportPackages(ServletContext.class);
                builder.addDynamicImportPackages(EventHandler.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = WAB, managed = false, testable = false)
    public static JavaArchive getWab() {
        return wab(WAB, "/wab-events");
    }

    @Test
    public void testWebApplicationEvents() throws Exception {
        ProvisionerSupport provisioner = new ProvisionerSupport(context);
        provisioner.populateRepository(getClass().getClassLoader(), "felix.eventadmin.feature");
        provisioner.installCapabilities(IdentityNamespace.IDENTITY_NAMESPACE, "felix.eventadmin.feature");

        final List<String> topics = Collections.synchronizedList(new ArrayList<String>());
        Dictionary<String, Object> props = new Hashtable<String, Object>();
        props.put(EventConstants.EVENT_TOPIC, new String[] { "org/osgi/service/web/*", "org/jboss/test/osgi/issues/wab-events" });
        ServiceRegistration<?> reg = context.registerService(EventHandler.class.getName(), new EventHandler() {
            @Override
            public void handleEvent(Event event) {
                if (WAB.equals(event.getProperty("bundle.symbolicName")))
                    topics.add(event.getTopic().substring("org/osgi/service/web/".length()));
                else if (event.getTopic().startsWith("org/jboss/test"))
                    topics.add("PRECONDITION");
            }
        }, props);
        try {
            // Precondition: the Event Admin delivers events to the handler
            ServiceReference<EventAdmin> eaRef = context.getServiceReference(EventAdmin.class);
            Assert.assertNotNull("Precondition: EventAdmin available", eaRef);
            context.getService(eaRef).sendEvent(new Event("org/jboss/test/osgi/issues/wab-events", Collections.<String, Object> emptyMap()));
            Assert.assertEquals("Precondition: the EventHandler receives events", Collections.singletonList("PRECONDITION"), topics);
            topics.clear();

            Bundle bundle = context.installBundle(WAB, deployer.getDeployment(WAB));
            try {
                bundle.start();
                Assert.assertEquals("Precondition: the WAB is deployed", "200", get("/wab-events/info")[0]);
                bundle.stop();
            } finally {
                bundle.uninstall();
            }
            for (int i = 0; i < 50 && topics.size() < 4; i++)
                Thread.sleep(100);
            Assert.assertEquals("Expected the Web Extender to post DEPLOYING, DEPLOYED, UNDEPLOYING, UNDEPLOYED for " + WAB
                    + ", but the EventHandler for org/osgi/service/web/* received " + topics, java.util.Arrays.asList("DEPLOYING", "DEPLOYED", "UNDEPLOYING", "UNDEPLOYED"), topics);
        } finally {
            reg.unregister();
        }
    }
}
