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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.OperationBuilder;
import org.jboss.dmr.ModelNode;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.ByteArrayAsset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * Bundle.update and the refresh of a bundle whose deployment has a runtime-name of its own (name app-1.0.jar,
 * runtime-name app.jar) give the deployment the runtime name of the management name.
 *
 * <h2>Severity</h2>
 * major. The refresh writes runtime-name="app-1.0.jar" to standalone.xml, the module of the bundle becomes
 * deployment.app-1.0.jar, a WAR with Dependencies: deployment.app.jar fails, and after a restart the bundle has another
 * location: a new bundle id and an empty data area (4.4.1, 4.5.2).
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem: {@code BundleLifecycleIntegration.createUpdateRevision} and the refresh policy.
 *
 * <h2>Found by</h2>
 * R8 fix review, regression 6 (umb-a-U01).
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/BundleLifecycleIntegration.java:595-597, 1111-1113 (f49fa4d9).
 *
 * <h2>Root cause</h2>
 * The runtime name of an updated revision and the runtime name of the refreshed deployment were derived from the name
 * of the deployment in the management model, not from its runtime name, which is the bundle location.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=UpdateKeepsRuntimeNameIssueTestCase
 * </pre>
 * Not Arquillian: copies the testsuite server to target/issues-update-runtime-name and runs it at port offset
 * issue.port.offset (2400).
 *
 * <h2>Expected</h2>
 * The deployment keeps runtime-name app.jar, the bundle keeps its location, id and data area across a restart, and the
 * WAR stays deployed.
 *
 * <h2>Regression vs WF41</h2>
 * yes, introduced by f49fa4d9.
 */
public class UpdateKeepsRuntimeNameIssueTestCase {

    static final int OFFSET = Integer.getInteger("issue.port.offset", 2400);
    static final int MGMT_PORT = 9990 + OFFSET;
    static final String BSN = "update-runtime-name";
    static final String NAME = "update-runtime-name-1.0.jar";
    static final String RUNTIME = "update-runtime-name.jar";
    static final String WAR = "update-runtime-name-web.war";

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

