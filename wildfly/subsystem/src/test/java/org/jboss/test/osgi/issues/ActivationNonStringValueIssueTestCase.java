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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.jboss.as.osgi.parser.OSGiExtension;
import org.jboss.as.subsystem.test.AbstractSubsystemTest;
import org.jboss.as.subsystem.test.AdditionalInitialization;
import org.jboss.as.subsystem.test.KernelServices;
import org.jboss.dmr.ModelNode;
import org.junit.Test;

/**
 * The activation attribute of the osgi subsystem accepts any non-string value ({@code write-attribute(name=activation,
 * value=true)}, value=1): the enum check is skipped, the value is stored and persisted as activation="true", and the
 * server can not boot from the persisted configuration any more.
 *
 * <h2>Severity</h2>
 * major (one accepted management operation leaves a standalone.xml that the next :reload/restart rejects:
 * "WFLYSRV0056: Server boot has failed in an unrecoverable manner; exiting"; the file must be repaired by hand).
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem, org.jboss.as.osgi.parser.OSGiRootResource.ACTIVATION (validator),
 * ActivationAttributeHandler / OSGiSubsystemAdd.
 *
 * <h2>Found by</h2>
 * fuzz-server (fuzz round 2): management operation fuzzer DmrFuzz seed 6101 (1500 random operations on
 * /subsystem=osgi), flagged "SUCCESS root:write-attribute activation=true"; confirmed on the patched server
 * (JDK 25, offset 1300) by write-attribute(activation=true) followed by :reload.
 *
 * <h2>Spec reference</h2>
 * Not an OSGi specification issue. The attribute is described with "allowed" =&gt; ["eager", "lazy"]
 * (read-resource-description); a value outside the allowed set must be rejected, and a value the model accepts must
 * be persisted so that the next boot accepts it. The subsystem's own XML parser rejects activation="true"
 * (WFLYCTL0248).
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/parser/OSGiRootResource.java:58-63
 * {@code .setValidator(new EnumValidator<>(Activation.class, EnumSet.allOf(Activation.class)))}: the deprecated
 * EnumValidator constructor (wildfly-controller 33 EnumValidator.validateParameter) only checks values of
 * ModelType.STRING; BOOLEAN/INT/LONG/... values pass the ModelTypeValidator conversion check and are stored as is.
 * Pre-existing.
 *
 * <h2>Root cause</h2>
 * The validator of ACTIVATION does not reject non-string nodes; OSGiSubsystemWriter then marshals the stored value
 * ("true") which the parser's validation (a STRING node) rejects.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl wildfly/subsystem test -Dtest=ActivationNonStringValueIssueTestCase
 * </pre>
 * Real server (CLI): {@code /subsystem=osgi:write-attribute(name=activation, value=true)} then {@code :reload}.
 *
 * <h2>Observed</h2>
 * <pre>
 * write-attribute activation=true (BOOLEAN) was accepted, but the persisted configuration
 *   &lt;subsystem xmlns="urn:jboss:domain:osgi:1.2" activation="true"/&gt; can not be parsed again:
 *   ParseError at [row,col]:[2,65]
 * (read-attribute returns "true" after the write)
 * </pre>
 * Real server after :reload:
 * <pre>
 * OPVDX001: Validation error in standalone-osgi.xml
 *  448: &lt;subsystem xmlns="urn:jboss:domain:osgi:1.2" activation="true"&gt;
 *       ^^^^ Invalid value true for activation; legal values are [eager, lazy]
 * FATAL [org.jboss.as.server] WFLYSRV0056: Server boot has failed in an unrecoverable manner; exiting.
 * </pre>
 * The string "true" is rejected by the same operation: "WFLYCTL0248: Invalid value true for activation; legal values
 * are [eager, lazy]".
 *
 * <h2>Expected</h2>
 * write-attribute (and add) with activation true / 1 fails with WFLYCTL0248 and nothing is persisted.
 *
 * <h2>Reference behaviour</h2>
 * WildFly core enum-like attributes reject the same input, e.g.
 * {@code /subsystem=logging/console-handler=CONSOLE:write-attribute(name=target, value=true)} -&gt; "WFLYCTL0248: Invalid
 * value true for target; legal values are [console, System.err, System.out]".
 *
 * <h2>Suggested fix</h2>
 * Replace the deprecated EnumValidator (deprecated for removal since WildFly Core 31) by a validator that checks
 * every value type, e.g. {@code new StringAllowedValuesValidator("eager", "lazy")}; with that change this test,
 * OSGiSubsystemTestCase and ActivationWriteHandlerTestCase pass (trial in a scratch worktree).
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing).
 */
public class ActivationNonStringValueIssueTestCase extends AbstractSubsystemTest {

    public ActivationNonStringValueIssueTestCase() {
        super(OSGiExtension.SUBSYSTEM_NAME, new OSGiExtension());
    }

    @Test
    public void testNonStringActivationRejected() throws Exception {
        ModelNode[] values = {new ModelNode(true), new ModelNode(1)};
        for (ModelNode value : values) {
            KernelServices services = createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT)
                    .setSubsystemXml("<subsystem xmlns='urn:jboss:domain:osgi:1.2' activation='lazy'/>").build();
            try {
                ModelNode op = new ModelNode();
                op.get("operation").set("write-attribute");
                op.get("address").add("subsystem", "osgi");
                op.get("name").set("activation");
                op.get("value").set(value);
                ModelNode result = services.executeOperation(op);
                if ("success".equals(result.get("outcome").asString())) {
                    String persisted = services.getPersistedSubsystemXml();
                    try {
                        createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT).setSubsystemXml(persisted).build().shutdown();
                    } catch (Exception ex) {
                        fail("write-attribute activation=" + value + " (" + value.getType() + ") was accepted, but the persisted configuration "
                                + persisted.trim() + " can not be parsed again: " + ex.getMessage());
                    }
                }
                assertEquals("write-attribute activation=" + value + " (" + value.getType() + ")", "failed", result.get("outcome").asString());
            } finally {
                services.shutdown();
            }
        }
    }
}
