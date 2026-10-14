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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.ServletContext;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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
import org.osgi.framework.FrameworkUtil;

/**
 * A Web Application Bundle deployed through the management API (CLI {@code deploy}, console) is not served after a
 * server restart: the bundle is ACTIVE and the deployment reports status OK with its context-root, but every request
 * to the Web-ContextPath answers 404 until the WAB is redeployed or stopped and started again.
 *
 * <h3>Severity</h3>
 * critical (the web application is down after every server restart while all management views say it is running;
 * 404 after 12 of 13 manual restarts on JDK 25, 1 of 1 on JDK 27, and in every run of this test (4 runs)).
 *
 * <h3>Component</h3>
 * jbosgi umbrella: wildfly/subsystem {@code InitialDeploymentTracker} / {@code PersistentBundlesIntegration} (the
 * framework starts the bundles of the persisted deployments as soon as their DEPENDENCIES phase is done) and
 * wildfly/integration/web {@code WebContextActivationProcessor} / {@code WebContextLifecycleInterceptor} (the web
 * context is only started from the bundle's transition to ACTIVE, and only if the ContextActivator is already attached).
 *
 * <h3>Found by</h3>
 * wildfly-lifecycle workstream, restart persistence checks on the patched WildFly 41.0.1 + jbosgi R8 server (JDK 25 and
 * JDK 27): a WAB with a servlet, deployed with the CLI, answered 200 before and 404 after {@code :shutdown} + start.
 *
 * <h3>Spec reference</h3>
 * OSGi Compendium R8 128.3.2 Starting the Web Application Bundle
 * (https://docs.osgi.org/specification/osgi.cmpn/8.0.0/service.war.html): "A WAB's Web Application must be deployed
 * while the WAB is ready. Deployed means that the Web Application is available for web requests. ... Ready is when the
 * WAB: Is in the ACTIVE state, or Has a lazy activation policy and is in the STARTING state."
 *
 * <h3>Code location</h3>
 * <ul>
 * <li>wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/InitialDeploymentTracker.java:83: the initial
 * deployments count as complete when their {@code Phase.DEPENDENCIES} service is up; PersistentBundlesIntegration.java:54
 * then resolves the restored bundles and jbosgi-framework {@code FrameworkActive.createServiceValue}
 * (FrameworkActive.java:151, {@code increaseFrameworkStartLevel}) starts every persistently started one, long before
 * the deployment reaches its INSTALL phase.</li>
 * <li>wildfly/integration/web/src/main/java/org/jboss/as/osgi/web/WebContextActivationProcessor.java:49-51 attaches the
 * {@code ContextActivator} to the bundle revision only in Phase.INSTALL (WebExtension.java:91, INSTALL_WAB_DEPLOYMENT);
 * WebContextLifecycleInterceptor.java:90-91 does nothing when the bundle becomes ACTIVE without it.</li>
 * <li>wildfly/subsystem/src/main/java/org/jboss/as/osgi/deployment/BundleActivateProcessor.java:129: the deployment's
 * own Activate service finds the bundle ACTIVE and does not start it again, so nothing ever starts the web context.</li>
 * </ul>
 * In some boots the interceptor runs with the activator but before the Undertow deployment service is up and logs
 * "JBOSGI011026: Framework Error: java.lang.IllegalStateException: WFLYUT0058: Cannot activate context: service
 * jboss.deployment.unit."lc-wab.wab".undertow-deployment ... Caused by: IllegalStateException: Service unavailable"
 * (seen once, in the one manual restart where the WAB was nevertheless served).
 *
 * <h3>Root cause</h3>
 * Two independent start paths at boot: the framework start-level start of the restored bundles runs before the WAB
 * deployment has installed its web context, and the web integration only activates the context on the ACTIVE
 * transition it misses.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=WabNotServedAfterRestartIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local
 * Maven repository. The test is not an Arquillian test: it copies the testsuite server to
 * target/issues-wabrestart-server and starts/stops it itself with port offset 1200 (management 11190, HTTP 9280), so
 * those ports must be free. Manually: {@code deploy x.wab} with the CLI, request the context path (200), restart the
 * server, request again (404); {@code /subsystem=osgi/bundle=x.wab:stop} + {@code :start} or a redeploy bring it back.)
 * The port offset can be overridden with the system property issue.port.offset (default 1200; management 9990+n, HTTP 8080+n),
 * e.g. append {@code -Dissue.port.offset=300} to the mvn command to run the test beside a server that uses
 * offset 1200.
 *
 * <h3>Observed</h3>
 * <pre>
 * org.junit.ComparisonFailure: Expected the deployed WAB (bundle ACTIVE) to be served after a server restart as it
 *   was before, but GET /restartwab/info answered 404 &lt;html&gt;&lt;head&gt;&lt;title&gt;Error&lt;/title&gt;...404 - Not Found...
 *   expected:&lt;[200 bundle=restart-wab]&gt; but was:&lt;[404 ...]&gt;
 * (server log of the second boot: "JBOSGI011002: Bundle started: restart-wab:1.0.0", no
 *  "WFLYUT0021: Registered web context: '/restartwab'"; /deployment=restart-wab.wab reports status OK.)
 * </pre>
 *
 * <h3>Expected</h3>
 * After a restart the WAB is served on its Web-ContextPath as it was before (200 bundle=restart-wab).
 *
 * <h3>Reference behaviour</h3>
 * Freshly deployed WABs, WABs after {@code :reload} (where lifecycle/01 reinstalls the bundle) and WABs in
 * standalone/deployments (not in the persisted model, so not started by the framework at boot; see tests/08) are served.
 *
 * <h3>Suggested fix</h3>
 * Start the web context when the activator is attached to an already ACTIVE (or lazily STARTING) bundle, after the
 * Undertow deployment service is up. Trial fix verified (test passes): in {@code WebContextActivationProcessor.deploy},
 * if the bundle is already ACTIVE, install a service that requires
 * {@code depUnit.getServiceName().append("undertow-deployment")} and calls {@code activator.startContext()} (a complete
 * fix also injects the BundleContext and registers the ServletContext service like the interceptor does, and handles
 * the WFLYUT0058 race by deferring the interceptor's startContext until that service is up). Alternatively make the
 * initial deployment tracker wait for the INSTALL phase of WAB deployments.
 * Related: ee/20 (BootActivatorStartedTwiceIssueTestCase). Same "two uncoordinated start paths at boot" design: the
 * deployment's Activate service (BundleActivateProcessor:130-136) and the framework start-level ramp
 * (BundleLifecycleIntegration:306-309 via StartLevelManagerImpl) both start persisted bundles. Different symptom and
 * partly different fix location (here the start-level path runs before the web context activator is attached; in ee/20
 * the start-level path ignores the FAILED deployment), so not a duplicate, but one coordinated boot-start fix should
 * address both.
 *
 * <h3>Regression vs WF41</h3>
 * no (measured on a WF41 server built from origin/WF41 77f95be6 on the same patched WildFly 41.0.1, JDK 25: same
 * failure). The involved classes are unchanged on WF41-osgi-r8 except a pom version. The WF41 run used harness
 * adaptations only, no change to this test or to the code under test: the jacc {@code <policy>} removed from
 * standalone-osgi.xml, as r8 42d6c470 does (without it org.wildfly.security.policy fails on WildFly 41 and every
 * deferred start fails with JBAS011969), and the r8 Arquillian JBOSGiApplicationArchiveProcessor from 54b65e85
 * (in-container ManagementClient lookup); see the regression-2 workstream NOTES.md.
 */
