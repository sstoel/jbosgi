/*
 * #%L
 * JBossOSGi WildFly Integration
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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * After an in-place upgrade from WF41 to WF41-osgi-r8 that keeps the server configuration, the OSGi subsystem does not
 * start: the WF41 standalone-osgi.xml lists the capability org.apache.felix.log, whose module r8 removed.
 *
 * <h2>Severity</h2>
 * critical (in-place upgrade of a production server that keeps its standalone-osgi.xml, or any customised config
 * derived from the WF41 one: BootstrapBundles.INSTALL fails, the OSGi framework never starts, every OSGi deployment
 * is dead; the server reports "started (with errors)").
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/build (shipped configuration and bundles/modules), wildfly/subsystem
 * BootstrapBundlesIntegration (capability bootstrap).
 *
 * <h2>Found by</h2>
 * Tests workstream, round 4 upgrade compatibility: WF41 server (umbrella origin/WF41 77f95be6, built by the WildFly
 * workstream) with deployed bundles, then the r8 server (f025b420) started on the same standalone/ directory with port
 * offset 500 on JDK 25.
 *
 * <h2>Spec reference</h2>
 * Not an OSGi spec issue: upgrade compatibility of the server configuration. The OSGi subsystem configuration
 * (urn:jboss:domain:osgi:1.2, unchanged namespace) is operator-owned; a newer subsystem must accept a configuration
 * written for the previous release, or migrate it.
 *
 * <h2>Code location</h2>
 * Commit 80805736 "Build against OSGi Core R8, remove the Felix Log Service" removed the bundle
 * bundles/org/apache/felix/log/main and changed wildfly/build/src/main/resources/wildfly/standalone/configuration/
 * standalone-osgi.xml (-capability org.apache.felix.log, +org.glassfish.hk2). The subsystem has no migration or
 * tolerance for a capability whose module is gone:
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/BootstrapBundlesIntegration.java:128-152 throws
 * startFailedToProcessInitialCapability for the first unresolvable capability.
 *
 * <h2>Root cause</h2>
 * A capability that was valid in WF41 can no longer be resolved, and a single unresolvable capability fails the whole
 * OSGi bootstrap.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl wildfly/build test -Dtest=Wf41ConfigCapabilityRemovedIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository; -Dmaven.compiler.proc=full works around tests/01.)
 * <h2>Observed</h2>
 * <pre>
 * Expected every capability of the WF41 standalone-osgi.xml to resolve in the r8 server, but these do not:
 *   [org.apache.felix.log]
 * </pre>
 *
 * <h2>Expected</h2>
 * A WF41 configuration keeps working after the upgrade: either the capability still resolves, or the subsystem
 * skips (with a warning) a capability that a release removed, or a documented migration step exists.
 *
 * <h2>Reference behaviour</h2>
 * The same standalone/ directory with the r8 standalone-osgi.xml boots cleanly (bundles restored, data files kept).
 *
 * <h2>Suggested fix</h2>
 * Treat org.apache.felix.log as a deprecated capability in BootstrapBundlesIntegration (skip with a WARN, the
 * framework provides the Log Service now), or keep an empty/alias bundle for it, and document the config change in
 * the upgrade notes. Related upgrade-procedure note (not part of this test): unzipping the r8 patch over a WF41
 * installation leaves the old jars next to the new ones (e.g. bundles/org/jboss/osgi/logging/main has
 * jboss-osgi-logging-4.6.0.Final.jar and -4.8.0.Final.jar), and ModuleIdentityRepositoryIntegration.getSingleJarEntry
 * then ignores the directory ("Multiple jars"), which fails the bootstrap the same way for org.jboss.osgi.logging.
 *
 * <h2>Regression vs WF41</h2>
 * yes (upgrade path; a WF41 server boots with this configuration).
 *
 * <h2>Resolution</h2>
 * Documented migration step (README, "Upgrading a server from the WF41 build"): the capability org.apache.felix.log
 * is removed from a WF41 configuration, the framework provides the Log Service. It is not in the capability list of
 * this test any more; the test guards that every other capability of the WF41 configuration still resolves.
 */
public class Wf41ConfigCapabilityRemovedIssueTestCase {

    private static File getServerHome() {
        File[] dirs = new File("target").listFiles((dir, name) -> name.startsWith("wildfly-") && new File(dir, name + "/bin/standalone.sh").isFile());
        assertNotNull("The server built by this module under target/wildfly-*", dirs);
        assertTrue("The server built by this module under target/wildfly-*", dirs.length == 1);
        return dirs[0];
    }

    private static boolean resolves(File home, String capability) {
        String path = capability.replace('.', '/');
        String slot = "main";
        int index = capability.indexOf(':');
        if (index > 0) {
            path = capability.substring(0, index).replace('.', '/');
            slot = capability.substring(index + 1);
        }
        File bundleDir = new File(home, "bundles/" + path + "/" + slot);
        String[] jars = bundleDir.list((dir, name) -> name.endsWith(".jar"));
        if (jars != null && jars.length == 1)
            return true;
        return new File(home, "modules/system/layers/base/" + path + "/" + slot + "/module.xml").isFile();
    }

    @Test
    public void testWf41CapabilitiesResolve() throws Exception {
        File home = getServerHome();
        List<String> missing = new ArrayList<String>();
        InputStream input = getClass().getResourceAsStream("/issues/wf41-config-capability-removed/wf41-capabilities.txt");
        assertNotNull("WF41 capability list", input);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                line = line.trim();
                if (line.length() > 0 && resolves(home, line) == false)
                    missing.add(line);
            }
        }
        assertTrue("Expected every capability of the WF41 standalone-osgi.xml (without the removed org.apache.felix.log) to resolve in the r8 server, but these do not: " + missing, missing.isEmpty());
    }
}
