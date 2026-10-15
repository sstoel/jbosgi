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
package org.jboss.as.osgi.naming;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.naming.Context;
import javax.naming.Name;
import javax.naming.spi.ObjectFactory;

import org.jboss.as.naming.InitialContext;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleReference;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;

/**
 * Registers OSGi Services under the osgi.jndi.url.scheme
 *
 * @author David Bosschaert
 * @author Thomas.Diesler@jboss.com
 * @since 31-Jul-2012
 */
final class JNDIServiceListener implements ServiceListener {

    private static final String OSGI_JNDI_URL_SCHEME = "osgi.jndi.url.scheme";
    private static final String OSGI_JNDI_BUNDLE_CONTEXT = "osgi.service.jndi.bundleContext";

    private final BundleContext bundleContext;
    // The factory that WildFly's InitialContext has for a scheme, it delegates to the URL context factory services
    private final Map<String, ObjectFactory> delegates = new HashMap<String, ObjectFactory>();

    public JNDIServiceListener(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
        try {
            // Register the pre-existing services
            ServiceReference<?>[] refs = bundleContext.getServiceReferences(ObjectFactory.class.getName(), null);
            if (refs != null) {
                for (ServiceReference<?> ref : refs) {
                    updateSchemes(getStringPlusProperty(ref.getProperty(OSGI_JNDI_URL_SCHEME)));
                }
            }
        } catch (InvalidSyntaxException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public void serviceChanged(ServiceEvent event) {
        // The services of a scheme are looked up when a name is resolved, the event only tells which schemes have services
        ServiceReference<?> ref = event.getServiceReference();
        Set<String> schemes = new HashSet<String>(getStringPlusProperty(ref.getProperty(OSGI_JNDI_URL_SCHEME)));
        if (event.getType() == ServiceEvent.MODIFIED_ENDMATCH || event.getType() == ServiceEvent.MODIFIED) {
            synchronized (delegates) {
                schemes.addAll(delegates.keySet());
            }
        }
        updateSchemes(schemes);
    }

    void removeAll() {
        synchronized (delegates) {
            for (Map.Entry<String, ObjectFactory> entry : delegates.entrySet()) {
                InitialContext.removeUrlContextFactory(entry.getKey(), entry.getValue());
            }
            delegates.clear();
        }
    }

    // A scheme with a URL context factory service has a delegate in WildFly's InitialContext, the others have none
    private void updateSchemes(Collection<String> schemes) {
        synchronized (delegates) {
            for (String scheme : schemes) {
                boolean available = getFactoryReferences(bundleContext, scheme).length > 0;
                ObjectFactory delegate = delegates.get(scheme);
                if (available && delegate == null) {
                    delegate = new DelegatingUrlContextFactory(scheme);
                    delegates.put(scheme, delegate);
                    InitialContext.addUrlContextFactory(scheme, delegate);
                } else if (!available && delegate != null) {
                    delegates.remove(scheme);
                    InitialContext.removeUrlContextFactory(scheme, delegate);
                }
            }
        }
    }

    private static ServiceReference<?>[] getFactoryReferences(BundleContext context, String scheme) {
        try {
            String filter = "(&(" + Constants.OBJECTCLASS + "=" + ObjectFactory.class.getName() + ")(" + OSGI_JNDI_URL_SCHEME + "=" + scheme + "))";
            ServiceReference<?>[] refs = context.getServiceReferences((String) null, filter);
            return refs != null ? refs : new ServiceReference<?>[0];
        } catch (InvalidSyntaxException ex) {
            return new ServiceReference<?>[0];
        }
    }

    /**
     * The URL context factory of a scheme for WildFly's InitialContext. It uses the best ranked factory service of the
     * scheme that the calling bundle can see, obtained through the BundleContext of that bundle (126.7.3).
     */
    private final class DelegatingUrlContextFactory implements ObjectFactory {

        private final String scheme;

        DelegatingUrlContextFactory(String scheme) {
            this.scheme = scheme;
        }

        @Override
        public Object getObjectInstance(Object obj, Name name, Context nameCtx, Hashtable<?, ?> environment) throws Exception {
            BundleContext callerContext = getCallerContext(environment);
            ServiceReference<?>[] refs = getFactoryReferences(callerContext, scheme);
            if (refs.length == 0)
                return null;
            Arrays.sort(refs, Collections.reverseOrder());
            for (ServiceReference<?> ref : refs) {
                Object factory = callerContext.getService(ref);
                if (factory instanceof ObjectFactory)
                    return ((ObjectFactory) factory).getObjectInstance(obj, name, nameCtx, environment);
            }
            return null;
        }

        private BundleContext getCallerContext(Hashtable<?, ?> environment) {
            // The context given with the environment, else the bundle of the context class loader, else the first bundle
            // on the call stack, else the system bundle
            Object envContext = environment != null ? environment.get(OSGI_JNDI_BUNDLE_CONTEXT) : null;
            if (envContext instanceof BundleContext)
                return (BundleContext) envContext;
            BundleContext context = getBundleContext(Thread.currentThread().getContextClassLoader());
            if (context != null)
                return context;
            context = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> frames
                    .map(frame -> getBundleContext(frame.getDeclaringClass().getClassLoader()))
                    .filter(Objects::nonNull).findFirst().orElse(null));
            return context != null ? context : bundleContext;
        }

        private BundleContext getBundleContext(ClassLoader classLoader) {
            if (classLoader instanceof BundleReference) {
                Bundle bundle = ((BundleReference) classLoader).getBundle();
                BundleContext context = bundle != null && bundle.getBundleId() != 0 ? bundle.getBundleContext() : null;
                return context;
            }
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Collection<String> getStringPlusProperty(Object property) {
        if (property instanceof Collection) {
            return (Collection<String>) property;
        } else if (property instanceof String[]) {
            return Arrays.asList((String[]) property);
        } else if (property instanceof String) {
            return Collections.singleton((String) property);
        }
        return Collections.emptyList();
    }
}
