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

import org.jboss.arquillian.testenricher.osgi.BundleAssociation;
import org.jboss.arquillian.testenricher.osgi.BundleContextAssociation;
import org.jboss.as.arquillian.service.ArquillianConfig;
import org.jboss.as.arquillian.service.ArquillianConfigServiceCustomizer;
import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleClassLoader;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.value.InjectedValue;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleReference;

/**
 * @author <a href="mailto:arcadiy@ivanov.biz">Arcadiy Ivanov</a>
 */
public class JBOSGiArquillianConfigServiceCustomizer implements ArquillianConfigServiceCustomizer {

    private final InjectedValue<BundleContext> injectedBundleContext = new InjectedValue<BundleContext>();

    @Override
    public void customizeService(ArquillianConfig arquillianConfig, ServiceBuilder<ArquillianConfig> builder,
            ServiceController<?> depController) {
        builder.addDependency(ServiceName.parse("jbosgi.framework.CREATE"), BundleContext.class, injectedBundleContext);
    }

    @Override
    public void customizeLoadClass(DeploymentUnit depUnit, Class<?> testClass) {
        Module module = depUnit.getAttachment(Attachments.MODULE);
        ModuleClassLoader classLoader = module != null ? module.getClassLoader() : null;
        BundleContextAssociation.setBundleContext(injectedBundleContext.getValue());
        if (classLoader instanceof BundleReference) {
            BundleAssociation.setBundle(((BundleReference) classLoader).getBundle());
        }
    }
}
