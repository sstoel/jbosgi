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

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.as.arquillian.api.ContainerResource;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper;
import org.jboss.dmr.ModelNode;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.startlevel.BundleStartLevel;

/**
 * A server {@code :reload} uninstalls every deployed bundle and installs it again as a new bundle: the bundle's
 * persistent storage area ({@code BundleContext.getDataFile}) is deleted, its start level is reset to the initial
 * start level, a bundle stopped by the operator is started again, and the bundle ids change. A full restart
 * ({@code :shutdown} + start) keeps all of this; only {@code :reload} loses it.
 *
 * <h3>Severity</h3>
 * critical (data loss: every {@code :reload} - a routine operation that many configuration changes require - wipes
 * the persistent data of all deployed bundles, e.g. stored configuration, keys, caches, queues; operator decisions
 * such as "bundle stopped" and bundle start levels are silently reverted, so stopped bundles come back to life).
 *
 * <h3>Component</h3>
 * jbosgi umbrella, wildfly/subsystem: {@code org.jboss.as.osgi.deployment.BundleInstallProcessor.getUninstallOptions}
 * (decides between "server stops: keep the bundle storage" and "real undeploy: uninstall and delete the storage");
 * effect in jbosgi-framework core {@code UserBundleState.uninstallInternal}.
 *
 * <h3>Found by</h3>
 * wildfly-lifecycle workstream (operator view), server restart/reload persistence checks on the patched WildFly 41.0.1
 * + jbosgi R8 server, JDK 25: CLI deployments with a data-file counter, a start level change and a management
 * {@code bundle=X:stop}, compared after {@code :reload} and after {@code :shutdown} + start.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8 4.5.2 Persistent Storage (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html):
 * "The Framework should provide a private persistent storage area for each installed bundle"; storage is only removed
 * by {@code Bundle.uninstall} ("This bundle and any persistent storage area provided for this bundle by the Framework
 * are removed"), and a reload uninstalls nothing from the operator's point of view (the deployments stay in the
 * model).<br>
 * {@code BundleStartLevel.setStartLevel} javadoc: "The start level value assigned to the bundle will be persistently
 * recorded by the Framework."<br>
 * {@code Bundle.stop(int)} javadoc: "If the STOP_TRANSIENT option is not set then set this bundle's persistent
 * autostart setting to Stopped. When the Framework is restarted and this bundle's autostart setting is Stopped, this
 * bundle must not be automatically started."<br>
 * {@code Bundle.getBundleId} javadoc: "Its value is not reused for another bundle, even after a bundle is uninstalled."
 *
 * <h3>Code location</h3>
 * <ul>
 * <li>jbosgi wildfly/subsystem/src/main/java/org/jboss/as/osgi/deployment/BundleInstallProcessor.java:153-158
 * {@code getUninstallOptions}: returns {@code STOP_TRANSIENT} (keep storage) only when the service
 * {@code jboss.server.controller} ({@code Services.JBOSS_SERVER_CONTROLLER}) is in mode REMOVE or NEVER. That is the case
 * on shutdown (the container sets every service to REMOVE), but not on {@code :reload}: WildFly Core reloads by setting
 * the root service {@code jboss.as} ({@code Services.JBOSS_AS}) to NEVER (wildfly-core
 * controller/.../operations/common/ProcessReloadHandler.java:104, server/.../ServerProcessReloadHandler.java:94/99); the
 * server controller service keeps mode ACTIVE while it goes down. So undeploy() at line 104-105 calls
 * {@code uninstallBundle(bundle, 0)}.</li>
 * <li>jbosgi-framework core/src/main/java/org/jboss/osgi/framework/internal/UserBundleState.java:481-485: without
 * STOP_TRANSIENT the storage state (bundle-N directory: data area, start level, autostart setting) is deleted.</li>
 * <li>After the reload the deployments are installed again; BundleInstallProcessor.java:82 finds no storage state to
 * restore, so the bundles start from scratch with the initial start level, autostart and new ids.</li>
 * </ul>
 * The logic is unchanged since the code was taken from WildFly 8 (git log -S getUninstallOptions: e4fe0811); the WF41
 * branch has the same method.
 *
 * <h3>Root cause</h3>
 * The "server is stopping" detection does not recognise a reload, so a reload is handled like an operator undeploy.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=11190 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=1200 \
 *     -pl testsuite/integration test -Dtest=ReloadUninstallsDeployedBundlesIssueTestCase
 * </pre>
 * (Prerequisites: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local
 * Maven repository; on JDK 24+ the testsuite/pom.xml surefire.security.args workaround of issue wildfly/03. The two
 * -Darq... options start the managed server with port offset 1200 so the test cannot connect to another running server.)
 * Manually: deploy a bundle with the CLI, let it write a file into its data area, {@code /subsystem=osgi/bundle=X:stop},
 * {@code :reload}: the bundle is ACTIVE again, its data file is gone and {@code standalone/data/osgi-store/bundle-N/}
 * was recreated. With {@code :shutdown} and a new start instead, all of it is kept.
 *
 * <h3>Observed</h3>
 * <pre>
 * testDataAreaKeptOverReload: Expected the deployed bundle to find the data file it wrote before the :reload in its
 *   persistent storage area (1 start recorded), but it found 0 (bundle id 25 before, 25 after the reload)
 * testBundleStartLevelKeptOverReload: Expected the bundle start level 2 (...) to survive a :reload, but it is 1 and
 *   the bundle is "ACTIVE" expected:&lt;2&gt; but was:&lt;1&gt;
 * testStoppedBundleStaysStoppedOverReload: Expected a bundle stopped through
 *   /subsystem=osgi/bundle=reloadkeep-stopped.jar:stop (...) to stay stopped after a :reload, as it does after a
 *   restart, but it is "ACTIVE".
 * (manual run with three CLI deployments: ids 25/26/27 became 26/27/25 after the first :reload and changed again on
 * every further reload; standalone/data/osgi-store/bundle-N/ of every deployed bundle is recreated with StartLevel=1
 * and PersistentlyStarted=true.)
 * </pre>
 *
 * <h3>Expected</h3>
 * {@code :reload} behaves like a restart: the bundles keep their storage area, start level, autostart setting and id.
 *
 * <h3>Reference behaviour</h3>
 * The same server keeps all of it across {@code :shutdown} + start (checked manually: data counter continued, start
 * level 2 and the stopped state kept, ids unchanged). With the trial fix below the three test methods pass.
 *
 * <h3>Suggested fix</h3>
 * In {@code BundleInstallProcessor.getUninstallOptions} also treat a stopping root service as "server stops", e.g.
 * <pre>
 * ServiceController&lt;?&gt; root = serviceContainer.getService(Services.JBOSS_AS);
 * stopRequested |= root != null &amp;&amp; root.getMode() != ServiceController.Mode.ACTIVE;
 * </pre>
 * (verified as a trial fix: 3/3 pass), or better, ask the {@code ControlledProcessStateService} whether the process is
 * STOPPING/reloading instead of inspecting service modes.
 *
 * <h3>Regression vs WF41</h3>
 * no (pre-existing; BundleInstallProcessor.getUninstallOptions is unchanged on WF41-osgi-r8).
 */
