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

import static org.jboss.as.osgi.OSGiLogger.LOGGER;
import static org.jboss.as.osgi.OSGiMessages.MESSAGES;
import static org.jboss.as.server.Services.JBOSS_SERVER_CONTROLLER;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jboss.as.controller.ModelController;
import org.jboss.as.controller.ModelControllerClientFactory;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.OperationBuilder;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.client.helpers.standalone.DeploymentAction;
import org.jboss.as.controller.client.helpers.standalone.DeploymentPlanBuilder;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentActionResult;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentManager;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentPlanResult;
import org.jboss.as.controller.client.helpers.standalone.ServerUpdateActionResult;
import org.jboss.as.controller.client.helpers.standalone.UndeployDeploymentPlanBuilder;
import org.jboss.as.osgi.OSGiConstants;
import org.jboss.as.osgi.deployment.BundleActivateProcessor;
import org.jboss.as.osgi.deployment.BundleActivateProcessor.StartCoordinator;
import org.jboss.as.osgi.deployment.BundleDeploymentProcessor;
import org.jboss.as.osgi.management.OperationAssociation;
import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUtils;
import org.jboss.as.server.deployment.Phase;
import org.jboss.as.server.moduleservice.ServiceModuleLoader;
import org.jboss.dmr.ModelNode;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.msc.service.LifecycleEvent;
import org.jboss.msc.service.LifecycleListener;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.msc.service.ServiceController.State;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceRegistry;
import org.jboss.msc.service.StabilityMonitor;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;
import org.jboss.osgi.deployment.deployer.Deployment;
import org.jboss.osgi.framework.FrameworkMessages;
import org.jboss.osgi.framework.Services;
import org.jboss.osgi.framework.spi.BundleLifecycle;
import org.jboss.osgi.framework.spi.BundleLifecyclePlugin;
import org.jboss.osgi.framework.spi.BundleManager;
import org.jboss.osgi.framework.spi.DeploymentProvider;
import org.jboss.osgi.framework.spi.FutureServiceValue;
import org.jboss.osgi.framework.spi.IntegrationConstants;
import org.jboss.osgi.framework.spi.IntegrationServices;
import org.jboss.osgi.framework.spi.LockManager;
import org.jboss.osgi.framework.spi.StorageManager;
import org.jboss.osgi.resolver.XBundle;
import org.jboss.osgi.resolver.XBundleRevision;
import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.resolver.XResolveContext;
import org.jboss.osgi.resolver.XResolver;
import org.jboss.osgi.resolver.XResource;
import org.jboss.osgi.spi.AttachmentKey;
import org.jboss.osgi.vfs.AbstractVFS;
import org.jboss.osgi.vfs.VirtualFile;
import org.jboss.vfs.VFSUtils;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.startlevel.FrameworkStartLevel;
import org.osgi.framework.wiring.BundleRevisions;
import org.osgi.service.resolver.ResolutionException;

/**
 * An {@link org.jboss.osgi.framework.spi.IntegrationService} that that handles the bundle lifecycle.
 *
 * @author thomas.diesler@jboss.com
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 * @since 24-Nov-2010
 */
public final class BundleLifecycleIntegration extends BundleLifecyclePlugin {

    private static final AttachmentKey<String> RUNTIME_NAME_KEY = AttachmentKey.create(String.class);
    private static final AttachmentKey<Boolean> BUNDLE_REFRESHING_KEY = AttachmentKey.create(Boolean.class);
    private static final AttachmentKey<Boolean> BUNDLE_UNDEPLOYING_KEY = AttachmentKey.create(Boolean.class);
    private static final AttachmentKey<Boolean> REVISION_UNDEPLOYED_KEY = AttachmentKey.create(Boolean.class);
    // The revision is resolved with its deployment: other deployments need its module
    public static final AttachmentKey<Boolean> RESOLVE_ON_DEPLOY_KEY = AttachmentKey.create(Boolean.class);
    // The revision of a bundle whose deployment is replaced by Bundle.update
    private static final AttachmentKey<XBundleRevision> BUNDLE_UPDATING_KEY = AttachmentKey.create(XBundleRevision.class);
    // The content of an undeployed revision that is still in use, closed when the revision is removed
    public static final AttachmentKey<Closeable> RETAINED_CONTENT_KEY = AttachmentKey.create(Closeable.class);

    private static final Map<String, Deployment> deploymentMap = new HashMap<String, Deployment>();
    private static final ThreadLocal<Boolean> deploymentUndeploy = new ThreadLocal<Boolean>();

