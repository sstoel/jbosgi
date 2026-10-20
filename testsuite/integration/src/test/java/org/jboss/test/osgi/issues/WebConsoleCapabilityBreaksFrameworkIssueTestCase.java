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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * Enabling the Felix Web Console capability that the shipped standalone-osgi.xml offers in a comment takes the whole
 * OSGi framework down: the shipped org.apache.felix.webconsole 4.2.14-all imports javax.servlet, which nothing exports
 * in this Jakarta EE 11 server, so jbosgi.BootstrapBundles.RESOLVE fails, the framework never leaves STARTING, no
 * capability bundle is started and every OSGi deployment (bundle or WAB) fails.
 *
 * <h3>Severity</h3>
 * major (enabling a capability that the shipped configuration documents takes the whole OSGi subsystem down: the server
 * reports "started (with errors)", the system bundle stays STARTING, all 25 capability bundles stay INSTALLED/RESOLVED,
 * every new bundle/WAB deployment is rolled back with "failure message: undefined" and every bundle deployed before the
 * change is FAILED after the restart. Not critical: the line is commented out by default and plain Jakarta EE
 * deployments keep working. No security exposure: the console never becomes reachable).
 *
 * <h3>Component</h3>
 * jbosgi umbrella, wildfly/build: the shipped configuration
 * {@code wildfly/build/src/main/resources/wildfly/standalone/configuration/standalone-osgi.xml} and the shipped bundle
 * {@code bundles/org/apache/felix/webconsole/main/org.apache.felix.webconsole-4.2.14-all.jar} (copied by
 * {@code wildfly/build/scripts/antrun-wildfly-patch.xml}); the strict bootstrap resolve is
 * {@code org.jboss.osgi.framework.spi.BootstrapBundlesResolve} (jbosgi-framework core) driven by
 * {@code org.jboss.as.osgi.service.BootstrapBundlesIntegration} (wildfly/subsystem).
 *
 * <h3>Found by</h3>
 * security workstream (HTTP surface review: is the shipped Felix Web Console reachable, with which credentials?), filed
 * by a follow-up workstream that reproduced it on a copy of the patched WildFly 41.0.1 + jbosgi R8 server on JDK 25 and on
 * the WF41 build (umbrella origin/WF41, jbosgi-framework-core 6.6.0.Final) for comparison.
 *
 * <h3>Spec reference / documentation</h3>
 * The shipped configuration itself documents the capability as working (standalone-osgi.xml:152-156 on WF41-osgi-r8):
 * <pre>
 *   &lt;!--
 *       While Felix Webconsole technically works, it's insecure!
 *       Use at OWN RISK!
 *       &lt;capability name="org.apache.felix.webconsole" startlevel="1"/&gt;
 *    --&gt;
 * </pre>
 * The bundle's manifest: {@code Import-Package: javax.servlet;version="2.4",javax.servlet.http;version="2.4",...,
 * org.osgi.service.http;version="[1.2,2)",...}. WildFly 41 provides only jakarta.servlet (capability
 * jakarta.servlet.api); the org.jboss.as.osgi.http bundle exports org.osgi.service.http 1.2.1 rebuilt on
 * jakarta.servlet ({@code uses:="jakarta.servlet.http,jakarta.servlet"}). Not an OSGi spec violation: a shipped
 * configuration/packaging defect.
 *
 * <h3>Code location</h3>
 * <ul>
 * <li>wildfly/build/src/main/resources/wildfly/standalone/configuration/standalone-osgi.xml:152-156 (the comment; added in
 * d6dddcf0 "JBOSGI-781 Add support for WildFly 9.0.x", when javax.servlet still existed; never updated by the
 * Jakarta switch 16261b69 "Fixes + jakarta change").</li>
 * <li>pom.xml:50 {@code <version.apache.felix.webconsole>4.2.14</version.apache.felix.webconsole>} (classifier all),
 * wildfly/build/pom.xml:57, wildfly/build/scripts/antrun-wildfly-patch.xml:136-137 (copied into the server).</li>
 * <li>jbosgi-framework core/src/main/java/org/jboss/osgi/framework/spi/BootstrapBundlesResolve.java:88-111: every
 * auto-start bootstrap bundle whose start level is &lt;= org.osgi.framework.startlevel.beginning (1 in the shipped
 * config) is a MANDATORY resource of one resolveAndApply call; a ResolutionException becomes a StartException of
 * jbosgi.BootstrapBundles.RESOLVE, so BootstrapBundles.COMPLETE, PersistentBundles.INSTALL and
 * jbosgi.internal.framework.INIT never come up.</li>
 * </ul>
 *
 * <h3>Root cause</h3>
 * The distribution ships, and its configuration recommends, a javax.servlet web console that cannot resolve on a
 * Jakarta server; together with the all-or-nothing bootstrap resolve, one unresolvable optional console takes down the
 * framework. (The all-or-nothing resolve itself is the same mechanism as tests/07 and services/22, but the defect
 * here is the shipped, documented, unusable bundle; removing or replacing it fixes this issue independently.)
 *
 * <h3>Startlevel 1 vs 2, boot vs runtime add (exact behaviour, JDK 25, f025b420)</h3>
 * <ul>
 * <li>(a) config edited as the comment says (startlevel="1"), then boot: MSC000001 Failed to start service
 * jbosgi.BootstrapBundles.RESOLVE ... missing requirement osgi.wiring.package=javax.servlet (version&gt;=2.4.0);
 * WFLYSRV0026 started (with errors); server-state "running"; /subsystem=osgi/bundle=0 state STARTING; webconsole
 * INSTALLED; 11 bundles INSTALLED, 14 RESOLVED, none ACTIVE; deploying a bundle jar or a WAB: WFLYSRV0021 rolled back
 * with "failure message: undefined"; a plain (non-OSGi) WAR deploys and is served (HTTP 200).</li>
 * <li>(b) {@code /subsystem=osgi/capability=org.apache.felix.webconsole:add(startlevel=1)} on a running server:
 * outcome success, nothing changes at runtime (no reload-required either, services/21); after :shutdown and start the
 * persisted config has the capability and the boot is exactly (a); in addition the bundle and the WAB deployed before
 * the change log WFLYSRV0010 "Deployed" but their /deployment status is FAILED and the WAB answers 404.</li>
 * <li>startlevel=2 (boot or runtime add + restart, as the wildfly-lifecycle workstream did): the bundle is not in the
 * mandatory set (2 &gt; beginning start level 1), so the boot is clean (WFLYSRV0025), deployments stay OK, and the web
 * console is INSTALLED (never resolved, never reachable: /system/console 404). Raising the framework to start level 2
 * logs only "Starting bundles for start level: 2" and leaves it INSTALLED silently (the bootstrap autostart flag of a
 * level-2 capability is not persisted, see fuzz/12); an explicit /subsystem=osgi/bundle=org.apache.felix.webconsole:start
 * fails with JBOSGI011250 Cannot resolve bundle (missing javax.servlet). So startlevel 2 hides the defect, it does not
 * make the console work.</li>
 * </ul>
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=WebConsoleCapabilityBreaksFrameworkIssueTestCase
 * </pre>
 * (Prerequisites: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local
 * Maven repository; on JDK 24+ the surefire.security.args edit of testsuite/pom.xml, issue wildfly/03. Not an
 * Arquillian test: it copies the testsuite server to target/issues-webconsole-config and target/issues-webconsole-add,
 * changes the configuration and starts/stops the copies with port offset 1200 (management 11190). The port offset can
 * be overridden with -Dissue.port.offset=n (management 9990+n).) Manually: uncomment the capability line in
 * standalone/configuration/standalone-osgi.xml and run {@code bin/standalone.sh -c standalone-osgi.xml}.
 *
 * <h3>Observed</h3>
 * <pre>
 * testDocumentedWebConsoleCapability: Expected the OSGi framework to start with the web console capability enabled as
 *   the shipped standalone-osgi.xml comment suggests, but: system bundle STARTING, webconsole INSTALLED, deployment of a
 *   bundle afterwards: /deployment=wc-app.jar absent (rolled back); boot log: MSC000001: Failed to start service
 *   jbosgi.BootstrapBundles.RESOLVE: ... ReasonException: Unable to resolve HostBundleRevision[org.apache.felix.webconsole:4.2.14.all]:
 *   missing requirement XPackageRequirement[dirs={filter=(&amp;(osgi.wiring.package=javax.servlet)(version&gt;=2.4.0))} ...
 *   expected:&lt;[ACTIVE/ACTIVE/OK]&gt; but was:&lt;[STARTING/INSTALLED/absent (rolled back)]&gt;
 *   (server log: WFLYSRV0021 Deploy of deployment "wc-app.jar" was rolled back with the following failure message: undefined;
 *   ServerDeploymentHelper.deploy does not even throw)
 * testWebConsoleCapabilityAddedAtRuntime: Expected /subsystem=osgi/capability=org.apache.felix.webconsole:add(startlevel=1)
 *   either to be refused or to leave a working OSGi subsystem after the restart, but the add succeeded and after the
 *   restart: system bundle STARTING, webconsole INSTALLED, /deployment=wc-app.jar status FAILED; boot log: MSC000001 ...
 *   expected:&lt;[ACTIVE/OK]&gt; but was:&lt;[STARTING/FAILED]&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * Either the documented capability works (framework ACTIVE, console bundle ACTIVE, deployments unaffected), or the
 * distribution no longer offers it (no comment, no jar, and capability:add of it refused).
 *
 * <h3>Reference behaviour</h3>
 * The same server without the capability boots cleanly (WFLYSRV0025, framework ACTIVE). Felix Web Console 5.x
 * (latest 5.0.18 on Maven Central) imports jakarta.servlet;version="[5,7)" but also requires
 * osgi.implementation=osgi.http 2.0 and org.osgi.service.servlet.context [2.0,3) (OSGi Servlet Whiteboard, Compendium
 * R8.1), which this server does not provide (only a jakarta-flavoured HttpService 1.2.1), so it is not a drop-in
 * replacement either.
 *
 * <h3>Suggested fix</h3>
 * Remove the capability comment and stop shipping org.apache.felix.webconsole-4.2.14-all (pom.xml dependency
 * management, wildfly/build/pom.xml, antrun-wildfly-patch.xml, distribution/installer, testsuite/example assembly), since
 * no 4.x console can work on jakarta.servlet and HttpService 1.2.1-on-Jakarta. If a console is wanted, ship Felix Web
 * Console 5.x together with a Jakarta Servlet Whiteboard 2.0 implementation (e.g. on Undertow), protected by Elytron
 * and with non-default credentials (4.x defaults to admin/admin). Independently, a capability whose bootstrap resolve
 * fails should be reported and skipped instead of failing jbosgi.BootstrapBundles.RESOLVE for the whole framework
 * (same mechanism as tests/07, services/22).
 *
 * <h3>Regression vs WF41</h3>
 * no. WF41 ships the same comment (standalone-osgi.xml:148-152 on WF41) and the same 4.2.14-all jar; on a WF41 build
 * (jbosgi-framework-core 6.6.0.Final, WildFly 41.0.1, JDK 25) adding the capability with startlevel="1" fails the same
 * way: MSC000001 jbosgi.BootstrapBundles.RESOLVE ... ResolutionException ... missing requirement
 * osgi.wiring.package=javax.servlet, system bundle STARTING, webconsole INSTALLED. Broken since the Jakarta switch.
 */
public class WebConsoleCapabilityBreaksFrameworkIssueTestCase {

    static final int OFFSET = Integer.getInteger("issue.port.offset", 1200);
    static final int MGMT_PORT = 9990 + OFFSET;
    static final String CAPABILITY_LINE = "<capability name=\"org.apache.felix.webconsole\" startlevel=\"1\"/>";
    static final String WEBCONSOLE = "org.apache.felix.webconsole";

    public static class NoopActivator implements BundleActivator {
        @Override
        public void start(BundleContext context) {
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
    public void testDocumentedWebConsoleCapability() throws Exception {
        prepareServer("issues-webconsole-config");
        File config = new File(home, "standalone/configuration/standalone-osgi.xml");
        String xml = new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8);
        // the comment block that offers the capability
        Matcher matcher = Pattern.compile("<!--((?:(?!-->).)*?)" + Pattern.quote(CAPABILITY_LINE) + "(\\s*)-->", Pattern.DOTALL).matcher(xml);
        if (matcher.find() == false) {
            // fixed: the shipped configuration no longer offers the capability
            return;
        }
        assertTrue("Precondition: the comment says the console works", matcher.group(1).contains("Felix Webconsole technically works"));
        // uncomment exactly the line the comment offers, keep the warning as a comment
        String edited = xml.substring(0, matcher.start()) + "<!--" + matcher.group(1) + "-->\n                " + CAPABILITY_LINE + xml.substring(matcher.end());
        Files.write(config.toPath(), edited.getBytes(StandardCharsets.UTF_8));

        File out = startServer("console-1.out");
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            String deployFailure = "";
            try {
                new ServerDeploymentHelper(client).deploy("wc-app.jar", bundle());
            } catch (Exception ex) {
                deployFailure = " (" + trim(String.valueOf(ex)) + ")";
            }
            // the helper does not report a rollback whose failure description is undefined: read the deployment back
            String deployment = deploymentStatus(client, "wc-app.jar");
            String framework = bundleState(client, "0");
            String console = bundleState(client, WEBCONSOLE);
            assertEquals("Expected the OSGi framework to start with the web console capability enabled as the shipped standalone-osgi.xml comment "
                    + "suggests, but: system bundle " + framework + ", webconsole " + console + ", deployment of a bundle afterwards: /deployment=wc-app.jar "
                    + deployment + deployFailure + "; boot log: " + bootError(out), "ACTIVE/ACTIVE/OK", framework + "/" + console + "/" + deployment);
        }
    }

    @Test
    public void testWebConsoleCapabilityAddedAtRuntime() throws Exception {
        prepareServer("issues-webconsole-add");
        startServer("console-1.out");
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            new ServerDeploymentHelper(client).deploy("wc-app.jar", bundle());
            assertEquals("Precondition: deployed bundle active", "ACTIVE", bundleState(client, "wc-app.jar"));
            ModelNode add = new ModelNode();
            add.get("operation").set("add");
            add.get("address").add("subsystem", "osgi").add("capability", WEBCONSOLE);
            add.get("startlevel").set(1);
            ModelNode result = client.execute(add);
            if ("success".equals(result.get("outcome").asString()) == false)
                return; // fixed: the server refuses a capability it cannot run
        }
        stopServer();
        File out = startServer("console-2.out");
        try (ModelControllerClient client = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            String framework = bundleState(client, "0");
            String console = bundleState(client, WEBCONSOLE);
            String deployment = deploymentStatus(client, "wc-app.jar");
            assertEquals("Expected /subsystem=osgi/capability=" + WEBCONSOLE + ":add(startlevel=1) either to be refused or to leave a working OSGi "
                    + "subsystem after the restart, but the add succeeded and after the restart: system bundle " + framework + ", webconsole " + console
                    + ", /deployment=wc-app.jar status " + deployment + "; boot log: " + bootError(out), "ACTIVE/OK", framework + "/" + deployment);
        }
    }

    private static String trim(String text) {
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() > 400 ? flat.substring(0, 400) + " ..." : flat;
    }

    /** The first MSC000001 line of the boot log, or a note that there is none */
    private static String bootError(File out) throws IOException {
        for (String line : Files.readAllLines(out.toPath(), StandardCharsets.UTF_8)) {
            if (line.contains("MSC000001") && line.contains("jbosgi"))
                return trim(line.substring(line.indexOf("MSC000001")));
        }
        return "(no MSC000001 for a jbosgi service)";
    }

    private String deploymentStatus(ModelControllerClient client, String name) throws IOException {
        ModelNode op = new ModelNode();
        op.get("operation").set("read-attribute");
        op.get("address").add("deployment", name);
        op.get("name").set("status");
        ModelNode result = client.execute(op);
        return "success".equals(result.get("outcome").asString()) ? result.get("result").asString() : "absent (rolled back)";
    }

    private String bundleState(ModelControllerClient client, String bundle) throws IOException {
        ModelNode op = new ModelNode();
        op.get("operation").set("read-attribute");
        op.get("address").add("subsystem", "osgi").add("bundle", bundle);
        op.get("name").set("state");
        ModelNode result = client.execute(op);
        return "success".equals(result.get("outcome").asString()) ? result.get("result").asString() : "(" + result.get("failure-description").asString() + ")";
    }

    private InputStream bundle() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "wc-app.jar");
        archive.addClasses(NoopActivator.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName("wc-app");
                builder.addBundleActivator(NoopActivator.class);
                builder.addImportPackages(BundleActivator.class);
                return builder.openStream();
            }
        });
        return archive.as(ZipExporter.class).exportAsInputStream();
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
