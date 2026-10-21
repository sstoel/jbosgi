/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2026, Red Hat, Inc., and individual contributors
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
package org.jboss.test.osgi.issues;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.http.HttpContext;
import org.osgi.service.http.HttpService;

/**
 * The shared ServletContext of an HttpContext answers for the alias that was registered first: a servlet that is
 * registered after a resource alias with the same HttpContext gets the context path of the resources. The ServletContext
 * of a request is another object with other attributes, and the attributes of the container are not visible.
 *
 * <h3>Severity</h3>
 * major. Links and request dispatchers of the servlet point to the resource alias; attributes set in init are not seen
 * through the request; file uploads find no temporary directory.
 *
 * <h3>Component</h3>
 * jbosgi/wildfly/integration/http: {@code HttpServiceImpl.ShareableContextWrapper}, {@code SecurityServletWrapper}.
 *
 * <h3>Found by</h3>
 * R8 fix review, residual of wildfly/11 (umb-a-H02).
 *
 * <h3>Spec reference</h3>
 * OSGi Compendium R8 102.10 (one ServletContext per HttpContext); Servlet 6.0 4.8.1 (jakarta.servlet.context.tempdir),
 * ServletRequest.getServletContext.
 *
 * <h3>Code location</h3>
 * wildfly/integration/http/src/main/java/org/jboss/as/osgi/httpservice/HttpServiceImpl.java:371-414 (f49fa4d9).
 *
 * <h3>Root cause</h3>
 * The proxy sent the methods other than attributes and resources to the container context of the first registration,
 * only the ServletConfig returned it, and it kept the attributes in a map of its own.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi/testsuite/integration
 * mvn test -Dtest=HttpServletContextPerAliasIssueTestCase
 * </pre>
 *
 * <h3>Expected</h3>
 * The servlet sees its own context path; the request gives the same ServletContext as the ServletConfig, with the
 * attribute set in init; the temporary directory of the container is an attribute.
 *
 * <h3>Regression vs WF41</h3>
 * no (f49fa4d9).
 */
@RunWith(Arquillian.class)
public class HttpServletContextPerAliasIssueTestCase {

    @ArquillianResource
    BundleContext context;

    @ArquillianResource
    Bundle bundle;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "http-context-per-alias-tests");
        archive.addAsResource(new StringAsset("static"), "www/s.txt");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(HttpService.class, HttpServlet.class, ServletContext.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    /** Reports what it sees of its ServletContext */
    static class ContextServlet extends HttpServlet {
        @Override
        public void init() throws ServletException {
            getServletContext().setAttribute("hpa.init", "set-in-init");
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
            ServletContext servletContext = getServletContext();
            res.getWriter().print(servletContext.getContextPath() + "|" + (req.getServletContext() == servletContext) + "|"
                    + req.getServletContext().getAttribute("hpa.init") + "|" + (servletContext.getAttribute(ServletContext.TEMPDIR) != null));
        }
    }

    @Test
    public void testServletContextOfServletAfterResources() throws Exception {
        ServiceReference<HttpService> sref = context.getServiceReference(HttpService.class);
        Assert.assertNotNull("Precondition: HttpService available", sref);
        HttpService http = context.getService(sref);
        HttpContext shared = http.createDefaultHttpContext();
        http.registerResources("/hpa/static", "/www", shared);
        try {
            http.registerServlet("/hpa", new ContextServlet(), null, shared);
            try {
                String body = get("/hpa/x");
                Assert.assertEquals("The servlet sees its own context path, the ServletContext of its requests (with the attribute of its init) "
                        + "and the temporary directory of the container", "/hpa|true|set-in-init|true", body);
            } finally {
                http.unregister("/hpa");
            }
        } finally {
            http.unregister("/hpa/static");
        }
    }

    private static String get(String path) throws IOException {
        int port = Integer.getInteger("jboss.http.port", 8080) + Integer.getInteger("jboss.socket.binding.port-offset", 0);
        HttpURLConnection con = (HttpURLConnection) new URL("http://localhost:" + port + path).openConnection();
        con.setConnectTimeout(5000);
        con.setReadTimeout(5000);
        int status = con.getResponseCode();
        InputStream in = status < 400 ? con.getInputStream() : con.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            in.transferTo(out);
            in.close();
        }
        return status == 200 ? out.toString("UTF-8") : "HTTP " + status + ": " + out.toString("UTF-8");
    }
}
