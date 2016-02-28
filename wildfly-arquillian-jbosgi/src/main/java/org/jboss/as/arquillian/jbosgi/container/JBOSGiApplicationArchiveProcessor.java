/*
 * Copyright 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.as.arquillian.jbosgi.container;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.jboss.arquillian.container.osgi.AbstractOSGiApplicationArchiveProcessor;
import org.jboss.arquillian.container.test.spi.client.deployment.ApplicationArchiveProcessor;
import org.jboss.arquillian.test.spi.TestClass;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ArchivePath;
import org.jboss.shrinkwrap.api.ArchivePaths;
import org.jboss.shrinkwrap.api.Node;
import org.jboss.shrinkwrap.api.asset.Asset;

/**
 * An OSGi {@link ApplicationArchiveProcessor} that does not generate the
 * manifest on demand. AS7 test archives must be explicit about their manifest
 * metadata.
 *
 * @author Thomas.Diesler@jboss.com
 */
public class JBOSGiApplicationArchiveProcessor implements ApplicationArchiveProcessor {
    private static final ArchivePath MANIFEST_PATH = ArchivePaths.create(JarFile.MANIFEST_NAME);
    private static final String ESSENTIAL_DEPENDENCIES = "org.jboss.as.osgi,deployment.arquillian-service";

    @Override
    public void process(Archive<?> appArchive, TestClass testClass) {
        if (isValidOSGiBundleArchive(appArchive)) {
            ApplicationArchiveProcessor processor = new AbstractOSGiApplicationArchiveProcessor() {
                @Override
                protected Manifest createBundleManifest(String symbolicName) {
                    return null;
                }
            };
            processor.process(appArchive, testClass);
        } else {
            // Add or replace the manifest in the archive
            try {
                Manifest existingManifest = new Manifest();
                Node manifestNode = appArchive.get(MANIFEST_PATH);
                if (manifestNode != null) {
                    existingManifest = new Manifest(manifestNode.getAsset().openStream());
                    appArchive.delete(MANIFEST_PATH);
                }
                Attributes attrs = existingManifest.getMainAttributes();
                String dependenciesAttr = attrs != null ? attrs.getValue("Dependencies") : null;
                if (dependenciesAttr != null) {
                    dependenciesAttr += "," + ESSENTIAL_DEPENDENCIES;
                } else {
                    dependenciesAttr = ESSENTIAL_DEPENDENCIES;
                    attrs.putValue(Attributes.Name.MANIFEST_VERSION.toString(), "1.0");
                }
                attrs.putValue("Dependencies", dependenciesAttr);

                final Manifest newManifest = existingManifest;
                appArchive.add(new Asset() {
                    public InputStream openStream() {
                        try {
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            newManifest.write(bos);
                            return new ByteArrayInputStream(bos.toByteArray());
                        } catch (IOException e) {
                            throw new RuntimeException("Unable to write customized application manifest", e);
                        }
                    }
                }, JarFile.MANIFEST_NAME);
            } catch (IllegalArgumentException | IOException e) {
                throw new RuntimeException("Unable to customize existing application manifest", e);
            }
        }
    }

    public static boolean isValidOSGiBundleArchive(Archive<?> appArchive) {
        // org.jboss.arquillian.container:arquillian-container-osgi must be be
        // provided
        ClassLoader classLoader = JBOSGiApplicationArchiveProcessor.class.getClassLoader();
        try {
            classLoader.loadClass("org.jboss.arquillian.container.osgi.AbstractOSGiApplicationArchiveProcessor");
        } catch (ClassNotFoundException ex) {
            return false;
        }
        Manifest manifest = ManifestUtils.getManifest(appArchive, true);
        return OSGiManifestBuilder.isValidBundleManifest(manifest);
    }
}
