/*
 * #%L
 * JBossOSGi Testsuite
 * %%
 * Copyright (C) 2026 RTR
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 * #L%
 */
package org.jboss.test.osgi.issues;

import java.io.InputStream;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.dmr.ModelNode;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The /subsystem=osgi/bundle=* resources are named by bundle location, but a name is resolved as a bundle id first:
 * a bundle whose location is a number is listed under that number, and every read, start and stop under that name
 * acts on the bundle that has this number as its id.
 *
 * <h2>Severity</h2>
 * minor (management acts on the wrong bundle: on the patched server {@code /subsystem=osgi/bundle=3:stop}, where "3"
 * is the name listed for a bundle deployed as "3", stopped jakarta.mail.api (bundle id 3) and left the deployed bundle
 * ACTIVE; the deployed bundle can not be reached through the management model at all. Needs a numeric location, e.g.
 * a deployment named "3" or a bundle installed through BundleContext/JMX with location "42").
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem, org.jboss.as.osgi.management.OSGiRuntimeResource (child names, hasBundle) and
 * BundleResourceHandler (getTargetBundle).
 *
 * <h2>Found by</h2>
 * fuzz-server (fuzz round 2), management operation fuzzing with numeric, negative and huge bundle names
 * (seed 6101), then reproduced by hand on the patched server (JDK 25, offset 1300): deployment "3" (bundle
 * fuzz.simple.a) -&gt; read-children-names lists "3" -&gt; bundle=3 reads id 3 / jakarta.mail.api, bundle=3:stop stops
 * jakarta.mail.api.
 *
 * <h2>Spec reference</h2>
 * Not an OSGi specification issue. WildFly management model contract: the names returned by read-children-names
 * address exactly those children; read-resource of a listed child describes that child. Core R8 4.4.1 (Bundle
 * Identifiers): "Bundle location - A name assigned by the management agent (Operator) to a bundle during the
 * installation. This string is normally interpreted as a URL to the JAR file but this is not mandatory." The location
 * is an arbitrary string, the bundle id is a separate unique long: neither is restricted so as not to collide with the
 * other.
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/management/OSGiRuntimeResource.java:224 (child names =
 * {@code b.getLocation()}) versus :209-212 and BundleResourceHandler.java:124-127
 * ({@code Long.parseLong(value)} -&gt; getBundle(id), only on NumberFormatException getBundleByLocation(value)).
 * Pre-existing (code from the AS7 subsystem, e4fe0811 and earlier).
 *
 * <h2>Root cause</h2>
 * The resource name is ambiguous: it is produced from the location but interpreted as an id whenever it parses as a
 * long.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=BundleResourceNumericLocationIssueTestCase \
 *     -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500
 * </pre>
 * (patched wildfly-dist tar.gz in the local repository, testsuite/pom.xml surefire.security.args replaced on JDK 24+,
 * see wildfly/03. The two -Darq... options start the managed server with port offset 500 (any free offset), so the
 * test cannot connect to another server on 9990: the stock arquillian.xml has allowConnectingToRunningServer=true.) This test deploys a bundle as "0" (the system bundle id, so nothing is stopped by the test).
 *
 * <h2>Observed</h2>
 * <pre>
 * location of the resource listed as bundle=0 (a bundle deployed as "0") expected:&lt;[0]&gt; but
 *   was:&lt;[org.jboss.osgi.framework]&gt;
 * </pre>
 * On the server with a deployment "3": {@code /subsystem=osgi/bundle=3:stop} -&gt; success, jakarta.mail.api RESOLVED,
 * fuzz.simple.a still ACTIVE.
 *
 * <h2>Expected</h2>
 * Every listed child name addresses the bundle it was listed for (location lookup first, or unambiguous names such as
 * the bundle id for every bundle).
 *
 * <h2>Reference behaviour</h2>
 * none (WildFly-specific management model).
 *
 * <h2>Suggested fix</h2>
 * In OSGiRuntimeResource.hasBundle and BundleResourceHandler.getTargetBundle look the name up as a location first and
 * fall back to the id only when no bundle has that location; or name all children by id.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing).
 */
@RunAsClient
@RunWith(Arquillian.class)
public class BundleResourceNumericLocationIssueTestCase {

    private static final String DEPLOYMENT = "0";
    private static final String SYMBOLIC_NAME = "numeric-location-bundle";

    @ArquillianResource
    ManagementClient managementClient;

    @Deployment(name = DEPLOYMENT, testable = false)
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, DEPLOYMENT);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(SYMBOLIC_NAME);
                builder.addBundleManifestVersion(2);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Test
    public void testNumericLocationAddressesItsBundle() throws Exception {
        ModelNode names = new ModelNode();
        names.get("operation").set("read-children-names");
        names.get("address").add("subsystem", "osgi");
        names.get("child-type").set("bundle");
        ModelNode result = execute(names);
        Assert.assertTrue("bundle children " + result + " list the deployed bundle by its location " + DEPLOYMENT,
                result.get("result").asList().contains(new ModelNode(DEPLOYMENT)));

        Assert.assertEquals("location of the resource listed as bundle=" + DEPLOYMENT + " (a bundle deployed as \"" + DEPLOYMENT + "\")",
                DEPLOYMENT, readBundleAttribute(DEPLOYMENT, "location"));
        Assert.assertEquals("symbolic-name of the resource listed as bundle=" + DEPLOYMENT,
                SYMBOLIC_NAME, readBundleAttribute(DEPLOYMENT, "symbolic-name"));
    }

    private String readBundleAttribute(String bundle, String attribute) throws Exception {
        ModelNode op = new ModelNode();
        op.get("operation").set("read-attribute");
        op.get("address").add("subsystem", "osgi").add("bundle", bundle);
        op.get("name").set(attribute);
        ModelNode response = execute(op);
        Assert.assertEquals("read-attribute " + attribute + " of bundle=" + bundle + ": " + response, "success", response.get("outcome").asString());
        return response.get("result").asString();
    }

    private ModelNode execute(ModelNode op) throws Exception {
        return managementClient.getControllerClient().execute(op);
    }
}