@RunAsClient
@RunWith(Arquillian.class)
public class ReloadUninstallsDeployedBundlesIssueTestCase {

    static final String PREFIX = "issues.reloadkeep.";

    @ContainerResource
    ManagementClient managementClient;

    @Test
    public void testDataAreaKeptOverReload() throws Exception {
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        String name = server.deploy("reloadkeep-data.jar", createBundle("reloadkeep-data", DataActivator.class));
        try {
            Assert.assertEquals("Precondition: the bundle found an empty data area on its first start", "0", systemProperty(PREFIX + "reloadkeep-data"));
            long idBefore = bundle(name).get("id").asLong();
            reload();
            String found = systemProperty(PREFIX + "reloadkeep-data");
            long idAfter = bundle(name).get("id").asLong();
            Assert.assertEquals("Expected the deployed bundle to find the data file it wrote before the :reload in its persistent storage area (1 start recorded), "
                    + "but it found " + found + " (bundle id " + idBefore + " before, " + idAfter + " after the reload)", "1", found);
        } finally {
            server.undeploy(name);
        }
    }

    @Test
    public void testBundleStartLevelKeptOverReload() throws Exception {
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        String name = server.deploy("reloadkeep-level.jar", createBundle("reloadkeep-level", DataActivator.class));
        try {
            // A helper bundle moves the bundle to start level 2; the framework stays at start level 1, so the bundle is stopped
            String helper = server.deploy("reloadkeep-setter.jar", createBundle("reloadkeep-setter", StartLevelSetter.class));
            server.undeploy(helper);
            ModelNode before = bundle(name);
            Assert.assertEquals("Precondition: start level set", 2, before.get("startlevel").asInt());
            Assert.assertEquals("Precondition: bundle stopped by the start level change", "RESOLVED", before.get("state").asString());
            reload();
            ModelNode after = bundle(name);
            Assert.assertEquals("Expected the bundle start level 2 (set through BundleStartLevel.setStartLevel, javadoc: 'persistently set') to survive a :reload, "
                    + "but it is " + after.get("startlevel") + " and the bundle is " + after.get("state"), 2, after.get("startlevel").asInt());
        } finally {
            server.undeploy(name);
        }
    }

