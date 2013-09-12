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
import org.jboss.test.osgi.ds.sub.a.ServiceA;
import org.jboss.test.osgi.ds.sub.a1.ServiceA1;
import org.jboss.test.osgi.ds.support.AbstractComponent;
import org.jboss.test.osgi.ds.support.InvalidComponentException;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentContext;
import org.osgi.util.tracker.ServiceTracker;

/**
 * Test static service references
 *
 * @author thomas.diesler@jboss.com
 * @since 11-Sep-2013
 */
@RunWith(Arquillian.class)
public class StaticReferenceTestCase {

    static final String BUNDLE_A = "bundleA";
    static final String BUNDLE_A1 = "bundleA1";

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
                builder.addDynamicImportPackages(ServiceA1.class, ServiceA.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Test
    public void testServiceAccess() throws Exception {
        Bundle bundleA1 = context.installBundle(BUNDLE_A1, deployer.getDeployment(BUNDLE_A1));
        Bundle bundleA = context.installBundle(BUNDLE_A, deployer.getDeployment(BUNDLE_A));
        try {
            bundleA1.start();
            bundleA.start();

            ServiceA srvA = FrameworkUtils.waitForService(context, ServiceA.class);
            Assert.assertEquals("ServiceA#1:ServiceA1#1:Hello", srvA.doStuff("Hello"));

            ServiceA1 srvA1 = srvA.getServiceA1();
            Assert.assertEquals("ServiceA1#1:Hello", srvA1.doStuff("Hello"));

            ServiceReference<ServiceA> srefT = context.getServiceReference(ServiceA.class);
            Assert.assertSame(srvA, context.getService(srefT));

            ServiceReference<ServiceA1> srefA = context.getServiceReference(ServiceA1.class);
            Assert.assertSame(srvA1, context.getService(srefA));

            bundleA1.stop();
            try {
                srvA1.doStuff("Hello");
                Assert.fail("InvalidComponentException expected");
            } catch (InvalidComponentException ex) {
                // expected
            }

            try {
                srvA.getServiceA1();
                Assert.fail("InvalidComponentException expected");
            } catch (InvalidComponentException ex) {
                // expected
            }

            bundleA1.start();
            srvA = FrameworkUtils.waitForService(context, ServiceA.class);
            Assert.assertEquals("ServiceA#2:ServiceA1#2:Hello", srvA.doStuff("Hello"));

            srvA1 = FrameworkUtils.waitForService(context, ServiceA1.class);
            Assert.assertEquals("ServiceA1#2:Hello", srvA1.doStuff("Hello"));
        } finally {
            bundleA.uninstall();
            bundleA1.uninstall();
        }
    }

    @Deployment(name = BUNDLE_A, managed = false, testable = false)
    public static JavaArchive testBundleA() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, BUNDLE_A);
        archive.addClasses(ServiceA.class);
        archive.addAsResource("OSGI-INF/org.jboss.test.osgi.ds.sub.a.ServiceA.xml");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addManifestHeader("Service-Component", "OSGI-INF/org.jboss.test.osgi.ds.sub.a.ServiceA.xml");
                builder.addExportPackages(ServiceA.class);
                builder.addImportPackages(AbstractComponent.class, ComponentContext.class, Logger.class);
                builder.addImportPackages(ServiceA1.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = BUNDLE_A1, managed = false, testable = false)
    public static JavaArchive testBundleA1() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, BUNDLE_A1);
        archive.addClasses(ServiceA1.class);
        archive.addAsResource("OSGI-INF/org.jboss.test.osgi.ds.sub.a1.ServiceA1.xml");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addManifestHeader("Service-Component", "OSGI-INF/org.jboss.test.osgi.ds.sub.a1.ServiceA1.xml");
                builder.addExportPackages(ServiceA1.class);
                builder.addImportPackages(AbstractComponent.class, ComponentContext.class, Logger.class);
                return builder.openStream();
            }
        });
        return archive;
    }
}