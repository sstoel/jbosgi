/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2026, Red Hat, Inc., and individual contributors
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

import java.util.Locale;
import java.util.jar.Manifest;

import org.jboss.as.ee.structure.DeploymentType;
import org.jboss.as.ee.structure.DeploymentTypeMarker;
import org.jboss.as.osgi.OSGiLogger;
import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.as.server.deployment.module.ResourceRoot;

/**
 * Processor that deploys a bundle without a valid Web-ContextPath header as a plain bundle.
 *
 * The server marks a deployment as a web application by the name ".wab" or by the presence of the Web-ContextPath
 * header. A bundle is a Web Application Bundle only when its Web-ContextPath header is present and begins with '/'
 * (128.3.1). A web archive (".war") and a webbundle: URI are web applications by themselves.
 */
public class WebBundleRecognitionProcessor implements DeploymentUnitProcessor {

    @Override
    public void deploy(final DeploymentPhaseContext phaseContext) throws DeploymentUnitProcessingException {
        DeploymentUnit depUnit = phaseContext.getDeploymentUnit();
        Manifest manifest = depUnit.getAttachment(Attachments.OSGI_MANIFEST);
        if (manifest == null || !DeploymentTypeMarker.isType(DeploymentType.WAR, depUnit))
            return;

        String name = depUnit.getName().toLowerCase(Locale.ENGLISH);
        if (name.endsWith(".war") || isValidWebApplicationBundle(depUnit))
            return;

        OSGiLogger.LOGGER.warnf("Not a Web Application Bundle, no valid %s header: %s", WebExtension.WEB_CONTEXTPATH, depUnit.getName());
        depUnit.removeAttachment(org.jboss.as.ee.structure.Attachments.DEPLOYMENT_TYPE);
        ResourceRoot deploymentRoot = depUnit.getAttachment(Attachments.DEPLOYMENT_ROOT);
        if (deploymentRoot != null)
            deploymentRoot.removeAttachment(org.jboss.as.ee.structure.Attachments.DEPLOYMENT_TYPE);
    }

    @Override
    public void undeploy(final DeploymentUnit depUnit) {
    }

    /**
     * Whether the deployment is a webbundle: URI, or a bundle whose Web-ContextPath header begins with '/'
     */
    static boolean isValidWebApplicationBundle(DeploymentUnit depUnit) {
        if (depUnit.getName().toLowerCase(Locale.ENGLISH).startsWith(WebExtension.WEBBUNDLE_PREFIX))
            return true;
        Manifest manifest = depUnit.getAttachment(Attachments.OSGI_MANIFEST);
        String contextPath = manifest != null ? manifest.getMainAttributes().getValue(WebExtension.WEB_CONTEXTPATH) : null;
        return contextPath != null && contextPath.startsWith("/");
    }
}
