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

import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.junit.InSequence;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper;
import org.jboss.dmr.ModelNode;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * Bundle.uninstall() of a deployed bundle that a Jakarta EE deployment depends on (WAR with
 * {@code Dependencies: deployment.<bundle>}) runs a server undeploy that the management controller rolls back; the
 * subsystem ignores the rollback: uninstall blocks for 60 s on a framework lock timeout, then uninstalls the bundle anyway
 * and leaves a FAILED deployment of the uninstalled bundle (and a FAILED WAR) behind.
 * (Update + refresh of such a bundle leaves a FAILED {@code <bundle>-rev1.jar}; that also happens without a WAR and is
 * reported separately as ee/10, UpdateRefreshLeavesFailedRevisionDeploymentIssueTestCase. The ignored undeploy result
 * in undeployRevision described here is one of the two defects behind ee/10.)
 *
 * <h2>Severity</h2>
 * critical (raised from major by the coordinator after the security workstream found an RBAC trigger of the same root
 * cause, see "Security consequence" below: a management user whose undeploy is denied can still remove deployed
 * bundles and destroy their persistent data). The documented way for a WAR/EJB-JAR/EAR to use a bundle is a module dependency on {@code deployment.<bundle>}
 * (jboss-deployment-structure.xml or the Dependencies manifest header). Any OSGi-side management of such a bundle
 * (Felix web console, a provisioning agent, uninstall from code) then hangs the calling thread for a minute, takes
 * the WAR down, and leaves a broken deployment of a bundle that no longer exists in the server configuration.
 *
 * <h2>Security consequence (security workstream, 2026-10-14)</h2>
 * Same root cause (BundleLifecycleIntegration.undeployRevision ignores the result of server.undeploy), reached without
 * any WAR by a management user whose undeploy is denied by RBAC. Patched server, standalone-osgi.xml as shipped (the
 * org.apache.aries.jmx capability is active, JMX remoting on the management port), RBAC on, user mapped to Operator:
 * {@code /deployment=x.jar:undeploy} is refused (WFLYCTL0313), but
 * {@code osgi.core:type=framework,...:uninstallBundle(id)} over JMX returns normally; the internal undeploy runs as the
 * Operator and fails (audit log: IN_VM_USER, success false), yet the bundle is UNINSTALLED, its persistent data area
 * osgi-store/bundle-N is DELETED and the deployment stays FAILED in the model. Install and update through the same MBean
 * are correctly refused because the deploy path checks its result. The suggested fix below (turn a failed or rolled-back
 * undeploy into a BundleException before the framework changes state) closes this path too. Not covered by this test's
 * assertions; the dedicated test is security/03 OperatorJmxUninstallBypassesRbacIssueTestCase (same root cause,
 * counted as one).
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem, {@code org.jboss.as.osgi.service.BundleLifecycleIntegration}
 * ({@code BundleLifecycleImpl.removeRevision}/{@code undeployRevision}, {@code RecreateCurrentRevisionPolicy}).
 *
 * <h2>Found by</h2>
 * wildfly-ee workstream (deep 2), exploration of EE deployments that depend on a bundle: WAR with
 * {@code Dependencies: deployment.ee-lib.jar}, then undeploy / full-replace / Bundle.update + refresh / Bundle.stop /
 * Bundle.uninstall of the bundle on the patched WildFly 41.0.1 server (JDK 25). Management undeploy (rolled back, the
 * WAR stays consistent), full-replace (WAR restarted with the new classes) and stop/start behave correctly; uninstall
 * through the OSGi API does not.
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 Bundle.uninstall() javadoc: "The Framework must remove any resources related to this bundle that it is
 * able to remove." ... "Postconditions, no exceptions thrown: getState() in { UNINSTALLED }" / "Postconditions, when an
 * exception is thrown: getState() not in { UNINSTALLED }. This Bundle has not been uninstalled." ... "@throws
 * BundleException If the uninstall failed. This can occur if another thread is attempting to change this bundle's state
 * and does not complete in a timely manner."
 * https://docs.osgi.org/specification/osgi.core/8.0.0/framework.api.html#org.osgi.framework.Bundle.uninstall--
 * Documented jbosgi/WildFly behaviour: a bundle deployment uninstalled through the OSGi API is undeployed from the server
 * (testsuite BundleUninstallTestCase); a WildFly deployment whose module dependency goes away goes down with it.
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/BundleLifecycleIntegration.java:337-373
 * ({@code removeRevision} -&gt; {@code undeployRevision} -&gt; {@code server.undeploy(runtimeName)}, line 360, called
 * synchronously from Bundle.uninstall while the uninstalling thread holds the framework locks; the result is not
 * checked).
 * {@code ServerDeploymentHelper.undeploy} only throws when the plan result carries a deployment exception; a rollback
 * caused by the controller's service verification ("WFLYSRV0008: Undeploy of deployment ... was rolled back with no
 * failure message") returns normally. Unchanged result handling since WF41 (origin/WF41 BundleLifecycleIntegration.java:318-326).
 *
 * <h2>Root cause</h2>
 * The WAR's module depends on {@code jboss.module.spec.service."deployment.ee-lib.jar"}. Undeploying the bundle's
 * deployment would leave the WAR with a missing dependency, so the controller rolls the operation back (WFLYCTL0184 New
 * missing/unsatisfied dependencies ... dependents: [service jboss.module.resolve.phase."deployment.ee-web.war".1]). The
 * rollback re-deploys the bundle deployment; its REGISTER phase needs the framework lock held by the uninstalling thread
 * and times out after 60 s (JBOSGI011282 Cannot obtain lock in timely fashion: (INSTALL) [FrameworkWiringLock,
 * ee-lib:1.0.0]). The subsystem does not see the rollback, finishes the uninstall in the framework, and the server keeps
 * the FAILED deployments.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=BundleUsedByWarUndeployRollbackIssueTestCase \
 *     -Darq.container.jboss.configuration.managementPort=11090 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=1100
 * </pre>
 * (Prerequisites: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork in the local Maven
 * repository; on JDK 24+ the testsuite needs surefire.security.args without -Djava.security.manager, see
 * TestJvmSecurityManagerIssueTestCase. The -Darq options keep the test off any other server on 9990.)
 * Deterministic. The test is split in two sequenced methods because the remote test call times out after
 * 60 s: the first starts the uninstall in a background thread and checks that it returns within 15 s, the second waits
 * for it and checks the resulting state.
 *
 * <h2>Observed</h2>
 * <pre>
 * testUninstallOfBundleUsedByWarReturns: java.lang.AssertionError: Bundle.uninstall() of a deployed bundle that a WAR
 *   depends on must return (or fail) within 15 s, but it is still blocked after 15 s
 * testUninstallOfBundleUsedByWarLeavesConsistentState: java.lang.AssertionError: [the uninstall took 60 s, the bundle is
 *   UNINSTALLED but its deployment ee-lib.jar is still in the management model with status FAILED]
 * server.log: WFLYSRV0008: Undeploy of deployment "ee-lib.jar" was rolled back with no failure message
 *   MSC000001: Failed to start service jboss.deployment.unit."ee-lib.jar".REGISTER ... JBOSGI011282: Cannot obtain lock in
 *   timely fashion: (INSTALL) [FrameworkWiringLock, ee-lib:1.0.0]
 * </pre>
 *
 * <h2>Expected</h2>
 * Uninstall returns promptly and is atomic: either it fails with a BundleException and the bundle and its deployment
 * stay as they were (the server refused to undeploy it because the WAR depends on it), or the bundle is uninstalled and
 * its deployment removed (and the WAR goes down with its missing dependency).
 *
 * <h2>Reference behaviour</h2>
 * Not applicable (Equinox/Felix have no server deployments). Plain WildFly: undeploying a deployment that another
 * deployment depends on is rolled back and reported as a failure to the caller (WFLYCTL0441).
 *
 * <h2>Suggested fix (not applied)</h2>
 * Check the plan result in undeployRevision (ServerDeploymentActionResult.getResult() != EXECUTED, or execute the
 * composite through the ModelControllerClient and check the outcome) and turn a rolled-back undeploy into a
 * BundleException before the framework changes its state; run the undeploy without holding the framework locks (or
 * with rollback-on-runtime-failure=false so the dependent EE deployments go down instead of reinstalling the bundle
 * under the uninstaller's locks).
 * Related: wildfly/26 (RefreshRedeployDeadlockIssueTestCase). The 60 s hang has the same underlying design flaw:
 * BundleLifecycleIntegration runs a synchronous server undeploy/deploy while the calling thread holds the framework
 * locks. The primary defect here (rolled-back undeploy result ignored, FAILED deployments left behind and persisted)
 * is distinct, but a fix that moves the server operation out of the framework locks addresses the hang of both.
 *
 * <h2>Regression vs WF41</h2>
 * no (measured on a WF41 server built from origin/WF41 77f95be6 on the same patched WildFly 41.0.1, JDK 25: same
 * failure). testUninstallOfBundleUsedByWarReturns fails identically;
 * testUninstallOfBundleUsedByWarLeavesConsistentState fails on WF41 already at its precondition, because the uninstall
 * has still not returned (the client JMX call times out, server log JBOSGI011282 lock timeout) where R8 returns after
 * 60 s: the same blocked undeploy. The undeploy path and its missing result check are unchanged since WF41. The WF41
 * run used harness adaptations only, no change to this test or to the code under test: the jacc {@code <policy>}
 * removed from standalone-osgi.xml, as r8 42d6c470 does (without it org.wildfly.security.policy fails on WildFly 41
 * and every deferred start fails with JBAS011969), and the r8 Arquillian JBOSGiApplicationArchiveProcessor from
 * 54b65e85 (in-container ManagementClient lookup); see the regression-2 workstream NOTES.md.
 */
@RunWith(Arquillian.class)
public class BundleUsedByWarUndeployRollbackIssueTestCase {

    static final String LIB = "ee-lib.jar";
    static final String WEB = "ee-web.war";
    static final String LIB_V1 = "ee-lib-v1";
    static final String WEB_ARCHIVE = "ee-web";

    static volatile Thread uninstallThread;
    static volatile long uninstallMillis = -1;

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @ArquillianResource
    ManagementClient managementClient;

    @Deployment
    public static JavaArchive testDeployment() {
        final JavaArchive jar = ShrinkWrap.create(JavaArchive.class, "bundle-used-by-war-tests");
        jar.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(jar.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(ManagementClient.class, ModelControllerClient.class, ClientConstants.class, ServerDeploymentHelper.class, ModelNode.class);
                builder.addImportPackages(FrameworkWiring.class);
                return builder.openStream();
            }
        });
        return jar;
    }

    @Test
    @InSequence(2)
    public void testUninstallOfBundleUsedByWarReturns() throws Exception {
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        server.deploy(LIB, deployer.getDeployment(LIB_V1));
        server.deploy(WEB, deployer.getDeployment(WEB_ARCHIVE));
        final Bundle lib = getLibBundle();
        Assert.assertNotNull("Precondition: bundle ee-lib installed", lib);
        Assert.assertEquals("Precondition: WAR deployed", "OK", getStatus(WEB));

        // The uninstall runs in another thread, the remote test call times out after 60 s
        uninstallThread = new Thread("uninstall ee-lib") {
            @Override
            public void run() {
                long start = System.currentTimeMillis();
                try {
                    lib.uninstall();
                } catch (Exception ex) {
                    // checked by the state test
                } finally {
                    uninstallMillis = System.currentTimeMillis() - start;
                }
            }
        };
        uninstallThread.start();
        uninstallThread.join(15000);
        Assert.assertFalse("Bundle.uninstall() of a deployed bundle that a WAR depends on must return (or fail) within 15 s, but it is still blocked after 15 s",
                uninstallThread.isAlive());
    }

    @Test
    @InSequence(3)
    public void testUninstallOfBundleUsedByWarLeavesConsistentState() throws Exception {
        try {
            Assert.assertNotNull("Precondition: testUninstallOfBundleUsedByWarReturns ran", uninstallThread);
            uninstallThread.join(55000);
            Assert.assertFalse("Precondition: the uninstall has returned", uninstallThread.isAlive());

            List<String> failures = new ArrayList<String>();
            if (uninstallMillis > 15000)
                failures.add("the uninstall took " + uninstallMillis / 1000 + " s");
            Bundle lib = getLibBundle();
            String libStatus = getStatus(LIB);
            if (lib == null && libStatus != null)
                failures.add("the bundle is UNINSTALLED but its deployment " + LIB + " is still in the management model with status " + libStatus);
            if (lib != null && !"OK".equals(libStatus))
                failures.add("the uninstall failed but the deployment " + LIB + " has status " + libStatus + " (expected OK, unchanged)");
            Assert.assertTrue("Bundle.uninstall() of a deployed bundle that a WAR depends on must leave a consistent state, but: " + failures, failures.isEmpty());
        } finally {
            undeploy(WEB);
            undeploy(LIB);
            uninstallLeftover();
        }
    }

    private Bundle getLibBundle() {
        for (Bundle bundle : context.getBundles()) {
            if ("ee-lib".equals(bundle.getSymbolicName()))
                return bundle;
        }
        return null;
    }

    private void uninstallLeftover() {
        Bundle lib = getLibBundle();
        if (lib != null) {
            try {
                lib.uninstall();
            } catch (Exception ex) {
                // ignore
            }
        }
    }


    // The deployment status (OK, FAILED, STOPPED), null if there is no such deployment
    private String getStatus(String name) throws Exception {
        ModelNode op = new ModelNode();
        op.get(ClientConstants.OP).set(ClientConstants.READ_ATTRIBUTE_OPERATION);
        op.get(ClientConstants.OP_ADDR).add(ClientConstants.DEPLOYMENT, name);
        op.get(ClientConstants.NAME).set("status");
        ModelNode result = managementClient.getControllerClient().execute(op);
        return ClientConstants.SUCCESS.equals(result.get(ClientConstants.OUTCOME).asString()) ? result.get(ClientConstants.RESULT).asString() : null;
    }

    private void undeploy(String name) {
        try {
            if (getStatus(name) != null)
                new ServerDeploymentHelper(managementClient.getControllerClient()).undeploy(name);
        } catch (Exception ex) {
            // ignore
        }
    }

    private static JavaArchive lib(String archiveName, final String version) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, archiveName);
        archive.addAsResource(new StringAsset(version), "ee-lib/version.txt");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName("ee-lib");
                builder.addBundleVersion(version);
                builder.addBundleManifestVersion(2);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = LIB_V1, managed = false, testable = false)
    public static JavaArchive getLibV1() {
        return lib("ee-lib-v1.jar", "1.0.0");
    }

    @Deployment(name = WEB_ARCHIVE, managed = false, testable = false)
    public static WebArchive getWeb() {
        WebArchive war = ShrinkWrap.create(WebArchive.class, "ee-web.war");
        war.addAsWebResource(new StringAsset("ee-web"), "index.html");
        war.addAsManifestResource(new StringAsset("Manifest-Version: 1.0\nDependencies: deployment." + LIB + "\n"), "MANIFEST.MF");
        return war;
    }
}
