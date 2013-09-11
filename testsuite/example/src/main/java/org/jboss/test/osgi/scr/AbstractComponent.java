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
package org.jboss.test.osgi.scr;

import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.logging.Logger;
import org.osgi.service.component.ComponentContext;

public class AbstractComponent implements Validatable {

    public final static Logger LOGGER = Logger.getLogger(AbstractComponent.class);

    private final AtomicBoolean active = new AtomicBoolean();
    private ComponentContext context;

    public synchronized void activateComponent(ComponentContext context) {
        LOGGER.infof("activate: %s", this);
        this.context = context;
        active.set(true);
    }

    public synchronized void deactivateComponent() {
        active.set(false);
        context = null;
        LOGGER.infof("deactivate: %s", this);
    }

    public synchronized ComponentContext getComponentContext() {
        assertValid();
        return context;
    }

    @Override
    public synchronized boolean isValid() {
        return active.get();
    }

    @Override
    public synchronized void assertValid() {
        if (isValid() == false)
            throw new InvalidComponentException();
    }
}