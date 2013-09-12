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

import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.logging.Logger;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.test.osgi.FrameworkUtils;
import org.jboss.test.osgi.ds.sub.b.ServiceB;
import org.jboss.test.osgi.ds.sub.b1.ServiceB1;
import org.jboss.test.osgi.ds.support.AbstractComponent;
import org.jboss.test.osgi.ds.support.InvalidComponentException;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.ComponentContext;
import org.osgi.util.tracker.ServiceTracker;

/**
 * Test dynamic service references
 *
 * @author thomas.diesler@jboss.com
 * @since 11-Sep-2013
 */
@RunWith(Arquillian.class)
public class DynamicReferenceTestCase {

    static final String BUNDLE_B = "bundleB";
    static final String BUNDLE_B1 = "bundleB1";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive dsProvider() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "reference-tests");
        archive.addClasses(FrameworkUtils.class);
        archive.addPackage(AbstractComponent.class.getPackage());
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addExportPackages(AbstractComponent.class);
                builder.addImportPackages(ServiceTracker.class, Logger.class);
                builder.addDynamicImportPackages(ServiceB1.class, ServiceB.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Test
    public void testServiceAccess() throws Exception {
        Bundle bundleB1 = context.installBundle(BUNDLE_B1, deployer.getDeployment(BUNDLE_B1));
        Bundle bundleB = context.installBundle(BUNDLE_B, deployer.getDeployment(BUNDLE_B));
        try {
            bundleB1.start();
            bundleB.start();

            ServiceB srvB = FrameworkUtils.waitForService(context, ServiceB.class);
            Assert.assertEquals("ServiceB#1:ServiceB1#1:Hello", srvB.doStuff("Hello"));

            ServiceB1 srvB1 = srvB.getServiceB1();
            Assert.assertEquals("ServiceB1#1:Hello", srvB1.doStuff("Hello"));

            bundleB1.stop();
            try {
                srvB1.doStuff("Hello");
                Assert.fail("InvalidComponentException expected");
            } catch (InvalidComponentException ex) {
                // expected
            }

            try {
                srvB.getServiceB1();
                Assert.fail("InvalidComponentException expected");
            } catch (InvalidComponentException ex) {
                // expected
            }

            // The dynamic policy is ignored, we still get a new instance of ServiceB

            bundleB1.start();
            srvB = FrameworkUtils.waitForService(context, ServiceB.class);
            Assert.assertEquals("ServiceB#2:ServiceB1#2:Hello", srvB.doStuff("Hello"));

            srvB1 = FrameworkUtils.waitForService(context, ServiceB1.class);
            Assert.assertEquals("ServiceB1#2:Hello", srvB1.doStuff("Hello"));
        } finally {
            bundleB.uninstall();
            bundleB1.uninstall();
        }
    }

    @Deployment(name = BUNDLE_B, managed = false, testable = false)
    public static JavaArchive testBundleB() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, BUNDLE_B);
        archive.addClasses(ServiceB.class);
        archive.addAsResource("OSGI-INF/org.jboss.test.osgi.ds.sub.b.ServiceB.xml");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addManifestHeader("Service-Component", "OSGI-INF/org.jboss.test.osgi.ds.sub.b.ServiceB.xml");
                builder.addExportPackages(ServiceB.class);
                builder.addImportPackages(AbstractComponent.class, ComponentContext.class, Logger.class);
                builder.addImportPackages(ServiceB1.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = BUNDLE_B1, managed = false, testable = false)
    public static JavaArchive testBundleB1() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, BUNDLE_B1);
        archive.addClasses(ServiceB1.class);
        archive.addAsResource("OSGI-INF/org.jboss.test.osgi.ds.sub.b1.ServiceB1.xml");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addManifestHeader("Service-Component", "OSGI-INF/org.jboss.test.osgi.ds.sub.b1.ServiceB1.xml");
                builder.addExportPackages(ServiceB1.class);
                builder.addImportPackages(AbstractComponent.class, ComponentContext.class, Logger.class);
                return builder.openStream();
            }
        });
        return archive;
    }
}