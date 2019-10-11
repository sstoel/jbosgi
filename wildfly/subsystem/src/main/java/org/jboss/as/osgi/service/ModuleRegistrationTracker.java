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
package org.jboss.as.osgi.service;

import static org.jboss.as.osgi.OSGiConstants.SERVICE_BASE_NAME;
import static org.jboss.as.osgi.OSGiLogger.LOGGER;
import static org.jboss.as.osgi.OSGiMessages.MESSAGES;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jboss.modules.Module;
import org.jboss.msc.service.AbstractService;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.value.InjectedValue;
import org.jboss.osgi.framework.Services;
import org.jboss.osgi.framework.spi.AbstractBundleRevisionAdaptor;
import org.jboss.osgi.framework.spi.BundleManager;
import org.jboss.osgi.framework.spi.IntegrationConstants;
import org.jboss.osgi.framework.spi.IntegrationServices;
import org.jboss.osgi.metadata.OSGiMetaData;
import org.jboss.osgi.resolver.XBundleRevision;
import org.jboss.osgi.resolver.XBundleRevisionBuilderFactory;
import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.resolver.XResourceBuilder;
import org.jboss.osgi.resolver.spi.OSGiMetaDataProcessor;
import org.osgi.framework.BundleContext;

/**
 * A service that tracks module registrations.
 *
 * @author thomas.diesler@jboss.com
 * @since 25-Jul-2012
 */
public class ModuleRegistrationTracker extends AbstractService<Void> {

    public static final ServiceName MODULE_REGISTRATION_COMPLETE = SERVICE_BASE_NAME.append("module", "registration");

    private final InjectedValue<BundleContext> injectedSystemContext = new InjectedValue<BundleContext>();
    private final InjectedValue<BundleManager> injectedBundleManager = new InjectedValue<BundleManager>();
    private final InjectedValue<XEnvironment> injectedEnvironment = new InjectedValue<XEnvironment>();

    private final Map<Module, Registration> registrations = new LinkedHashMap<Module, Registration>();

    public ServiceController<Void> install(ServiceTarget serviceTarget) {
        ServiceBuilder<Void> builder = serviceTarget.addService(MODULE_REGISTRATION_COMPLETE, this);
        builder.addDependency(Services.FRAMEWORK_CREATE, BundleContext.class, injectedSystemContext);
        builder.addDependency(Services.BUNDLE_MANAGER, BundleManager.class, injectedBundleManager);
        builder.addDependency(Services.ENVIRONMENT, XEnvironment.class, injectedEnvironment);
        builder.addDependencies(IntegrationServices.BOOTSTRAP_BUNDLES_COMPLETE);
        builder.setInitialMode(Mode.ON_DEMAND);
        return builder.install();
    }

    public synchronized void registerModule(Module module, OSGiMetaData metadata) {
        BundleContext context = injectedSystemContext.getOptionalValue();
        Registration reg = new Registration(module, metadata);
        if (context != null) {
            reg.brev = registerInternal(context, reg);
        }
        registrations.put(module, reg);
    }

    public synchronized void unregisterModule(Module module) {
        BundleContext context = injectedSystemContext.getOptionalValue();
        XEnvironment env = injectedEnvironment.getOptionalValue();
        Registration reg = registrations.remove(module);
        if (context != null && env != null && reg != null) {
            unregisterInternal(env, reg);
        }
    }

    public synchronized void start(StartContext startContext) throws StartException {
        ServiceController<?> serviceController = startContext.getController();
        LOGGER.tracef("Starting: %s in mode %s", serviceController.getName(), serviceController.getMode());

        BundleContext syscontext = injectedSystemContext.getValue();
        for (Registration reg : registrations.values()) {
            reg.brev = registerInternal(syscontext, reg);
        }
        registrations.clear();
    }

    private XBundleRevision registerInternal(final BundleContext context, final Registration reg) {
        OSGiMetaData metadata = reg.metadata;
        final Module module = reg.module;
        LOGGER.infoRegisterModule(module.getIdentifier());

        XBundleRevision brev;
        try {
            XBundleRevisionBuilderFactory factory = new XBundleRevisionBuilderFactory() {
                @Override
                public XBundleRevision createResource() {
                    return new AbstractBundleRevisionAdaptor(context, module);
                }
            };

            XResourceBuilder<XBundleRevision> builder = XBundleRevisionBuilderFactory.create(factory);
            if (metadata != null) {
                builder.loadFrom(metadata);
                brev = builder.getResource();
            } else {
                builder.loadFrom(module);
                brev = builder.getResource();
                metadata = OSGiMetaDataProcessor.getOsgiMetaData(brev);
            }
            brev.putAttachment(IntegrationConstants.OSGI_METADATA_KEY, metadata);

            injectedEnvironment.getValue().installResources(brev);

        } catch (Throwable th) {
            throw MESSAGES.illegalStateFailedToRegisterModule(th, module);
        }

        return brev;
    }

    private void unregisterInternal(final XEnvironment env, final Registration reg) {
        assert reg.brev != null : "BundleRevision not null";
        LOGGER.infoUnregisterModule(reg.module.getIdentifier());
        env.uninstallResources(reg.brev);
    }

    public static final class Registration {
        private final OSGiMetaData metadata;
        private final Module module;
        private XBundleRevision brev;

        Registration(Module module, OSGiMetaData metadata) {
            this.metadata = metadata;
            this.module = module;
        }
    }
}