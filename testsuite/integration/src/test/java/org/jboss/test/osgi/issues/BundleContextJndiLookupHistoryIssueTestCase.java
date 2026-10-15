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
import java.util.Arrays;
import java.util.List;

import javax.naming.InitialContext;

import jakarta.annotation.Resource;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper;
import org.jboss.dmr.ModelNode;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.BundleContext;

/**
 * Whether a programmatic JNDI lookup of java:jboss/osgi/BundleContext (the system bundle context binding of the OSGi
 * subsystem) succeeds depends on the deployment history of the server: it fails with NameNotFoundException until some
 * deployment with a {@code @Resource BundleContext} injection point has been deployed since the boot, and from then on
 * it succeeds for every deployment, also after the injecting deployment has been undeployed, until the next restart.
 *
 * <h2>Severity</h2>
 * minor. A deployment (WAR, EJB, library code, a bundle) that looks the system context up with
 * {@code new InitialContext().lookup("java:jboss/osgi/BundleContext")} works in a test server where other applications
 * happened to inject the BundleContext first, and fails in production after a restart or when it is deployed first.
 * The documented injection forms ({@code @Resource BundleContext}, {@code @Resource(lookup = "java:jboss/osgi/BundleContext")})
 * are not affected because their injection point demands the binder service.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/integration/naming: {@code org.jboss.as.osgi.naming.BundleContextBindingService}
 * (binder service of java:jboss/osgi/BundleContext) and {@code BundleContextBindingProcessor} (adds the only demand on it).
 *
 * <h2>Found by</h2>
 * wildfly-ee workstream round 1 (open question "programmatic lookup depends on another deployment having activated the
 * ON_DEMAND binder"), verified by wildfly-ee-2 (deep 2): on a fresh server a WAR without injection point gets
 * NameNotFoundException; after deploying and undeploying a WAR with {@code @Resource BundleContext} the same request
 * returns the system context; after a server restart it fails again.
 *
 * <h2>Spec reference</h2>
 * No OSGi clause (the binding is a jbosgi/WildFly integration feature). jbosgi README.md: "The injection of the system
 * bundle context with @Resource BundleContext context without a lookup name is part of the WildFly fork as well. With
 * {@code @Resource(lookup = "java:jboss/osgi/BundleContext")} it does not depend on that" - the name is published as the
 * global binding of the system context, and the subsystem logs "Bound system context to java:jboss/osgi/BundleContext".
 * Jakarta EE / WildFly naming: a name in the global java:jboss namespace is either bound or not; its visibility to one
 * application must not depend on which other applications were deployed before.
 *
 * <h2>Code location</h2>
 * wildfly/integration/naming/src/main/java/org/jboss/as/osgi/naming/BundleContextBindingService.java:51-57 and 76
 * ({@code builder.setInitialMode(Mode.ON_DEMAND)}, and in {@code start()} {@code controller.setMode(Mode.ACTIVE)}: once
 * demanded the binder promotes itself to ACTIVE and stays bound for the rest of the server life);
 * BundleContextBindingProcessor.java:124-133 (only a deployment with a BundleContext injection point adds a dependency
 * on the binder). Unchanged since the AS7 import except for logging (commit f025b420 changed only the DOWN log message).
 *
 * <h2>Root cause</h2>
 * The binder is ON_DEMAND, so it is bound only while or after something depends on it; the self-promotion to ACTIVE
 * makes the first demand permanent. A plain JNDI lookup does not create an MSC dependency, so its outcome depends on
 * the history of unrelated deployments since the boot.
 *
 * <h2>Reproduction</h2>
 * Scripted test (does not use Arquillian): it copies the testsuite server (target/wildfly-41.0.1.Final, without
 * deployments, data, log and tmp) to target/issues-bclookup-server and starts it with standalone-osgi.xml at port offset
 * 1100 (management 11090, http 9180); a fresh server is needed because the result depends on earlier deployments.
 * Run the class on its own (never in the same surefire run as Arquillian tests using offset 1100).
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=BundleContextJndiLookupHistoryIssueTestCase
 * </pre>
 * (Prerequisites: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork in the local Maven
 * repository; on JDK 24+ the testsuite needs surefire.security.args without -Djava.security.manager, see
 * TestJvmSecurityManagerIssueTestCase.) Deterministic.
 * The port offset can be overridden with the system property issue.port.offset (default 1100; management 9990+n, HTTP 8080+n),
 * e.g. append {@code -Dissue.port.offset=300} to the mvn command to run the test beside a server that uses
 * offset 1100.
 *
 * <h2>Observed</h2>
 * <pre>
 * java.lang.AssertionError: Expected the lookup of java:jboss/osgi/BundleContext from a WAR to give the same result
 *   regardless of other deployments, but on the fresh server it gave [200 lookup failed:
 *   javax.naming.NameNotFoundException: osgi/BundleContext [Root exception is java.lang.IllegalStateException]] and
 *   after deploying and undeploying a WAR with @Resource BundleContext it gave [200 found BundleContext[system.bundle:0.0.0]]
 * </pre>
 *
 * <h2>Expected</h2>
 * The lookup result does not depend on other deployments: with the framework active (activation="eager" in
 * standalone-osgi.xml) java:jboss/osgi/BundleContext resolves for every deployment, before and after other
 * deployments injected it.
 *
 * <h2>Reference behaviour</h2>
 * Other global WildFly bindings (java:jboss/datasources/..., java:jboss/ee/concurrency/...) are bound with their
 * subsystem resource, independently of injection points.
 *
 * <h2>Suggested fix (not applied)</h2>
 * Install the binder with Mode.PASSIVE and drop the setMode(ACTIVE) in start(): it is then bound whenever the
 * framework is active, without forcing a lazy framework to activate, and unbound with it. Alternatively document that
 * only the injection forms are supported and keep the binder ON_DEMAND without the self-promotion (consistent failure).
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing).
 */
