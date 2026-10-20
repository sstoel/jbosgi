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
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import org.jboss.as.controller.client.ModelControllerClient;
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
 * {@code BundleContext.installBundle} called from the {@code BundleActivator.start} of a deployed bundle never
 * returns: the install is turned into a nested server deployment that waits for the controller lock held by the
 * deployment operation that is starting the bundle. The deployment operation times out after
 * jboss.as.management.blocking.timeout (300 s by default), is rolled back, and the server is left in state
 * restart-required with an MSC thread blocked in installBundle.
 *
 * <h3>Severity</h3>
 * major (a common OSGi pattern - a bundle that installs and starts bundles it embeds or provisions in its activator -
 * hangs the deployment for 5 minutes and leaves the server needing a restart; the same applies to the deployment
 * scanner, which also deploys through a management operation).
 *
 * <h3>Component</h3>
 * jbosgi umbrella, wildfly/subsystem: {@code org.jboss.as.osgi.service.BundleLifecycleIntegration.BundleLifecycleImpl
 * .createBundleRevision} (every BundleContext.installBundle becomes a synchronous {@code ServerDeploymentHelper.deploy}
 * through the server's own ModelControllerClient), with {@code BundleActivateProcessor.BundleActivateService.start}
 * (starts the bundle in an MSC thread while the deploy operation holds the controller lock).
 *
 * <h3>Found by</h3>
 * wildfly-lifecycle workstream, persistence checks of "bundles installed through the BundleContext only" on the patched
 * WildFly 41.0.1 + jbosgi R8 server (JDK 25): a bundle whose activator installs a file: bundle; {@code deploy} hung for
 * 5:11 min and ended with WFLYCTL0344; jstack showed the MSC thread in
 * {@code ServerDeploymentPlanResultFuture.get <- ServerDeploymentHelper.deploy <- BundleLifecycleIntegration
 * $BundleLifecycleImpl.createBundleRevision(BundleLifecycleIntegration.java:247) <- AbstractBundleContext.installBundle
 * <- InstActivator.start <- BundleActivateProcessor$BundleActivateService.start}.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, {@code BundleContext.installBundle} javadoc (no restriction on the calling context; the method
 * installs the bundle and returns it): "The following steps are required to install a bundle: ... The bundle's state
 * is set to INSTALLED. A bundle event of type BundleEvent.INSTALLED is fired. The Bundle object for the newly or
 * previously installed bundle is returned." 4.4.5 / 4.4.6: the Framework calls BundleActivator.start, and the
 * activator may use its BundleContext (which is valid from the STARTING state on).
 *
 * <h3>Code location</h3>
 * <ul>
 * <li>jbosgi wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/BundleLifecycleIntegration.java:229-247: only
 * when {@code OperationAssociation} is set (only by the /subsystem=osgi/bundle=X:start handler, AS7-5642) is the bundle
 * installed directly; otherwise {@code server.deploy(runtimeName, input)} blocks without timeout.</li>
 * <li>wildfly/subsystem/src/main/java/org/jboss/as/osgi/deployment/BundleActivateProcessor.java:131: the deployed
 * bundle is started from an MSC service of the running deploy operation, without that association.</li>
 * </ul>
 *
 * <h3>Root cause</h3>
 * A nested, synchronous management operation issued from inside the MSC phase of another management operation (the
 * AS7-5642 guard covers only the osgi bundle start operation, not the start of a deployment).
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=ActivatorInstallBundleDeadlockIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local
 * Maven repository. The test is not an Arquillian test: it copies the testsuite server to
 * target/issues-activatorinstall-server and starts it itself with port offset 1200 (management 11190) and
 * -Djboss.as.management.blocking.timeout=30 so the hang ends after 30 s instead of 300 s; it kills that server at the
 * end. Manually: deploy any bundle whose activator calls context.installBundle(...) with the CLI.)
 * The port offset can be overridden with the system property issue.port.offset (default 1200; management 9990+n),
 * e.g. append {@code -Dissue.port.offset=300} to the mvn command to run the test beside a server that uses
 * offset 1200.
 *
 * <h3>Observed</h3>
 * <pre>
 * org.junit.ComparisonFailure: Expected the deployment of a bundle whose BundleActivator.start installs a bundle
 *   through BundleContext.installBundle to succeed, but after 40367 ms the operation answered failed WFLYCTL0344:
 *   Operation timed out awaiting service container stability; server-state restart-required, child bundle state
 *   undefined expected:&lt;[success]&gt; but was:&lt;[failed]&gt;
 * (manual run with the default timeout: "WFLYCTL0348: Timeout after [300] seconds waiting for service container
 *  stability. Operation will roll back", "WFLYCTL0349: ... Process must be restarted"; the parent bundle stays STARTING.)
 * </pre>
 *
 * <h3>Expected</h3>
 * installBundle returns the installed bundle, the activator completes and the deployment succeeds.
 *
 * <h3>Reference behaviour</h3>
 * A plain jbosgi framework (and Equinox/Felix) install the bundle synchronously from an activator. In the server the
 * same activator code works when the parent bundle is started with /subsystem=osgi/bundle=X:start (AS7-5642 path).
 *
 * <h3>Suggested fix</h3>
 * Never issue a blocking nested management operation from an MSC thread or while a management operation is active:
 * mark the thread in {@code BundleActivateService.start} (and in every other place the subsystem starts or installs
 * bundles from an operation) like the bundle start handler does, and install such bundles directly (and register them
 * with the deployment model afterwards, asynchronously). Note: a trial with only that marker turned the hang into a fast
 * failure "JBOSGI011217: Cannot load module: deployment.activator-installed-child:0.0.0 ... JBOSGI011272: Timeout getting:
 * jboss.module.service..." (the directly installed bundle's module service does not come up while the activating MSC
 * thread waits for it in BundleLifecycleImpl.resolve), so a complete fix also has to resolve/start such bundles without
 * blocking on MSC from the MSC thread.
 * Related: wildfly/26 (RefreshRedeployDeadlockIssueTestCase) and ee/01 (BundleUsedByWarUndeployRollbackIssueTestCase):
 * the same design flaw (synchronous nested server deployment operations from BundleLifecycleIntegration while another
 * operation or the framework locks are held), with a distinct trigger (installBundle from an activator during
 * deploy).
 *
 * <h3>Regression vs WF41</h3>
 * no (measured on a WF41 server built from origin/WF41 77f95be6 on the same patched WildFly 41.0.1, JDK 25: same
 * failure). createBundleRevision's nested deploy and the AS7-5642 guard predate WF41. The WF41 run used harness
 * adaptations only, no change to this test or to the code under test: the jacc {@code <policy>} removed from
 * standalone-osgi.xml, as r8 42d6c470 does (without it org.wildfly.security.policy fails on WildFly 41 and every
 * deferred start fails with JBAS011969), and the r8 Arquillian JBOSGiApplicationArchiveProcessor from 54b65e85
 * (in-container ManagementClient lookup); see the regression-2 workstream NOTES.md.
 */
