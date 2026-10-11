/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
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
package org.jboss.as.osgi.service;

import static org.jboss.as.osgi.parser.SubsystemState.PROP_JBOSS_OSGI_SYSTEM_MODULES;
import static org.jboss.as.osgi.parser.SubsystemState.PROP_JBOSS_OSGI_SYSTEM_MODULES_EXTRA;
import static org.jboss.osgi.framework.Constants.JBOSGI_PREFIX;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import org.jboss.modules.DependencySpec;
import org.jboss.modules.IterableResourceLoader;
import org.jboss.modules.LocalLoader;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.modules.ModuleLoadException;
import org.jboss.modules.ModuleLoader;
import org.jboss.modules.ModuleSpec;
import org.jboss.modules.Resource;
import org.jboss.modules.ResourceLoader;
import org.jboss.modules.ResourceLoaderSpec;
import org.jboss.modules.ResourceLoaders;
import org.jboss.modules.filter.PathFilter;
import org.jboss.modules.filter.PathFilters;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.value.InjectedValue;
import org.jboss.osgi.framework.Services;
import org.jboss.osgi.framework.spi.BundleManager;
import org.jboss.osgi.framework.spi.BundleReferenceClassLoader;
import org.jboss.osgi.framework.spi.FrameworkModuleProvider;
import org.jboss.osgi.framework.spi.FrameworkModuleProviderPlugin;
import org.jboss.osgi.resolver.XBundle;

/**
 * An {@link org.jboss.osgi.framework.spi.IntegrationService} that provides the Framework module.
 *
 * @author Thomas.Diesler@jboss.com
 * @since 11-Sep-2010
 */
final class FrameworkModuleIntegration extends FrameworkModuleProviderPlugin {

    private final InjectedValue<BundleManager> injectedBundleManager = new InjectedValue<BundleManager>();
    private final Map<String, String> props;
    private final File tempDir;

    /**
     * @param tempDir The directory for the content of framework extension bundles
     */
    FrameworkModuleIntegration(Map<String, String> props, File tempDir) {
        this.props = props;
        this.tempDir = tempDir;
    }

    @Override
    protected void addServiceDependencies(ServiceBuilder<FrameworkModuleProvider> builder) {
        super.addServiceDependencies(builder);
        builder.addDependency(Services.BUNDLE_MANAGER, BundleManager.class, injectedBundleManager);
    }

    @Override
    protected FrameworkModuleProvider createServiceValue(StartContext startContext) {
        return new FrameworkModuleProviderImpl();
    }

    class FrameworkModuleProviderImpl implements FrameworkModuleProvider {

        private final List<ResourceLoaderSpec> extensionLoaders = new ArrayList<ResourceLoaderSpec>();
        private FrameworkModuleLoader moduleLoader;
        private volatile boolean hasExtensions;
        private Module frameworkModule;

        @Override
        public boolean supportsExtensions() {
            return true;
        }

        /**
         * Add the content of a framework extension bundle to the framework module.
         * The classes of the extension are defined by the class loader of the framework module.
         */
        @Override
        public synchronized boolean addExtensionContent(URL contentURL, ResourceLoader resourceLoader) {
            Module module = getFrameworkModule();
            hasExtensions = true;
            try {
                extensionLoaders.add(ResourceLoaderSpec.createResourceLoaderSpec(copyContent(resourceLoader)));
                moduleLoader.refreshResourceLoaders(module, extensionLoaders);
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            } catch (ModuleLoadException ex) {
                throw new IllegalStateException(ex);
            }
            return false;
        }

