#!/bin/bash
# 
# The TCK build.xml fails hard and prevents further test report processing 
#

tname=$1
 
echo "ANT: Running $tname task..."
ant -Dtck.section=$2 $tname
 
antReturnCode=$?
 
echo "ANT: Return code is: \""$antReturnCode"\""
 
# Here we exit with 0 even when ant failed
#
if [ $antReturnCode -ne 0 ];then
    echo "BUILD ERROR"
    exit 0;
else
    echo "BUILD SUCCESS"
    exit 0;
fi
