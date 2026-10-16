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

import static org.jboss.as.osgi.httpservice.WebLogger.WEB_LOGGER;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.jboss.as.osgi.OSGiMessages;
import org.jboss.as.osgi.httpservice.HttpServiceFactory.GlobalRegistry;
import org.jboss.as.osgi.httpservice.HttpServiceFactory.Registration;
import org.jboss.as.osgi.httpservice.HttpServiceFactory.Registration.Type;
import org.jboss.as.server.ServerEnvironment;
import org.jboss.as.web.host.ApplicationContextWrapper;
import org.jboss.as.web.host.CommonWebServer;
import org.jboss.as.web.host.ServletBuilder;
import org.jboss.as.web.host.WebDeploymentBuilder;
import org.jboss.as.web.host.WebDeploymentController;
import org.jboss.as.web.host.WebHost;
import org.osgi.framework.Bundle;
import org.osgi.service.http.HttpContext;
import org.osgi.service.http.HttpService;
import org.osgi.service.http.NamespaceException;

/**
 * An {@link HttpService} implementation
 *
 * @author Thomas.Diesler@jboss.com
 * @author David Bosschaert
 * @since 19-Jul-2012
 */
final class HttpServiceImpl implements HttpService {

    private final GlobalRegistry registry;
    private final ServerEnvironment serverEnvironment;
    private final CommonWebServer webServer;
    private final WebHost virtualHost;
    private final Bundle bundle;

    // This map holds the shared ApplicationContexts to be used with the associated HttpContext.
    // It is a WeakHashMap which means that the ApplicationContexts are remembered for as long
    // as the HttpContext exists.
    private final Map<HttpContext, ShareableContextWrapper> contexts = new WeakHashMap<HttpContext, ShareableContextWrapper>();
    // The servlet wrappers of the registrations by alias
    private final Map<String, SecurityServletWrapper> servletWrappers = new ConcurrentHashMap<String, SecurityServletWrapper>();

    HttpServiceImpl(ServerEnvironment serverEnvironment, CommonWebServer webServer, WebHost virtualHost, Bundle bundle) {
        this.registry = GlobalRegistry.INSTANCE;
        this.virtualHost = virtualHost;
        this.webServer = webServer;
        this.serverEnvironment = serverEnvironment;
        this.bundle = bundle;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void registerServlet(String alias, Servlet servlet, Dictionary initparams, HttpContext httpContext) throws ServletException, NamespaceException {
        validateAlias(alias, false);
        validateServlet(servlet);

        registerInternal(alias, servlet, initparams, httpContext, Type.SERVLET);

    }

    @Override
    public void registerResources(String alias, String name, HttpContext httpContext) throws NamespaceException {
        validateAlias(alias, false);
        validateName(name);

        if (httpContext == null) { httpContext = createDefaultHttpContext(); }
        ResourceServlet servlet = new ResourceServlet(alias, name, httpContext);

        // The security of the resources is that of their HttpContext (handleSecurity)
        try {
            registerInternal(alias, servlet, null, httpContext, Type.RESOURCE);
        } catch (ServletException ex) {
            throw new NamespaceException(WEB_LOGGER.startContextFailed(), ex);
        }
    }

    /**
     * The (empty) document root of the registration of an alias. The alias is a URI path, not a file path: the directory
     * is named by a digest of the alias, which is valid on every file system whatever the length and characters of the alias.
     */
    private File getDocumentRoot(String alias) {
        String name;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(alias.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++)
                hex.append(String.format("%02x", digest[i]));
            name = hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            name = Integer.toHexString(alias.hashCode());
        }
        return new File(serverEnvironment.getServerTempDir(), "osgiservlet" + File.separator + name);
    }

    @SuppressWarnings("rawtypes")
    private synchronized ServletBuilder registerInternal(String alias, Servlet servlet, Dictionary initparams, HttpContext httpContext, Type type) throws NamespaceException, ServletException {
        // The alias is claimed before the web context exists, a registration that fails releases it
        registry.reserve(alias, bundle);
        boolean registered = false;
        try {
            ServletBuilder result = registerReserved(alias, servlet, initparams, httpContext, type);
            registered = true;
            return result;
        } finally {
            if (!registered)
                registry.release(alias, bundle);
        }
    }

