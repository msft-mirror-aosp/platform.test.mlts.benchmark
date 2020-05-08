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

import static com.android.nn.benchmark.app.NNParallelTestActivity.TestResult.CRASH;
import static com.android.nn.benchmark.app.NNParallelTestActivity.TestResult.FAILURE;
import static com.android.nn.benchmark.app.NNParallelTestActivity.TestResult.HANG;
import static com.android.nn.benchmark.app.NNParallelTestActivity.TestResult.SUCCESS;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import com.android.nn.benchmark.crashtest.CrashTestCoordinator;
import com.android.nn.benchmark.crashtest.CrashTestCoordinator.CrashTestCompletionListener;
import com.android.nn.benchmark.crashtest.test.RunModelsInParallel;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;


public class NNParallelTestActivity extends Activity {
    public static final int SHUTDOWN_TIMEOUT = 20000;
    String TAG = "NNParallelTestActivity";

    public static final String EXTRA_TEST_DURATION_MILLIS = "duration";
    public static final String EXTRA_THREAD_COUNT = "thread_count";
    public static final String EXTRA_TEST_LIST = "test_list";
    public static final String EXTRA_RUN_IN_SEPARATE_PROCESS = "run_in_separate_process";
    public static final String EXTRA_TEST_NAME = "test_name";
    public static final String EXTRA_ACCELERATOR_NAME = "accelerator_name";
    public static final String EXTRA_IGNORE_UNSUPPORTED_MODELS = "ignore_unsupported_models";


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
    private String mTestName;

    private CrashTestCompletionListener testCompletionListener = new CrashTestCompletionListener() {
        private void handleCompletionNotification(TestResult testResult, String reason) {
            Log.d(TAG,
                    String.format("Received crash test notification: %s and extra msg %s.",
                            testResult,
                            reason));
            if (mTestResult.compareAndSet(null, testResult)) {
                if (reason != null) {
                    showMessage(
                            String.format("Test completed with result %s and msg: %s.", testResult,
                                    reason));
                } else {
                    showMessage(String.format("Test completed with result %s", testResult));
                }
                mParallelTestComplete.countDown();
                showMessage(String.format("mParallelTestComplete count is now %d, test result is %s", mParallelTestComplete.getCount(), mTestResult.get()));
            } else {
                Log.d(TAG, "Ignored, another completion notification was sent before");
            }
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
        public void testProgressing(Optional<String> description) {
            runOnUiThread(() -> {
                mTestResultView.append(".");
            });
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
        Log.i(TAG, msg);
        runOnUiThread(() -> mTestResultView.append(msg + "\n"));
    }


    @Override
    protected void onResume() {
        super.onResume();

        Log.i(TAG, "ON RESUME");

        if (mParallelTestComplete.getCount() == 0) {
            // test was completed before resuming
            return;
        }

        final Intent intent = getIntent();

        final int[] testList = intent.getIntArrayExtra(EXTRA_TEST_LIST);

        Log.i(TAG, "Test list is " + testList);
        final int threadCount = intent.getIntExtra(EXTRA_THREAD_COUNT, 10);
        final long testDurationMillis = intent.getLongExtra(EXTRA_TEST_DURATION_MILLIS,
                1000 * 60 * 10);
        final boolean runInSeparateProcess = intent.getBooleanExtra(EXTRA_RUN_IN_SEPARATE_PROCESS,
                true);
        mTestName = intent.getStringExtra(EXTRA_TEST_NAME) != null
                ? intent.getStringExtra(EXTRA_TEST_NAME) : "no-name";

        coordinator = new CrashTestCoordinator(getApplicationContext());

        String acceleratorName = intent.getStringExtra(EXTRA_ACCELERATOR_NAME);
        boolean ignoreUnsupportedModels = intent.getBooleanExtra(EXTRA_IGNORE_UNSUPPORTED_MODELS, false);

        final long testTimeoutMillis = (long) (testDurationMillis * 1.5);
        coordinator.startTest(RunModelsInParallel.class,
                RunModelsInParallel.intentInitializer(testList, threadCount,
                        Duration.ofMillis(testDurationMillis),
                        mTestName, acceleratorName, ignoreUnsupportedModels), testCompletionListener,
                runInSeparateProcess, mTestName);

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
            // Giving the test a bit of time to wrap up
            final long testResultTimeout = testDurationMillis + SHUTDOWN_TIMEOUT;
            boolean completed = mParallelTestComplete.await(testResultTimeout, MILLISECONDS);
            if (!completed) {
                showMessage(String.format(
                        "Ending test '%s' since test result collection timeout of %d "
                                + "millis is expired",
                        mTestName, testResultTimeout));
                endTests();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        // If no result is available, assuming HANG
        mTestResult.compareAndSet(null, HANG);
        Log.i(TAG, String.format("Returning result for test '%s': %s", mTestName, mTestResult.get()));

        return mTestResult.get();
    }

    public void onStopTestClicked(View view) {
        showMessage("Stopping tests");
        endTests();
    }

    /**
     * Kills the process running the tests.
     *
     * @throws IllegalStateException if the method is called for an in-process test.
     * @throws RemoteException if the test service is not reachable
     */
    public void killTestProcess() throws RemoteException {
        final Intent intent = getIntent();

        final boolean runInSeparateProcess = intent.getBooleanExtra(EXTRA_RUN_IN_SEPARATE_PROCESS,
                true);

        if(!runInSeparateProcess) {
            throw new IllegalStateException("Cannot kill the test process in an in-process test!");
        }

        Log.i(TAG, "Shutting down coordinator to kill test process");
        coordinator.killCrashTestService();
    }
}
