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

package org.jboss.as.osgi.httpservice;


import static org.jboss.as.osgi.OSGiLogger.LOGGER;
import static org.jboss.as.osgi.OSGiMessages.MESSAGES;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import jakarta.servlet.Servlet;

import org.jboss.as.osgi.OSGiMessages;
import org.jboss.as.server.ServerEnvironment;
import org.jboss.as.web.host.CommonWebServer;
import org.jboss.as.web.host.WebDeploymentController;
import org.jboss.as.web.host.WebHost;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.http.HttpService;
import org.osgi.service.http.NamespaceException;

/**
 * A {@link ServiceFactory} for {@link org.osgi.service.http.HttpService}
 *
 * @author Thomas.Diesler@jboss.com
 * @author David Bosschaert
 * @since 19-Jul-2012
 */
final class HttpServiceFactory implements ServiceFactory<HttpService> {

    private final GlobalRegistry registry;
    private final CommonWebServer webServer;
    private final WebHost virtualHost;
    private final ServerEnvironment serverEnvironment;

    HttpServiceFactory(CommonWebServer webServer, WebHost virtualHost, ServerEnvironment serverEnvironment) {
        this.registry = GlobalRegistry.INSTANCE;
        this.webServer = webServer;
        this.virtualHost = virtualHost;
        this.serverEnvironment = serverEnvironment;
    }

    @Override
    public HttpService getService(final Bundle bundle, final ServiceRegistration<HttpService> registration) {
        synchronized (registry) {
            return new HttpServiceImpl(serverEnvironment, webServer, virtualHost, bundle);
        }
    }

    @Override
    public void ungetService(Bundle bundle, ServiceRegistration<HttpService> registration, HttpService service) {
        synchronized (registry) {
            HttpServiceImpl httpService = (HttpServiceImpl) service;
            for (Registration reg : registry.unregister(bundle)) {
                httpService.unregisterInternal(reg);
            }
        }
    }

    static class GlobalRegistry {

        static GlobalRegistry INSTANCE = new GlobalRegistry();
        private Map<String, Registration> registrations = new HashMap<String, Registration>();
        // The aliases of the registrations in progress, by the bundle that registers them
        private Map<String, Bundle> reservations = new HashMap<String, Bundle>();

        private GlobalRegistry() {
        }

        /**
         * Claim the alias for a registration of the bundle before its web context is created: of two registrations of
         * an alias at the same time only one gets that far, the other fails without side effects (102.4)
         */
        synchronized void reserve(String alias, Bundle bundle) throws NamespaceException {
            if (exists(alias) || reservations.containsKey(alias)) { throw new NamespaceException(OSGiMessages.MESSAGES.aliasMappingAlreadyExists(alias)); }
            reservations.put(alias, bundle);
        }

        /**
         * The registration of the alias by the bundle failed
         */
        synchronized void release(String alias, Bundle bundle) {
            reservations.remove(alias, bundle);
        }

        synchronized Registration register(String alias, Bundle bundle, WebDeploymentController context, Servlet servlet, Registration.Type type) throws NamespaceException {
            Bundle reservedBy = reservations.get(alias);
            if (exists(alias) || (reservedBy != null && reservedBy != bundle)) { throw new NamespaceException(OSGiMessages.MESSAGES.aliasMappingAlreadyExists(alias)); }
            reservations.remove(alias);

            LOGGER.infoRegisterHttpServiceAlias(alias);

            Registration result = new Registration(alias, bundle, context, servlet, type);
            registrations.put(alias, result);

            return result;
        }

        synchronized boolean contains(Servlet servlet) {
            for (Registration reg : registrations.values()) {
                if (servlet.equals(reg.getServlet())) { return true; }
            }
            return false;
        }

        synchronized boolean exists(String alias) {
            return registrations.get(alias) != null;
        }

        /**
         * The longest registered alias other than the given one that matches the request path (102.4)
         */
        synchronized String findMatchingAlias(String requestPath, String excludedAlias) {
            String result = null;
            for (String alias : registrations.keySet()) {
                if (alias.equals(excludedAlias))
                    continue;
                boolean matches = alias.equals("/") || requestPath.equals(alias) || requestPath.startsWith(alias + "/");
                if (matches && (result == null || alias.length() > result.length()))
                    result = alias;
            }
            return result;
        }

        synchronized Registration unregister(String alias, Bundle bundle) {

            if (!exists(alias)) {
                LOGGER.errorf(MESSAGES.aliasMappingDoesNotExist(alias));
                return null;
            }

            Registration reg = registrations.get(alias);
            if (bundle != reg.bundle) {
                LOGGER.errorf(MESSAGES.aliasMappingNotOwnedByBundle(alias, bundle));
                return null;
            }

            LOGGER.infoUnregisterHttpServiceAlias(alias);
            return registrations.remove(reg.alias);
        }

        synchronized Set<Registration> unregister(Bundle bundle) {
            Set<Registration> result = new HashSet<Registration>();
            for (Registration reg : new HashSet<Registration>(registrations.values())) {
                if (bundle == reg.bundle) {
                    registrations.remove(reg.alias);
                    result.add(reg);
                }
            }
            return result;
        }
    }

    static class Registration {
        private final String alias;
        private final Bundle bundle;
        private final WebDeploymentController context;
        private final Servlet servlet;
        private final Type type;

        Registration(String alias, Bundle bundle, WebDeploymentController context, Servlet servlet, Type type) {
            this.alias = alias;
            this.bundle = bundle;
            this.context = context;
            this.servlet = servlet;
            this.type = type;
        }

        String getAlias() {
            return alias;
        }

        Bundle getBundle() {
            return bundle;
        }

        WebDeploymentController getContext() {
            return context;
        }

        Servlet getServlet() {
            return servlet;
        }

        public Type getType() {
            return type;
        }

        @Override
        public String toString() {
            return "Registration [alias=" + alias + ",bundle=" + bundle + ",type=" + type + "]";
        }

        enum Type {
            SERVLET, RESOURCE
        }
    }
}