    @SuppressWarnings("rawtypes")
    private ServletBuilder registerReserved(String alias, Servlet servlet, Dictionary initparams, HttpContext httpContext, Type type) throws NamespaceException, ServletException {
        File storageDir = getDocumentRoot(alias);
        storageDir.mkdirs();

        //ShareableContext ctx;
        WebDeploymentBuilder deploymentBuilder = new WebDeploymentBuilder();
        // The servlets of one HttpContext share its ServletContext (102.2)
        if (httpContext == null) {
            httpContext = new DefaultHttpContext(bundle);
        }
        ShareableContextWrapper scWrapper = contexts.get(httpContext);
        if (scWrapper == null) {
            scWrapper = new ShareableContextWrapper(httpContext);
            contexts.put(httpContext, scWrapper);
        }


        deploymentBuilder.setDocumentRoot(storageDir);
        deploymentBuilder.setContextRoot(alias);
        deploymentBuilder.setApplicationContextWrapper(scWrapper);
        // An alias has no WEB-INF and META-INF directories, the registration gets every path below it (102.4)
        deploymentBuilder.setProtectedPathsServed(true);
        //ctx.addLifecycleListener(new ContextConfig());

        deploymentBuilder.setClassLoader(servlet.getClass().getClassLoader());

        deploymentBuilder.addMimeMapping("html", "text/html");
        deploymentBuilder.addMimeMapping("jpg", "image/jpeg");
        deploymentBuilder.addMimeMapping("png", "image/png");
        deploymentBuilder.addMimeMapping("gif", "image/gif");
        deploymentBuilder.addMimeMapping("css", "text/css");
        deploymentBuilder.addMimeMapping("js", "text/javascript");

        String wrapperName = alias.substring(1);
        ServletBuilder wrapper = new ServletBuilder();

        wrapper.setServletName(wrapperName);
        SecurityServletWrapper servletWrapper = new SecurityServletWrapper(servlet, httpContext, scWrapper);
        wrapper.setServlet(servletWrapper);
        wrapper.setServletClass(servlet.getClass());

        // Init parameters
        if (initparams != null) {
            Enumeration keys = initparams.keys();
            while (keys.hasMoreElements()) {
                String key = (String) keys.nextElement();
                String val = (String) initparams.get(key);
                wrapper.addInitParam(key, val);
            }
        }

        wrapper.setForceInit(true);
        wrapper.addUrlMapping("/*");
        deploymentBuilder.addServlet(wrapper);

        WebDeploymentController deploymentController;
        try {
            deploymentController = virtualHost.addWebDeployment(deploymentBuilder);
            WEB_LOGGER.registerWebapp(deploymentBuilder.getContextRoot());
            deploymentController.create();
        } catch (Exception ex) {
            throw new NamespaceException(WEB_LOGGER.createContextFailed(), ex);
        }
        try {
            deploymentController.start();
        } catch (Exception ex) {
            // The context that was created does not stay on the host
            stopDeployment(deploymentController);
            scWrapper.removeContext(servletWrapper.getContainerContext());
            throw new NamespaceException(WEB_LOGGER.startContextFailed(), ex);
        }

        // The container only logs a failing Servlet.init, the registration fails and its alias stays free
        Throwable initFailure = servletWrapper.getInitFailure();
        if (initFailure != null) {
            stopDeployment(deploymentController);
            scWrapper.removeContext(servletWrapper.getContainerContext());
            if (initFailure instanceof ServletException)
                throw (ServletException) initFailure;
            throw new ServletException(initFailure);
        }

        try {
            registry.register(alias, bundle, deploymentController, servlet, type);
        } catch (NamespaceException ex) {
            stopDeployment(deploymentController);
            scWrapper.removeContext(servletWrapper.getContainerContext());
            throw ex;
        }
        servletWrappers.put(alias, servletWrapper);

        // Must be added to the main mapper as no dynamic servlets usually
        /*Mapper mapper = webServer.getService().getMapper();
        mapper.addWrapper(virtualHost.getName(), ctx.getPath(), pattern, wrapper, false);*/

        return wrapper;
    }

    @Override
    public void unregister(String alias) {
        try {
            validateAlias(alias, true);
        } catch (NamespaceException e) {
            WEB_LOGGER.errorf(e, "");
            return;
        }

        Registration reg = registry.unregister(alias, bundle);
        if (reg != null) {
            unregisterInternal(reg);
        }
    }

