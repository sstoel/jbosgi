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
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
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
import org.wildfly.security.auth.client.AuthenticationConfiguration;
import org.wildfly.security.auth.client.AuthenticationContext;
import org.wildfly.security.auth.client.MatchRule;
import org.wildfly.security.sasl.SaslMechanismSelector;

/**
 * A management user with the Operator role, which RBAC denies {@code /deployment=x:undeploy}, can uninstall any deployed
 * bundle through the OSGi JMX FrameworkMBean ({@code osgi.core:type=framework,...:uninstallBundle(id)}): the internal
 * undeploy runs as the Operator and is refused, the refusal is ignored, the bundle is uninstalled in the framework, its
 * persistent data area osgi-store/bundle-N is deleted and the deployment is left FAILED in the management model.
 *
 * <h3>Related</h3>
 * related: same root cause as ee/01 (BundleUsedByWarUndeployRollbackIssueTestCase). ee/01 demonstrates the ignored
 * undeploy result with a WAR that depends on the bundle; this class demonstrates the RBAC/security consequence that the
 * ee/01 test does not assert. One root cause, one fix.
 *
 * <h3>Severity</h3>
 * critical (security: privilege escalation and data loss. A role that RBAC explicitly denies undeploy removes any
 * deployed bundle from the runtime and destroys its persistent data; the server keeps a FAILED deployment that a
 * restart or the next deployment operation reports as broken).
 *
 * <h3>Component</h3>
 * jbosgi umbrella, wildfly/subsystem: {@code org.jboss.as.osgi.service.BundleLifecycleIntegration}
 * ({@code BundleLifecycleImpl.removeRevision} / {@code undeployRevision}); reached through the shipped
 * org.apache.aries.jmx capability (FrameworkMBean) over the WildFly jmx subsystem remoting connector on the management
 * port.
 *
 * <h3>Found by</h3>
 * security workstream (RBAC matrix: users with the Monitor, Operator, Deployer, Maintainer roles calling osgi.core
 * MBean operations over service:jmx:remote+http with DIGEST-MD5, management and JMX audit logs on; evidence in
 * agents/security/evidence/rbac-audit-log.log and matrix.txt, note issues/ee/01-*&#47;NOTE-from-security.md); filed as a
 * dedicated test by a follow-up workstream.
 *
 * <h3>Spec reference</h3>
 * WildFly Admin Guide, "Role Based Access Control": the Operator role may modify the runtime state (start/stop
 * services) but not the persistent configuration; deploying and undeploying are configuration changes reserved to
 * Deployer/Maintainer and above. The server enforces exactly that for {@code /deployment=x:undeploy} (WFLYCTL0313) and
 * for the JMX facade {@code jboss.as:deployment=x undeploy}. OSGi Core R8 API, Bundle.uninstall() javadoc
 * (osgi.core 8.0.0): "Postconditions, when an exception is thrown: getState() not in { UNINSTALLED }. This Bundle has
 * not been uninstalled." / "@throws BundleException If the uninstall failed." In this integration an uninstall of a
 * deployed bundle is implemented as a server undeploy; when that undeploy is refused the uninstall has failed and must
 * not proceed to "This bundle and any persistent storage area provided for this bundle by the Framework are removed."
 *
 * <h3>Code location</h3>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/BundleLifecycleIntegration.java:337-372 on WF41-osgi-r8:
 * {@code removeRevision} calls {@code undeployRevision}, which runs {@code server.undeploy(runtimeName)} (line 360) and
 * on failure only logs WARN JBAS011905 (line 362); {@code removeRevision} returns normally and the framework completes
 * the uninstall (UNINSTALLED event, storage area deleted). In this path ServerDeploymentHelper.undeploy does throw
 * (ServerDeploymentException carrying WFLYCTL0313, because the internal operation runs with the caller's identity); in
 * the ee/01 path the rollback carries no deployment exception and is not even thrown. Either way the result is
 * ignored. Install/update through the same MBean are refused correctly because the deploy path checks its result.
 *
 * <h3>Root cause</h3>
 * same root cause as ee/01 (BundleLifecycleIntegration.undeployRevision ignores the undeploy result); counts with ee/01
 * as one root cause; the ee/01 fix closes it.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=OperatorJmxUninstallBypassesRbacIssueTestCase
 * </pre>
 * (Prerequisites: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local
 * Maven repository; on JDK 24+ the surefire.security.args edit of testsuite/pom.xml, issue wildfly/03. Not an Arquillian
 * test: it copies the testsuite server to target/issues-operator-jmx-server, adds a management user mapped to Operator,
 * enables RBAC, restarts and drives the copy with port offset 1200 (management 11190); override with
 * -Dissue.port.offset=n, management 9990+n.) Manually: {@code add-user.sh -u op -p ...}, map it to Operator, set
 * {@code /core-service=management/access=authorization:write-attribute(name=provider,value=rbac)}, restart, deploy a
 * bundle as superuser, then with a JMX client authenticated as op over service:jmx:remote+http://localhost:9990 invoke
 * {@code osgi.core:type=framework,version=1.7,framework=org.jboss.osgi.framework,uuid=...:uninstallBundle(id)}.
 *
 * <h3>Observed</h3>
 * <pre>
 * [issue] jboss.as JMX facade undeploy as osgi-operator: javax.management.JMRuntimeException: WFLYJMX0040: Not authorized
 *   to invoke operation: 'undeploy'
 * [issue] osgi.core:type=framework,version=1.7,framework=org.jboss.osgi.framework,uuid=... uninstallBundle(25) as
 *   osgi-operator: returned normally
 * testOperatorCannotUninstallDeployedBundleViaJmx:233 Expected FrameworkMBean.uninstallBundle(25) as the Operator (whom
 *   RBAC denies /deployment=operator-marker.jar:undeploy: WFLYCTL0313: Unauthorized to execute operation 'undeploy' for
 *   resource '[("deployment" =&gt; "operator-marker.jar")]' -- "WFLYCTL0332: Permission denied") to be refused and to
 *   leave the deployed bundle alone, but the call returned normally and afterwards: bundle state (WFLYCTL0216:
 *   Management resource '[ ("subsystem" =&gt; "osgi"), ("bundle" =&gt; "operator-marker.jar") ]' not found), persistent
 *   data file .../standalone/data/osgi-store/bundle-25/marker.txt exists=false, /deployment=operator-marker.jar status
 *   FAILED expected:&lt;[ACTIVE/true/OK]&gt; but was:&lt;[(WFLYCTL0216: ... not found)/false/FAILED]&gt;
 * Server log:
 *   JBOSGI011003: Bundle stopped: operator-marker:0.0.0
 *   WARN JBAS011905: Cannot undeploy bundle revision: HostBundleRevision[operator-marker:0.0.0]:
 *     ServerDeploymentHelper$ServerDeploymentException: java.lang.Exception: "WFLYCTL0313: Unauthorized to execute
 *     operation 'undeploy' for resource '[(\"deployment\" =&gt; \"operator-marker.jar\")]' -- \"WFLYCTL0332: Permission denied\""
 *   JBOSGI011005: Bundle uninstalled: operator-marker:0.0.0
 * </pre>
 * Security workstream evidence (agents/security/evidence/rbac-audit-log.log): with the management audit log on, the
 * internal composite undeploy+remove is recorded for the Operator with access IN_VM_USER and "success": false.
 *
 * <h3>Expected</h3>
 * uninstallBundle fails (the MBean throws an IOException / the BundleException of a refused undeploy), the bundle stays
 * ACTIVE, its data area survives and the deployment stays OK - consistent with the denied {@code :undeploy}.
 *
 * <h3>Reference behaviour</h3>
 * In the same server, the same user is denied {@code /deployment=x:undeploy} through the native API (WFLYCTL0313) and
 * {@code undeploy} through the jboss.as JMX facade; FrameworkMBean installBundleFromURL / updateBundleFromURL as Operator
 * are refused ("JBAS011952: Cannot deploy bundle revision", bundle unchanged).
 *
 * <h3>Suggested fix</h3>
 * The ee/01 fix: check the result of the undeploy in undeployRevision (ServerDeploymentActionResult.getResult() !=
 * EXECUTED, or run the composite through the ModelControllerClient and check outcome) and turn a refused or rolled-back
 * undeploy into a BundleException before the framework changes the bundle state or deletes its storage.
 *
 * <h3>Regression vs WF41</h3>
 * no. Same code on WF41 (origin/WF41 BundleLifecycleIntegration.java:318-326: undeployRevision catches and logs). Checked
 * on a WF41 build (umbrella origin/WF41, jbosgi-framework-core 6.6.0.Final, WildFly 41.0.1, JDK 25, RBAC on, user
 * mapped to Operator): jboss.as:deployment=marker.jar undeploy over JMX -&gt; WFLYJMX0040 Not authorized;
 * FrameworkMBean.uninstallBundle(25) -&gt; returns null; log JBAS011905 ... WFLYCTL0313 then JBOSGI011005 Bundle
 * uninstalled; osgi-store/bundle-25 deleted; /deployment=marker.jar status FAILED. Identical behaviour.
 */
