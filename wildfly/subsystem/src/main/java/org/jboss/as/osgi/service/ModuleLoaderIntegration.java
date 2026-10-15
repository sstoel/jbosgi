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

import static org.jboss.as.osgi.OSGiLogger.LOGGER;
import static org.jboss.as.server.Services.JBOSS_SERVICE_MODULE_LOADER;
import static org.jboss.as.server.moduleservice.ServiceModuleLoader.MODULE_PREFIX;
import static org.jboss.osgi.framework.spi.IntegrationConstants.MODULE_IDENTIFIER_KEY;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.jboss.as.osgi.deployment.BundleDeploymentProcessor;
import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUtils;
import org.jboss.as.server.deployment.Phase;
import org.jboss.as.server.deployment.Services;
import org.jboss.as.server.deployment.module.FilterSpecification;
import org.jboss.as.server.deployment.module.ModuleDependency;
import org.jboss.as.server.deployment.module.ModuleSpecification;
import org.jboss.as.server.moduleservice.ModuleDefinition;
import org.jboss.as.server.moduleservice.ModuleLoadService;
import org.jboss.as.server.moduleservice.ServiceModuleLoader;
import org.jboss.modules.DependencySpec;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleDependencySpec;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.modules.ModuleLoadException;
import org.jboss.modules.ModuleLoader;
import org.jboss.modules.ModuleSpec;
import org.jboss.modules.ModuleSpec.Builder;
import org.jboss.modules.filter.MultiplePathFilterBuilder;
import org.jboss.modules.filter.PathFilter;
import org.jboss.modules.filter.PathFilters;
import org.jboss.msc.service.LifecycleEvent;
import org.jboss.msc.service.LifecycleListener;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;
import org.jboss.osgi.deployment.deployer.Deployment;
import org.jboss.osgi.framework.spi.FrameworkModuleLoader;
import org.jboss.osgi.framework.spi.FutureServiceValue;
import org.jboss.osgi.framework.spi.FrameworkModuleLoaderPlugin;
import org.jboss.osgi.framework.spi.IntegrationConstants;
import org.jboss.osgi.resolver.XBundle;
import org.jboss.osgi.resolver.XBundleRevision;
import org.jboss.osgi.resolver.XIdentityCapability;
import org.jboss.osgi.resolver.spi.AbstractBundleWiring;
import org.osgi.framework.wiring.BundleRevisions;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;

/**
 * This is the single {@link ModuleLoader} that the OSGi layer uses for the modules that are associated with the bundles that
 * are registered with the {@link org.jboss.osgi.framework.spi.BundleManager}.
 *
 * @author thomas.diesler@jboss.com
 * @since 20-Apr-2011
 */
final class ModuleLoaderIntegration extends FrameworkModuleLoaderPlugin {

    private final InjectedValue<ServiceModuleLoader> injectedModuleLoader = new InjectedValue<ServiceModuleLoader>();
    private final Set<String> bundleModules = ConcurrentHashMap.newKeySet();
    private final Set<String> strictModules = new HashSet<String>();
    private final Map<String, XBundleRevision> moduleOwners = new ConcurrentHashMap<String, XBundleRevision>();
    private final boolean strictBootDelegation;
    private ServiceContainer serviceContainer;
    private ServiceTarget serviceTarget;

    /**
     * @param strictBootDelegation If true, the modules of bundles do not get the dependency on the java.base module
     *        that every module has by default
     */
    ModuleLoaderIntegration(boolean strictBootDelegation) {
        this.strictBootDelegation = strictBootDelegation;
    }

    @Override
    protected void addServiceDependencies(ServiceBuilder<FrameworkModuleLoader> builder) {
        super.addServiceDependencies(builder);
        builder.addDependency(JBOSS_SERVICE_MODULE_LOADER, ServiceModuleLoader.class, injectedModuleLoader);
    }

    @Override
    public void start(StartContext context) throws StartException {
        serviceContainer = context.getController().getServiceContainer();
        serviceTarget = context.getChildTarget();
        super.start(context);
    }

    @Override
    protected FrameworkModuleLoader createServiceValue(StartContext startContext) {
        return new FrameworkModuleLoaderImpl();
    }

