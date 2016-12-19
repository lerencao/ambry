#!/bin/sh

exec java \
     $JMX_OPTS \
     -Dlog4j.configuration=file:/ambry/config/log4j.properties \
     -cp "*" com.github.ambry.frontend.AmbryFrontendMain \
     --serverPropsFilePath /ambry/config/frontend.properties \
     --hardwareLayoutFilePath /ambry/config/HardwareLayout.json \
     --partitionLayoutFilePath /ambry/config/PartitionLayout.json

