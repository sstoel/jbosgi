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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;
import org.junit.After;
import org.junit.Test;

/**
 * A server whose OSGi subsystem lists the capability org.apache.felix.log, as the standalone-osgi.xml of the WF41 build
 * does, does not start the OSGi framework: the module of the capability was removed.
 *
 * <h2>Severity</h2>
 * critical. After an upgrade that keeps the server configuration, BootstrapBundles.INSTALL fails, the OSGi framework
 * never starts, every OSGi deployment is dead, and the server reports "started (with errors)".
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem: {@code BootstrapBundlesIntegration}, the capability bootstrap.
 *
 * <h2>Found by</h2>
 * tests/07 (Wf41ConfigCapabilityRemovedIssueTestCase of wildfly/build checks the WF41 capability list); R8 fix review,
 * section 3: a README migration step only.
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/parser/OSGiCapabilityAdd.java:83-106 fails the boot operation of
 * the capability (JBAS011977), BootstrapBundlesIntegration.java:128-152 throws startFailedToProcessInitialCapability
 * (JBAS011955) for the first capability that cannot be resolved.
 *
 * <h2>Root cause</h2>
 * Commit 80805736 removed the Felix Log Service bundle, the framework provides the Log Service since; the subsystem had
 * no tolerance for a capability that a release removed.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=Wf41CapabilityBootIssueTestCase
 * </pre>
 * Not Arquillian: copies the testsuite server to target/issues-wf41-capability, adds the capability to its
 * configuration and starts it at port offset issue.port.offset (2600).
 *
 * <h2>Expected</h2>
 * The server starts without errors, logs a warning that the capability is not available any more (JBAS011923), and
 * the OSGi framework starts the other capabilities.
 *
 * <h2>Regression vs WF41</h2>
 * yes (upgrade path; a WF41 server boots with this configuration).
 */
public class Wf41CapabilityBootIssueTestCase {

    static final int OFFSET = Integer.getInteger("issue.port.offset", 2600);
    static final int MGMT_PORT = 9990 + OFFSET;

    private File home;
    private Process process;
    private File console;

    @After
    public void tearDown() throws Exception {
        stopServer();
    }

    @Test
    public void testServerStartsWithRetiredCapability() throws Exception {
        prepareServer("issues-wf41-capability");
        Path config = home.toPath().resolve("standalone/configuration/standalone-osgi.xml");
        String xml = new String(Files.readAllBytes(config), StandardCharsets.UTF_8);
        assertTrue("Precondition: the OSGi subsystem has capabilities", xml.contains("</capabilities>"));
        xml = xml.replace("</capabilities>", "    <capability name=\"org.apache.felix.log\" startlevel=\"1\"/>\n            </capabilities>");
        Files.write(config, xml.getBytes(StandardCharsets.UTF_8));

        startServer("console.out");
        // The framework starts the capabilities when it is created
        String configadmin = bundleState("org.apache.felix.configadmin");
        long deadline = System.currentTimeMillis() + 30000;
        while ("ACTIVE".equals(configadmin) == false && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            configadmin = bundleState("org.apache.felix.configadmin");
        }
        String text = new String(Files.readAllBytes(console.toPath()), StandardCharsets.UTF_8);
        assertTrue("The server starts without errors", text.contains("WFLYSRV0025") && text.contains("WFLYSRV0026") == false);
        assertTrue("The configuration of the capability is accepted", text.contains("JBAS011977") == false);
        assertTrue("The OSGi bootstrap does not fail on the capability", text.contains("JBAS011955") == false);
        assertTrue("A warning names the capability that is not available any more", text.contains("JBAS011923") && text.contains("org.apache.felix.log"));
        assertTrue("The framework starts the other capabilities, org.apache.felix.configadmin is " + configadmin, "ACTIVE".equals(configadmin));
    }

    // The state of the bundle with the symbolic name
    private String bundleState(String symbolicName) throws IOException {
        ModelNode op = new ModelNode();
        op.get("operation").set("read-children-resources");
        op.get("address").add("subsystem", "osgi");
        op.get("child-type").set("bundle");
        op.get("include-runtime").set(true);
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            ModelNode result = client.execute(op).get("result");
            if (result.isDefined()) {
                for (Property property : result.asPropertyList()) {
                    if (symbolicName.equals(property.getValue().get("symbolic-name").asString()))
                        return property.getValue().get("state").asString();
                }
            }
        }
        return "not installed";
    }

    private void prepareServer(String name) throws IOException {
        File source = new File(System.getProperty("jboss.home", "target/wildfly-41.0.1.Final"));
        assertTrue("Precondition: the testsuite server " + source, new File(source, "bin/standalone.sh").isFile());
        final File target = new File("target/" + name);
        deleteRecursive(target.toPath());
        final Path src = source.toPath();
        Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String rel = src.relativize(dir).toString();
                if (rel.equals("standalone/data") || rel.equals("standalone/log") || rel.equals("standalone/tmp") || rel.equals("standalone/configuration/standalone_xml_history"))
                    return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(target.toPath().resolve(rel));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String rel = src.relativize(file).toString();
                if (rel.startsWith("standalone/deployments/") && rel.endsWith("README.txt") == false)
                    return FileVisitResult.CONTINUE;
                Files.copy(file, target.toPath().resolve(rel), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
        // Without the deployments that the Arquillian server persisted, their content is not copied
        Path config = target.toPath().resolve("standalone/configuration/standalone-osgi.xml");
        String xml = new String(Files.readAllBytes(config), StandardCharsets.UTF_8);
        Files.write(config, xml.replaceAll("(?s)\\s*<deployments>.*?</deployments>", "").getBytes(StandardCharsets.UTF_8));
        new File(target, "bin/standalone.sh").setExecutable(true);
        home = target.getCanonicalFile();
    }

    private void startServer(String log) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(new File(home, "bin/standalone.sh").getPath(), "-c", "standalone-osgi.xml",
                "-Djboss.socket.binding.port-offset=" + OFFSET);
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        builder.environment().remove("JAVA_OPTS");
        builder.redirectErrorStream(true);
        console = new File(home, log);
        builder.redirectOutput(console);
        process = builder.start();
        long deadline = System.currentTimeMillis() + 180000;
        while (System.currentTimeMillis() < deadline) {
            String text = console.exists() ? new String(Files.readAllBytes(console.toPath()), StandardCharsets.UTF_8) : "";
            if (text.contains("WFLYSRV0025") || text.contains("WFLYSRV0026"))
                return;
            if (process.isAlive() == false)
                fail("Precondition: server process ended: " + text);
            Thread.sleep(500);
        }
        fail("Precondition: server did not start in time, see " + console);
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