    private final InjectedValue<ModelControllerClientFactory> injectedModelControllerClientFactory = new InjectedValue<ModelControllerClientFactory>();
    private final InjectedValue<BundleManager> injectedBundleManager = new InjectedValue<BundleManager>();
    private final InjectedValue<XEnvironment> injectedEnvironment = new InjectedValue<XEnvironment>();
    private final InjectedValue<XResolver> injectedResolver = new InjectedValue<XResolver>();
    private final InjectedValue<StorageManager> injectedStorageManager = new InjectedValue<StorageManager>();
    private final InjectedValue<DeploymentProvider> injectedDeploymentManager = new InjectedValue<DeploymentProvider>();
    private final InjectedValue<LockManager> injectedLockManager = new InjectedValue<LockManager>();
    private ServerDeploymentManager serverDeploymentManager;
    private ModelControllerClient modelControllerClient;
    private ExecutorService undeployExecutor;
    private ExecutorService clientExecutor;

    @Override
    protected void addServiceDependencies(ServiceBuilder<BundleLifecycle> builder) {
        super.addServiceDependencies(builder);
        builder.addDependency(ServiceName.parse("org.wildfly.management.model-controller-client-factory"), ModelControllerClientFactory.class, injectedModelControllerClientFactory);
        builder.addDependency(IntegrationServices.STORAGE_MANAGER_PLUGIN, StorageManager.class, injectedStorageManager);
        builder.addDependency(IntegrationServices.DEPLOYMENT_PROVIDER_PLUGIN, DeploymentProvider.class, injectedDeploymentManager);
        builder.addDependency(IntegrationServices.LOCK_MANAGER_PLUGIN, LockManager.class, injectedLockManager);
        builder.addDependency(Services.BUNDLE_MANAGER, BundleManager.class, injectedBundleManager);
        builder.addDependency(Services.ENVIRONMENT, XEnvironment.class, injectedEnvironment);
        builder.addDependency(Services.RESOLVER, XResolver.class, injectedResolver);
        builder.requires(Services.FRAMEWORK_CREATE);
    }

