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
import org.junit.After;
import org.junit.Test;

/**
 * Changing the OSGi framework start level through the management model
 * ({@code /subsystem=osgi:write-attribute(name=startlevel,value=N)}) is not recorded in the management audit log:
 * neither the successful change by an authorized user nor a denied attempt by an unauthorized one.
 *
 * <h3>Severity</h3>
 * major (security: audit trail gap). The write starts or stops every bundle whose start level lies between the old and
 * the new framework start level (all applications deployed with a start level above the new value go down), i.e. it is
 * a runtime-state change of the same weight as bundle start/stop, but the audit log - the record an operator relies on
 * to find out who changed the server - stays silent. Attacker model: an authorized but malicious or compromised
 * management user (Maintainer, Administrator, SuperUser; without RBAC every management user) who wants to take
 * applications down without leaving a trace. RBAC itself is enforced for this write (Monitor, Operator and Deployer get
 * WFLYCTL0313), so this is not an authorization bypass; that part is covered for bundle start/stop by lifecycle/05.
 *
 * <h3>Component</h3>
 * jbosgi umbrella, wildfly/subsystem: {@code org.jboss.as.osgi.management.StartLevelHandler} (WRITE_HANDLER, the write
 * handler of the runtime attribute {@code startlevel} registered in {@code org.jboss.as.osgi.parser.OSGiRootResource}).
 *
 * <h3>Found by</h3>
 * security workstream, systematic check of the osgi subsystem handlers for the lifecycle/05 pattern (a runtime change
 * that is never declared to the OperationContext), on the patched WildFly 41.0.1 + jbosgi R8 server (JDK 25) with RBAC
 * on (Monitor/Operator/Deployer/Maintainer/Administrator users) and the management audit logger enabled: every osgi
 * operation of the role matrix has an audit record (allowed and denied: capability/property add/remove, property and
 * activation write, :activate) except bundle :start/:stop (lifecycle/05) and write-attribute(name=startlevel), whose
 * records are missing for all five roles, also for a real change 1 -&gt; 3 -&gt; 1 by the Maintainer.
 *
 * <h3>Spec reference</h3>
 * WildFly Admin Guide, "Audit logging": "The audit log ... logs all operations that modify the configuration or runtime
 * state of the server" ({@code log-read-only=false} is the default: only read-only operations are left out). WildFly
 * decides "read-only" per operation from what the handlers declared to the OperationContext: an operation whose steps
 * neither obtained a resource for update nor asked for a modifiable service registry
 * ({@code OperationContext.getServiceRegistry(true)}, javadoc: "modify - true if the operation may be interested in
 * modifying any services in the registry") is treated as read-only and not audited.
 *
 * <h3>Code location</h3>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/management/StartLevelHandler.java:52-66 (WRITE_HANDLER calls
 * {@code FrameworkStartLevel.setStartLevel} directly) and :69-71 ({@code execute} obtains the system bundle with
 * {@code context.getServiceRegistry(false)} for the read and the write handler alike). The attribute is
 * {@code setStorageRuntime()} (parser/OSGiRootResource.java), so the global write-attribute handler does not touch the
 * model either. The access pattern is unchanged since 24cea4cb (2013); the R8 branch only added the
 * IllegalStateException catch around setStartLevel.
 *
 * <h3>Root cause</h3>
 * The write handler changes the running framework without declaring a runtime modification
 * ({@code getServiceRegistry(true)}); WildFly therefore classifies the whole operation as read-only and skips the
 * audit record. Same pattern as lifecycle/05 (BundleResourceHandler), different handler and fix site; services/28
 * (same handler, rollback in the MODEL stage) is a different defect and its suggested fix does not add the declaration.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=StartLevelWriteNotAuditedIssueTestCase
 * </pre>
 * (Prerequisites: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local
 * Maven repository, and on JDK 24+ the wildfly/03 surefire.security.args change. Not an Arquillian test: it copies the
 * testsuite server to target/issues-startlevel-audit-server, enables the audit logger and starts/stops the copy with
 * port offset 1200 (management 11190); override with {@code -Dissue.port.offset=n} (management 9990+n). Run the class on
 * its own.) Manually: {@code /core-service=management/access=audit/logger=audit-log:write-attribute(name=enabled,value=true)},
 * {@code /subsystem=osgi:write-attribute(name=startlevel,value=2)}, then look for "startlevel" in
 * standalone/data/audit-log.log.
 *
 * <h3>Observed</h3>
 * <pre>
 * testStartLevelWriteIsAudited: Expected the management audit log to record the successful
 *   /subsystem=osgi:write-attribute(name=startlevel,value=2) and (value=1) operations (they changed the framework start
 *   level 1 -&gt; 2 -&gt; 1), but write-attribute(startlevel=2) logged=false, write-attribute(startlevel=1) logged=false
 * </pre>
 *
 * <h3>Expected</h3>
 * Every write-attribute(name=startlevel) - successful or denied - has an audit record, like write-attribute(name=activation)
 * on the same resource.
 *
 * <h3>Reference behaviour</h3>
 * The other write operations of the osgi subsystem (activation, property value, capability/property add/remove,
 * :activate) are audited in the same server.
 *
 * <h3>Suggested fix</h3>
 * Declare the runtime modification with {@code context.getServiceRegistry(true)} before changing the start level. The
 * declaration is only allowed in the RUNTIME stage (calling it in the current MODEL-stage handler fails the write with
 * WFLYCTL0181), so do both in a RUNTIME step - which is the services/28 fix - and call
 * {@code ctx.getServiceRegistry(true)} first in that step. Trial verified (reverted): services/28's patch plus this one
 * line makes the test pass; services/28's patch alone leaves it failing (logged=false, logged=false).
 *
 * <h3>Regression vs WF41</h3>
 * no (pre-existing; same access pattern on origin/WF41).
 */
