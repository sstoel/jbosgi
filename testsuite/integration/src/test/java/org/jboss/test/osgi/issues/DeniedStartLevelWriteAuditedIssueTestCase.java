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
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
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
import org.jboss.dmr.ModelNode;
import org.junit.After;
import org.junit.Test;

/**
 * A write of the framework start level that RBAC denies is not recorded in the management audit log: the global
 * write-attribute handler refuses a runtime attribute before the operation is marked as a write, and the audit logger
 * skips read-only operations.
 *
 * <h3>Severity</h3>
 * minor (security: audit trail gap). Attempts of read-only users to change the framework start level leave no record.
 *
 * <h3>Component</h3>
 * wildfly-core fork, controller: {@code WriteAttributeHandler}, {@code OperationContext.deniedWriteAttempt}.
 *
 * <h3>Found by</h3>
 * R8 fix review, residual of security/01 (umb-a-M02).
 *
 * <h3>Spec reference</h3>
 * WildFly Admin Guide, "Audit logging": the audit log records the operations that modify the configuration or the
 * runtime state, also when they are denied.
 *
 * <h3>Code location</h3>
 * wildfly-core controller/src/main/java/org/jboss/as/controller/operations/global/WriteAttributeHandler.java:82-91.
 *
 * <h3>Root cause</h3>
 * A configuration attribute is read for update before it is authorized, which marks the operation as a write; a runtime
 * attribute is not.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi/testsuite/integration
 * mvn test -Dtest=DeniedStartLevelWriteAuditedIssueTestCase
 * </pre>
 * Self-hosted: copies the testsuite server, adds a Monitor user, enables RBAC and the audit logger.
 *
 * <h3>Expected</h3>
 * The denied write-attribute(name=startlevel) of the Monitor user has an audit record.
 *
 * <h3>Regression vs WF41</h3>
 * no.
 */
public class DeniedStartLevelWriteAuditedIssueTestCase {

    static final int OFFSET = Integer.getInteger("issue.port.offset", 3000);
    static final int MGMT_PORT = 9990 + OFFSET;
    static final String USER = "osgi-monitor";
    static final String PASSWORD = "Monitor-pw-1";

    private File home;
    private Process process;

    @After
    public void tearDown() throws Exception {
        stopServer();
    }

    @Test
    public void testDeniedStartLevelWriteIsAudited() throws Exception {
        prepareServer("issues-denied-audit-server");
        // a management user that will get the read-only Monitor role
        String hash = new BigInteger(1, MessageDigest.getInstance("MD5").digest((USER + ":ManagementRealm:" + PASSWORD).getBytes(StandardCharsets.UTF_8))).toString(16);
        while (hash.length() < 32)
            hash = "0" + hash;
        Files.write(new File(home, "standalone/configuration/mgmt-users.properties").toPath(), ("\n" + USER + "=" + hash + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);

        startServer("console-1.out");
        try (ModelControllerClient admin = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            execute(admin, "add", "core-service=management/access=authorization/role-mapping=Monitor");
            execute(admin, "add", "core-service=management/access=authorization/role-mapping=Monitor/include=user-" + USER, "name", USER, "type", "USER");
            execute(admin, "write-attribute", "core-service=management/access=authorization", "name", "provider", "value", "rbac");
            execute(admin, "write-attribute", "core-service=management/access=audit/logger=audit-log", "name", "enabled", "value", "true");
        }
        stopServer();
        startServer("console-2.out");

        try (ModelControllerClient monitor = monitorClient()) {
            ModelNode whoami = new ModelNode();
            whoami.get("operation").set("whoami");
            whoami.get("verbose").set(true);
            assertEquals("Precondition: the client is authenticated as " + USER + " with the Monitor role", "[\"Monitor\"]",
                    monitor.execute(whoami).get("result").get("mapped-roles").asString().replaceAll("\\s", ""));

            ModelNode write = new ModelNode();
            write.get("operation").set("write-attribute");
            write.get("address").add("subsystem", "osgi");
            write.get("name").set("startlevel");
            write.get("value").set(7);
            ModelNode result = monitor.execute(write);
            assertEquals("Precondition: the write is denied for the Monitor role: " + result, "failed", result.get("outcome").asString());
        }
        File log = new File(home, "standalone/data/audit-log.log");
        long deadline = System.currentTimeMillis() + 10000;
        String compact = "";
        while (System.currentTimeMillis() < deadline) {
            compact = log.isFile() ? new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8).replaceAll("\\s", "") : "";
            if (compact.contains("\"name\":\"startlevel\",\"value\":7"))
                break;
            Thread.sleep(500);
        }
        assertTrue("Expected the management audit log to record the denied write-attribute(name=startlevel,value=7) of the Monitor user",
                compact.contains("\"operation\":\"write-attribute\"") && compact.contains("\"name\":\"startlevel\",\"value\":7"));
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
