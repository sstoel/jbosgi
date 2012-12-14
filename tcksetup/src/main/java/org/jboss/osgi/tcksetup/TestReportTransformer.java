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
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;

/**
 * Generate standard test reports from legacy bnd output file
 * 
 * @author Thomas.Diesler@jboss.com
 * @since 13-Dec-2012
 */
public class TestReportTransformer {

    private final File inputFile;
    private final File outputDir;

    /**
     * arg[0] - bnd report 
     * arg[1] - output directory
     */
    public static void main(String[] args) throws Exception {
        if (args == null)
            throw new IllegalArgumentException("Null args");
        if (args.length < 2)
            throw new IllegalArgumentException("Invalid args: " + Arrays.asList(args));

        int index = 0;
        if (args[index] == null || args[index].isEmpty()) {
            throw new IllegalArgumentException("No input file");
        }
        File inputFile = new File(args[index++]);

        if (args[index] == null || args[index].isEmpty()) {
            throw new IllegalArgumentException("No output directory");
        }
        File outputDir = new File(args[index]);

        new TestReportTransformer(inputFile, outputDir).process();
    }

    public TestReportTransformer(File inputFile, File outputDir) {
        this.inputFile = inputFile;
        this.outputDir = outputDir;
    }

    public void process() throws Exception {
        outputDir.mkdirs();
        
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Element doc = builder.parse(inputFile).getDocumentElement();
        NodeList childNodes = doc.getElementsByTagName("test");

        TestSuiteInfo current = null;
        List<TestSuiteInfo> suites = new ArrayList<TestSuiteInfo>();
        for (int i = 0; i < childNodes.getLength(); i++) {
            Element item = (Element) childNodes.item(i);
            NamedNodeMap atts = item.getAttributes();
            String testName = atts.getNamedItem("name").getNodeValue();
            String testClass = atts.getNamedItem("class").getNodeValue();
            if (current == null || !current.name.equals(testClass)) {
                current = new TestSuiteInfo(testClass);
                Properties sysprops = System.getProperties();
                for (Entry<Object, Object> entry : sysprops.entrySet()) {
                    PropertyInfo prop = new PropertyInfo((String)entry.getKey(), (String)entry.getValue());
                    current.addProperty(prop);
                }
                suites.add(current);
            }
            NodeList errors = item.getElementsByTagName("error");
            NodeList failures = item.getElementsByTagName("failure");
            NodeList skipped = item.getElementsByTagName("skipped");
            
            TestInfo test;
            if (errors.getLength() > 0) {
                Element failure = (Element) errors.item(0);
                NamedNodeMap fatts = failure.getAttributes();
                test = new TestInfo(testClass, testName, TestInfo.Type.error);
                test.failureMessage = fatts.getNamedItem("name").getNodeValue();
                test.failureType = fatts.getNamedItem("type").getNodeValue();
                test.failureBody = failure.getTextContent();
            } else if (failures.getLength() > 0) {
                Element failure = (Element) failures.item(0);
                NamedNodeMap fatts = failure.getAttributes();
                test = new TestInfo(testClass, testName, TestInfo.Type.failure);
                test.failureMessage = fatts.getNamedItem("name").getNodeValue();
                test.failureType = fatts.getNamedItem("type").getNodeValue();
                test.failureBody = failure.getTextContent();
            } else if (skipped.getLength() > 0) {
                test = new TestInfo(testClass, testName, TestInfo.Type.skipped);
            } else {
                test = new TestInfo(testClass, testName, TestInfo.Type.good);
            }
            current.addTest(test);
        }
        
        for (TestSuiteInfo suite : suites) {
            File outputFile = new File(outputDir + File.separator + "TEST-" + suite.name + ".xml");
            PrintWriter out = new PrintWriter(new FileWriter(outputFile));
            suite.print(out);
            out.close();
        }
    }

    static class TestSuiteInfo {
        final List<PropertyInfo> properties = new ArrayList<PropertyInfo>();
        final List<TestInfo> tests = new ArrayList<TestInfo>();
        final String name;
        long totalTime;
        int totalFailures;
        int totalErrors;
        int totalSkipped;
        int totalTests;

        TestSuiteInfo(String name) {
            this.name = name;
        }
        
        void addProperty(PropertyInfo prop) {
            properties.add(prop);
        }
        
        void addTest(TestInfo test) {
            tests.add(test);
            totalTime += test.testTime;
            if (test.type == TestInfo.Type.error) {
                totalErrors++;
            } else if (test.type == TestInfo.Type.failure) {
                totalFailures++;
            } else if (test.type == TestInfo.Type.skipped) {
                totalSkipped++;
            }
            totalTests++;
        }

        void print(PrintWriter out) {
            out.println("<?xml version='1.0' encoding='UTF-8' ?>");
            out.print("<testsuite");
            out.print(" time='" + ((double)totalTime/1000) + "'");
            out.print(" failures='" + totalFailures + "'");
            out.print(" errors='" + totalErrors + "'");
            out.print(" skipped='" + totalSkipped + "'");
            out.print(" tests='" + totalTests + "'");
            out.println(" name='" + name + "'>");
            out.println("<properties>");
            for (PropertyInfo prop : properties) {
                prop.print(out);
            }
            out.println("</properties>");
            for (TestInfo test : tests) {
                test.print(out);
            }
            out.println("</testsuite>");
        }

    }
    
    static class TestInfo {
        enum Type { failure, error, skipped, good }
        final String classname;
        final String name;
        final Type type;
        long testTime = 100;
        String failureMessage;
        String failureType;
        String failureBody;

        TestInfo(String classname, String name, Type type) {
            this.classname = classname;
            this.name = name;
            this.type = type;
        }

        void print(PrintWriter out) {
            if (failureType != null && failureType.indexOf(":") > 0) {
                failureType = failureType.substring(0, failureType.indexOf(":"));
            }
            out.print("<testcase");
            out.print(" time='" + ((double)testTime/1000) + "'");
            out.print(" classname='" + classname + "'");
            out.print(" name='" + name + "'");
            if (type == Type.error) {
                out.println(">");
                out.println("<error message='" + failureMessage + "' type='" + failureType + "'>");
                out.println("<![CDATA[" + failureBody + "]]>");
                out.println("</error>");
                out.println("</testcase>");
            } else if (type == Type.failure) {
                out.println(">");
                out.println("<failure message='" + failureMessage + "' type='" + failureType + "'>");
                out.println("<![CDATA[" + failureBody + "]]>");
                out.println("</failure>");
                out.println("</testcase>");
            } else if (type == Type.skipped) {
                out.println(">");
                out.println("<skipped/>");
                out.println("</testcase>");
            } else {
                out.println("/>");
                
            }
        }

        @Override
        public String toString() {
            return "TestInfo [name=" + name + ", type=" + type + "]";
        }
    }
    
    static class PropertyInfo {
        final String name;
        final String value;

        PropertyInfo(String name, String value) {
            this.name = name;
            this.value = value;
        }

        void print(PrintWriter out) {
            out.println("<property name='" + name + "' value='" + value + "'/>");
        }
    }
}