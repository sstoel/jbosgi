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
import org.jboss.test.osgi.ds.sub.a1.ServiceA1;
import org.jboss.test.osgi.ds.support.AbstractComponent;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentContext;
import org.osgi.util.tracker.ServiceTracker;

/**
 * Verifies that a service listener can see activated components.
 *
 * @author thomas.diesler@jboss.com
 * @since 25-Sep-2013
 */
@RunWith(Arquillian.class)
public class ServiceListenerTestCase {

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
                builder.addDynamicImportPackages(ServiceA1.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Test
    public void testServiceAccess() throws Exception {
        Bundle bundleA1 = context.installBundle(BUNDLE_A1, deployer.getDeployment(BUNDLE_A1));
        try {
            final StringBuffer result = new StringBuffer();
            ServiceListener listener = new ServiceListener() {
                @Override
                public void serviceChanged(ServiceEvent event) {
                    if (ServiceEvent.REGISTERED == event.getType()) {
                        ServiceReference<?> sref = event.getServiceReference();
                        ServiceA1 srvA1 = (ServiceA1) context.getService(sref);
                        result.append(srvA1.doStuff("Hello"));
                    }
                    else if (ServiceEvent.UNREGISTERING == event.getType()) {
                        ServiceReference<?> sref = event.getServiceReference();
                        ServiceA1 srvA1 = (ServiceA1) context.getService(sref);
                        result.append(srvA1.doStuff("Hello"));
                    }
                }
            };
            context.addServiceListener(listener, "(objectClass=" + ServiceA1.class.getName() + ")");
            bundleA1.start();

            Assert.assertEquals("ServiceA1#1:Hello", result.toString());

            result.setLength(0);
            Assert.assertEquals("", result.toString());

            bundleA1.stop();

            Assert.assertEquals("ServiceA1#1:Hello", result.toString());
        } finally {
            bundleA1.uninstall();
        }
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