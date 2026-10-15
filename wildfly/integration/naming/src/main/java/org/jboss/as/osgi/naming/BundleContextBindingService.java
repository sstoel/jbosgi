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
package org.jboss.as.osgi.naming;

import static org.jboss.as.osgi.OSGiLogger.LOGGER;

import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.as.naming.ManagedReferenceFactory;
import org.jboss.as.naming.ManagedReferenceInjector;
import org.jboss.as.naming.ServiceBasedNamingStore;
import org.jboss.as.naming.deployment.ContextNames;
import org.jboss.as.naming.service.BinderService;
import org.jboss.msc.service.LifecycleListener;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.osgi.framework.Services;
import org.jboss.osgi.framework.spi.IntegrationService;
import org.osgi.framework.BundleContext;

/**
 * Binds the system context to java:jboss/osgi/BundleContext.
 *
 * The binder is installed with the services of the active framework, also when a lazy framework is activated, and it
 * is passive: the name is bound whenever the framework is active, also for a plain lookup that does not depend on the
 * binder. A deployment with an injection point depends on the binder, it activates a lazy framework.
 */
final class BundleContextBindingService extends BinderService implements IntegrationService<ManagedReferenceFactory> {

    private static final String BUNDLE_CONTEXT_BINDING_NAME = "java:jboss/osgi/BundleContext";

    BundleContextBindingService() {
        super(ContextNames.bindInfoFor(BUNDLE_CONTEXT_BINDING_NAME).getBindName());
    }

    @Override
    public ServiceName getServiceName() {
        return getBinderServiceName();
    }

    @Override
    public ServiceController<ManagedReferenceFactory> install(ServiceTarget serviceTarget, LifecycleListener listener) {
        ContextNames.BindInfo bindInfo = ContextNames.bindInfoFor(BUNDLE_CONTEXT_BINDING_NAME);
        final AtomicBoolean bound = new AtomicBoolean();
        ServiceBuilder<ManagedReferenceFactory> builder = serviceTarget.addService(getBinderServiceName(), this);
        builder.addDependency(Services.FRAMEWORK_ACTIVE, BundleContext.class, new ManagedReferenceInjector<BundleContext>(getManagedObjectInjector()));
        builder.addDependency(bindInfo.getParentContextServiceName(), ServiceBasedNamingStore.class, getNamingStoreInjector()).addListener((controller, event) -> {
            switch (event) {
                case UP: {
                    bound.set(true);
                    LOGGER.infoBoundSystemContext(BUNDLE_CONTEXT_BINDING_NAME);
                    break;
                }
                case DOWN: {
                    // The listener also gets this event when the service is installed
                    if (bound.getAndSet(false))
                        LOGGER.infoUnboundSystemContext(BUNDLE_CONTEXT_BINDING_NAME);
                    break;
                }
            }
        });
        if (listener != null) {
            builder.addListener(listener);
        }
        builder.setInitialMode(Mode.PASSIVE);
        return builder.install();
    }

    public static ServiceName getBinderServiceName() {
        ContextNames.BindInfo bindInfo = ContextNames.bindInfoFor(BUNDLE_CONTEXT_BINDING_NAME);
        return bindInfo.getBinderServiceName();
    }
}
