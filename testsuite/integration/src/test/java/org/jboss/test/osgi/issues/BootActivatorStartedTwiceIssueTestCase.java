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
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * At server boot the BundleActivator of a deployed (persistently started) bundle is started twice when its first start
 * fails: once by the deployment's Activate service and, after the deployment has already been marked FAILED, a second
 * time by the framework start level service. When the second attempt succeeds the bundle runs ACTIVE while its
 * deployment stays FAILED and the server reports "started with errors".
 *
 * <h2>Severity</h2>
 * minor. Every boot runs a failing activator twice (side effects twice, two ERROR reports: MSC000001 for the Activate
 * service and JBOSGI011026 / FrameworkEvent ERROR for the start level), whereas a management deploy of the same bundle
 * calls it once. An activator that fails transiently at boot (database or remote service not yet available) ends up
 * ACTIVE behind a FAILED deployment: monitoring and :read-resource report a failed application that is in fact
 * running, and an operator who redeploys it stops a working bundle.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem: {@code org.jboss.as.osgi.deployment.BundleActivateProcessor.BundleActivateService}
 * and {@code org.jboss.as.osgi.service.BundleLifecycleIntegration.BundleLifecycleImpl.start}; jbosgi-framework
 * {@code StartLevelManagerImpl} (start level ramp of persistently started bundles).
 *
 * <h2>Found by</h2>
 * wildfly-lifecycle workstream observation ("activator start() called TWICE per boot for a failing persistent bundle"),
 * verified and root-caused by wildfly-ee-2 (deep 2): a counting activator (count kept in a JVM system property, read
 * through /core-service=platform-mbean/type=runtime) and stack traces of both calls:
 * call #1 {@code BundleActivateProcessor$BundleActivateService.start(BundleActivateProcessor.java:131)} (MSC thread of
 * the deployment), call #2 about 50 ms later {@code BundleLifecycleIntegration$BundleLifecycleImpl.start(BundleLifecycleIntegration.java:308)}
 * from the framework start level service, after "MSC000001: Failed to start service
 * jboss.deployment.unit."boot-twice.jar".Activate".
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 9.3.1 Changing the Active Start Level: "If the requested start level is higher than the active start
 * level, the Framework must increase the start level by one and then start all bundles that meet the following
 * criteria: Bundles that are persistently marked started, and Bundles that have a bundle start level equal to the new
 * active start level." (one start per bundle and start level change; https://docs.osgi.org/specification/osgi.core/8.0.0/framework.startlevel.html#i3238802).
 * 4.4.5 Starting Bundles: a non-transient start sets the autostart setting before the activator runs, so the bundle
 * stays persistently marked started when BundleActivator.start throws. WildFly deployment model: a deployment whose
 * services failed to start has status FAILED; its runtime (here the bundle) is not expected to run.
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/deployment/BundleActivateProcessor.java:130-136
 * ({@code if (bundle.getState() != Bundle.ACTIVE) bundleManager.startBundle(bundle, Bundle.START_ACTIVATION_POLICY)},
 * a non-transient start at every boot; on BundleException the Activate service fails and the deployment is FAILED, but
 * the bundle keeps its "persistently started" autostart setting);
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/BundleLifecycleIntegration.java:306-309 (start level start
 * of a bundle whose deployment unit has no deferred phase: {@code bundleManager.startBundle(bundle, options)} without
 * looking at the state of the deployment). Pre-existing code (BundleActivateProcessor unchanged since the AS7 import,
 * commit e4fe0811).
 *
 * <h2>Root cause</h2>
 * Two independent agents start a persistent bundle at boot: the deployment (Activate service) and the framework start
 * level ramp (the bundle is persistently marked started and its start level is reached once the persistent
 * deployments are installed). Neither knows about the other's attempt: the deployment's failure does not clear or
 * suspend the autostart setting, and the start level service does not skip a bundle whose deployment failed.
 *
 * <h2>Reproduction</h2>
 * Scripted test (does not use Arquillian): it copies the testsuite server (target/wildfly-41.0.1.Final, without
 * deployments, data, log and tmp) to target/issues-boottwice-server, starts it with standalone-osgi.xml at port offset
 * 1100 (management 11090, http 9180), deploys the bundle, restarts the server with -Dboottwice.fail=always|first and
 * reads the start count. Run each class on its own (never in the same surefire run as Arquillian tests using offset 1100).
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=BootActivatorStartedTwiceIssueTestCase
 * </pre>
 * (Prerequisites: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork in the local Maven
 * repository; on JDK 24+ the testsuite needs surefire.security.args without -Djava.security.manager, see
 * TestJvmSecurityManagerIssueTestCase.) Deterministic (3 of 3 runs).
 * The port offset can be overridden with the system property issue.port.offset (default 1100; management 9990+n, HTTP 8080+n),
 * e.g. append {@code -Dissue.port.offset=300} to the mvn command to run the test beside a server that uses
 * offset 1100.
 *
 * <h2>Observed</h2>
 * <pre>
 * testFailingActivatorStartedOncePerBoot: java.lang.AssertionError: Expected BundleActivator.start to be called once
 *   at boot for the persistently started bundle whose activator throws, but it was called 2 times (deployment
 *   status FAILED, bundle state RESOLVED) expected:&lt;1&gt; but was:&lt;2&gt;
 * testDeploymentStatusMatchesBundleState: java.lang.AssertionError: Expected a bundle whose deployment is FAILED not
 *   to be running, but after the boot the deployment is FAILED and the bundle is ACTIVE (activator called 2 times:
 *   the second attempt by the start level service succeeded) expected:&lt;RESOLVED&gt; but was:&lt;ACTIVE&gt;
 * server.log: MSC000001: Failed to start service jboss.deployment.unit."boot-twice.jar".Activate ... JBAS011968
 *   ... JBOSGI011026: Framework Error: BundleException: JBOSGI011254: Cannot start bundle (second attempt)
 * </pre>
 *
 * <h2>Expected</h2>
 * One start attempt per boot (the same as for a management deploy, which calls the activator once and fails the
 * deploy), and a deployment status that matches the bundle: a FAILED deployment leaves the bundle not ACTIVE (or the
 * deployment is not FAILED if the bundle is started).
 *
 * <h2>Reference behaviour</h2>
 * A management deploy of the same failing bundle (no restart) calls the activator once and the deploy operation is
 * rolled back (checked in the exploration). Equinox/Felix have only the start level agent, so a failing persistent
 * bundle is started once per launch.
 *
 * <h2>Suggested fix (not applied)</h2>
 * Let only one agent start persistent bundles at boot: either the Activate service performs a transient start
 * (START_TRANSIENT) and the start level service skips bundles that have a deployment unit (they are started by their
 * Activate service), or the Activate service does not start bundles that the start level ramp will start and only
 * waits for the result. When the Activate service fails, record the failure so that the start level service does not
 * start the bundle again (e.g. a transient stop / skip while the deployment is FAILED).
 * Related: lifecycle/03 (WabNotServedAfterRestartIssueTestCase). Same "two uncoordinated start paths at boot" design:
 * the deployment's Activate service (BundleActivateProcessor:130-136) and the framework start-level ramp
 * (BundleLifecycleIntegration:306-309 via StartLevelManagerImpl) both start persisted bundles. Different symptom and
 * partly different fix location (here the start-level path ignores the FAILED deployment; in lifecycle/03 the
 * start-level path runs before the web context activator is attached), so not a duplicate, but one coordinated
 * boot-start fix should address both.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing behaviour; the code paths are unchanged since the AS7 import).
 */
