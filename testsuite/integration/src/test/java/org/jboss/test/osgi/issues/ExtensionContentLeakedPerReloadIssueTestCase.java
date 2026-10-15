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

import java.io.File;
import java.io.InputStream;

import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.as.arquillian.api.ContainerResource;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper;
import org.jboss.dmr.ModelNode;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Every framework initialization copies the content of each framework extension bundle into a new temporary jar in
 * the server's tmp directory and keeps it open; the copies are neither closed nor deleted until the JVM exits, so each
 * {@code :reload} adds one file and one open file descriptor per extension.
 *
 * <h3>Severity</h3>
 * minor. Unbounded growth of the server tmp directory and of open file descriptors with every reload while a framework
 * extension is deployed (also memory: the open JarFile and the deleteOnExit list).
 *
 * <h3>Component</h3>
 * jbosgi umbrella, wildfly/subsystem, {@code org.jboss.as.osgi.service.FrameworkModuleIntegration}
 * ({@code FrameworkModuleProviderImpl.addExtensionContent}/{@code copyContent}).
 *
 * <h3>Found by</h3>
 * connect workstream, round 3 (":reload hygiene" harness on the patched WildFly 41 server, JDK 25); suspected from code
 * review in round 2.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.2.6 Stopping a Framework
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html#framework.lifecycle.stopping): the
 * framework "releases any resources (like threads, class loaders, etc.) it held" (open files and temporary copies of
 * extension content included).
 *
 * <h3>Code location</h3>
 * {@code wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/FrameworkModuleIntegration.java:134-158}:
 * {@code copyContent} writes {@code File.createTempFile("osgi-extension", ".jar", tempDir)} (line 139), only registers
 * {@code deleteOnExit()} (line 140) and opens it with {@code ResourceLoaders.createJarResourceLoader(new JarFile(file))}
 * (line 158); nothing closes the loader or deletes the file when the framework module goes away. Introduced by 9df5185d
 * (framework extension support in the server).
 *
 * <h3>Root cause</h3>
 * The extension copy is tied to the JVM lifetime instead of the framework instance.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=ExtensionContentLeakedPerReloadIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository. The two -Darq... options start the managed server with port offset 500, so the test cannot connect to another server on 9990 (arquillian.xml has allowConnectingToRunningServer=true); they can be left out when no other server runs.)
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: The server tmp directory must keep at most one copy of the extension content, but after 4
 *   reloads there are 5 osgi-extension*.jar files
 * (harness, 12 reloads with one extension: osgi-extension*.jar 1 -> 13, lsof entries of the server process 711 -> 723,
 *   exactly +1 per reload)
 * </pre>
 *
 * <h3>Expected</h3>
 * At most one copy per extension; the previous copy is closed and deleted when the framework module is discarded.
 *
 * <h3>Reference behaviour</h3>
 * n/a (WildFly integration).
 *
 * <h3>Suggested fix</h3>
 * Keep the created ResourceLoaders/files in the FrameworkModuleProviderImpl, close the loaders and delete the files in
 * the provider service's stop; or reuse a stable file name per extension revision.
 *
 * <h3>Note on the test</h3>
 * The test deploys a framework extension, reloads the server 4 times (about 20 s) and undeploys the extension.
 *
 * <h3>Regression vs WF41</h3>
 * new R8 behaviour: on the WF41 server (origin/WF41 77f95be6, same patched WildFly 41.0.1, JDK 25) an extension bundle
 * cannot be deployed at all (PARSE: IllegalArgumentException: Module specification is null), so the test errors at its
 * first deploy; extension support in the server is new on the R8 branch; the defect is in behaviour WF41 never had, so
 * it is not a regression of working functionality.
 */
@RunAsClient
@RunWith(Arquillian.class)
public class ExtensionContentLeakedPerReloadIssueTestCase {

    static final int RELOADS = 4;

    @ContainerResource
    ManagementClient managementClient;

    @Test
    public void testExtensionContentNotLeakedPerReload() throws Exception {
        File tmp = new File(System.getProperty("jboss.home"), "standalone/tmp");
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        String deployment = server.deploy("reload-extension.jar", createExtension());
        // Deploying another bundle resolves the installed extension, which attaches its content to the framework
        String other = server.deploy("reload-extension-user.jar", createBundle());
        try {
            for (int i = 0; i < RELOADS; i++)
                reload();
            int copies = countRecursive(tmp, "osgi-extension");
            Assert.assertTrue("The server tmp directory must keep at most one copy of the extension content, but after " + RELOADS + " reloads there are "
                    + copies + " osgi-extension*.jar files", copies <= 1);
        } finally {
            server.undeploy(other);
            server.undeploy(deployment);
        }
    }

    private InputStream createExtension() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "reload-extension.jar");
        archive.addAsResource(new StringAsset("extension"), "reload-extension/marker.txt");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName("reload-extension");
                builder.addBundleManifestVersion(2);
                builder.addFragmentHost("system.bundle; extension:=framework");
                return builder.openStream();
            }
        });
        return archive.as(ZipExporter.class).exportAsInputStream();
    }

    private InputStream createBundle() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "reload-extension-user.jar");
        archive.addAsResource(new StringAsset("user"), "reload-extension-user/marker.txt");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName("reload-extension-user");
                builder.addBundleManifestVersion(2);
                return builder.openStream();
            }
        });
        return archive.as(ZipExporter.class).exportAsInputStream();
    }

    private static int countRecursive(File dir, String prefix) {
        File[] files = dir.listFiles();
        int count = 0;
        if (files != null) {
            for (File file : files) {
                if (file.getName().startsWith(prefix))
                    count++;
                if (file.isDirectory())
                    count += countRecursive(file, prefix);
            }
        }
        return count;
    }

    private void reload() throws Exception {
        long startTime = startTime();
        long begin = System.currentTimeMillis();
        while (System.currentTimeMillis() - begin < 90000) {
            ModelNode op = new ModelNode();
            op.get("operation").set("reload");
            try {
                managementClient.getControllerClient().execute(op);
            } catch (Exception ex) {
                // the connection may be closed by the reload
            }
            // Wait until a new server instance (another start time) is running
            long wait = System.currentTimeMillis();
            while (System.currentTimeMillis() - wait < 30000) {
                Thread.sleep(500);
                try {
                    ModelNode read = new ModelNode();
                    read.get("operation").set("read-attribute");
                    read.get("name").set("server-state");
                    boolean running = "running".equals(managementClient.getControllerClient().execute(read).get("result").asString());
                    if (running && startTime() != startTime) {
                        Thread.sleep(1000);
                        return;
                    }
                } catch (Exception ex) {
                    // not yet available
                }
            }
        }
        throw new IllegalStateException("The server did not come back from :reload");
    }

    /** The number of server starts in the server log (WFLYSRV0025), which grows with every completed reload */
    private long startTime() throws Exception {
        java.io.File log = new java.io.File(System.getProperty("jboss.home"), "standalone/log/server.log");
        long count = 0;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(log))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.contains("WFLYSRV0025:") || line.contains("WFLYSRV0026:"))
                    count++;
            }
        }
        return count;
    }
}
