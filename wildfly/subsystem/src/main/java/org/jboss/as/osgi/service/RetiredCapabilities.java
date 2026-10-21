/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2026, Red Hat, Inc., and individual contributors
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

package org.jboss.as.osgi.service;

import java.util.Map;

/**
 * The capabilities of the OSGi subsystem that a release removed. A configuration that was written for an earlier
 * release keeps working: the subsystem skips such a capability with a warning instead of failing to start.
 */
public final class RetiredCapabilities {

    // capability (module name) -> what replaces it
    private static final Map<String, String> RETIRED = Map.of(
            "org.apache.felix.log", "the framework provides the OSGi Log Service",
            // Version 4.2 needs javax.servlet, which the server does not provide; its default user is admin/admin (security/02)
            "org.apache.felix.webconsole", "the Felix Web Console 4.2 does not run on Jakarta EE and is not shipped any more");

    private RetiredCapabilities() {
    }

    /**
     * What replaces the capability that a release removed, null when the capability was not removed
     */
    public static String getReplacement(String identifier) {
        String name = identifier.trim();
        if (name.endsWith(":main"))
            name = name.substring(0, name.length() - ":main".length());
        return RETIRED.get(name);
    }
}
