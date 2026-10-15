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
package org.jboss.test.osgi.issues;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import org.junit.Test;

/**
 * A Maven capability in the osgi subsystem configuration (&lt;capability name="groupId:artifactId:version"/&gt;) never works:
 * BootstrapBundlesIntegration classifies Maven coordinates as a jboss-modules module identifier, takes the module branch,
 * and the whole osgi subsystem fails to start with JBAS011955 / ModuleNotFoundException; the Maven branch is unreachable.
 *
 * <h2>Severity</h2>
 * major. An intended configuration form (the subsystem resolves "initial capabilities" from modules, the bundles
 * directory or Maven; the code has an explicit Maven branch, see the Maven branch in BootstrapBundlesIntegration.getInitialBundleDeployment and the
 * MavenIdentityRepository delegate wired by RepositoryService) breaks the OSGi container at boot: in the patched server
 * (WildFly 41.0.1 + WF41-osgi-r8, JDK 25) the server "started (with errors)", the osgi subsystem add failed and no bundle runs.
 *
 * <h2>Component</h2>
 * jbosgi umbrella, module wildfly/subsystem, class org.jboss.as.osgi.service.BootstrapBundlesIntegration (start,
 * isValidModuleIdentifier, getInitialBundleDeployment).
 *
 * <h2>Found by</h2>
 * subrepos-b workstream, subsystem paths that provision capabilities at boot; reproduced in the patched server.
 *
 * <h2>Spec reference</h2>
 * Not an OSGi spec clause. jboss-modules 2.1.6 ModuleIdentifier.fromString(String): "Parse a module specification from a
 * string" in the form name[:slot] - everything after the first ':' is the slot, so "g:a:v" parses as name "g", slot "a:v"
 * and is never rejected. The subsystem's own code treats Maven coordinates as a separate capability kind
 * (isValidMavenIdentifier / MavenCoordinates.parse).
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/BootstrapBundlesIntegration.java (WF41-osgi-r8 f025b420),
 * unchanged since 2013 (pre-existing):
 * <ul>
 * <li>:133 start(): if (isValidModuleIdentifier(identifier)) -&gt; installInitialModuleCapability -&gt;
 * ModuleIdentityRepositoryIntegration.findProviders -&gt; ModuleNotFoundException -&gt; :140-141 startFailedToProcessInitialCapability</li>
 * <li>:209 / :222 getInitialBundleDeployment(): the Maven branch is an else-if of the module branch, so it is dead for
 * every Maven coordinate</li>
 * <li>:251-257 isValidModuleIdentifier only checks that ModuleIdentifier.fromString does not throw</li>
 * </ul>
 *
 * <h2>Root cause</h2>
 * The module-identifier check accepts any string containing ':'; Maven coordinates (two or more ':') are tested as module
 * identifiers first.
 *
 * <h2>Reproduction</h2>
 * Unit level (this test, JDK 25, umbrella repository):
 * <pre>
 * cd jbosgi
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full -pl wildfly/subsystem -am install -DskipTests
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full -pl wildfly/subsystem test -Dtest=MavenCapabilityTreatedAsModuleIssueTestCase
 * </pre>
 * Server level: in standalone-osgi.xml add
 * {@code <capability name="org.apache.felix:org.apache.felix.eventadmin:1.6.4" startlevel="1"/>} to the osgi
 * subsystem capabilities (the artifact is on Maven Central, reachable) and start
 * {@code bin/standalone.sh -c standalone-osgi.xml} on JDK 25.
 *
 * <h2>Observed</h2>
 * Test:
 * <pre>
 * testMavenCoordinatesReachTheMavenBranch:41 Expected the capability org.apache.felix:org.apache.felix.eventadmin:1.6.4
 *   (Maven coordinates) to be installed through the Maven repository, but BootstrapBundlesIntegration classifies it as a
 *   module identifier (module 'org.apache.felix', slot 'org.apache.felix.eventadmin:1.6.4'), so start() takes the module
 *   branch, never reaches the Maven branch, and fails the osgi subsystem with JBAS011955 (ModuleNotFoundException: org.apache.felix)
 * </pre>
 * Server (patched WildFly 41.0.1.Final + r8, JDK 25):
 * <pre>
 * ERROR [org.jboss.msc.service.fail] MSC000001: Failed to start service jbosgi.BootstrapBundles.INSTALL: ... JBAS011955:
 *   Failed to process initial capability: org.apache.felix:org.apache.felix.eventadmin:1.6.4
 *   at org.jboss.as.osgi.service.BootstrapBundlesIntegration.start(BootstrapBundlesIntegration.java:141)
 * Caused by: org.jboss.osgi.repository.RepositoryResolutionException: java.lang.IllegalArgumentException: JBOSGI020521:
 *   Cannot obtain module resource: org.apache.felix
 *   at org.jboss.as.osgi.service.ModuleIdentityRepositoryIntegration.findProviders(ModuleIdentityRepositoryIntegration.java:114)
 *   at org.jboss.as.osgi.service.BootstrapBundlesIntegration.installInitialModuleCapability(BootstrapBundlesIntegration.java:181)
 * Caused by: org.jboss.modules.ModuleNotFoundException: org.apache.felix
 * ERROR [org.jboss.as.controller.management-operation] WFLYCTL0013: Operation ("add") failed - address: ([("subsystem" =&gt; "osgi")])
 * ERROR [org.jboss.as] WFLYSRV0026: WildFly 41.0.1.Final (WildFly Core 33.0.1.Final) started (with errors) ...
 * </pre>
 * No Maven repository was contacted (no "Find maven providers" message).
 *
 * <h2>Expected</h2>
 * Maven coordinates are resolved through the MavenIdentityRepository and the bundle is installed (or, if the artifact
 * cannot be found, that one capability fails with a clear message).
 *
 * <h2>Reference behaviour</h2>
 * n/a (WildFly integration).
 *
 * <h2>Suggested fix</h2>
 * In isValidModuleIdentifier return false when the identifier is valid Maven coordinates (MavenCoordinates.parse succeeds,
 * i.e. it has two or more ':'), or test isValidMavenIdentifier first in both start() and getInitialBundleDeployment()
 * (then adjust this test, which pins the classification). Note that once the Maven branch is reachable, the bundle is
 * deployed from the remote content URL on every boot (no use of the persistent repository), so a boot without network
 * fails that capability (and see subrepos/54 for a stalled network).
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing; same code and the same jboss-modules parsing on WF41).
 */
