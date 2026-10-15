/*
 * #%L
 * JBossOSGi
 * %%
 * Copyright (C) 2026 RTR
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 * #L%
 */
package org.jboss.test.osgi.issues;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.Dictionary;
import java.util.List;

import org.jboss.msc.value.InjectedValue;
import org.jboss.osgi.repository.ResourceInstaller;
import org.jboss.osgi.repository.XRepository;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.service.repository.Repository;

/**
 * The WildFly osgi subsystem registers the OSGi Repository service without the mandatory service.pid property (it passes
 * no service properties at all).
 *
 * <h2>Severity</h2>
 * minor. Spec "must" violation of the Repository service registration; clients that select or track repositories by
 * service.pid (as the spec intends: "A globally unique identifier for this Repository") cannot find this one, and
 * service.description / repository.url are absent too.
 *
 * <h2>Component</h2>
 * jbosgi umbrella, module wildfly/subsystem, class org.jboss.as.osgi.service.SystemServicesIntegration
 * (registerRepositoryServices). The standalone repository bundle (jbosgi-repository/bundle RepositoryActivator.start)
 * registers only service.description and has the same gap; it is not used in the server.
 *
 * <h2>Found by</h2>
 * subrepos-b workstream, Repository service registration vs Compendium 132.3 (the Repository TCK does not check it).
 *
 * <h2>Spec reference</h2>
 * OSGi Compendium R8, 132.3 Repository (https://docs.osgi.org/specification/osgi.cmpn/8.0.0/service.repository.html):
 * "A Repository service must be registered with the service properties given in the following table." Table 132.1:
 * "service.pid mandatory String A globally unique identifier for this Repository." 132.2: "service.pid - A mandatory
 * unique identity for this Repository service."
 *
 * <h2>Code location</h2>
 * wildfly/subsystem/src/main/java/org/jboss/as/osgi/service/SystemServicesIntegration.java:188 (WF41-osgi-r8 f025b420):
 * syscontext.registerService(classes, repository, null); pre-existing.
 *
 * <h2>Root cause</h2>
 * No service properties are passed.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full -pl wildfly/subsystem -am install -DskipTests
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full -pl wildfly/subsystem test -Dtest=RepositoryServicePidMissingIssueTestCase
 * </pre>
 * The test calls the private registerRepositoryServices with a mocked system BundleContext and captures the registration.
 *
 * <h2>Observed</h2>
 * <pre>
 * testRepositoryServiceHasServicePid:118 Expected the Repository service to be registered with the mandatory service.pid
 *   property (Compendium 132.3, Table 132.1), but it was registered with no service properties at all
 * </pre>
 *
 * <h2>Expected</h2>
 * The registration carries service.pid (e.g. "org.jboss.osgi.repository.persistent"), and preferably
 * service.description (repository name).
 *
 * <h2>Reference behaviour</h2>
 * n/a (not run). The OSGi Repository TCK registers its own fixtures and does not verify this property.
 *
 * <h2>Suggested fix</h2>
 * Pass a Dictionary with Constants.SERVICE_PID (a stable unique value) and Constants.SERVICE_DESCRIPTION
 * (repository.getName()); do the same in RepositoryActivator.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing, same code on WF41).
 */
public class RepositoryServicePidMissingIssueTestCase {

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void testRepositoryServiceHasServicePid() throws Exception {
        Class<?> clazz = Class.forName("org.jboss.as.osgi.service.SystemServicesIntegration");
        Constructor<?> ctor = clazz.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object integration = ctor.newInstance(null, Collections.emptyList());
        inject(clazz, integration, "injectedRepository", Mockito.mock(XRepository.class));
        inject(clazz, integration, "injectedResourceInstaller", Mockito.mock(ResourceInstaller.class));

        BundleContext syscontext = Mockito.mock(BundleContext.class);
        Method register = clazz.getDeclaredMethod("registerRepositoryServices", BundleContext.class);
        register.setAccessible(true);
        register.invoke(integration, syscontext);

        ArgumentCaptor<String[]> classes = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<Dictionary> props = ArgumentCaptor.forClass(Dictionary.class);
        Mockito.verify(syscontext).registerService(classes.capture(), Mockito.any(), props.capture());
        List<String> names = Arrays.asList(classes.getValue());
        assertTrue("Precondition: the XRepository is registered as " + Repository.class.getName() + ", names " + names,
                names.contains(Repository.class.getName()));
        Dictionary<String, ?> properties = props.getValue();
        assertNotNull("Expected the Repository service to be registered with the mandatory service.pid property "
                + "(Compendium 132.3, Table 132.1), but it was registered with no service properties at all", properties);
        assertNotNull("Expected the Repository service to be registered with the mandatory service.pid property "
                + "(Compendium 132.3, Table 132.1), but the properties are " + properties, properties.get(Constants.SERVICE_PID));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void inject(Class<?> clazz, Object target, String fieldName, Object value) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        ((InjectedValue) field.get(target)).inject(value);
    }
}
