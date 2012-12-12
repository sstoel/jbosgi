if [ "$4" == "" ] 
then
    echo Please provide the OSGi TCK location, the jboss-as build location, and the
    echo location of the jboss-as-osgi-launcher.jar file, e.g.
    echo  $0 ./osgitck/ /jboss-as/jboss-as-7.2.0 /jboss-as/osgi/launcher/target/jboss-as-osgi-launcher-7.2.0.jar run-httpservice-tests
else
    if [ ! -d "$2/modules" ]
    then
        echo Please specify a valid jboss-as build directory.
        echo It must contain the modules subdirectory.
    else 

pushd `dirname $0`
CURDIR=`pwd`
popd

export TCKCHECKOUT=$1
export JBOSS_AS_LOCATION=$2
export JBOSS_OSGI_LAUNCHER=$3

export JBOSS_AS_EMBEDDED=`find $JBOSS_AS_LOCATION | grep "\/jboss-as-embedded.*[.]jar$"`
export JBOSS_MODULES=`find $JBOSS_AS_LOCATION | grep "\/jboss-modules.*[.]jar$"`
export JBOSS_LOGGING=`find $JBOSS_AS_LOCATION | grep "\/jboss-logging.*[.]jar$"`
export JBOSS_AS_CONTROLLER_CLIENT=`find $JBOSS_AS_LOCATION | grep "\/jboss-as-controller-client.*[.]jar$"`
export JBOSS_LOGMANAGER=`find $JBOSS_AS_LOCATION | grep "\/jboss-logmanager.*[.]jar$"`
export JBOSS_DMR=`find $JBOSS_AS_LOCATION | grep "\/jboss-dmr.*[.]jar$"`
export JBOSS_MSC=`find $JBOSS_AS_LOCATION | grep "\/jboss-msc.*[.]jar$"`
export JBOSS_OSGI_CORE=`find $JBOSS_AS_LOCATION | grep "\/org[.]osgi[.]core.*[.]jar$"`

echo TCK Checkout directory: $TCKCHECKOUT
echo JBOSS_AS_LOCATION: $JBOSS_AS_LOCATION

# Setup the TCK
# The current script is known to work with the r4v42-enterprise-ri-ct-final tag of the osgi TCK repository
ant setup.vi

# Run the actual tests
ant $4

fi
fi
