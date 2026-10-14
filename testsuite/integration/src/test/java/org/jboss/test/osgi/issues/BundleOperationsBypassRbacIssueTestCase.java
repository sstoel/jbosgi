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
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.sasl.RealmCallback;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.ModelControllerClientConfiguration;
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
 * The OSGi bundle operations {@code /subsystem=osgi/bundle=*:start} and {@code :stop} bypass role based access
 * control and the management audit log: with RBAC enabled a user with the read-only Monitor role (and the Deployer
 * role) can stop and start any bundle, although the access-control metadata of the resource says
 * {@code "stop" => {"execute" => false}}; and no audit log entry is written for these operations, whoever runs them.
 *
 * <h3>Severity</h3>
 * critical (security: privilege escalation of read-only management users - a Monitor can stop the HTTP Service bundle,
 * any application bundle or start one an administrator stopped - and no audit trail of who did it).
 *
 * <h3>Component</h3>
 * jbosgi umbrella, wildfly/subsystem: {@code org.jboss.as.osgi.management.BundleResourceHandler} (handler of the
 * start/stop operations of the runtime-only bundle resources) and {@code org.jboss.as.osgi.parser.BundleResource}
 * (operation definitions without flags).
 *
 * <h3>Found by</h3>
 * wildfly-lifecycle workstream, RBAC/audit matrix on the patched WildFly 41.0.1 + jbosgi R8 server (JDK 25): users with
 * the Monitor, Operator, Deployer and Maintainer roles running bundle start/stop, framework startlevel write,
 * :activate, capability and property add/remove through the CLI with --no-local-auth, audit logger enabled.
 *
 * <h3>Spec reference</h3>
 * WildFly management RBAC (WildFly Admin Guide, "Role Based Access Control"): the Monitor role "has the fewest
 * permissions and restricts the user to viewing configuration and state of the server"; Deployer may only modify
 * deployments. The resource's own access-control metadata
 * ({@code read-resource-description(access-control=trim-descriptions,operations=true)} as Monitor) lists
 * {@code "start" => {"execute" => false}, "stop" => {"execute" => false}}. Management audit logging (Admin Guide, "Audit
 * logging"): operations that change the configuration or runtime state of the server are logged.
 *
 * <h3>Code location</h3>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/management/BundleResourceHandler.java:95-107: {@code handleOperation}
 * calls {@code bundle.start()} / {@code bundle.stop()} directly and only ever uses
 * {@code context.getServiceRegistry(false)} (lines 153, 158). WildFly authorizes an operation step lazily, when the
 * handler touches the model or asks for a modifiable service registry, and treats an operation that did neither as
 * read-only (so it is neither authorized as a write nor written to the audit log with log-read-only=false). The
 * operation definitions (parser/BundleResource.java:43-44) carry no flags either. Unchanged on WF41-osgi-r8 (only
 * ActivateOperationHandler and StartLevelHandler changed in the management package).
 *
 * <h3>Root cause</h3>
 * The runtime-modifying handler never declares its runtime modification to the OperationContext.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=BundleOperationsBypassRbacIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local
 * Maven repository. Not an Arquillian test: it copies the testsuite server to target/issues-rbac-server and
 * target/issues-audit-server, adds a management user, enables RBAC / the audit logger and starts/stops the copies with
 * port offset 1200 (management 11190). Manually: {@code add-user.sh -u mon -p ...}, map it to Monitor, set
 * {@code /core-service=management/access=authorization:write-attribute(name=provider,value=rbac)}, reload, then
 * {@code jboss-cli.sh --no-local-auth --user=mon ... --command=/subsystem=osgi/bundle=X:stop}.)
 * The port offset can be overridden with the system property issue.port.offset (default 1200; management 9990+n),
 * e.g. append {@code -Dissue.port.offset=300} to the mvn command to run the test beside a server that uses
 * offset 1200.
 *
 * <h3>Observed</h3>
 * <pre>
 * testMonitorCannotStopBundle: Expected the read-only Monitor role to be denied
 *   /subsystem=osgi/bundle=rbac-bundle.jar:stop (WFLYCTL0313/WFLYCTL0332, its access-control metadata says
 *   execute=false), but the operation answered success and the bundle is now RESOLVED expected:&lt;[ACTIVE]&gt; but was:&lt;[RESOLVED]&gt;
 * testBundleStopIsAudited: Expected the management audit log to record the successful
 *   /subsystem=osgi/bundle=rbac-bundle.jar:stop and :start operations (they change the runtime state), but stop
 *   logged=false, start logged=false
 * Manual matrix (CLI, RBAC on): bundle :stop/:start succeed for Monitor, Operator, Deployer and Maintainer; audit log
 * has entries for :activate and capability/property add/remove (allowed and denied) but none for the 10 bundle
 * stop/start operations. Related inconsistencies seen in the same matrix (not covered by this test): Operator's
 * metadata says startlevel write=true but write-attribute(name=startlevel) is denied (applied in the MODEL stage,
 * services/28), and :activate is executable by Operator although its metadata says execute=false (no RUNTIME_ONLY flag).
 * </pre>
 *
 * <h3>Expected</h3>
 * Monitor and Deployer get WFLYCTL0313 "Unauthorized to execute operation 'stop'" and the bundle stays ACTIVE; Operator
 * and above may start/stop bundles; every successful start/stop is in the audit log.
 *
 * <h3>Reference behaviour</h3>
 * The other osgi operations in the same server are authorized and audited (:activate, capability/property add).
 *
 * <h3>Suggested fix</h3>
 * Call {@code context.getServiceRegistry(true)} (or {@code context.authorize(operation,
 * EnumSet.of(ActionEffect.WRITE_RUNTIME))} plus a runtime-affecting call) before acting in
 * {@code BundleResourceHandler.handleOperation}, and flag the start/stop (and activate) operation definitions
 * {@code OperationEntry.Flag.RUNTIME_ONLY} so the metadata matches (Operator may run them). Trial fix verified: the
 * single line {@code context.getServiceRegistry(true);} at the start of handleOperation makes both test methods pass
 * (Monitor denied, stop/start audited).
 *
 * <h3>Regression vs WF41</h3>
 * no (pre-existing; BundleResourceHandler and BundleResource are unchanged on WF41-osgi-r8).
 */
