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
import org.jboss.arquillian.junit.InSequence;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.logging.Logger;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.provision.ProvisionerSupport;
import org.jboss.osgi.provision.XResourceProvisioner;
import org.jboss.osgi.repository.XRepository;
import org.jboss.osgi.resolver.XResource;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.test.osgi.FrameworkUtils;
import org.jboss.test.osgi.ds.sub.c.ServiceC;
import org.jboss.test.osgi.ds.sub.c1.ServiceC1;
import org.jboss.test.osgi.ds.support.AbstractComponent;
import org.jboss.test.osgi.ds.support.InvalidComponentException;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.namespace.IdentityNamespace;
import org.osgi.resource.Resource;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.repository.Repository;
import org.osgi.util.tracker.ServiceTracker;

/**
 * Test recursive service references
 *
 * @author thomas.diesler@jboss.com
 * @since 11-Sep-2013
 */
@Ignore
@RunWith(Arquillian.class)
public class RecursiveReferenceTestCase {

    static final String BUNDLE_C = "bundleC";
    static final String BUNDLE_C1 = "bundleC1";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive dsProvider() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "reference-tests");
        archive.addClasses(FrameworkUtils.class);
        archive.addPackage(AbstractComponent.class.getPackage());
        archive.addAsResource("repository/felix.scr.feature.xml");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addExportPackages(AbstractComponent.class);
                builder.addImportPackages(XRepository.class, Repository.class, XResource.class, Resource.class, XResourceProvisioner.class);
                builder.addImportPackages(ServiceTracker.class, Logger.class);
                builder.addDynamicImportPackages(ServiceC1.class, ServiceC.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Test
    @InSequence(0)
    public void addDeclarativeServicesSupport() throws Exception {
        ProvisionerSupport provisioner = new ProvisionerSupport(context);
        provisioner.populateRepository(getClass().getClassLoader(), "felix.scr.feature");
        provisioner.installCapabilities(IdentityNamespace.IDENTITY_NAMESPACE, "felix.scr.feature");
    }

    @Test
    public void testServiceAccess() throws Exception {
        Bundle bundleC1 = context.installBundle(BUNDLE_C1, deployer.getDeployment(BUNDLE_C1));
        Bundle bundleC = context.installBundle(BUNDLE_C, deployer.getDeployment(BUNDLE_C));
        try {
            bundleC1.start();
            bundleC.start();

            ServiceC srvC = FrameworkUtils.waitForService(context, ServiceC.class);
            Assert.assertEquals("ServiceC#1:ServiceC1#1:Hello", srvC.doStuff("Hello"));

            ServiceC1 srvC1 = srvC.getServiceC1();
            Assert.assertEquals("ServiceC1#1:Hello", srvC1.doStuff("Hello"));

            bundleC1.stop();
            try {
                srvC1.doStuff("Hello");
                Assert.fail("InvalidComponentException expected");
            } catch (InvalidComponentException ex) {
                // expected
            }

            try {
                srvC.getServiceC1();
                Assert.fail("InvalidComponentException expected");
            } catch (InvalidComponentException ex) {
                // expected
            }

            bundleC1.start();
            srvC = FrameworkUtils.waitForService(context, ServiceC.class);
            Assert.assertEquals("ServiceC#2:ServiceC1#2:Hello", srvC.doStuff("Hello"));

            srvC1 = FrameworkUtils.waitForService(context, ServiceC1.class);
            Assert.assertEquals("ServiceC1#2:Hello", srvC1.doStuff("Hello"));
        } finally {
            bundleC.uninstall();
            bundleC1.uninstall();
        }
    }

    @Deployment(name = BUNDLE_C, managed = false, testable = false)
    public static JavaArchive testBundleC() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, BUNDLE_C);
        archive.addClasses(ServiceC.class);
        archive.addAsResource("OSGI-INF/org.jboss.test.osgi.ds.sub.c.ServiceC.xml");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addManifestHeader("Service-Component", "OSGI-INF/org.jboss.test.osgi.ds.sub.c.ServiceC.xml");
                builder.addExportPackages(ServiceC.class);
                builder.addImportPackages(AbstractComponent.class, ComponentContext.class, Logger.class);
                builder.addImportPackages(ServiceC1.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = BUNDLE_C1, managed = false, testable = false)
    public static JavaArchive testBundleC1() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, BUNDLE_C1);
        archive.addClasses(ServiceC1.class);
        archive.addAsResource("OSGI-INF/org.jboss.test.osgi.ds.sub.c1.ServiceC1.xml");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addManifestHeader("Service-Component", "OSGI-INF/org.jboss.test.osgi.ds.sub.c1.ServiceC1.xml");
                builder.addExportPackages(ServiceC1.class);
                builder.addImportPackages(AbstractComponent.class, ComponentContext.class, Logger.class);
                return builder.openStream();
            }
        });
        return archive;
    }
}