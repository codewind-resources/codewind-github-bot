#!/bin/bash

export SCRIPT_LOCT=$( cd $( dirname $0 ); pwd )
cd $SCRIPT_LOCT

docker build -t codewind-github-bot --file $SCRIPT_LOCT/Dockerfile $SCRIPT_LOCT/.. 