    @Override
    public HttpContext createDefaultHttpContext() {
        return new DefaultHttpContext(bundle);
    }

    void unregisterInternal(Registration reg) {
        stopDeployment(reg.getContext());
        SecurityServletWrapper servletWrapper = servletWrappers.remove(reg.getAlias());
        if (servletWrapper != null) {
            synchronized (this) {
                ShareableContextWrapper scWrapper = servletWrapper.contextWrapper;
                scWrapper.removeContext(servletWrapper.getContainerContext());
                if (scWrapper.isUnused())
                    contexts.remove(scWrapper.httpContext);
            }
        }
    }

    private static void stopDeployment(WebDeploymentController context) {
        try {
            context.stop();
        } catch (Exception e) {
            WEB_LOGGER.stopContextFailed(e);
        }
        try {
            context.destroy();
        } catch (Exception e) {
            WEB_LOGGER.destroyContextFailed(e);
        }
    }

    private void validateAlias(String alias, boolean exists) throws NamespaceException {
        // An alias must begin with slash ('/') and must not end with slash ('/'), with the exception
        // that an alias of the form "/" is used to denote the root alias
        if (alias == null || !alias.startsWith("/")) { throw new IllegalArgumentException(OSGiMessages.MESSAGES.invalidServletAlias(alias)); }
        if (alias.length() > 1 && alias.endsWith("/")) { throw new IllegalArgumentException(OSGiMessages.MESSAGES.invalidServletAlias(alias)); }

        if (exists && !registry.exists(alias)) { throw new IllegalArgumentException(OSGiMessages.MESSAGES.aliasMappingDoesNotExist(alias)); }
        if (!exists && registry.exists(alias)) { throw new NamespaceException(OSGiMessages.MESSAGES.aliasMappingAlreadyExists(alias)); }
    }

    private void validateName(String name) {
        // The name parameter must also not end with slash ('/') with the exception
        // that a name of the form "/" is used to denote the root of the bundle.
        if (name == null || (name.length() > 1 && name.endsWith("/"))) { throw new IllegalArgumentException(OSGiMessages.MESSAGES.invalidResourceName(name)); }
    }

    private void validateServlet(Servlet servlet) throws ServletException {
        // A single servlet instance can only be registered once.
        if (registry.contains(servlet)) { throw new ServletException(OSGiMessages.MESSAGES.servletAlreadyRegistered(servlet.getServletInfo())); }
    }

    /**
     * The ServletContext of an HttpContext: one object for all its servlets, with shared attributes, whose MIME types
     * and resources come from the HttpContext. The other methods go to the context of a registration that is up.
     */
    static class ShareableContextWrapper implements ApplicationContextWrapper {

        private final HttpContext httpContext;
        private final Map<String, Object> attributes = new ConcurrentHashMap<String, Object>();
        private final List<ServletContext> containerContexts = new CopyOnWriteArrayList<ServletContext>();
        private ServletContext sharedContext;

        ShareableContextWrapper(HttpContext httpContext) {
            this.httpContext = httpContext;
        }

        @Override
        public synchronized Object wrap(final Object context) {
            return context;
        }

        synchronized ServletContext getServletContext(ServletContext containerContext) {
            if (containerContext != null && !containerContexts.contains(containerContext))
                containerContexts.add(containerContext);
            if (sharedContext == null) {
                sharedContext = (ServletContext) Proxy.newProxyInstance(ServletContext.class.getClassLoader(), new Class<?>[] { ServletContext.class },
                        new InvocationHandler() {
                            @Override
                            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                                return invokeShared(proxy, method, args);
                            }
                        });
            }
            return sharedContext;
        }

        void removeContext(ServletContext containerContext) {
            if (containerContext != null)
                containerContexts.remove(containerContext);
        }

        boolean isUnused() {
            return containerContexts.isEmpty();
        }