    /** Updates the target from its embedded update.jar, or refreshes the bundles, in a thread */
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
                if (context.getBundle().getEntry("update.jar") != null) {
                    for (Bundle bundle : context.getBundles()) {
                        if (BSN.equals(bundle.getSymbolicName())) {
                            try (InputStream in = context.getBundle().getEntry("update.jar").openStream()) {
                                bundle.update(in);
                            }
                        }
                    }
                    System.out.println("ISSUE UPDATED");
                } else {
                    RefreshListener listener = new RefreshListener();
                    context.getBundle(0).adapt(FrameworkWiring.class).refreshBundles(null, listener);
                    System.out.println("ISSUE REFRESHED " + listener.latch.await(60, TimeUnit.SECONDS));
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                System.out.println("ISSUE OPERATION-FAILED " + ex);
            }
        }

        @Override
        public void stop(BundleContext context) {
        }
    }

    public static class RefreshListener implements FrameworkListener {
        final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void frameworkEvent(FrameworkEvent event) {
            latch.countDown();
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
    public void testUpdateAndRefresh() throws Exception {
        prepareServer("issues-update-runtime-name");
        startServer("console-1.out");
        String firstStart = deployTargetAndWar();
        execute(deploy("issues-updater.jar", null, operationBundle("issues-updater", target("2.0.0"))));
        assertTrue("Precondition: updated", await("ISSUE UPDATED"));
        undeploy("issues-updater.jar");
        execute(deploy("issues-refresher.jar", null, operationBundle("issues-refresher", null)));
        assertTrue("Precondition: refreshed", await("ISSUE REFRESHED"));
        undeploy("issues-refresher.jar");

        assertEquals("After the refresh the deployment has its runtime-name again", RUNTIME, readDeployment(NAME, "runtime-name"));
        assertTrue("standalone-osgi.xml keeps the runtime-name: " + configLines(), configLines().contains("runtime-name=\"" + RUNTIME + "\""));
        assertEquals("The WAR that depends on the module of the bundle stays deployed", "OK", readDeployment(WAR, "status"));

        stopServer();
        startServer("console-2.out");
        assertRestarted(firstStart);
    }

    @Test
    public void testUpdateAndRestart() throws Exception {
        prepareServer("issues-update-runtime-name-restart");
        startServer("console-1.out");
        String firstStart = deployTargetAndWar();
        execute(deploy("issues-updater.jar", null, operationBundle("issues-updater", target("2.0.0"))));
        assertTrue("Precondition: updated", await("ISSUE UPDATED"));
        undeploy("issues-updater.jar");
        assertEquals("The WAR that depends on the module of the bundle stays deployed", "OK", readDeployment(WAR, "status"));

        stopServer();
        startServer("console-2.out");
        assertRestarted(firstStart);
    }

    // Deploy the target with name and runtime-name of its own and a WAR that depends on its module, the first start line of the target
    private String deployTargetAndWar() throws Exception {
        ModelNode result = execute(deploy(NAME, RUNTIME, target("1.0.0")));
        assertEquals("Precondition: deployed " + result, "success", result.get("outcome").asString());
        result = execute(deploy(WAR, null, war()));
        assertEquals("Precondition: deployed " + result, "success", result.get("outcome").asString());
        List<String> starts = lines("ISSUE TARGET-START");
        assertEquals("Precondition: the target started: " + starts, 1, starts.size());
        String start = starts.get(0);
        assertTrue("Precondition: location " + RUNTIME + ": " + start, start.contains(" location=" + RUNTIME + " "));
        return start;
    }

    private void assertRestarted(String firstStart) throws Exception {
        List<String> starts = lines("ISSUE TARGET-START");
        String id = firstStart.replaceAll(".* id=(\\d+) .*", "$1");
        assertEquals("After the restart the bundle starts once: " + starts, 1, starts.size());
        String start = starts.get(0);
        assertTrue("After the restart the bundle has its id, location, the update and its data area: " + start,
                start.contains(" id=" + id + " ") && start.contains(" location=" + RUNTIME + " ") && start.contains(" version=2.0.0 ") && start.endsWith("data=existing"));
        assertEquals("After the restart the WAR is deployed", "OK", readDeployment(WAR, "status"));
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
        archive.addClasses(OperationActivator.class, RefreshListener.class);
        if (update != null)
            archive.add(new ByteArrayAsset(update), "update.jar");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                builder.addBundleActivator(OperationActivator.class);
                builder.addImportPackages(BundleActivator.class, FrameworkWiring.class);
                builder.addImportPackages(FrameworkListener.class);
                return builder.openStream();
            }
        });
        return bytes(archive);
    }

    private static byte[] war() {
        WebArchive war = ShrinkWrap.create(WebArchive.class, WAR);
        war.addAsWebResource(new StringAsset("issues"), "index.html");
        war.addAsManifestResource(new StringAsset("Manifest-Version: 1.0\nDependencies: deployment." + RUNTIME + "\n"), "MANIFEST.MF");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        war.as(ZipExporter.class).exportTo(out);
        return out.toByteArray();
    }

    private static byte[] bytes(JavaArchive archive) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        archive.as(ZipExporter.class).exportTo(out);
        return out.toByteArray();
    }

    private static OperationBuilder deploy(String name, String runtimeName, byte[] content) {
        ModelNode op = new ModelNode();
        op.get("operation").set("add");
        op.get("address").add("deployment", name);
        if (runtimeName != null)
            op.get("runtime-name").set(runtimeName);
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

    private String configLines() throws IOException {
        List<String> result = new ArrayList<String>();
        for (String line : new String(Files.readAllBytes(new File(home, "standalone/configuration/standalone-osgi.xml").toPath()), StandardCharsets.UTF_8).split("\n"))
            if (line.contains(BSN))
                result.add(line.trim());
        return result.toString();
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
