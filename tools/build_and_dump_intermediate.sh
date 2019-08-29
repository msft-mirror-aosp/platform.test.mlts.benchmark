#!/bin/bash

# This script build and run DumpIntermediateTensors activity
# The results will be pulled to /tmp/intermediate by default.
# Usage
# build_and_dump_intermediate.sh [output directory] [name of the output folder] [build/ nobuild]

if [[ -z "$ANDROID_BUILD_TOP" ]]; then
  echo ANDROID_BUILD_TOP not set, bailing out
  echo you must run lunch before running this script
  exit 1
fi

INTERMEDIATE_OUTPUT_DIR="${1:-/tmp}"
CURRENTDATE=`date +"%m%d%y"`
RENAME="${2:-intermediate_$CURRENTDATE}"
BUILD_MODE="${3:-build}"

cd $ANDROID_BUILD_TOP

if [[ "$BUILD_MODE" == "build" ]]; then
  # Build and install benchmark app
  build/soong/soong_ui.bash --make-mode NeuralNetworksApiBenchmark
  if ! adb install -r $OUT/testcases/NeuralNetworksApiBenchmark/arm64/NeuralNetworksApiBenchmark.apk; then
    adb uninstall com.android.nn.benchmark.app
    adb install -r $OUT/testcases/NeuralNetworksApiBenchmark/arm64/NeuralNetworksApiBenchmark.apk
  fi
fi

# Default to run all public models in DumpIntermediateTensors
adb shell am start -n com.android.nn.benchmark.app/com.android.nn.benchmark.util.DumpIntermediateTensors --es inputAssetIndex 0 &&
# Wait for the files to finish writing.
# TODO(veralin): find a better way to wait, maybe some sort of callback
sleep 13 &&

mkdir -p $INTERMEDIATE_OUTPUT_DIR &&
cd $INTERMEDIATE_OUTPUT_DIR &&
rm -rf intermediate &&
adb pull /data/data/com.android.nn.benchmark.app/files/intermediate/ &&
rsync -a --delete intermediate/ $RENAME/ &&
echo "Results pulled to $INTERMEDIATE_OUTPUT_DIR/$RENAME"

exit