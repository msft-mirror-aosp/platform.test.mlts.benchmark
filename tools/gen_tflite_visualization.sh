#!/bin/bash

# Prereq:
# g4d -f NAME
# blaze build third_party/tensorflow/lite/tools:visualize

ANDROID_BUILD_TOP="/usr/local/google/home/$(whoami)/android/master"
MODEL_DIR="$ANDROID_BUILD_TOP/test/mlts/models/assets"
# The .json files are always output to /tmp
HTML_DIR="/tmp"

mkdir -p $HTML_DIR

for file in "$MODEL_DIR"/*.tflite
do
  if [ -f "$file" ]; then
    filename=`basename $file`
    modelname=${filename%.*}
    blaze-bin/third_party/tensorflow/lite/tools/visualize $file $HTML_DIR/$modelname.html
  fi
done

# Example visualization: blaze-bin/third_party/tensorflow/lite/tools/visualize ~/android/master/test/mlts/models/assets/mobilenet_v1_0.75_192.tflite /tmp/mobilenet_v1_0.75_192.html
