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
package org.jboss.test.osgi.issues;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentHelper;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

/**
 * In the server, an entry below a path that is a file entry too ({@code p/q} and {@code p/q/a.txt}) is not an entry of
 * the bundle: the server mounts the archive with the zip file system of jboss-vfs, which drops it. The framework, which
 * mounts the archives it installs itself in a way that keeps it, finds it.
 *
 * <h3>Severity</h3>
 * minor. Archives with such entries are rare; their resources and classes are not found in the server.
 *
 * <h3>Component</h3>
 * jbosgi/wildfly/subsystem: {@code OSGiBundleInfoParseProcessor}, {@code BundleContentRoot}.
 *
 * <h3>Found by</h3>
 * R8 fix review, residual of tck/02 (support-05).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8 10.1.6.10 Bundle.getEntry: the entries of the bundle are those of its JAR.
 *
 * <h3>Code location</h3>
 * jbosgi-vfs vfs30/src/main/java/org/jboss/osgi/vfs30/VirtualFileAdaptor30.java:124-151 (8e082b8): the lenient file system
 * is used only for archives that the adaptor mounts itself.
 *
 * <h3>Root cause</h3>
 * The bundle content of a deployment is the deployment root that the server mounted.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi/testsuite/integration
 * mvn test -Dtest=ZipEntryUnderFileEntryInServerIssueTestCase
 * </pre>
 *
 * <h3>Expected</h3>
 * getEntry("p/q/a.txt") of the deployed bundle finds the entry.
 *
 * <h3>Regression vs WF41</h3>
 * no.
 */
@RunWith(Arquillian.class)
public class ZipEntryUnderFileEntryInServerIssueTestCase {

    static final String DEPLOYMENT = "zip-entry-under-file.jar";

    @ArquillianResource
    ManagementClient managementClient;

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "zip-entry-under-file-tests");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(ManagementClient.class, ModelControllerClient.class, ServerDeploymentHelper.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Test
    public void testEntryBelowFileEntry() throws Exception {
        ServerDeploymentHelper server = new ServerDeploymentHelper(managementClient.getControllerClient());
        server.deploy(DEPLOYMENT, new ByteArrayInputStream(archive()));
        try {
            Bundle bundle = null;
            for (Bundle aux : context.getBundles()) {
                if ("zip-entry-under-file".equals(aux.getSymbolicName()))
                    bundle = aux;
            }
            Assert.assertNotNull("Precondition: the bundle is installed", bundle);
            Assert.assertNotNull("Precondition: the file entry p/q", bundle.getEntry("p/q"));
            Assert.assertNotNull("The entry below the file entry p/q is an entry of the bundle", bundle.getEntry("p/q/a.txt"));
        } finally {
            server.undeploy(DEPLOYMENT);
        }
    }

    // A JAR with the file p/q and the file p/q/a.txt below it, as java.util.zip writes it
    private static byte[] archive() throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Bundle-ManifestVersion", "2");
        manifest.getMainAttributes().putValue("Bundle-SymbolicName", "zip-entry-under-file");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out, manifest)) {
            jar.putNextEntry(new JarEntry("p/q"));
            jar.write("file".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("p/q/a.txt"));
            jar.write("below".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return out.toByteArray();
    }
}