public class BundleOperationsBypassRbacIssueTestCase {

    static final int OFFSET = Integer.getInteger("issue.port.offset", 1200);
    static final int MGMT_PORT = 9990 + OFFSET;
    static final String USER = "osgi-monitor";
    static final String PASSWORD = "Monitor-pw-1";

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
    public void testMonitorCannotStopBundle() throws Exception {
        prepareServer("issues-rbac-server");
        // a management user that will get the read-only Monitor role
        String hash = new BigInteger(1, MessageDigest.getInstance("MD5").digest((USER + ":ManagementRealm:" + PASSWORD).getBytes(StandardCharsets.UTF_8))).toString(16);
        while (hash.length() < 32)
            hash = "0" + hash;
        Files.write(new File(home, "standalone/configuration/mgmt-users.properties").toPath(), ("\n" + USER + "=" + hash + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);

        startServer("console-1.out");
        try (ModelControllerClient admin = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            new ServerDeploymentHelper(admin).deploy("rbac-bundle.jar", bundle());
            execute(admin, "add", "core-service=management/access=authorization/role-mapping=Monitor");
            execute(admin, "add", "core-service=management/access=authorization/role-mapping=Monitor/include=user-" + USER, "name", USER, "type", "USER");
            execute(admin, "write-attribute", "core-service=management/access=authorization", "name", "provider", "value", "rbac");
            execute(admin, "write-attribute", "core-service=management/access=audit/logger=audit-log", "name", "enabled", "value", "true");
        }
        stopServer();
        startServer("console-2.out");

        try (ModelControllerClient monitor = monitorClient(); ModelControllerClient admin = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            ModelNode whoami = new ModelNode();
            whoami.get("operation").set("whoami");
            whoami.get("verbose").set(true);
            assertEquals("Precondition: the client is authenticated as " + USER + " with the Monitor role", "[\"Monitor\"]",
                    monitor.execute(whoami).get("result").get("mapped-roles").asString().replaceAll("\\s", ""));
            assertEquals("Precondition: bundle active", "ACTIVE", bundleState(admin));

            ModelNode stop = new ModelNode();
            stop.get("operation").set("stop");
            stop.get("address").add("subsystem", "osgi").add("bundle", "rbac-bundle.jar");
            ModelNode result = monitor.execute(stop);
            String state = bundleState(admin);
            assertEquals("Expected the read-only Monitor role to be denied /subsystem=osgi/bundle=rbac-bundle.jar:stop (WFLYCTL0313/WFLYCTL0332, its "
                    + "access-control metadata says execute=false), but the operation answered " + result.get("outcome").asString()
                    + " and the bundle is now " + state, "ACTIVE", state);
        }
    }

    @Test
    public void testBundleStopIsAudited() throws Exception {
        prepareServer("issues-audit-server");
        startServer("console-1.out");
        try (ModelControllerClient admin = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            new ServerDeploymentHelper(admin).deploy("rbac-bundle.jar", bundle());
            execute(admin, "write-attribute", "core-service=management/access=audit/logger=audit-log", "name", "enabled", "value", "true");
            execute(admin, "add", "system-property=lifecycle.audit.marker", "value", "x");
            execute(admin, "stop", "subsystem=osgi/bundle=rbac-bundle.jar");
            assertEquals("Precondition: bundle stopped", "RESOLVED", bundleState(admin));
            execute(admin, "start", "subsystem=osgi/bundle=rbac-bundle.jar");
        }
        File log = new File(home, "standalone/data/audit-log.log");
        assertTrue("Precondition: audit log written " + log, log.isFile());
        String text = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
        assertTrue("Precondition: the audit log records management operations (system-property add)", text.contains("lifecycle.audit.marker"));
        String compact = text.replaceAll("\\s", "");
        boolean stopLogged = compact.contains("\"operation\":\"stop\",\"address\":[{\"subsystem\":\"osgi\"},{\"bundle\":\"rbac-bundle.jar\"}]");
        boolean startLogged = compact.contains("\"operation\":\"start\",\"address\":[{\"subsystem\":\"osgi\"},{\"bundle\":\"rbac-bundle.jar\"}]");
        assertTrue("Expected the management audit log to record the successful /subsystem=osgi/bundle=rbac-bundle.jar:stop and :start operations "
                + "(they change the runtime state), but stop logged=" + stopLogged + ", start logged=" + startLogged, stopLogged && startLogged);
    }

    private ModelControllerClient monitorClient() {
        ModelControllerClientConfiguration config = new ModelControllerClientConfiguration.Builder().setProtocol("remote+http").setHostName("localhost")
                .setPort(MGMT_PORT).setSaslOptions(Collections.singletonMap("SASL_DISALLOWED_MECHANISMS", "JBOSS-LOCAL-USER")).setHandler(callbacks -> {
                    for (Callback callback : callbacks) {
                        if (callback instanceof NameCallback)
                            ((NameCallback) callback).setName(USER);
                        else if (callback instanceof PasswordCallback)
                            ((PasswordCallback) callback).setPassword(PASSWORD.toCharArray());
                        else if (callback instanceof RealmCallback)
                            ((RealmCallback) callback).setText(((RealmCallback) callback).getDefaultText());
                    }
                }).build();
        return ModelControllerClient.Factory.create(config);
    }

    private String bundleState(ModelControllerClient client) throws IOException {
        ModelNode op = new ModelNode();
        op.get("operation").set("read-attribute");
        op.get("address").add("subsystem", "osgi").add("bundle", "rbac-bundle.jar");
        op.get("name").set("state");
        return client.execute(op).get("result").asString();
    }

    /** Executes an operation on the address a=b/c=d with parameters name, value, ...; asserts success */
    private void execute(ModelControllerClient client, String operation, String address, String... params) throws IOException {
        ModelNode op = new ModelNode();
        op.get("operation").set(operation);
        for (String element : address.split("/")) {
            String[] pair = element.split("=");
            op.get("address").add(pair[0], pair[1]);
        }
        for (int i = 0; i < params.length; i += 2)
            op.get(params[i]).set(params[i + 1]);
        ModelNode result = client.execute(op);
        assertEquals("Precondition: " + op + " -> " + result, "success", result.get("outcome").asString());
    }

    private InputStream bundle() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "rbac-bundle.jar");
        archive.addClasses(NoopActivator.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName("rbac-bundle");
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
