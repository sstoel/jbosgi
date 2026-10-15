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

import static org.jboss.as.osgi.OSGiLogger.LOGGER;
import static org.jboss.as.server.deployment.Attachments.BUNDLE_STATE_KEY;
import static org.jboss.as.server.deployment.Attachments.MODULE;
import static org.jboss.msc.service.ServiceController.State.STOPPING;
import static org.jboss.msc.service.ServiceController.State.UP;
import static org.jboss.osgi.framework.spi.IntegrationConstants.MODULE_IDENTIFIER_KEY;
import static org.jboss.osgi.framework.spi.IntegrationConstants.STORAGE_STATE_KEY;

import org.jboss.as.osgi.OSGiConstants;
import org.jboss.as.osgi.service.BundleLifecycleIntegration;
import org.jboss.as.osgi.service.InitialDeploymentTracker;
import org.jboss.as.server.Services;
import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.Attachments.BundleState;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.as.server.deployment.module.ResourceRoot;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceRegistry;
import org.jboss.osgi.deployment.deployer.Deployment;
import org.jboss.osgi.framework.spi.BundleManager;
import org.jboss.osgi.framework.spi.IntegrationConstants;
import org.jboss.osgi.framework.spi.IntegrationServices;
import org.jboss.osgi.framework.spi.StorageManager;
import org.jboss.osgi.framework.spi.StorageState;
import org.jboss.osgi.resolver.XBundle;
import org.jboss.osgi.resolver.XBundleRevision;
import org.jboss.osgi.resolver.XResource.State;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.wiring.BundleWiring;

/**
 * Processes deployments that have OSGi metadata attached.
 *
 * @author Thomas.Diesler@jboss.com
 * @since 20-Sep-2010
 */
public class BundleInstallProcessor implements DeploymentUnitProcessor {

    private final InitialDeploymentTracker deploymentTracker;

    public BundleInstallProcessor(InitialDeploymentTracker deploymentTracker) {
        this.deploymentTracker = deploymentTracker;
    }

    @Override
    public void deploy(final DeploymentPhaseContext phaseContext) throws DeploymentUnitProcessingException {
        final DeploymentUnit depUnit = phaseContext.getDeploymentUnit();
        final Deployment deployment = depUnit.getAttachment(OSGiConstants.DEPLOYMENT_KEY);
        if (deployment == null)
            return;

        try {
            BundleManager bundleManager = depUnit.getAttachment(OSGiConstants.BUNDLE_MANAGER_KEY);
            BundleContext syscontext = depUnit.getAttachment(OSGiConstants.SYSTEM_CONTEXT_KEY);
            // Every deployment of a bundle that is still in the storage gets its identity back: its id and data area
            // (4.5.2), also deployments of the deployment scanner, which are not in the persisted management model
            if (deploymentTracker.hasDeploymentName(depUnit.getName()) || isStoredBundle(bundleManager, deployment)) {
                restoreStorageState(phaseContext, deployment);
            }
            // Not the service target of the deployment phase: the revision keeps it, and it references the management model
            // of the deploy operation. The module services of the revision are installed by the ModuleLoaderIntegration
            XBundleRevision brev = bundleManager.installBundleRevision(syscontext, deployment, null);
            depUnit.putAttachment(OSGiConstants.BUNDLE_REVISION_KEY, brev);
            depUnit.putAttachment(BUNDLE_STATE_KEY, BundleState.INSTALLED);
            // The bundle is started by the deployment, the framework may try to start it before (BundleActivateProcessor)
            if (!brev.isFragment())
                depUnit.putAttachment(BundleActivateProcessor.START_COORDINATOR_KEY, new BundleActivateProcessor.StartCoordinator());
        } catch (BundleException ex) {
            throw new DeploymentUnitProcessingException(ex);
        }
    }

    @Override
    public void undeploy(final DeploymentUnit depUnit) {
        XBundleRevision brev = depUnit.getAttachment(OSGiConstants.BUNDLE_REVISION_KEY);
        if (brev == null)
            return;

        XBundle bundle = brev.getBundle();
        BundleManager bundleManager = depUnit.getAttachment(OSGiConstants.BUNDLE_MANAGER_KEY);

        // Bundle.update replaces the deployment of the bundle: the bundle stays and the revision before the update keeps
        // its content while it is in use. When the deployment of the new revision is rolled back the bundle gets back the
        // revision it had
        XBundleRevision updateOrigin = BundleLifecycleIntegration.getUpdateOrigin(bundle);
        if (updateOrigin != null) {
            if (brev != updateOrigin && brev.getState() != State.UNINSTALLED) {
                bundleManager.removeRevision(brev, 0);
            }
            retainContentOfRevisionInUse(depUnit, brev);
            return;
        }

        // Bundle.uninstall undeploys the deployment: it uninstalls the bundle itself when the undeploy is done,
        // when the undeploy is rolled back the deployment is deployed again with the bundle as it was
        if (BundleLifecycleIntegration.isBundleUndeploying(bundle))
            return;

        if (uninstallRequired(depUnit, brev)) {
            BundleLifecycleIntegration.setDeploymentUndeploy(true);
            try {
                int options = getUninstallOptions(bundleManager);
                bundleManager.uninstallBundle(bundle, options);
            } catch (BundleException ex) {
                Deployment dep = depUnit.getAttachment(OSGiConstants.DEPLOYMENT_KEY);
                LOGGER.errorFailedToUninstallDeployment(ex, dep);
            } finally {
                BundleLifecycleIntegration.setDeploymentUndeploy(false);
            }
        } else if (brev.getState() != State.UNINSTALLED) {
            BundleWiring wiring = brev.getWiringSupport().getWiring(false);
            if (wiring == null || !wiring.isInUse()) {
                bundleManager.removeRevision(brev, 0);
            }
        }
        retainContentOfRevisionInUse(depUnit, brev);
    }

