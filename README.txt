Copyright 2017 The Android Open Source Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
------------------------------------------------------------------

This directory contains files for the Android MLTS (Machine Learning
Test Suite). MLTS allows to evaluate NNAPI acceleration latency and accuracy
on an Android device, using few selected ML models and datesets.

Models and datasets used description and licensing can be found in
platform/test/mlts/models/README.txt file.

Usage:
* Connect a target device to your workstation, make sure it's
reachable through adb. Export target device ANDROID_SERIAL
environment variable if more than one device is connected.
* cd into android top-level source directory
> source build/envsetup.sh
> lunch aosp_arm-userdebug # Or aosp_arm64-userdebug if available.
> ./test/mlts/benchmark/build_and_run_benchmark.sh
* At the end of a benchmark run, its results will be
presented as html page, passed to xdg-open.

# Crash test

The MLTS suite contains a series of tests to validate the behaviour of the drivers under stress or
in corner case conditions.

To run the tests use the specific targets available in the build_and_run_benchmark.sh script.
Currently available ones are:

- parallel-inference-stress: to test the behaviour of drivers with different amount of inference
executed in parallel. Tests are running in a separate process so crashes can be detected and
notified as test failures

- parallel-inference-stress-in-process: same as parallel-inference-stress but the tests are running
in the same process of the test so in case of crash the testing app will crash too
