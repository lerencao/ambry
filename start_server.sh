#!/bin/sh

exec java \
     $JMX_OPTS \
     -Dlog4j.configuration=file:/ambry/config/log4j.properties \
     -jar ambry.jar \
     --serverPropsFilePath /ambry/config/server.properties \
     --hardwareLayoutFilePath /ambry/config/HardwareLayout.json \
     --partitionLayoutFilePath /ambry/config/PartitionLayout.json

