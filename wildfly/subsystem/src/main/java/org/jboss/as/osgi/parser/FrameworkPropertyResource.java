/*
* JBoss, Home of Professional Open Source.
* Copyright 2011, Red Hat Middleware LLC, and individual contributors
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
package org.jboss.as.osgi.parser;

import static org.jboss.as.osgi.OSGiMessages.MESSAGES;

import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.SimpleAttributeDefinition;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.SimpleResourceDefinition;
import org.jboss.as.controller.registry.AttributeAccess.Flag;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

/**
 *
 * @author <a href="kabir.khan@jboss.com">Kabir Khan</a>
 */
public class FrameworkPropertyResource extends SimpleResourceDefinition {

    static final PathElement PROPERTY_PATH = PathElement.pathElement(ModelConstants.PROPERTY);
    // The framework is created with its properties, a reload applies a change
    static final SimpleAttributeDefinition VALUE = new SimpleAttributeDefinitionBuilder(ModelConstants.VALUE, ModelType.STRING)
            .addFlag(Flag.RESTART_ALL_SERVICES)
            .setAllowExpression(true)
            .build();

    FrameworkPropertyResource() {
        super(PROPERTY_PATH, OSGiResolvers.getResolver("framework.property"),
                OSGiFrameworkPropertyAdd.INSTANCE, OSGiFrameworkPropertyRemove.INSTANCE);
    }

    @Override
    public void registerAttributes(ManagementResourceRegistration resourceRegistration) {
        resourceRegistration.registerReadWriteAttribute(VALUE, null, OSGiFrameworkPropertyWrite.INSTANCE);
    }

    /**
     * Rejects a property value that does not survive persist and parse of the subsystem configuration.
     *
     * The parsers trim the element text of a property, so leading or trailing whitespace (and a whitespace only value)
     * would be lost or make the persisted configuration unparseable. A carriage return is normalized to a line feed by
     * the XML parser and the other control characters can not be represented in XML 1.0 at all.
     *
     * This checks the value as stored in the model (an expression unresolved), not the resolved value; it is not a
     * validator of {@link #VALUE} because expressions are only validated after they are resolved.
     */
    static void validatePersistentValue(ModelNode value) throws OperationFailedException {
        if (value.isDefined()) {
            String str = value.asString();
            if (!str.equals(str.trim())) {
                throw MESSAGES.invalidFrameworkPropertyValue(str, ModelConstants.VALUE);
            }
            for (int i = 0; i < str.length(); i++) {
                char ch = str.charAt(i);
                if (ch < ' ' && ch != '\t' && ch != '\n') {
                    throw MESSAGES.invalidFrameworkPropertyValue(str, ModelConstants.VALUE);
                }
            }
        }
    }
}
