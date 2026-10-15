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
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

/**
 * The JTA integration registers the UserTransaction and TransactionManager services but not the
 * TransactionSynchronizationRegistry service.
 *
 * <h2>Severity</h2>
 * major. Bundles that register synchronizations through the OSGi service (JPA providers and frameworks that integrate
 * with the JTA Service, e.g. an EclipseLink/Hibernate JTA platform looking up
 * TransactionSynchronizationRegistry in the service registry) find no service, although WildFly has the registry.
 *
 * <h2>Component</h2>
 * jbosgi (umbrella), wildfly/integration/jta, {@code org.jboss.as.osgi.jta.TransactionsExtension}.
 *
 * <h2>Found by</h2>
 * wildfly workstream, Enterprise R7 123 (JTA Transaction Services) spec alignment (exploratory service check
 * JTA-TransactionSynchronizationRegistry: no service; UserTransaction and TransactionManager present, registered by the
 * system bundle).
 *
 * <h2>Spec reference</h2>
 * OSGi Enterprise R7 123.1.4 Synopsis: "The JTA Provider register the Transaction Services: User Transaction ...
 * Transaction Manager ... Transaction Synchronization Registry - Offers a callback registration service for volatile
 * transactional participants wishing to be notified of the completion of the transaction. A JTA Provider must register
 * these services when it is started." 123.1.2: "Transaction Services - The triplet of the User Transaction, Transaction
 * Manager, and Transaction Synchronization Registry services registered by the JTA Provider."
 * https://docs.osgi.org/specification/osgi.enterprise/7.0.0/service.jta.html
 * (The specification names the javax.transaction types; the server uses the jakarta.transaction equivalents, which is
 * the deliberate Jakarta EE adaptation and not part of this report.)
 *
 * <h2>Code location</h2>
 * wildfly/integration/jta/src/main/java/org/jboss/as/osgi/jta/TransactionsExtension.java:50-63 (dependencies on and
 * registration of TransactionManager and UserTransaction only).
 *
 * <h2>Root cause</h2>
 * The third Transaction Service was never wired in.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -Darq.container.jboss.configuration.managementPort=10490 \
 *     -Darq.container.jboss.configuration.javaVmArguments=-Djboss.socket.binding.port-offset=500 \
 *     -pl testsuite/integration test -Dtest=JtaTransactionSynchronizationRegistryIssueTestCase
 * </pre>
 * (Prerequisite: the patched org.wildfly:wildfly-dist:41.0.1.Final:tar.gz of the RTR WildFly fork build in the local Maven repository. The two -Darq... options start the managed server with port offset 500, so the test cannot connect to another server on 9990 (arquillian.xml has allowConnectingToRunningServer=true); they can be left out when no other server runs.)
 * <h2>Observed</h2>
 * <pre>
 * java.lang.AssertionError: Expected a jakarta.transaction.TransactionSynchronizationRegistry service next to the
 *   UserTransaction and TransactionManager services, but there is none
 * </pre>
 *
 * <h2>Expected</h2>
 * A TransactionSynchronizationRegistry service is registered (WildFly's
 * TransactionSynchronizationRegistry, capability org.wildfly.transactions.transaction-synchronization-registry, also
 * available as TransactionSynchronizationRegistryService.SERVICE_NAME in the transactions subsystem).
 *
 * <h2>Reference behaviour</h2>
 * Not checked (Aries Transaction registers all three services).
 *
 * <h2>Suggested fix (not applied)</h2>
 * Inject the WildFly TransactionSynchronizationRegistry service (capability
 * org.wildfly.transactions.transaction-synchronization-registry) in TransactionsExtension and register it with
 * {@code systemContext.registerService(TransactionSynchronizationRegistry.class.getName(), tsr, null)}.
 *
 * <h2>Regression vs WF41</h2>
 * no (TransactionsExtension unchanged since the WF30 port, e4d06ba7).
 */
@RunWith(Arquillian.class)
public class JtaTransactionSynchronizationRegistryIssueTestCase {

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "jta-tsr-tests");
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
    public void testTransactionServicesRegistered() throws Exception {
        Assert.assertNotNull("Precondition: UserTransaction service", context.getServiceReference("jakarta.transaction.UserTransaction"));
        Assert.assertNotNull("Precondition: TransactionManager service", context.getServiceReference("jakarta.transaction.TransactionManager"));
        ServiceReference<?> tsr = context.getServiceReference("jakarta.transaction.TransactionSynchronizationRegistry");
        Assert.assertNotNull("Expected a jakarta.transaction.TransactionSynchronizationRegistry service next to the UserTransaction and TransactionManager services, but there is none", tsr);
    }
}
