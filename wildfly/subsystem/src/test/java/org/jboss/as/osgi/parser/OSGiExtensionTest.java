package org.jboss.as.osgi.parser;

import org.junit.Test;

public class OSGiExtensionTest {
    @Test
    public void testStaticInitializer() throws Exception {
        // This will trigger the static initializer of OSGiExtension
        Class.forName(OSGiExtension.class.getName());
    }
}
