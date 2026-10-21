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
import java.nio.file.NoSuchFileException;
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
 * A WAB with a lazy activation policy that the framework starts at boot is STARTING, waiting for its activation, when its
 * deployment attaches the web context activator: its web application is not deployed, it answers 404, and the 404
 * prevents the class load that would activate it.
 *
 * <h3>Severity</h3>
 * minor. A lazy WAB is not served after a restart until it is redeployed.
 *
 * <h3>Component</h3>
 * jbosgi/wildfly/integration/web: {@code WebContextActivationProcessor}, {@code WebContextLifecycleInterceptor}.
 *
 * <h3>Found by</h3>
 * R8 fix review, residuals of ee/22 (umb-a-H04) and lifecycle/03 (umb-a-S03).
 *
 * <h3>Spec reference</h3>
 * OSGi Enterprise R8 128.3.2: a WAB is ready when it is ACTIVE, or STARTING with a lazy activation policy.
 *
 * <h3>Code location</h3>
 * wildfly/integration/web/src/main/java/org/jboss/as/osgi/web/WebContextActivationProcessor.java:65, :89 (f49fa4d9).
 *
 * <h3>Root cause</h3>
 * The processor only started the context of a bundle that was ACTIVE when the deployment attached the activator.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi/testsuite/integration
 * mvn test -Dtest=LazyWabServedAfterRestartIssueTestCase
 * </pre>
 * Self-hosted: copies the testsuite server, deploys a lazy WAB and restarts the server.
 *
 * <h3>Expected</h3>
 * After the restart the lazy WAB is served on its Web-ContextPath.
 *
 * <h3>Regression vs WF41</h3>
 * no.
 */
public class LazyWabServedAfterRestartIssueTestCase {

    static final int OFFSET = Integer.getInteger("issue.port.offset", 2900);
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
    public void testLazyWabServedAfterRestart() throws Exception {
        prepareServer("issues-lazywabrestart-server");
        startServer("console-1.out");
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            new ServerDeploymentHelper(client).deploy("lazy-restart-wab.wab", wab("lazy-restart-wab", "/lazyrestartwab").as(ZipExporter.class).exportAsInputStream());
        }
        assertEquals("Precondition: the WAB is served after the deployment", "200 bundle=lazy-restart-wab", get("/lazyrestartwab/info"));
        stopServer();

        startServer("console-2.out");
        String state = bundleState("lazy-restart-wab.wab");
        // give the server up to 15 s to register the web context
        String response = get("/lazyrestartwab/info");
        for (long begin = System.currentTimeMillis(); response.startsWith("200") == false && System.currentTimeMillis() - begin < 15000; response = get("/lazyrestartwab/info"))
            Thread.sleep(500);
        assertEquals("Expected the deployed WAB (bundle " + state + ") to be served after a server restart as it was before, but GET /lazyrestartwab/info answered "
                + response, "200 bundle=lazy-restart-wab", response);
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
                builder.addManifestHeader("Bundle-ActivationPolicy", "lazy");
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
