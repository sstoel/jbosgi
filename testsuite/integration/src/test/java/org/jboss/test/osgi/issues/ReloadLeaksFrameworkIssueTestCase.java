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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

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
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * The OSGi framework is never shut down when the server stops or reloads: the bundles that the framework itself
 * installed (the subsystem capability bundles such as Felix SCR, Config Admin, Aries Blueprint/JMX) are never stopped,
 * the framework's own executors are not shut down, and every {@code :reload} leaves the previous framework instance
 * behind with its threads (Framework Events Thread, SCR, Config Admin, Blueprint, JMX dispatchers), classes and heap.
 * (Bundles of deployments are stopped, because the reload undeploys their deployments.)
 *
 * <h3>Severity</h3>
 * critical. The capability bundles never get their stop callback (their services are not unregistered through the
 * bundle lifecycle, state they persist on stop is lost, resources they hold are not released), and each reload of the
 * server, a
 * routine management operation, leaks about 8 threads, about 345 loaded classes (none unloaded) and about 6 MB of heap
 * after GC: a server that is reloaded regularly runs out of threads or metaspace/heap.
 *
 * <h3>Component</h3>
 * jbosgi umbrella, wildfly/subsystem: {@code org.jboss.as.osgi.service.FrameworkBootstrapService} (stop does nothing)
 * and the framework services it installs; jbosgi-framework core services ({@code BundleManagerPlugin.stop},
 * {@code ExecutorServicePlugin}) do not stop the bundles when they are stopped by MSC.
 *
 * <h3>Found by</h3>
 * connect workstream, round 3 (":reload hygiene" harness: N reloads of the patched WildFly 41 server on JDK 25, counting
 * threads, classes, heap, MSC services, storage directories and file descriptors through the management API).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.2.6 Stopping a Framework
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html#framework.lifecycle.stopping): "When the
 * framework is shut down, it first enters the STOPPING state. All ACTIVE bundles are stopped as described in the
 * Bundle.stop method ... The framework then moves to start level 0, ... stops event handling and releases any resources
 * (like threads, class loaders, etc.) it held."<br>
 * 9.3.3 Shutdown Sequence: "When the Framework shuts down, the requested start level must be set to zero."
 *
 * <h3>Code location</h3>
 * {@code wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/FrameworkBootstrapService.java:158-161}:
 * {@code stop(StopContext)} only logs; nothing moves the framework to start level 0 (as
 * {@code BundleManagerPlugin.shutdownManager} does for {@code Framework.stop()}) before MSC removes the framework's
 * child services. The server log of a reload has no "JBOSGI011003: Bundle stopped" lines, and "WildFly ... stopped in
 * 11-23ms" (the "Bundle stopped" lines of a reload are only those of deployed bundles). The idle "Framework Events Thread" executors and the threads of the never stopped bundles (SCR Component
 * Actor, CM Event Dispatcher, Blueprint Extender, JMX OSGi ... Event Dispatcher) stay alive. Unchanged since WF41
 * (FrameworkBootstrapService.stop has not changed).
 *
 * <h3>Root cause</h3>
 * The WildFly integration tears the framework services down without performing the framework shutdown.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=ReloadLeaksFrameworkIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository. The two -Darq... options start the managed server with port offset 500, so the test cannot connect to another server on 9990 (arquillian.xml has allowConnectingToRunningServer=true); they can be left out when no other server runs.)
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: Expected the OSGi framework to be shut down on :reload, but: [5 "SCR Component Actor"
 *   threads after 4 reloads, 1 before (the Felix SCR capability bundle is never stopped), 5 "Framework Events Thread"
 *   threads after 4 reloads, 1 before]
 * (harness, 6 reloads, no deployments: threads 168 -> 216, loaded classes 20356 -> 22552, unloaded 19 -> 19,
 *   heap after GC 59 MB -> 95 MB, MSC services 7495 -> 7495; thread names: Framework Events Thread 1 -> 7,
 *   SCR Component Actor 1 -> 7, CM Event Dispatcher 1 -> 7, Blueprint Extender 2 -> 14, ...)
 * </pre>
 *
 * <h3>Expected</h3>
 * On reload/stop all bundles are stopped (start level 0), the framework's threads end, and the per-reload numbers stay
 * flat.
 *
 * <h3>Reference behaviour</h3>
 * n/a (WildFly integration); a plain jbosgi framework stops its bundles in {@code Framework.stop()}.
 *
 * <h3>Suggested fix</h3>
 * In {@code FrameworkBootstrapService.stop} (or a dedicated service that the framework services depend on) stop the
 * framework through the system bundle ({@code BundleManagerPlugin.shutdownManager}, i.e. start level 0, extension and
 * connector activators, executors) and wait for it before MSC removes the child services.
 *
 * <h3>Note on the test</h3>
 * The test reloads the server 4 times (about 20 s) and leaves it running; the leaked threads of those reloads stay in
 * the server JVM until it exits.
 *
 * <h3>Regression vs WF41</h3>
 * no (measured on a WF41 server built from origin/WF41 77f95be6 on the same patched WildFly 41.0.1, JDK 25: same
 * failure). The code is unchanged since WF41. The WF41 run used harness adaptations only, no change to this test or to
 * the code under test: the jacc {@code <policy>} removed from standalone-osgi.xml, as r8 42d6c470 does (without it
 * org.wildfly.security.policy fails on WildFly 41 and every deferred start fails with JBAS011969), and the r8
 * Arquillian JBOSGiApplicationArchiveProcessor from 54b65e85 (in-container ManagementClient lookup); see the
 * regression-2 workstream NOTES.md.
 */
