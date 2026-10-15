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
 * The EntityManagerFactory service of a Persistence Unit carries wrong JPA service properties (osgi.unit.name is the
 * scoped "deployment#unit" name, osgi.unit.version and osgi.unit.provider are the literal "unknown"), and no
 * EntityManagerFactoryBuilder service is registered.
 *
 * <h2>Severity</h2>
 * major. Client bundles find their Persistence Unit with the filter the spec prescribes,
 * {@code (osgi.unit.name=<unit>)} and optionally {@code (osgi.unit.version=...)}: neither ever matches, so clients
 * written against the JPA Service specification never get the EntityManagerFactory; clients that need the
 * EntityManagerFactoryBuilder (incomplete units, runtime properties) have nothing to look up.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/integration/jpa, {@code org.jboss.as.osgi.jpa.PersistenceUnitProcessor.EntityManagerFactoryRegistration}.
 *
 * <h2>Found by</h2>
 * wildfly workstream, Enterprise R7 127 (JPA Service) spec alignment.
 *
 * <h2>Spec reference</h2>
 * OSGi Enterprise R7 127.4.5 Service Registrations: "each JPA Service must have the following service properties:
 * osgi.unit.name - (String) The name of the Persistence Unit. This property corresponds to the name attribute of the
 * persistence-unit element in the Persistence Descriptor. ... osgi.unit.version - (String) The version of the
 * Persistence Bundle, as specified in Bundle-Version header ... osgi.unit.provider - (String) The JPA Provider
 * implementation class name that registered the service." 127.4.6: "Once the Persistence Bundle is ready, a JPA
 * Provider must register an Entity Manager Factory Builder service for each assigned Persistence Unit from that
 * Persistence Bundle." https://docs.osgi.org/specification/osgi.enterprise/7.0.0/service.jpa.html
 *
 * <h2>Code location</h2>
 * wildfly/integration/jpa/src/main/java/org/jboss/as/osgi/jpa/PersistenceUnitProcessor.java:116-127:
 * {@code properties.put(JPA_UNIT_NAME, puService.getScopedPersistenceUnitName()); // [TODO] unit version/provider
 * properties.put(JPA_UNIT_VERSION, "unknown"); properties.put(JPA_UNIT_PROVIDER, "unknown");}; only the
 * EntityManagerFactory is registered, no EntityManagerFactoryBuilder.
 *
 * <h2>Root cause</h2>
 * Left as TODO since the original implementation; the WildFly scoped name is used instead of the unit name.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=JpaServicePropertiesIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository. The two -Darq... options start the managed server with port offset 500, so the test cannot connect to another server on 9990 (arquillian.xml has allowConnectingToRunningServer=true); they can be left out when no other server runs.)
 * <h2>Observed</h2>
 * <pre>
 * testEntityManagerFactoryServiceProperties: Expected osgi.unit.name=jpa-props-unit, osgi.unit.version=1.2.3 and
 *   osgi.unit.provider=&lt;provider class name&gt;, but got osgi.unit.name=jpa-props-persistence.jar#jpa-props-unit,
 *   osgi.unit.version=unknown, osgi.unit.provider=unknown
 * testEntityManagerFactoryBuilderService: Expected an org.osgi.service.jpa.EntityManagerFactoryBuilder service for the
 *   Persistence Unit jpa-props-unit, but there is none
 * </pre>
 *
 * <h2>Expected</h2>
 * osgi.unit.name = the persistence-unit name, osgi.unit.version = the Bundle-Version, osgi.unit.provider = e.g.
 * org.hibernate.jpa.HibernatePersistenceProvider; an EntityManagerFactoryBuilder service with the same properties.
 *
 * <h2>Reference behaviour</h2>
 * Not checked (Aries JPA registers both services with these properties).
 *
 * <h2>Suggested fix (not applied)</h2>
 * Use the unit name (PersistenceUnitMetadata.getPersistenceUnitName()), the bundle's Bundle-Version and the provider
 * class name (PersistenceUnitMetadata.getPersistenceProviderClassName() or the provider's class); register an
 * EntityManagerFactoryBuilder backed by the WildFly persistence unit service.
 *
 * <h2>Regression vs WF41</h2>
 * no (PersistenceUnitProcessor unchanged since the WF30 port, e4d06ba7).
 */
@RunWith(Arquillian.class)
public class JpaServicePropertiesIssueTestCase {

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
                + "    <class>" + JpaIssueItem.class.getName() + "</class>\n    <exclude-unlisted-classes>true</exclude-unlisted-classes>\n"
                + "    <properties><property name=\"hibernate.hbm2ddl.auto\" value=\"create-drop\"/></properties>\n  </persistence-unit>\n</persistence>\n";
    }

    static JavaArchive persistenceBundle(final String name, String unit, String descriptor, final String metaPersistence) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.addClasses(JpaIssueItem.class);
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
    public void testEntityManagerFactoryServiceProperties() throws Exception {
        Bundle bundle = context.installBundle(PERSISTENCE_BUNDLE, deployer.getDeployment(PERSISTENCE_BUNDLE));
        try {
            bundle.start();
            ServiceReference<?> sref = serviceOf(bundle, EntityManagerFactory.class.getName());
            Assert.assertNotNull("Precondition: EntityManagerFactory service of the persistence bundle", sref);
            Object name = sref.getProperty("osgi.unit.name");
            Object version = sref.getProperty("osgi.unit.version");
            Object provider = sref.getProperty("osgi.unit.provider");
            String props = "osgi.unit.name=" + name + ", osgi.unit.version=" + version + ", osgi.unit.provider=" + provider;
            Assert.assertTrue("Expected osgi.unit.name=" + UNIT + ", osgi.unit.version=1.2.3 and osgi.unit.provider=<provider class name>, but got " + props,
                    UNIT.equals(name) && "1.2.3".equals(version) && provider instanceof String && ((String) provider).contains("."));
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testEntityManagerFactoryBuilderService() throws Exception {
        Bundle bundle = context.installBundle(PERSISTENCE_BUNDLE, deployer.getDeployment(PERSISTENCE_BUNDLE));
        try {
            bundle.start();
            ServiceReference<?> sref = serviceOf(bundle, "org.osgi.service.jpa.EntityManagerFactoryBuilder");
            Assert.assertNotNull("Expected an org.osgi.service.jpa.EntityManagerFactoryBuilder service for the Persistence Unit " + UNIT + ", but there is none", sref);
        } finally {
            bundle.uninstall();
        }
    }
}

/** Entity of the helper persistence bundles (top-level, so that it does not reference the test class) */
@Entity
class JpaIssueItem {
    @Id
    private long id;

    public JpaIssueItem() {
    }
}
