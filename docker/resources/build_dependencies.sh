#!/bin/bash

set -eo pipefail

cd /root
if [ -n "$GHAM_TARGET_REPO" ]; then
    git clone "$GHAM_TARGET_REPO"
else
    git clone https://github.com/jgwest/github-api-mirror
fi
cd github-api-mirror
if [ -n "$GHAM_TARGET_VERSION" ]; then
    git checkout "$GHAM_TARGET_VERSION"
fi
mvn --projects GitHubApiMirrorClient  --also-make install


cd /root
git clone https://github.com/jgwest/zenhub-api-java-client/
cd zenhub-api-java-client
mvn install -DskipTests


cd /root
if [ -n "$ZHAM_TARGET_REPO" ]; then
    git clone "$ZHAM_TARGET_REPO"
else
    git clone https://github.com/jgwest/zenhub-api-mirror
fi
cd zenhub-api-mirror
if [ -n "$GHAM_TARGET_VERSION" ]; then
    git checkout "$GHAM_TARGET_VERSION"
fi
mvn --projects ZenHubApiMirrorClient  --also-make install

