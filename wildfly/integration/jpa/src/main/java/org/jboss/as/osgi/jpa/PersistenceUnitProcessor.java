/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
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
package org.jboss.as.osgi.jpa;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManagerFactory;

import org.jboss.as.jpa.config.PersistenceUnitMetadataHolder;
import org.jboss.as.jpa.processor.JpaAttachments;
import org.jboss.as.osgi.OSGiConstants;
import org.jboss.as.server.deployment.AttachmentKey;
import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.as.server.deployment.module.ResourceRoot;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;
import org.jboss.osgi.framework.spi.BundleManager;
import org.jboss.osgi.resolver.XBundle;
import org.jboss.osgi.resolver.XBundleRevision;
import org.jipijapa.plugin.spi.PersistenceUnitMetadata;
import org.jipijapa.plugin.spi.PersistenceUnitService;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.SynchronousBundleListener;
import org.osgi.service.jpa.EntityManagerFactoryBuilder;

/**
 * Install the services for a persistence bundle.
 *
 * @author thomas.diesler@jboss.com
 * @since 01-Sep-2012
 */
public class PersistenceUnitProcessor implements DeploymentUnitProcessor {

    private static AttachmentKey<BundleListener> BUNDLE_LISTENER_KEY = AttachmentKey.create(BundleListener.class);

    @Override
    public void deploy(final DeploymentPhaseContext phaseContext) throws DeploymentUnitProcessingException {
        final DeploymentUnit depUnit = phaseContext.getDeploymentUnit();
        final ServiceName puServiceName = depUnit.getAttachment(JpaAttachments.PERSISTENCE_UNIT_SERVICE_KEY);
        final XBundleRevision brev = depUnit.getAttachment(OSGiConstants.BUNDLE_REVISION_KEY);
        if (brev == null || puServiceName == null)
            return;

        BundleContext syscontext = depUnit.getAttachment(OSGiConstants.SYSTEM_CONTEXT_KEY);
        BundleListener listener = new SynchronousBundleListener() {
            @Override
            public void bundleChanged(BundleEvent event) {
                if (event.getBundle() == brev.getBundle() && event.getType() == BundleEvent.STARTED) {
                    BundleManager bundleManager = depUnit.getAttachment(OSGiConstants.BUNDLE_MANAGER_KEY);
                    EntityManagerFactoryRegistration.addService(phaseContext.getServiceTarget(), bundleManager, puServiceName, brev, getPersistenceUnits(depUnit));
                }
            }
        };
        syscontext.addBundleListener(listener);
        depUnit.putAttachment(BUNDLE_LISTENER_KEY, listener);
    }

    @Override
    public void undeploy(DeploymentUnit depUnit) {
        BundleListener listener = depUnit.getAttachment(BUNDLE_LISTENER_KEY);
        if (listener != null) {
            BundleContext syscontext = depUnit.getAttachment(OSGiConstants.SYSTEM_CONTEXT_KEY);
            syscontext.removeBundleListener(listener);
        }
    }

    // The persistence units of the deployment, they are attached to its root
    private static List<PersistenceUnitMetadata> getPersistenceUnits(DeploymentUnit depUnit) {
        List<PersistenceUnitMetadata> result = new ArrayList<PersistenceUnitMetadata>();
        ResourceRoot deploymentRoot = depUnit.getAttachment(Attachments.DEPLOYMENT_ROOT);
        for (PersistenceUnitMetadataHolder holder : new PersistenceUnitMetadataHolder[] {
                deploymentRoot != null ? deploymentRoot.getAttachment(PersistenceUnitMetadataHolder.PERSISTENCE_UNITS) : null,
                depUnit.getAttachment(PersistenceUnitMetadataHolder.PERSISTENCE_UNITS) }) {
            if (holder != null && holder.getPersistenceUnits() != null)
                result.addAll(holder.getPersistenceUnits());
        }
        return result;
    }

