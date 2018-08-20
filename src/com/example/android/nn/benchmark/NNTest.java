/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.example.android.nn.benchmark;


import android.app.Activity;
import android.os.Bundle;
import android.os.Trace;
import android.support.test.InstrumentationRegistry;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;

import com.android.nn.benchmark.core.BenchmarkException;
import com.android.nn.benchmark.core.BenchmarkResult;

import com.android.nn.benchmark.core.TestModels;
import com.android.nn.benchmark.core.TestModels.TestModelEntry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.io.IOException;
import java.util.List;

/**
 * NNAPI benchmark test.
 * To run the test, please use command
 *
 * adb shell am instrument -w
 * com.example.android.nn.benchmark/android.support.test.runner.AndroidJUnitRunner
 *
 * To run only one model, please run:
 * adb shell am instrument
 * -e class "com.example.android.nn.benchmark.NNTest#testNNAPI[MODEL_NAME]"
 * -w com.example.android.nn.benchmark/android.support.test.runner.AndroidJUnitRunner
 *
 */
@RunWith(Parameterized.class)
public class NNTest extends ActivityInstrumentationTestCase2<NNBenchmark> {
    // Only run 1 iteration now to fit the MediumTest time requirement.
    // One iteration means running the tests continuous for 1s.
    private NNBenchmark mActivity;
    protected final TestModelEntry mModel;

    // The default 0.3s warmup and 1.0s runtime give reasonably repeatable results (run-to-run
    // variability of ~20%) when run under performance settings (fixed CPU cores enabled and at
    // fixed frequency). The continuous build is not allowed to take much more than 1s so we
    // can't change the defaults for @MediumTest.
    private static final float WARMUP_SHORT_SECONDS = 0.3f;
    private static final float RUNTIME_SHORT_SECONDS = 1.f;
    // For running like a normal user-initiated app, the variability for 0.3s/1.0s is easily 3x.
    // With 2s/10s it's 20-50%. This @LargeTest allows running with these timings.
    protected static final float WARMUP_REPEATABLE_SECONDS = 2.f;
    protected static final float RUNTIME_REPEATABLE_SECONDS = 10.f;

    public NNTest(TestModelEntry model) {
        super(NNBenchmark.class);
        mModel = model;
    }

    // Initialize the parameter for ImageProcessingActivityJB.
    protected void prepareTest() {
        injectInstrumentation(InstrumentationRegistry.getInstrumentation());
        mActivity = getActivity();
        mActivity.prepareInstrumentationTest();
    }

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        prepareTest();
        setActivityInitialTouchMode(false);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    class TestAction implements Runnable {
        TestModelEntry mTestModel;
        BenchmarkResult mResult;
        float mWarmupTimeSeconds;
        float mRunTimeSeconds;
        boolean mNoNNAPI;

        public TestAction(TestModelEntry testName) {
            mTestModel = testName;
            mNoNNAPI = false;
        }
        public TestAction(TestModelEntry testName, float warmupTimeSeconds, float runTimeSeconds,
                          boolean noNNAPI) {
            mTestModel = testName;
            mWarmupTimeSeconds = warmupTimeSeconds;
            mRunTimeSeconds = runTimeSeconds;
            mNoNNAPI = noNNAPI;
        }

        public void run() {
            try {
                mResult = mActivity.mProcessor.getInstrumentationResult(
                    mTestModel, mWarmupTimeSeconds, mRunTimeSeconds, mNoNNAPI);
            } catch (IOException | BenchmarkException e) {
                e.printStackTrace();
            }
            Log.v(NNBenchmark.TAG,
                    "Benchmark for test \"" + mTestModel.toString() + "\" is: " + mResult);
            synchronized (this) {
                this.notify();
            }
        }

        public BenchmarkResult getBenchmark() {
            return mResult;
        }
    }

    // Set the benchmark thread to run on ui thread
    // Synchronized the thread such that the test will wait for the benchmark thread to finish
    public void runOnUiThread(Runnable action) {
        synchronized (action) {
            mActivity.runOnUiThread(action);
            try {
                action.wait();
            } catch (InterruptedException e) {
                Log.v(NNBenchmark.TAG, "waiting for action running on UI thread is interrupted: " +
                        e.toString());
            }
        }
    }

    public void runTest(TestAction ta, String testName) {
        float sum = 0;
        // For NNAPI systrace usage documentation, see
        // frameworks/ml/nn/common/include/Tracing.h.
        final String traceName = "[NN_LA_PO]" + testName;
        try {
            Trace.beginSection(traceName);
            runOnUiThread(ta);
        } finally {
            Trace.endSection();
        }
        BenchmarkResult bmValue = ta.getBenchmark();

        // post result to INSTRUMENTATION_STATUS
        Bundle results = new Bundle();
        // Reported in ms
        results.putFloat(testName + "_avg", bmValue.getMeanTimeSec() * 1000.0f);
        results.putFloat(testName + "_std_dev", bmValue.mTimeStdDeviation * 1000.0f);
        results.putFloat(testName + "_total_time", bmValue.mTotalTimeSec * 1000.0f);
        results.putFloat(testName + "_mean_square_error", bmValue.mSumOfMSEs / bmValue.mIterations);
        results.putFloat(testName + "_max_single_error", bmValue.mMaxSingleError);
        results.putInt(testName + "_iterations", bmValue.mIterations);
        getInstrumentation().sendStatus(Activity.RESULT_OK, results);
    }

    @Parameters(name = "{0}")
    public static List<TestModelEntry> modelsList() {
        return TestModels.modelsList();
    }

    @Test
    @MediumTest
    public void testNNAPI() {
        TestAction ta = new TestAction(mModel, WARMUP_SHORT_SECONDS, RUNTIME_SHORT_SECONDS, false);
        runTest(ta, mModel.getTestName());
    }

    @Test
    @LargeTest
    public void testNNAPI10Seconds() {
        TestAction ta = new TestAction(mModel, WARMUP_REPEATABLE_SECONDS,
            RUNTIME_REPEATABLE_SECONDS, false);
        runTest(ta, mModel.getTestName());
    }
}
