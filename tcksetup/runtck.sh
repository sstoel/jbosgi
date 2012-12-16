if [ "$2" == "" ] 
then
    echo Please provide the OSGi TCK location and the jboss-as build location
    echo  $0 ~/git/org.osgi.build ~/git/jboss-as
    exit
fi

if [ ! -d "$1/osgi.ct" ]
then
    echo Please specify a valid TCK checkout directory.
    echo It must contain the osgi.ct subdirectory.
    exit 1
fi

if [ ! -d "$2/build/target" ]
then
    echo Please specify a valid jboss-as checkout directory.
    echo It must contain the build/target subdirectory.
    exit 1
fi

if [ ! -d "$2/osgi/launcher" ]
then
    echo Please specify a valid jboss-as checkout directory.
    echo It must contain the osgi/launcher subdirectory.
    exit 1
fi

export TCKCHECKOUT=$1
export JBOSS_AS_CHECKOUT=$2
export JBOSS_AS_VERSION=`ls $JBOSS_AS_CHECKOUT/build/target | grep jboss-as | cut -c10-100`
export JBOSS_AS_LOCATION=$JBOSS_AS_CHECKOUT/build/target/jboss-as-$JBOSS_AS_VERSION

export JBOSGI_RESOLVER_API=`find $JBOSS_AS_LOCATION | grep "\/jbosgi-resolver-api.*[.]jar$"`
export JBOSS_AS_CONTROLLER_CLIENT=`find $JBOSS_AS_LOCATION | grep "\/jboss-as-controller-client-$JBOSS_AS_VERSION[.]jar$"`
export JBOSS_AS_EMBEDDED=`find $JBOSS_AS_LOCATION | grep "\/jboss-as-embedded-$JBOSS_AS_VERSION[.]jar$"`
export JBOSS_AS_OSGI_LAUNCHER=`find $JBOSS_AS_CHECKOUT/osgi/launcher | grep "\/jboss-as-osgi-launcher-$JBOSS_AS_VERSION[.]jar$"`
export JBOSS_DMR=`find $JBOSS_AS_LOCATION | grep "\/jboss-dmr.*[.]jar$"`
export JBOSS_LOGGING=`find $JBOSS_AS_LOCATION | grep "\/jboss-logging.*[.]jar$"`
export JBOSS_LOGMANAGER=`find $JBOSS_AS_LOCATION | grep "\/jboss-logmanager.*[.]jar$"`
export JBOSS_MODULES=`find $JBOSS_AS_LOCATION | grep "\/jboss-modules.*[.]jar$"`
export JBOSS_MSC=`find $JBOSS_AS_LOCATION | grep "\/jboss-msc.*[.]jar$"`
export JBOSS_THREADS=`find $JBOSS_AS_LOCATION | grep "\/jboss-threads.*[.]jar$"`
export OSGI_CORE=`find $JBOSS_AS_LOCATION | grep "\/org[.]osgi[.]core.*[.]jar$"`

echo
echo JBOSS_AS_VERSION: $JBOSS_AS_VERSION
echo JBOSS_AS_LOCATION: $JBOSS_AS_LOCATION
echo JBOSS_AS_OSGI_LAUNCHER: $JBOSS_AS_OSGI_LAUNCHER
echo TCKCHECKOUT: $TCKCHECKOUT
echo

# Write the TCKCHECKOUT dir to ant.properties
echo "tck.checkout.dir=$TCKCHECKOUT" > ant.properties

# Setup the TCK
# The current script is known to work with the r4v42-enterprise-ri-ct-final tag of the osgi TCK repository
ant setup.vi

