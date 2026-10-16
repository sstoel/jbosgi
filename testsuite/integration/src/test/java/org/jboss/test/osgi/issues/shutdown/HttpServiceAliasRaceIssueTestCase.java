/*
 * #%L
 * JBossOSGi
 * %%
 * Copyright (C) 2026 RTR
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 * #L%
 */
package org.jboss.test.osgi.issues.shutdown;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.http.HttpService;
import org.osgi.service.http.NamespaceException;

/**
 * Http Service: two bundles that register the same alias at the same time (e.g. two bundle deployments started in
 * parallel by MSC at boot or during concurrent deploys) both get an Undertow web context. The alias check
 * ({@code validateAlias}) runs before the context is created and started, the global registration only after it, and
 * only per-bundle {@code synchronized} is held in between. The losing bundle gets a NamespaceException, but its web
 * context is never stopped or removed: it stays registered on the host and serves the alias in place of the
 * registered owner's servlet. The leaked context is never destroyed (it keeps the losing servlet and its bundle class
 * loader), and depending on which context Undertow created last, the owner's later unregister fails to destroy its
 * context (ERROR JBAS018209 "Failed to destroy context: UT010002: Deployments can only be removed when in undeployed
 * state, but state was STARTED", seen in the fuzzer run).
 *
 * <h3>Severity</h3>
 * major (a startup/deploy race: requests are routed to a servlet whose registration was rejected; the registered
 * owner's servlet is unreachable; the leaked Undertow deployment and the servlet/bundle class loader stay until the
 * server stops. Two deployments registering the same alias are a
 * conflict, but the Http Service contract is that the second one is rejected without side effects.)
 *
 * <h3>Component</h3>
 * jbosgi umbrella, wildfly/integration/http: {@code org.jboss.as.osgi.httpservice.HttpServiceImpl} (registerServlet /
 * registerResources -&gt; validateAlias -&gt; registerInternal) and {@code HttpServiceFactory.GlobalRegistry.register}.
 *
 * <h3>Found by</h3>
 * sd-srv-fuzz workstream (shutdown round), randomized lifecycle fuzzer on the patched WildFly 41.0.1 + jbosgi R8 server
 * (JDK 25), seed 1 iteration 2: after a restart the two bundle deployments fz.b2.jar and scan-fz.b2.jar (same Http
 * alias /fz-b2) were started in parallel on MSC threads 1-5 and 1-7; server.log shows two "JBAS018210: Register web
 * context: /fz-b2" and two "WFLYUT0021: Registered web context: '/fz-b2'" in the same millisecond but one "JBAS011913:
 * Register HttpService alias", and 14 s later, at the owner's stop, "JBAS018209: Failed to destroy context:
 * java.lang.IllegalStateException: UT010002". This test forces that interleaving deterministically: the first
 * registration's {@code Servlet.init} (called while the context starts) blocks until the second registration returned.
 *
 * <h3>Spec reference</h3>
 * OSGi Compendium R8, 102.4 Mapping HTTP Requests to Servlet and Resource Registrations
 * (https://docs.osgi.org/specification/osgi.cmpn/8.0.0/service.http.html): "Registrations for identical aliases are
 * not allowed. If a bundle registers the alias "/fudd", and another bundle tries to register the exactly the same
 * alias, the second caller must receive a NamespaceException and its resource or servlet must not be registered."
 * {@code HttpService.registerServlet}: "Throws NamespaceException - if the registration fails because the alias is
 * already in use." {@code HttpService.unregister}: "After this call, the registered alias in the URI name-space will
 * no longer be available. If the registration was for a servlet, the Http Service must call the destroy method of the
 * servlet before returning." (the rejected servlet is served and never destroyed).
 *
 * <h3>Code location</h3>
 * <ul>
 * <li>wildfly/integration/http/src/main/java/org/jboss/as/osgi/httpservice/HttpServiceImpl.java:87-93
 * (registerServlet: validateAlias(alias, false) outside any global lock), :108 (registerInternal is
 * {@code synchronized} on the per-bundle HttpServiceImpl, created by the ServiceFactory for each bundle), :161-173
 * (addWebDeployment, create, start - the context is live on the host here), :175 (registry.register throws
 * NamespaceException when another bundle registered the alias in the meantime; the started context is not stopped or
 * destroyed), :207-218 (unregisterInternal stop + destroy).</li>
 * <li>wildfly/integration/http/src/main/java/org/jboss/as/osgi/httpservice/HttpServiceFactory.java:92-101
 * (GlobalRegistry.register: the only global check, after the context was started).</li>
 * </ul>
 *
 * <h3>Root cause</h3>
 * Check-then-act on the alias without a global lock spanning check, context creation and registration, and no cleanup
 * of the created context when the final registration fails. Undertow keys deployments by context path, so the
 * loser's deployment also replaces the owner's entry (hence UT010002 when the owner's context is destroyed).<br>
 * Related: wildfly/08 (HttpServletInitFailureIssueTestCase: a failed {@code Servlet.init} leaves the alias
 * registered). Same fix site (wildfly/integration/http registration path, missing cleanup on failure) but a different
 * trigger; fix both together.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=HttpServiceAliasRaceIssueTestCase
 * </pre>
 * (Arquillian test; on JDK 24+ with the wildfly/03 surefire.security.args edit; use a port offset as described in
 * HOW-TO-RUN, the HTTP port is read as jboss.http.port + jboss.socket.binding.port-offset.)
 *
 * <h3>Observed</h3>
 * JDK 25, WF41-osgi-r8 (jbosgi f025b420), patched WildFly 41.0.1 (run.log):
 * <pre>
 * org.junit.ComparisonFailure: Expected /alias-race to be served by the registered servlet-B (registration A failed
 *   with org.osgi.service.http.NamespaceException: JBAS011967: HttpService mapping already exists: /alias-race,
 *   registration B succeeded), but GET answered '200 servlet-A'; after the owner unregistered it answered '404 '
 *   expected:&lt;200 servlet-[B]&gt; but was:&lt;200 servlet-[A]&gt;
 * server.log: JBAS018210 Register web context: /alias-race (alias-race-A), JBAS018210 + WFLYUT0021 (B),
 *   JBAS011913 Register HttpService alias (B only), WFLYUT0021 Registered web context '/alias-race' (alias-race-A,
 *   after B, although A's registration then failed)
 * </pre>
 *
 * <h3>Expected</h3>
 * Exactly one registration succeeds, the alias serves that bundle's servlet, the other registration fails with
 * NamespaceException and leaves nothing behind; after the owner unregisters, the alias answers 404.
 *
 * <h3>Reference behaviour</h3>
 * Felix Http Jetty and Equinox Http Service check and claim the alias atomically under one registry lock (Felix
 * {@code HandlerRegistry}/{@code PerContextHandlerRegistry.addServlet} is synchronized and throws
 * NamespaceException before anything is started).
 *
 * <h3>Suggested fix</h3>
 * Claim the alias in GlobalRegistry atomically before the Undertow context is created (e.g. a {@code reserve(alias,
 * bundle)} under the GlobalRegistry monitor, released on failure), or hold one global lock across validateAlias,
 * context creation and registry.register; on any failure after addWebDeployment stop and destroy the created
 * context. Trial (fix.diff in the issue folder, never applied to a checkout: GlobalRegistry.reserve/release around
 * registerInternal): this test passes (1 of 1, 4.8 s).
 *
 * <h3>Regression vs WF41</h3>
 * no (measured with the same forced interleaving on a WF41 server built from origin/WF41 77f95be6 on the same patched
 * WildFly 41.0.1, JDK 25, using the probe-bundle scenario explore/sd-srv-fuzz/scen/s12-http-alias-race.json - bundle
 * fz.ha's servlet init waits for a signal file while fz.hb registers the same alias: on WF41 and on R8 alike fz.ha's
 * registration fails with JBAS011967 but GET /race answers "200 fz.ha"). The check-then-act code predates WF41.
 */
