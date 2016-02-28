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
import java.util.Properties;

import org.jboss.arquillian.container.test.spi.TestDeployment;
import org.jboss.arquillian.container.test.spi.client.deployment.ProtocolArchiveProcessor;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.osgi.framework.Constants;

/**
 * @author <a href="mailto:arcadiy@ivanov.biz">Arcadiy Ivanov</a>
 */
public class JBOSGiProtocolArchiveProcessor implements ProtocolArchiveProcessor {

    @Override
    public void process(TestDeployment testDeployment, Archive<?> protocolArchive) {
        JavaArchive archive = protocolArchive.as(JavaArchive.class);

        // Add resource capabilities for registration with the Environment
        archive.addAsResource(new Asset() {
            public InputStream openStream() {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try {
                    Properties props = new Properties();
                    props.setProperty(Constants.BUNDLE_SYMBOLICNAME, "arquillian-service");
                    StringBuilder builder = new StringBuilder();
                    builder.append("org.jboss.arquillian.container.test.api,org.jboss.arquillian.junit,");
                    builder.append("org.jboss.arquillian.osgi,org.jboss.arquillian.test.api,");
                    builder.append("org.jboss.as.arquillian.api,org.jboss.as.arquillian.container,org.jboss.as.osgi,");
                    builder.append("org.jboss.shrinkwrap.api,org.jboss.shrinkwrap.api.asset,org.jboss.shrinkwrap.api.spec,");
                    builder.append("org.junit,org.junit.runner");
                    props.setProperty(Constants.EXPORT_PACKAGE, builder.toString());
                    props.store(baos, null);
                } catch (IOException ex) {
                    throw new IllegalStateException("Cannot write osgi metadata", ex);
                }
                return new ByteArrayInputStream(baos.toByteArray());
            }

        }, "META-INF/jbosgi-xservice.properties");
    }
}