    /**
     * Remove the dependency on the java.base module from the module of a bundle.
     *
     * The module is defined by the {@link ServiceModuleLoader}, which is the only loader that can change its dependencies.
     * This is done when the framework gets the module, that is before it loads a class of the bundle.
     */
    private void removeBaseDependency(Module module) {
        String name = module.getName();
        synchronized (strictModules) {
            if (bundleModules.contains(name) == false || strictModules.add(name) == false)
                return;

            List<DependencySpec> dependencies = new ArrayList<DependencySpec>();
            for (DependencySpec dependency : module.getDependencies()) {
                if (dependency instanceof ModuleDependencySpec == false || "java.base".equals(((ModuleDependencySpec) dependency).getName()) == false)
                    dependencies.add(dependency);
            }
            try {
                Method method = ModuleLoader.class.getDeclaredMethod("setAndRelinkDependencies", Module.class, List.class);
                method.setAccessible(true);
                method.invoke(module.getModuleLoader(), module, dependencies);
            } catch (Exception ex) {
                LOGGER.warnCannotApplyStrictBootDelegation(ex, name);
            }
        }
    }

    /**
     * Whether a deployment names the module as a module dependency
     */
    static boolean isModuleUsedByDeployments(ServiceContainer serviceContainer, String moduleName) {
        for (ServiceName serviceName : serviceContainer.getServiceNames()) {
            ServiceName parent = serviceName.getParent();
            boolean unitName = Services.JBOSS_DEPLOYMENT_UNIT.equals(parent) || (parent != null && Services.JBOSS_DEPLOYMENT_SUB_UNIT.equals(parent.getParent()));
            if (!unitName)
                continue;
            ServiceController<?> controller = serviceContainer.getService(serviceName);
            Object value = controller != null && controller.getState() == ServiceController.State.UP ? controller.getValue() : null;
            if (!(value instanceof DeploymentUnit))
                continue;
            ModuleSpecification moduleSpec = ((DeploymentUnit) value).getAttachment(Attachments.MODULE_SPECIFICATION);
            if (moduleSpec == null)
                continue;
            for (ModuleDependency moduleDep : moduleSpec.getAllDependencies()) {
                if (moduleName.equals(moduleDep.getDependencyModule()))
                    return true;
            }
        }
        return false;
    }

    class FrameworkModuleLoaderImpl implements FrameworkModuleLoader {

        @Override
        public ModuleLoader getModuleLoader() {
            class DelegatingModuleLoader extends ModuleLoader {

                @Override
                protected Module preloadModule(ModuleIdentifier identifier) throws ModuleLoadException {
                    Module module = ModuleLoader.preloadModule(identifier, injectedModuleLoader.getValue());
                    if (module == null)
                        LOGGER.debugf("Cannot obtain module for: %s", identifier);
                    else if (strictBootDelegation)
                        removeBaseDependency(module);
                    return module;
                }

                @Override
                public void setAndRelinkDependencies(Module module, List<DependencySpec> dependencies) throws ModuleLoadException {
                    throw new UnsupportedOperationException();
                }

                @Override
                public String toString() {
                    return ModuleLoaderIntegration.class.getSimpleName() + "." + getClass().getSimpleName();
                }
            }
            return new DelegatingModuleLoader();
        }

        /**
         * Get the module identifier for the given {@link XBundleRevision}. The returned identifier must be such that it can be used
         * by the {@link ServiceModuleLoader}
         */
        @Override
        public ModuleIdentifier getModuleIdentifier(XBundleRevision brev) {
            XBundle bundle = brev.getBundle();
            Deployment deployment = bundle.adapt(Deployment.class);
            ModuleIdentifier identifier = deployment.getAttachment(MODULE_IDENTIFIER_KEY);
            if (identifier != null) {
                // [JBOSGI-800] The identifier comes from the deployment name. A bundle that is deployed again while a
                // revision of the uninstalled bundle is still in use cannot have it, that revision keeps its module.
                // Likewise the new revision of an updated bundle while the revision before the update is in use
                if (isTakenByRevisionInUse(identifier, brev)) {
                    ModuleIdentifier taken = identifier;
                    ModuleIdentifier revisionIdentifier = deployment.getAttachment(BundleDeploymentProcessor.REVISION_MODULE_IDENTIFIER_KEY);
                    if (revisionIdentifier != null && !isTakenByRevisionInUse(revisionIdentifier, brev)) {
                        identifier = revisionIdentifier;
                    } else {
                        identifier = ModuleIdentifier.create(identifier.getName() + "-" + bundle.getBundleId(), identifier.getSlot());
                    }
                    LOGGER.debugf("Module %s is used by %s, %s gets: %s", taken, moduleOwners.get(taken.toString()), brev, identifier);
                }
            } else {
                XIdentityCapability icap = brev.getIdentityCapability();
                BundleRevisions brevs = bundle.adapt(BundleRevisions.class);
                int revsize = brevs.getRevisions().size();
                String name = icap.getName();
                if (revsize > 1) {
                    name += "-rev" + (revsize - 1);
                }
                identifier = ModuleIdentifier.create(MODULE_PREFIX + name, "" + icap.getVersion());
            }
            return identifier;
        }

