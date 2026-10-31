#!/bin/bash

VERSION=$1
mkdir /tmp/TrboTAK-$VERSION
cp -r * /tmp/TrboTAK-$VERSION/
rm -rf /tmp/TrboTAK-$VERSION/build
rm -rf /tmp/TrboTAK-$VERSION/app/build
cd /tmp
zip -r TrboTAK-$VERSION.zip TrboTAK-$VERSION
