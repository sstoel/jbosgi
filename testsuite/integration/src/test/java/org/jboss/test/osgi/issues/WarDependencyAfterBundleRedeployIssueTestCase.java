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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.OperationBuilder;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper;
import org.jboss.dmr.ModelNode;
import org.jboss.modules.Module;
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
 * A WAR with Dependencies: deployment.&lt;bundle&gt; fails when the bundle was deployed again while its old revision was
 * in use and that revision is removed; a new WAR with that dependency cannot be deployed while the bundle is ACTIVE.
 *
 * <h2>Severity</h2>
 * major. The WAR answers 404 (status FAILED) for good, and every later deployment of a WAR that depends on the bundle
 * fails with WFLYCTL0412 (jboss.module.spec.service."deployment.&lt;bundle&gt;" not installed) until a restart.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem: {@code ModuleLoaderIntegration.getModuleIdentifier} and {@code removeModule}.
 *
 * <h2>Found by</h2>
 * R8 fix review, regression 7 (umb-a-S01).
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/ModuleLoaderIntegration.java:223-236, 447-462 (13dd3d85).
 *
 * <h2>Root cause</h2>
 * The bundle that is deployed again while the revision of the uninstalled bundle is in use gets the module
 * deployment.&lt;bundle&gt;-&lt;id&gt;, the old revision keeps deployment.&lt;bundle&gt;. When the old revision is
 * removed nothing provides deployment.&lt;bundle&gt; any more.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=WarDependencyAfterBundleRedeployIssueTestCase
 * </pre>
 * lib.jar exports a package, imp.jar imports it, web1.war depends on deployment.lib.jar; lib.jar is replaced, a second
 * WAR is deployed, imp.jar is undeployed, which removes the old revision of lib.jar, then a third WAR is deployed.
 *
 * <h2>Expected</h2>
 * When the old revision is removed the WARs get the module of the current revision of lib.jar and stay deployed, the
 * third WAR deploys with it. While the old revision is in use, the module deployment.lib.jar is still the one of the
 * old revision: a WAR that is deployed then gets the old revision until it is removed.
 *
 * <h2>Regression vs WF41</h2>
 * yes, introduced by 13dd3d85.
 */
@RunWith(Arquillian.class)
public class WarDependencyAfterBundleRedeployIssueTestCase {

    static final String LIB = "wardep-lib.jar";
    static final String IMP = "wardep-imp.jar";
    static final String PACKAGE = WarDependencyAfterBundleRedeployIssueTestCase.class.getPackage().getName();

    @ArquillianResource
    BundleContext context;

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    ManagementClient managementClient;