        /**
         * Whether the module of the identifier belongs to another revision that is in use. The revision that a bundle
         * had before its update is in use when other bundles or deployments use it, not because it is current.
         */
        private boolean isTakenByRevisionInUse(ModuleIdentifier identifier, XBundleRevision brev) {
            XBundleRevision owner = moduleOwners.get(identifier.toString());
            if (owner == null || owner == brev)
                return false;
            BundleWiring wiring = owner.getWiring();
            if (wiring == null)
                return false;
            if (owner.getBundle() != brev.getBundle())
                return wiring.isInUse() || isModuleInUse(owner);
            boolean usedByBundles = wiring instanceof AbstractBundleWiring && ((AbstractBundleWiring) wiring).isInUseForUninstall();
            return usedByBundles || isModuleInUse(owner);
        }

        /**
         * The module of the revision is in use when a deployment that is not the bundle names it as a module dependency
         * (a WAR with Dependencies: deployment.&lt;bundle&gt;). The framework does not see these as wires.
         */
        @Override
        public boolean isModuleInUse(XBundleRevision brev) {
            ModuleIdentifier identifier = brev.getModuleIdentifier();
            if (identifier == null || moduleOwners.get(identifier.toString()) != brev)
                return false;
            return isModuleUsedByDeployments(serviceContainer, identifier.toString());
        }

        @Override
        public void addIntegrationDependencies(ModuleSpecBuilderContext context) {
            Builder builder = context.getModuleSpecBuilder();
            XBundleRevision brev = context.getBundleRevision();
            Map<ModuleIdentifier, DependencySpec> moduleDependencies = context.getModuleDependencies();
            Deployment deployment = brev.getBundle().adapt(Deployment.class);
            ModuleSpecification moduleSpecification = deployment.getAttachment(BundleDeploymentProcessor.MODULE_SPECIFICATION_KEY);
            if (moduleSpecification != null) {
                List<ModuleDependency> dependencies = moduleSpecification.getAllDependencies();
                LOGGER.debugf("Adding integration dependencies: %d", dependencies.size());
                for (ModuleDependency moduleDep : dependencies) {
                    ModuleIdentifier moduleId = ModuleIdentifier.fromString(moduleDep.getDependencyModule());
                    if (moduleDependencies.get(moduleId) != null) {
                        LOGGER.debugf("  -dependency on %s (skipped)", moduleId);
                        continue;
                    }
                    // Build import filter
                    MultiplePathFilterBuilder importBuilder = PathFilters.multiplePathFilterBuilder(true);
                    for (FilterSpecification filter : moduleDep.getImportFilters()) {
                        importBuilder.addFilter(filter.getPathFilter(), filter.isInclude());
                    }
                    PathFilter importFilter = importBuilder.create();
                    // Build export filter
                    MultiplePathFilterBuilder exportBuilder = PathFilters.multiplePathFilterBuilder(true);
                    for (FilterSpecification filter : moduleDep.getExportFilters()) {
                        exportBuilder.addFilter(filter.getPathFilter(), filter.isInclude());
                    }
                    PathFilter exportFilter = exportBuilder.create();
                    ModuleLoader moduleLoader = moduleDep.getModuleLoader();
                    boolean optional = moduleDep.isOptional();
                    DependencySpec depSpec = DependencySpec.createModuleDependencySpec(importFilter, exportFilter, moduleLoader, moduleId, optional);
                    LOGGER.debugf("  +%s", depSpec);
                    builder.addDependency(depSpec);
                }
            }
        }