@RunAsClient
@RunWith(Arquillian.class)
public class ReloadLeaksFrameworkIssueTestCase {

    static final int RELOADS = 4;
    static final String STOPS = "issues.reload.stops";

    @ContainerResource
    ManagementClient managementClient;

    @Test
    public void testReloadShutsDownTheFramework() throws Exception {
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        String deployment = server.deploy("reload-stop-bundle.jar", createBundle());
        try {
            int eventThreadsBefore = countThreads("Framework Events Thread");
            int scrThreadsBefore = countThreads("SCR Component Actor");
            for (int i = 0; i < RELOADS; i++)
                reload();
            int eventThreadsAfter = countThreads("Framework Events Thread");
            int scrThreadsAfter = countThreads("SCR Component Actor");
            String stops = systemProperty(STOPS);

            // The bundles of deployments are stopped when the deployments are undeployed by the reload
            Assert.assertEquals("Precondition: the deployed bundle is stopped with its deployment on every reload", Integer.toString(RELOADS), stops);

            List<String> failures = new ArrayList<String>();
            if (scrThreadsAfter > scrThreadsBefore)
                failures.add(scrThreadsAfter + " \"SCR Component Actor\" threads after " + RELOADS + " reloads, " + scrThreadsBefore
                        + " before (the Felix SCR capability bundle is never stopped)");
            if (eventThreadsAfter > eventThreadsBefore)
                failures.add(eventThreadsAfter + " \"Framework Events Thread\" threads after " + RELOADS + " reloads, " + eventThreadsBefore + " before");
            Assert.assertTrue("Expected the OSGi framework to be shut down on :reload, but: " + failures, failures.isEmpty());
        } finally {
            server.undeploy(deployment);
        }
    }

    private InputStream createBundle() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "reload-stop-bundle.jar");
        archive.addClasses(StopCountingActivator.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName("reload-stop-bundle");
                builder.addBundleManifestVersion(2);
                builder.addBundleActivator(StopCountingActivator.class);
                builder.addImportPackages(BundleActivator.class);
                return builder.openStream();
            }
        });
        return archive.as(ZipExporter.class).exportAsInputStream();
    }

    private void reload() throws Exception {
        long startTime = startTime();
        long begin = System.currentTimeMillis();
        while (System.currentTimeMillis() - begin < 90000) {
            ModelNode op = new ModelNode();
            op.get("operation").set("reload");
            try {
                managementClient.getControllerClient().execute(op);
            } catch (Exception ex) {
                // the connection may be closed by the reload
            }
            // Wait until a new server instance (another start time) is running
            long wait = System.currentTimeMillis();
            while (System.currentTimeMillis() - wait < 30000) {
                Thread.sleep(500);
                try {
                    ModelNode read = new ModelNode();
                    read.get("operation").set("read-attribute");
                    read.get("name").set("server-state");
                    boolean running = "running".equals(managementClient.getControllerClient().execute(read).get("result").asString());
                    if (running && startTime() != startTime) {
                        Thread.sleep(1000);
                        return;
                    }
                } catch (Exception ex) {
                    // not yet available
                }
            }
        }
        throw new IllegalStateException("The server did not come back from :reload");
    }

    /** The number of server starts in the server log (WFLYSRV0025), which grows with every completed reload */
    private long startTime() throws Exception {
        java.io.File log = new java.io.File(System.getProperty("jboss.home"), "standalone/log/server.log");
        long count = 0;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(log))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.contains("WFLYSRV0025:") || line.contains("WFLYSRV0026:"))
                    count++;
            }
        }
        return count;
    }

    private int countThreads(String prefix) throws Exception {
        ModelNode op = new ModelNode();
        op.get("operation").set("dump-all-threads");
        op.get("address").add("core-service", "platform-mbean");
        op.get("address").add("type", "threading");
        op.get("locked-monitors").set(false);
        op.get("locked-synchronizers").set(false);
        int count = 0;
        for (ModelNode thread : managementClient.getControllerClient().execute(op).get("result").asList()) {
            if (thread.get("thread-name").asString().startsWith(prefix))
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

    /** Counts its stop calls in a system property of the server JVM */
    public static class StopCountingActivator implements BundleActivator {

        @Override
        public void start(BundleContext context) {
        }

        @Override
        public void stop(BundleContext context) {
            synchronized (System.class) {
                System.setProperty(STOPS, Integer.toString(Integer.getInteger(STOPS, 0) + 1));
            }
        }
    }
}
