/*
 * #%L
 * JBossOSGi
 * %%
 * Copyright (C) 2026 RTR
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 * #L%
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
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

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
 * Bundles deployed through the deployment scanner get new bundle ids on every server restart, and the framework hands
 * the persistent data area of the bundle that had that id before the restart to whichever bundle gets the id now: one
 * bundle reads (and overwrites) another bundle's persistent data.
 *
 * <h2>Severity</h2>
 * critical (data corruption: BundleContext.getDataFile of bundle A returns files written by bundle B after a plain
 * server restart; configuration, keys or caches of one application end up in another).
 *
 * <h2>Component</h2>
 * jbosgi (umbrella) wildfly/subsystem: org.jboss.as.osgi.service.InitialDeploymentTracker /
 * org.jboss.as.osgi.deployment.BundleInstallProcessor (stored bundle identity only restored for deployments in the
 * persisted management model); together with jbosgi-framework core StorageManagerImpl.createStorageState (an existing
 * bundle-N directory of another bundle is taken over as "previous revision", its data files are kept) and the in-memory
 * bundle id counter of connect/14 (BundleIdReusedAfterRestartIssueTestCase).
 *
 * <h2>Found by</h2>
 * Tests workstream, round 4/5 upgrade and restart checks: three bundles deployed through standalone/deployments on a
 * WF41 and on the r8 server, data area contents compared across :shutdown/start cycles (ids 25-27 assigned in a
 * different order on each boot, data counters continued in the "wrong" bundle).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 4.5.2 Persistent Storage: "The Framework should provide a private persistent storage area for each
 * installed bundle." BundleContext.getDataFile javadoc: "Creates a File object for a file in the persistent storage
 * area provided for the bundle by the Framework." Bundle.getBundleId javadoc: "A bundle's unique identifier ... Is
 * unique and persistent. ... Its value is not reused for another bundle, even after a bundle is uninstalled."
 * Core R8 4.4.10 Uninstalling Bundles: "To whatever extent possible, the Framework must remove any resources related
 * to the bundle."
 *
 * <h2>Code location</h2>
 * <ul>
 * <li>jbosgi/wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/InitialDeploymentTracker.java:151-165
 * (getDeploymentNames reads the deployments of the persisted model at subsystem add; deployment scanner deployments are
 * added later, so they are never "initial") and deployment/BundleInstallProcessor.java:82-84 (restoreStorageState only
 * for those names): a scanner deployment is installed as a NEW bundle on every boot, its stored identity is ignored.
 * Deployments in the persisted model (CLI {@code deploy}) keep their ids (checked: 3 restarts, ids and data stable).</li>
 * <li>jbosgi-framework core/src/main/java/org/jboss/osgi/framework/internal/StorageManagerImpl.java:136-170: a
 * new bundle that gets id N reuses an existing bundle-N directory of a different location as its previous revision
 * (revision+1, Location overwritten, data files kept).</li>
 * <li>connect/14: the next bundle id is not persisted, so the ids of the not restored scanner bundles are given out
 * again (starting at the highest restored id + 1).</li>
 * </ul>
 * All three are unchanged on WF41 (same behaviour observed on the WF41 server); not a regression.
 *
 * <h2>Root cause</h2>
 * The subsystem does not restore the stored identity of scanner deployments, and the framework neither keeps bundle
 * ids unique across restarts nor clears a stale storage directory before giving it to a new bundle.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=ScannerBundleDataMixupIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local
 * Maven repository. The test is not an Arquillian test: it starts and stops its own copy of the server with port
 * offset 500 (management 10490), so -Darq... options have no effect on it.)
 * The port offset can be overridden with the system property issue.port.offset (default 500; management 9990+n,
 * HTTP 8080+n), e.g. append {@code -Dissue.port.offset=300} to the mvn command to run the test beside a server that
 * uses offset 500.
 * <h2>Observed</h2>
 * <pre>
 * testNewBundleDoesNotGetDataOfAnotherBundle: Expected the new bundle datamix-b to start with an empty data area,
 *   but it found the data of datamix-a (id=25 seen=&lt;none&gt; / datamix-b: id=25 seen=datamix-a)
 * testBundlesKeepTheirOwnDataAfterRestart: Expected every scanner-deployed bundle to find its own marker in its data
 *   area after a restart, but these did not: [datamix-1 [id=25 seen=&lt;none&gt; -&gt; id=26 seen=datamix-4],
 *   datamix-2 [id=28 seen=&lt;none&gt; -&gt; id=25 seen=datamix-1], datamix-3 [id=29 ... -&gt; id=28 seen=datamix-2],
 *   datamix-4 [id=26 ... -&gt; id=27 seen=datamix-5], datamix-5 [id=27 ... -&gt; id=29 seen=datamix-3]] expected:&lt;0&gt; but was:&lt;5&gt;
 * (3 runs: the first method fails every time, the second failed with 5, 4 and 5 of 5 bundles mixed up; it can only
 * pass if the boot happens to assign all five ids in the previous order. With :reload instead of a restart the scanner
 * bundles are uninstalled and lose their data instead.)
 * </pre>
 *
 * <h2>Expected</h2>
 * After a restart every bundle finds exactly the data it wrote itself, and a bundle that is new after the restart
 * starts with an empty data area.
 *
 * <h2>Reference behaviour</h2>
 * Deployments in the persisted model on the same server keep their ids and data across restarts. Equinox/Felix keep
 * bundle ids unique and delete the data area on uninstall (see connect/14).
 *
 * <h2>Suggested fix</h2>
 * Restore the stored identity for every deployment whose location has a storage state (not only for names of the
 * initial model), e.g. by looking the storage state up by location in BundleInstallProcessor unconditionally; uninstall
 * (and delete the storage of) stored deployment bundles that are not deployed again; in StorageManagerImpl refuse or
 * wipe an existing bundle-N directory whose stored Location differs from the new bundle; and fix connect/14.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing; the same id reshuffling and data takeover were observed on the WF41 server).
 */
