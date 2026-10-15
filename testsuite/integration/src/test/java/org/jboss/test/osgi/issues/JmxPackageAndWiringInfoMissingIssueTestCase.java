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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.ObjectName;
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
import org.osgi.jmx.framework.BundleStateMBean;
import org.osgi.jmx.framework.PackageStateMBean;

/**
 * The JMX Management Model MBeans of the server (Apache Aries JMX 1.1.5) report no exported or imported packages, no
 * fragments or hosts and no required or requiring bundles, and PackageStateMBean lists no packages: the information is
 * taken from the framework's PackageAdmin service, whose jbosgi implementation returns null for all of it.
 *
 * <h3>Severity</h3>
 * major. Every management tool that uses the standard OSGi JMX API (JConsole/VisualVM OSGi plugins, Karaf-style web
 * consoles, monitoring) sees a framework in which no bundle exports or imports anything and no fragment is attached;
 * "Required" is false for every bundle, so tools that warn before uninstalling a bundle in use do not warn.
 *
 * <h3>Component</h3>
 * jbosgi-framework core {@code org.jboss.osgi.framework.internal.PackageAdminImpl} (getExportedPackages,
 * getExportedPackage, getRequiredBundles, getFragments, getHosts all {@code return null}), consumed by the shipped
 * org.apache.aries.jmx 1.1.5 (capability of the WildFly OSGi subsystem, module org.apache.aries.jmx).
 *
 * <h3>Found by</h3>
 * connect workstream, round 4 (OSGi JMX Management Model, Enterprise R7 chapter 124): every row of
 * BundleStateMBean.listBundles() compared with the framework API on the patched WildFly 41 server (JDK 25).
 *
 * <h3>Spec reference</h3>
 * OSGi Enterprise R7, 124 JMX Management Model Specification
 * (https://docs.osgi.org/specification/osgi.enterprise/7.0.0/service.jmx.html), BundleStateMBean javadoc:
 * getExportedPackages "Answer the list of exported packages for this bundle ... the array of package names, combined
 * with their version in the format &lt;packageName;version&gt;"; getImportedPackages "Answer the array of the packages
 * imported by this bundle"; getHosts "Answer the list of bundle ids of the bundles which host a fragment";
 * getFragments "Answer the list of the bundle ids of the fragments associated with this bundle";
 * getRequiringBundles "Answer the list of identifiers of the bundles which require this bundle";
 * PackageStateMBean.listPackages "Answer the package state of the system in tabular form".
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/PackageAdminImpl.java:100-127}: the
 * deprecated PackageAdmin is registered as a stub ("[JBOSGI-636] Restore PackageAdmin compatible with Aries JMX
 * 1.0.0"), whose query methods return null; Aries JMX 1.1.5 ({@code org.apache.aries.jmx.codec.BundleData},
 * {@code org.apache.aries.jmx.framework.PackageState}) builds the package, fragment, host and required-bundle data
 * from it. The framework's own wiring API (BundleWiring, FrameworkWiring) has the data. Unchanged since WF41.
 *
 * <h3>Root cause</h3>
 * A stub PackageAdmin that satisfies Aries JMX's dependency without providing data.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=JmxPackageAndWiringInfoMissingIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository. The two -Darq... options start the managed server with port offset 500, so the test cannot connect to another server on 9990 (arquillian.xml has allowConnectingToRunningServer=true); they can be left out when no other server runs.)
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: Expected the OSGi JMX MBeans to report the wiring of the bundles, but: [exported packages
 *   of jmx-wiring-exporter: [] (expected [org.jboss.test.osgi.issues.jmxapi;1.1.0]), imported packages of
 *   jmx-wiring-importer: [] (...), hosts of jmx-wiring-fragment: [] (expected [importer id]), fragments of
 *   jmx-wiring-importer: [], requiring bundles of jmx-wiring-exporter: [], required bundles of jmx-wiring-requirer:
 *   [], PackageStateMBean.listPackages has no row for org.jboss.test.osgi.issues.jmxapi]
 * (comparing every listBundles() row with the framework API: ExportedPackages, ImportedPackages, Fragments, Hosts,
 *   RequiredBundles, RequiringBundles empty and Required=false for all 34 bundles; PackageAdmin.getExportedPackages
 *   (null) itself returns null)
 * </pre>
 *
 * <h3>Expected</h3>
 * The MBeans report the packages and bundle wiring that BundleWiring/FrameworkWiring report.
 *
 * <h3>Reference behaviour</h3>
 * Not run as a differential test (on Equinox and Felix, PackageAdmin is a real implementation).
 *
 * <h3>Suggested fix</h3>
 * Implement the PackageAdmin query methods on top of the wiring API (BundleWiring.getCapabilities/getProvidedWires,
 * FrameworkWiring.getRemovalPendingBundles), or ship a JMX implementation that uses the wiring API.
 *
 * <h3>Regression vs WF41</h3>
 * no (the stub and Aries JMX are unchanged since WF41).
 */
