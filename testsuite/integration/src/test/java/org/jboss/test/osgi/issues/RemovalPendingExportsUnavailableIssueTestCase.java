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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.ByteArrayAsset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * Undeploying a bundle whose exported package is in use leaves it removal pending and its importers wired to it, but
 * the package is no longer usable: classes the importer had not loaded yet fail with ClassNotFoundException /
 * NoClassDefFoundError and resources of the package are not found (ERROR JBOSGI011031 "Cannot get entry ...
 * FileNotFoundException"), until a refresh. Importers started later fail in their activator.
 *
 * <h2>Severity</h2>
 * major. Found by the concurrent-deployment soak (round 3, period 3): redeploying the API bundle under a running
 * provider made the next start of the provider fail with
 * {@code JBOSGI011254: Cannot start bundle: soak.provider:1.0.0 Caused by: java.lang.NoClassDefFoundError: soak/api/Api};
 * period 2 logged many {@code JBOSGI011031: Cannot get entry ... /content/<jar>} for such stale revisions. Any
 * redeployment of a library bundle (the normal way to update in WildFly) breaks its users until something refreshes
 * them, and the framework keeps reporting them as RESOLVED/ACTIVE.
 *
 * <h2>Component</h2>
 * jbosgi-framework core ({@code UserBundleState.uninstallInternal}, {@code StorageManagerImpl.deleteStorageState});
 * in WildFly additionally the deployment teardown of the jbosgi (umbrella) wildfly/subsystem
 * ({@code BundleInstallProcessor.undeploy}, deployment content mount).
 *
 * <h2>Found by</h2>
 * wildfly workstream, server soak under concurrent deployment load (JDK 25, Docker eclipse-temurin:25-jdk).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 Bundle.uninstall(): "If this bundle has exported any packages, the Framework must continue to make these
 * packages available to their importing bundles until the FrameworkWiring.refreshBundles method has been called or the
 * Framework is relaunched." Also 4.4.8 Uninstalling Bundles and 10.2.? FrameworkWiring.getRemovalPendingBundles.
 * https://docs.osgi.org/specification/osgi.core/8.0.0/framework.api.html#org.osgi.framework.Bundle.uninstall--
 *
 * <h2>Code location</h2>
 * jbosgi-framework core/src/main/java/org/jboss/osgi/framework/internal/UserBundleState.java:481-485
 * ({@code uninstallInternal} step "#5": {@code storagePlugin.deleteStorageState(getStorageState())} runs even when
 * {@code hasActiveWiresWhileUninstalling()} is true, i.e. for a bundle that stays removal pending);
 * StorageManagerImpl.java:175-179 ({@code deleteStorageState} closes the storage root VirtualFile, which is the content
 * the revision loads classes and entries from, and deletes the storage directory). In WildFly the undeploy
 * (BundleInstallProcessor.undeploy -&gt; uninstallBundle, wildfly/subsystem/.../deployment/BundleInstallProcessor.java:96-120)
 * takes this path and the deployment teardown also closes the deployment content mount; HostBundleRevision.getEntry
 * then fails with ERROR JBOSGI011031 "Cannot get entry ... FileNotFoundException". The framework still lists the
 * revision as removal pending and the importer's wire to it as valid.
 *
 * <h2>Root cause</h2>
 * The bundle's content is released at uninstall time instead of when its last revision is removed: "uninstalled but
 * still in use" is modelled for the wiring, but the storage (content) of the revision is closed and deleted
 * immediately (and in WildFly the deployment's mount is closed as well).
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full \
 *     -pl testsuite/integration test -Dtest=RemovalPendingExportsUnavailableIssueTestCase
 * </pre>
 * Deterministic: deploy an API bundle (exports package {@code removalpending.api} with class Api, generated in the test,
 * and a resource), deploy an importer and let it resolve (it does not load the class), undeploy the API bundle, then
 * load {@code removalpending.api.Api} and the resource through the importer.
 * (on JDK 24+ the testsuite needs surefire.security.args without -Djava.security.manager, see TestJvmSecurityManagerIssueTestCase)
 *
 * <h2>Observed</h2>
 * <pre>
 * java.lang.AssertionError: Expected the packages of the removal pending API bundle to stay available to its importer
 *   until refresh, but: [loading removalpending.api.Api through the importer (wired to removal-pending-api.jar:0.0.0 in
 *   state 1) must work, but: java.lang.ClassNotFoundException: removalpending.api.Api from [Module
 *   "deployment.removal-pending-user.jar" from Service Module Loader], the resource removalpending/api/marker.txt of the
 *   removal pending exporter must be found, but getResource returned null]
 * server.log: ERROR [org.jboss.osgi.framework] JBOSGI011031: Cannot get entry 'removalpending/api/marker.txt' from:
 *   HostBundleRevision[removal-pending-api.jar:0.0.0]: java.io.FileNotFoundException
 * </pre>
 *
 * <h2>Expected</h2>
 * While the API bundle is removal pending, the importer loads {@code removalpending.api.Api} and reads the resource
 * from it; after FrameworkWiring.refreshBundles the importer is re-resolved (or unresolved if nothing exports the
 * package any more).
 *
 * <h2>Reference behaviour</h2>
 * The plain jbosgi framework fails the same way (framework itest in the issue folder: after Bundle.uninstall of an
 * in-use exporter, getRemovalPendingBundles = [rpfw-api], importer.loadClass gives ClassNotFoundException and
 * getResource null). Felix and Equinox keep the uninstalled exporter's content open until refresh (Felix:
 * BundleImpl revisions are closed in Felix.refreshPackages / garbage collection of the bundle; Equinox: the
 * generation is closed when the bundle is removed after refresh).
 *
 * <h2>Suggested fix (not applied)</h2>
 * In UserBundleState.uninstallInternal delete the storage state only when the bundle is not in use
 * ({@code activeWires == false}); otherwise defer it to the point where the removal-pending revisions are removed
 * (removeBundle of the aux bundle / refresh). In WildFly also keep the deployment content mount of an in-use bundle
 * until then (or refresh the importers as part of the undeploy: undeploy = uninstall + refresh of the dependents).
 * Trial (framework only, reverted): guarding the deleteStorageState call with {@code activeWires == false} makes the
 * plain-framework reproduction pass (class loads, resource readable; issue folder explore-rp-fw-trial.log); the storage
 * then has to be deleted when the removal-pending revision is finally removed. Not verified in WildFly.
 *
 * <h2>Regression vs WF41</h2>
 * no: the same test fails the same way on the adapted origin/WF41 baseline (JDK 25, see run.log).
 */
@RunWith(Arquillian.class)
public class RemovalPendingExportsUnavailableIssueTestCase {

    static final String API = "removal-pending-api.jar";
    static final String USER = "removal-pending-user.jar";
    static final String PKG = "removalpending.api";
    static final String RESOURCE = "removalpending/api/marker.txt";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "removal-pending-tests");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(FrameworkWiring.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Test
    public void testRemovalPendingExportStaysUsable() throws Exception {
        deployer.deploy(API);
        boolean userDeployed = false;
        try {
            deployer.deploy(USER);
            userDeployed = true;
            Bundle user = find(USER);
            FrameworkWiring fwiring = context.getBundle(0).adapt(FrameworkWiring.class);
            Assert.assertTrue("Precondition: importer resolves", fwiring.resolveBundles(java.util.Collections.singleton(user)));

            deployer.undeploy(API);

            List<String> problems = new ArrayList<String>();
            String pending = String.valueOf(fwiring.getRemovalPendingBundles());
            if (!pending.contains(API))
                problems.add("precondition: the API bundle must be removal pending, but removal pending: " + pending);
            String wire = providerOf(user);
            try {
                user.loadClass(PKG + ".Api");
            } catch (Throwable th) {
                problems.add("loading " + PKG + ".Api through the importer (wired to " + wire + ") must work, but: " + th);
            }
            URL url = user.getResource(RESOURCE);
            if (url == null) {
                problems.add("the resource " + RESOURCE + " of the removal pending exporter must be found, but getResource returned null");
            } else {
                try {
                    url.openStream().close();
                } catch (IOException ex) {
                    problems.add("the resource " + RESOURCE + " must be readable, but: " + ex);
                }
            }
            Assert.assertTrue("Expected the packages of the removal pending API bundle to stay available to its importer until refresh, but: " + problems, problems.isEmpty());
        } finally {
            if (userDeployed)
                deployer.undeploy(USER);
            if (findOrNull(API) != null)
                deployer.undeploy(API);
        }
    }

    private Bundle find(String bsn) {
        Bundle bundle = findOrNull(bsn);
        Assert.assertNotNull("Precondition: bundle deployed: " + bsn, bundle);
        return bundle;
    }

    private Bundle findOrNull(String bsn) {
        for (Bundle bundle : context.getBundles())
            if (bsn.equals(bundle.getSymbolicName()))
                return bundle;
        return null;
    }

    private static String providerOf(Bundle bundle) {
        BundleWiring wiring = bundle.adapt(BundleWiring.class);
        if (wiring == null)
            return "nothing (no wiring)";
        for (BundleWire wire : wiring.getRequiredWires("osgi.wiring.package"))
            return wire.getProvider().getBundle() + " in state " + wire.getProvider().getBundle().getState();
        return "nothing (no package wire)";
    }

    // Class file of 'public class removalpending.api.Api' (no members), so that the API bundle carries a real class
    static byte[] apiClass() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(0xCAFEBABE);
            out.writeShort(0);
            out.writeShort(52);
            out.writeShort(5);
            out.writeByte(7);
            out.writeShort(2);
            out.writeByte(1);
            out.writeUTF("removalpending/api/Api");
            out.writeByte(7);
            out.writeShort(4);
            out.writeByte(1);
            out.writeUTF("java/lang/Object");
            out.writeShort(0x21);
            out.writeShort(1);
            out.writeShort(3);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(0);
            out.close();
            return bos.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Deployment(name = API, managed = false, testable = false)
    public static JavaArchive getApi() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, API);
        archive.add(new ByteArrayAsset(apiClass()), "removalpending/api/Api.class");
        archive.add(new StringAsset("marker"), RESOURCE);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addExportPackages(PKG);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = USER, managed = false, testable = false)
    public static JavaArchive getUser() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, USER);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(PKG);
                return builder.openStream();
            }
        });
        return archive;
    }
}
