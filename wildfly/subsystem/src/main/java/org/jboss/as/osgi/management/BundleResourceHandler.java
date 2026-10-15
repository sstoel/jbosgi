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
package org.jboss.as.osgi.management;

import static org.jboss.as.osgi.OSGiLogger.LOGGER;
import static org.jboss.as.osgi.OSGiMessages.MESSAGES;

import org.jboss.as.controller.AbstractRuntimeOnlyHandler;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.osgi.parser.ModelConstants;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.service.ServiceController;
import org.jboss.osgi.framework.Services;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.startlevel.BundleStartLevel;

/**
 * @author David Bosschaert
 * @author Thomas.Diesler@jboss.com
 */
public class BundleResourceHandler extends AbstractRuntimeOnlyHandler {

    public static final BundleResourceHandler INSTANCE = new BundleResourceHandler();
    private BundleResourceHandler() {
    }

    @Override
    protected void executeRuntimeStep(OperationContext context, ModelNode operation) throws OperationFailedException {
        final String operationName = operation.require(ModelDescriptionConstants.OP).asString();
        if (ModelDescriptionConstants.READ_ATTRIBUTE_OPERATION.equals(operationName)) {
            handleReadAttributeOperation(context, operation);
        } else {
            handleOperation(context, operationName, operation);
        }
    }

    private void handleReadAttributeOperation(OperationContext context, ModelNode operation) {
        String name = operation.require(ModelDescriptionConstants.NAME).asString();
        // A bundle that is uninstalled after its resource was listed has no attributes (OSGiRuntimeResource)
        Bundle bundle = findTargetBundle(context, operation);
        if (bundle != null && bundle.getState() != Bundle.UNINSTALLED) {
            try {
                readAttribute(context, bundle, name);
            } catch (IllegalStateException ex) {
                context.getResult().set(new ModelNode());
            }
        }
        context.completeStep(OperationContext.RollbackHandler.NOOP_ROLLBACK_HANDLER);
    }

    private void readAttribute(OperationContext context, Bundle bundle, String name) {
        if (ModelConstants.ID.equals(name)) {
            context.getResult().set(bundle.getBundleId());
        } else if (ModelConstants.STARTLEVEL.equals(name)) {
            BundleStartLevel bundleStartLevel = bundle.adapt(BundleStartLevel.class);
            int startlevel = bundleStartLevel.getStartLevel();
            context.getResult().set(startlevel);
        } else if (ModelConstants.STATE.equals(name)) {
            context.getResult().set(getBundleState(bundle));
        } else if (ModelConstants.SYMBOLIC_NAME.equals(name)) {
            context.getResult().set(bundle.getSymbolicName());
        } else if (ModelConstants.LOCATION.equals(name)) {
            context.getResult().set(bundle.getLocation());
        } else if (ModelConstants.TYPE.equals(name)) {
            if (bundle.getHeaders().get(Constants.FRAGMENT_HOST) != null) {
                context.getResult().set(ModelConstants.FRAGMENT);
            } else {
                context.getResult().set(ModelConstants.BUNDLE);
            }
        } else if (ModelConstants.VERSION.equals(name)) {
            context.getResult().set(bundle.getVersion().toString());
        }
    }

    private void handleOperation(OperationContext context, String operationName, ModelNode operation) {
        // The operation changes the runtime: this authorizes it for that (RBAC) and makes it audited
        context.getServiceRegistry(true);
        try {
            if (ModelConstants.START.equals(operationName)) {
                OperationAssociation.INSTANCE.setAssociation(operation);
                try {
                    Bundle bundle = getTargetBundle(context, operation);
                    bundle.start();
                } finally {
                    OperationAssociation.INSTANCE.removeAssociation();
                }
            } else if (ModelConstants.STOP.equals(operationName)) {
                Bundle bundle = getTargetBundle(context, operation);
                // The framework runs as long as the server, a stop of the system bundle would not stop it
                if (bundle.getBundleId() == 0) {
                    context.getFailureDescription().set(MESSAGES.cannotStopSystemBundle());
                } else {
                    bundle.stop();
                }
            } else  {
                throw new UnsupportedOperationException(operationName);
            }
        } catch (Exception ex) {
            LOGGER.errorInOperationHandler(ex, operationName);
            context.getFailureDescription().set(ex.getLocalizedMessage());
        }
        context.completeStep(OperationContext.RollbackHandler.NOOP_ROLLBACK_HANDLER);
    }

    private Bundle getTargetBundle(OperationContext context, ModelNode operation) {
        Bundle bundle = findTargetBundle(context, operation);
        if (bundle == null)
            throw MESSAGES.illegalArgumentCannotObtainBundleResource(getTargetName(operation));
        return bundle;
    }

    private static String getTargetName(ModelNode operation) {
        ModelNode addr = operation.require(ModelDescriptionConstants.OP_ADDR);
        return PathAddress.pathAddress(addr).getLastElement().getValue();
    }

    private Bundle findTargetBundle(OperationContext context, ModelNode operation) {
        String value = getTargetName(operation);
        // The resources are named by the bundle location, which may be a number: a bundle id addresses the bundle only
        // when no bundle has the name as its location
        BundleContext syscontext = getSystemContext(context);
        Bundle bundle = syscontext.getBundle(value);
        if (bundle == null) {
            try {
                bundle = syscontext.getBundle(Long.parseLong(value));
            } catch (NumberFormatException ex) {
                // not a bundle id
            }
        }
        return bundle;
    }

    static String getBundleState(Bundle bundle) {
        switch (bundle.getState()) {
        case Bundle.UNINSTALLED:
            return "UNINSTALLED";
        case Bundle.INSTALLED:
            return "INSTALLED";
        case Bundle.RESOLVED:
            return "RESOLVED";
        case Bundle.STARTING:
            return "STARTING";
        case Bundle.STOPPING:
            return "STOPPING";
        case Bundle.ACTIVE:
            return "ACTIVE";
        }
        return null;
    }

    private BundleContext getSystemContext(OperationContext context) {
        ServiceController<?> controller = context.getServiceRegistry(false).getService(Services.FRAMEWORK_CREATE);
        return controller != null ? (BundleContext)controller.getValue() : null;
    }
}
