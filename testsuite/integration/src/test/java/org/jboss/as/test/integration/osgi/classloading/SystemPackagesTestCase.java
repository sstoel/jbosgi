/*
 * JBoss, Home of Professional Open Source
 * Copyright 2009, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.as.test.integration.osgi.classloading;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleClassLoader;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.modules.ModuleLoader;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.resolver.XBundle;
import org.jboss.osgi.resolver.XBundleRevision;
import org.jboss.osgi.resolver.XCapability;
import org.jboss.osgi.resolver.XPackageCapability;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.BundleContext;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleCapability;

/**
 * Test access to system packages.
 *
 * https://issues.jboss.org/browse/AS7-5436
 *
 * @author thomas.diesler@jboss.com
 * @since 28-Aug-2012
 */
@RunWith(Arquillian.class)
public class SystemPackagesTestCase {

	@ArquillianResource
	BundleContext context;

	@Deployment
	public static JavaArchive createdeployment() {
		final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "test-bundle");
		archive.setManifest(new Asset() {
			@Override
			public InputStream openStream() {
				OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
				builder.addBundleSymbolicName(archive.getName());
				builder.addBundleManifestVersion(2);
				builder.addImportPackages(XBundle.class, BundleCapability.class, PackageNamespace.class);
				builder.addImportPackages(ModuleClassLoader.class);
				return builder.openStream();
			}
		});
		return archive;
	}

	@Test
	public void testSystemPackages() throws Exception {
		XBundle sysbundle = (XBundle) context.getBundle(0);
		XBundleRevision revision = sysbundle.getBundleRevision();
		ModuleClassLoader moduleClassLoader = revision.getModuleClassLoader();
		ClassLoaderWrapper classLoader = new ClassLoaderWrapper(moduleClassLoader);
		List<String> inaccessible = new ArrayList<String>();
		for (BundleCapability cap : revision.getDeclaredCapabilities(PackageNamespace.PACKAGE_NAMESPACE)) {
			XPackageCapability pcap = ((XCapability) cap).adapt(XPackageCapability.class);
			Package definedPackage = classLoader.getPackage(pcap.getPackageName());
			if (definedPackage == null && pcap.getPackageName().startsWith("javax.")) {
				inaccessible.add(pcap.getPackageName());
			}
		}
		// [TODO] Packages may not be defined but still be accessible.
		// Assert.assertTrue("Cannot access " + inaccessible,
		// inaccessible.isEmpty());

		ModuleLoader loader = moduleClassLoader.getModule().getModuleLoader();
		loader = ((ModuleClassLoader) loader.getClass().getClassLoader()).getModule().getModuleLoader();

		ModuleIdentifier asOsgi = ModuleIdentifier.fromString("org.jboss.as.osgi");
		Module asOsgiModule = loader.loadModule(asOsgi);
		Class<?> sPI = asOsgiModule.getClassLoader().loadClass("org.jboss.as.osgi.service.SystemPackagesIntegration");
		ModuleIdentifier javaxApiModuleId = ModuleIdentifier.fromString("javax.api");
		Module javaxApiModule = loader.loadModule(javaxApiModuleId);

		Field javaxAPIPackagesField = sPI.getDeclaredField("JAVAX_API_PACKAGES");
		javaxAPIPackagesField.setAccessible(true);
		String[] javaxAPIPackages = (String[]) javaxAPIPackagesField.get(null);

		List<String> javaxApiPaths = javaxApiModule.getExportedPaths().stream().map(x -> x.replace('/', '.'))
				.filter(x -> x.startsWith("javax") || x.startsWith("org")).sorted(String::compareTo)
				.collect(Collectors.toList());
		List<String> declaredPackages = new ArrayList<String>(Arrays.asList(javaxAPIPackages));
		declaredPackages.sort(String::compareTo);

		Assert.assertEquals("system.bundle does not contain all javax.api packages", javaxApiPaths, declaredPackages);
	}

	static class ClassLoaderWrapper extends ClassLoader {

		ClassLoaderWrapper(ClassLoader parent) {
			super(parent);
		}

		@Override
		public Package getPackage(String name) {
			return super.getPackage(name);
		}
	}
}
