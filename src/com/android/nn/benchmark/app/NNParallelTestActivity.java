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

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import com.android.nn.benchmark.crashtest.CrashTestCoordinator;
import com.android.nn.benchmark.crashtest.CrashTestCoordinator.CrashTestCompletionListener;
import com.android.nn.benchmark.crashtest.test.RunModelsInParallel;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static com.android.nn.benchmark.app.NNParallelTestActivity.TestResult.CRASH;
import static com.android.nn.benchmark.app.NNParallelTestActivity.TestResult.FAILURE;
import static com.android.nn.benchmark.app.NNParallelTestActivity.TestResult.HANG;
import static com.android.nn.benchmark.app.NNParallelTestActivity.TestResult.SUCCESS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;


public class NNParallelTestActivity extends Activity {
    String TAG = "NN_BENCHMARK";

    public static String EXTRA_TEST_DURATION_MILLIS = "duration";
    public static String EXTRA_THREAD_COUNT = "thread count";
    public static String EXTRA_TEST_LIST = "test list";
    public static String EXTRA_RUN_IN_SEPARATE_PROCESS = "run in separate process";


    public static enum TestResult {
        SUCCESS,
        FAILURE,
        CRASH,
        HANG
    }

    // Not using AtomicBoolean to have the concept of unset status
    private AtomicReference<TestResult> mTestResult = new AtomicReference<TestResult>(null);
    private CountDownLatch mParallelTestComplete = new CountDownLatch(1);
    private CrashTestCoordinator coordinator;
    private TextView mTestResultView;
    private Button mStopTestButton;
    private CrashTestCompletionListener testCompletionListener = new CrashTestCompletionListener() {
        private void handleCompletionNotification(TestResult testResult, String reason) {
            Log.d(TAG,
                    String.format("Received crashed notification: %s and extra msg %s.", testResult,
                            reason));
            if (mTestResult.compareAndSet(null, testResult)) {
                if (reason != null) {
                    showMessage(
                            String.format("Test completed with result %s and msg: %s.", testResult,
                                    reason));
                } else {
                    showMessage(String.format("Test completed with result %s", testResult));
                }
            } else {
                Log.d(TAG, "Ignored, another completion notification was sent before");
            }
            mParallelTestComplete.countDown();
        }

        @Override
        public void testCrashed() {
            handleCompletionNotification(CRASH, null);
        }

        @Override
        public void testSucceeded() {
            handleCompletionNotification(SUCCESS, null);
        }

        @Override
        public void testFailed(String reason) {
            handleCompletionNotification(FAILURE, reason);
        }

        @Override
        public void testHung() {
            handleCompletionNotification(HANG, null);
        }
    };

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.interruptable_test);
        mTestResultView = findViewById(R.id.parallel_test_result);
        mStopTestButton = findViewById(R.id.stop_test);
        mStopTestButton.setEnabled(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    protected void showMessage(String msg) {
        Log.i(TAG, "TestActivity: " + msg);
        runOnUiThread(() -> {
            mTestResultView.append(msg + "\n");
        });
    }


    @Override
    protected void onResume() {
        super.onResume();

        if (mParallelTestComplete.getCount() == 0) {
            // test was completed before resuming
            return;
        }

        final Intent intent = getIntent();

        final int[] testList = intent.getIntArrayExtra(EXTRA_TEST_LIST);
        final int threadCount = intent.getIntExtra(EXTRA_THREAD_COUNT, 10);
        final long testDurationMillis = intent.getLongExtra(EXTRA_TEST_DURATION_MILLIS,
                1000 * 60 * 10);
        final boolean runInSeparateProcess = intent.getBooleanExtra(EXTRA_RUN_IN_SEPARATE_PROCESS,
                true);

        coordinator = new CrashTestCoordinator(getApplicationContext());

        final long testTimeoutMillis = (long) (testDurationMillis * 1.5);
        coordinator.startTest(RunModelsInParallel.class,
                RunModelsInParallel.intentInitializer(testList, threadCount,
                        Duration.ofMillis(testDurationMillis)), testCompletionListener,
                testTimeoutMillis, runInSeparateProcess);

        mStopTestButton.setEnabled(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (coordinator != null) {
            coordinator.shutdown();
            coordinator = null;
        }
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "Destroying NNParallelTestActivity");
        super.onDestroy();
    }

    private void endTests() {
        coordinator.shutdown();
    }

    // This method blocks until the tests complete and returns true if all tests completed
    // successfully
    public TestResult testResult() {
        try {
            final Intent intent = getIntent();
            final long testDurationMillis = intent.getLongExtra(EXTRA_TEST_DURATION_MILLIS,
                    60 * 10);
            boolean completed = mParallelTestComplete.await(testDurationMillis, MILLISECONDS);
            if (!completed) {
                showMessage("Ending tests since they didn't complete on time");
                endTests();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        TestResult resultMaybe = mTestResult.get();
        return resultMaybe != null ? resultMaybe : HANG;
    }

    public void onStopTestClicked(View view) {
        showMessage("Stopping tests");
        endTests();
    }
}
