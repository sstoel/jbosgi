JBoss OSGi
==========

This is the umbrella project for our OSGi effort. It is the home of the integration testsuite, the installer, documentation, etc.

Building From Source
--------------------

> git clone git://github.com/jbosgi/jbosgi.git

Setup the JBoss Maven Repository
--------------------------------

To use dependencies from JBoss.org, you need to add the JBoss Maven Repositories to your Maven settings.xml. For details see http://community.jboss.org/wiki/MavenGettingStarted-Users

Prerequisites for the WildFly OSGi subsystem
--------------------------------------------

The OSGi subsystem does not build against a stock WildFly. It needs the OSGi branches of the WildFly forks, which
add the deployment phases, attachments and module handling that the subsystem relies on

* https://github.com/sstoel/wildfly-core branch `33.0.1.Final-osgi`
* https://github.com/sstoel/wildfly branch `41.0.1.Final-osgi`

Build both with `mvn install -DskipTests` first. They install their artifacts under the versions of the WildFly
release that they are based on, so that these replace the stock artifacts in the local Maven repository.

The injection of the system bundle context with `@Resource BundleContext context` without a lookup name is part of
the WildFly fork as well. With `@Resource(lookup = "java:jboss/osgi/BundleContext")` it does not depend on that.

The sibling repositories (jbosgi-parent, jbosgi-vfs, jbosgi-metadata, jbosgi-spi, jbosgi-testing, jbosgi-deployment,
jbosgi-resolver, jbosgi-repository, jbosgi-provision, jbosgi-logging, jbosgi-framework) have to be installed in that order
before this project is built.

The tests start a WildFly server that uses the management port 9990 and connect to a server that is already running
on that port. Use another port when it is taken

<pre>
$ mvn clean install -Darq.container.jboss.configuration.managementPort=19990 -Darq.container.jboss.configuration.javaVmArguments=-Djboss.management.http.port=19990
</pre>

Build with Maven
----------------

The command below builds all modules and runs the embedded suite.

<pre>
$ mvn -Dembedded clean install
...
[INFO] ------------------------------------------------------------------------
[INFO] Reactor Summary:
[INFO] 
[INFO] JBossOSGi ......................................... SUCCESS [0.801s]
[INFO] JBossOSGi Testsuite ............................... SUCCESS [0.201s]
[INFO] JBossOSGi Testsuite Examples ...................... SUCCESS [15.912s]
[INFO] JBossOSGi Testsuite Functional .................... SUCCESS [4.984s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time: 22.504s
[INFO] Finished at: Mon Jul 29 10:49:01 CEST 2013
[INFO] Final Memory: 26M/201M
[INFO] ------------------------------------------------------------------------
</pre>

Build/Test the WildFly OSGi subsystem
-------------------------------------

To build the OSGi subsystem specify the target container like this

<pre>
$ mvn clean install
...
[INFO] ------------------------------------------------------------------------
[INFO] Reactor Summary:
[INFO] 
[INFO] JBossOSGi ......................................... SUCCESS [0.386s]
[INFO] JBossOSGi WildFly ................................. SUCCESS [0.016s]
[INFO] JBossOSGi WildFly Subsystem ....................... SUCCESS [11.367s]
[INFO] JBossOSGi WildFly Integration ..................... SUCCESS [0.013s]
[INFO] JBossOSGi WildFly Integration: Configadmin ........ SUCCESS [3.443s]
[INFO] JBossOSGi WildFly Integration: Http ............... SUCCESS [1.308s]
[INFO] JBossOSGi WildFly Integration: JMX ................ SUCCESS [0.150s]
[INFO] JBossOSGi WildFly Integration: JPA ................ SUCCESS [1.445s]
[INFO] JBossOSGi WildFly Integration: JTA ................ SUCCESS [0.159s]
[INFO] JBossOSGi WildFly Integration: Naming ............. SUCCESS [0.243s]
[INFO] JBossOSGi WildFly Integration: WebApp ............. SUCCESS [0.359s]
[INFO] JBossOSGi WildFly Build ........................... SUCCESS [17.692s]
[INFO] JBossOSGi Testsuite ............................... SUCCESS [1.388s]
[INFO] JBossOSGi Testsuite Examples ...................... SUCCESS [37.201s]
[INFO] JBossOSGi Testsuite Functional .................... SUCCESS [14.247s]
[INFO] JBossOSGi Testsuite Integration ................... SUCCESS [51.954s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time: 2:22.915s
[INFO] Finished at: Mon Jul 29 10:53:23 CEST 2013
[INFO] Final Memory: 79M/254M
[INFO] ------------------------------------------------------------------------
</pre>
