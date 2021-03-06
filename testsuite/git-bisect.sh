#!/bin/sh
#
# Usage
#
# git bisect start [badrev] [goodrev]
# git bisect run ./git-bisect.sh
#
# @author thomas.diesler@jboss.com
# @since 15-Sep-2013

JBOSGI_HOME="$HOME/git/jbosgi"
WILDFLY_HOME="$HOME/git/wildfly"

cd $WILDFLY_HOME
mvn -DskipTests clean install | grep "Building WildFly\|BUILD"

cd $JBOSGI_HOME
mvn -Dtest=ArquillianDeployerTestCase clean install | tee mvn.out

MVN_RESULT=`cat mvn.out | grep -o "BUILD SUCCESS\|BUILD FAILURE"`
echo $MVN_RESULT

if [ "$MVN_RESULT" = "BUILD SUCCESS" ]; then
   exit 0
else
   exit 1
fi

