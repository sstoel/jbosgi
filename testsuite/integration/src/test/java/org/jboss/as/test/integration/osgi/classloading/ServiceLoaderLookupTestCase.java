/*
 * Copyright (C) 2015 Computer Science Corporation
 * All rights reserved.
 *
 */
package org.jboss.as.test.integration.osgi.classloading;

import java.io.InputStream;
import java.util.ServiceLoader;

import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.test.integration.osgi.classloading.suba.TestServiceImpl;
import org.jboss.as.test.integration.osgi.classloading.subb.TestService;
import org.jboss.as.test.integration.osgi.classloading.subc.TestBA;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.osgi.metadata.ManifestBuilder;
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

/**
 * Service discovery with DynamicImport-Package instructions
 *
 * @author arcivanov
 */
@RunWith(Arquillian.class)
public class ServiceLoaderLookupTestCase
{

    static final String MODULE_SERVICE_CONTAINER = "service-container";
    static final String MODULE_SERVICE_IMPL_CONTAINER = "service-impl-container";
    static final String BUNDLE_SERVICE_CONSUMER = "bundle-service-consumer";

    @ArquillianResource
    ServiceContainer serviceContainer;

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive createdeployment()
    {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "serviceloader-lookup");
        archive.addClass(TestService.class);
        archive.addClass(TestServiceImpl.class);
        archive.addClass(TestBA.class);
        return archive;
    }

    @Test
    public void testServiceDiscovery() throws Exception
    {
        deployer.deploy(MODULE_SERVICE_CONTAINER);
        try {
            deployer.deploy(MODULE_SERVICE_IMPL_CONTAINER);
            try {
                InputStream consumerIn = deployer.getDeployment(BUNDLE_SERVICE_CONSUMER);
                Bundle consumerBundle = context.installBundle(BUNDLE_SERVICE_CONSUMER, consumerIn);
                try {
                    ClassLoader oldCl = Thread.currentThread().getContextClassLoader();
                    try {
                        Thread.currentThread().setContextClassLoader(
                                consumerBundle.loadClass(TestBA.class.getName()).getClassLoader());
                        ServiceLoader<?> providers =
                                ServiceLoader.load(Class.forName(TestService.class.getName()));
                        Assert.assertTrue("no providers found", providers.iterator().hasNext());
                        Assert.assertEquals("wrong service class", providers.iterator().next().getClass().getName(),
                                TestServiceImpl.class.getName());
                    }
                    finally {
                        Thread.currentThread().setContextClassLoader(oldCl);
                    }
                }
                finally {
                    consumerBundle.uninstall();
                }
            }
            finally {
                deployer.undeploy(MODULE_SERVICE_IMPL_CONTAINER);
            }
        }
        finally {
            deployer.undeploy(MODULE_SERVICE_CONTAINER);
        }
    }

    @Deployment(name = BUNDLE_SERVICE_CONSUMER, managed = false, testable = false)
    public static JavaArchive getConsumerBundle()
    {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, BUNDLE_SERVICE_CONSUMER);
        archive.addClass(TestBA.class);
        archive.setManifest(new Asset() {
            public InputStream openStream()
            {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addDynamicImportPackages("META-INF.*", "org.jboss.as.test.integration.osgi.classloading.*");
                builder.addManifestHeader("Dependencies", "deployment." + MODULE_SERVICE_IMPL_CONTAINER + " meta-inf");
                return builder.openStream();
            }
        });
        return archive;
    }

    @Deployment(name = MODULE_SERVICE_CONTAINER, managed = false, testable = false)
    public static JavaArchive getServiceContainer()
    {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, MODULE_SERVICE_CONTAINER);
        archive.addClass(TestService.class);
        return archive;
    }

    @Deployment(name = MODULE_SERVICE_IMPL_CONTAINER, managed = false, testable = false)
    public static JavaArchive getServinceImplContainer()
    {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, MODULE_SERVICE_IMPL_CONTAINER);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream()
            {
                ManifestBuilder builder = ManifestBuilder.newInstance();
                builder.addManifestHeader("Dependencies", "deployment." + MODULE_SERVICE_CONTAINER);
                return builder.openStream();
            }
        });
        archive.addAsManifestResource(new StringAsset(TestServiceImpl.class.getName()), "services/" + TestService.class.getName());
        return archive;
    }
}