public class ScannerBundleDataMixupIssueTestCase {

    static final int OFFSET = Integer.getInteger("issue.port.offset", 500);
    static final int MGMT_PORT = 9990 + OFFSET;

    /** Writes its own symbolic name into owner.txt of its data area once, records what it finds on every start */
    public static class MarkerActivator implements BundleActivator {
        @Override
        public void start(BundleContext context) throws Exception {
            String me = context.getBundle().getSymbolicName();
            File owner = context.getDataFile("owner.txt");
            String seen = owner.exists() ? new String(Files.readAllBytes(owner.toPath()), StandardCharsets.UTF_8) : "<none>";
            if (owner.exists() == false)
                Files.write(owner.toPath(), me.getBytes(StandardCharsets.UTF_8));
            File dir = new File(System.getProperty("jboss.server.data.dir"), "datamix");
            dir.mkdirs();
            Files.write(new File(dir, me + ".txt").toPath(), ("id=" + context.getBundle().getBundleId() + " seen=" + seen + "\n").getBytes(StandardCharsets.UTF_8),
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
                if (rel.equals("standalone/data") || rel.equals("standalone/log") || rel.equals("standalone/tmp"))
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
        new File(target, "bin/standalone.sh").setExecutable(true);
        return home = target.getCanonicalFile();
    }

    private void startServer(String log) throws Exception {
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
                return;
            if (process.isAlive() == false)
                fail("Precondition: server process ended: " + text);
            Thread.sleep(500);
        }
        fail("Precondition: server did not start in time, see " + out);
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

    private void deploy(String symbolicName) throws IOException {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, symbolicName + ".jar");
        archive.addClasses(MarkerActivator.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(symbolicName);
                builder.addBundleActivator(MarkerActivator.class);
                builder.addImportPackages(BundleActivator.class);
                return builder.openStream();
            }
        });
        archive.as(ZipExporter.class).exportTo(new File(home, "standalone/deployments/" + symbolicName + ".jar"), true);
    }

    private void undeployWhileStopped(String symbolicName) throws IOException {
        for (String suffix : new String[] { ".jar", ".jar.deployed" })
            Files.deleteIfExists(new File(home, "standalone/deployments/" + symbolicName + suffix).toPath());
    }

    /** The lines that the activator of the bundle wrote, one per start */
    private List<String> observations(String symbolicName, int expected) throws Exception {
        File file = new File(home, "standalone/data/datamix/" + symbolicName + ".txt");
        long deadline = System.currentTimeMillis() + 60000;
        while (System.currentTimeMillis() < deadline) {
            if (file.exists()) {
                List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
                if (lines.size() >= expected)
                    return lines;
            }
            Thread.sleep(500);
        }
        fail("Precondition: bundle " + symbolicName + " was not started " + expected + " times, see " + file);
        return null;
    }

    @Test
    public void testNewBundleDoesNotGetDataOfAnotherBundle() throws Exception {
        prepareServer("issues-datamix-server-1");
        deploy("datamix-a");
        startServer("console-1.out");
        String first = observations("datamix-a", 1).get(0);
        stopServer();

        // datamix-a is removed and datamix-b is added while the server is down
        undeployWhileStopped("datamix-a");
        deploy("datamix-b");
        startServer("console-2.out");
        String seen = observations("datamix-b", 1).get(0);
        assertTrue("Expected the new bundle datamix-b to start with an empty data area, but it found the data of datamix-a ("
                + first + " / datamix-b: " + seen + ")", seen.endsWith("seen=<none>"));
    }

    @Test
    public void testBundlesKeepTheirOwnDataAfterRestart() throws Exception {
        prepareServer("issues-datamix-server-2");
        String[] names = { "datamix-1", "datamix-2", "datamix-3", "datamix-4", "datamix-5" };
        for (String name : names)
            deploy(name);
        startServer("console-1.out");
        for (String name : names)
            observations(name, 1);
        stopServer();

        startServer("console-2.out");
        List<String> wrong = new ArrayList<String>();
        for (String name : names) {
            List<String> lines = observations(name, 2);
            if (lines.get(1).endsWith("seen=" + name) == false)
                wrong.add(name + " [" + lines.get(0) + " -> " + lines.get(1) + "]");
        }
        assertEquals("Expected every scanner-deployed bundle to find its own marker in its data area after a restart, but these did not: " + wrong, 0, wrong.size());
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
