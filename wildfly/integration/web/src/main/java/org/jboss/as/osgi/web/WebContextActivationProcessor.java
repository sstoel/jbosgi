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



import org.jboss.as.osgi.OSGiConstants;
import org.jboss.as.osgi.OSGiMessages;
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

            // The framework starts the persistent bundles at boot, maybe before the activator is attached: the context
            // of a bundle that is ACTIVE already is started when the web deployment is up
            XBundle bundle = brev.getBundle();
            if (bundle.getState() == Bundle.ACTIVE) {
                ServiceName serviceName = depUnit.getServiceName().append("osgi-wab-activation");
                ServiceBuilder<?> builder = phaseContext.getServiceTarget().addService(serviceName);
                builder.requires(depUnit.getServiceName().append("undertow-deployment"));
                builder.setInstance(new ActiveBundleContextService(bundle, brev, activator));
                builder.install();
            }
        }
    }

    private static final class ActiveBundleContextService implements Service {
        private final XBundle bundle;
        private final XBundleRevision brev;
        private final ContextActivator activator;

        ActiveBundleContextService(XBundle bundle, XBundleRevision brev, ContextActivator activator) {
            this.bundle = bundle;
            this.brev = brev;
            this.activator = activator;
        }

        @Override
        public void start(StartContext context) throws StartException {
            // Not when the bundle was stopped meanwhile, or the interceptor started the context already
            if (bundle.getState() != Bundle.ACTIVE || bundle.getBundleRevision() != brev || activator.getServletContext() != null)
                return;
            try {
                WebContextLifecycleInterceptor.deployContext(bundle, activator);
            } catch (RuntimeException ex) {
                throw new StartException(OSGiMessages.MESSAGES.startContextFailed(), ex);
            }
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
