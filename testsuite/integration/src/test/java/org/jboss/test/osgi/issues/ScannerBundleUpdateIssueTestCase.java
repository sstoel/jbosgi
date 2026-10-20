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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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
import org.jboss.as.controller.client.OperationBuilder;
import org.jboss.dmr.ModelNode;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.ByteArrayAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * Bundle.update of a bundle that the deployment scanner deployed drops the scanner as the owner of the deployment, and
 * after a restart the scanner deploys the old content again: the update is reverted.
 *
 * <h2>Severity</h2>
 * major. The update of a scanner bundle is lost at the next restart (4.4.9), the updated bundle data area is used by
 * the old content, and removing the scanned file does not undeploy the bundle any more.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem: {@code BundleLifecycleIntegration.replaceDeployment} and
 * {@code createUpdateRevision}.
 *
 * <h2>Found by</h2>
 * R8 fix review, confirmed by test (umb-a-U03); lifecycle/06 for scanner deployments.
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/BundleLifecycleIntegration.java:627-653 (f49fa4d9).
 *
 * <h2>Root cause</h2>
 * The full-replace of the update did not pass the owner of the deployment. A scanner deployment is not persistent:
 * the scanner deploys its file when the server starts, and the file had the content from before the update.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=ScannerBundleUpdateIssueTestCase
 * </pre>
 * Not Arquillian: copies the testsuite server to target/issues-scanner-update and runs it at port offset
 * issue.port.offset (2500).
 *
 * <h2>Expected</h2>
 * The deployment stays owned by the scanner and is not deployed again by it; after a restart the bundle is the updated
 * one with its id and data area; removing the scanned file undeploys it.
 *
 * <h2>Regression vs WF41</h2>
 * partly: the owner is dropped since f49fa4d9, the revert after a restart is lifecycle/06 for scanner deployments.
 */
public class ScannerBundleUpdateIssueTestCase {

    static final int OFFSET = Integer.getInteger("issue.port.offset", 2500);
    static final int MGMT_PORT = 9990 + OFFSET;
    static final String BSN = "scanner-update";
    static final String FILE = "scanner-update.jar";

    /** Reports its id, location, version and whether its data area file existed, which it creates */
    public static class TargetActivator implements BundleActivator {
        @Override
        public void start(BundleContext context) throws Exception {
            Bundle bundle = context.getBundle();
            File marker = context.getDataFile("marker.txt");
            String data = marker.exists() ? "existing" : "new";
            Files.write(marker.toPath(), "marker".getBytes(StandardCharsets.UTF_8));
            System.out.println("ISSUE TARGET-START id=" + bundle.getBundleId() + " location=" + bundle.getLocation() + " version=" + bundle.getVersion()
                    + " data=" + data);
        }

        @Override
        public void stop(BundleContext context) {
        }
    }

    /** Updates the target from its embedded update.jar in a thread */
    public static class OperationActivator implements BundleActivator, Runnable {
        private volatile BundleContext context;

        @Override
        public void start(BundleContext context) {
            this.context = context;
            new Thread(this, "issues-operation").start();
        }

        @Override
        public void run() {
            try {
                for (Bundle bundle : context.getBundles()) {
                    if (BSN.equals(bundle.getSymbolicName())) {
                        try (InputStream in = context.getBundle().getEntry("update.jar").openStream()) {
                            bundle.update(in);
                        }
                    }
                }
                System.out.println("ISSUE UPDATED");
            } catch (Exception ex) {
                ex.printStackTrace();
                System.out.println("ISSUE OPERATION-FAILED " + ex);
            }
        }

        @Override
        public void stop(BundleContext context) {
        }
    }

    private File home;
    private Process process;
    private File console;

    @After
    public void tearDown() throws Exception {
        stopServer();
    }

