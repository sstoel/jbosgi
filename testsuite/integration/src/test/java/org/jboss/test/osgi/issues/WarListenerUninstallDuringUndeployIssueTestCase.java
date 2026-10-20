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

import java.io.InputStream;

import jakarta.annotation.Resource;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper;
import org.jboss.dmr.ModelNode;
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

/**
 * Bundle.uninstall of a deployed bundle from the ServletContextListener.contextDestroyed of a web application that is
 * undeployed with a management operation blocks the undeploy until the blocking timeout, and the server ends
 * restart-required.
 *
 * <h2>Severity</h2>
 * major. The undeploy hangs for the blocking timeout (300 s by default), the uninstall fails, and the server must be
 * restarted (WFLYCTL0348/0349). An EJB {@code @PreDestroy} that uninstalls a bundle does the same.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem: {@code BundleLifecycleIntegration.uninstall}.
 *
 * <h2>Found by</h2>
 * R8 fix review, confirmed by test (umb-shutdown-01); residual of shutdown/05.
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/BundleLifecycleIntegration.java:779-810 (d3eb93ba).
 *
 * <h2>Root cause</h2>
 * The uninstall undeploys the deployment of the bundle with a management operation of its own, which waits for the
 * operation that undeploys the web application; that operation waits for the web application to stop, which waits for
 * contextDestroyed. The undeploy is deferred for activators, management operations and the server stop only.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=WarListenerUninstallDuringUndeployIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * The undeploy of the web application completes at once, the bundle is uninstalled, its deployment is removed after
 * the undeploy, and the server stays running.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing, the shutdown round fixed the activators and the server stop).
 */
@RunWith(Arquillian.class)
public class WarListenerUninstallDuringUndeployIssueTestCase {

    static final String USER = "war-uninstall-user.jar";
    static final String WAR = "war-uninstall-web.war";
    static final String RESULT = "issues.war.uninstall.result";

    @ArquillianResource
    BundleContext context;

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    ManagementClient managementClient;

    public static class UninstallingListener implements ServletContextListener {
        @Resource
        BundleContext bundleContext;

        @Override
        public void contextInitialized(ServletContextEvent event) {
        }

        @Override
        public void contextDestroyed(ServletContextEvent event) {
            String result;
            try {
                Bundle target = null;
                for (Bundle bundle : bundleContext.getBundles())
                    if ("war-uninstall-user".equals(bundle.getSymbolicName()))
                        target = bundle;
                if (target != null) {
                    target.uninstall();
                    result = "uninstalled";
                } else {
                    result = "no bundle";
                }
            } catch (Throwable th) {
                result = th.toString();
            }
            System.setProperty(RESULT, result);
        }
    }

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "war-uninstall-tests");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(ManagementClient.class, ModelControllerClient.class, ClientConstants.class, ServerDeploymentHelper.class, ModelNode.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = "user", managed = false, testable = false)
    public static JavaArchive user() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, USER);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName("war-uninstall-user");
                builder.addBundleManifestVersion(2);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = "web", managed = false, testable = false)
    public static WebArchive web() {
        WebArchive war = ShrinkWrap.create(WebArchive.class, WAR);
        war.addClass(UninstallingListener.class);
        war.addAsWebResource(new StringAsset("issues"), "index.html");
        war.addAsWebInfResource(new StringAsset("<?xml version=\"1.0\"?>\n<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.0\">\n"
                + "  <listener><listener-class>" + UninstallingListener.class.getName() + "</listener-class></listener>\n</web-app>\n"), "web.xml");
        return war;
    }

    private ModelNode read(ModelNode address, String name) throws Exception {
        ModelNode op = new ModelNode();
        op.get(ClientConstants.OP).set(ClientConstants.READ_ATTRIBUTE_OPERATION);
        op.get(ClientConstants.OP_ADDR).set(address);
        op.get(ClientConstants.NAME).set(name);
        return managementClient.getControllerClient().execute(op);
    }

    private boolean userBundleInstalled() {
        for (Bundle bundle : context.getBundles())
            if ("war-uninstall-user".equals(bundle.getSymbolicName()))
                return true;
        return false;
    }

    @Test
    public void testUninstallFromContextDestroyedDuringUndeploy() throws Exception {
        System.clearProperty(RESULT);
        final ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        server.deploy(USER, deployer.getDeployment("user"));
        server.deploy(WAR, deployer.getDeployment("web"));
        try {
            // The in-container call times out after 60 s, the undeploy runs in a thread
            final String[] undeploy = { "running" };
            Thread undeployer = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        server.undeploy(WAR);
                        undeploy[0] = "ok";
                    } catch (Exception ex) {
                        undeploy[0] = ex.toString();
                    }
                }
            }, "war-uninstall-undeploy");
            undeployer.setDaemon(true);
            long begin = System.currentTimeMillis();
            undeployer.start();
            undeployer.join(20000);
            long elapsed = System.currentTimeMillis() - begin;
            String info = "undeploy " + undeploy[0] + " after " + elapsed + " ms, contextDestroyed: " + System.getProperty(RESULT);
            Assert.assertFalse("The undeploy of the web application completes: " + info, undeployer.isAlive());
            Assert.assertEquals("The undeploy succeeds: " + info, "ok", undeploy[0]);
            Assert.assertEquals("The bundle is uninstalled in contextDestroyed: " + info, "uninstalled", System.getProperty(RESULT));
            Assert.assertFalse("The bundle is uninstalled", userBundleInstalled());

            // The deployment of the bundle is removed after the undeploy
            ModelNode address = new ModelNode().add(ClientConstants.DEPLOYMENT, USER);
            long deadline = System.currentTimeMillis() + 30000;
            while (ClientConstants.SUCCESS.equals(read(address, "name").get(ClientConstants.OUTCOME).asString()) && System.currentTimeMillis() < deadline)
                Thread.sleep(200);
            Assert.assertFalse("The deployment of the uninstalled bundle is removed", ClientConstants.SUCCESS.equals(read(address, "name").get(ClientConstants.OUTCOME).asString()));
            Assert.assertEquals("The server stays running", "running", read(new ModelNode().setEmptyList(), "server-state").get(ClientConstants.RESULT).asString());
        } finally {
            try {
                server.undeploy(WAR);
            } catch (Exception ex) {
                // undeployed
            }
            try {
                server.undeploy(USER);
            } catch (Exception ex) {
                // removed
            }
        }
    }
}
