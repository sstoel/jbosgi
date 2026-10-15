/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013, Red Hat, Inc., and individual contributors
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

import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

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
import org.xml.sax.SAXException;

/**
 * The jboss-as-osgi_1_2.xsd shipped in docs/schema (added on this branch) does not allow expressions where the
 * subsystem model does: a configuration that the server itself writes after
 * {@code :write-attribute(name=activation,value=${...})} or {@code capability=X:add(startlevel=${...})} is invalid
 * according to the schema.
 *
 * <h3>Severity</h3>
 * cosmetic. IDEs and configuration tools that validate standalone*.xml against the published schema reject valid,
 * server-written configurations; the server itself does not validate against the XSD.
 *
 * <h3>Component</h3>
 * jbosgi (umbrella), wildfly/build/src/main/resources/wildfly/docs/schema/jboss-as-osgi_1_2.xsd (also 1_0 / 1_1 for
 * the same attributes) versus wildfly/subsystem OSGiRootResource.ACTIVATION / FrameworkCapabilityResource.STARTLEVEL
 * (setAllowExpression(true)) and OSGiSubsystemWriter.
 *
 * <h3>Found by</h3>
 * services workstream round 4 (XSD versus parser/model, marshal/unmarshal round trip on the patched server).
 *
 * <h3>Spec reference</h3>
 * The subsystem's read-resource-description: activation "expressions-allowed" => true, capability startlevel
 * "expressions-allowed" => true. The schema: {@code <xs:attribute name="activation" type="activationType"
 * default="lazy"/>} with an xs:token enumeration of eager/lazy, {@code <xs:attribute name="startlevel"
 * type="xs:integer"/>}. WildFly's own subsystem schemas declare expression-capable attributes as xs:string (or a
 * union with an expression pattern) so that the persisted configuration validates.
 *
 * <h3>Code location</h3>
 * wildfly/build/src/main/resources/wildfly/docs/schema/jboss-as-osgi_1_2.xsd (activationType, capabilityType
 * startlevel). The schema files were added by commit c4bbcd55 on this branch (not on WF41).
 *
 * <h3>Root cause</h3>
 * The XSD was written from the old enumerations without taking the expression support of the model into account.
 *
 * <h3>Reproduction</h3>
 * JDK 25, own umbrella worktree (see SubsystemChangesNotFlaggedIssueTestCase):
 * {@code $SP/bin/mvn25 -B -o ... -pl testsuite/integration -Dtest=SubsystemSchemaExpressionsIssueTestCase -Dsurefire.failIfNoSpecifiedTests=false clean install}
 *
 * <h3>Observed</h3>
 * <pre>
 * testServerWrittenConfigurationValidates: the osgi subsystem element written by the server must validate against
 *   docs/schema/jboss-as-osgi_1_2.xsd, but: cvc-enumeration-valid: Value '${osgi.test.activation:eager}' is not facet-valid ...
 * </pre>
 *
 * <h3>Expected</h3>
 * The persisted subsystem element validates against the shipped schema.
 *
 * <h3>Reference behaviour</h3>
 * Not applicable.
 *
 * <h3>Suggested fix</h3>
 * Declare activation and capability startlevel as xs:string (documenting the allowed values and the ${...} form), as
 * the WildFly subsystem schemas do for expression attributes.
 *
 * Verified: with both attributes declared as xs:string in the schema of the testsuite server, the test passes.
 *
 * <h3>Regression vs WF41</h3>
 * not applicable (the schema files are new on this branch).
 */
@RunWith(Arquillian.class)
@RunAsClient
public class SubsystemSchemaExpressionsIssueTestCase {

    @ArquillianResource
    ManagementClient managementClient;

    @Deployment(testable = false)
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "subsystem-schema-expressions");
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
    public void testServerWrittenConfigurationValidates() throws Exception {
        File home = new File(System.getProperty("jboss.home"));
        File config = new File(home, "standalone/configuration/standalone-osgi.xml");
        File xsd = new File(home, "docs/schema/jboss-as-osgi_1_2.xsd");
        Assert.assertTrue("Precondition: schema shipped at " + xsd, xsd.isFile());

        String original = execute(op("read-attribute", "name", "activation")).get("result").asString();
        try {
            Assert.assertEquals("Precondition: activation accepts an expression", "success",
                    execute(op("write-attribute", "name", "activation", "value", "${osgi.test.activation:" + original + "}")).get("outcome").asString());

            String text = new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("<subsystem xmlns=\"urn:jboss:domain:osgi:1.2\".*?</subsystem>", Pattern.DOTALL).matcher(text);
            Assert.assertTrue("Precondition: osgi subsystem element in " + config, matcher.find());
            String element = matcher.group();
            Assert.assertTrue("Precondition: the server persisted the expression: " + element.substring(0, Math.min(120, element.length())),
                    element.contains("${osgi.test.activation"));

            Schema schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(xsd);
            try {
                schema.newValidator().validate(new StreamSource(new StringReader(element)));
            } catch (SAXException ex) {
                Assert.fail("the osgi subsystem element written by the server must validate against docs/schema/jboss-as-osgi_1_2.xsd, but: " + ex.getMessage());
            }
        } finally {
            execute(op("write-attribute", "name", "activation", "value", original));
            reloadIfRequired();
        }
    }

    private static ModelNode op(String name, String... keyValues) {
        ModelNode op = new ModelNode();
        op.get("operation").set(name);
        op.get("address").add("subsystem", "osgi");
        for (int i = 0; i + 1 < keyValues.length; i += 2)
            op.get(keyValues[i]).set(keyValues[i + 1]);
        return op;
    }

    private ModelNode execute(ModelNode op) throws Exception {
        return managementClient.getControllerClient().execute(op);
    }

    private String serverState() {
        try {
            ModelNode op = new ModelNode();
            op.get("operation").set("read-attribute");
            op.get("address").setEmptyList();
            op.get("name").set("server-state");
            ModelNode response = execute(op);
            return response.get("outcome").asString().equals("success") ? response.get("result").asString() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private void reloadIfRequired() throws Exception {
        if ("running".equals(serverState()))
            return;
        ModelNode reload = new ModelNode();
        reload.get("operation").set("reload");
        reload.get("address").setEmptyList();
        try {
            execute(reload);
        } catch (Exception ex) {
            // the connection may drop while reloading
        }
        long end = System.currentTimeMillis() + 120000;
        Thread.sleep(2000);
        while (System.currentTimeMillis() < end && "running".equals(serverState()) == false)
            Thread.sleep(500);
    }
}
