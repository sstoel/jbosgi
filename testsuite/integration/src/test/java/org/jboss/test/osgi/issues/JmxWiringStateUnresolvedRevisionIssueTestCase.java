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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleRevisions;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * The BundleWiringStateMBean ({@code osgi.core:type=wiringState,version=1.1}) fails with a
 * {@code RuntimeMBeanException} wrapping a {@code NullPointerException} for every bundle that has a revision without
 * a wiring: {@code getRevisionsWiring} of a bundle that was updated while its old revision is still in use, and
 * {@code getCurrentWiring} of a bundle that is not resolved.
 *
 * <h3>Severity</h3>
 * minor. The wiring MBean is unusable for exactly the bundles a management client most wants to inspect (a redeployed
 * bundle whose old revision is still wired, a bundle that failed to resolve); generic JMX consoles show an internal
 * NPE instead of the in-use wiring.
 *
 * <h3>Component</h3>
 * shipped org.apache.aries.jmx 1.1.5 ({@code org.apache.aries.jmx.framework.wiring.BundleWiringState}), as the JMX
 * Management Model implementation of the WildFly OSGi subsystem (capability org.apache.aries.jmx).
 *
 * <h3>Found by</h3>
 * connect workstream, round 5 (OSGi JMX Management Model, Enterprise R7 chapter 124): every BundleWiringStateMBean
 * operation compared with the framework's BundleRevision/BundleWiring API on the patched WildFly 41 server (JDK 25).
 * For resolved bundles all operations agree with the framework (declared capabilities/requirements with attributes,
 * directives and attribute types, wiring capabilities/requirements, required/provided wires, closures).
 *
 * <h3>Spec reference</h3>
 * OSGi Enterprise R7, 124.13.2 BundleWiringStateMBean
 * (https://docs.osgi.org/specification/osgi.enterprise/7.0.0/service.jmx.html): "getRevisionsWiring(long bundleId,
 * String namespace) - Returns the bundle wirings for all revisions of the bundle." and "getCurrentWiring(long bundleId,
 * String namespace) - Returns the bundle wiring for the current bundle revision."; Core R8 7.4 / BundleRevision.getWiring():
 * "Returns the bundle wiring which is using this bundle revision ... null if this bundle revision is not in use"
 * (a pending revision after update, or the revision of an unresolved bundle, legitimately has no wiring).
 *
 * <h3>Code location</h3>
 * org.apache.aries.jmx 1.1.5 {@code BundleWiringState.getRevisionWiring(BundleRevision, int, String, Map)}: calls
 * {@code revision.getWiring().getCapabilities(namespace)} without a null check; used by getCurrentWiring,
 * getRevisionsWiring, getCurrentWiringClosure and getRevisionsWiringClosure. Shipped by the umbrella pom
 * ({@code version.apache.aries.jmx} 1.1.5), unchanged since WF41.
 *
 * <h3>Root cause</h3>
 * Aries assumes every revision returned by BundleRevisions.getRevisions() (and every current revision) has a wiring.
 * The framework is correct: after {@code update()} with the old revision wired to an importer, getRevisions() returns
 * the new (unresolved, no wiring) revision and the old in-use one, as Core R8 requires.
 *
 * <h3>Reproduction</h3>
 * In an umbrella worktree at f025b420 on JDK 25 (surefire.security.args replaced in testsuite/pom.xml, see issue
 * wildfly/03), after one {@code install}:
 * {@code $SP/bin/mvn25 -B -o -pl testsuite/integration -Dtest=JmxWiringStateUnresolvedRevisionIssueTestCase
 * -Dsurefire.failIfNoSpecifiedTests=false clean install}
 *
 * <h3>Observed</h3>
 * <pre>
 * testRevisionsWiringAfterUpdate: getRevisionsWiring failed: javax.management.RuntimeMBeanException:
 *   java.lang.NullPointerException: Cannot invoke "org.osgi.framework.wiring.BundleWiring.getCapabilities(String)"
 *   because "wiring" is null
 * testCurrentWiringOfUnresolvedBundle: getCurrentWiring failed: javax.management.RuntimeMBeanException: ... same NPE
 * </pre>
 *
 * <h3>Expected</h3>
 * getRevisionsWiring returns a row for the in-use revision (and skips, or returns an empty wiring for, the revision
 * that has no wiring); getCurrentWiring of an unresolved bundle returns a result without wires (or null), not an
 * internal NPE.
 *
 * <h3>Reference behaviour</h3>
 * Same Aries version on any framework fails the same way (the framework returns null wirings correctly); the defect is
 * in the shipped component, not in jbosgi.
 *
 * <h3>Suggested fix</h3>
 * Null-check {@code getWiring()} in BundleWiringState.getRevisionWiring (skip revisions without wiring in the
 * "Revisions" operations; return an empty BUNDLE_WIRING for getCurrentWiring), or ship a fixed Aries JMX.
 *
 * <h3>Regression vs WF41</h3>
 * no.
 */
