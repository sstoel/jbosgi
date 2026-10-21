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

package org.jboss.as.osgi.web;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;



import org.jboss.as.osgi.OSGiConstants;
import org.jboss.as.osgi.OSGiMessages;
import org.jboss.as.server.Services;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.as.web.host.ContextActivator;
import org.jboss.msc.Service;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.osgi.resolver.XBundle;
import org.jboss.osgi.resolver.XBundleRevision;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;

/**
 * Start/stop an OSGi webapp context according to bundle lifecycle changes.
 *
 * @author Thomas.Diesler@jboss.com
 * @author David Bosschaert
 * @since 26-Jun-2012
 */
public class WebContextActivationProcessor implements DeploymentUnitProcessor {

    @Override
    public void deploy(final DeploymentPhaseContext phaseContext) throws DeploymentUnitProcessingException {
        DeploymentUnit depUnit = phaseContext.getDeploymentUnit();
        ContextActivator activator = depUnit.getAttachment(ContextActivator.ATTACHMENT_KEY);
        XBundleRevision brev = depUnit.getAttachment(OSGiConstants.BUNDLE_REVISION_KEY);
        if (activator != null && brev != null) {
            // Add the {@link ContextActivator} to the {@link XBundleRevision}
            brev.putAttachment(WebExtension.CONTEXT_ACTIVATOR_KEY, activator);

            // The framework starts the persistent bundles at boot, maybe before the activator is attached, or while it
            // is attached: the context of a bundle that is ready is started once the web deployment is up. That is
            // checked when the web deployment is up, not now, the bundle may become ready in between (lifecycle/03)
            XBundle bundle = brev.getBundle();
            ServiceName serviceName = depUnit.getServiceName().append("osgi-wab-activation");
            ServiceBuilder<?> builder = phaseContext.getServiceTarget().addService(serviceName);
            builder.requires(depUnit.getServiceName().append("undertow-deployment"));
            Supplier<ExecutorService> executor = builder.requires(Services.JBOSS_SERVER_EXECUTOR);
            builder.setInstance(new ReadyBundleContextService(bundle, brev, activator, executor));
            builder.install();
        }
    }

    /**
     * A WAB is ready when it is ACTIVE, or STARTING with a lazy activation policy: then it waits for its activation
     * (128.3.2, ee/22)
     */
    static boolean isReady(XBundle bundle) {
        int state = bundle.getState();
        if (state == Bundle.ACTIVE)
            return true;
        Object policy = bundle.getHeaders("").get(Constants.BUNDLE_ACTIVATIONPOLICY);
        return state == Bundle.STARTING && policy != null && policy.toString().trim().startsWith(Constants.ACTIVATION_LAZY);
    }

    private static final class ReadyBundleContextService implements Service {
        // How long a bundle whose activator runs is waited for
        private static final long STARTING_TIMEOUT = TimeUnit.SECONDS.toMillis(30);

        private final XBundle bundle;
        private final XBundleRevision brev;
        private final ContextActivator activator;
        private final Supplier<ExecutorService> executor;

        ReadyBundleContextService(XBundle bundle, XBundleRevision brev, ContextActivator activator, Supplier<ExecutorService> executor) {
            this.bundle = bundle;
            this.brev = brev;
            this.activator = activator;
            this.executor = executor;
        }

        @Override
        public void start(final StartContext context) throws StartException {
            // The start of the web application runs the servlet initialization: not on an MSC thread, like the start of
            // a web deployment in Undertow
            context.asynchronous();
            executor.get().submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        startWhenReady();
                        context.complete();
                    } catch (Throwable th) {
                        context.failed(new StartException(OSGiMessages.MESSAGES.startContextFailed(), th));
                    }
                }
            });
        }

        private void startWhenReady() throws InterruptedException {
            // A bundle whose activator runs becomes ready shortly; the interceptor does not see a bundle whose activator
            // was attached while it became ACTIVE
            long deadline = System.currentTimeMillis() + STARTING_TIMEOUT;
            while (bundle.getState() == Bundle.STARTING && isReady(bundle) == false && System.currentTimeMillis() < deadline)
                Thread.sleep(50);
            // Not when the bundle is not ready, or the interceptor started the context already
            if (isReady(bundle) == false || bundle.getBundleRevision() != brev || activator.getServletContext() != null)
                return;
            WebContextLifecycleInterceptor.deployContext(bundle, activator);
        }

        @Override
        public void stop(StopContext context) {
        }
    }

    @Override
    public void undeploy(final DeploymentUnit depUnit) {
        ContextActivator activator = depUnit.getAttachment(ContextActivator.ATTACHMENT_KEY);
        XBundleRevision brev = depUnit.getAttachment(OSGiConstants.BUNDLE_REVISION_KEY);
        if (activator != null && brev != null) {
            brev.removeAttachment(WebExtension.CONTEXT_ACTIVATOR_KEY);
        }
    }
}