@RunWith(Arquillian.class)
public class JmxPackageAndWiringInfoMissingIssueTestCase {

    static final String PACKAGE = "org.jboss.test.osgi.issues.jmxapi";

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "jmx-wiring-info-tests");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(MBeanServer.class, TabularData.class, BundleStateMBean.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Test
    public void testWiringReportedByMBeans() throws Exception {
        Bundle exporter = install("jmx-wiring-exporter", "Export-Package: " + PACKAGE + ";version=1.1.0");
        Bundle importer = install("jmx-wiring-importer", "Import-Package: " + PACKAGE);
        Bundle fragment = install("jmx-wiring-fragment", "Fragment-Host: jmx-wiring-importer");
        Bundle requirer = install("jmx-wiring-requirer", "Require-Bundle: jmx-wiring-exporter");
        try {
            importer.start();
            requirer.start();
            BundleStateMBean bundleState = mbean("bundleState", BundleStateMBean.class);
            PackageStateMBean packageState = mbean("packageState", PackageStateMBean.class);

            List<String> failures = new ArrayList<String>();
            List<String> exported = Arrays.asList(bundleState.getExportedPackages(exporter.getBundleId()));
            if (exported.contains(PACKAGE + ";1.1.0") == false)
                failures.add("exported packages of jmx-wiring-exporter: " + exported + " (expected [" + PACKAGE + ";1.1.0])");
            List<String> imported = Arrays.asList(bundleState.getImportedPackages(importer.getBundleId()));
            if (imported.contains(PACKAGE + ";1.1.0") == false)
                failures.add("imported packages of jmx-wiring-importer: " + imported + " (expected [" + PACKAGE + ";1.1.0])");
            Set<Long> hosts = ids(bundleState.getHosts(fragment.getBundleId()));
            if (hosts.equals(Collections.singleton(importer.getBundleId())) == false)
                failures.add("hosts of jmx-wiring-fragment: " + hosts + " (expected [" + importer.getBundleId() + "])");
            Set<Long> fragments = ids(bundleState.getFragments(importer.getBundleId()));
            if (fragments.equals(Collections.singleton(fragment.getBundleId())) == false)
                failures.add("fragments of jmx-wiring-importer: " + fragments + " (expected [" + fragment.getBundleId() + "])");
            // Aries JMX reports every bundle that depends on the bundle (FrameworkUtils.getDependentBundles): the
            // importers of its packages too, not only the bundles that require it
            Set<Long> requiring = ids(bundleState.getRequiringBundles(exporter.getBundleId()));
            if (requiring.contains(requirer.getBundleId()) == false)
                failures.add("requiring bundles of jmx-wiring-exporter: " + requiring + " (expected to contain " + requirer.getBundleId() + ")");
            Set<Long> required = ids(bundleState.getRequiredBundles(requirer.getBundleId()));
            if (required.equals(Collections.singleton(exporter.getBundleId())) == false)
                failures.add("required bundles of jmx-wiring-requirer: " + required + " (expected [" + exporter.getBundleId() + "])");
            boolean listed = false;
            for (Object row : packageState.listPackages().values()) {
                if (PACKAGE.equals(((javax.management.openmbean.CompositeData) row).get(PackageStateMBean.NAME)))
                    listed = true;
            }
            if (listed == false)
                failures.add("PackageStateMBean.listPackages has no row for " + PACKAGE);
            Assert.assertTrue("Expected the OSGi JMX MBeans to report the wiring of the bundles, but: " + failures, failures.isEmpty());
        } finally {
            // Users first, so that the exporter is never uninstalled while it is in use
            requirer.uninstall();
            fragment.uninstall();
            importer.uninstall();
            exporter.uninstall();
        }
    }

    private MBeanServer mbeanServer() {
        return context.getService(context.getServiceReference(MBeanServer.class));
    }

    private <T> T mbean(String type, Class<T> type2) throws Exception {
        MBeanServer server = mbeanServer();
        ObjectName name = server.queryNames(new ObjectName("osgi.core:type=" + type + ",*"), null).iterator().next();
        return JMX.newMBeanProxy(server, name, type2);
    }

    private Bundle install(String symbolicName, String... headers) throws Exception {
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
        return context.installBundle(symbolicName, new ByteArrayInputStream(bytes.toByteArray()));
    }

    private static Set<Long> ids(long[] values) {
        Set<Long> result = new TreeSet<Long>();
        if (values != null)
            for (long value : values)
                result.add(value);
        return result;
    }
}
