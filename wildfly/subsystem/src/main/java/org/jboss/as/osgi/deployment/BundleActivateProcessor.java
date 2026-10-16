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

package org.jboss.as.osgi.deployment;

import static org.jboss.as.ee.component.Attachments.EE_MODULE_DESCRIPTION;
import static org.jboss.as.osgi.OSGiLogger.LOGGER;
import static org.jboss.as.osgi.OSGiMessages.MESSAGES;
import static org.jboss.osgi.framework.spi.IntegrationConstants.BUNDLE_ACTIVATOR_FACTORY_KEY;

import java.util.function.Supplier;

import org.jboss.as.ee.component.Component;
import org.jboss.as.ee.component.ComponentDescription;
import org.jboss.as.ee.component.ComponentInstance;
import org.jboss.as.ee.component.EEModuleDescription;
import org.jboss.as.osgi.OSGiConstants;
import org.jboss.as.osgi.management.OperationAssociation;
import org.jboss.as.osgi.service.BundleLifecycleIntegration;
import org.jboss.as.server.deployment.AttachmentKey;
import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.Attachments.BundleState;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.msc.service.Service;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;
import org.jboss.osgi.deployment.deployer.Deployment;
import org.jboss.osgi.framework.spi.BundleManager;
import org.jboss.osgi.framework.spi.IntegrationServices;
import org.jboss.osgi.framework.spi.StartLevelManager;
import org.jboss.osgi.metadata.OSGiMetaData;
import org.jboss.osgi.resolver.XBundle;
import org.jboss.osgi.resolver.XBundleRevision;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleException;

/**
 * Attempt to activate the OSGi deployment.
 *
 * @author Thomas.Diesler@jboss.com
 * @since 20-Jun-2012
 */
public class BundleActivateProcessor implements DeploymentUnitProcessor {

    /**
     * The {@link StartCoordinator} of the bundle of a deployment, attached when the bundle is installed
     */
    public static final AttachmentKey<StartCoordinator> START_COORDINATOR_KEY = AttachmentKey.create(StartCoordinator.class);

    @Override
    public void deploy(final DeploymentPhaseContext phaseContext) throws DeploymentUnitProcessingException {
        DeploymentUnit depUnit = phaseContext.getDeploymentUnit();
        XBundleRevision brev = depUnit.getAttachment(OSGiConstants.BUNDLE_REVISION_KEY);
        if (brev == null || brev.isFragment())
            return;

        ServiceController<XBundle> controller = BundleActivateService.addService(phaseContext.getServiceTarget(), depUnit, brev.getBundle());
        phaseContext.addDependency(controller.getName(), AttachmentKey.create(XBundle.class));
    }

    @Override
    public void undeploy(final DeploymentUnit depUnit) {
    }

    /**
     * Every start of the bundle gets a new instance of the activator component, with its injections (4.4.6). The
     * instance of the previous start is destroyed.
     */
    static final class ActivatorFactory implements Supplier<BundleActivator> {

        private final Component component;
        private ComponentInstance current;

        ActivatorFactory(Component component) {
            this.component = component;
        }

        @Override
        public synchronized BundleActivator get() {
            destroy();
            current = component.createInstance();
            return (BundleActivator) current.getInstance();
        }

        synchronized void destroy() {
            if (current != null) {
                current.destroy();
                current = null;
            }
        }
    }

    /**
     * Two agents start a deployed bundle: the Activate service of its deployment, and the framework with a transient
     * start, e.g. the start level service when the start level increases. At boot the start level service starts the
     * persistently started bundles while their deployments are still deployed. The activator is called once: a
     * transient start leaves the bundle to its deployment while the deployment has not tried to start it, and does not
     * start it again when the deployment failed to start it.
     */
    public static final class StartCoordinator {

        private boolean attempted;
        private boolean skipped;
        private BundleException failure;

        synchronized void attemptStarted() {
            attempted = false;
            skipped = false;
            failure = null;
        }

        /**
         * The start attempt of the deployment is done. It is repeated (returns false) when a transient start was
         * skipped meanwhile and the attempt did not start the bundle (its start level was not reached yet).
         */
        synchronized boolean attemptCompleted(XBundle bundle, BundleException ex) {
            if (ex == null && skipped && (bundle.getState() & (Bundle.STARTING | Bundle.ACTIVE)) == 0) {
                skipped = false;
                return false;
            }
            attempted = true;
            failure = ex;
            return true;
        }

        synchronized void attemptEnded() {
            attempted = true;
        }

        /**
         * Whether a transient start may start the bundle: not before its deployment tried to start it, and not when
         * that failed
         */
        public synchronized boolean isTransientStartAllowed() {
            if (!attempted) {
                skipped = true;
                return false;
            }
            return failure == null;
        }

        /**
         * The bundle was started: a later transient start may start it again
         */
        public synchronized void bundleStarted() {
            failure = null;
        }
    }

    static class BundleActivateService implements Service<XBundle> {

        private final InjectedValue<Component> injectedComponent = new InjectedValue<Component>();
        private final DeploymentUnit depUnit;
        private final XBundle bundle;

