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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper;
import org.jboss.dmr.ModelNode;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Test;

/**
 * A capability of the OSGi subsystem that cannot be installed, e.g. Maven coordinates whose artifact cannot be
 * downloaded, fails the whole framework at boot: no bundle runs. A capability that cannot be resolved is only logged,
 * management does not show it.
 *
 * <h3>Severity</h3>
 * major. An offline boot with a Maven capability, or a missing artifact, takes down every OSGi deployment.
 *
 * <h3>Component</h3>
 * jbosgi/wildfly/subsystem: {@code BootstrapBundlesIntegration}.
 *
 * <h3>Found by</h3>
 * R8 fix review, residuals of subrepos/65 and services/21 (umb-a-M04).
 *
 * <h3>Spec reference</h3>
 * none; robustness, as for the lenient resolve of the bootstrap bundles (security/02).
 *
 * <h3>Code location</h3>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/BootstrapBundlesIntegration.java:141, :152 (f49fa4d9).
 *
 * <h3>Root cause</h3>
 * The install failure was thrown as a StartException of jbosgi.BootstrapBundles.INSTALL.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi/testsuite/integration
 * mvn test -Dtest=UnavailableBootstrapCapabilityIssueTestCase
 * </pre>
 * Self-hosted: copies the testsuite server, adds a Maven capability whose repository does not answer and boots it.
 *
 * <h3>Expected</h3>
 * The server reports that it started with errors and logs JBAS011927; the framework is ACTIVE and a bundle deploys.
 *
 * <h3>Regression vs WF41</h3>
 * no.
 */
public class UnavailableBootstrapCapabilityIssueTestCase {

    static final int OFFSET = Integer.getInteger("issue.port.offset", 2800);
    static final int MGMT_PORT = 9990 + OFFSET;
    static final String CAPABILITY = "org.acme.osgi:does-not-exist:1.0.0";

    private File home;
    private Process process;

    @After
    public void tearDown() throws Exception {
        stopServer();
    }

    @Test
    public void testFrameworkStartsWithoutUnavailableCapability() throws Exception {
        prepareServer("issues-unavailable-capability");
        Path config = home.toPath().resolve("standalone/configuration/standalone-osgi.xml");
        String xml = new String(Files.readAllBytes(config), StandardCharsets.UTF_8);
        // The properties of the OSGi subsystem, not those of the other subsystems
        int subsystem = xml.indexOf("urn:jboss:domain:osgi");
        int properties = xml.indexOf("<properties>", subsystem) + "<properties>".length();
        xml = xml.substring(0, properties) + "\n                <property name=\"org.jboss.osgi.repository.maven.base.urls\">http://127.0.0.1:1/maven2</property>"
                + xml.substring(properties);
        xml = xml.replace("</capabilities>", "    <capability name=\"" + CAPABILITY + "\" startlevel=\"1\"/>\n            </capabilities>");
        Files.write(config, xml.getBytes(StandardCharsets.UTF_8));

        File out = startServer("console-1.out");
        String log = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
        assertTrue("The server reports that it started with errors", log.contains("WFLYSRV0026"));
        assertTrue("The unavailable capability is logged (JBAS011927)", log.contains("JBAS011927") && log.contains(CAPABILITY));
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            assertEquals("The framework is active", "ACTIVE", bundleState(client, "0"));
            new ServerDeploymentHelper(client).deploy("unavailable-capability-app.jar", bundle("unavailable-capability-app"));
            assertEquals("A bundle deploys and starts", "ACTIVE", bundleState(client, "unavailable-capability-app.jar"));
        }
    }

    private static String bundleState(ModelControllerClient client, String bundle) throws IOException {
        ModelNode op = new ModelNode();
        op.get("operation").set("read-attribute");
        op.get("address").add("subsystem", "osgi").add("bundle", bundle);
        op.get("name").set("state");
        ModelNode response = client.execute(op);
        return "success".equals(response.get("outcome").asString()) ? response.get("result").asString() : String.valueOf(response.get("failure-description"));
    }

    private static InputStream bundle(final String symbolicName) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, symbolicName + ".jar");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(symbolicName);
                return builder.openStream();
            }
        });
        return archive.as(ZipExporter.class).exportAsInputStream();
    }

    private File prepareServer(String name) throws IOException {
        File source = new File(System.getProperty("jboss.home", "target/wildfly-41.0.1.Final"));
        assertTrue("Precondition: the testsuite server " + source, new File(source, "bin/standalone.sh").isFile());
        File target = new File("target/" + name);
        deleteRecursive(target.toPath());
        Path src = source.toPath();
        Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String rel = src.relativize(dir).toString();
                // The running testsuite server writes its data, logs and configuration history meanwhile
                if (rel.equals("standalone/data") || rel.equals("standalone/log") || rel.equals("standalone/tmp")
                        || rel.equals("standalone/configuration/standalone_xml_history"))
                    return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(target.toPath().resolve(rel));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String rel = src.relativize(file).toString();
                if (rel.startsWith("standalone/deployments/") && rel.endsWith("README.txt") == false)
                    return FileVisitResult.CONTINUE;
                try {
                    Files.copy(file, target.toPath().resolve(rel), StandardCopyOption.COPY_ATTRIBUTES);
                } catch (NoSuchFileException ex) {
                    // removed by the running server meanwhile
                }
                return FileVisitResult.CONTINUE;
            }
        });
        // Without the deployments that the Arquillian server persisted: their content is in standalone/data, which is not
        // copied, and the server would not boot (WFLYSRV0137)
        Path config = target.toPath().resolve("standalone/configuration/standalone-osgi.xml");
        String xml = new String(Files.readAllBytes(config), StandardCharsets.UTF_8);
        Files.write(config, xml.replaceAll("(?s)\\s*<deployments>.*?</deployments>", "").getBytes(StandardCharsets.UTF_8));
        new File(target, "bin/standalone.sh").setExecutable(true);
        return home = target.getCanonicalFile();
    }

    private File startServer(String log) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(new File(home, "bin/standalone.sh").getPath(), "-c", "standalone-osgi.xml",
                "-Djboss.socket.binding.port-offset=" + OFFSET);
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        builder.environment().remove("JAVA_OPTS");
        builder.redirectErrorStream(true);
        File out = new File(home, log);
        builder.redirectOutput(out);
        process = builder.start();
        long deadline = System.currentTimeMillis() + 180000;
        while (System.currentTimeMillis() < deadline) {
            String text = out.exists() ? new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8) : "";
            if (text.contains("WFLYSRV0025") || text.contains("WFLYSRV0026"))
                return out;
            if (process.isAlive() == false)
                fail("Precondition: server process ended: " + text);
            Thread.sleep(500);
        }
        fail("Precondition: server did not start in time, see " + out);
        return out;
    }

    private void stopServer() throws Exception {
        if (process == null)
            return;
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            ModelNode op = new ModelNode();
            op.get("operation").set("shutdown");
            client.execute(op);
        } catch (Exception ex) {
            // already down
        }
        // only the processes this test started: the script and its children
        List<ProcessHandle> handles = new ArrayList<ProcessHandle>();
        process.toHandle().descendants().forEach(handles::add);
        handles.add(process.toHandle());
        long deadline = System.currentTimeMillis() + 60000;
        for (ProcessHandle handle : handles) {
            while (handle.isAlive() && System.currentTimeMillis() < deadline)
                Thread.sleep(200);
            if (handle.isAlive())
                handle.destroyForcibly();
        }
        process = null;
    }

    private static void deleteRecursive(Path path) throws IOException {
        if (Files.exists(path) == false)
            return;
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
