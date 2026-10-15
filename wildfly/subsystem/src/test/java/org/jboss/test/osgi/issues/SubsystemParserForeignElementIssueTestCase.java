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

import static javax.xml.stream.XMLStreamConstants.END_ELEMENT;
import static javax.xml.stream.XMLStreamConstants.START_ELEMENT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;

import org.jboss.as.controller.parsing.ExtensionParsingContext;
import org.jboss.as.osgi.parser.OSGiExtension;
import org.jboss.dmr.ModelNode;
import org.jboss.staxmapper.XMLElementReader;
import org.jboss.staxmapper.XMLExtendedStreamReader;
import org.jboss.staxmapper.XMLMapper;
import org.junit.Test;

/**
 * The osgi subsystem parsers for urn:jboss:domain:osgi:1.1 and 1.2 silently skip an element of any other namespace
 * (including the other osgi schema versions) and then return from the subsystem element early: the rest of the
 * subsystem and, on a real server, the rest of the server configuration is dropped.
 *
 * <h2>Severity</h2>
 * minor (edge-case deviation; downgraded from major after independent verification). The trigger is a hand-edited
 * invalid configuration: one wrong element in standalone.xml, e.g. a
 * {@code <capabilities xmlns="urn:jboss:domain:osgi:1.1">} block pasted from an older configuration into the 1.2
 * subsystem, loses the OSGi capabilities and every element after the osgi subsystem. On the patched WildFly 41 server
 * the boot then fails (WFLYSRV0056) with an unrelated error about missing Elytron/socket-binding capabilities, and
 * nothing points at the osgi subsystem. The correct outcome for this invalid configuration is also a failed boot (a
 * WFLYCTL0198 parse error naming the unexpected element), so the damage is a misleading error message, not a running
 * server with silently missing configuration.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem, org.jboss.as.osgi.parser.OSGiNamespace12Parser and OSGiNamespace11Parser
 * (readElement).
 *
 * <h2>Found by</h2>
 * fuzz-server (fuzz round 2), subsystem XML fuzzer XmlFuzz seeds 6001/6002 (800 generated subsystem elements in the
 * three schema versions, verdict "SILENT-ACCEPT [foreign-ns element]"), minimised by hand and confirmed with real
 * boots of the patched server (standalone-osgi.xml, JDK 25, port offset 1300).
 *
 * <h2>Spec reference</h2>
 * Not an OSGi specification issue. Contract of org.jboss.staxmapper.XMLElementReader.readElement: "Read an element
 * from the reader" - on return the reader must be positioned on the END_ELEMENT of the element it was called for.
 * Schema jboss-as-osgi_1_2.xsd (wildfly/build/src/main/resources/wildfly/docs/schema): subsystemType is a sequence of
 * {urn:jboss:domain:osgi:1.2}properties and {urn:jboss:domain:osgi:1.2}capabilities only; any other element is
 * invalid. WildFly parsers report such elements with ParseUtils.unexpectedElement (as OSGiNamespace10Parser does).
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/parser/OSGiNamespace12Parser.java:69-88 and
 * OSGiNamespace11Parser.java:69-92 (unchanged since 24cea4cb, 2013; pre-existing):
 * <pre>
 * while (reader.hasNext() &amp;&amp; reader.nextTag() != END_ELEMENT) {
 *     switch (Namespace.forUri(reader.getNamespaceURI())) {
 *         case VERSION_1_2: { ... }
 *         // no default branch: an element of another namespace is not consumed
 *     }
 * }
 * </pre>
 *
 * <h2>Root cause</h2>
 * The outer switch has no default branch (OSGiNamespace10Parser has one). A START_ELEMENT of another namespace is
 * neither rejected nor skipped: the loop calls nextTag() again, walks into the children of the foreign element, and
 * the first END_ELEMENT it meets (the end of a child or of the foreign element) ends the subsystem loop. readElement
 * returns while the reader is still inside the subsystem element, so the caller takes the remaining end tags for the
 * end of the subsystem, of the profile and of the server element.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl wildfly/subsystem test -Dtest=SubsystemParserForeignElementIssueTestCase
 * </pre>
 * Real server: replace the osgi subsystem of standalone-osgi.xml by
 * <pre>
 * &lt;subsystem xmlns="urn:jboss:domain:osgi:1.2" activation="eager"&gt;
 *     &lt;properties&gt;&lt;property name="org.osgi.framework.startlevel.beginning"&gt;1&lt;/property&gt;&lt;/properties&gt;
 *     &lt;capabilities xmlns="urn:jboss:domain:osgi:1.1"&gt;
 *         &lt;capability name="org.jboss.osgi.logging" startlevel="1"/&gt;
 *     &lt;/capabilities&gt;
 * &lt;/subsystem&gt;
 * </pre>
 *
 * <h2>Observed</h2>
 * <pre>
 * testOtherSchemaVersionElementRejected: urn:jboss:domain:osgi:1.2: expected an XMLStreamException for the
 *   {urn:jboss:domain:osgi:1.1}capabilities element, but it was skipped silently; operations parsed:
 *   [{"operation" =&gt; "add","address" =&gt; [("subsystem" =&gt; "osgi")],"activation" =&gt; "eager"}]
 * testFollowingElementsKept: urn:jboss:domain:osgi:1.2: expected the elements after the subsystem to be read by
 *   the enclosing parser, but it saw [] instead of [interfaces, socket-binding-group]
 * </pre>
 * Real server (same input): the parse "succeeds", every subsystem after osgi and the interfaces/socket bindings are
 * lost, and the boot fails with
 * <pre>
 * ERROR WFLYCTL0362: Capabilities required by resource '/core-service=management/management-interface=http-interface'
 *   are not available: org.wildfly.security.http-authentication-factory.management-http-authentication; ...
 *   org.wildfly.network.socket-binding.management-http; ...
 * FATAL WFLYSRV0056: Server boot has failed in an unrecoverable manner; exiting.
 * </pre>
 * With a foreign element that is followed by a valid osgi element the error names the wrong element:
 * "'properties' isn't an allowed element here. Elements allowed here are: capabilities, properties".
 *
 * <h2>Expected</h2>
 * An element of another namespace inside the subsystem fails the parse at that element with
 * WFLYCTL0198 Unexpected element (as the 1.0 parser does), and the reader is never left inside the subsystem.
 *
 * <h2>Reference behaviour</h2>
 * OSGiNamespace10Parser (same code base) has the default branch and rejects the same input with
 * "Unexpected element '{urn:fuzz:other}foo' encountered".
 *
 * <h2>Suggested fix</h2>
 * Add {@code default: throw unexpectedElement(reader);} to the outer namespace switch of readElement in
 * OSGiNamespace11Parser and OSGiNamespace12Parser (and a {@code break} after the VERSION case).
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing, parser unchanged since 2013).
 */