    @Test
    public void testUpdateOfScannerBundle() throws Exception {
        prepareServer("issues-scanner-update");
        Files.write(new File(home, "standalone/deployments/" + FILE).toPath(), target("1.0.0"));
        startServer("console-1.out");
        assertTrue("Precondition: the scanner deployed " + FILE, await("ISSUE TARGET-START"));
        String firstStart = lines("ISSUE TARGET-START").get(0);
        String id = firstStart.replaceAll(".* id=(\\d+) .*", "$1");

        execute(deploy("issues-updater.jar", operationBundle("issues-updater", target("2.0.0"))));
        assertTrue("Precondition: updated", await("ISSUE UPDATED"));
        undeploy("issues-updater.jar");
        // A few intervals of the scanner, which must not deploy the file again
        Thread.sleep(12000);
        assertTrue("The deployment stays owned by the scanner: " + readDeployment(FILE, "owner"), readDeployment(FILE, "owner").contains("deployment-scanner"));
        assertEquals("The scanner does not deploy the file again after the update: " + lines("ISSUE TARGET-START"), 2, lines("ISSUE TARGET-START").size());

        stopServer();
        startServer("console-2.out");
        assertTrue("The scanner deploys the bundle again", await("ISSUE TARGET-START"));
        List<String> starts = lines("ISSUE TARGET-START");
        assertEquals("After the restart the bundle starts once: " + starts, 1, starts.size());
        String start = starts.get(0);
        assertTrue("After the restart the bundle has its id, location, the update and its data area: " + start,
                start.contains(" id=" + id + " ") && start.contains(" location=" + FILE + " ") && start.contains(" version=2.0.0 ") && start.endsWith("data=existing"));

        // Removing the scanned file undeploys the bundle
        new File(home, "standalone/deployments/" + FILE).delete();
        long deadline = System.currentTimeMillis() + 30000;
        while (readDeployment(FILE, "runtime-name").equals("undefined") == false && System.currentTimeMillis() < deadline)
            Thread.sleep(500);
        assertEquals("The scanner undeploys the bundle when its file is removed", "undefined", readDeployment(FILE, "runtime-name"));
    }

    private static byte[] target(final String version) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, BSN + ".jar");
        archive.addClasses(TargetActivator.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(BSN);
                builder.addBundleVersion(version);
                builder.addBundleActivator(TargetActivator.class);
                builder.addImportPackages(BundleActivator.class);
                return builder.openStream();
            }
        });
        return bytes(archive);
    }

    private static byte[] operationBundle(final String name, byte[] update) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name + ".jar");
        archive.addClasses(OperationActivator.class);
        archive.add(new ByteArrayAsset(update), "update.jar");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                builder.addBundleActivator(OperationActivator.class);
                builder.addImportPackages(BundleActivator.class);
                return builder.openStream();
            }
        });
        return bytes(archive);
    }

    private static byte[] bytes(JavaArchive archive) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        archive.as(ZipExporter.class).exportTo(out);
        return out.toByteArray();
    }

    private static OperationBuilder deploy(String name, byte[] content) {
        ModelNode op = new ModelNode();
        op.get("operation").set("add");
        op.get("address").add("deployment", name);
        op.get("content").add().get("input-stream-index").set(0);
        op.get("enabled").set(true);
        OperationBuilder builder = OperationBuilder.create(op);
        builder.addInputStream(new ByteArrayInputStream(content));
        return builder;
    }

    private ModelNode execute(OperationBuilder builder) throws IOException {
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            return client.execute(builder.build());
        }
    }

    private void undeploy(String name) throws IOException {
        for (String operation : new String[] { "undeploy", "remove" }) {
            ModelNode op = new ModelNode();
            op.get("operation").set(operation);
            op.get("address").add("deployment", name);
            execute(OperationBuilder.create(op));
        }
    }

    private String readDeployment(String name, String attribute) throws IOException {
        ModelNode op = new ModelNode();
        op.get("operation").set("read-attribute");
        op.get("address").add("deployment", name);
        op.get("name").set(attribute);
        return execute(OperationBuilder.create(op)).get("result").asString();
    }

    private boolean await(String marker) throws Exception {
        long deadline = System.currentTimeMillis() + 90000;
        while (System.currentTimeMillis() < deadline) {
            if (lines(marker).size() > 0)
                return true;
            Thread.sleep(200);
        }
        return false;
    }

    private List<String> lines(String marker) throws IOException {
        List<String> result = new ArrayList<String>();
        String text = console != null && console.exists() ? new String(Files.readAllBytes(console.toPath()), StandardCharsets.UTF_8) : "";
        for (String line : text.split("\n"))
            if (line.contains(marker))
                result.add(line.substring(line.indexOf(marker)).trim());
        return result;
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