    static class EntityManagerFactoryRegistration implements Service<ServiceRegistration<EntityManagerFactory>> {

        // The provider of a persistence unit that names none
        private static final String DEFAULT_PROVIDER = "org.hibernate.jpa.HibernatePersistenceProvider";

        private final InjectedValue<PersistenceUnitService> injectedPersistenceUnitService = new InjectedValue<PersistenceUnitService>();
        private final XBundle bundle;
        private final List<PersistenceUnitMetadata> persistenceUnits;
        private ServiceRegistration<EntityManagerFactory> registration;
        private ServiceRegistration<EntityManagerFactoryBuilder> builderRegistration;

        static void addService(ServiceTarget serviceTarget, BundleManager bundleManager, ServiceName puServiceName, XBundleRevision brev, List<PersistenceUnitMetadata> persistenceUnits) {
            ServiceName serviceName = puServiceName.append(EntityManagerFactory.class.getSimpleName());
            EntityManagerFactoryRegistration service = new EntityManagerFactoryRegistration(brev.getBundle(), persistenceUnits);
            ServiceBuilder<ServiceRegistration<EntityManagerFactory>> builder = serviceTarget.addService(serviceName, service);
            builder.addDependency(puServiceName, PersistenceUnitService.class, service.injectedPersistenceUnitService);
            builder.setInitialMode(Mode.PASSIVE);
            builder.install();
        }

        private EntityManagerFactoryRegistration(XBundle bundle, List<PersistenceUnitMetadata> persistenceUnits) {
            this.bundle = bundle;
            this.persistenceUnits = persistenceUnits;
        }

        @Override
        public void start(StartContext context) throws StartException {
            BundleContext bundleContext = bundle.getBundleContext();
            PersistenceUnitService puService = injectedPersistenceUnitService.getValue();
            final EntityManagerFactory emf = puService.getEntityManagerFactory();

            // The name of the unit is the one in its descriptor, WildFly scopes it with the deployment (127.4.1)
            String scopedName = puService.getScopedPersistenceUnitName();
            PersistenceUnitMetadata metadata = null;
            for (PersistenceUnitMetadata aux : persistenceUnits) {
                if (scopedName.equals(aux.getScopedPersistenceUnitName()))
                    metadata = aux;
            }
            String unitName = metadata != null ? metadata.getPersistenceUnitName() : scopedName.substring(scopedName.indexOf('#') + 1);
            String provider = metadata != null ? metadata.getPersistenceProviderClassName() : null;

            Dictionary<String, Object> properties = new Hashtable<String, Object>();
            properties.put(EntityManagerFactoryBuilder.JPA_UNIT_NAME, unitName);
            properties.put(EntityManagerFactoryBuilder.JPA_UNIT_VERSION, bundle.getVersion().toString());
            properties.put(EntityManagerFactoryBuilder.JPA_UNIT_PROVIDER, provider != null ? provider : DEFAULT_PROVIDER);
            registration = bundleContext.registerService(EntityManagerFactory.class, emf, properties);

            // The persistence unit is managed by the container, the builder gives its EntityManagerFactory (127.4.3)
            EntityManagerFactoryBuilder builder = new EntityManagerFactoryBuilder() {
                @Override
                public EntityManagerFactory createEntityManagerFactory(Map<String, Object> props) {
                    if (props != null && props.isEmpty() == false)
                        throw new IllegalArgumentException("The persistence unit is managed by the container, its properties cannot be changed: " + props);
                    return emf;
                }
            };
            builderRegistration = bundleContext.registerService(EntityManagerFactoryBuilder.class, builder, properties);
        }

        @Override
        public void stop(StopContext context) {
            if (builderRegistration != null) {
                builderRegistration.unregister();
                builderRegistration = null;
            }
            if (registration != null) {
                registration.unregister();
                registration = null;
            }
        }

        @Override
        public ServiceRegistration<EntityManagerFactory> getValue() throws IllegalStateException, IllegalArgumentException {
            return registration;
        }
    }
}