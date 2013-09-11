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
import org.jboss.test.osgi.ds.sub.ServiceA;
import org.jboss.test.osgi.ds.sub.ServiceT;
import org.jboss.test.osgi.scr.AbstractComponent;
import org.jboss.test.osgi.scr.InvalidComponentException;
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
 * Example for Declarative Services
 *
 * @author thomas.diesler@jboss.com
 * @since 11-Sep-2013
 */
@RunWith(Arquillian.class)
public class DeclarativeServiceReferenceTestCase {

    static final String BUNDLE_A = "bundleA";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive dsProvider() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "reference-tests");
        archive.addClasses(FrameworkUtils.class);
        archive.addPackage(ServiceT.class.getPackage());
        archive.addPackage(AbstractComponent.class.getPackage());
        archive.addAsResource("repository/felix.scr.feature.xml");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addExportPackages(ServiceT.class);
                builder.addImportPackages(XRepository.class, Repository.class, XResource.class, Resource.class, XResourceProvisioner.class);
                builder.addImportPackages(ServiceTracker.class, Logger.class);
                builder.addDynamicImportPackages(ComponentContext.class);
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
    @InSequence(1)
    public void testActiveServices() throws Exception {
        InputStream input = deployer.getDeployment(BUNDLE_A);
        Bundle bundle = context.installBundle(BUNDLE_A, input);
        try {
            bundle.start();

            ServiceT srvT = FrameworkUtils.waitForService(context, ServiceT.class);
            Assert.assertNotNull("ServiceT#1:ServiceA#1:Hello", srvT.doStuff("Hello"));

            ServiceA srvA = srvT.getServiceA();
            Assert.assertNotNull("ServiceA#1:Hello", srvA.doStuff("Hello"));

            ServiceReference<ServiceT> srefT = context.getServiceReference(ServiceT.class);
            Assert.assertSame(srvT, context.getService(srefT));

            ServiceReference<ServiceA> srefA = context.getServiceReference(ServiceA.class);
            Assert.assertSame(srvA, context.getService(srefA));
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    @InSequence(1)
    public void testImmediateService() throws Exception {
        InputStream input = deployer.getDeployment(BUNDLE_A);
        Bundle bundle = context.installBundle(BUNDLE_A, input);
        try {
            bundle.start();

            ServiceT srvT = FrameworkUtils.waitForService(context, ServiceT.class);
            Assert.assertNotNull("ServiceT#1:ServiceA#1:Hello", srvT.doStuff("Hello"));

            ServiceA srvA = srvT.getServiceA();
            Assert.assertNotNull("ServiceA#1:Hello", srvA.doStuff("Hello"));

            srvA.getComponentContext().getComponentInstance().dispose();
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
        } finally {
            bundle.uninstall();
        }
    }

    @Deployment(name = BUNDLE_A, managed = false, testable = false)
    public static JavaArchive testBundle() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, BUNDLE_A);
        archive.addAsResource("OSGI-INF/serviceComponents.xml");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addManifestHeader("Service-Component", "OSGI-INF/serviceComponents.xml");
                builder.addImportPackages(ServiceT.class);
                return builder.openStream();
            }
        });
        return archive;
    }
}