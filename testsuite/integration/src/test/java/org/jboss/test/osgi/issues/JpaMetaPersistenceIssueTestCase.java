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
import java.util.Collection;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;

import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

/**
 * A persistence descriptor named in the Meta-Persistence header (other than META-INF/persistence.xml) is ignored: its
 * Persistence Units are never deployed.
 *
 * <h2>Severity</h2>
 * major. Persistence Bundles that keep their descriptors elsewhere (the spec's own example:
 * {@code Meta-Persistence: META-INF/jpa.xml, persistence/jpa.xml}) get no EntityManagerFactory; the bundle starts
 * without any error.
 *
 * <h2>Component</h2>
 * WildFly fork jpa/subsystem {@code PersistenceUnitParseProcessor} (only META-INF/persistence.xml and
 * WEB-INF/classes/META-INF/persistence.xml are parsed); jbosgi wildfly/integration/jpa does not read the header.
 *
 * <h2>Found by</h2>
 * wildfly workstream, Enterprise R7 127.4.3/127.4.4 (Persistence Bundle, Meta-Persistence) spec alignment.
 *
 * <h2>Spec reference</h2>
 * OSGi Enterprise R7 127.4.4: "The header may include zero or more comma-separated jar-paths, each a path to a
 * Persistence Descriptor resource in the bundle. ... The JPA Provider must always include the META-INF/persistence.xml
 * first if it is not one of the listed paths. ... The JPA Provider must then subsequently process META-INF/jpa.xml and
 * the persistence/jpa.xml resources." 127.4.3: "Persistence bundles must be detected by a JPA Provider. The JPA
 * Provider must parse any Persistence Descriptors in these bundles". https://docs.osgi.org/specification/osgi.enterprise/7.0.0/service.jpa.html
 *
 * <h2>Code location</h2>
 * WildFly fork jpa/subsystem/src/main/java/org/jboss/as/jpa/processor/PersistenceUnitParseProcessor.java:63-64 and the
 * parse code from line 90 (fixed descriptor locations); no reference to Meta-Persistence in the fork or in
 * wildfly/integration/jpa.
 *
 * <h2>Root cause</h2>
 * The OSGi JPA integration relies on WildFly's Jakarta EE descriptor discovery, which knows only the standard
 * locations.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=JpaMetaPersistenceIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository. The two -Darq... options start the managed server with port offset 500, so the test cannot connect to another server on 9990 (arquillian.xml has allowConnectingToRunningServer=true); they can be left out when no other server runs.)
 * <h2>Observed</h2>
 * <pre>
 * java.lang.AssertionError: Expected an EntityManagerFactory service for the Persistence Unit jpa-meta-unit of the
 *   descriptor persistence/jpa.xml named in Meta-Persistence, but there is none
 * </pre>
 *
 * <h2>Expected</h2>
 * The unit of persistence/jpa.xml is deployed and its EntityManagerFactory service registered.
 *
 * <h2>Reference behaviour</h2>
 * Not checked (Aries JPA processes the Meta-Persistence paths).
 *
 * <h2>Suggested fix (not applied)</h2>
 * In the OSGi JPA integration, read the Meta-Persistence header of a persistence bundle and add the named descriptors
 * (Bundle.getEntry) to the PersistenceUnitMetadataHolder before the WildFly persistence unit services are installed.
 *
 * <h2>Regression vs WF41</h2>
 * no (no Meta-Persistence support on origin/WF41 either).
 */
@RunWith(Arquillian.class)
public class JpaMetaPersistenceIssueTestCase {

    static final String PERSISTENCE_BUNDLE = "jpa-props-persistence.jar";
    static final String META_BUNDLE = "jpa-meta-persistence.jar";
    static final String UNIT = "jpa-props-unit";
    static final String META_UNIT = "jpa-meta-unit";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "jpa-props-tests");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(EntityManagerFactory.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    static String persistenceXml(String unit) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<persistence xmlns=\"https://jakarta.ee/xml/ns/persistence\" version=\"3.0\">\n"
                + "  <persistence-unit name=\"" + unit + "\">\n    <jta-data-source>java:jboss/datasources/ExampleDS</jta-data-source>\n"
                + "    <class>" + JpaMetaIssueItem.class.getName() + "</class>\n    <exclude-unlisted-classes>true</exclude-unlisted-classes>\n"
                + "    <properties><property name=\"hibernate.hbm2ddl.auto\" value=\"create-drop\"/></properties>\n  </persistence-unit>\n</persistence>\n";
    }

    static JavaArchive persistenceBundle(final String name, String unit, String descriptor, final String metaPersistence) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.addClasses(JpaMetaIssueItem.class);
        archive.addAsResource(new StringAsset(persistenceXml(unit)), descriptor);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addBundleVersion("1.2.3");
                builder.addManifestHeader("Meta-Persistence", metaPersistence);
                builder.addImportPackages("jakarta.persistence");
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = PERSISTENCE_BUNDLE, managed = false, testable = false)
    public static JavaArchive getPersistenceBundle() {
        return persistenceBundle(PERSISTENCE_BUNDLE, UNIT, "META-INF/persistence.xml", "");
    }

    @Deployment(name = META_BUNDLE, managed = false, testable = false)
    public static JavaArchive getMetaBundle() {
        return persistenceBundle(META_BUNDLE, META_UNIT, "persistence/jpa.xml", "persistence/jpa.xml");
    }

    private ServiceReference<?> serviceOf(Bundle bundle, String clazz) throws Exception {
        for (int i = 0; i < 50; i++) {
            ServiceReference<?>[] refs = context.getAllServiceReferences(clazz, null);
            if (refs != null) {
                for (ServiceReference<?> ref : refs)
                    if (ref.getBundle() != null && ref.getBundle().getBundleId() == bundle.getBundleId())
                        return ref;
            }
            Thread.sleep(100);
        }
        return null;
    }

    @Test
    public void testMetaPersistencePath() throws Exception {
        Bundle bundle = context.installBundle(META_BUNDLE, deployer.getDeployment(META_BUNDLE));
        try {
            bundle.start();
            ServiceReference<?> sref = serviceOf(bundle, EntityManagerFactory.class.getName());
            Assert.assertNotNull("Expected an EntityManagerFactory service for the Persistence Unit " + META_UNIT
                    + " of the descriptor persistence/jpa.xml named in Meta-Persistence, but there is none", sref);
        } finally {
            bundle.uninstall();
        }
    }
}

/** Entity of the helper persistence bundles (top-level, so that it does not reference the test class) */
@Entity
class JpaMetaIssueItem {
    @Id
    private long id;

    public JpaMetaIssueItem() {
    }
}