public class BootActivatorStartedTwiceIssueTestCase {

    static final int OFFSET = Integer.getInteger("issue.port.offset", 1100);
    static final int MGMT_PORT = 9990 + OFFSET;
    static final String NAME = "boot-twice";

    /** Counts its start calls in a JVM system property; fails depending on -Dboottwice.fail (always | first) */
    public static class CountingActivator implements BundleActivator {
        @Override
        public void start(BundleContext context) {
            int count = Integer.getInteger("boottwice.count", 0) + 1;
            System.setProperty("boottwice.count", String.valueOf(count));
            String mode = System.getProperty("boottwice.fail", "");
            if (mode.equals("always") || mode.equals("first") && count == 1)
                throw new IllegalStateException("boot-twice activator fails on purpose (call " + count + ")");
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
    public void testFailingActivatorStartedOncePerBoot() throws Exception {
        restartWithFailure("always");
        String count = sysprop("boottwice.count");
        assertEquals("Expected BundleActivator.start to be called once at boot for the persistently started bundle whose activator throws, but it was called "
                + count + " times (deployment status " + deploymentStatus() + ", bundle state " + bundleState() + ")", "1", count);
    }

    @Test
    public void testDeploymentStatusMatchesBundleState() throws Exception {
        restartWithFailure("first");
        String status = deploymentStatus();
        String state = bundleState();
        assertEquals("Precondition: the deployment fails at boot (first activator call throws)", "FAILED", status);
        assertEquals("Expected a bundle whose deployment is FAILED not to be running, but after the boot the deployment is " + status + " and the bundle is "
                + state + " (activator called " + sysprop("boottwice.count") + " times: the second attempt by the start level service succeeded)", "RESOLVED", state);
    }

    private void restartWithFailure(String mode) throws Exception {
        prepareServer("issues-boottwice-server");
        startServer("console-1.out");
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            new ServerDeploymentHelper(client).deploy(NAME + ".jar", bundle().as(ZipExporter.class).exportAsInputStream());
        }
        assertEquals("Precondition: activator started once by the deployment", "1", sysprop("boottwice.count"));
        stopServer();
        startServer("console-2.out", "-Dboottwice.fail=" + mode);
        // the start level ramp runs right after the persistent deployments, give it time
        Thread.sleep(3000);
    }

