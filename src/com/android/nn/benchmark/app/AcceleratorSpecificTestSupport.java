/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.nn.benchmark.app;

import android.content.Context;
import android.util.Log;

import com.android.nn.benchmark.core.BenchmarkException;
import com.android.nn.benchmark.core.Processor;
import com.android.nn.benchmark.core.TestModels;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

public interface AcceleratorSpecificTestSupport {
    String TAG = "AcceleratorTest";

    default Optional<TestModels.TestModelEntry> findTestModelRunningOnAccelerator(
            Context context, String acceleratorName) {
        return TestModels.modelsList().stream()
                .filter(
                        model -> Processor.isTestModelSupportedByAccelerator(
                                context,
                                model, acceleratorName)).findAny();
    }

    default long ramdomInRange(long min, long max) {
        return min + (long) (Math.random() * (max - min));
    }

    class DriverLivenessChecker implements Callable<Boolean> {
        final Processor mProcessor;
        private final AtomicBoolean mRun = new AtomicBoolean(true);
        private final TestModels.TestModelEntry mTestModelEntry;

        public DriverLivenessChecker(Context context, String acceleratorName,
                TestModels.TestModelEntry testModelEntry) {
            mProcessor = new Processor(context,
                    new Processor.Callback() {
                        @Override
                        public void onBenchmarkFinish(boolean ok) {
                        }

                        @Override
                        public void onStatusUpdate(int testNumber, int numTests, String modelName) {
                        }
                    }, new int[0]);
            mProcessor.setUseNNApi(true);
            mProcessor.setCompleteInputSet(false);
            mProcessor.setNnApiAcceleratorName(acceleratorName);
            mTestModelEntry = testModelEntry;
        }

        public void stop() {
            mRun.set(false);
        }

        @Override
        public Boolean call() throws Exception {
            while (mRun.get()) {
                try {
                    mProcessor.getInstrumentationResult(mTestModelEntry, 0, 3);
                } catch (IOException | BenchmarkException e) {
                    Log.e(TAG, String.format("Error running model %s", mTestModelEntry.mModelName));
                }
            }

            return true;
        }
    }
}