    public static class LibraryService {
    }

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "wardep-redeploy-tests");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(ManagementClient.class, ModelControllerClient.class, ClientConstants.class, ServerDeploymentHelper.class, ModelNode.class,
                        OperationBuilder.class, Module.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    static JavaArchive lib(final String version) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "wardep-lib-" + version + ".jar");
        archive.addClasses(LibraryService.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName("wardep-lib");
                builder.addBundleVersion(version);
                builder.addBundleManifestVersion(2);
                builder.addExportPackages(PACKAGE + ";wardep=true");
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = "lib1", managed = false, testable = false)
    public static JavaArchive lib1() {
        return lib("1.0.0");
    }

    @Deployment(name = "lib2", managed = false, testable = false)
    public static JavaArchive lib2() {
        return lib("2.0.0");
    }

    @Deployment(name = "imp", managed = false, testable = false)
    public static JavaArchive imp() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, IMP);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName("wardep-imp");
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(PACKAGE + ";wardep=true");
                return builder.openStream();
            }
        });
        return archive;
    }

    static WebArchive web(String name) {
        WebArchive war = ShrinkWrap.create(WebArchive.class, name);
        war.addAsWebResource(new StringAsset(name), "index.html");
        war.addAsManifestResource(new StringAsset("Manifest-Version: 1.0\nDependencies: deployment." + LIB + "\n"), "MANIFEST.MF");
        return war;
    }

    @Deployment(name = "web1", managed = false, testable = false)
    public static WebArchive web1() {
        return web("wardep-web1.war");
    }

    @Deployment(name = "web2", managed = false, testable = false)
    public static WebArchive web2() {
        return web("wardep-web2.war");
    }

    @Deployment(name = "web3", managed = false, testable = false)
    public static WebArchive web3() {
        return web("wardep-web3.war");
    }

    private Bundle lib() {
        Bundle result = null;
        for (Bundle bundle : context.getBundles())
            if ("wardep-lib".equals(bundle.getSymbolicName()) && bundle.getState() != Bundle.UNINSTALLED)
                result = bundle;
        return result;
    }

    // The version of the bundle whose class the WAR gets
    private String warView(String war) throws Exception {
        Class<?> clazz = Module.getCallerModuleLoader().loadModule("deployment." + war).getClassLoader().loadClass(LibraryService.class.getName());
        Class<?> current = lib().loadClass(LibraryService.class.getName());
        return clazz == current ? "current " + lib().getVersion() : "other revision";
    }

    private ModelNode fullReplace(String name, InputStream in) throws Exception {
        ModelNode op = new ModelNode();
        op.get(ClientConstants.OP).set(ClientConstants.DEPLOYMENT_FULL_REPLACE_OPERATION);
        op.get(ClientConstants.OP_ADDR).setEmptyList();
        op.get(ClientConstants.NAME).set(name);
        op.get(ClientConstants.CONTENT).add().get(ClientConstants.INPUT_STREAM_INDEX).set(0);
        OperationBuilder builder = OperationBuilder.create(op);
        builder.addInputStream(in);
        return managementClient.getControllerClient().execute(builder.build());
    }

    private static void undeployQuietly(ServerDeploymentHelper server, String name) {
        try {
            server.undeploy(name);
        } catch (Exception ex) {
            // not deployed
        }
    }

    @Test
    public void testWarDependencyAfterRedeployOfBundleInUse() throws Exception {
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        try {
            server.deploy(LIB, deployer.getDeployment("lib1"));
            server.deploy(IMP, deployer.getDeployment("imp"));
            server.deploy("wardep-web1.war", deployer.getDeployment("web1"));
            Assert.assertEquals("web1 gets the bundle", "current 1.0.0", warView("wardep-web1.war"));

            ModelNode result = fullReplace(LIB, deployer.getDeployment("lib2"));
            Assert.assertEquals("full-replace " + result, "success", result.get("outcome").asString());
            server.deploy("wardep-web2.war", deployer.getDeployment("web2"));

            // The old revision of lib.jar is removed, the WARs get the module of the current revision
            server.undeploy(IMP);
            String web1 = awaitOk("/wardep-web1/index.html");
            String web2 = awaitOk("/wardep-web2/index.html");
            Assert.assertTrue("web1 stays deployed when the old revision of " + LIB + " is removed: " + web1, web1.startsWith("200"));
            Assert.assertTrue("web2 stays deployed when the old revision of " + LIB + " is removed: " + web2, web2.startsWith("200"));
            Assert.assertEquals("web1 gets the current revision", "current 2.0.0", warView("wardep-web1.war"));
            Assert.assertEquals("web2 gets the current revision", "current 2.0.0", warView("wardep-web2.war"));

            server.deploy("wardep-web3.war", deployer.getDeployment("web3"));
            Assert.assertEquals("web3 gets the current revision", "current 2.0.0", warView("wardep-web3.war"));
        } finally {
            undeployQuietly(server, "wardep-web3.war");
            undeployQuietly(server, "wardep-web2.war");
            undeployQuietly(server, "wardep-web1.war");
            undeployQuietly(server, IMP);
            undeployQuietly(server, LIB);
        }
    }

    private static String awaitOk(String path) throws Exception {
        String result = get(path);
        long deadline = System.currentTimeMillis() + 30000;
        while (result.startsWith("200") == false && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            result = get(path);
        }
        return result;
    }

    private static String get(String path) throws IOException {
        int port = Integer.getInteger("jboss.http.port", 8080) + Integer.getInteger("jboss.socket.binding.port-offset", 0);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 5000);
            socket.setSoTimeout(15000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(buffer);
            String text = buffer.toString(StandardCharsets.UTF_8);
            int eol = text.indexOf("\r\n");
            String[] status = (eol > 0 ? text.substring(0, eol) : text).split(" ");
            return status.length > 1 ? status[1] : text;
        }
    }
}
