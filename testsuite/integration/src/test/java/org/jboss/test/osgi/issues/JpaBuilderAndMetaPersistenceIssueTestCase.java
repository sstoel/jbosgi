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
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;

import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.ClassAsset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

/**
 * The EntityManagerFactoryBuilder of a persistence bundle returns the EntityManagerFactory of the container: the close
 * of a client closes it for the bundle and the registered service. The builder has the API 1.0 only. The
 * Meta-Persistence header of a WAB, and its {@code jar!/path} entries, are ignored.
 *
 * <h3>Severity</h3>
 * minor. A client that closes the factory it created breaks the persistence unit of the bundle; persistence units of a
 * WAB or in an embedded JAR are not found.
 *
 * <h3>Component</h3>
 * jbosgi/wildfly/integration/jpa: {@code PersistenceUnitProcessor}; WildFly fork, jpa: {@code PersistenceUnitParseProcessor}.
 *
 * <h3>Found by</h3>
 * R8 fix review, residuals of wildfly/19 (umb-a-H07) and wildfly/20 (umb-b-04, umb-a-T04).
 *
 * <h3>Spec reference</h3>
 * OSGi Enterprise R8 127.4.2 (Meta-Persistence, jar-path), 127.4.6 (EntityManagerFactoryBuilder 1.1), 127.4.9 (close).
 *
 * <h3>Code location</h3>
 * PersistenceUnitProcessor.java:165-173 (f49fa4d9); wildfly jpa/subsystem PersistenceUnitParseProcessor.java:113,
 * 211-235 (90639e0510).
 *
 * <h3>Root cause</h3>
 * The builder returned the factory of the container. The header was read for JAR deployments only, without the
 * embedded JARs.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi/testsuite/integration
 * mvn test -Dtest=JpaBuilderAndMetaPersistenceIssueTestCase
 * </pre>
 *
 * <h3>Expected</h3>
 * The factory of the builder closes on its own, the service stays open; the persistence units of the WAB and of the
 * embedded JAR are registered.
 *
 * <h3>Regression vs WF41</h3>
 * no.
 */
@RunWith(Arquillian.class)
public class JpaBuilderAndMetaPersistenceIssueTestCase {