    @Override
    public void start(StartContext startContext) throws StartException {
        super.start(startContext);
        ModelControllerClientFactory mcff = injectedModelControllerClientFactory.getValue();
        clientExecutor = Executors.newCachedThreadPool();
        modelControllerClient = mcff.createClient(clientExecutor);
        serverDeploymentManager =  ServerDeploymentManager.Factory.create(modelControllerClient);
        undeployExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable run) {
                Thread thread = new Thread(run, "OSGi Bundle Undeploy Thread");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    @Override
    public void stop(StopContext context) {
        undeployExecutor.shutdownNow();
        try {
            modelControllerClient.close();
        } catch (IOException ex) {
            // ignore
        }
        clientExecutor.shutdown();
        super.stop(context);
    }

    @Override
    protected BundleLifecycle createServiceValue(StartContext startContext) throws StartException {
        return new BundleLifecycleImpl();
    }

    public static Deployment getDeployment(String runtimeName) {
        synchronized (deploymentMap) {
            return deploymentMap.get(runtimeName);
        }
    }

    public static Deployment removeDeployment(String runtimeName) {
        synchronized (deploymentMap) {
            return deploymentMap.remove(runtimeName);
        }
    }

    private static void removeDeployment(String runtimeName, Deployment dep) {
        synchronized (deploymentMap) {
            deploymentMap.remove(runtimeName, dep);
        }
    }

    private void putDeployment(String runtimeName, final Deployment dep) {
        synchronized (deploymentMap) {
            deploymentMap.put(runtimeName, dep);
        }
    }

    /**
     * Mark the current thread as the thread that undeploys a bundle deployment.
     *
     * A management operation is active then. Other deployments cannot be undeployed before it completed.
     */
    public static void setDeploymentUndeploy(boolean flag) {
        if (flag)
            deploymentUndeploy.set(Boolean.TRUE);
        else
            deploymentUndeploy.remove();
    }

    // The undeployed revisions in use that keep the content of their deployment, by deployment name
    private static final Map<String, XBundleRevision> retainedContents = new HashMap<String, XBundleRevision>();

    /**
     * The revision keeps the mount of the content of its deployment until it is removed
     */
    public static void retainContent(String deploymentName, XBundleRevision brev, Closeable mount) {
        synchronized (retainedContents) {
            brev.putAttachment(RETAINED_CONTENT_KEY, mount);
            XBundleRevision previous = retainedContents.put(deploymentName, brev);
            if (previous != null && previous != brev)
                VFSUtils.safeClose(previous.removeAttachment(RETAINED_CONTENT_KEY));
        }
    }

    /**
     * A deployment of the name is deployed again: its content is mounted at the same place, the mount that an older
     * revision kept is closed
     */
    public static void releaseContent(String deploymentName) {
        synchronized (retainedContents) {
            XBundleRevision brev = retainedContents.remove(deploymentName);
            if (brev != null)
                VFSUtils.safeClose(brev.removeAttachment(RETAINED_CONTENT_KEY));
        }
    }

    private static void releaseContent(XBundleRevision brev) {
        synchronized (retainedContents) {
            retainedContents.values().remove(brev);
            VFSUtils.safeClose(brev.removeAttachment(RETAINED_CONTENT_KEY));
        }
    }

    public static boolean isBundleRefreshing(XBundle bundle) {
        return Boolean.TRUE.equals(bundle.getAttachment(BUNDLE_REFRESHING_KEY));
    }

    /**
     * The revision that the bundle had before the Bundle.update that replaces its deployment now, or null.
     *
     * The update replaces the content of the bundle's deployment: the deployment of that revision is undeployed and the
     * deployment of the new revision is deployed in one management operation. The bundle and the old revision stay, the
     * old revision is removed when the bundle is refreshed.
     */
    public static XBundleRevision getUpdateOrigin(XBundle bundle) {
        return bundle.getAttachment(BUNDLE_UPDATING_KEY);
    }

    /**
     * The runtime name of the deployment of an updated bundle: an updated revision is deployed under a runtime name of
     * its own, the revision before the update keeps its content and its module while it is in use.
     */
    public static String getRevisionRuntimeName(String name, int revid) {
        int dotindex = name.length() - 4;
        if (dotindex > 0 && name.charAt(dotindex) == '.')
            return name.substring(0, dotindex) + "-rev" + revid + name.substring(dotindex);
        return name + "-rev" + revid;
    }

    /**
     * Whether the runtime name is one that {@link #getRevisionRuntimeName(String, int)} gives for the name
     */
    public static boolean isRevisionRuntimeName(String runtimeName, String name) {
        String base = name;
        String suffix = "";
        int dotindex = name.length() - 4;
        if (dotindex > 0 && name.charAt(dotindex) == '.') {
            base = name.substring(0, dotindex);
            suffix = name.substring(dotindex);
        }
        String prefix = base + "-rev";
        if (!runtimeName.startsWith(prefix) || !runtimeName.endsWith(suffix) || runtimeName.length() <= prefix.length() + suffix.length())
            return false;
        String revid = runtimeName.substring(prefix.length(), runtimeName.length() - suffix.length());
        for (int i = 0; i < revid.length(); i++) {
            if (!Character.isDigit(revid.charAt(i)))
                return false;
        }
        return true;
    }

    /**
     * The location of the bundle of a deployment: its runtime name, or the name of the deployment in the management
     * model when the deployment has the runtime name of an updated revision. An updated bundle keeps its location
     * (4.4.1), also when the server starts again with the deployment of the update.
     */
    public static String getBundleLocation(DeploymentUnit depUnit) {
        String runtimeName = depUnit.getName();
        if (depUnit.getParent() == null) {
            String managementName = depUnit.getAttachment(Attachments.MANAGEMENT_NAME);
            if (managementName != null && isRevisionRuntimeName(runtimeName, managementName))
                return managementName;
        }
        return runtimeName;
    }

    /**
     * The name in the management model of the deployment of the bundle deployment, null when it is not a deployment of
     * its own (a subdeployment, or a bundle that was installed without a deployment)
     */
    private static String getManagementName(Deployment deployment) {
        DeploymentUnit depUnit = deployment != null ? deployment.getAttachment(BundleDeploymentProcessor.DEPLOYMENT_UNIT_KEY) : null;
        if (depUnit == null || depUnit.getParent() != null)
            return null;
        String managementName = depUnit.getAttachment(Attachments.MANAGEMENT_NAME);
        return managementName != null ? managementName : depUnit.getName();
    }

    /**
     * True while Bundle.uninstall undeploys the deployment of the bundle: the bundle stays installed until the undeploy
     * is done, a rolled back undeploy finds it as it was.
     */
    public static boolean isBundleUndeploying(XBundle bundle) {
        return Boolean.TRUE.equals(bundle.getAttachment(BUNDLE_UNDEPLOYING_KEY));
    }

    /**
     * Whether a deployment service belongs to the deployment of the given unit.
     *
     * A deployment that is deployed again under the same name gets the same service names, only the
     * {@link DeploymentUnit} of its deployment service tells the deployments apart.
     */
    static boolean isServiceOf(ServiceController<?> controller, DeploymentUnit depUnit) {
        DeploymentUnit rootUnit = depUnit.getParent() != null ? depUnit.getParent() : depUnit;
        ServiceName rootName = rootUnit.getServiceName();
        ServiceController<?> aux = controller;
        while (aux != null && !rootName.equals(aux.getName()))
            aux = aux.getParent();
        // The value of a stopped deployment service is null
        return aux != null && aux.getValue() == rootUnit;
    }

    class BundleLifecycleImpl implements BundleLifecycle {

        private final BundleManager bundleManager;
        private final XEnvironment environment;
        private final XResolver resolver;

        BundleLifecycleImpl() {
            this.bundleManager = injectedBundleManager.getValue();
            this.environment = injectedEnvironment.getValue();
            this.resolver = injectedResolver.getValue();
        }

        @Override
        public XBundleRevision createBundleRevision(BundleContext context, Deployment deployment) throws BundleException {

            // Do the install directly if we have a running management op
            // https://issues.jboss.org/browse/AS7-5642
            if (OperationAssociation.INSTANCE.getAssociation() != null) {
                LOGGER.warnCannotDeployBundleFromManagementOperation(deployment);
                return bundleManager.installBundleRevision(context, deployment, null);
            }

            // Check if a bundle with that location already exists
            if (!deployment.isBundleUpdate() && bundleManager.getBundleByLocation(deployment.getLocation()) != null) {
                return bundleManager.installBundleRevision(context, deployment, null);
            }

            if (deployment.isBundleUpdate()) {
                return createUpdateRevision(context, deployment);
            }

            LOGGER.debugf("Install deployment: %s", deployment);
            String runtimeName = getRuntimeName(deployment);
            putDeployment(runtimeName, deployment);
            try {
                InputStream input = deployment.getRoot().openStream();
                try {
                    ServerDeploymentHelper server = new ServerDeploymentHelper(serverDeploymentManager);
                    server.deploy(runtimeName, input);
                } finally {
                    VFSUtils.safeClose(input);
                }
            } catch (RuntimeException rte) {
                throw rte;
            } catch (Exception ex) {
                throw MESSAGES.cannotDeployBundleRevision(ex, deployment);
            } finally {
                // A deployment that failed before its processors took the Deployment must not hand it to the next one
                removeDeployment(runtimeName, deployment);
            }

            // For an already existing bundle (i.e. same location) the
            // {@link Deployment} may not have the bundle revision attached
            DeploymentUnit depUnit = deployment.getAttachment(BundleDeploymentProcessor.DEPLOYMENT_UNIT_KEY);
            XBundleRevision brev = depUnit.getAttachment(OSGiConstants.BUNDLE_REVISION_KEY);
            return brev;
        }

        /**
         * Bundle.update replaces the content of the bundle's deployment: the deployment keeps its name in the management
         * model and gets the new content, so that the server starts again with the updated bundle (4.4.9). The new
         * revision is deployed under a runtime name of its own, the revision before the update keeps the content of its
         * deployment while it is in use. A refresh gives the deployment the runtime name of the bundle again.
         */
        private XBundleRevision createUpdateRevision(BundleContext context, Deployment deployment) throws BundleException {
            XBundle bundle = deployment.getAttachment(IntegrationConstants.BUNDLE_KEY);
            XBundleRevision origin = bundle.getBundleRevision();
            Deployment originDeployment = origin.getAttachment(IntegrationConstants.DEPLOYMENT_KEY);
            String managementName = originDeployment != null && isDeployed(originDeployment) ? getManagementName(originDeployment) : null;

            // A bundle without a deployment of its own gets the new revision without a deployment too
            if (managementName == null) {
                return bundleManager.installBundleRevision(context, deployment, null);
            }

            int revid = bundle.adapt(BundleRevisions.class).getRevisions().size();
            String runtimeName = getRevisionRuntimeName(managementName, revid);
            deployment.putAttachment(RUNTIME_NAME_KEY, runtimeName);
            LOGGER.debugf("Update deployment %s with: %s", managementName, deployment);
            putDeployment(runtimeName, deployment);
            bundle.putAttachment(BUNDLE_UPDATING_KEY, origin);
            try {
                InputStream input = deployment.getRoot().openStream();
                try {
                    replaceDeployment(managementName, runtimeName, input);
                } finally {
                    VFSUtils.safeClose(input);
                }
                // The deployment of the old revision is gone, the revision is removed with the next refresh
                origin.putAttachment(REVISION_UNDEPLOYED_KEY, Boolean.TRUE);
            } catch (RuntimeException rte) {
                throw rte;
            } catch (Exception ex) {
                throw MESSAGES.cannotDeployBundleRevision(ex, deployment);
            } finally {
                bundle.removeAttachment(BUNDLE_UPDATING_KEY);
                removeDeployment(runtimeName, deployment);
            }

            DeploymentUnit depUnit = deployment.getAttachment(BundleDeploymentProcessor.DEPLOYMENT_UNIT_KEY);
            return depUnit.getAttachment(OSGiConstants.BUNDLE_REVISION_KEY);
        }

        /**
         * Replace the content of the deployment in one management operation: the deployment of the current content is
         * undeployed and the new content is deployed, or nothing changes. The deployment stays persistent or not.
         */
        private void replaceDeployment(String managementName, String runtimeName, InputStream input) throws Exception {
            ModelNode address = new ModelNode();
            address.add(ClientConstants.DEPLOYMENT, managementName);
            ModelNode read = new ModelNode();
            read.get(ClientConstants.OP).set(ClientConstants.READ_ATTRIBUTE_OPERATION);
            read.get(ClientConstants.OP_ADDR).set(address);
            read.get(ClientConstants.NAME).set("persistent");
            ModelNode readResult = modelControllerClient.execute(read);
            boolean persistent = !ClientConstants.SUCCESS.equals(readResult.get(ClientConstants.OUTCOME).asString())
                    || !readResult.get(ClientConstants.RESULT).isDefined() || readResult.get(ClientConstants.RESULT).asBoolean();

            ModelNode op = new ModelNode();
            op.get(ClientConstants.OP).set(ClientConstants.DEPLOYMENT_FULL_REPLACE_OPERATION);
            op.get(ClientConstants.OP_ADDR).setEmptyList();
            op.get(ClientConstants.NAME).set(managementName);
            op.get(ClientConstants.RUNTIME_NAME).set(runtimeName);
            op.get("persistent").set(persistent);
            op.get(ClientConstants.CONTENT).add().get(ClientConstants.INPUT_STREAM_INDEX).set(0);
            OperationBuilder builder = OperationBuilder.create(op);
            builder.addInputStream(input);
            ModelNode result = modelControllerClient.execute(builder.build());
            if (!ClientConstants.SUCCESS.equals(result.get(ClientConstants.OUTCOME).asString())) {
                ModelNode failure = result.get(ClientConstants.FAILURE_DESCRIPTION);
                throw new IllegalStateException(ClientConstants.DEPLOYMENT_FULL_REPLACE_OPERATION + " of " + managementName + " with " + runtimeName + ": "
                        + (failure.isDefined() ? failure.asString() : "rolled back"));
            }
        }

        @Override
        @SuppressWarnings({ "unchecked", "rawtypes" })
        public void resolve(XBundle bundle) throws ResolutionException {

            // Resolve the bundle
            bundleManager.resolveBundle(bundle);

            // In case there is no DeploymentUnit there is a possiblity
            // of a race between the first class load and the availability of the Module
            // Here we wait for the ModuleSpec service to come up before we are done resolving
            // https://issues.jboss.org/browse/AS7-6016
            Deployment deployment = bundle.adapt(Deployment.class);
            DeploymentUnit depUnit = deployment.getAttachment(BundleDeploymentProcessor.DEPLOYMENT_UNIT_KEY);
            if (depUnit == null) {
                // The module is found by the module loader once its spec service is UP, the module service itself is
                // started on demand: nobody may ask for it while this thread waits
                ModuleIdentifier identifier = bundle.getBundleRevision().getModuleIdentifier();
                ServiceName moduleServiceName = ServiceModuleLoader.moduleSpecServiceName(identifier.toString());
                ServiceRegistry serviceRegistry = bundleManager.getServiceContainer();
                ServiceController<?> controller = serviceRegistry.getRequiredService(moduleServiceName);
                FutureServiceValue<?> future = new FutureServiceValue(controller);
                try {
                    future.get(2, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    throw FrameworkMessages.MESSAGES.illegalStateCannotLoadModule(ex, identifier);
                }
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public void start(XBundle bundle, int options) throws BundleException {
            Deployment deployment = bundle.adapt(Deployment.class);
            DeploymentUnit depUnit = deployment.getAttachment(BundleDeploymentProcessor.DEPLOYMENT_UNIT_KEY);

            // The DeploymentUnit would be null for initial capabilities
            // or for bundles that have been installed in nested mgmnt ops
            // https://issues.jboss.org/browse/AS7-5642
            if (depUnit == null) {
                bundleManager.startBundle(bundle, options);
                return;
            }

            // There is no deferred phase, activate using the default
            List<String> deferredModules = DeploymentUtils.getDeferredModules(depUnit);
            if (!deferredModules.contains(depUnit.getName())) {
                startDeployedBundle(bundle, options, depUnit);
                return;
            }

            // Get the INSTALL phase service and check whether we need to activate it
            ServiceController<Phase> phaseService = getDeferredPhaseService(depUnit);
            if (phaseService.getMode() != Mode.NEVER) {
                startDeployedBundle(bundle, options, depUnit);
                return;
            }

            final ServiceName deploymentServiceName;
            if (depUnit.getParent() == null) {
                deploymentServiceName = depUnit.getServiceName();
            } else {
                deploymentServiceName = depUnit.getParent().getServiceName();
            }

            ServiceContainer serviceContainer = bundleManager.getServiceContainer();
            ServiceController<DeploymentUnit> deploymentService = (ServiceController<DeploymentUnit>) serviceContainer.getRequiredService(deploymentServiceName);
            activateDeferredPhase(bundle, options, depUnit, phaseService, deploymentService);
        }

        /**
         * The deployment of the bundle starts it. A transient start, e.g. by the start level service while the
         * deployment is deployed at boot, does not start it before the deployment tried to, nor again when the
         * deployment failed to start it. A lazy activation (the bundle is STARTING) is always done.
         */
        private void startDeployedBundle(XBundle bundle, int options, DeploymentUnit depUnit) throws BundleException {
            StartCoordinator coordinator = depUnit.getAttachment(BundleActivateProcessor.START_COORDINATOR_KEY);
            if (coordinator != null && (options & Bundle.START_TRANSIENT) != 0 && bundle.getState() != Bundle.STARTING
                    && !coordinator.isTransientStartAllowed()) {
                LOGGER.debugf("Bundle is started by its deployment: %s", bundle);
                return;
            }
            bundleManager.startBundle(bundle, options);
            if (coordinator != null)
                coordinator.bundleStarted();
        }

        @Override
        public void stop(XBundle bundle, int options) throws BundleException {
            bundleManager.stopBundle(bundle, options);
        }

        @Override
        public void uninstall(XBundle bundle, int options) throws BundleException {
            XBundleRevision brev = bundle.getBundleRevision();
            Deployment deployment = brev.getAttachment(IntegrationConstants.DEPLOYMENT_KEY);
            if (deployment == null || !isUndeployRequired(bundle, deployment)) {
                bundleManager.uninstallBundle(bundle, options);
                return;
            }

            // The deployment is undeployed first, without the locks of the framework: the undeploy may be refused (RBAC)
            // or rolled back (a deployment depends on the bundle), the bundle is then not uninstalled. The deployments of
            // older revisions are undeployed when these are removed
            String managementName = getManagementName(deployment);
            bundle.putAttachment(BUNDLE_UNDEPLOYING_KEY, Boolean.TRUE);
            try {
                undeployChecked(managementName);
                brev.putAttachment(REVISION_UNDEPLOYED_KEY, Boolean.TRUE);
            } catch (Exception ex) {
                throw MESSAGES.cannotUninstallDeployedBundle(ex, bundle, managementName);
            } finally {
                bundle.removeAttachment(BUNDLE_UNDEPLOYING_KEY);
            }
            if (bundle.getState() != Bundle.UNINSTALLED) {
                bundleManager.uninstallBundle(bundle, options);
            }
        }

        private boolean isUndeployRequired(XBundle bundle, Deployment deployment) {
            if (bundle.getState() == Bundle.UNINSTALLED || isBundleRefreshing(bundle) || getManagementName(deployment) == null)
                return false;
            // Within a management operation or under the locks of the framework the undeploy cannot complete
            if (OperationAssociation.INSTANCE.getAssociation() != null || injectedLockManager.getValue().getCurrentLockContext() != null)
                return false;
            if (Boolean.TRUE.equals(deploymentUndeploy.get()))
                return false;
            return isDeployed(deployment);
        }

        private void undeployChecked(String runtimeName) throws Exception {
            DeploymentPlanBuilder builder = serverDeploymentManager.newDeploymentPlan();
            UndeployDeploymentPlanBuilder undeployBuilder = builder.undeploy(runtimeName);
            List<DeploymentAction> actions = new ArrayList<DeploymentAction>();
            actions.add(undeployBuilder.getLastAction());
            builder = undeployBuilder.andRemoveUndeployed();
            actions.add(builder.getLastAction());
            ServerDeploymentPlanResult planResult = serverDeploymentManager.execute(builder.build()).get();
            for (DeploymentAction action : actions) {
                ServerDeploymentActionResult actionResult = planResult.getDeploymentActionResult(action.getId());
                if (actionResult == null)
                    throw new IllegalStateException("No result for: " + action.getType() + " " + runtimeName);
                if (actionResult.getDeploymentException() != null)
                    throw new IllegalStateException(actionResult.getDeploymentException());
                // A rolled back undeploy carries no exception
                if (actionResult.getResult() != ServerUpdateActionResult.Result.EXECUTED)
                    throw new IllegalStateException(action.getType() + " of " + runtimeName + ": " + actionResult.getResult());
            }
        }

        @Override
        public void removeRevision(XBundleRevision brev, int options) {

            undeployRevision(brev);

            // [AS7-6855] Bundle revision not removed when not in use any more
            if (brev.getState() != XResource.State.UNINSTALLED) {
                bundleManager.removeRevision(brev, options);
            }
            releaseContent(brev);
        }

        private void undeployRevision(final XBundleRevision brev) {
            // Bundle.uninstall undeployed the deployment already
            if (Boolean.TRUE.equals(brev.getAttachment(REVISION_UNDEPLOYED_KEY)))
                return;

            final Deployment deployment = brev.getAttachment(IntegrationConstants.DEPLOYMENT_KEY);
            final boolean deferred = Boolean.TRUE.equals(deploymentUndeploy.get());
            Runnable undeploy = new Runnable() {
                @Override
                public void run() {
                    // The deployment may be gone already, or it is part of another deployment
                    String managementName = getManagementName(deployment);
                    if (managementName == null || isDeployed(deployment) == false)
                        return;

                    ServerDeploymentHelper server = new ServerDeploymentHelper(serverDeploymentManager);
                    try {
                        server.undeploy(managementName);
                    } catch (Exception ex) {
                        LOGGER.warnCannotUndeployBundleRevision(ex, brev);
                    }
                }
            };

            // A bundle that was uninstalled while it was in use is removed when another bundle gets undeployed.
            // Its deployment can only be undeployed after that operation
            if (deferred)
                undeployExecutor.execute(undeploy);
            else
                undeploy.run();
        }

        private boolean isDeployed(Deployment deployment) {
            DeploymentUnit depUnit = deployment.getAttachment(BundleDeploymentProcessor.DEPLOYMENT_UNIT_KEY);
            if (depUnit == null || depUnit.getParent() != null)
                return false;

            // A deployment of the same name that was deployed again after this one has the same service name
            ServiceController<?> controller = bundleManager.getServiceContainer().getService(depUnit.getServiceName());
            return controller != null && controller.getMode() != Mode.REMOVE && isServiceOf(controller, depUnit);
        }

        @Override
        public BundleRefreshPolicy getBundleRefreshPolicy() {
            return new RecreateCurrentRevisionPolicy();
        }

        private void retainDeploymentServices(Set<ServiceController<?>> controllers, ServiceController<?> phaseService, ServiceController<?> parentDeploymentService) {
            for (Iterator<ServiceController<?>> it = controllers.iterator(); it.hasNext();) {
                boolean related = false;
                for (ServiceController<?> aux = it.next(); aux != null && !related; aux = aux.getParent()) {
                    related = aux == phaseService || aux == parentDeploymentService;
                }
                if (!related)
                    it.remove();
            }
        }

        private void activateDeferredPhase(XBundle bundle, int options, DeploymentUnit depUnit, ServiceController<Phase> phaseService,
                ServiceController<DeploymentUnit> parentDeploymentService) throws BundleException {

            // If the Framework's current start level is less than this bundle's start level
            FrameworkStartLevel frameworkStartLevel = bundleManager.getSystemBundle().adapt(FrameworkStartLevel.class);
            BundleStartLevel bundleStartLevel = bundle.adapt(BundleStartLevel.class);
            int startlevel = bundleStartLevel.getStartLevel();
            if (startlevel > frameworkStartLevel.getStartLevel()) {
                LOGGER.debugf("Start level [%d] not valid for: %s", startlevel, bundle);
                return;
            }

            LOGGER.infoActivateDeferredModulePhase(bundle);

            if (!bundle.isResolved()) {
                XResolveContext context = resolver.createResolveContext(environment, Collections.singleton(bundle.getBundleRevision()), null);
                try {
                    resolver.resolveAndApply(context);
                } catch (ResolutionException ex) {
                    throw new BundleException(FrameworkMessages.MESSAGES.cannotResolveBundle(bundle), BundleException.RESOLVE_ERROR, ex);
                }
            }

            Set<ServiceController<?>> failed = new HashSet<>();
            Set<ServiceController<?>> problems = new HashSet<>();

            try {
                phaseService.setMode(Mode.ACTIVE);
                phaseService.getServiceContainer().awaitStability(failed, problems);
            } catch (final InterruptedException ex) {
                // ignore
            }

            // The count of activations is that of the completed ones: the processors of the first activation install the
            // bindings of the module (java:module/env), only a later activation finds them installed
            if (failed.isEmpty() && problems.isEmpty())
                depUnit.getAttachment(Attachments.DEFERRED_ACTIVATION_COUNT).incrementAndGet();

            // The container reports every service that failed or has a problem, only those of this deployment are relevant
            retainDeploymentServices(failed, phaseService, parentDeploymentService);
            retainDeploymentServices(problems, phaseService, parentDeploymentService);

            // In case of failure we go back to NEVER

            if (failed.size() > 0 || problems.size() > 0) {
                List<ServiceController<?>> combined = new ArrayList<ServiceController<?>>();
                combined.addAll(failed);
                combined.addAll(problems);

                // Collect the first start exception
                StartException startex = null;
                for (ServiceController<?> aux : combined) {
                    if (aux.getStartException() != null) {
                        startex = aux.getStartException();
                        break;
                    }
                }

                // Create the BundleException that we throw later
                BundleException failure;
                if (startex != null && startex.getCause() instanceof BundleException) {
                    failure = (BundleException) startex.getCause();
                } else {
                    failure = MESSAGES.cannotActivateDeferredModulePhase(startex, bundle);
                }

                // Deactivate the deferred phase
                depUnit.putAttachment(OSGiConstants.DEFERRED_ACTIVATION_FAILED, Boolean.TRUE);
                LOGGER.warnDeactivateDeferredModulePhase(bundle);
                phaseService.setMode(Mode.NEVER);

                // Wait for the phase service to come down
                try {
                    FutureServiceValue<Phase> future = new FutureServiceValue<Phase>(phaseService, State.DOWN);
                    future.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException ex) {
                    LOGGER.errorf(failure, failure.getMessage());
                    throw MESSAGES.cannotDeactivateDeferredModulePhase(ex, bundle);
                } catch (TimeoutException ex) {
                    LOGGER.errorf(failure, failure.getMessage());
                    throw MESSAGES.cannotDeactivateDeferredModulePhase(ex, bundle);
                }

                // Throw the BundleException that caused the start failure
                throw failure;
            }
        }

        // Maps the bundle.location to a deployment runtime name
        private String getRuntimeName(Deployment deployment) {
            String runtimeName = deployment.getAttachment(RUNTIME_NAME_KEY);
            if (runtimeName == null) {
                runtimeName = deployment.getLocation();
                try {
                    // Strip the query off the location if it is a valid URI
                    new URI(runtimeName);
                    int queryIndex = runtimeName.indexOf('?');
                    if (queryIndex > 0) {
                        runtimeName = runtimeName.substring(0, queryIndex);
                    }
                } catch (URISyntaxException ex) {
                    // ignore
                }
                deployment.putAttachment(RUNTIME_NAME_KEY, runtimeName);
            }
            return runtimeName;
        }

        @SuppressWarnings("unchecked")
        private ServiceController<Phase> getDeferredPhaseService(DeploymentUnit depUnit) {
            ServiceName serviceName = DeploymentUtils.getDeploymentUnitPhaseServiceName(depUnit, Phase.FIRST_MODULE_USE);
            ServiceContainer serviceContainer = bundleManager.getServiceContainer();
            return (ServiceController<Phase>) serviceContainer.getRequiredService(serviceName);
        }

        /**
         * The current revision of a bundle with a deployment of its own is recreated by replacing the content of the
         * deployment with the same content, under the runtime name of the bundle: one management operation undeploys
         * the deployment of the current revision and deploys the new one. A bundle without a deployment gets the new
         * revision without a deployment.
         */
        private final class RecreateCurrentRevisionPolicy implements BundleRefreshPolicy {
            private XBundle bundle;
            private VirtualFile rootFile;
            private String managementName;

            @Override
            public void startBundleRefresh(XBundle bundle) throws BundleException {
                this.bundle = bundle;

                XBundleRevision brev = bundle.getBundleRevision();
                Deployment deployment = brev.getAttachment(IntegrationConstants.DEPLOYMENT_KEY);
                if (deployment == null)
                    deployment = bundle.adapt(Deployment.class);

                try {
                    InputStream inputStream = deployment.getRoot().getStreamURL().openStream();
                    rootFile = AbstractVFS.toVirtualFile(inputStream);
                } catch (IOException ex) {
                    throw FrameworkMessages.MESSAGES.cannotObtainVirtualFile(ex);
                }

                bundle.putAttachment(BUNDLE_REFRESHING_KEY, Boolean.TRUE);
                managementName = isDeployed(deployment) ? getManagementName(deployment) : null;
                if (managementName == null) {
                    removeRevision(brev, 0);
                }
            }

            @Override
            public void refreshCurrentRevision(XBundleRevision brev) throws BundleException {

                // Create the revision {@link Deployment}
                DeploymentProvider deploymentManager = injectedDeploymentManager.getValue();
                Deployment deployment = deploymentManager.createDeployment(bundle.getLocation(), rootFile);
                deployment.putAttachment(IntegrationConstants.BUNDLE_KEY, bundle);
                deployment.setAutoStart(false);

                if (managementName == null) {
                    BundleContext context = bundleManager.getSystemBundle().getBundleContext();
                    bundleManager.installBundleRevision(context, deployment, null);
                    return;
                }

                // The deployment gets the runtime name of the bundle again
                String runtimeName = managementName;
                deployment.putAttachment(RUNTIME_NAME_KEY, runtimeName);

                // Deployments that depend on the module of the bundle lost it with the old revisions. The new revision
                // is resolved with its deployment, the operation leaves them with their module again
                String moduleName = ServiceModuleLoader.MODULE_PREFIX + runtimeName;
                if (ModuleLoaderIntegration.isModuleUsedByDeployments(bundleManager.getServiceContainer(), moduleName))
                    deployment.putAttachment(RESOLVE_ON_DEPLOY_KEY, Boolean.TRUE);
                putDeployment(runtimeName, deployment);

                try {
                    InputStream input = deployment.getRoot().openStream();
                    try {
                        replaceDeployment(managementName, runtimeName, input);
                    } finally {
                        VFSUtils.safeClose(input);
                    }
                } catch (RuntimeException rte) {
                    throw rte;
                } catch (Exception ex) {
                    throw MESSAGES.cannotDeployBundleRevision(ex, deployment);
                } finally {
                    removeDeployment(runtimeName, deployment);
                }
            }

            @Override
            public void endBundleRefresh(XBundle bundle) {
                bundle.removeAttachment(BUNDLE_REFRESHING_KEY);
            }
        }
    }
}
