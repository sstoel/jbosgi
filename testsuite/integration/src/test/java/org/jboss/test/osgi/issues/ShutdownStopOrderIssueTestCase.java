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
import org.osgi.framework.startlevel.FrameworkStartLevel;

/**
 * On server shutdown and {@code :reload} the deployed bundles are stopped in MSC deployment teardown order, not by
 * start level: a start level 1 bundle is stopped while the framework is still at start level 2 and while start level 2
 * bundles (which may use its services in their own {@code stop()}) are still ACTIVE. The framework never moves its
 * active start level down for the deployed bundles.
 *
 * <h3>Severity</h3>
 * major (spec violation a real bundle hits: start levels are the OSGi tool to order shutdown; a bundle at a higher
 * start level that flushes or deregisters through a service of a lower-level bundle in its {@code stop()} finds that
 * bundle already stopped, depending on MSC scheduling).
 *
 * <h3>Component</h3>
 * jbosgi umbrella, wildfly/subsystem: {@code org.jboss.as.osgi.deployment.BundleActivateProcessor.BundleActivateService}
 * (stops each deployed bundle when MSC stops its deployment); no part of the subsystem performs the framework shutdown
 * sequence before the deployments are taken down.
 *
 * <h3>Found by</h3>
 * wildfly-lifecycle workstream, graceful shutdown / reload checks on the patched WildFly 41.0.1 + jbosgi R8 server, JDK
 * 25: probe bundles at start levels 1, 2 and 3 (framework start level 5) recording their activator calls.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8 9.3.3 Shutdown Sequence
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.startlevel.html): "When the Framework shuts down, the
 * requested start level must be set to zero. The Framework must then follow the process described in Changing the
 * Active Start Level to make the active start level equal to zero."<br>
 * 9.3.1: "If the requested start level is lower than the active start level, the Framework must stop all bundles that
 * have a bundle start level that is equal to the active start level. The Framework must then decrease the active start
 * level by 1."<br>
 * 4.2.6 Stopping a Framework: "All ACTIVE bundles are stopped as described in the Bundle.stop method, except that
 * their persistently recorded start state is kept unchanged."
 *
 * <h3>Code location</h3>
 * <ul>
 * <li>jbosgi wildfly/subsystem/src/main/java/org/jboss/as/osgi/deployment/BundleActivateProcessor.java:140-149:
 * {@code BundleActivateService.stop} calls {@code bundleManager.stopBundle(bundle, STOP_TRANSIENT)} for its own bundle
 * when MSC stops the deployment; MSC stops the deployments of a reload/shutdown independently of each other (parallel
 * MSC threads), so the order is arbitrary.</li>
 * <li>The framework's ordered shutdown (jbosgi-framework {@code BundleManagerPlugin.shutdownManager} ->
 * {@code FrameworkStartLevelSupport.shutdownFramework} -> {@code StartLevelManagerImpl.decreaseFrameworkStartLevel},
 * StartLevelManagerImpl.java:186) is never invoked in the server; {@code FrameworkBootstrapService.stop}
 * (wildfly/subsystem .../service/FrameworkBootstrapService.java:158) only logs (connect/27) and would in any case run
 * after the deployments, which depend on the framework services, are already down.</li>
 * </ul>
 *
 * <h3>Root cause</h3>
 * The bundles of deployments are stopped one by one by their MSC services instead of by the framework moving to start
 * level 0 before the deployment teardown. Related but different: connect/27 (capability bundles never stopped at all)
 * - its suggested fix in FrameworkBootstrapService.stop runs too late to order the deployed bundles.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=11190 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=1200 \
 *     -pl testsuite/integration test -Dtest=ShutdownStopOrderIssueTestCase
 * </pre>
 * (Prerequisites: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local
 * Maven repository; on JDK 24+ the testsuite/pom.xml surefire.security.args workaround of issue wildfly/03. The two
 * -Darq... options start the managed server with port offset 1200 so the test cannot connect to another running server.)
 * The test uses {@code :reload} (Arquillian keeps the server); {@code :shutdown} takes the same path.
 *
 * <h3>Observed</h3>
 * <pre>
 * org.junit.ComparisonFailure: Expected the framework to be at active start level 1 when the start level 1 bundle is
 *   stopped during the reload (9.3.3: ...), but the active start level was 2 and the start level 2 bundle was ACTIVE
 *   (stop() called in thread MSC service thread 1-8) expected:&lt;[1]&gt; but was:&lt;[2]&gt;
 * (5 runs: active start level 2 every time; the start level 2 bundle was still ACTIVE in 3 of 4 runs that recorded it.)
 * Manual :shutdown(suspend-timeout=20) with bundles at start levels 3, 1, 2 (framework start level 5), activator log:
 *   STOP lc.rec.a sl=3 [MSC service thread 1-8], STOP lc.rec.c sl=1 [MSC service thread 1-7],
 *   STOP lc.rec.b sl=2 [MSC service thread 1-2]; three :reload runs gave three other orders.
 * </pre>
 *
 * <h3>Expected</h3>
 * When the server stops or reloads, the deployed bundles are stopped by descending start level (the framework moves its
 * active start level down to 0 one level at a time), before or as the first step of the deployment teardown.
 *
 * <h3>Reference behaviour</h3>
 * A plain jbosgi framework (and Equinox/Felix) stops bundles by descending start level in {@code Framework.stop()}.
 * Boot is correct: with org.osgi.framework.startlevel.beginning=5 the same server starts the deployed bundles in
 * ascending start level order (checked manually).
 *
 * <h3>Suggested fix</h3>
 * When the server is stopping or reloading (see lifecycle/01 for the detection), lower the framework start level to 0
 * through the start level manager before the first deployed bundle is stopped, e.g. at the start of
 * {@code BundleActivateService.stop}:
 * <pre>
 * if (serverStopping) startLevelManager.decreaseFrameworkStartLevel(0);  // synchronized; later callers return at once
 * </pre>
 * (verified as a trial fix: the test passes and the log shows "JBOSGI011012: Stopping bundles for start level: 0" once;
 * no errors), or better perform the whole framework shutdown (also fixing connect/27) from a service that every bundle
 * deployment depends on, so that it runs before MSC stops the deployments.
 *
 * <h3>Regression vs WF41</h3>
 * no (pre-existing; BundleActivateProcessor and FrameworkBootstrapService.stop are unchanged on WF41-osgi-r8).
 */