public class BundleContextJndiLookupHistoryIssueTestCase {

    static final int OFFSET = Integer.getInteger("issue.port.offset", 1100);
    static final int MGMT_PORT = 9990 + OFFSET;
    static final int HTTP_PORT = 8080 + OFFSET;

    /** Looks the system context up programmatically, no injection point */
    @WebServlet("/lookup")
    public static class LookupServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
            String result;
            try {
                result = "found " + new InitialContext().lookup("java:jboss/osgi/BundleContext");
            } catch (Exception ex) {
                result = "lookup failed: " + ex;
            }
            res.getWriter().print(result);
        }
    }

    /** The documented injection point */
    @WebServlet("/injected")
    public static class InjectingServlet extends HttpServlet {
        @Resource
        BundleContext context;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
            res.getWriter().print("injected " + context);
        }
    }

    private File home;
    private Process process;

    @After
    public void tearDown() throws Exception {
        stopServer();
    }

    @Test
    public void testLookupIndependentOfOtherDeployments() throws Exception {
        prepareServer("issues-bclookup-server");
        startServer("console-1.out");
        deploy("bclookup.war", ShrinkWrap.create(WebArchive.class, "bclookup.war").addClasses(LookupServlet.class));
        String fresh = get("/bclookup/lookup");

        deploy("bcinjected.war", ShrinkWrap.create(WebArchive.class, "bcinjected.war").addClasses(InjectingServlet.class));
        String injected = get("/bcinjected/injected");
        assertTrue("Precondition: @Resource BundleContext is injected, but got " + injected, injected.startsWith("200 injected BundleContext"));
        undeploy("bcinjected.war");
        String after = get("/bclookup/lookup");

        assertTrue("Expected the lookup of java:jboss/osgi/BundleContext from a WAR to give the same result regardless of other deployments, but on the fresh server it gave ["
                + fresh + "] and after deploying and undeploying a WAR with @Resource BundleContext it gave [" + after + "]", fresh.equals(after) && fresh.startsWith("200 found"));
    }

    private void deploy(String name, Archive<?> archive) throws Exception {
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            new ServerDeploymentHelper(client).deploy(name, archive.as(ZipExporter.class).exportAsInputStream());
        }
    }

    private void undeploy(String name) throws Exception {
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            new ServerDeploymentHelper(client).undeploy(name);
        }
    }

    /** "status body" of a GET on the server's HTTP port */
    private String get(String path) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL("http://localhost:" + HTTP_PORT + path).openConnection();
        con.setConnectTimeout(5000);
        con.setReadTimeout(10000);
        int status = con.getResponseCode();
        InputStream in = status < 400 ? con.getInputStream() : con.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            in.transferTo(out);
            in.close();
        }
        return status + " " + out.toString("UTF-8");
    }

    private ModelNode execute(ModelNode op) throws IOException {
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            return client.execute(op);
        }
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
