#!/bin/bash

export SCRIPT_LOCT=$( cd $( dirname $0 ); pwd )
cd $SCRIPT_LOCT

EXTRACT_DIR=`mktemp -d`

echo "Building in $EXTRACT_DIR"

cp -R ../* $EXTRACT_DIR

cd $EXTRACT_DIR

cp -R ~/.ssh $EXTRACT_DIR


if [ -n "$GHAM_TARGET_REPO" ]; then
        BUILD_ARGS="--build-arg GHAM_TARGET_REPO=$GHAM_TARGET_REPO $BUILD_ARGS"
fi

if [ -n "$GHAM_TARGET_VERSION" ]; then
        BUILD_ARGS="--build-arg GHAM_TARGET_VERSION=$GHAM_TARGET_VERSION $BUILD_ARGS"
fi


if [ -n "$ZHAM_TARGET_REPO" ]; then
        BUILD_ARGS="--build-arg ZHAM_TARGET_REPO=$ZHAM_TARGET_REPO $BUILD_ARGS"
fi

if [ -n "$ZHAM_TARGET_VERSION" ]; then
        BUILD_ARGS="--build-arg ZHAM_TARGET_VERSION=$ZHAM_TARGET_VERSION $BUILD_ARGS"
fi


docker build $BUILD_ARGS -t codewind-github-bot --file $SCRIPT_LOCT/Dockerfile $EXTRACT_DIR