    static JavaArchive bundle() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, NAME + ".jar");
        archive.addClasses(CountingActivator.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(NAME);
                builder.addBundleManifestVersion(2);
                builder.addBundleActivator(CountingActivator.class);
                builder.addImportPackages(BundleActivator.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    private ModelNode execute(ModelNode op) throws IOException {
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            return client.execute(op);
        }
    }

    private String sysprop(String name) throws IOException {
        ModelNode op = new ModelNode();
        op.get("operation").set("read-attribute");
        op.get("address").add("core-service", "platform-mbean").add("type", "runtime");
        op.get("name").set("system-properties");
        ModelNode result = execute(op).get("result");
        return result.hasDefined(name) ? result.get(name).asString() : "0";
    }

    private String deploymentStatus() throws IOException {
        ModelNode op = new ModelNode();
        op.get("operation").set("read-attribute");
        op.get("address").add("deployment", NAME + ".jar");
        op.get("name").set("status");
        return execute(op).get("result").asString();
    }

    private String bundleState() throws IOException {
        ModelNode op = new ModelNode();
        op.get("operation").set("read-attribute");
        op.get("address").add("subsystem", "osgi").add("bundle", NAME + ".jar");
        op.get("name").set("state");
        return execute(op).get("result").asString();
    }

    private void prepareServer(String name) throws IOException {
        File source = new File(System.getProperty("jboss.home", "target/wildfly-41.0.1.Final"));
        assertTrue("Precondition: the testsuite server " + source, new File(source, "bin/standalone.sh").isFile());
        File target = new File("target/" + name);
        deleteRecursive(target.toPath());
        Path src = source.toPath();
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
        new File(target, "bin/standalone.sh").setExecutable(true);
        home = target.getCanonicalFile();
        // drop deployments that earlier testsuite runs may have persisted in the configuration
        File config = new File(home, "standalone/configuration/standalone-osgi.xml");
        String xml = new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8);
        Files.write(config.toPath(), xml.replaceAll("(?s)\\s*<deployments>.*?</deployments>", "").getBytes(StandardCharsets.UTF_8));
    }

    private void startServer(String log, String... sysprops) throws Exception {
        List<String> cmd = new ArrayList<String>(Arrays.asList(new File(home, "bin/standalone.sh").getPath(), "-c", "standalone-osgi.xml",
                "-Djboss.socket.binding.port-offset=" + OFFSET));
        cmd.addAll(Arrays.asList(sysprops));
        ProcessBuilder builder = new ProcessBuilder(cmd);
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
        try {
            ModelNode op = new ModelNode();
            op.get("operation").set("shutdown");
            execute(op);
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