public class StartLevelWriteNotAuditedIssueTestCase {

    static final int OFFSET = Integer.getInteger("issue.port.offset", 1200);
    static final int MGMT_PORT = 9990 + OFFSET;

    private File home;
    private Process process;

    @After
    public void tearDown() throws Exception {
        stopServer();
    }

    @Test
    public void testStartLevelWriteIsAudited() throws Exception {
        prepareServer("issues-startlevel-audit-server");
        startServer("console.out");
        try (ModelControllerClient admin = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            execute(admin, "write-attribute", "core-service=management/access=audit/logger=audit-log", "name", "enabled", "value", "true");
            execute(admin, "add", "system-property=security.audit.marker", "value", "x");
            assertEquals("Precondition: framework start level", 1, readStartLevel(admin));
            execute(admin, "write-attribute", "subsystem=osgi", "name", "startlevel", "value", "2");
            assertEquals("Precondition: the write changed the framework start level", 2, readStartLevel(admin));
            execute(admin, "write-attribute", "subsystem=osgi", "name", "startlevel", "value", "1");
            assertEquals("Precondition: the write changed the framework start level back", 1, readStartLevel(admin));
        }
        File log = new File(home, "standalone/data/audit-log.log");
        assertTrue("Precondition: audit log written " + log, log.isFile());
        String text = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
        assertTrue("Precondition: the audit log records management operations (system-property add)", text.contains("security.audit.marker"));
        boolean raised = audited(text, "2");
        boolean lowered = audited(text, "1");
        assertTrue("Expected the management audit log to record the successful /subsystem=osgi:write-attribute(name=startlevel,value=2) and "
                + "(value=1) operations (they changed the framework start level 1 -> 2 -> 1), but write-attribute(startlevel=2) logged=" + raised
                + ", write-attribute(startlevel=1) logged=" + lowered, raised && lowered);
    }

    /** true if a record of the audit log contains /subsystem=osgi:write-attribute(name=startlevel,value=value) */
    private static boolean audited(String auditLog, String value) {
        for (String record : auditLog.split("(?m)^(?=\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d - )")) {
            int start = record.indexOf('{');
            if (start < 0)
                continue;
            ModelNode node = ModelNode.fromJSONString(record.substring(start));
            if (node.hasDefined("ops") == false)
                continue;
            for (ModelNode op : node.get("ops").asList()) {
                if ("write-attribute".equals(op.get("operation").asString()) && "startlevel".equals(op.get("name").asString())
                        && value.equals(op.get("value").asString()) && op.get("address").asString().contains("osgi"))
                    return true;
            }
        }
        return false;
    }

    private int readStartLevel(ModelControllerClient client) throws IOException {
        ModelNode op = new ModelNode();
        op.get("operation").set("read-attribute");
        op.get("address").add("subsystem", "osgi");
        op.get("name").set("startlevel");
        ModelNode result = client.execute(op);
        assertEquals("Precondition: " + op + " -> " + result, "success", result.get("outcome").asString());
        return result.get("result").asInt();
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
