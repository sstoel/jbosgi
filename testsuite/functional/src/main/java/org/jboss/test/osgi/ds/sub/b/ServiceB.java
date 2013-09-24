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
package org.jboss.test.osgi.ds.sub.b;

import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.test.osgi.ds.sub.b1.ServiceB1;
import org.jboss.test.osgi.ds.support.AbstractComponent;
import org.jboss.test.osgi.ds.support.ValidatingReference;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

@Component(service = { ServiceB.class }, immediate = true)
public class ServiceB extends AbstractComponent {

    static AtomicInteger INSTANCE_COUNT = new AtomicInteger();
    final String name = getClass().getSimpleName() + "#" + INSTANCE_COUNT.incrementAndGet();

    final ValidatingReference<ServiceB1> ref = new ValidatingReference<ServiceB1>();

    @Activate
    void activate(ComponentContext context) {
        activateComponent();
    }

    @Deactivate
    void deactivate() {
        deactivateComponent();
    }

    @Reference(cardinality=ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    void bindServiceB1(ServiceB1 service) {
        LOGGER.infof("bindService: %s:%s", this, service);
        ref.bind(service);
    }

    void unbindServiceB1(ServiceB1 service) {
        LOGGER.infof("unbindService: %s:%s", this, service);
        ref.unbind(service);
    }

    public ServiceB1 getServiceB1() {
        return ref.get();
    }

    public String doStuff(String msg) {
        assertValid();
        ServiceB1 srv = ref.getOptional();
        return name + ":" + (srv != null ? srv.doStuff(msg) : msg);
    }

    @Override
    public String toString() {
        return name;
    }
}