        static ServiceController<XBundle> addService(ServiceTarget serviceTarget, DeploymentUnit depUnit, XBundle bundle) {
            ServiceName serviceName = depUnit.getServiceName().append("Activate");
            BundleActivateService service = new BundleActivateService(depUnit, bundle);
            ServiceBuilder<XBundle> builder = serviceTarget.addService(serviceName, service);
            // Add a dependency on the BundleActivator component
            OSGiMetaData metadata = depUnit.getAttachment(OSGiConstants.OSGI_METADATA_KEY);
            if (metadata != null && metadata.getBundleActivator() != null) {
                String activatorClass = metadata.getBundleActivator();
                EEModuleDescription moduleDescription = depUnit.getAttachment(EE_MODULE_DESCRIPTION);
                if (moduleDescription != null) {
                    for (ComponentDescription componentDescription : moduleDescription.getComponentDescriptions()) {
                        if (activatorClass.equals(componentDescription.getComponentClassName())) {
                            ServiceName startServiceName = componentDescription.getStartServiceName();
                            builder.addDependency(startServiceName, Component.class, service.injectedComponent);
                        }
                    }
                }
            }
            // Add other named dependencies
            for (ServiceName sn : depUnit.getAttachmentList(Attachments.BUNDLE_ACTIVE_DEPENDENCIES)) {
                builder.requires(sn);
            }
            return builder.install();
        }

        private BundleActivateService(DeploymentUnit depUnit, XBundle bundle) {
            this.depUnit = depUnit;
            this.bundle = bundle;
        }

        @Override
        public void start(StartContext context) throws StartException {
            Deployment deployment = depUnit.getAttachment(OSGiConstants.DEPLOYMENT_KEY);
            BundleManager bundleManager = depUnit.getAttachment(OSGiConstants.BUNDLE_MANAGER_KEY);
            Component activatorComponent = injectedComponent.getOptionalValue();
            if (activatorComponent != null && deployment.getAttachment(BUNDLE_ACTIVATOR_FACTORY_KEY) == null) {
                deployment.putAttachment(BUNDLE_ACTIVATOR_FACTORY_KEY, new ActivatorFactory(activatorComponent));
            }
            StartCoordinator coordinator = depUnit.getAttachment(START_COORDINATOR_KEY);
            if (coordinator != null)
                coordinator.attemptStarted();
            // A deployed bundle that the activator uninstalls is undeployed after the operation that runs this service,
            // not by a nested operation from this thread, which would wait for this service
            boolean marked = BundleLifecycleIntegration.isDeploymentUndeploy();
            if (!marked)
                BundleLifecycleIntegration.setDeploymentUndeploy(true);
            try {
                if (bundle.getState() != Bundle.ACTIVE) {
                    // The activator runs within the deploy operation: a bundle that it installs is installed directly, a
                    // nested deploy operation would wait for the one that runs this service (AS7-5642)
                    boolean associated = OperationAssociation.INSTANCE.getAssociation() != null;
                    if (!associated)
                        OperationAssociation.INSTANCE.setAssociation(new ModelNode(depUnit.getName()));
                    try {
                        BundleException failure;
                        do {
                            failure = null;
                            try {
                                bundleManager.startBundle(bundle, Bundle.START_ACTIVATION_POLICY);
                            } catch (BundleException ex) {
                                failure = ex;
                            }
                        } while (coordinator != null && !coordinator.attemptCompleted(bundle, failure));
                        if (failure != null)
                            throw MESSAGES.cannotStartBundle(failure, bundle);
                        depUnit.putAttachment(Attachments.BUNDLE_STATE_KEY, BundleState.ACTIVE);
                    } finally {
                        if (!associated)
                            OperationAssociation.INSTANCE.removeAssociation();
                    }
                }
            } finally {
                if (!marked)
                    BundleLifecycleIntegration.setDeploymentUndeploy(false);
                if (coordinator != null)
                    coordinator.attemptEnded();
            }
        }

        @Override
        public void stop(StopContext context) {
            // A deployed bundle that an activator uninstalls in its stop is undeployed after the operation that stops
            // this service (an undeploy, a server stop), not by a nested operation from this thread
            boolean marked = BundleLifecycleIntegration.isDeploymentUndeploy();
            if (!marked)
                BundleLifecycleIntegration.setDeploymentUndeploy(true);
            try {
                stopBundle();
            } finally {
                if (!marked)
                    BundleLifecycleIntegration.setDeploymentUndeploy(false);
            }
        }

        private void stopBundle() {
            // When the server stops, the framework moves to start level 0 first: the bundles are stopped by descending
            // start level, not in the order in which their deployments are stopped
            BundleManager manager = depUnit.getAttachment(OSGiConstants.BUNDLE_MANAGER_KEY);
            if (manager != null && BundleInstallProcessor.isServerStopping(manager)) {
                ServiceController<?> controller = manager.getServiceContainer().getService(IntegrationServices.START_LEVEL_PLUGIN);
                Object startLevelManager = controller != null ? controller.getValue() : null;
                if (startLevelManager instanceof StartLevelManager)
                    ((StartLevelManager) startLevelManager).decreaseFrameworkStartLevel(0);
            }
            if (bundle.getState() == Bundle.STARTING || bundle.getState() == Bundle.ACTIVE) {
                try {
                    BundleManager bundleManager = depUnit.getAttachment(OSGiConstants.BUNDLE_MANAGER_KEY);
                    bundleManager.stopBundle(bundle, Bundle.STOP_TRANSIENT);
                    depUnit.putAttachment(Attachments.BUNDLE_STATE_KEY, BundleState.RESOLVED);
                } catch (BundleException ex) {
                    LOGGER.debugf(ex, "Cannot stop bundle: %s", bundle);
                }
            }
            // The component of the activator goes down with this service, the activator instances with it
            Deployment deployment = depUnit.getAttachment(OSGiConstants.DEPLOYMENT_KEY);
            Supplier<BundleActivator> factory = deployment != null ? deployment.removeAttachment(BUNDLE_ACTIVATOR_FACTORY_KEY) : null;
            if (factory instanceof ActivatorFactory)
                ((ActivatorFactory) factory).destroy();
        }

        @Override
        public XBundle getValue() {
            return bundle;
        }
    }
}
