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
 * An osgi subsystem element without the activation attribute fails the server boot ("activation is required"),
 * although every osgi schema declares the attribute optional with default "lazy" and the model attribute has that
 * default.
 *
 * <h2>Severity</h2>
 * minor (a schema-valid configuration, e.g. the minimal {@code <subsystem xmlns="urn:jboss:domain:osgi:1.2"/>},
 * stops the whole server from booting; the error message does name the attribute).
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem, org.jboss.as.osgi.parser.OSGiRootResource.ACTIVATION.
 *
 * <h2>Found by</h2>
 * fuzz-server (fuzz round 2): subsystem XML fuzzer XmlFuzz seeds 6001/6002 (42 of 800 inputs without the activation
 * attribute, all rejected by the subsystem test harness with WFLYCTL0231), confirmed by a boot of the patched server
 * (JDK 25, offset 1300) with the osgi subsystem element of standalone-osgi.xml lacking activation.
 *
 * <h2>Spec reference</h2>
 * Not an OSGi specification issue. wildfly/build/src/main/resources/wildfly/docs/schema/jboss-as-osgi_1_2.xsd:47
 * {@code <xs:attribute name="activation" type="activationType" default="lazy"/>} (same in 1_0 and 1_1): the attribute
 * is optional. The subsystem's own OSGiSubsystemTestCase.testParseEmptySubsystem parses exactly this element.
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/parser/OSGiRootResource.java:58-63:
 * {@code new SimpleAttributeDefinitionBuilder(ModelConstants.ACTIVATION, ModelType.STRING, false)
 * .setDefaultValue(new ModelNode(SubsystemState.DEFAULT_ACTIVATION.toString()))} - optional=false makes the
 * attribute required; WildFly Core 33 does not store the default in the model, so model validation fails with
 * WFLYCTL0172. Pre-existing (the definition dates from the AS7 code).
 *
 * <h2>Root cause</h2>
 * A required attribute with a default value: WildFly Core treats "required" literally (the operation parameter and
 * the model value must be defined), the default value is only used when resolving.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl wildfly/subsystem test -Dtest=ActivationAttributeRequiredIssueTestCase
 * </pre>
 * Real server: remove {@code activation="eager"} from the osgi subsystem of standalone-osgi.xml and start it.
 *
 * <h2>Observed</h2>
 * <pre>
 * urn:jboss:domain:osgi:1.2: expected the subsystem without activation attribute to boot with activation lazy,
 *   but: WFLYCTL0231: Required parameter activation is not present.
 *   {"operation" =&gt; "add","address" =&gt; [("subsystem" =&gt; "osgi")]}
 * </pre>
 * Real server:
 * <pre>
 * ERROR WFLYCTL0013: Operation ("internal-model-validation") failed - address: ([]) - Stage: (MODEL) -
 *   failure description: "WFLYCTL0172: activation is required"
 * FATAL WFLYSRV0056: Server boot has failed in an unrecoverable manner; exiting.
 * </pre>
 *
 * <h2>Expected</h2>
 * The subsystem boots with activation lazy (the schema and model default), for every schema version.
 *
 * <h2>Reference behaviour</h2>
 * none checked (WildFly-specific).
 *
 * <h2>Suggested fix</h2>
 * Declare ACTIVATION optional: {@code new SimpleAttributeDefinitionBuilder(ModelConstants.ACTIVATION,
 * ModelType.STRING, true)} keeping the default value; the add handler already resolves it with
 * resolveModelAttribute, which applies the default.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing; same WildFly Core 33 behaviour on the WF41 branch).
 */
public class ActivationAttributeRequiredIssueTestCase extends AbstractSubsystemTest {

    public ActivationAttributeRequiredIssueTestCase() {
        super(OSGiExtension.SUBSYSTEM_NAME, new OSGiExtension());
    }

    @Test
    public void testSubsystemWithoutActivationBoots() throws Exception {
        for (String ns : new String[] {"urn:jboss:domain:osgi:1.2", "urn:jboss:domain:osgi:1.1", "urn:jboss:domain:osgi:1.0"}) {
            String xml = "<subsystem xmlns='" + ns + "'/>";
            KernelServices services;
            try {
                services = createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT).setSubsystemXml(xml).build();
            } catch (Exception ex) {
                fail(ns + ": expected the subsystem without activation attribute to boot with activation lazy, but: " + ex.getMessage());
                return;
            }
            try {
                assertEquals(ns + ": expected the subsystem without activation attribute to boot, boot error: " + services.getBootError(), true, services.isSuccessfulBoot());
                ModelNode read = new ModelNode();
                read.get("operation").set("read-attribute");
                read.get("address").add("subsystem", "osgi");
                read.get("name").set("activation");
                String activation = services.executeForResult(read).asString();
                assertEquals(ns + ": activation of a subsystem without the attribute", "lazy", activation);
            } finally {
                services.shutdown();
            }
        }
    }
}
