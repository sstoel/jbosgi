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
 * A framework property value that the management API accepts does not survive persist and re-parse of the osgi
 * subsystem: a whitespace-only value makes the persisted configuration unparseable (the server does not boot
 * any more), leading/trailing whitespace is dropped, and a multi-line value gains indentation on every persist.
 *
 * <h2>Severity</h2>
 * major (one accepted management operation, e.g. {@code /subsystem=osgi/property=x:add(value=" ")}, leaves a
 * standalone.xml with which the server can not boot: the next :reload or restart ends with
 * "WFLYSRV0056: Server boot has failed in an unrecoverable manner; exiting"; the config file must be repaired by hand).
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem, org.jboss.as.osgi.parser.OSGiSubsystemWriter (writer),
 * OSGiNamespace12Parser/11/10 (parseFrameworkProperties), FrameworkPropertyResource.VALUE.
 *
 * <h2>Found by</h2>
 * fuzz-server (fuzz round 2): subsystem XML fuzzer XmlFuzz seeds 6001/6002 (verdict ROUNDTRIP-DIFF for "x&#10;y" and
 * whitespace values) and the management-value round-trip probe XmlMgmt (values " ", "\t", "\n", "  x  ",
 * "x\r\ny"); confirmed on the patched server (JDK 25, offset 1300) with add(value=" ") followed by :reload.
 *
 * <h2>Spec reference</h2>
 * Not an OSGi specification issue. WildFly management contract: a configuration change that the model accepts is
 * persisted to standalone.xml so that the next boot recreates the same model (XmlConfigurationPersister,
 * "parse -&gt; marshal -&gt; parse" fidelity that the subsystem test harness checks with compareXml).
 * Schema jboss-as-osgi_1_2.xsd: propertyValueType is xs:string (whitespace is significant, not xs:token).
 *
 * <h2>Code location</h2>
 * <ul>
 * <li>wildfly/subsystem/src/main/java/org/jboss/as/osgi/parser/OSGiSubsystemWriter.java:67
 * {@code writer.writeCharacters(val)}: the value is written as element text; WildFly's formatting writer puts it
 * on an indented line of its own and indents every further line.</li>
 * <li>OSGiNamespace12Parser.java:152 (OSGiNamespace11Parser.java:205, OSGiNamespace10Parser.java:207)
 * {@code value = reader.getElementText().trim();}: the parser trims, so the formatting whitespace is removed but so
 * is the value's own whitespace, and a whitespace-only value becomes "" which VALUE rejects
 * (WFLYCTL0113 minimum length 1).</li>
 * <li>FrameworkPropertyResource.java:39-42: VALUE accepts any non-empty string, including " ".</li>
 * </ul>
 * Pre-existing (writer/parsers unchanged since 24cea4cb, 2013).
 *
 * <h2>Root cause</h2>
 * The persisted form (indented element text) and the parser (trim) are not inverse to the model's validation:
 * whatever whitespace the value carries is not representable in the persisted XML.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl wildfly/subsystem test -Dtest=PropertyValuePersistRoundTripIssueTestCase
 * </pre>
 * Real server: {@code /subsystem=osgi/property=fuzz.ws:add(value=" ")} (outcome success), then {@code :reload}.
 *
 * <h2>Observed</h2>
 * <pre>
 * testWhitespaceOnlyValue: value [ ] was accepted by add, but the persisted configuration can not be parsed again:
 *   ParseError at [row,col]:[6,20] Message: "WFLYCTL0113: '' is an invalid value for parameter value. Values must
 *   have a minimum length of 1 characters"
 * testSurroundingWhitespaceKept: value changed by persist + parse expected:&lt;[  x  ]&gt; but was:&lt;[x]&gt;
 * testMultiLineValueStable: value changed by persist + parse expected:&lt;a,[\n]b&gt; but was:&lt;a,[\n            ]b&gt;
 *   (cumulative: 12 more spaces per persist + parse cycle, 36 after three cycles)
 * </pre>
 * Real server log after :reload:
 * <pre>
 * OPVDX001: Validation error in standalone-osgi.xml
 *  450: &lt;property name="fuzz.ws"&gt;
 *  451:
 *  452: &lt;/property&gt;
 *       ^^^^ '' is an invalid value for parameter value. Values must have a minimum length of 1 characters
 * FATAL [org.jboss.as.server] WFLYSRV0056: Server boot has failed in an unrecoverable manner; exiting.
 * </pre>
 *
 * <h2>Expected</h2>
 * Either the model rejects values that can not be persisted (e.g. blank values), or the value is persisted so that
 * the next parse yields exactly the same string; in every case a persisted configuration boots.
 *
 * <h2>Reference behaviour</h2>
 * WildFly core attributes persisted as XML attributes (e.g. /system-property=x:add(value=" ")) keep the value exactly.
 *
 * <h2>Suggested fix</h2>
 * Persist the value as an attribute ({@code <property name="x" value="..."/>}, a new schema version) or wrap it so
 * that the formatter does not touch it and stop trimming in the parser for that form; at least add a validator to
 * FrameworkPropertyResource.VALUE that rejects blank values and values with leading/trailing whitespace or line
 * breaks, so the model never holds a value the persister can not reproduce.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing).
 */
public class PropertyValuePersistRoundTripIssueTestCase extends AbstractSubsystemTest {

    public PropertyValuePersistRoundTripIssueTestCase() {
        super(OSGiExtension.SUBSYSTEM_NAME, new OSGiExtension());
    }

    private KernelServices boot(String xml) throws Exception {
        KernelServices services = createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT).setSubsystemXml(xml).build();
        assertEquals("boot of " + xml, true, services.isSuccessfulBoot());
        return services;
    }

    private static ModelNode addProperty(String value) {
        ModelNode op = new ModelNode();
        op.get("operation").set("add");
        op.get("address").add("subsystem", "osgi").add("property", "fuzz.p");
        op.get("value").set(value);
        return op;
    }

    /**
     * Adds the property, then persists and parses the subsystem the given number of times; returns the value read
     * back (or the original value if the model rejects it).
     */
    private String roundTrip(String value, int times) throws Exception {
        KernelServices services = boot("<subsystem xmlns='urn:jboss:domain:osgi:1.2' activation='lazy'/>");
        ModelNode result = services.executeOperation(addProperty(value));
        if (!"success".equals(result.get("outcome").asString())) {
            services.shutdown();
            return value; // rejecting a value that can not be persisted is a fix too
        }
        for (int i = 0; i < times; i++) {
            String persisted = services.getPersistedSubsystemXml();
            services.shutdown();
            try {
                services = createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT).setSubsystemXml(persisted).build();
            } catch (Exception ex) {
                fail("value [" + value + "] was accepted by add, but the persisted configuration can not be parsed again: " + ex.getMessage() + "\npersisted:\n" + persisted);
            }
            assertEquals("boot of the persisted configuration", true, services.isSuccessfulBoot());
        }
        String actual = services.readWholeModel().get("subsystem", "osgi", "property", "fuzz.p", "value").asString();
        services.shutdown();
        return actual;
    }

    @Test
    public void testWhitespaceOnlyValue() throws Exception {
        assertEquals("value changed by persist + parse", " ", roundTrip(" ", 1));
    }

    @Test
    public void testSurroundingWhitespaceKept() throws Exception {
        assertEquals("value changed by persist + parse", "  x  ", roundTrip("  x  ", 1));
    }

    @Test
    public void testMultiLineValueStable() throws Exception {
        assertEquals("value changed by persist + parse", "a,\nb", roundTrip("a,\nb", 1));
    }
}
