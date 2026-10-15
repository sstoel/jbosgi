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

import static org.junit.Assert.fail;

import javax.xml.stream.XMLStreamException;

import org.jboss.as.osgi.parser.OSGiExtension;
import org.jboss.as.subsystem.test.AbstractSubsystemTest;
import org.jboss.as.subsystem.test.AdditionalInitialization;
import org.jboss.as.subsystem.test.KernelServices;
import org.junit.Test;

/**
 * An empty name of a capability or framework property in the osgi subsystem XML ({@code name=""}) is not reported as
 * a parse error: the parser accepts it, the controller then fails on the operation address with an unchecked
 * IllegalArgumentException of PathElement, so the boot aborts with a raw exception without file, line or column,
 * whose text ("Character ' ' is not allowed") does not match the input.
 *
 * <h2>Severity</h2>
 * cosmetic (the server does not boot either way; only the diagnosis is poor: every other invalid value of this
 * subsystem gets WildFly's OPVDX001 report with the file excerpt and position).
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem, org.jboss.as.osgi.parser.OSGiNamespace12Parser/11/10 (parseCapabilities,
 * parseModules, parseFrameworkProperties).
 *
 * <h2>Found by</h2>
 * fuzz-server (fuzz round 2): subsystem XML fuzzer XmlFuzz seeds 6001/6002, verdict
 * "CRASH-parse PathElement$OperationClientIllegalArgumentException" (8 of 800 inputs, all with name=""), confirmed
 * by a boot of the patched server (JDK 25, offset 1300).
 *
 * <h2>Spec reference</h2>
 * Not an OSGi specification issue. org.jboss.staxmapper.XMLElementReader.readElement declares XMLStreamException for
 * invalid content; WildFly parsers report invalid attribute values with ParseUtils.invalidAttributeValue (an
 * XMLStreamException carrying the location), which the server turns into the OPVDX001 validation report.
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/parser/OSGiNamespace12Parser.java:142 (property name taken
 * as is) and :156 ({@code propNode.get(OP_ADDR).set(address).add(ModelConstants.PROPERTY, name)}), :192
 * ({@code ...add(ModelConstants.CAPABILITY, reader.getAttributeValue(i))}); the same code in OSGiNamespace11Parser
 * and OSGiNamespace10Parser (module identifier). Pre-existing.
 *
 * <h2>Root cause</h2>
 * The parsers put the raw attribute value into the operation address without checking it; the empty value is only
 * rejected when the controller converts the address into a PathAddress (PathElement throws the unchecked
 * OperationClientIllegalArgumentException), outside the parser and without location.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl wildfly/subsystem test -Dtest=EmptyNameAttributeParseErrorIssueTestCase
 * </pre>
 * Real server: add {@code <capability name=""/>} to the capabilities of standalone-osgi.xml and start it.
 *
 * <h2>Observed</h2>
 * <pre>
 * &lt;capability name=''/&gt; (urn:jboss:domain:osgi:1.2): expected an XMLStreamException with the location of the
 *   empty name, but got org.jboss.as.controller.PathElement$OperationClientIllegalArgumentException: WFLYCTL0128:
 *   Invalid resource address element 'capability='. The value '' is not valid for an element in a resource address.
 *   Character ' ' is not allowed.
 * </pre>
 * Real server:
 * <pre>
 * ERROR [org.jboss.as.server] WFLYSRV0055: Caught exception during boot:
 *   org.jboss.as.controller.PathElement$OperationClientIllegalArgumentException: WFLYCTL0128: Invalid resource
 *   address element 'capability='. The value '' is not valid for an element in a resource address. Character ' '
 *   is not allowed.
 * FATAL [org.jboss.as.server] WFLYSRV0056: Server boot has failed in an unrecoverable manner; exiting.
 * </pre>
 *
 * <h2>Expected</h2>
 * An XMLStreamException at the element with the empty name (e.g. "WFLYCTL0106: Invalid value '' for attribute
 * 'name'"), reported by the server like every other configuration error.
 *
 * <h2>Reference behaviour</h2>
 * Invalid values of the other attributes of this subsystem (startlevel="abc", activation="bogus", an empty property
 * value) are reported as XMLStreamException with position.
 *
 * <h2>Suggested fix</h2>
 * In the three parsers, check the name/identifier for emptiness before building the address and throw
 * ParseUtils.invalidAttributeValue(reader, i) (or catch IllegalArgumentException around the address construction
 * and rethrow it as XMLStreamException with reader.getLocation()).
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing).
 */
public class EmptyNameAttributeParseErrorIssueTestCase extends AbstractSubsystemTest {

    public EmptyNameAttributeParseErrorIssueTestCase() {
        super(OSGiExtension.SUBSYSTEM_NAME, new OSGiExtension());
    }

    private void assertParseError(String label, String xml) throws Exception {
        KernelServices services = null;
        try {
            services = createKernelServicesBuilder(AdditionalInitialization.MANAGEMENT).setSubsystemXml(xml).build();
        } catch (XMLStreamException expected) {
            return;
        } catch (RuntimeException ex) {
            fail(label + ": expected an XMLStreamException with the location of the empty name, but got " + ex);
        } finally {
            if (services != null) {
                services.shutdown();
            }
        }
        fail(label + ": expected an XMLStreamException for the empty name, but the configuration was accepted");
    }

    @Test
    public void testEmptyCapabilityName() throws Exception {
        assertParseError("<capability name=''/> (urn:jboss:domain:osgi:1.2)",
                "<subsystem xmlns='urn:jboss:domain:osgi:1.2' activation='lazy'><capabilities><capability name=''/></capabilities></subsystem>");
        assertParseError("<module identifier=''/> (urn:jboss:domain:osgi:1.0)",
                "<subsystem xmlns='urn:jboss:domain:osgi:1.0' activation='lazy'><modules><module identifier=''/></modules></subsystem>");
    }

    @Test
    public void testEmptyPropertyName() throws Exception {
        assertParseError("<property name=''>v</property> (urn:jboss:domain:osgi:1.2)",
                "<subsystem xmlns='urn:jboss:domain:osgi:1.2' activation='lazy'><properties><property name=''>v</property></properties></subsystem>");
    }
}