public class MavenCapabilityTreatedAsModuleIssueTestCase {

    private static final String MAVEN_CAPABILITY = "org.apache.felix:org.apache.felix.eventadmin:1.6.4";

    @Test
    public void testMavenCoordinatesReachTheMavenBranch() throws Exception {
        Class<?> clazz = Class.forName("org.jboss.as.osgi.service.BootstrapBundlesIntegration");
        Constructor<?> ctor = clazz.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object integration = ctor.newInstance();

        boolean maven = invoke(clazz, integration, "isValidMavenIdentifier", MAVEN_CAPABILITY);
        boolean module = invoke(clazz, integration, "isValidModuleIdentifier", MAVEN_CAPABILITY);

        assertTrue("Precondition: " + MAVEN_CAPABILITY + " are valid Maven coordinates", maven);
        assertFalse("Expected the capability " + MAVEN_CAPABILITY + " (Maven coordinates) to be installed through the Maven "
                + "repository, but BootstrapBundlesIntegration classifies it as a module identifier (module 'org.apache.felix', slot "
                + "'org.apache.felix.eventadmin:1.6.4'), so start() takes the module branch, never reaches the Maven branch, and fails "
                + "the osgi subsystem with JBAS011955 (ModuleNotFoundException: org.apache.felix)", module);
    }

    private static boolean invoke(Class<?> clazz, Object target, String name, String arg) throws Exception {
        Method method = clazz.getDeclaredMethod(name, String.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(target, arg);
    }
}
