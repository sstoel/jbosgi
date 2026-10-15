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

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jboss.as.osgi.httpservice.HttpServiceFactory.GlobalRegistry;
import org.osgi.service.http.HttpContext;

/**
 * An {@link org.osgi.service.http.HttpService} implementation
 *
 * @author Thomas.Diesler@jboss.com
 * @since 19-Jul-2012
 */
@SuppressWarnings("serial")
final class ResourceServlet extends HttpServlet {

    // The resource of the request, from service to the method handlers of HttpServlet
    private static final String TARGET_ATTRIBUTE = ResourceServlet.class.getName() + ".target";
    private static final String URL_ATTRIBUTE = ResourceServlet.class.getName() + ".url";

    private final String alias;
    private final String name;
    private final HttpContext context;

    ResourceServlet(String alias, String name, HttpContext context) {
        this.alias = alias;
        this.name = name;
        this.context = context;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {

        // The internal name "/" stands for the root, an exact match of the alias is the name itself (102.4)
        String pathInfo = req.getPathInfo();
        String prefix = "/".equals(name) ? "" : name;
        String target = (pathInfo == null || pathInfo.isEmpty()) ? name : prefix + pathInfo;
        URL resurl = context.getResource(target);

        // A directory is not a resource, its URL would give the content of the whole subtree
        if (resurl == null || target.endsWith("/") || resurl.getPath().endsWith("/")) {
            // The next shorter alias that matches the request handles a resource that this registration does not have
            if (resurl == null && dispatchToShorterAlias(req, res, pathInfo))
                return;
            res.sendError(HttpServletResponse.SC_NOT_FOUND, req.getRequestURI());
            return;
        }

        // The method dispatch of HttpServlet: GET and HEAD serve the resource, with the conditional GET of
        // getLastModified, OPTIONS answers the allowed methods, the other methods are rejected (405 or 501)
        req.setAttribute(TARGET_ATTRIBUTE, target);
        req.setAttribute(URL_ATTRIBUTE, resurl);
        try {
            super.service(req, res);
        } finally {
            req.removeAttribute(TARGET_ATTRIBUTE);
            req.removeAttribute(URL_ATTRIBUTE);
        }
    }

    @Override
    protected long getLastModified(HttpServletRequest req) {
        URL resurl = (URL) req.getAttribute(URL_ATTRIBUTE);
        if (resurl == null)
            return -1;
        try {
            long lastModified = resurl.openConnection().getLastModified();
            return lastModified != 0 ? lastModified : -1;
        } catch (IOException ex) {
            return -1;
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
        String target = (String) req.getAttribute(TARGET_ATTRIBUTE);
        URL resurl = (URL) req.getAttribute(URL_ATTRIBUTE);

        URLConnection conn = resurl.openConnection();
        if (conn.getContentLength() != -1) {
            res.setContentLength(conn.getContentLength());
        }
        // The type is the one of the HttpContext, else the one of the servlet container
        String type = context.getMimeType(target);
        if (type == null)
            type = getServletContext().getMimeType(target);
        if (type == null)
            type = conn.getContentType();
        if (type != null) {
            res.setContentType(type);
        }
        if(conn.getContentEncoding() != null) {
            res.setCharacterEncoding(conn.getContentEncoding());
        }
        if(conn.getDate() != 0) {
            res.setDateHeader("date", conn.getDate());
        }
        if(conn.getExpiration() != 0) {
            res.setDateHeader("expires", conn.getExpiration());
        }
        if(conn.getLastModified() != 0) {
            res.setDateHeader("last-modified", conn.getLastModified());
        }
        ServletOutputStream out = res.getOutputStream();
        try (InputStream in = conn.getInputStream()) {
            IOUtils.copyStream(out, in);
        }
    }

    private boolean dispatchToShorterAlias(HttpServletRequest req, HttpServletResponse res, String pathInfo) throws ServletException, IOException {
        String requestPath = "/".equals(alias) ? (pathInfo != null ? pathInfo : "/") : alias + (pathInfo != null ? pathInfo : "");
        String fallbackAlias = GlobalRegistry.INSTANCE.findMatchingAlias(requestPath, alias);
        if (fallbackAlias == null || fallbackAlias.length() >= alias.length())
            return false;
        ServletContext fallbackContext = req.getServletContext().getContext("/".equals(fallbackAlias) ? "" : fallbackAlias);
        if (fallbackContext == null)
            return false;
        String remaining = "/".equals(fallbackAlias) ? requestPath : requestPath.substring(fallbackAlias.length());
        RequestDispatcher dispatcher = fallbackContext.getRequestDispatcher(remaining.isEmpty() ? "/" : remaining);
        if (dispatcher == null)
            return false;
        dispatcher.forward(req, res);
        return true;
    }
}