public class OperatorJmxUninstallBypassesRbacIssueTestCase {

    static final int OFFSET = Integer.getInteger("issue.port.offset", 1200);
    static final int MGMT_PORT = 9990 + OFFSET;
    static final String USER = "osgi-operator";
    static final String PASSWORD = "Operator-pw-1";
    static final String DEPLOYMENT = "operator-marker.jar";

    public static class DataActivator implements BundleActivator {
        @Override
        public void start(BundleContext context) throws Exception {
            File file = context.getDataFile("marker.txt");
            if (file.exists() == false)
                Files.write(file.toPath(), "persistent bundle data".getBytes(StandardCharsets.UTF_8));
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
    public void testOperatorCannotUninstallDeployedBundleViaJmx() throws Exception {
        prepareServer("issues-operator-jmx-server");
        String hash = new BigInteger(1, MessageDigest.getInstance("MD5").digest((USER + ":ManagementRealm:" + PASSWORD).getBytes(StandardCharsets.UTF_8))).toString(16);
        while (hash.length() < 32)
            hash = "0" + hash;
        Files.write(new File(home, "standalone/configuration/mgmt-users.properties").toPath(), ("\n" + USER + "=" + hash + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);

        startServer("console-1.out");
        try (ModelControllerClient admin = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            new ServerDeploymentHelper(admin).deploy(DEPLOYMENT, bundle());
            execute(admin, "add", "core-service=management/access=authorization/role-mapping=Operator");
            execute(admin, "add", "core-service=management/access=authorization/role-mapping=Operator/include=user-" + USER, "name", USER, "type", "USER");
            execute(admin, "write-attribute", "core-service=management/access=authorization", "name", "provider", "value", "rbac");
        }
        stopServer();
        startServer("console-2.out");

        try (ModelControllerClient operator = operatorClient(); ModelControllerClient admin = ModelControllerClient.Factory.create("localhost", MGMT_PORT)) {
            ModelNode whoami = new ModelNode();
            whoami.get("operation").set("whoami");
            whoami.get("verbose").set(true);
            assertEquals("Precondition: the client is authenticated as " + USER + " with the Operator role", "[\"Operator\"]",
                    operator.execute(whoami).get("result").get("mapped-roles").asString().replaceAll("\\s", ""));
            assertEquals("Precondition: bundle active", "ACTIVE", bundleState(admin));
            long bundleId = readBundleAttribute(admin, "id").get("result").asLong();
            File dataFile = dataFile(bundleId);
            assertTrue("Precondition: the bundle's persistent data file exists: " + dataFile, dataFile.isFile());

            // (1) RBAC denies the Operator the undeploy of the bundle deployment
            ModelNode undeploy = new ModelNode();
            undeploy.get("operation").set("undeploy");
            undeploy.get("address").add("deployment", DEPLOYMENT);
            ModelNode denied = operator.execute(undeploy);
            assertEquals("Precondition: RBAC refuses /deployment=" + DEPLOYMENT + ":undeploy for the Operator: " + denied, "failed", denied.get("outcome").asString());
            assertTrue("Precondition: refused with WFLYCTL0313: " + denied, denied.get("failure-description").asString().contains("WFLYCTL0313"));
            assertEquals("Precondition: bundle still active after the refused undeploy", "ACTIVE", bundleState(admin));

            // (2) the same user uninstalls the bundle through the OSGi FrameworkMBean over JMX remoting
            String jmxResult = uninstallViaJmx(bundleId);

            String state = bundleState(admin);
            boolean dataSurvives = dataFile.isFile();
            String status = deploymentStatus(admin);
            assertEquals("Expected FrameworkMBean.uninstallBundle(" + bundleId + ") as the Operator (whom RBAC denies /deployment=" + DEPLOYMENT
                    + ":undeploy: " + denied.get("failure-description").asString() + ") to be refused and to leave the deployed bundle alone, but the call "
                    + jmxResult + " and afterwards: bundle state " + state + ", persistent data file " + dataFile + " exists=" + dataSurvives
                    + ", /deployment=" + DEPLOYMENT + " status " + status, "ACTIVE/true/OK", state + "/" + dataSurvives + "/" + status);
        }
    }

    /** Connects as the Operator (DIGEST-MD5, no local auth) and invokes FrameworkMBean.uninstallBundle(id) */
    private String uninstallViaJmx(long bundleId) throws Exception {
        AuthenticationConfiguration config = AuthenticationConfiguration.empty().useName(USER).usePassword(PASSWORD)
                .setSaslMechanismSelector(SaslMechanismSelector.NONE.addMechanism("DIGEST-MD5"));
        AuthenticationContext context = AuthenticationContext.empty().with(MatchRule.ALL, config);
        JMXServiceURL url = new JMXServiceURL("service:jmx:remote+http://localhost:" + MGMT_PORT);
        JMXConnector connector = context.run((java.security.PrivilegedExceptionAction<JMXConnector>) () -> JMXConnectorFactory.connect(url, new HashMap<String, Object>()));
        try {
            MBeanServerConnection server = connector.getMBeanServerConnection();
            // the same user is denied the undeploy through the jboss.as JMX facade too (proves the identity of this connection)
            try {
                server.invoke(new ObjectName("jboss.as:deployment=" + DEPLOYMENT), "undeploy", new Object[0], new String[0]);
                fail("Precondition: the JMX connection runs as the Operator, whom RBAC denies jboss.as:deployment=" + DEPLOYMENT + " undeploy");
            } catch (Exception ex) {
                String message = String.valueOf(ex);
                assertTrue("Precondition: the JMX connection runs as the Operator, whom RBAC denies jboss.as:deployment=" + DEPLOYMENT + " undeploy: " + message,
                        message.contains("WFLYJMX0037") || message.contains("WFLYJMX0040") || message.contains("WFLYCTL0313"));
                System.out.println("[issue] jboss.as JMX facade undeploy as " + USER + ": " + message);
            }
            Set<ObjectName> names = server.queryNames(new ObjectName("osgi.core:type=framework,*"), null);
            assertEquals("Precondition: one osgi.core FrameworkMBean (org.apache.aries.jmx capability) " + names, 1, names.size());
            try {
                server.invoke(names.iterator().next(), "uninstallBundle", new Object[] { bundleId }, new String[] { "long" });
                System.out.println("[issue] " + names.iterator().next() + " uninstallBundle(" + bundleId + ") as " + USER + ": returned normally");
                return "returned normally";
            } catch (Exception ex) {
                return "threw " + ex;
            }
        } finally {
            connector.close();
        }
    }

    private File dataFile(long bundleId) throws IOException {
        File store = new File(home, "standalone/data/osgi-store/bundle-" + bundleId);
        if (store.isDirectory()) {
            try (java.util.stream.Stream<Path> files = Files.walk(store.toPath())) {
                for (Path path : (Iterable<Path>) files::iterator) {
                    if (path.getFileName().toString().equals("marker.txt"))
                        return path.toFile();
                }
            }
        }
        return new File(store, "marker.txt");
    }

    private ModelControllerClient operatorClient() {
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

    private ModelNode readBundleAttribute(ModelControllerClient client, String name) throws IOException {
        ModelNode op = new ModelNode();
        op.get("operation").set("read-attribute");
        op.get("address").add("subsystem", "osgi").add("bundle", DEPLOYMENT);
        op.get("name").set(name);
        return client.execute(op);
    }

    private String bundleState(ModelControllerClient client) throws IOException {
        ModelNode result = readBundleAttribute(client, "state");
        return "success".equals(result.get("outcome").asString()) ? result.get("result").asString() : "(" + result.get("failure-description").asString().replaceAll("\\s+", " ") + ")";
    }

    private String deploymentStatus(ModelControllerClient client) throws IOException {
        ModelNode op = new ModelNode();
        op.get("operation").set("read-attribute");
        op.get("address").add("deployment", DEPLOYMENT);
        op.get("name").set("status");
        ModelNode result = client.execute(op);
        return "success".equals(result.get("outcome").asString()) ? result.get("result").asString() : "absent";
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
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, DEPLOYMENT);
        archive.addClasses(DataActivator.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName("operator-marker");
                builder.addBundleActivator(DataActivator.class);
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
