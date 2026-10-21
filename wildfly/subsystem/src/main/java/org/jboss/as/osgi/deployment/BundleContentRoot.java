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

package org.jboss.as.osgi.deployment;

import static org.jboss.as.osgi.OSGiLogger.LOGGER;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.osgi.vfs.AbstractVFS;
import org.jboss.vfs.VirtualFile;

/**
 * The content of a bundle deployment as the framework reads it. The server mounts an archive with the zip file system
 * of jboss-vfs, which drops the entries below a path that is also a file entry ({@code p/q} and {@code p/q/A.class}).
 * The framework mounts such an archive with a file system that keeps them, as java.util.zip does: the bundle of a
 * deployment gets the same view of such an archive (tck/02).
 *
 * @since 20-Oct-2026
 */
final class BundleContentRoot {

    private static final AtomicLong count = new AtomicLong();

    private BundleContentRoot() {
    }

    /**
     * The root of the bundle content: the deployment root, or the archive of the deployment mounted again
     */
    static org.jboss.osgi.vfs.VirtualFile getBundleRoot(DeploymentUnit depUnit, VirtualFile deploymentRoot) {
        File archive = getArchive(depUnit);
        if (archive != null && hasPathThatIsFileAndDirectory(archive)) {
            try {
                InputStream input = new FileInputStream(archive);
                return AbstractVFS.toVirtualFile("osgi-bundle-content-" + count.incrementAndGet() + "-" + archive.getName(), input);
            } catch (IOException ex) {
                LOGGER.debugf(ex, "Cannot mount the archive of %s again", depUnit.getName());
            }
        }
        return AbstractVFS.adapt(deploymentRoot);
    }

    // The archive of a top level deployment, not an exploded one
    private static File getArchive(DeploymentUnit depUnit) {
        if (depUnit.getParent() != null)
            return null;
        VirtualFile contents = depUnit.getAttachment(Attachments.DEPLOYMENT_CONTENTS);
        try {
            File file = contents != null ? contents.getPhysicalFile() : null;
            return file != null && file.isFile() ? file : null;
        } catch (IOException ex) {
            return null;
        }
    }

    // An entry whose path is a file entry too
    static boolean hasPathThatIsFileAndDirectory(File archive) {
        try (ZipFile zipFile = new ZipFile(archive)) {
            Set<String> files = new HashSet<String>();
            Set<String> parents = new HashSet<String>();
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.endsWith("/"))
                    continue;
                files.add(name);
                for (int index = name.indexOf('/'); index > 0; index = name.indexOf('/', index + 1))
                    parents.add(name.substring(0, index));
            }
            parents.retainAll(files);
            return parents.isEmpty() == false;
        } catch (IOException ex) {
            return false;
        }
    }
}