        /**
         * Add a {@link ModuleSpec} for and OSGi module as a service that can later be looked up by the {@link ServiceModuleLoader}
         */
        @Override
        public void addModuleSpec(XBundleRevision brev, final ModuleSpec moduleSpec) {
            String identifier = moduleSpec.getName();
            LOGGER.tracef("Add module spec to loader: %s", identifier);
            bundleModules.add(identifier);
            ServiceName moduleSpecName = ServiceModuleLoader.moduleSpecServiceName(identifier);

            // [JBOSGI-800] Another bundle gets an identifier of its own while the revision of an uninstalled bundle
            // that has it is in use (see getModuleIdentifier), otherwise the revision that is deployed now takes it over
            XBundleRevision previous = moduleOwners.put(identifier, brev);
            if (previous != brev && serviceContainer.getService(moduleSpecName) != null) {
                LOGGER.debugf("Module %s is taken over from: %s", identifier, previous);
                removeModuleServices(brev.getModuleIdentifier(), true);
            }

            ServiceBuilder sb = serviceTarget.addService(moduleSpecName);
            Consumer<ModuleDefinition> c = sb.provides(moduleSpecName);
            sb.setInstance(new ModuleDefinitionService(c, new ModuleDefinition(identifier, Collections.emptySet(), moduleSpec)));
            ServiceController<?> specController = sb.install();

            ServiceModuleLoader.installModuleResolvedService(serviceTarget, identifier);

            // The ServiceModuleLoader does not find a module whose spec service is not UP yet: a class load right
            // after the resolve would fail. The spec service has no dependencies, it comes up at once.
            try {
                new FutureServiceValue(specController).get(10, TimeUnit.SECONDS);
            } catch (Exception ex) {
                LOGGER.debugf(ex, "Module spec service not up: %s", moduleSpecName);
            }
        }

        /**
         * Add an already loaded {@link Module} to the OSGi {@link ModuleLoader}. This happens when AS registers an existing
         * {@link Module} with the {@link org.jboss.osgi.framework.spi.BundleManager}.
         * <p/>
         * The {@link Module} may not necessarily result from a user deployment. We use the same {@link ServiceName} convention as
         * in {@link ServiceModuleLoader#moduleServiceName(ModuleIdentifier)}
         * <p/>
         * The {@link ServiceModuleLoader} cannot load these modules.
         */
        @Override
        public void addModule(XBundleRevision brev, final Module module) {
            ServiceName moduleServiceName = getModuleServiceName(module.getIdentifier());
            if (serviceContainer.getService(moduleServiceName) == null) {
                LOGGER.debugf("Add module to loader: %s", module.getIdentifier());
                ServiceBuilder sb = serviceTarget.addService(moduleServiceName);
                Consumer<Module> c = sb.provides(moduleServiceName);
                sb.setInstance(new ModuleService(c, module));
                sb.install();
            }
        }

        private final class ModuleDefinitionService implements Service {
            private final Consumer<ModuleDefinition> moduleConsumer;
            private final ModuleDefinition module;

            private ModuleDefinitionService(final Consumer<ModuleDefinition> moduleConsumer, final ModuleDefinition module) {
                this.moduleConsumer = moduleConsumer;
                this.module = module;
            }
            @Override
            public void start(final StartContext startContext) {
                moduleConsumer.accept(module);
            }

            @Override
            public void stop(final StopContext stopContext) {
                moduleConsumer.accept(null);
            }

            @Override
            public Object getValue() {
                return module;
            }
        }

        private final class ModuleService implements Service {
            private final Consumer<Module> moduleConsumer;
            private final Module module;

            private ModuleService(final Consumer<Module> moduleConsumer, final Module module) {
                this.moduleConsumer = moduleConsumer;
                this.module = module;
            }
            @Override
            public void start(final StartContext startContext) {
                moduleConsumer.accept(module);
            }

            @Override
            public void stop(final StopContext stopContext) {
                moduleConsumer.accept(null);
            }

            @Override
            public Object getValue() {
                return module;
            }
        }

        @Override
        public ServiceName createModuleService(XBundleRevision brev, List<BundleWire> wires) {
            Deployment deployment = brev.getBundle().adapt(Deployment.class);
            DeploymentUnit depUnit = deployment.getAttachment(BundleDeploymentProcessor.DEPLOYMENT_UNIT_KEY);

            // Add a dependency on the parent module if we have one
            List<ModuleDependency> dependencies = new ArrayList<ModuleDependency>();
            if (depUnit != null && depUnit.getParent() != null) {
                String parentName = depUnit.getParent().getName();
                String depId = ModuleIdentifier.create(MODULE_PREFIX + parentName).toString();
                dependencies.add(new ModuleDependency(null, depId, false, false, false, false));
            }

            // Add dependencies on all modules this brev has a wire to
            for (BundleWire wire : wires) {
                XBundleRevision provider = (XBundleRevision) wire.getProvider();
                String providerid = provider.getModuleIdentifier().toString();
                dependencies.add(new ModuleDependency(null, providerid, false, false, false, false));
            }

            String identifier = brev.getModuleIdentifier().toString();
            return ModuleLoadService.install(serviceTarget, identifier, dependencies, new ArrayList<ModuleDependency>(),
                    new ArrayList<ModuleDependency>());
        }

