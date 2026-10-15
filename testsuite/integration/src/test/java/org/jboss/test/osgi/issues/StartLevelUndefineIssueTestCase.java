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
 * {@code /subsystem=osgi:undefine-attribute(name=startlevel)} (and write-attribute without a value) fails with an
 * unchecked NoSuchElementException ("WFLYCTL0158: Operation handler failed: java.util.NoSuchElementException: No
 * child 'value' exists") and an ERROR with stack trace in server.log, although the attribute is described as
 * nillable.
 *
 * <h2>Severity</h2>
 * cosmetic (nothing changes and the operation is rolled back; the failure description is an internal exception
 * instead of a usage error, and every such call logs an ERROR stack trace).
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem, org.jboss.as.osgi.management.StartLevelHandler.WRITE_HANDLER and
 * org.jboss.as.osgi.parser.OSGiRootResource.STARTLEVEL.
 *
 * <h2>Found by</h2>
 * fuzz-server (fuzz round 2), management operation fuzzer DmrFuzz seed 6101 (1500 random operations on
 * /subsystem=osgi): the only failure descriptions carrying an unchecked handler exception from osgi code
 * (verdict "UNCHECKED-IN-FAILURE WFLYCTL0158", ops #160 undefine-attribute and #369 write-attribute without value).
 *
 * <h2>Spec reference</h2>
 * Not an OSGi specification issue. WildFly management contract: undefine-attribute is valid for an attribute whose
 * description says "nillable" =&gt; true; handlers report invalid requests with OperationFailedException (a clean
 * failure description), WFLYCTL0158 marks a handler bug.
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/management/StartLevelHandler.java:55
 * {@code ModelNode value = operation.require(ModelDescriptionConstants.VALUE);} - undefine-attribute and a
 * write-attribute without value carry no "value" parameter; OSGiRootResource.java:64 declares STARTLEVEL optional
 * ({@code new SimpleAttributeDefinitionBuilder(ModelConstants.STARTLEVEL, ModelType.INT, true)}). Pre-existing.
 *
 * <h2>Root cause</h2>
 * The write handler assumes a defined value; the attribute definition allows undefined.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl testsuite/integration test -Dtest=StartLevelUndefineIssueTestCase \
 *     -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500
 * </pre>
 * (patched wildfly-dist tar.gz in the local repository, testsuite/pom.xml surefire.security.args replaced on JDK 24+,
 * see wildfly/03. The two -Darq... options start the managed server with port offset 500 (any free offset), so the
 * test cannot connect to another server on 9990: the stock arquillian.xml has allowConnectingToRunningServer=true.)
 *
 * <h2>Observed</h2>
 * <pre>
 * undefine-attribute startlevel must fail with a usage error, not an internal exception; failure description was
 *   WFLYCTL0158: Operation handler failed: java.util.NoSuchElementException: No child 'value' exists
 * </pre>
 * server.log:
 * <pre>
 * ERROR [org.jboss.as.controller.management-operation] WFLYCTL0013: Operation ("undefine-attribute") failed -
 *   address: ([("subsystem" =&gt; "osgi")]) - Stage: (MODEL): java.util.NoSuchElementException: No child 'value' exists
 *   at org.jboss.dmr.ModelNode.require(ModelNode.java:1037)
 *   at org.jboss.as.osgi.management.StartLevelHandler$2.invokeOperation(StartLevelHandler.java:55)
 * </pre>
 *
 * <h2>Expected</h2>
 * A clean failure such as "startlevel can not be undefined" (OperationFailedException), or the attribute described
 * as required (nillable false) so that the controller rejects the request itself.
 *
 * <h2>Reference behaviour</h2>
 * none (WildFly-specific).
 *
 * <h2>Suggested fix</h2>
 * In WRITE_HANDLER: {@code if (!operation.hasDefined(VALUE)) throw new OperationFailedException("...")}, or declare
 * STARTLEVEL with optional=false (it is a storage-runtime attribute that always has a value while the framework runs).
 * Related: services/28 (StartLevelWriteNotRolledBackIssueTestCase), a different defect in the same handler
 * (StartLevelHandler.WRITE_HANDLER); fix both together.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing).
 */
@RunAsClient
@RunWith(Arquillian.class)
public class StartLevelUndefineIssueTestCase {

    @ArquillianResource
    ManagementClient managementClient;

    @Deployment(testable = false)
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "startlevel-undefine");
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

    @Test
    public void testUndefineStartLevelFailsCleanly() throws Exception {
        ModelNode op = new ModelNode();
        op.get("operation").set("undefine-attribute");
        op.get("address").add("subsystem", "osgi");
        op.get("name").set("startlevel");
        ModelNode response = managementClient.getControllerClient().execute(op);
        String description = response.get("failure-description").asString();
        Assert.assertFalse("undefine-attribute startlevel must fail with a usage error, not an internal exception; failure description was " + description,
                description.contains("WFLYCTL0158") || description.contains("NoSuchElementException"));
    }
}
