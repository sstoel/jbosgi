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
import org.osgi.jmx.framework.BundleStateMBean;

/**
 * BundleStateMBean.listBundles() and getBundle(id) leave the {@code ActivationPolicyUsed} item of every bundle null
 * (the shipped Apache Aries JMX 1.1.5 never fills it), although the item is part of BUNDLE_TYPE and
 * {@code isActivationPolicyUsed(id)} returns the value.
 *
 * <h3>Severity</h3>
 * minor. Management clients reading the bundle table see no activation policy (null instead of a boolean), e.g. a
 * console cannot show which lazily started bundles are waiting for their activation.
 *
 * <h3>Component</h3>
 * shipped org.apache.aries.jmx 1.1.5 ({@code org.apache.aries.jmx.codec.BundleData} has no activation policy field),
 * as the JMX Management Model implementation of the WildFly OSGi subsystem (capability org.apache.aries.jmx).
 *
 * <h3>Found by</h3>
 * connect workstream, round 4 (OSGi JMX Management Model, Enterprise R7 chapter 124): listBundles() compared with the
 * framework API on the patched WildFly 41 server (JDK 25).
 *
 * <h3>Spec reference</h3>
 * OSGi Enterprise R7, 124 JMX Management Model (https://docs.osgi.org/specification/osgi.enterprise/7.0.0/service.jmx.html),
 * BundleStateMBean javadoc: "ACTIVATION_POLICY_USED_ITEM: The item containing the indication whether the bundle
 * activation policy must be used in BUNDLE_TYPE. The key is ACTIVATION_POLICY_USED and the type is
 * SimpleType.BOOLEAN."; listBundles: "Answer the bundle state of the system in tabular form. Each row of the returned
 * table represents a single bundle. The Tabular Data consists of Composite Data that is type by BUNDLES_TYPE."
 *
 * <h3>Code location</h3>
 * org.apache.aries.jmx 1.1.5 {@code org.apache.aries.jmx.codec.BundleData}: the fields (exportedPackages, fragment,
 * ..., persistentlyStarted, ...) contain no activation policy, so {@code toCompositeData} leaves
 * {@code ActivationPolicyUsed} null. Shipped by the umbrella pom ({@code version.apache.aries.jmx} 1.1.5), unchanged
 * since WF41.
 *
 * <h3>Root cause</h3>
 * The shipped Aries JMX version predates the 1.7 BUNDLE_TYPE item.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=JmxActivationPolicyUsedMissingIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository. The two -Darq... options start the managed server with port offset 500, so the test cannot connect to another server on 9990 (arquillian.xml has allowConnectingToRunningServer=true); they can be left out when no other server runs.)
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: The ActivationPolicyUsed item of the lazily started bundle must be true (as
 *   isActivationPolicyUsed returns true), but listBundles() has null
 * </pre>
 *
 * <h3>Expected</h3>
 * {@code Boolean.TRUE} for a bundle started with START_ACTIVATION_POLICY, {@code Boolean.FALSE} otherwise.
 *
 * <h3>Reference behaviour</h3>
 * The same Aries JMX version behaves the same on every framework (the defect is in the shipped component, not in
 * jbosgi).
 *
 * <h3>Suggested fix</h3>
 * Ship an Aries JMX version that fills the item (or patch BundleData to add it from BundleStartLevel).
 *
 * <h3>Regression vs WF41</h3>
 * no.
 */
@RunWith(Arquillian.class)
public class JmxActivationPolicyUsedMissingIssueTestCase {

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "jmx-activation-policy-tests");
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
    public void testActivationPolicyUsedItem() throws Exception {
        Bundle lazy = install("jmx-lazy-bundle", "Bundle-ActivationPolicy: lazy");
        try {
            lazy.start(Bundle.START_ACTIVATION_POLICY);
            BundleStateMBean bundleState = mbean("bundleState", BundleStateMBean.class);
            Assert.assertTrue("Precondition: isActivationPolicyUsed reports the policy", bundleState.isActivationPolicyUsed(lazy.getBundleId()));
            Object item = null;
            for (Object row : bundleState.listBundles().values()) {
                CompositeData data = (CompositeData) row;
                if (Long.valueOf(lazy.getBundleId()).equals(data.get(BundleStateMBean.IDENTIFIER)))
                    item = data.get(BundleStateMBean.ACTIVATION_POLICY_USED);
            }
            Assert.assertEquals("The ActivationPolicyUsed item of the lazily started bundle must be true (as isActivationPolicyUsed returns true), but listBundles() has "
                    + item, Boolean.TRUE, item);
        } finally {
            lazy.uninstall();
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
