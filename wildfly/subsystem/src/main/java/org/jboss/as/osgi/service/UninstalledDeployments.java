/*
 * JBoss, Home of Professional Open Source
 * Copyright 2005, JBoss Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
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

import static org.jboss.as.osgi.OSGiLogger.LOGGER;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

/**
 * The deployments whose bundle was uninstalled while the deployment could not be removed from the management model at
 * once: Bundle.uninstall from a deployment that starts or stops, within a management operation, or while the server
 * stops. The deployment is removed by an operation that runs later. Until that removal is done the deployment is
 * recorded in the storage area of the framework, so that the uninstall survives a restart: the server does not install
 * the bundle of a recorded deployment again, it removes the deployment after the boot.
 */
public final class UninstalledDeployments {

    private static final String FILE_NAME = "uninstalled-deployments.properties";

    private UninstalledDeployments() {
    }

    /**
     * Record the deployment of an uninstalled bundle by its name in the management model
     */
    public static synchronized void add(File storageArea, String managementName) {
        Properties names = read(storageArea);
        if (names != null && names.setProperty(managementName, "uninstalled") == null)
            write(storageArea, names);
    }

    /**
     * The deployment was removed, or a deployment of the name was deployed again
     */
    public static synchronized void remove(File storageArea, String managementName) {
        Properties names = read(storageArea);
        if (names != null && names.remove(managementName) != null)
            write(storageArea, names);
    }

    public static synchronized boolean contains(File storageArea, String managementName) {
        Properties names = read(storageArea);
        return names != null && names.containsKey(managementName);
    }

    // Null if the list cannot be read: a list that was only read in part is not written back (shutdown/15)
    private static Properties read(File storageArea) {
        if (storageArea == null)
            return null;
        Properties names = new Properties();
        File file = new File(storageArea, FILE_NAME);
        if (file.isFile()) {
            try (InputStream input = new FileInputStream(file)) {
                names.load(input);
            } catch (IOException | IllegalArgumentException ex) {
                LOGGER.warnf(ex, "Cannot read: %s", file);
                return null;
            }
        }
        return names;
    }

    // The rename is on the disk too. Not every platform can sync a directory
    private static void syncDirectory(File dir) {
        try (FileChannel channel = FileChannel.open(dir.toPath(), StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | RuntimeException ex) {
            // ignore
        }
    }

    private static void write(File storageArea, Properties names) {
        File file = new File(storageArea, FILE_NAME);
        try {
            if (names.isEmpty()) {
                Files.deleteIfExists(file.toPath());
                return;
            }
            storageArea.mkdirs();
            // Written to a new file that replaces the old one: a crash leaves the old or the new list
            File temp = new File(storageArea, FILE_NAME + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temp)) {
                names.store(output, "The deployments of uninstalled bundles that are still to be removed");
                // On the disk before it replaces the old list, also after a power loss
                output.getFD().sync();
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            syncDirectory(storageArea);
        } catch (IOException ex) {
            LOGGER.warnf(ex, "Cannot write: %s", file);
        }
    }
}