        /**
         * Copy the content of an extension to a jar file.
         *
         * The content stays part of the framework until the server is stopped. It is given as the content of a
         * deployment, which is not available any more when the extension is undeployed.
         */
        private ResourceLoader copyContent(ResourceLoader resourceLoader) throws IOException {
            if (resourceLoader instanceof IterableResourceLoader == false)
                return resourceLoader;

            tempDir.mkdirs();
            File file = File.createTempFile("osgi-extension", ".jar", tempDir);
            file.deleteOnExit();
            JarOutputStream output = new JarOutputStream(new FileOutputStream(file));
            try {
                Iterator<Resource> resources = ((IterableResourceLoader) resourceLoader).iterateResources("", true);
                while (resources.hasNext()) {
                    Resource resource = resources.next();
                    output.putNextEntry(new JarEntry(resource.getName()));
                    InputStream input = resource.openStream();
                    try {
                        input.transferTo(output);
                    } finally {
                        input.close();
                    }
                    output.closeEntry();
                }
            } finally {
                output.close();
            }
            return ResourceLoaders.createJarResourceLoader(new JarFile(file));
        }

        @Override
        public Module getFrameworkModule() {
            synchronized (this) {
                if (frameworkModule == null) {
                    frameworkModule = createFrameworkModule();
                }
                return frameworkModule;
            }
        }

        private Module createFrameworkModule() {
            // Setup the extended framework module spec
            ModuleSpec.Builder specBuilder = ModuleSpec.build(ModuleIdentifier.create(JBOSGI_PREFIX + ".framework"));

            // Add the framework module dependencies
            String sysmodules = (String) props.get(PROP_JBOSS_OSGI_SYSTEM_MODULES);
            if (sysmodules == null)
                sysmodules = "";

            String extramodules = (String) props.get(PROP_JBOSS_OSGI_SYSTEM_MODULES_EXTRA);
            if (extramodules != null)
                sysmodules += "," + extramodules;

            // Add a dependency on the default framework modules
            ModuleLoader bootLoader = Module.getBootModuleLoader();
            PathFilter acceptAll = PathFilters.acceptAll();
            for (String modid : sysmodules.split(",")) {
                modid = modid.trim();
                if (modid.length() > 0) {
                    ModuleIdentifier identifier = ModuleIdentifier.create(modid);
                    specBuilder.addDependency(DependencySpec.createModuleDependencySpec(acceptAll, acceptAll, bootLoader, identifier, false));
                }
            }

            // The content of the framework extensions
            specBuilder.addDependency(DependencySpec.createLocalDependencySpec());

            // Classes of a framework extension can use all classes of the framework implementation
            final ClassLoader frameworkClassLoader = BundleManager.class.getClassLoader();
            specBuilder.setFallbackLoader(new LocalLoader() {

                @Override
                public Class<?> loadClassLocal(String name, boolean resolve) {
                    if (hasExtensions == false)
                        return null;
                    try {
                        return frameworkClassLoader.loadClass(name);
                    } catch (ClassNotFoundException ex) {
                        return null;
                    }
                }

                @Override
                public Package loadPackageLocal(String name) {
                    return null;
                }

                @Override
                public List<Resource> loadResourceLocal(String name) {
                    return Collections.emptyList();
                }
            });

            XBundle systemBundle = injectedBundleManager.getValue().getSystemBundle();
            specBuilder.setModuleClassLoaderFactory(new BundleReferenceClassLoader.Factory<XBundle>(systemBundle));

            try {
                moduleLoader = new FrameworkModuleLoader(specBuilder.create());
                return moduleLoader.loadModule(specBuilder.getIdentifier());
            } catch (ModuleLoadException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    private static final class FrameworkModuleLoader extends ModuleLoader {

        private final ModuleSpec moduleSpec;

        FrameworkModuleLoader(ModuleSpec moduleSpec) {
            this.moduleSpec = moduleSpec;
        }

        @Override
        protected ModuleSpec findModule(ModuleIdentifier identifier) throws ModuleLoadException {
            return (moduleSpec.getModuleIdentifier().equals(identifier) ? moduleSpec : null);
        }

        void refreshResourceLoaders(Module module, List<ResourceLoaderSpec> loaders) throws ModuleLoadException {
            setAndRefreshResourceLoaders(module, loaders);
            relink(module);
        }

        @Override
        public String toString() {
            return "FrameworkModuleLoader";
        }
    }
}