@RunAsClient
@RunWith(Arquillian.class)
public class ShutdownStopOrderIssueTestCase {

    static final String LOW = "stoporder-low";
    static final String HIGH = "stoporder-high";
    static final String RESULT = "issues.stoporder.result";
    static final String RUN = Long.toString(System.currentTimeMillis());

    @ContainerResource
    ManagementClient managementClient;

    @Test
    public void testHigherStartLevelStoppedFirstOnReload() throws Exception {
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        String low = server.deploy(LOW + ".jar", createBundle(LOW, LowActivator.class));
        String high = server.deploy(HIGH + ".jar", createBundle(HIGH, HighActivator.class));
        try {
            // Framework start level 2, the high bundle gets start level 2 (helper bundle), the low bundle keeps 1
            writeFrameworkStartLevel(2);
            String helper = server.deploy("stoporder-setter.jar", createBundle("stoporder-setter", StartLevelSetter.class));
            server.undeploy(helper);
            Assert.assertEquals("Precondition: high bundle start level", 2, bundle(high).get("startlevel").asInt());
            Assert.assertEquals("Precondition: high bundle active", "ACTIVE", bundle(high).get("state").asString());
            Assert.assertEquals("Precondition: low bundle active", "ACTIVE", bundle(low).get("state").asString());

            reload();

            String result = systemProperty(RESULT + "." + RUN);
            Assert.assertNotNull("Precondition: the stop method of the start level 1 bundle ran during the reload", result);
            String[] parts = result.split("[|]");
            Assert.assertEquals("Expected the framework to be at active start level 1 when the start level 1 bundle is stopped during the reload "
                    + "(9.3.3: shutdown moves the active start level down one level at a time, stopping the bundles of each level; so all start level 2 bundles "
                    + "are stopped before), but the active start level was " + parts[0] + " and the start level 2 bundle was " + parts[1]
                    + " (stop() called in thread " + parts[2] + ")", "1", parts[0]);
        } finally {
            server.undeploy(high);
            server.undeploy(low);
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
                builder.addImportPackages(BundleActivator.class, BundleStartLevel.class, FrameworkStartLevel.class);
                builder.addManifestHeader("X-Run", RUN);
                return builder.openStream();
            }
        });
        return archive.as(ZipExporter.class).exportAsInputStream();
    }

    private void writeFrameworkStartLevel(int level) throws Exception {
        ModelNode op = new ModelNode();
        op.get("operation").set("write-attribute");
        op.get("address").add("subsystem", "osgi");
        op.get("name").set("startlevel");
        op.get("value").set(level);
        ModelNode result = managementClient.getControllerClient().execute(op);
        Assert.assertEquals("Precondition: framework start level written: " + result, "success", result.get("outcome").asString());
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

    /** The start level 1 bundle: records the active start level and the state of the start level 2 bundle when it is stopped */
    public static class LowActivator implements BundleActivator {

        @Override
        public void start(BundleContext context) {
        }

        @Override
        public void stop(BundleContext context) {
            int active = context.getBundle(0).adapt(FrameworkStartLevel.class).getStartLevel();
            String high = "not found";
            for (Bundle bundle : context.getBundles()) {
                if (HIGH.equals(bundle.getSymbolicName()))
                    high = bundle.getState() == Bundle.ACTIVE ? "ACTIVE" : "state " + bundle.getState();
            }
            System.setProperty(RESULT + "." + context.getBundle().getHeaders().get("X-Run"), active + "|" + high + "|" + Thread.currentThread().getName());
        }
    }

    /** The start level 2 bundle */
    public static class HighActivator implements BundleActivator {

        @Override
        public void start(BundleContext context) {
        }

        @Override
        public void stop(BundleContext context) {
        }
    }

    /** Moves bundle stoporder-high to start level 2 */
    public static class StartLevelSetter implements BundleActivator {

        @Override
        public void start(BundleContext context) {
            for (Bundle bundle : context.getBundles()) {
                if (HIGH.equals(bundle.getSymbolicName()))
                    bundle.adapt(BundleStartLevel.class).setStartLevel(2);
            }
        }

        @Override
        public void stop(BundleContext context) {
        }
    }
}