public class SubsystemParserForeignElementIssueTestCase {

    private static final String ROOT_NS = "urn:fuzz:root";

    private static Map<String, XMLElementReader<List<ModelNode>>> getParsers() {
        Map<String, XMLElementReader<List<ModelNode>>> parsers = new HashMap<>();
        ExtensionParsingContext context = mock(ExtensionParsingContext.class);
        doAnswer(invocation -> {
            parsers.put(invocation.getArgument(1), invocation.getArgument(2));
            return null;
        }).when(context).setSubsystemXmlMapping(anyString(), anyString(), any(XMLElementReader.class));
        new OSGiExtension().initializeParsers(context);
        return parsers;
    }

    /**
     * Parses {@code <server xmlns="urn:fuzz:root">subsystemXml<interfaces/><socket-binding-group/></server>} with the
     * osgi parser registered for its namespace, like the server's profile parser does. Returns the local names of the
     * root children seen after the subsystem; the subsystem operations are added to ops.
     */
    private static List<String> parseInRoot(String namespace, String subsystemXml, List<ModelNode> ops) throws XMLStreamException {
        XMLElementReader<List<ModelNode>> osgiParser = getParsers().get(namespace);
        assertNotNull("parser for " + namespace, osgiParser);
        List<String> seen = new ArrayList<>();
        XMLMapper mapper = XMLMapper.Factory.create();
        mapper.registerRootElement(new QName(namespace, "subsystem"), osgiParser);
        mapper.registerRootElement(new QName(ROOT_NS, "server"), new XMLElementReader<List<ModelNode>>() {
            @Override
            public void readElement(XMLExtendedStreamReader reader, List<ModelNode> value) throws XMLStreamException {
                while (reader.hasNext() && reader.nextTag() != END_ELEMENT) {
                    if ("subsystem".equals(reader.getLocalName())) {
                        reader.handleAny(value);
                    } else {
                        seen.add(reader.getLocalName());
                        int depth = 1;
                        while (depth > 0) {
                            int event = reader.next();
                            if (event == START_ELEMENT) depth++;
                            if (event == END_ELEMENT) depth--;
                        }
                    }
                }
            }
        });
        String doc = "<server xmlns='" + ROOT_NS + "'>" + subsystemXml + "<interfaces/><socket-binding-group/></server>";
        mapper.parseDocument(ops, XMLInputFactory.newInstance().createXMLStreamReader(new StringReader(doc)));
        return seen;
    }

    @Test
    public void testOtherSchemaVersionElementRejected() throws Exception {
        String[][] cases = {
            {"urn:jboss:domain:osgi:1.2", "urn:jboss:domain:osgi:1.1"},
            {"urn:jboss:domain:osgi:1.1", "urn:jboss:domain:osgi:1.2"},
        };
        for (String[] c : cases) {
            String xml = "<subsystem xmlns='" + c[0] + "' activation='eager'>"
                    + "<capabilities xmlns='" + c[1] + "'><capability name='org.acme.module1'/></capabilities>"
                    + "</subsystem>";
            List<ModelNode> ops = new ArrayList<>();
            try {
                parseInRoot(c[0], xml, ops);
            } catch (XMLStreamException expected) {
                continue;
            }
            fail(c[0] + ": expected an XMLStreamException for the {" + c[1] + "}capabilities element, but it was skipped silently; operations parsed: " + ops);
        }
    }

    @Test
    public void testFollowingElementsKept() throws Exception {
        for (String ns : new String[] {"urn:jboss:domain:osgi:1.2", "urn:jboss:domain:osgi:1.1"}) {
            String xml = "<subsystem xmlns='" + ns + "' activation='eager'>"
                    + "<capabilities><capability name='org.acme.module1'/></capabilities>"
                    + "<foo xmlns='urn:fuzz:other'><bar/></foo>"
                    + "</subsystem>";
            List<ModelNode> ops = new ArrayList<>();
            List<String> seen;
            try {
                seen = parseInRoot(ns, xml, ops);
            } catch (XMLStreamException ex) {
                // rejecting the foreign element is the fixed behaviour
                continue;
            }
            assertEquals(ns + ": expected the elements after the subsystem to be read by the enclosing parser, but it saw " + seen + " instead of",
                    List.of("interfaces", "socket-binding-group"), seen);
        }
    }
}
