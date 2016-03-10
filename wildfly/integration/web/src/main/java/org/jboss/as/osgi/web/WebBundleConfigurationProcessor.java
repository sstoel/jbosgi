/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
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

import java.lang.reflect.Field;
import java.security.Permission;
import java.security.Permissions;
import java.util.Enumeration;
import java.util.List;

import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.as.server.deployment.module.ModuleSpecProcessor;
import org.jboss.as.server.deployment.module.ModuleSpecification;
import org.jboss.modules.security.FactoryPermissionCollection;
import org.jboss.modules.security.ImmediatePermissionFactory;
import org.jboss.modules.security.PermissionFactory;

/**
 * @author <a href="mailto:arcadiy@ivanov.biz">Arcadiy Ivanov</a>
 * @version $Revision: $
 */
public class WebBundleConfigurationProcessor implements DeploymentUnitProcessor {

    private static final Permissions DEFAULT_PERMISSIONS;
    static {
        try {
            Field f = ModuleSpecProcessor.class.getDeclaredField("DEFAULT_PERMISSIONS");
            f.setAccessible(true);
            DEFAULT_PERMISSIONS = (Permissions) f.get(null);
        } catch (NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException e) {
            throw new Error("JBOSGI HACK: Unable to access " + ModuleSpecProcessor.class.getName() + ".DEFAULT_PERMISSIONS", e);
        }
    }
    
    @Override
    public void deploy(DeploymentPhaseContext phaseContext) throws DeploymentUnitProcessingException {
        DeploymentUnit depUnit = phaseContext.getDeploymentUnit();

        final ModuleSpecification moduleSpecification = depUnit.getAttachment(Attachments.MODULE_SPECIFICATION);
        final List<PermissionFactory> permFactories = moduleSpecification.getPermissionFactories();

        
        final Enumeration<Permission> e = DEFAULT_PERMISSIONS.elements();
        while (e.hasMoreElements()) {
            permFactories.add(new ImmediatePermissionFactory(e.nextElement()));
        }

        FactoryPermissionCollection permissionCollection = new FactoryPermissionCollection(permFactories.toArray(new PermissionFactory[permFactories.size()]));
        depUnit.putAttachment(Attachments.MODULE_PERMISSIONS, permissionCollection);
    }

    @Override
    public void undeploy(DeploymentUnit context) {
    }
}
