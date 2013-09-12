/*
 * JBoss, Home of Professional Open Source
 * Copyright 2005, JBoss Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
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
package org.jboss.test.osgi.ds;

import java.io.InputStream;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.logging.Logger;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.test.osgi.ConfigurationAdminSupport;
import org.jboss.test.osgi.FrameworkUtils;
import org.jboss.test.osgi.ds.sub.d.ServiceD;
import org.jboss.test.osgi.ds.sub.d1.ServiceD1;
import org.jboss.test.osgi.ds.support.AbstractComponent;
import org.jboss.test.osgi.ds.support.InvalidComponentException;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.util.tracker.ServiceTracker;

/**
 * Test static service references
 *
 * @author thomas.diesler@jboss.com
 * @since 11-Sep-2013
 */
@RunWith(Arquillian.class)
public class ConfiguredReferenceTestCase {

    static final String BUNDLE_D = "bundleD";
    static final String BUNDLE_D1 = "bundleD1";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive dsProvider() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "reference-tests");
        archive.addClasses(FrameworkUtils.class, ConfigurationAdminSupport.class);
        archive.addPackage(AbstractComponent.class.getPackage());
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addExportPackages(AbstractComponent.class);
                builder.addImportPackages(ServiceTracker.class, ConfigurationAdmin.class, Logger.class);
                builder.addDynamicImportPackages(ServiceD1.class, ServiceD.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Test
    public void testServiceAccess() throws Exception {
        Bundle bundleD1 = context.installBundle(BUNDLE_D1, deployer.getDeployment(BUNDLE_D1));
        Bundle bundleD = context.installBundle(BUNDLE_D, deployer.getDeployment(BUNDLE_D));
        try {
            bundleD1.start();
            bundleD.start();

            ServiceD srvD = FrameworkUtils.waitForService(context, ServiceD.class);
            Assert.assertEquals("ServiceD#1:ServiceD1#1:null:Hello", srvD.doStuff("Hello"));

            ServiceD1 srvD1 = srvD.getServiceD1();
            Assert.assertEquals("ServiceD1#1:null:Hello", srvD1.doStuff("Hello"));

            ConfigurationAdmin configAdmin = ConfigurationAdminSupport.getConfigurationAdmin(bundleD1);
            Configuration config = configAdmin.getConfiguration(ServiceD1.class.getName());
            Assert.assertNotNull("Config not null", config);
            Assert.assertNull("Config is empty, but was: " + config.getProperties(), config.getProperties());

            CountDownLatch latch = srvD1.getDeactivateLatch();

            Dictionary<String, String> configProps = new Hashtable<String, String>();
            configProps.put("foo", "bar");
            config.update(configProps);

            latch.await(4000, TimeUnit.MILLISECONDS);

            try {
                srvD1.doStuff("Hello");
                Assert.fail("InvalidComponentException expected");
            } catch (InvalidComponentException ex) {
                // expected
            }

            srvD1 = FrameworkUtils.waitForService(context, ServiceD1.class);
            Assert.assertEquals("ServiceD1#2:bar:Hello", srvD1.doStuff("Hello"));

        } finally {
            bundleD.uninstall();
            bundleD1.uninstall();
        }
    }

    @Deployment(name = BUNDLE_D, managed = false, testable = false)
    public static JavaArchive testBundleD() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, BUNDLE_D);
        archive.addClasses(ServiceD.class);
        archive.addAsResource("OSGI-INF/org.jboss.test.osgi.ds.sub.d.ServiceD.xml");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addManifestHeader("Service-Component", "OSGI-INF/org.jboss.test.osgi.ds.sub.d.ServiceD.xml");
                builder.addExportPackages(ServiceD.class);
                builder.addImportPackages(AbstractComponent.class, ComponentContext.class, Logger.class);
                builder.addImportPackages(ServiceD1.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = BUNDLE_D1, managed = false, testable = false)
    public static JavaArchive testBundleD1() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, BUNDLE_D1);
        archive.addClasses(ServiceD1.class);
        archive.addAsResource("OSGI-INF/org.jboss.test.osgi.ds.sub.d1.ServiceD1.xml");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addManifestHeader("Service-Component", "OSGI-INF/org.jboss.test.osgi.ds.sub.d1.ServiceD1.xml");
                builder.addExportPackages(ServiceD1.class);
                builder.addImportPackages(AbstractComponent.class, ComponentContext.class, Logger.class);
                return builder.openStream();
            }
        });
        return archive;
    }
}