        /**
         * Remove the {@link Module} and {@link ModuleSpec} services associated with the given identifier.
         */
        @Override
        public void removeModule(XBundleRevision brev) {
            deactivateDeferredPhase(brev);

            // The services belong to another revision when that has taken over the identifier
            ModuleIdentifier identifier = brev.getModuleIdentifier();
            if (moduleOwners.remove(identifier.toString(), brev) == false && moduleOwners.containsKey(identifier.toString()))
                return;

            bundleModules.remove(identifier.toString());
            synchronized (strictModules) {
                strictModules.remove(identifier.toString());
            }
            removeModuleServices(identifier, false);
        }

        private void removeModuleServices(ModuleIdentifier identifier, boolean awaitRemoval) {
            Set<ServiceName> serviceNames = new HashSet<ServiceName>();
            serviceNames.add(getModuleSpecServiceName(identifier));
            serviceNames.add(getModuleServiceName(identifier));
            serviceNames.add(ServiceModuleLoader.moduleResolvedServiceName(identifier.toString()));
            List<ServiceController<?>> controllers = new ArrayList<ServiceController<?>>();
            for (ServiceName serviceName : serviceNames) {
                ServiceController<?> controller = serviceContainer.getService(serviceName);
                if (controller != null)
                    controllers.add(controller);
            }
            final CountDownLatch latch = new CountDownLatch(controllers.size());
            for (ServiceController<?> controller : controllers) {
                LOGGER.debugf("Remove from loader: %s", controller.getName());
                if (awaitRemoval) {
                    controller.addListener(new LifecycleListener() {
                        @Override
                        public void handleEvent(ServiceController<?> controller, LifecycleEvent event) {
                            if (event == LifecycleEvent.REMOVED)
                                latch.countDown();
                        }
                    });
                }
                controller.setMode(Mode.REMOVE);
            }
            if (awaitRemoval) {
                try {
                    if (latch.await(30, TimeUnit.SECONDS) == false)
                        LOGGER.debugf("Timeout removing the services of module: %s", identifier);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        /**
         * The activated deferred phase of a deployment depends on the module of the bundle.
         *
         * A bundle that is uninstalled while it is in use stays deployed. Its module is removed when it is not in
         * use any more, the deployment must not have a missing dependency then.
         */
        private void deactivateDeferredPhase(XBundleRevision brev) {
            // The deployment of the revision, the bundle has the deployment of its current revision
            Deployment deployment = brev.getAttachment(IntegrationConstants.DEPLOYMENT_KEY);
            if (deployment == null)
                deployment = brev.getBundle().adapt(Deployment.class);
            DeploymentUnit depUnit = deployment != null ? deployment.getAttachment(BundleDeploymentProcessor.DEPLOYMENT_UNIT_KEY) : null;
            if (depUnit == null || DeploymentUtils.getDeferredModules(depUnit).contains(depUnit.getName()) == false)
                return;

            ServiceName phaseName = DeploymentUtils.getDeploymentUnitPhaseServiceName(depUnit, Phase.FIRST_MODULE_USE);
            ServiceController<?> phaseService = serviceContainer.getService(phaseName);
            // The phase of a deployment of the same name that was deployed again has the same service name
            if (phaseService != null && phaseService.getMode() == Mode.ACTIVE && BundleLifecycleIntegration.isServiceOf(phaseService, depUnit)) {
                LOGGER.debugf("Deactivate deferred module phase for: %s", brev);
                phaseService.setMode(Mode.NEVER);
            }
        }

        @Override
        public ServiceName getModuleServiceName(ModuleIdentifier identifier) {
            return ServiceModuleLoader.moduleServiceName(identifier.toString());
        }

        private ServiceName getModuleSpecServiceName(ModuleIdentifier identifier) {
            return ServiceModuleLoader.moduleSpecServiceName(identifier.toString());
        }

        @Override
        public String toString() {
            return ModuleLoaderIntegration.class.getSimpleName();
        }
    }
 }
