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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import jakarta.servlet.ServletContext;

import org.jboss.as.osgi.OSGiLogger;
import org.jboss.as.osgi.OSGiMessages;
import org.jboss.as.web.host.ContextActivator;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;
import org.jboss.osgi.deployment.interceptor.AbstractLifecycleInterceptor;
import org.jboss.osgi.deployment.interceptor.InvocationContext;
import org.jboss.osgi.deployment.interceptor.LifecycleInterceptor;
import org.jboss.osgi.deployment.interceptor.LifecycleInterceptorException;
import org.jboss.osgi.framework.Services;
import org.jboss.osgi.resolver.XBundle;
import org.jboss.osgi.resolver.XBundleRevision;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.SynchronousBundleListener;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;

/**
 * {@link LifecycleInterceptor} for webapp bundles.
 *
 * @author Thomas.Diesler@jboss.com
 * @since 26-Jun-2012
 */
class WebContextLifecycleInterceptor extends AbstractLifecycleInterceptor implements Service<LifecycleInterceptor> {

    static final ServiceName SERVICE_NAME = ServiceName.JBOSS.append("as", "osgi", "web").append(WebContextLifecycleInterceptor.class.getSimpleName());

    private static final String EVENT_TOPIC_PREFIX = "org/osgi/service/web/";

    // The deployed web application bundles by context path, and the ones that wait for their context path (128.3.8)
    private static final Map<String, XBundle> deployed = new HashMap<String, XBundle>();
    private static final Map<String, Set<XBundle>> collided = new HashMap<String, Set<XBundle>>();
    // The context of the system bundle, the events are posted with it
    private static volatile BundleContext systemContext;

    private final InjectedValue<BundleContext> injectedSystemContext = new InjectedValue<BundleContext>();
    private ServiceRegistration<LifecycleInterceptor> registration;
    private BundleListener lazyListener;

    static ServiceController<LifecycleInterceptor> addService(ServiceTarget serviceTarget) {
        WebContextLifecycleInterceptor service = new WebContextLifecycleInterceptor();
        ServiceBuilder<LifecycleInterceptor> builder = serviceTarget.addService(SERVICE_NAME, service);
        builder.addDependency(Services.FRAMEWORK_CREATE, BundleContext.class, service.injectedSystemContext);
        builder.setInitialMode(Mode.ON_DEMAND);
        return builder.install();
    }

    @Override
    public void start(StartContext context) throws StartException {
        BundleContext syscontext = injectedSystemContext.getValue();
        systemContext = syscontext;
        registration = syscontext.registerService(LifecycleInterceptor.class, this, null);

        // A WAB with a lazy activation policy is ready when it is STARTING, it is deployed then (128.3.2)
        lazyListener = new SynchronousBundleListener() {
            @Override
            public void bundleChanged(BundleEvent event) {
                if (event.getType() == BundleEvent.LAZY_ACTIVATION && event.getBundle() instanceof XBundle) {
                    XBundle bundle = (XBundle) event.getBundle();
                    ContextActivator activator = bundle.getBundleRevision().getAttachment(WebExtension.CONTEXT_ACTIVATOR_KEY);
                    if (activator != null)
                        deployContext(bundle, activator);
                }
            }
        };
        syscontext.addBundleListener(lazyListener);
    }

    @Override
    public void stop(StopContext context) {
        if (registration != null)
            registration.unregister();
        if (lazyListener != null)
            injectedSystemContext.getValue().removeBundleListener(lazyListener);
    }

    @Override
    public void invoke(int state, InvocationContext context) {
        XBundle bundle = (XBundle) context.getBundle();
        XBundleRevision brev = bundle.getBundleRevision();
        ContextActivator activator = brev.getAttachment(WebExtension.CONTEXT_ACTIVATOR_KEY);
        if (activator != null) {
            switch (state) {
                case Bundle.ACTIVE:
                    deployContext(bundle, activator);
                    break;
                case Bundle.RESOLVED:
                    undeployContext(bundle, activator);
                    break;
            }
        }
    }

    /**
     * Deploy the web application of a WAB that is ready. A WAB whose context path is used by another one waits for it,
     * its start does not fail (128.3.8).
     */
    static void deployContext(XBundle bundle, ContextActivator activator) {
        String contextPath = getContextPath(bundle);
        synchronized (deployed) {
            if (deployed.get(contextPath) == bundle && activator.getServletContext() != null)
                return;
            postEvent(bundle, "DEPLOYING", contextPath, null);
            XBundle owner = deployed.get(contextPath);
            if (owner != null && owner != bundle) {
                Set<XBundle> waiting = collided.get(contextPath);
                if (waiting == null)
                    collided.put(contextPath, waiting = new TreeSet<XBundle>(BY_BUNDLE_ID));
                waiting.add(bundle);
                List<Long> ids = new ArrayList<Long>();
                ids.add(owner.getBundleId());
                for (XBundle aux : waiting)
                    ids.add(aux.getBundleId());
                Map<String, Object> props = new HashMap<String, Object>();
                props.put("collision", contextPath);
                props.put("collision.bundles", ids);
                postEvent(bundle, "FAILED", contextPath, props);
                return;
            }
            boolean started;
            try {
                started = activator.startContext();
            } catch (RuntimeException ex) {
                Map<String, Object> props = new HashMap<String, Object>();
                props.put("exception", ex);
                postEvent(bundle, "FAILED", contextPath, props);
                throw ex;
            }
            if (!started) {
                postEvent(bundle, "FAILED", contextPath, null);
                throw new LifecycleInterceptorException(OSGiMessages.MESSAGES.startContextFailed());
            }
            deployed.put(contextPath, bundle);
            injectBundleContext(activator.getServletContext(), bundle.getBundleContext());
            postEvent(bundle, "DEPLOYED", contextPath, null);
        }
    }

