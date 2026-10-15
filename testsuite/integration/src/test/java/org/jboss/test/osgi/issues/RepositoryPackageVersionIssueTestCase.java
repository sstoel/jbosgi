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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * The system bundle exports org.osgi.service.repository as version 1.0, but the classes behind it are the Repository
 * 1.1 API: bundles built against Repository 1.1 (osgi.enterprise 7, the API this build uses) cannot resolve.
 *
 * <h2>Severity</h2>
 * minor. Any bundle that uses the Repository 1.1 API (RequirementBuilder, ExpressionCombiner,
 * findProviders(RequirementExpression)) imports {@code org.osgi.service.repository;version="[1.1,2)"} (bnd's default
 * consumer range for an API compiled against 1.1) and stays INSTALLED, although the server has exactly these classes.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/subsystem, {@code org.jboss.as.osgi.service.SystemPackagesIntegration}
 * (DEFAULT_INTEGRATION_PACKAGES); classes from module org.jboss.osgi.repository (jbosgi-repository-core-7.8.0.Final.jar).
 *
 * <h2>Found by</h2>
 * wildfly workstream, review "package versions exported vs. the actual jars": the embedded
 * org/osgi/service/repository/package-info.class carries {@code @org.osgi.annotation.versioning.Version("1.1")} and
 * the package contains the 1.1 types (AndExpression, ExpressionCombiner, RequirementBuilder, ...); the
 * jbosgi-repository bundle itself exports it as {@code org.osgi.service.repository;version="1.1"}
 * (jbosgi-repository bundle/pom.xml).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 3.6.5 Export-Package: "version - The version of the named packages with syntax as defined in Version. It
 * defines the version of the associated packages." (see also 3.7.3 Semantic Versioning); Enterprise R7 132.7 org.osgi.service.repository: "Version 1.1 ... Example import for
 * consumers using the API in this package: Import-Package: org.osgi.service.repository; version="[1.1,2.0)"".
 * https://docs.osgi.org/specification/osgi.enterprise/7.0.0/service.repository.html
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/SystemPackagesIntegration.java:66
 * ({@code "org.osgi.service.repository;version=1.0;uses:=\"org.osgi.resource\""}; on origin/WF41 the same 1.0 without
 * the uses directive). The R8 commits 6da002c5 ("stale package versions") and 80805736 (added the uses directive) left
 * the version at 1.0.
 *
 * <h2>Root cause</h2>
 * Stale version in the hard-coded integration package list.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=RepositoryPackageVersionIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository. The two -Darq... options start the managed server with port offset 500, so the test cannot connect to another server on 9990 (arquillian.xml has allowConnectingToRunningServer=true); they can be left out when no other server runs.)
 * <h2>Observed</h2>
 * <pre>
 * testSystemBundleExportsRepository11: Expected the system bundle to export org.osgi.service.repository in version 1.1
 *   (the API it provides), but it exports [1.0.0]
 * testRepository11ConsumerResolves: Expected a bundle importing org.osgi.service.repository;version="[1.1,2)" to
 *   resolve, but it stayed INSTALLED
 * </pre>
 *
 * <h2>Expected</h2>
 * {@code org.osgi.service.repository;version=1.1;uses:="org.osgi.resource,org.osgi.util.promise"}; the consumer resolves.
 *
 * <h2>Reference behaviour</h2>
 * Not applicable.
 *
 * <h2>Suggested fix (not applied)</h2>
 * Change the entry to {@code "org.osgi.service.repository;version=1.1;uses:=\"org.osgi.resource,org.osgi.util.promise\""}
 * (the Repository 1.1 API uses Promise), together with the org.osgi.util.function export of
 * PromiseWithoutFunctionPackageIssueTestCase.
 *
 * <h2>Regression vs WF41</h2>
 * no (1.0 on origin/WF41 as well).
 */
@RunWith(Arquillian.class)
public class RepositoryPackageVersionIssueTestCase {

    static final String CONSUMER = "repository-11-consumer";

    @ArquillianResource
    Deployer deployer;

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "repository-version-tests");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(Bundle.class, PackageNamespace.class, BundleCapability.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    @Test
    public void testSystemBundleExportsRepository11() throws Exception {
        List<String> versions = new ArrayList<String>();
        for (BundleCapability cap : context.getBundle(0).adapt(BundleRevision.class).getDeclaredCapabilities(PackageNamespace.PACKAGE_NAMESPACE)) {
            if ("org.osgi.service.repository".equals(cap.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE)))
                versions.add(String.valueOf(cap.getAttributes().get(PackageNamespace.CAPABILITY_VERSION_ATTRIBUTE)));
        }
        Assert.assertFalse("Precondition: the system bundle exports org.osgi.service.repository", versions.isEmpty());
        Assert.assertTrue("Expected the system bundle to export org.osgi.service.repository in version 1.1 (the API it provides), but it exports " + versions,
                versions.contains("1.1.0"));
    }

    @Test
    public void testRepository11ConsumerResolves() throws Exception {
        Bundle bundle = context.installBundle(CONSUMER, deployer.getDeployment(CONSUMER));
        try {
            context.getBundle(0).adapt(FrameworkWiring.class).resolveBundles(Collections.singleton(bundle));
            Assert.assertTrue("Expected a bundle importing org.osgi.service.repository;version=\"[1.1,2)\" to resolve, but it stayed INSTALLED",
                    bundle.getState() != Bundle.INSTALLED);
        } finally {
            bundle.uninstall();
        }
    }

    @Deployment(name = CONSUMER, managed = false, testable = false)
    public static JavaArchive getConsumer() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, CONSUMER);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages("org.osgi.service.repository;version=\"[1.1,2)\"");
                return builder.openStream();
            }
        });
        return archive;
    }
}
