#!/bin/bash

export SCRIPT_LOCT=$( cd $( dirname $0 ); pwd )
cd $SCRIPT_LOCT

PATH_TO_CONFIG_YAML=(fill in this value)

./run_docker.sh $PATH_TO_CONFIG_YAML