    /**
     * Undeploy the web application of a WAB, the WAB that waits longest for the context path gets it
     */
    static void undeployContext(XBundle bundle, ContextActivator activator) {
        String contextPath = getContextPath(bundle);
        XBundle next = null;
        synchronized (deployed) {
            Set<XBundle> waiting = collided.get(contextPath);
            if (deployed.get(contextPath) != bundle) {
                if (waiting != null)
                    waiting.remove(bundle);
                return;
            }
            postEvent(bundle, "UNDEPLOYING", contextPath, null);
            uninjectBundleContext(activator.getServletContext());
            activator.stopContext();
            deployed.remove(contextPath);
            postEvent(bundle, "UNDEPLOYED", contextPath, null);
            if (waiting != null) {
                for (XBundle aux : new ArrayList<XBundle>(waiting)) {
                    waiting.remove(aux);
                    if (aux.getState() == Bundle.ACTIVE || aux.getState() == Bundle.STARTING) {
                        next = aux;
                        break;
                    }
                }
                if (waiting.isEmpty())
                    collided.remove(contextPath);
            }
        }
        if (next != null) {
            ContextActivator nextActivator = next.getBundleRevision().getAttachment(WebExtension.CONTEXT_ACTIVATOR_KEY);
            if (nextActivator != null)
                deployContext(next, nextActivator);
        }
    }

    private static final Comparator<XBundle> BY_BUNDLE_ID = new Comparator<XBundle>() {
        @Override
        public int compare(XBundle b1, XBundle b2) {
            return Long.compare(b1.getBundleId(), b2.getBundleId());
        }
    };

    private static String getContextPath(XBundle bundle) {
        Object path = bundle.getHeaders().get(WebExtension.WEB_CONTEXTPATH);
        return path != null ? path.toString() : "/" + bundle.getSymbolicName();
    }

    /**
     * Post a Web Extender event (128.5) with the Event Admin, if there is one. The Event Admin API is not visible to
     * this module: the event is created with the class loader of the Event Admin service.
     */
    private static void postEvent(XBundle bundle, String type, String contextPath, Map<String, Object> extra) {
        try {
            BundleContext context = systemContext;
            if (context == null)
                return;
            ServiceReference<?> sref = context.getServiceReference("org.osgi.service.event.EventAdmin");
            if (sref == null)
                return;
            Object eventAdmin = context.getService(sref);
            if (eventAdmin == null)
                return;
            try {
                Map<String, Object> props = new HashMap<String, Object>();
                props.put("bundle.symbolicName", bundle.getSymbolicName());
                props.put("bundle.id", bundle.getBundleId());
                props.put("bundle", bundle);
                props.put("bundle.version", bundle.getVersion());
                props.put("context.path", contextPath);
                props.put("timestamp", System.currentTimeMillis());
                // The extender is part of the framework integration
                Bundle extender = context.getBundle();
                props.put("extender.bundle", extender);
                props.put("extender.bundle.id", extender.getBundleId());
                props.put("extender.bundle.symbolicName", extender.getSymbolicName());
                props.put("extender.bundle.version", extender.getVersion());
                if (extra != null)
                    props.putAll(extra);
                ClassLoader loader = eventAdmin.getClass().getClassLoader();
                Class<?> eventClass = Class.forName("org.osgi.service.event.Event", true, loader);
                Object event = eventClass.getConstructor(String.class, Map.class).newInstance(EVENT_TOPIC_PREFIX + type, props);
                Class<?> adminClass = Class.forName("org.osgi.service.event.EventAdmin", true, loader);
                adminClass.getMethod("postEvent", eventClass).invoke(eventAdmin, event);
            } finally {
                context.ungetService(sref);
            }
        } catch (Exception ex) {
            OSGiLogger.LOGGER.debugf(ex, "Cannot post web event %s for %s", type, bundle);
        }
    }

    static void injectBundleContext(ServletContext webContext, BundleContext bundleContext) {
        OSGiLogger.LOGGER.debugf("Injecting bundle context %s into %s", bundleContext, webContext);
        webContext.setAttribute(WebExtension.OSGI_BUNDLECONTEXT, bundleContext);
        registerServletContextService(webContext, bundleContext);
    }

    private static void uninjectBundleContext(ServletContext webContext) {
        if (webContext != null) {
            OSGiLogger.LOGGER.debugf("Uninjecting bundle context from %s", webContext);
            webContext.removeAttribute(WebExtension.OSGI_BUNDLECONTEXT);
        }
    }

    private static void registerServletContextService(ServletContext servletContext, BundleContext bundleContext) {
        Bundle bundle = bundleContext.getBundle();
        Dictionary<String, Object> props = new Hashtable<String, Object>();
        props.put("osgi.web.symbolicname", bundle.getSymbolicName());
        Dictionary<?, ?> headers = bundle.getHeaders();
        // The version of the Web Application Bundle. If no Bundle-Version is specified in the manifest then
        // this property must not be set.
        Object version = headers.get(Constants.BUNDLE_VERSION);
        if (version instanceof String)
            props.put("osgi.web.version", version);

        Object contextPath = headers.get(WebExtension.WEB_CONTEXTPATH);
        if (contextPath instanceof String)
            props.put("osgi.web.contextpath", contextPath);

        bundleContext.registerService(ServletContext.class.getName(), servletContext, props);
    }

    @Override
    public LifecycleInterceptor getValue() throws IllegalStateException, IllegalArgumentException {
        return this;
    }
}