@RunWith(Arquillian.class)
public class HttpServiceAliasRaceIssueTestCase {

    static final String OTHER = "http-alias-race-other";
    static final String ALIAS = "/alias-race";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "http-alias-race-tests");
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

    @Deployment(name = OTHER, managed = false, testable = false)
    public static JavaArchive otherBundle() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, OTHER);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                return builder.openStream();
            }
        });
        return archive;
    }

    static class TextServlet extends HttpServlet {
        private final String text;
        private final CountDownLatch entered;
        private final CountDownLatch release;

        TextServlet(String text, CountDownLatch entered, CountDownLatch release) {
            this.text = text;
            this.entered = entered;
            this.release = release;
        }

        @Override
        public void init(ServletConfig config) throws ServletException {
            super.init(config);
            if (entered != null) {
                // the first registration passed the alias check and is starting its context: let the other one run
                entered.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
            res.getWriter().print(text);
        }
    }

    private static HttpService httpService(BundleContext ctx) {
        ServiceReference<HttpService> sref = ctx.getServiceReference(HttpService.class);
        Assert.assertNotNull("Precondition: HttpService available", sref);
        return ctx.getService(sref);
    }

    /** GET on the server's HTTP port; returns "status body" */
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
        String body = out.toString("UTF-8");
        return status + " " + (status < 400 ? body : "");
    }

    @Test
    public void testConcurrentRegistrationOfSameAlias() throws Exception {
        Bundle other = context.installBundle(OTHER, deployer.getDeployment(OTHER));
        HttpService httpA = httpService(context);
        HttpService httpB = null;
        try {
            other.start();
            httpB = httpService(other.getBundleContext());

            final CountDownLatch entered = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final AtomicReference<Throwable> resultA = new AtomicReference<Throwable>();
            final HttpService fa = httpA;
            Thread t = new Thread(() -> {
                try {
                    fa.registerServlet(ALIAS, new TextServlet("servlet-A", entered, release), null, null);
                } catch (Throwable th) {
                    resultA.set(th);
                }
            }, "alias-race-A");
            t.start();
            Assert.assertTrue("Precondition: registration A reached Servlet.init within 30 s", entered.await(30, TimeUnit.SECONDS));

            Throwable resultB = null;
            try {
                httpB.registerServlet(ALIAS, new TextServlet("servlet-B", null, null), null, null);
            } catch (Throwable th) {
                resultB = th;
            } finally {
                release.countDown();
            }
            t.join(30000);
            Assert.assertFalse("Precondition: registration A finished", t.isAlive());

            boolean aOk = resultA.get() == null, bOk = resultB == null;
            String outcome = "registration A " + (aOk ? "succeeded" : "failed with " + resultA.get()) + ", registration B "
                    + (bOk ? "succeeded" : "failed with " + resultB);
            Assert.assertTrue("Expected exactly one of two concurrent registrations of " + ALIAS + " to succeed, but " + outcome, aOk != bOk);
            Assert.assertTrue("Expected the losing registration to fail with NamespaceException, but " + outcome,
                    (aOk ? resultB : resultA.get()) instanceof NamespaceException);

            String winner = aOk ? "servlet-A" : "servlet-B";
            String served = get(ALIAS);
            String afterUnregister;
            if (aOk)
                httpA.unregister(ALIAS);
            else
                httpB.unregister(ALIAS);
            afterUnregister = get(ALIAS);
            Assert.assertEquals("Expected " + ALIAS + " to be served by the registered " + winner + " (" + outcome + "), but GET answered '"
                    + served + "'; after the owner unregistered it answered '" + afterUnregister + "'", "200 " + winner, served);
            Assert.assertEquals("Expected " + ALIAS + " to be gone (404) after its owner unregistered it (" + outcome + "), but GET answered '"
                    + afterUnregister + "'", "404 ", afterUnregister);
        } finally {
            for (HttpService h : new HttpService[] { httpA, httpB }) {
                if (h == null)
                    continue;
                try {
                    h.unregister(ALIAS);
                } catch (RuntimeException ex) {
                    // not registered by this bundle
                }
            }
            other.uninstall();
        }
    }
}