        private Object invokeShared(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            int argc = args != null ? args.length : 0;
            if (name.equals("equals") && argc == 1)
                return proxy == args[0];
            if (name.equals("hashCode") && argc == 0)
                return System.identityHashCode(proxy);
            if (name.equals("toString") && argc == 0)
                return "ServletContext[" + httpContext + "]";
            if (name.equals("getAttribute") && argc == 1)
                return attributes.get((String) args[0]);
            if (name.equals("getAttributeNames") && argc == 0)
                return Collections.enumeration(new ArrayList<String>(attributes.keySet()));
            if (name.equals("setAttribute") && argc == 2) {
                if (args[1] == null)
                    attributes.remove((String) args[0]);
                else
                    attributes.put((String) args[0], args[1]);
                return null;
            }
            if (name.equals("removeAttribute") && argc == 1) {
                attributes.remove((String) args[0]);
                return null;
            }
            if (name.equals("getMimeType") && argc == 1) {
                String type = httpContext.getMimeType((String) args[0]);
                if (type != null)
                    return type;
            }
            if (name.equals("getResource") && argc == 1)
                return httpContext.getResource((String) args[0]);
            if (name.equals("getResourceAsStream") && argc == 1) {
                URL url = httpContext.getResource((String) args[0]);
                return url != null ? url.openStream() : null;
            }
            ServletContext delegate = containerContexts.isEmpty() ? null : containerContexts.get(0);
            if (delegate == null)
                throw new IllegalStateException("No servlet is registered with " + httpContext);
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause();
            }
        }
    }

    /* This wrapper class takes care of handling the security through the HttpContext.
     */
    static class SecurityServletWrapper implements Servlet {
        private final HttpContext httpContext;
        private final Servlet delegate;
        private final ShareableContextWrapper contextWrapper;
        private volatile Throwable initFailure;
        private volatile ServletContext containerContext;

        SecurityServletWrapper(Servlet servlet, HttpContext ctx, ShareableContextWrapper contextWrapper) {
            if (servlet == null) { throw new NullPointerException(); }
            delegate = servlet;

            if (ctx == null) { throw new NullPointerException(); }
            httpContext = ctx;
            this.contextWrapper = contextWrapper;
        }

        Throwable getInitFailure() {
            return initFailure;
        }

        ServletContext getContainerContext() {
            return containerContext;
        }

        @Override
        public void destroy() {
            delegate.destroy();
        }

        @Override
        public ServletConfig getServletConfig() {
            return delegate.getServletConfig();
        }

        @Override
        public String getServletInfo() {
            return delegate.getServletInfo();
        }

        @Override
        public void init(final ServletConfig sc) throws ServletException {
            // The servlet gets the ServletContext of its HttpContext
            containerContext = sc.getServletContext();
            final ServletContext servletContext = contextWrapper.getServletContext(containerContext);
            ServletConfig config = new ServletConfig() {
                @Override
                public String getServletName() {
                    return sc.getServletName();
                }

                @Override
                public ServletContext getServletContext() {
                    return servletContext;
                }

                @Override
                public String getInitParameter(String name) {
                    return sc.getInitParameter(name);
                }

                @Override
                public Enumeration<String> getInitParameterNames() {
                    return sc.getInitParameterNames();
                }
            };
            try {
                delegate.init(config);
            } catch (ServletException | RuntimeException | Error ex) {
                initFailure = ex;
                throw ex;
            }
        }

        @Override
        public void service(ServletRequest request, ServletResponse response) throws ServletException, IOException {
            if (request instanceof HttpServletRequest && response instanceof HttpServletResponse) {
                HttpServletRequest httpRequest = (HttpServletRequest) request;
                HttpServletResponse httpResponse = (HttpServletResponse) response;
                if (!httpContext.handleSecurity(httpRequest, httpResponse)) { return; }

                Object u = httpRequest.getAttribute(HttpContext.REMOTE_USER);
                String remoteUser = u instanceof String ? (String) u : null;
                Object a = httpRequest.getAttribute(HttpContext.AUTHENTICATION_TYPE);
                String authType = a instanceof String ? (String) a : null;

                if (remoteUser != null || authType != null) {
                    request = new SecurityRequestWrapper(remoteUser, authType, httpRequest);
                }
            }
            delegate.service(request, response);
        }
    }

    /* This wrapper class can provide the remote user and auth type information if provided through the HttpContext.
     */
    static class SecurityRequestWrapper extends HttpServletRequestWrapper implements HttpServletRequest {
        private final String remoteUser;
        private final String authType;

        SecurityRequestWrapper(String remoteUser, String authType, HttpServletRequest request) {
            super(request);
            this.remoteUser = remoteUser;
            this.authType = authType;
        }

        @Override
        public String getAuthType() {
            return authType;
        }

        @Override
        public String getRemoteUser() {
            return remoteUser;
        }
    }
}