    static final String BUILDER_BUNDLE = "jpa-builder-persistence.jar";
    static final String EMBEDDED_BUNDLE = "jpa-embedded-persistence.jar";
    static final String META_WAB = "jpa-meta-wab.war";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    ManagementClient managementClient;

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "jpa-builder-tests");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(EntityManagerFactory.class);
                builder.addImportPackages(ManagementClient.class, ModelControllerClient.class, ServerDeploymentHelper.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    static String persistenceXml(String unit) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<persistence xmlns=\"https://jakarta.ee/xml/ns/persistence\" version=\"3.0\">\n"
                + "  <persistence-unit name=\"" + unit + "\">\n    <jta-data-source>java:jboss/datasources/ExampleDS</jta-data-source>\n"
                + "    <class>" + JpaResidualItem.class.getName() + "</class>\n    <exclude-unlisted-classes>true</exclude-unlisted-classes>\n"
                + "    <properties><property name=\"hibernate.hbm2ddl.auto\" value=\"create-drop\"/></properties>\n  </persistence-unit>\n</persistence>\n";
    }

    private static Asset manifest(final String symbolicName, final String metaPersistence, final String bundleClassPath, final String contextPath) {
        return new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(symbolicName);
                builder.addBundleManifestVersion(2);
                builder.addManifestHeader("Meta-Persistence", metaPersistence);
                if (bundleClassPath != null)
                    builder.addBundleClasspath(bundleClassPath);
                if (contextPath != null)
                    builder.addManifestHeader("Web-ContextPath", contextPath);
                builder.addImportPackages("jakarta.persistence");
                return builder.openStream();
            }
        };
    }

    @Deployment(name = BUILDER_BUNDLE, managed = false, testable = false)
    public static JavaArchive getBuilderBundle() {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, BUILDER_BUNDLE);
        archive.addClasses(JpaResidualItem.class);
        archive.addAsResource(new StringAsset(persistenceXml("jpa-builder-unit")), "META-INF/persistence.xml");
        archive.setManifest(manifest("jpa-builder-persistence", "", null, null));
        return archive;
    }

    // The descriptor and the entity are in an embedded JAR: Meta-Persistence: lib/units.jar!/META-INF/persistence.xml
    @Deployment(name = EMBEDDED_BUNDLE, managed = false, testable = false)
    public static JavaArchive getEmbeddedBundle() {
        JavaArchive units = ShrinkWrap.create(JavaArchive.class, "units.jar");
        units.addClasses(JpaResidualItem.class);
        units.addAsResource(new StringAsset(persistenceXml("jpa-embedded-unit")), "META-INF/persistence.xml");
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, EMBEDDED_BUNDLE);
        archive.add(units, "lib", ZipExporter.class);
        archive.setManifest(manifest("jpa-embedded-persistence", "lib/units.jar!/META-INF/persistence.xml", ".,lib/units.jar", null));
        return archive;
    }

    // A WAB that is a persistence bundle: the descriptor of its Meta-Persistence header, the entity at the bundle root
    @Deployment(name = META_WAB, managed = false, testable = false)
    public static WebArchive getMetaWab() {
        WebArchive war = ShrinkWrap.create(WebArchive.class, META_WAB);
        war.add(new ClassAsset(JpaResidualItem.class), JpaResidualItem.class.getName().replace('.', '/') + ".class");
        war.add(new StringAsset(persistenceXml("jpa-wab-unit")), "persistence/jpa.xml");
        war.add(new StringAsset("static"), "static.txt");
        war.setManifest(manifest("jpa-meta-wab", "persistence/jpa.xml", ".", "/jpa-meta-wab"));
        return war;
    }

    @Test
    public void testBuilderFactoryClosesOnItsOwn() throws Exception {
        Bundle bundle = context.installBundle(BUILDER_BUNDLE, deployer.getDeployment(BUILDER_BUNDLE));
        try {
            bundle.start();
            ServiceReference<?> builderRef = serviceOf(bundle, "org.osgi.service.jpa.EntityManagerFactoryBuilder");
            ServiceReference<?> emfRef = serviceOf(bundle, EntityManagerFactory.class.getName());
            Assert.assertNotNull("Precondition: the builder service", builderRef);
            Assert.assertNotNull("Precondition: the EntityManagerFactory service", emfRef);
            Object builder = context.getService(builderRef);
            Class<?> builderType = getInterface(builder, "org.osgi.service.jpa.EntityManagerFactoryBuilder");
            Method create = builderType.getMethod("createEntityManagerFactory", Map.class);
            EntityManagerFactory created = (EntityManagerFactory) create.invoke(builder, new HashMap<String, Object>());
            created.close();
            Assert.assertFalse("The factory of the builder is closed", created.isOpen());

            EntityManagerFactory service = (EntityManagerFactory) context.getService(emfRef);
            Assert.assertTrue("The EntityManagerFactory service stays open after a client closed the factory of the builder", service.isOpen());
            EntityManager em = service.createEntityManager();
            em.close();
            service.close();
            Assert.assertTrue("A close of the service does not close the factory of the container", service.isOpen());

            Object providerName = builderType.getMethod("getPersistenceProviderName").invoke(builder);
            Assert.assertEquals("The provider name of the builder (1.1) is the provider property", builderRef.getProperty("osgi.unit.provider"), providerName);
            Assert.assertNotNull("The provider bundle of the builder (1.1)", builderType.getMethod("getPersistenceProviderBundle").invoke(builder));
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testDescriptorInEmbeddedJar() throws Exception {
        Bundle bundle = context.installBundle(EMBEDDED_BUNDLE, deployer.getDeployment(EMBEDDED_BUNDLE));
        try {
            bundle.start();
            ServiceReference<?> sref = serviceOf(bundle, EntityManagerFactory.class.getName());
            Assert.assertNotNull("The persistence unit of the embedded JAR is registered", sref);
            Assert.assertEquals("jpa-embedded-unit", sref.getProperty("osgi.unit.name"));
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testMetaPersistenceOfWab() throws Exception {
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        server.deploy(META_WAB, deployer.getDeployment(META_WAB));
        try {
            Bundle bundle = null;
            for (Bundle aux : context.getBundles()) {
                if ("jpa-meta-wab".equals(aux.getSymbolicName()))
                    bundle = aux;
            }
            Assert.assertNotNull("Precondition: the WAB is installed", bundle);
            ServiceReference<?> sref = serviceOf(bundle, EntityManagerFactory.class.getName());
            Assert.assertNotNull("The persistence unit of the Meta-Persistence header of the WAB is registered", sref);
            Assert.assertEquals("jpa-wab-unit", sref.getProperty("osgi.unit.name"));
        } finally {
            server.undeploy(META_WAB);
        }
    }

    private static Class<?> getInterface(Object service, String name) {
        for (Class<?> type = service.getClass(); type != null; type = type.getSuperclass()) {
            for (Class<?> iface : type.getInterfaces()) {
                if (iface.getName().equals(name))
                    return iface;
            }
        }
        throw new IllegalStateException("Not a " + name + ": " + service);
    }

    private ServiceReference<?> serviceOf(Bundle bundle, String clazz) throws Exception {
        for (int i = 0; i < 100; i++) {
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
}

/** Entity of the helper persistence bundles (top-level, so that it does not reference the test class) */
@Entity
class JpaResidualItem {
    @Id
    private long id;

    public JpaResidualItem() {
    }
}
