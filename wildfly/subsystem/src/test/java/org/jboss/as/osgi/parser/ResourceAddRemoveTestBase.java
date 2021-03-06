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
package org.jboss.as.osgi.parser;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationContext.ResultAction;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.ProcessType;
import org.jboss.as.controller.RunningMode;
import org.jboss.as.controller.registry.ImmutableManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.osgi.OSGiConstants;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceRegistry;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * @author David Bosschaert
 * @author Thomas.Diesler@jboss.com
 */
class ResourceAddRemoveTestBase {

    private final AtomicReference<ModelNode> operationHolder = new AtomicReference<ModelNode>();
    private boolean resourceActive = true;

    @SuppressWarnings({ "unchecked", "rawtypes" })
    protected OperationContext mockOperationContext(SubsystemState stateService, final List<OperationStepHandler> addedSteps,
                                                    final ResultAction stepResult) throws Exception {
        ServiceRegistry serviceRegistry = mock(ServiceRegistry.class);
        ServiceController serviceController = mock(ServiceController.class);
        ImmutableManagementResourceRegistration registration = mock(ImmutableManagementResourceRegistration.class);
        when(serviceController.getValue()).thenReturn(stateService);
        when(serviceRegistry.getService(OSGiConstants.SUBSYSTEM_STATE_SERVICE_NAME)).thenReturn(serviceController);
        ModelNode result = new ModelNode();
        final OperationContext context = mock(OperationContext.class);
        final Resource resource = mock(Resource.class);
        when(resource.getModel()).thenReturn(result);
        when(context.getServiceRegistry(true)).thenReturn(serviceRegistry);
        when(context.createResource(PathAddress.EMPTY_ADDRESS)).thenReturn(resource);
        when(context.readResource(PathAddress.EMPTY_ADDRESS)).thenReturn(resource);
        when(context.readResource(PathAddress.EMPTY_ADDRESS, false)).thenAnswer(new Answer<Resource>() {
            @Override
            public Resource answer(InvocationOnMock invocation) throws Throwable {
                if (resourceActive) {
                    return resource;
                } else {
                    throw new Resource.NoSuchResourceException(PathAddress.EMPTY_ADDRESS.getLastElement());
                }
            }
        });

        when(context.removeResource(PathAddress.EMPTY_ADDRESS)).thenAnswer(new Answer<Resource>() {
            @Override
            public Resource answer(InvocationOnMock invocation) throws Throwable {
                resourceActive = false;
                return resource;
            }
        });
        when(context.getProcessType()).thenReturn(ProcessType.STANDALONE_SERVER);
        when(context.getRunningMode()).thenReturn(RunningMode.NORMAL);
        when(context.isNormalServer()).thenReturn(true);
        when(context.getResourceRegistration()).thenReturn(registration);
        when(context.isDefaultRequiresRuntime()).thenReturn(true);
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                addedSteps.add((OperationStepHandler) invocation.getArguments()[0]);
                return null;
            }
        }).when(context).addStep((OperationStepHandler) anyObject(), eq(OperationContext.Stage.RUNTIME));
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                if (stepResult == ResultAction.ROLLBACK) {
                    Object[] args = invocation.getArguments();
                    OperationContext.RollbackHandler handler = OperationContext.RollbackHandler.class.cast(args[0]);
                    handler.handleRollback(context, operationHolder.get());
                }
                return null;
            }
        }).when(context).completeStep(any(OperationContext.RollbackHandler.class));
        doAnswer(new Answer<ModelNode>() {
            @Override
            public ModelNode answer(InvocationOnMock invocation) throws Throwable {
                return (ModelNode)invocation.getArguments()[0];
            }
        }).when(context).resolveExpressions(any(ModelNode.class));
        return context;
    }

    protected void execute(final OperationStepHandler handler, final OperationContext context,
                                final ModelNode op) throws OperationFailedException {
        operationHolder.set(op);
        handler.execute(context, op);
    }

    protected void configureForRollback(final OperationContext context, final ModelNode operation) {
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                OperationContext.RollbackHandler handler = OperationContext.RollbackHandler.class.cast(args[0]);
                handler.handleRollback(context, operation);
                return null;
            }
        }).when(context).completeStep(any(OperationContext.RollbackHandler.class));
    }

    protected void configureForSuccess(final OperationContext context) {
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                return null;
            }
        }).when(context).completeStep(any(OperationContext.RollbackHandler.class));
    }
}
