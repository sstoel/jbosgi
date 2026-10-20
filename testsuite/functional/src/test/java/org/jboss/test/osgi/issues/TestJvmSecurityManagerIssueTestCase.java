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

import org.junit.Assert;
import org.junit.Test;

/**
 * The testsuite modules example and functional cannot run on Java 25: their surefire argLine enables the Java
 * Security Manager, and a JDK 24+ JVM refuses to start with it.
 *
 * <h2>Severity</h2>
 * major (test infrastructure). With the committed poms, {@code mvn install} of the umbrella on JDK 25 stops at
 * "JBossOSGi Testsuite Examples" with a surefire fork error; none of the example and functional tests run, and the
 * integration module is never reached. The user's target JDK is 25 (WildFly 41 supports 21 and 25).
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), testsuite/pom.xml property {@code surefire.security.args}, used in the surefire argLine of
 * testsuite/example/pom.xml (profiles embedded-testing and wildfly-testing) and testsuite/functional/pom.xml
 * (wildfly-testing).
 *
 * <h2>Found by</h2>
 * wildfly workstream, running the umbrella build with its testsuite on JDK 25 (user's Java 25 policy).
 *
 * <h2>Spec reference</h2>
 * Not an OSGi statement. JEP 486 (JDK 24, "Permanently Disable the Security Manager"): "Enabling a Security Manager at
 * startup is not supported. ... the java launcher will refuse to start". https://openjdk.org/jeps/486
 *
 * <h2>Code location</h2>
 * testsuite/pom.xml:36
 * {@code <surefire.security.args>-Djava.security.manager "-Djava.security.policy=${basedir}/src/test/resources/tst.policy"</surefire.security.args>}
 * (since 4c44a3ae, 2012); testsuite/example/pom.xml:233 and :274, testsuite/functional/pom.xml:168
 * {@code <argLine>@{jacocoArgLine} @{surefire.system.args} @{surefire.security.args}</argLine>}.
 * The policy file grants AllPermission, so the Security Manager adds nothing on JDK 21 either.
 *
 * <h2>Root cause</h2>
 * {@code -Djava.security.manager} on the command line of the forked test JVM. Overriding the property on the Maven
 * command line ({@code -Dsurefire.security.args=...}) does not help: the {@code @{...}} late replacement in the argLine
 * still takes the value of the pom property.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/functional test -Dtest=TestJvmSecurityManagerIssueTestCase
 * </pre>
 * (Run with the committed testsuite/pom.xml: the failure is the forked JVM refusing the Security Manager, see Observed. Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz in the local repository.)
 * <h2>Observed</h2>
 * <pre>
 * [ERROR] Error occurred during initialization of VM
 * [ERROR] java.lang.Error: A command line option has attempted to allow or enable the Security Manager. Enabling a Security Manager is not supported.
 * [ERROR] The forked VM terminated without properly saying goodbye. VM crash or System.exit called?
 * [ERROR] Command was /bin/sh -c cd '.../testsuite/functional' &amp;&amp; '.../openjdk@25/.../bin/java' '-javaagent:...jacoco...' '-ea' '-Xmx512m' '-Djava.security.manager' '-Djava.security.policy=.../tst.policy' '-jar' ...
 * </pre>
 *
 * <h2>Expected</h2>
 * The forked JVM starts on JDK 25 and the test runs (it passes on any JDK where the Security Manager is not requested
 * on JDK 24+).
 *
 * <h2>Reference behaviour</h2>
 * Not applicable.
 *
 * <h2>Suggested fix (not applied, pom change)</h2>
 * Drop {@code surefire.security.args} from the argLines (the policy grants AllPermission anyway), or set it to empty in
 * a profile activated by {@code <jdk>[24,)</jdk>}.
 *
 * <h2>Regression vs WF41</h2>
 * no (same poms on origin/WF41); it only shows on JDK 24+.
 */
public class TestJvmSecurityManagerIssueTestCase {

    @Test
    public void testForkedJvmRunsWithoutSecurityManagerOnJdk24Plus() {
        int feature = Runtime.version().feature();
        String sm = System.getProperty("java.security.manager");
        Assert.assertTrue("Expected the test JVM on Java " + feature + " not to request the Security Manager (JEP 486), but java.security.manager="
                + sm, feature < 24 || sm == null);
    }
}
