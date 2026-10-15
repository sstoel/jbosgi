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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Hashtable;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.Name;
import javax.naming.spi.ObjectFactory;

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
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;

/**
 * URL context factories registered as OSGi services (ObjectFactory with osgi.jndi.url.scheme) are not tracked
 * correctly by the WildFly naming integration: the last registered factory wins regardless of service.ranking,
 * unregistering it drops the scheme although another factory for it is still registered, and a change of the
 * osgi.jndi.url.scheme property is ignored, so the factory stays installed under the old scheme even after it was
 * unregistered (lookups keep calling into an unregistered service).
 *
 * <h2>Severity</h2>
 * major. URL scheme providers (Aries JNDI's osgi: scheme, application-specific schemes) come and go with their bundles.
 * After an update or restart of one of two providers of a scheme, JNDI lookups of that scheme fail with
 * NameNotFoundException; a provider that changes its properties stays reachable after it is unregistered, and WildFly's
 * static InitialContext map keeps the factory object and the class loader of its (possibly uninstalled) bundle.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/integration/naming: {@code org.jboss.as.osgi.naming.JNDIServiceListener}; WildFly naming
 * {@code org.jboss.as.naming.InitialContext.addUrlContextFactory/removeUrlContextFactory} (one factory per scheme;
 * removal throws IllegalArgumentException for a factory that is not the current one).
 *
 * <h2>Found by</h2>
 * wildfly-ee workstream (deep 2), exploration of the naming integration (URL context factory services of bundles) on the
 * patched WildFly 41.0.1 server (JDK 25). server.log: {@code WARN [org.jboss.osgi.framework] JBOSGI011018: Error while
 * firing service event UNREGISTERING for: ServiceState{objectClass=[javax.naming.spi.ObjectFactory], ...,
 * osgi.jndi.url.scheme=..., service.ranking=10}: java.lang.IllegalArgumentException}.
 *
 * <h2>Spec reference</h2>
 * OSGi Enterprise R7, 126.5.5 URL Context Provider: "A URL Context Factory must be registered as an Object Factory
 * service with the following service property: osgi.jndi.url.scheme ... When an initial Context ... is given a URL
 * based operation, it searches in the service registry for an Object Factory service that is published with the URL
 * scheme property that matches the scheme used from the lookup request." Core R8 BundleContext.getServiceReference(Class)
 * javadoc (see also 5.2.6 Service Ranking Order): "If multiple such services exist, the service with the highest
 * ranking (as specified in its Constants.SERVICE_RANKING property) is returned. If there is a tie in ranking, the
 * service with the lowest service id (as specified in its Constants.SERVICE_ID property); that is, the service that was
 * registered first is returned." Core R8 5.11 Unregistering Services: "Any ServiceReference object for this
 * ServiceRegistration object can no longer be used to access the service object." Core R8 5.6.1 Service Event Types:
 * "MODIFIED - The properties of a service have been modified." A tracker of URL Context Factories must therefore drop
 * unregistered factories and re-evaluate the scheme property on MODIFIED.
 * https://docs.osgi.org/specification/osgi.enterprise/7.0.0/service.jndi.html
 *
 * <h2>Code location</h2>
 * wildfly/integration/naming/src/main/java/org/jboss/as/osgi/naming/JNDIServiceListener.java:66-91
 * ({@code serviceChanged} only handles REGISTERED (addUrlContextFactory: put, the last one wins) and UNREGISTERING
 * (removeUrlContextFactory for the scheme of the unregistering service; MODIFIED ignored, no lookup of another factory
 * for the scheme, exception when the unregistering factory is not the installed one); the factories are gotten with
 * {@code getService} and never released.
 *
 * <h2>Root cause</h2>
 * The listener mirrors service events into WildFly's single scheme-to-factory map instead of tracking the registry: the
 * map entry for a scheme is not recomputed from the matching services (ranking, remaining services, current properties).
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=UrlContextFactoryTrackingIssueTestCase \
 *     -Darq.container.jboss.configuration.managementPort=11090 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=1100
 * </pre>
 * (Prerequisites: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork in the local Maven
 * repository; on JDK 24+ the testsuite needs surefire.security.args without -Djava.security.manager, see
 * TestJvmSecurityManagerIssueTestCase.) Deterministic. Each test uses its own scheme.
 *
 * <h2>Observed</h2>
 * <pre>
 * testHighestRankingFactoryIsUsed: org.junit.ComparisonFailure: With two factories for the scheme the one with the
 *   highest service.ranking must be used expected:&lt;[high]:rankscheme:x&gt; but was:&lt;[low]:rankscheme:x&gt;
 * testRemainingFactoryIsUsedAfterUnregister: org.junit.ComparisonFailure: After one of two factories for the scheme
 *   is unregistered the remaining one must be used expected:&lt;first:fallbackscheme:x&gt; but was:&lt;EXC
 *   javax.naming.NameNotFoundException: WFNAM00004: Name "x" is not found&gt;
 * testSchemeChangeAndUnregister: java.lang.AssertionError: [after the scheme property changed to newscheme the factory
 *   must not be used for oldscheme, but oldscheme:x returned mod:oldscheme:x, after the scheme property changed to
 *   newscheme the factory must be used for it, but newscheme:x returned EXC javax.naming.NameNotFoundException:
 *   WFNAM00004: Name "x" is not found, after the factory was unregistered it must not be used any more, but oldscheme:x
 *   returned mod:oldscheme:x, ]
 * </pre>
 *
 * <h2>Expected</h2>
 * For every scheme the highest ranked registered ObjectFactory service with that osgi.jndi.url.scheme is used; when it
 * goes away the next one takes over; property changes move the factory to the new scheme; an unregistered factory is
 * never called again.
 *
 * <h2>Reference behaviour</h2>
 * Not run on Equinox/Felix (there Aries JNDI resolves URL context factories itself from the service registry instead
 * of this WildFly bridge).
 *
 * <h2>Suggested fix (not applied)</h2>
 * Register one delegating ObjectFactory per scheme with WildFly's InitialContext that resolves the best matching
 * ObjectFactory service (filter {@code (&(objectClass=javax.naming.spi.ObjectFactory)(osgi.jndi.url.scheme=<scheme>))})
 * at lookup time (best combined with ee/03: through the caller's BundleContext), and remove the delegate only when no
 * such service is left; handle MODIFIED.
 *
 * <h2>Regression vs WF41</h2>
 * no (JNDIServiceListener unchanged since WF41; pre-existing).
 */
