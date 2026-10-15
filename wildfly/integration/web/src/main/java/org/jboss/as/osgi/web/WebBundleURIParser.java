/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2010 - 2012 JBoss by Red Hat
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-2.1.html>.
 * #L%
 */
package org.jboss.as.osgi.web;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import jakarta.servlet.Servlet;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.Version;

/**
 * Parser for 'webbundle://' locations.
 *
 * @author thomas.diesler@jboss.com
 * @since 30-Nov-2012
 */
public final class WebBundleURIParser {

    // The headers that the servlet container needs, imported by a WAR that is turned into a WAB
    private static final String[] DEFAULT_IMPORTS = new String[] { WebServlet.class.getPackage().getName(), Servlet.class.getPackage().getName(),
            HttpServlet.class.getPackage().getName(), Bundle.class.getPackage().getName() };

    // Hide ctor
    private WebBundleURIParser() {
    }

    /**
     * Parse a bundle location as a webbundle URI and generate a Manifest from it, for an input without a manifest
     *
     * @param location The bundle location
     * @return A valid OSGi Manifest or null
     * @throws IllegalArgumentException if the location is not a valid webbundle URI
     */
    public static Manifest parse(String location) {
        try {
            return parse(location, null, Collections.<String> emptyList());
        } catch (BundleException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
    }

    /**
     * The manifest of the WAB of a webbundle URI (128.4): a WAB as input only gets the Web-ContextPath of the URI, a WAR
     * gets its manifest with the URI parameters as headers and the libraries in WEB-INF/lib on its Bundle-ClassPath.
     *
     * @param location The bundle location
     * @param input The manifest of the input archive, may be null
     * @param libraries The jars in WEB-INF/lib of the input archive
     * @return The manifest of the WAB, null if the location is not a webbundle URI
     */
    public static Manifest parse(String location, Manifest input, List<String> libraries) throws BundleException {

        if (!location.startsWith(WebExtension.WEBBUNDLE_PREFIX))
            return null;

        URI uri;
        try {
            uri = new URI(location);
        } catch (URISyntaxException ex) {
            throw new BundleException("Invalid webbundle URI: " + location, BundleException.MANIFEST_ERROR, ex);
        }

        // If the scheme specific part is an URI we use that
        String schemeSpecificPart = uri.getSchemeSpecificPart();
        try {
            uri = new URI(schemeSpecificPart);
        } catch (URISyntaxException ex) {
            // ignore
        }

        // The parameters, their names are case insensitive (128.4.5)
        Map<String, String> params = new LinkedHashMap<String, String>();
        String query = uri.getQuery();
        if (query != null) {
            for (String part : query.split("&")) {
                int index = part.indexOf('=');
                String name = index > 0 ? part.substring(0, index) : part;
                String value = index > 0 ? part.substring(index + 1) : "";
                params.put(canonicalName(name), value);
            }
        }

        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        Attributes inputAttributes = input != null ? input.getMainAttributes() : null;
        boolean wabInput = inputAttributes != null && inputAttributes.getValue(Constants.BUNDLE_SYMBOLICNAME) != null;

        if (wabInput) {
            // A WAB keeps its headers, only the Web-ContextPath can be given (128.4.4)
            for (String name : params.keySet()) {
                if (!name.equals(WebExtension.WEB_CONTEXTPATH))
                    throw new BundleException("Only Web-ContextPath can be given for a WAB: " + name, BundleException.MANIFEST_ERROR);
            }
            attributes.putAll(inputAttributes);
            if (params.containsKey(WebExtension.WEB_CONTEXTPATH))
                attributes.putValue(WebExtension.WEB_CONTEXTPATH, params.get(WebExtension.WEB_CONTEXTPATH));
            if (attributes.getValue(WebExtension.WEB_CONTEXTPATH) == null)
                throw new BundleException("No Web-ContextPath for: " + location, BundleException.MANIFEST_ERROR);
            return manifest;
        }

        // A WAR: its manifest with the parameters as headers
        if (inputAttributes != null)
            attributes.putAll(inputAttributes);
        for (Map.Entry<String, String> entry : params.entrySet())
            attributes.putValue(entry.getKey(), entry.getValue());

        // The context path of the legacy form webbundle://name is the name
        if (attributes.getValue(WebExtension.WEB_CONTEXTPATH) == null && uri.getHost() != null) {
            String contextPath = uri.getHost();
            if (contextPath.endsWith(".war")) {
                contextPath = contextPath.substring(0, contextPath.length() - 4);
            } else {
                int index = contextPath.indexOf(".jar");
                if (index > 0) {
                    contextPath = contextPath.substring(0, index);
                }
            }
            attributes.putValue(WebExtension.WEB_CONTEXTPATH, contextPath);
        }
        if (attributes.getValue(WebExtension.WEB_CONTEXTPATH) == null)
            throw new BundleException("The Web-ContextPath parameter is mandatory for a WAR: " + location, BundleException.MANIFEST_ERROR);

        // A WAR without Bundle-SymbolicName gets one derived from its name (128.4.5)
        if (attributes.getValue(Constants.BUNDLE_SYMBOLICNAME) == null)
            attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, generateSymbolicName(uri));

        String manifestVersion = attributes.getValue(Constants.BUNDLE_MANIFESTVERSION);
        if (manifestVersion == null) {
            attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        } else if (!manifestVersion.trim().equals("2")) {
            throw new BundleException("Invalid Bundle-ManifestVersion for a WAB: " + manifestVersion, BundleException.MANIFEST_ERROR);
        }
        String version = attributes.getValue(Constants.BUNDLE_VERSION);
        if (version != null) {
            try {
                Version.parseVersion(version);
            } catch (IllegalArgumentException ex) {
                throw new BundleException("Invalid Bundle-Version: " + version, BundleException.MANIFEST_ERROR, ex);
            }
        }

        // The classes and the libraries of the WAR are on the Bundle-ClassPath
        Set<String> classpath = new LinkedHashSet<String>();
        String existing = attributes.getValue(Constants.BUNDLE_CLASSPATH);
        if (existing != null) {
            for (String entry : existing.split(","))
                classpath.add(entry.trim());
        }
        classpath.add("WEB-INF/classes");
        for (String library : libraries != null ? libraries : Collections.<String> emptyList())
            classpath.add(library);
        attributes.putValue(Constants.BUNDLE_CLASSPATH, String.join(",", classpath));

        // The packages of the servlet container
        Set<String> imports = new LinkedHashSet<String>();
        String existingImports = attributes.getValue(Constants.IMPORT_PACKAGE);
        if (existingImports != null && !existingImports.trim().isEmpty())
            imports.add(existingImports.trim());
        for (String pkg : DEFAULT_IMPORTS) {
            if (existingImports == null || !existingImports.contains(pkg))
                imports.add(pkg);
        }
        attributes.putValue(Constants.IMPORT_PACKAGE, String.join(",", imports));
        return manifest;
    }

    // The name of the archive without its extension, with the characters that a symbolic name cannot have replaced
    private static String generateSymbolicName(URI uri) {
        String path = uri.getPath() != null ? uri.getPath() : uri.getSchemeSpecificPart();
        String name = path != null ? path.substring(path.lastIndexOf('/') + 1) : "";
        int index = name.lastIndexOf('.');
        if (index > 0)
            name = name.substring(0, index);
        if (name.isEmpty() && uri.getHost() != null)
            name = uri.getHost();
        name = name.replaceAll("[^A-Za-z0-9_.-]", "_");
        return name.isEmpty() ? "webbundle" : name;
    }

    // The header names in their canonical case, a parameter name is case insensitive
    private static String canonicalName(String name) {
        for (String header : new String[] { Constants.BUNDLE_SYMBOLICNAME, Constants.BUNDLE_VERSION, Constants.BUNDLE_MANIFESTVERSION,
                Constants.IMPORT_PACKAGE, WebExtension.WEB_CONTEXTPATH }) {
            if (header.equalsIgnoreCase(name))
                return header;
        }
        return name;
    }
}