public class WabNotServedAfterRestartIssueTestCase {

    static final int OFFSET = Integer.getInteger("issue.port.offset", 1200);
    static final int MGMT_PORT = 9990 + OFFSET;
    static final int HTTP_PORT = 8080 + OFFSET;

    /** The servlet of the WAB: answers with the symbolic name of its bundle */
    @WebServlet("/info")
    public static class InfoServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
            res.getWriter().print("bundle=" + FrameworkUtil.getBundle(getClass()).getSymbolicName());
        }
    }

    private File home;
    private Process process;

    @After
    public void tearDown() throws Exception {
        stopServer();
    }

    @Test
    public void testDeployedWabServedAfterRestart() throws Exception {
        prepareServer("issues-wabrestart-server");
        startServer("console-1.out");
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            new ServerDeploymentHelper(client).deploy("restart-wab.wab", wab("restart-wab", "/restartwab").as(ZipExporter.class).exportAsInputStream());
        }
        assertEquals("Precondition: the WAB is served after the deployment", "200 bundle=restart-wab", get("/restartwab/info"));
        stopServer();

        startServer("console-2.out");
        String state = bundleState("restart-wab.wab");
        // give the server up to 15 s to register the web context
        String response = get("/restartwab/info");
        for (long begin = System.currentTimeMillis(); response.startsWith("200") == false && System.currentTimeMillis() - begin < 15000; response = get("/restartwab/info"))
            Thread.sleep(500);
        assertEquals("Expected the deployed WAB (bundle " + state + ") to be served after a server restart as it was before, but GET /restartwab/info answered "
                + response, "200 bundle=restart-wab", response);
    }

    static JavaArchive wab(final String name, final String contextPath) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name + ".wab");
        archive.addClasses(InfoServlet.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(name);
                builder.addBundleManifestVersion(2);
                builder.addBundleVersion("1.0.0");
                builder.addManifestHeader("Web-ContextPath", contextPath);
                builder.addImportPackages(HttpServlet.class, WebServlet.class, ServletContext.class, FrameworkUtil.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    /** "status body" of a GET on the server's HTTP port */
    private String get(String path) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL("http://localhost:" + HTTP_PORT + path).openConnection();
        con.setConnectTimeout(5000);
        con.setReadTimeout(5000);
        int status = con.getResponseCode();
        InputStream in = status < 400 ? con.getInputStream() : con.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            in.transferTo(out);
            in.close();
        }
        String body = out.toString("UTF-8");
        return status + " " + (body.length() > 80 ? body.substring(0, 80) + "..." : body);
    }

    private String bundleState(String location) throws IOException {
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            ModelNode op = new ModelNode();
            op.get("operation").set("read-attribute");
            op.get("address").add("subsystem", "osgi").add("bundle", location);
            op.get("name").set("state");
            return client.execute(op).get("result").asString();
        }
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
