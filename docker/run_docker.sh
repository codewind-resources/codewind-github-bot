#!/bin/bash

export SCRIPT_LOCT=$( cd $( dirname $0 ); pwd )
cd $SCRIPT_LOCT

NUM_GAM_DATA_VOLUMES=`docker volume ls | grep "codewind-github-bot-data-volume" | wc -l`

set -e

if [ "$NUM_ZAM_DATA_VOLUMES" == "0" ]; then
    docker volume create codewind-github-bot-data-volume
fi
set +e

# Delete the old container, if applicable
docker rm -f codewind-github-bot-container > /dev/null 2>&1

set -e

docker run  -d  --name codewind-github-bot-container \
    -v codewind-github-bot-data-volume:/home/default/data \
    -v $1:/home/default/codewind-bot-config.yaml \
    -v $2:/home/default/authorized-users.yaml \
    --restart always \
    --cap-drop=all  \
    codewind-github-bot \
    /home/default/codewind-bot-config.yaml
