/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
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

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.jboss.as.controller.Extension;
import org.jboss.as.controller.ExtensionContext;
import org.jboss.as.controller.ModelVersion;
import org.jboss.as.controller.SubsystemRegistration;
import org.jboss.as.controller.parsing.ExtensionParsingContext;
import org.jboss.as.controller.transform.description.DiscardAttributeChecker;
import org.jboss.as.controller.transform.description.RejectAttributeChecker;
import org.jboss.as.controller.transform.description.ResourceTransformationDescriptionBuilder;
import org.jboss.as.controller.transform.description.TransformationDescription;
import org.jboss.as.controller.transform.description.TransformationDescriptionBuilder;
import org.jboss.as.ee.component.deployers.ResourceInjectionAnnotationParsingProcessor;

import sun.misc.Unsafe;

/**
 * Domain extension used to initialize the OSGi subsystem.
 *
 * @author Thomas.Diesler@jboss.com
 * @author <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 * @author David Bosschaert
 */
@SuppressWarnings("restriction")
public class OSGiExtension implements Extension {
    static {
        Unsafe unsafe;
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            unsafe = (Unsafe) field.get(null);
        } catch (Exception e) {
            throw new Error("JBOSGI HACK: Unsafe is unavailable", e);
        }
        try {
            Field field = ResourceInjectionAnnotationParsingProcessor.class.getDeclaredField("FIXED_LOCATIONS");
            @SuppressWarnings("unchecked")
            Map<String, String> oldFixedLocations = (Map<String, String>) field.get(null);
            Map<String, String> newFixedLocations = new HashMap<String, String>(oldFixedLocations);
            newFixedLocations.put("org.osgi.framework.BundleContext", "java:jboss/osgi/BundleContext");
            unsafe.putObjectVolatile(ResourceInjectionAnnotationParsingProcessor.class, unsafe.staticFieldOffset(field),
                    Collections.unmodifiableMap(newFixedLocations));
        } catch (Exception e) {
            throw new Error("JBOSGI HACK: Unable to patch " + ResourceInjectionAnnotationParsingProcessor.class.getSimpleName() + ".FIXED_LOCATIONS", e);
        }
    }

    public static final String SUBSYSTEM_NAME = "osgi";

    private static final int MANAGEMENT_API_MAJOR_VERSION = 1;
    private static final int MANAGEMENT_API_MINOR_VERSION = 1;
    private static final int MANAGEMENT_API_MICRO_VERSION = 0;

    @Override
    public void initializeParsers(ExtensionParsingContext context) {
        context.setSubsystemXmlMapping(SUBSYSTEM_NAME, Namespace.VERSION_1_0.getUriString(), OSGiNamespace10Parser.INSTANCE);
        context.setSubsystemXmlMapping(SUBSYSTEM_NAME, Namespace.VERSION_1_1.getUriString(), OSGiNamespace11Parser.INSTANCE);
        context.setSubsystemXmlMapping(SUBSYSTEM_NAME, Namespace.VERSION_1_2.getUriString(), OSGiNamespace12Parser.INSTANCE);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void initialize(ExtensionContext context) {

        boolean registerRuntimeOnly = context.isRuntimeOnlyRegistrationValid();

        final SubsystemRegistration subsystem = context.registerSubsystem(SUBSYSTEM_NAME, MANAGEMENT_API_MAJOR_VERSION,
                MANAGEMENT_API_MINOR_VERSION, MANAGEMENT_API_MICRO_VERSION);
        subsystem.registerSubsystemModel(new OSGiRootResource(registerRuntimeOnly));

        subsystem.registerXMLElementWriter(OSGiSubsystemWriter.INSTANCE);

        if (context.isRegisterTransformers()) {
            registerTransformers1_0_0(subsystem);
        }
    }

    private void registerTransformers1_0_0(SubsystemRegistration subsystem) {

        // Root resource
        final ResourceTransformationDescriptionBuilder subsystemRoot = TransformationDescriptionBuilder.Factory.createSubsystemInstance();
        subsystemRoot.getAttributeBuilder()
                .addRejectCheck(RejectAttributeChecker.SIMPLE_EXPRESSIONS, OSGiRootResource.ACTIVATION);

        // Capabilities
        subsystemRoot.addChildResource(FrameworkCapabilityResource.CAPABILITY_PATH)
                .getAttributeBuilder()
                /** 1.0.0 does not like "start-level"=>undefined, so we remove this here */
                .setDiscard(DiscardAttributeChecker.UNDEFINED, FrameworkCapabilityResource.STARTLEVEL)
                .addRejectCheck(RejectAttributeChecker.SIMPLE_EXPRESSIONS, FrameworkCapabilityResource.STARTLEVEL);

        // Properties
        subsystemRoot.addChildResource(FrameworkPropertyResource.PROPERTY_PATH)
                .getAttributeBuilder()
                .addRejectCheck(RejectAttributeChecker.SIMPLE_EXPRESSIONS, FrameworkPropertyResource.VALUE);

        // Register
        TransformationDescription.Tools.register(subsystemRoot.build(), subsystem, ModelVersion.create(1, 0, 0));
    }

}
