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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.dmr.ModelNode;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * The stored state of a scanned bundle whose file was deleted while the server was down goes to any later bundle that
 * is scanned with that file name, also an unrelated one: it gets the id, the data area and the autostart setting of the
 * old bundle. The stored states of deployments that are never deployed again stay in the storage area for good.
 *
 * <h3>Severity</h3>
 * minor. The new bundle finds the data files of another bundle; storage directories pile up.
 *
 * <h3>Component</h3>
 * jbosgi/wildfly/subsystem: {@code BundleInstallProcessor.restoreStorageState},
 * {@code BundleLifecycleIntegration.removeStaleStorageStatesAfterBoot}.
 *
 * <h3>Found by</h3>
 * R8 fix review, residual of tests/08 (umb-a-S02).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8 4.5.2: the persistent storage area of a bundle is not given to another bundle.
 *
 * <h3>Code location</h3>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/deployment/BundleInstallProcessor.java:107-111, 199-214 (ca1bdd98).
 *
 * <h3>Root cause</h3>
 * The stored state was restored for any deployment of the name, without a check of the symbolic name, and nothing
 * removed the states of deployments that were not deployed again.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi/testsuite/integration
 * mvn test -Dtest=ScannerStoredStateOfOtherBundleIssueTestCase
 * </pre>
 * Self-hosted: copies the testsuite server, deploys through the deployment scanner and restarts it.
 *
 * <h3>Expected</h3>
 * The unrelated bundle starts with an empty data area; the stored state of the deleted file is removed after the boot.
 *
 * <h3>Regression vs WF41</h3>
 * no.
 */
public class ScannerStoredStateOfOtherBundleIssueTestCase {

    static final int OFFSET = Integer.getInteger("issue.port.offset", 2700);
    static final int MGMT_PORT = 9990 + OFFSET;

    /** Writes its own symbolic name into owner.txt of its data area once, records what it finds on every start */
    public static class OwnerActivator implements BundleActivator {
        @Override
        public void start(BundleContext context) throws Exception {
            String me = context.getBundle().getSymbolicName();
            File owner = context.getDataFile("owner.txt");
            String seen = owner.exists() ? new String(Files.readAllBytes(owner.toPath()), StandardCharsets.UTF_8) : "<none>";
            if (owner.exists() == false)
                Files.write(owner.toPath(), me.getBytes(StandardCharsets.UTF_8));
            File dir = new File(System.getProperty("jboss.server.data.dir"), "storedstate");
            dir.mkdirs();
            Files.write(new File(dir, me + ".txt").toPath(), ("seen=" + seen + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        @Override
        public void stop(BundleContext context) {
        }
    }

    private File home;
    private Process process;

    @After
    public void tearDown() throws Exception {
        stopServer();
    }

    @Test
    public void testUnrelatedBundleWithSameFileName() throws Exception {
        prepareServer("issues-storedstate-server-1");
        deploy("storedstate-app.jar", "storedstate-a");
        startServer("console-1.out");
        observation("storedstate-a");
        stopServer();

        // The file is replaced by an unrelated bundle while the server is down
        deleteDeployment("storedstate-app.jar");
        deploy("storedstate-app.jar", "storedstate-b");
        startServer("console-2.out");
        String seen = observation("storedstate-b");
        assertTrue("The unrelated bundle storedstate-b starts with an empty data area, it found: " + seen, seen.equals("seen=<none>"));
    }

    @Test
    public void testStateOfDeletedFileRemoved() throws Exception {
        prepareServer("issues-storedstate-server-2");
        deploy("storedstate-gone.jar", "storedstate-gone");
        startServer("console-1.out");
        observation("storedstate-gone");
        assertTrue("Precondition: the stored state of the bundle", storageDirOf("storedstate-gone.jar") != null);
        stopServer();

        deleteDeployment("storedstate-gone.jar");
        startServer("console-2.out");
        long deadline = System.currentTimeMillis() + 60000;
        while (storageDirOf("storedstate-gone.jar") != null && System.currentTimeMillis() < deadline)
            Thread.sleep(500);
        assertFalse("The stored state of the deployment that was not deployed again is removed after the boot: " + storageDirOf("storedstate-gone.jar"),
                storageDirOf("storedstate-gone.jar") != null);
    }

    private void deploy(String fileName, final String symbolicName) throws IOException {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, fileName);
        archive.addClasses(OwnerActivator.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(symbolicName);
                builder.addBundleActivator(OwnerActivator.class);
                builder.addImportPackages(BundleActivator.class);
                return builder.openStream();
            }
        });
        archive.as(ZipExporter.class).exportTo(new File(home, "standalone/deployments/" + fileName), true);
    }

    private void deleteDeployment(String fileName) throws IOException {
        for (String suffix : new String[] { "", ".deployed" })
            Files.deleteIfExists(new File(home, "standalone/deployments/" + fileName + suffix).toPath());
    }

    /** The line that the activator of the bundle wrote at its first start */
    private String observation(String symbolicName) throws Exception {
        File file = new File(home, "standalone/data/storedstate/" + symbolicName + ".txt");
        long deadline = System.currentTimeMillis() + 60000;
        while (System.currentTimeMillis() < deadline) {
            if (file.exists()) {
                List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
                if (lines.isEmpty() == false)
                    return lines.get(0);
            }
            Thread.sleep(500);
        }
        fail("Precondition: bundle " + symbolicName + " was not started, see " + file);
        return null;
    }

    // The storage directory of the bundle with the location, null if there is none
    private File storageDirOf(String location) throws IOException {
        File[] dirs = new File(home, "standalone/data/osgi-store").listFiles();
        for (File dir : dirs != null ? dirs : new File[0]) {
            File props = new File(dir, "bundle-persistent.properties");
            if (dir.getName().startsWith("bundle-") && props.isFile()) {
                Properties properties = new Properties();
                try (InputStream input = new FileInputStream(props)) {
                    properties.load(input);
                }
                if (location.equals(properties.getProperty("Location")))
                    return dir;
            }
        }
        return null;
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