@RunWith(Arquillian.class)
public class UrlContextFactoryTrackingIssueTestCase {

    @ArquillianResource
    Bundle bundle;

    // A URL context factory whose contexts answer every lookup with "<marker>:<name>"
    public static class MarkerFactory implements ObjectFactory {
        private final String marker;

        public MarkerFactory(String marker) {
            this.marker = marker;
        }

        @Override
        public Object getObjectInstance(Object obj, Name name, Context nameCtx, Hashtable<?, ?> environment) {
            return Proxy.newProxyInstance(Context.class.getClassLoader(), new Class<?>[] { Context.class }, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if (method.getName().equals("lookup"))
                        return marker + ":" + args[0];
                    if (method.getName().equals("close"))
                        return null;
                    if (method.getName().equals("toString"))
                        return "context of " + marker;
                    throw new UnsupportedOperationException(method.getName());
                }
            });
        }
    }

    @Deployment
    public static JavaArchive testDeployment() {
        final JavaArchive jar = ShrinkWrap.create(JavaArchive.class, "url-context-factory-tests");
        jar.addClasses(MarkerFactory.class);
        jar.addClass(MarkerFactory.class.getName() + "$1");
        jar.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(jar.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages("javax.naming", "javax.naming.spi");
                return builder.openStream();
            }
        });
        return jar;
    }

    private BundleContext context() throws Exception {
        if (bundle.getState() != Bundle.ACTIVE)
            bundle.start();
        return bundle.getBundleContext();
    }

    private ServiceRegistration<?> register(String scheme, String marker, int ranking) throws Exception {
        Hashtable<String, Object> props = new Hashtable<String, Object>();
        props.put("osgi.jndi.url.scheme", scheme);
        props.put(Constants.SERVICE_RANKING, ranking);
        return context().registerService(ObjectFactory.class.getName(), new MarkerFactory(marker), props);
    }

    private static String lookup(String name) {
        try {
            return String.valueOf(new InitialContext().lookup(name));
        } catch (Exception ex) {
            return "EXC " + ex;
        }
    }

    private static void unregister(ServiceRegistration<?> reg) {
        try {
            reg.unregister();
        } catch (IllegalStateException ex) {
            // already unregistered
        }
    }

    @Test
    public void testHighestRankingFactoryIsUsed() throws Exception {
        ServiceRegistration<?> high = register("rankscheme", "high", 10);
        ServiceRegistration<?> low = register("rankscheme", "low", 0);
        try {
            Assert.assertEquals("With two factories for the scheme the one with the highest service.ranking must be used", "high:rankscheme:x", lookup("rankscheme:x"));
        } finally {
            unregister(low);
            unregister(high);
        }
    }

    @Test
    public void testRemainingFactoryIsUsedAfterUnregister() throws Exception {
        ServiceRegistration<?> first = register("fallbackscheme", "first", 0);
        ServiceRegistration<?> second = register("fallbackscheme", "second", 0);
        try {
            Assert.assertEquals("Precondition: a lookup of the scheme works", true, lookup("fallbackscheme:x").startsWith("first:") || lookup("fallbackscheme:x").startsWith("second:"));
            second.unregister();
            Assert.assertEquals("After one of two factories for the scheme is unregistered the remaining one must be used", "first:fallbackscheme:x", lookup("fallbackscheme:x"));
        } finally {
            unregister(second);
            unregister(first);
        }
    }

    @Test
    public void testSchemeChangeAndUnregister() throws Exception {
        ServiceRegistration<?> reg = register("oldscheme", "mod", 0);
        try {
            Assert.assertEquals("Precondition: the factory is used for its scheme", "mod:oldscheme:x", lookup("oldscheme:x"));
            Hashtable<String, Object> props = new Hashtable<String, Object>();
            props.put("osgi.jndi.url.scheme", "newscheme");
            reg.setProperties(props);

            StringBuilder failures = new StringBuilder();
            String old = lookup("oldscheme:x");
            if (old.startsWith("mod:"))
                failures.append("after the scheme property changed to newscheme the factory must not be used for oldscheme, but oldscheme:x returned ").append(old).append(", ");
            String now = lookup("newscheme:x");
            if (!now.equals("mod:newscheme:x"))
                failures.append("after the scheme property changed to newscheme the factory must be used for it, but newscheme:x returned ").append(now).append(", ");
            reg.unregister();
            for (String name : new String[] { "oldscheme:x", "newscheme:x" }) {
                String result = lookup(name);
                if (result.startsWith("mod:"))
                    failures.append("after the factory was unregistered it must not be used any more, but ").append(name).append(" returned ").append(result).append(", ");
            }
            Assert.assertTrue("[" + failures + "]", failures.length() == 0);
        } finally {
            unregister(reg);
        }
    }
}
