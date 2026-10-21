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
package org.jboss.as.osgi.parser;

import static org.jboss.as.osgi.OSGiMessages.MESSAGES;

import java.io.File;
import java.util.List;

import org.jboss.as.controller.AbstractAddStepHandler;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.osgi.parser.SubsystemState.OSGiCapability;
import org.jboss.as.osgi.service.LayeredBundlePathFactory;
import org.jboss.as.osgi.service.RetiredCapabilities;
import org.jboss.as.server.ServerEnvironment;
import org.jboss.dmr.ModelNode;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.modules.ModuleLoadException;
import org.jboss.msc.service.ServiceController;
import org.jboss.osgi.resolver.MavenCoordinates;
import org.wildfly.security.manager.WildFlySecurityManager;

/**
 * @author David Bosschaert
 * @author Thomas.Diesler@jboss.com
 */
public class OSGiCapabilityAdd extends AbstractAddStepHandler {
    static final OSGiCapabilityAdd INSTANCE = new OSGiCapabilityAdd();

    private OSGiCapabilityAdd() {
    }

    @Override
    protected void populateModel(ModelNode operation, ModelNode model) throws OperationFailedException {
        FrameworkCapabilityResource.STARTLEVEL.validateAndSet(operation, model);
    }

    @Override
    protected void performRuntime(OperationContext context, ModelNode operation, ModelNode model) throws OperationFailedException {
        final Integer startLevel = (operation.hasDefined(ModelConstants.STARTLEVEL) ? FrameworkCapabilityResource.STARTLEVEL.resolveModelAttribute(context, model).asInt() : null);

        String identifier = operation.get(ModelDescriptionConstants.OP_ADDR).asObject().get(ModelConstants.CAPABILITY).asString();
        validateIdentifier(identifier, context.isBooting());
        OSGiCapability module = new OSGiCapability(identifier, startLevel);

        SubsystemState subsystemState = SubsystemState.getSubsystemState(context);
        if (subsystemState != null) {
            subsystemState.addCapability(module);
        }
        // The capabilities are installed when the framework is created
        context.reloadRequired();
    }

    /**
     * A capability is a bundle of the bundles hierarchy or a module, as the framework installs it when it is created.
     * Maven coordinates are resolved by the repository then.
     */
    private static void validateIdentifier(String identifier, boolean booting) throws OperationFailedException {
        // A capability that a release removed is skipped with a warning when the framework is created, a configuration
        // that names it still boots. It cannot be added any more
        String replacement = RetiredCapabilities.getReplacement(identifier);
        if (replacement != null) {
            if (booting)
                return;
            throw MESSAGES.retiredCapability(identifier, replacement);
        }
        try {
            MavenCoordinates.parse(identifier);
            return;
        } catch (IllegalArgumentException ex) {
            // not Maven coordinates
        }
        ModuleIdentifier moduleId;
        try {
            moduleId = ModuleIdentifier.fromString(identifier);
        } catch (IllegalArgumentException ex) {
            throw MESSAGES.cannotFindCapability(ex, identifier);
        }
        // The bundles hierarchy and the modules are those of a server
        String homeDir = WildFlySecurityManager.getPropertyPrivileged(ServerEnvironment.HOME_DIR, null);
        if (homeDir == null)
            return;
        String identifierPath = moduleId.getName().replace('.', File.separatorChar) + File.separator + moduleId.getSlot();
        for (File basePath : LayeredBundlePathFactory.resolveLayeredBundlePath(new File(homeDir, "bundles"))) {
            if (new File(basePath, identifierPath).isDirectory())
                return;
        }
        try {
            Module.getBootModuleLoader().loadModule(moduleId);
        } catch (ModuleLoadException ex) {
            throw MESSAGES.cannotFindCapability(ex, identifier);
        }
    }

    @Override
    protected void rollbackRuntime(OperationContext context, ModelNode operation, Resource resource) {
        String identifier = operation.get(ModelDescriptionConstants.OP_ADDR).asObject().get(ModelConstants.CAPABILITY).asString();
        SubsystemState subsystemState = SubsystemState.getSubsystemState(context);
        if (subsystemState != null) {
            subsystemState.removeCapability(identifier);
        }
        context.revertReloadRequired();
    }
}
