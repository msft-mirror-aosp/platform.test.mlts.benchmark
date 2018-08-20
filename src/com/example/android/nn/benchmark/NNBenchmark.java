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
import android.content.Intent;
import android.os.Bundle;
import android.os.Trace;
import android.util.Log;
import android.widget.TextView;

import com.android.nn.benchmark.core.BenchmarkException;
import com.android.nn.benchmark.core.BenchmarkResult;
import com.android.nn.benchmark.core.InferenceResult;
import com.android.nn.benchmark.core.NNTestBase;
import com.android.nn.benchmark.core.TestModels;

import java.util.List;
import java.io.IOException;

public class NNBenchmark extends Activity {
    protected static final String TAG = "NN_BENCHMARK";

    private int mTestList[];
    private BenchmarkResult mTestResults[];

    private TextView mTextView;
    private boolean mToggleLong;
    private boolean mTogglePause;

    // In demo mode this is used to count updates in the pipeline.  It's
    // incremented when work is submitted to RS and decremented when invalidate is
    // called to display a result.
    private boolean mDemoMode;

    // Initialize the parameters for Instrumentation tests.
    protected void prepareInstrumentationTest() {
        mTestList = new int[1];
        mTestResults = new BenchmarkResult[1];
        mDemoMode = false;
        mProcessor = new Processor(!mDemoMode);
    }

    /////////////////////////////////////////////////////////////////////////
    // Processor is a helper thread for running the work without
    // blocking the UI thread.
    class Processor extends Thread {

        private float mLastResult;
        private boolean mRun = true;
        private boolean mDoingBenchmark;
        private NNTestBase mTest;

        private boolean mBenchmarkMode;

        Processor(boolean benchmarkMode) {
            mBenchmarkMode = benchmarkMode;
        }

        // Method to retreive benchmark results for instrumentation tests.
        BenchmarkResult getInstrumentationResult(
            TestModels.TestModelEntry t, float warmupTimeSeconds, float runTimeSeconds,
            boolean noNNAPI)
                throws BenchmarkException, IOException {
            mTest = changeTest(t);
            return getBenchmark(warmupTimeSeconds, runTimeSeconds, noNNAPI);
        }

        // Run one loop of kernels for at least the specified minimum time.
        // The function returns the average time in ms for the test run
        private BenchmarkResult runBenchmarkLoop(float minTime, boolean noNNAPI)
                throws BenchmarkException, IOException {
            // Run the kernel
            List<InferenceResult> inferenceResults = mTest.runBenchmark(minTime, noNNAPI);
            return BenchmarkResult.fromInferenceResults(mTest.getTestInfo(), inferenceResults);
        }


        // Get a benchmark result for a specific test
        private BenchmarkResult getBenchmark(float warmupTimeSeconds, float runTimeSeconds,
            boolean noNNAPI)
                throws BenchmarkException, IOException {
            mDoingBenchmark = true;

            long result = 0;

            // We run a short bit of work before starting the actual test
            // this is to let any power management do its job and respond.
            // For NNAPI systrace usage documentation, see
            // frameworks/ml/nn/common/include/Tracing.h.
            try {
                final String traceName = "[NN_LA_PWU]runBenchmarkLoop";
                Trace.beginSection(traceName);
                runBenchmarkLoop(warmupTimeSeconds, noNNAPI);
            } finally {
                Trace.endSection();
            }

            // Run the actual benchmark
            BenchmarkResult r;
            try {
                final String traceName = "[NN_LA_PBM]runBenchmarkLoop";
                Trace.beginSection(traceName);
                r = runBenchmarkLoop(runTimeSeconds, noNNAPI);
            } finally {
                Trace.endSection();
            }

            Log.v(TAG, "Test: " + r.toString());

            mDoingBenchmark = false;
            return r;
        }

        @Override
        public void run() {
            while (mRun) {
                // Our loop for launching tests or benchmarks
                synchronized (this) {
                    // We may have been asked to exit while waiting
                    if (!mRun) return;
                }

                try {
                    if (mBenchmarkMode) {
                        // Loop over the tests we want to benchmark
                        for (int ct = 0; (ct < mTestList.length) && mRun; ct++) {

                            // For reproducibility we wait a short time for any sporadic work
                            // created by the user touching the screen to launch the test to pass.
                            // Also allows for things to settle after the test changes.
                            try {
                                sleep(250);
                            } catch (InterruptedException e) {
                            }

                            // If we just ran a test, we destroy it here to relieve some memory
                            // pressure

                            if (mTest != null) {
                                mTest.destroy();
                            }

                            // Select the next test
                            mTest = changeTest(mTestList[ct]);
                            // If the user selected the "long pause" option, wait
                            if (mTogglePause) {
                                for (int i = 0; (i < 100) && mRun; i++) {
                                    try {
                                        sleep(100);
                                    } catch (InterruptedException e) {
                                    }
                                }
                            }

                            // Run the test
                            float warmupTime = 0.3f;
                            float runTime = 1.f;
                            if (mToggleLong) {
                                warmupTime = 2.f;
                                runTime = 10.f;
                            }
                            mTestResults[ct] = getBenchmark(warmupTime, runTime, false);
                        }
                        onBenchmarkFinish(mRun);
                    } else {
                        // Run the kernel
                        mTest.runOneInference();
                    }
                } catch (IOException | BenchmarkException e) {
                    e.printStackTrace();
                    break;
                }
            }
        }

        public void exit() {
            mRun = false;

            synchronized (this) {
                notifyAll();
            }

            try {
                this.join();
            } catch (InterruptedException e) {
            }

            if (mTest != null) {
                mTest.destroy();
                mTest = null;
            }
        }
    }


    private boolean mDoingBenchmark;
    public Processor mProcessor;

    NNTestBase changeTest(TestModels.TestModelEntry t) {
        NNTestBase tb = t.createNNTestBase();
        tb.createBaseTest(this);
        return tb;
    }

    NNTestBase changeTest(int id) {
        return changeTest(TestModels.modelsList().get(id));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView textView = new TextView(this);
        textView.setTextSize(20);
        textView.setText("NN BenchMark Running.");
        setContentView(textView);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mProcessor != null) {
            mProcessor.exit();
        }
    }

    public void onBenchmarkFinish(boolean ok) {
        if (ok) {
            Intent intent = new Intent();
            intent.putExtra("tests", mTestList);
            intent.putExtra("results", mTestResults);
            setResult(RESULT_OK, intent);
        } else {
            setResult(RESULT_CANCELED);
        }
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Intent i = getIntent();
        mTestList = i.getIntArrayExtra("tests");

        mToggleLong = i.getBooleanExtra("enable long", false);
        mTogglePause = i.getBooleanExtra("enable pause", false);
        mDemoMode = i.getBooleanExtra("demo", false);

        if (mTestList != null) {
            mTestResults = new BenchmarkResult[mTestList.length];
            mProcessor = new Processor(!mDemoMode);
            if (mDemoMode) {
                mProcessor.mTest = changeTest(mTestList[0]);
            }
            mProcessor.start();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