@RunWith(Arquillian.class)
public class JmxWiringStateUnresolvedRevisionIssueTestCase {

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "jmx-wiring-unresolved-tests");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(MBeanServer.class, TabularData.class, FrameworkWiring.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Test
    public void testRevisionsWiringAfterUpdate() throws Exception {
        Bundle exporter = install("jmx-wiring-exporter", "Bundle-Version: 1.0", "Export-Package: jmx.wiring.pkg");
        Bundle importer = install("jmx-wiring-importer", "Import-Package: jmx.wiring.pkg");
        try {
            FrameworkWiring frameworkWiring = context.getBundle(0).adapt(FrameworkWiring.class);
            Assert.assertTrue("Precondition: bundles resolve", frameworkWiring.resolveBundles(Arrays.asList(exporter, importer)));
            exporter.update(jar("jmx-wiring-exporter", "Bundle-Version: 2.0", "Export-Package: jmx.wiring.pkg"));
            int inUse = 0;
            for (BundleRevision revision : exporter.adapt(BundleRevisions.class).getRevisions())
                if (revision.getWiring() != null && revision.getWiring().isInUse())
                    inUse++;
            Assert.assertEquals("Precondition: the old revision is still in use by the importer", 1, inUse);

            Object result;
            try {
                result = invoke("getRevisionsWiring", exporter.getBundleId());
            } catch (Exception ex) {
                throw new AssertionError("getRevisionsWiring failed: " + ex + (ex.getCause() != null ? " caused by " + ex.getCause() : ""), ex);
            }
            int rows = 0;
            for (Object row : ((TabularData) result).values())
                if (((CompositeData) row).get("RequiredWires") != null)
                    rows++;
            Assert.assertTrue("getRevisionsWiring must contain the in-use revision", rows >= 1);
        } finally {
            importer.uninstall();
            exporter.uninstall();
            context.getBundle(0).adapt(FrameworkWiring.class).refreshBundles(null);
        }
    }

    @Test
    public void testCurrentWiringOfUnresolvedBundle() throws Exception {
        Bundle unresolved = install("jmx-wiring-unresolved", "Import-Package: jmx.wiring.missing");
        try {
            Assert.assertFalse("Precondition: bundle does not resolve",
                    context.getBundle(0).adapt(FrameworkWiring.class).resolveBundles(Arrays.asList(unresolved)));
            Assert.assertNull("Precondition: the framework reports no wiring", unresolved.adapt(BundleRevision.class).getWiring());
            try {
                invoke("getCurrentWiring", unresolved.getBundleId());
            } catch (Exception ex) {
                throw new AssertionError("getCurrentWiring failed: " + ex + (ex.getCause() != null ? " caused by " + ex.getCause() : ""), ex);
            }
        } finally {
            unresolved.uninstall();
        }
    }

    private Object invoke(String operation, long bundleId) throws Exception {
        MBeanServer server = context.getService(context.getServiceReference(MBeanServer.class));
        ObjectName name = server.queryNames(new ObjectName("osgi.core:type=wiringState,*"), null).iterator().next();
        return server.invoke(name, operation, new Object[] { bundleId, null }, new String[] { "long", String.class.getName() });
    }

    private Bundle install(String symbolicName, String... headers) throws Exception {
        return context.installBundle(symbolicName, jar(symbolicName, headers));
    }

    private static InputStream jar(String symbolicName, String... headers) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        List<String> entries = new ArrayList<String>();
        entries.add(symbolicName + "/marker.txt");
        for (String header : headers) {
            int index = header.indexOf(": ");
            attributes.putValue(header.substring(0, index), header.substring(index + 2));
            if (header.startsWith(Constants.EXPORT_PACKAGE))
                entries.add(header.substring(index + 2).split(";")[0].replace(".", "/") + "/marker.txt");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes, manifest)) {
            for (String entry : entries) {
                jar.putNextEntry(new JarEntry(entry));
                jar.write(entry.getBytes());
                jar.closeEntry();
            }
        }
        return new ByteArrayInputStream(bytes.toByteArray());
    }
}
