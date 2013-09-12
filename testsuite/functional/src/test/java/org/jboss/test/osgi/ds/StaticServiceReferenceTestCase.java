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
import org.jboss.test.osgi.ds.suba.ServiceA;
import org.jboss.test.osgi.ds.subt.ServiceT;
import org.jboss.test.osgi.ds.support.AbstractComponent;
import org.jboss.test.osgi.ds.support.InvalidComponentException;
import org.junit.Assert;
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
 * Test static service references
 *
 * @author thomas.diesler@jboss.com
 * @since 11-Sep-2013
 */
@RunWith(Arquillian.class)
public class StaticServiceReferenceTestCase {

    static final String BUNDLE_T = "bundleT";
    static final String BUNDLE_A = "bundleA";

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
                builder.addDynamicImportPackages(ServiceA.class, ServiceT.class);
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
        Bundle bundleA = context.installBundle(BUNDLE_A, deployer.getDeployment(BUNDLE_A));
        Bundle bundleT = context.installBundle(BUNDLE_T, deployer.getDeployment(BUNDLE_T));
        try {
            bundleA.start();
            bundleT.start();

            ServiceT srvT = FrameworkUtils.waitForService(context, ServiceT.class);
            Assert.assertEquals("ServiceT#1:ServiceA#1:Hello", srvT.doStuff("Hello"));

            ServiceA srvA = srvT.getServiceA();
            Assert.assertEquals("ServiceA#1:Hello", srvA.doStuff("Hello"));

            ServiceReference<ServiceT> srefT = context.getServiceReference(ServiceT.class);
            Assert.assertSame(srvT, context.getService(srefT));

            ServiceReference<ServiceA> srefA = context.getServiceReference(ServiceA.class);
            Assert.assertSame(srvA, context.getService(srefA));

            bundleA.stop();
            try {
                srvA.doStuff("Hello");
                Assert.fail("InvalidComponentException expected");
            } catch (InvalidComponentException ex) {
                // expected
            }

            try {
                srvT.getServiceA();
                Assert.fail("InvalidComponentException expected");
            } catch (InvalidComponentException ex) {
                // expected
            }

            bundleA.start();
            srvT = FrameworkUtils.waitForService(context, ServiceT.class);
            Assert.assertEquals("ServiceT#2:ServiceA#2:Hello", srvT.doStuff("Hello"));

            srvA = FrameworkUtils.waitForService(context, ServiceA.class);
            Assert.assertEquals("ServiceA#2:Hello", srvA.doStuff("Hello"));
        } finally {
            bundleT.uninstall();
            bundleA.uninstall();
        }
    }

    @Deployment(name = BUNDLE_T, managed = false, testable = false)
    public static JavaArchive testBundleT() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, BUNDLE_T);
        archive.addClasses(ServiceT.class);
        archive.addAsResource("OSGI-INF/org.jboss.test.osgi.ds.subt.ServiceT.xml");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addManifestHeader("Service-Component", "OSGI-INF/org.jboss.test.osgi.ds.subt.ServiceT.xml");
                builder.addExportPackages(ServiceT.class);
                builder.addImportPackages(AbstractComponent.class, ComponentContext.class, Logger.class);
                builder.addImportPackages(ServiceA.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = BUNDLE_A, managed = false, testable = false)
    public static JavaArchive testBundleA() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, BUNDLE_A);
        archive.addClasses(ServiceA.class);
        archive.addAsResource("OSGI-INF/org.jboss.test.osgi.ds.suba.ServiceA.xml");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addManifestHeader("Service-Component", "OSGI-INF/org.jboss.test.osgi.ds.suba.ServiceA.xml");
                builder.addExportPackages(ServiceA.class);
                builder.addImportPackages(AbstractComponent.class, ComponentContext.class, Logger.class);
                return builder.openStream();
            }
        });
        return archive;
    }
}