public class ActivatorInstallBundleDeadlockIssueTestCase {

    static final int OFFSET = Integer.getInteger("issue.port.offset", 1200);
    static final int MGMT_PORT = 9990 + OFFSET;
    /** jboss.as.management.blocking.timeout of the test server (default 300 s), so that the hang ends in time */
    static final int BLOCKING_TIMEOUT = 30;

    /** Installs and starts the bundle embedded as child.jar from its start method, as provisioning bundles do */
    public static class InstallingActivator implements BundleActivator {
        @Override
        public void start(BundleContext context) throws Exception {
            try (InputStream in = context.getBundle().getEntry("child.jar").openStream()) {
                Bundle child = context.installBundle("activator-installed-child", in);
                child.start();
            }
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
    public void testInstallBundleFromActivatorOfDeployedBundle() throws Exception {
        prepareServer("issues-activatorinstall-server");
        startServer("console-1.out");
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            ModelNode add = new ModelNode();
            add.get("operation").set("add");
            add.get("address").add("deployment", "installing-parent.jar");
            add.get("content").add().get("bytes").set(parentBundle());
            add.get("enabled").set(true);
            long begin = System.currentTimeMillis();
            ModelNode result = client.execute(add);
            long millis = System.currentTimeMillis() - begin;

            ModelNode read = new ModelNode();
            read.get("operation").set("read-attribute");
            read.get("name").set("server-state");
            String serverState = client.execute(read).get("result").asString();
            read = new ModelNode();
            read.get("operation").set("read-attribute");
            read.get("address").add("subsystem", "osgi").add("bundle", "activator-installed-child");
            read.get("name").set("state");
            String childState = client.execute(read).get("result").asString();

            assertEquals("Expected the deployment of a bundle whose BundleActivator.start installs a bundle through BundleContext.installBundle to succeed, "
                    + "but after " + millis + " ms the operation answered " + result.get("outcome").asString() + " " + result.get("failure-description").asString()
                    + "; server-state " + serverState + ", child bundle state " + childState, "success", result.get("outcome").asString());
        }
    }

    private byte[] parentBundle() {
        final JavaArchive child = ShrinkWrap.create(JavaArchive.class, "child.jar");
        child.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName("activator-installed-child");
                return builder.openStream();
            }
        });
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        child.as(ZipExporter.class).exportTo(bytes);

        final JavaArchive parent = ShrinkWrap.create(JavaArchive.class, "installing-parent.jar");
        parent.addClasses(InstallingActivator.class);
        parent.add(new ByteArrayAsset(bytes.toByteArray()), "child.jar");
        parent.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName("installing-parent");
                builder.addBundleActivator(InstallingActivator.class);
                builder.addImportPackages(BundleActivator.class);
                return builder.openStream();
            }
        });
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        parent.as(ZipExporter.class).exportTo(out);
        return out.toByteArray();
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
        // Without the deployments that the Arquillian server persisted: their content is in standalone/data, which is not
        // copied, and the server would not boot (WFLYSRV0137)
        Path config = target.toPath().resolve("standalone/configuration/standalone-osgi.xml");
        String xml = new String(Files.readAllBytes(config), StandardCharsets.UTF_8);
        Files.write(config, xml.replaceAll("(?s)\\s*<deployments>.*?</deployments>", "").getBytes(StandardCharsets.UTF_8));
        new File(target, "bin/standalone.sh").setExecutable(true);
        return home = target.getCanonicalFile();
    }

    private void startServer(String log) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(new File(home, "bin/standalone.sh").getPath(), "-c", "standalone-osgi.xml",
                "-Djboss.socket.binding.port-offset=" + OFFSET, "-Djboss.as.management.blocking.timeout=" + BLOCKING_TIMEOUT);
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
