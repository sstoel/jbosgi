/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat Middleware LLC, and individual contributors
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
package org.jboss.osgi.tcksetup;

import java.io.File;
import java.net.URL;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;


/**
 * Generate standard test reports from legacy bnd output file
 *
 * @author Thomas.Diesler@jboss.com
 * @since 13-Dec-2012
 */
public class TestReportTransformerTestCase {

	@Test
	public void testGood() throws Exception {
		URL url = getClass().getResource("/org.osgi.test.cases.http.xml");
		Assert.assertNotNull(url);
		
		File inputFile = new File(url.getPath());
		File targetDir = new File("target/generated");
		TestReportTransformer transformer = new TestReportTransformer(inputFile, targetDir);
		transformer.process();
		
	}

    //@Test
    public void testFailure() throws Exception {
        Assert.assertTrue("Expect true", false);
    }

    //@Test
    public void testError() throws Exception {
        throw new RuntimeException("message");
    }
    
    @Test
    @Ignore
    public void testSkipped() throws Exception {
    }
}