    /**
     * The revision of an undeployed bundle that is still in use (removal pending) loads its classes and resources from
     * the content of the deployment until it is removed: the revision takes the mount of the deployment root, which the
     * deployment would close now, and closes it when it is removed.
     */
    private void retainContentOfRevisionInUse(DeploymentUnit depUnit, XBundleRevision brev) {
        if (brev.getState() == State.UNINSTALLED)
            return;
        BundleWiring wiring = brev.getWiringSupport().getWiring(false);
        if (wiring == null || !wiring.isInUse())
            return;
        ResourceRoot deploymentRoot = depUnit.getAttachment(Attachments.DEPLOYMENT_ROOT);
        if (depUnit.getParent() != null || deploymentRoot == null || deploymentRoot.getMountHandle() == null)
            return;
        BundleLifecycleIntegration.retainContent(depUnit.getName(), brev, deploymentRoot.getMountHandle());
        depUnit.putAttachment(Attachments.DEPLOYMENT_ROOT, new ResourceRoot(deploymentRoot.getRootName(), deploymentRoot.getRoot(), null));
    }

    private boolean isStoredBundle(BundleManager bundleManager, Deployment deployment) {
        // Not for an update or a refresh of an installed bundle: these get a new revision
        if (deployment.isBundleUpdate() || deployment.getAttachment(IntegrationConstants.BUNDLE_KEY) != null)
            return false;
        return bundleManager.getBundleByLocation(deployment.getLocation()) == null;
    }

    private void restoreStorageState(final DeploymentPhaseContext phaseContext, final Deployment deployment) {
        ServiceRegistry serviceRegistry = phaseContext.getServiceRegistry();
        StorageManager storageProvider = (StorageManager) serviceRegistry.getRequiredService(IntegrationServices.STORAGE_MANAGER_PLUGIN).getValue();
        StorageState storageState = storageProvider.getStorageState(deployment.getLocation());
        if (storageState != null) {
            deployment.setAutoStart(storageState.isPersistentlyStarted());
            deployment.putAttachment(STORAGE_STATE_KEY, storageState);
        }
    }

    private boolean uninstallRequired(DeploymentUnit depUnit, XBundleRevision brev) {

        // No uninstall when activation failed
        Boolean startFailed = depUnit.removeAttachment(OSGiConstants.DEFERRED_ACTIVATION_FAILED);
        if (Boolean.TRUE.equals(startFailed))
            return false;

        // No uninstall if the bundle is already uninstalled
        XBundle bundle = brev.getBundle();
        if (bundle.getState() == Bundle.UNINSTALLED || brev.getState() == State.UNINSTALLED)
            return false;

        // No uninstall if this is not the current revision
        if (bundle.getBundleRevision() != brev)
            return false;

        // No uninstall if the bundle is refreshing
        if (BundleLifecycleIntegration.isBundleRefreshing(bundle))
            return false;

        return true;
    }

    private int getUninstallOptions(BundleManager bundleManager) {
        return isServerStopping(bundleManager) ? Bundle.STOP_TRANSIENT : 0;
    }

    /**
     * True when the server stops or reloads
     */
    static boolean isServerStopping(BundleManager bundleManager) {
        ServiceContainer serviceContainer = bundleManager.getServiceContainer();
        ServiceController<?> controller = serviceContainer.getRequiredService(Services.JBOSS_SERVER_CONTROLLER);
        boolean stopRequested = controller.getState() == UP && (controller.getMode() == ServiceController.Mode.REMOVE || controller.getMode() == ServiceController.Mode.NEVER);
        // A :reload stops the root service jboss.as, the server controller service keeps its mode. The bundles of the
        // deployments stay installed like on a server stop: their persistent state and data area are kept
        ServiceController<?> root = serviceContainer.getService(Services.JBOSS_AS);
        stopRequested |= root != null && root.getMode() != ServiceController.Mode.ACTIVE;
        return stopRequested;
    }
}
