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

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;

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
import org.osgi.service.http.HttpService;

/**
 * HttpService.registerServlet and the release of the HttpService by the same bundle deadlock.
 *
 * <h2>Severity</h2>
 * critical. The registering thread and the thread that releases the service (e.g. the MSC thread that stops the
 * bundle) wait for each other forever; the Http Service's registry stays locked for every other bundle and the server
 * ends restart-required (WFLYCTL0348/0349).
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/integration/http: {@code HttpServiceImpl.registerInternal} and
 * {@code HttpServiceFactory.ungetService}.
 *
 * <h2>Found by</h2>
 * R8 fix review, regression 2 (umb-a-H01).
 *
 * <h2>Code location</h2>
 * wildfly/integration/http/src/main/java/org/jboss/as/osgi/httpservice/HttpServiceImpl.java:147, 284 and
 * HttpServiceFactory.java:76 (f49fa4d9).
 *
 * <h2>Root cause</h2>
 * registerInternal holds the monitor of the bundle's HttpServiceImpl while the servlet is initialized and then takes
 * the monitor of the registry; ungetService held the monitor of the registry and unregistered the registrations of the
 * bundle, which takes the monitor of the HttpServiceImpl.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=HttpServiceUngetDuringRegisterIssueTestCase
 * </pre>
 * The bundle has the registration /hunget-a. One thread registers /hunget-b with a servlet whose init blocks, another
 * thread releases the HttpService, then init is released.
 *
 * <h2>Expected</h2>
 * Both threads finish. The registration that was in progress when the service was released does not stay registered.
 *
 * <h2>Regression vs WF41</h2>
 * yes, introduced by f49fa4d9.
 */
@RunWith(Arquillian.class)
public class HttpServiceUngetDuringRegisterIssueTestCase {

    @ArquillianResource
    Bundle bundle;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "http-unget-register-tests");
        archive.addAsResource(new StringAsset("content-a"), "www/a.txt");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(HttpService.class, Servlet.class, HttpServlet.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    static class BlockingInitServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;
        final CountDownLatch inInit = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void init(ServletConfig config) throws ServletException {
            super.init(config);
            inInit.countDown();
            try {
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    public void testRegisterServletConcurrentWithUngetService() throws Exception {
        final BundleContext context = bundle.getBundleContext();
        final ServiceReference<HttpService> sref = context.getServiceReference(HttpService.class);
        Assert.assertNotNull("HttpService available", sref);
        final HttpService http = context.getService(sref);
        http.registerResources("/hunget-a", "/www", null);

        final BlockingInitServlet servlet = new BlockingInitServlet();
        final AtomicReference<Throwable> registerFailure = new AtomicReference<Throwable>();
        Thread register = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    http.registerServlet("/hunget-b", servlet, null, null);
                } catch (Throwable th) {
                    registerFailure.set(th);
                }
            }
        }, "hunget-register");
        Thread unget = new Thread(new Runnable() {
            @Override
            public void run() {
                context.ungetService(sref);
            }
        }, "hunget-unget");
        register.setDaemon(true);
        unget.setDaemon(true);
        register.start();
        Assert.assertTrue("Servlet.init of /hunget-b runs", servlet.inInit.await(30, TimeUnit.SECONDS));
        unget.start();
        // Let the release of the service reach the HttpServiceImpl
        long deadline = System.currentTimeMillis() + 10000;
        while (unget.getState() != Thread.State.BLOCKED && unget.isAlive() && System.currentTimeMillis() < deadline)
            Thread.sleep(50);
        servlet.release.countDown();
        register.join(30000);
        unget.join(30000);

        StringBuilder deadlocked = new StringBuilder();
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long[] ids = mx.findMonitorDeadlockedThreads();
        if (ids != null) {
            for (ThreadInfo info : mx.getThreadInfo(ids, true, true))
                deadlocked.append(info);
        }
        Assert.assertFalse("registerServlet and ungetService deadlocked: register " + register.getState() + ", unget " + unget.getState() + "\n" + deadlocked,
                register.isAlive() || unget.isAlive());

        // The registration that was in progress when the service was released is gone
        HttpService again = context.getService(sref);
        try {
            again.registerResources("/hunget-b", "/www", null);
            again.unregister("/hunget-b");
        } finally {
            context.ungetService(sref);
        }
        Assert.assertTrue("The registration fails because the service was released: " + registerFailure.get(), registerFailure.get() instanceof IllegalStateException);
    }
}