    @Test
    public void testStoppedBundleStaysStoppedOverReload() throws Exception {
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        String name = server.deploy("reloadkeep-stopped.jar", createBundle("reloadkeep-stopped", DataActivator.class));
        try {
            ModelNode op = new ModelNode();
            op.get("operation").set("stop");
            op.get("address").add("subsystem", "osgi").add("bundle", name);
            Assert.assertEquals("Precondition: management stop succeeds", "success", managementClient.getControllerClient().execute(op).get("outcome").asString());
            Assert.assertEquals("Precondition: bundle stopped", "RESOLVED", bundle(name).get("state").asString());
            reload();
            ModelNode after = bundle(name);
            Assert.assertNotEquals("Expected a bundle stopped through /subsystem=osgi/bundle=" + name + ":stop (Bundle.stop(): persistent autostart setting Stopped) "
                    + "to stay stopped after a :reload, as it does after a restart, but it is " + after.get("state"), "ACTIVE", after.get("state").asString());
        } finally {
            server.undeploy(name);
        }
    }

    private InputStream createBundle(final String symbolicName, final Class<? extends BundleActivator> activator) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, symbolicName + ".jar");
        archive.addClasses(activator);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(symbolicName);
                builder.addBundleManifestVersion(2);
                builder.addBundleActivator(activator);
                builder.addImportPackages(BundleActivator.class, BundleStartLevel.class);
                return builder.openStream();
            }
        });
        return archive.as(ZipExporter.class).exportAsInputStream();
    }

    private ModelNode bundle(String location) throws Exception {
        ModelNode op = new ModelNode();
        op.get("operation").set("read-resource");
        op.get("address").add("subsystem", "osgi").add("bundle", location);
        op.get("include-runtime").set(true);
        ModelNode result = managementClient.getControllerClient().execute(op);
        Assert.assertEquals("Precondition: bundle " + location + " readable: " + result, "success", result.get("outcome").asString());
        return result.get("result");
    }

    private void reload() throws Exception {
        long startCount = startCount();
        long begin = System.currentTimeMillis();
        ModelNode op = new ModelNode();
        op.get("operation").set("reload");
        try {
            managementClient.getControllerClient().execute(op);
        } catch (Exception ex) {
            // the connection may be closed by the reload
        }
        while (System.currentTimeMillis() - begin < 90000) {
            Thread.sleep(500);
            try {
                ModelNode read = new ModelNode();
                read.get("operation").set("read-attribute");
                read.get("name").set("server-state");
                boolean running = "running".equals(managementClient.getControllerClient().execute(read).get("result").asString());
                if (running && startCount() != startCount) {
                    Thread.sleep(1000);
                    return;
                }
            } catch (Exception ex) {
                // not yet available
            }
        }
        throw new IllegalStateException("The server did not come back from :reload");
    }

    /** The number of server starts in the server log (WFLYSRV0025/26), which grows with every completed reload */
    private long startCount() throws Exception {
        File log = new File(System.getProperty("jboss.home"), "standalone/log/server.log");
        long count = 0;
        for (String line : Files.readAllLines(log.toPath(), StandardCharsets.UTF_8)) {
            if (line.contains("WFLYSRV0025:") || line.contains("WFLYSRV0026:"))
                count++;
        }
        return count;
    }

    private String systemProperty(String name) throws Exception {
        ModelNode op = new ModelNode();
        op.get("operation").set("read-attribute");
        op.get("address").add("core-service", "platform-mbean");
        op.get("address").add("type", "runtime");
        op.get("name").set("system-properties");
        ModelNode props = managementClient.getControllerClient().execute(op).get("result");
        return props.hasDefined(name) ? props.get(name).asString() : null;
    }

    /** Counts its starts in a file of its persistent storage area; publishes the count found on start in a system property */
    public static class DataActivator implements BundleActivator {

        @Override
        public void start(BundleContext context) throws Exception {
            File file = context.getDataFile("starts.txt");
            int count = file.exists() ? Integer.parseInt(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim()) : 0;
            Files.write(file.toPath(), Integer.toString(count + 1).getBytes(StandardCharsets.UTF_8));
            System.setProperty(PREFIX + context.getBundle().getSymbolicName(), Integer.toString(count));
        }

        @Override
        public void stop(BundleContext context) {
        }
    }

    /** Moves bundle reloadkeep-level to start level 2 */
    public static class StartLevelSetter implements BundleActivator {

        @Override
        public void start(BundleContext context) {
            for (Bundle bundle : context.getBundles()) {
                if ("reloadkeep-level".equals(bundle.getSymbolicName()))
                    bundle.adapt(BundleStartLevel.class).setStartLevel(2);
            }
        }

        @Override
        public void stop(BundleContext context) {
        }
    }
}
