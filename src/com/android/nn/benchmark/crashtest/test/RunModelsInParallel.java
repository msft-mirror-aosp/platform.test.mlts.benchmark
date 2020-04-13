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

package com.android.nn.benchmark.crashtest.test;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.android.nn.benchmark.core.Processor;
import com.android.nn.benchmark.crashtest.CrashTest;
import com.android.nn.benchmark.crashtest.CrashTestCoordinator.CrashTestIntentInitializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RunModelsInParallel implements CrashTest {

    private static final String MODELS = "models";
    private static final String DURATION = "duration";
    private static final String THREADS = "thread_counts";

    static public CrashTestIntentInitializer intentInitializer(int[] models, int threadCount,
            Duration duration) {
        return intent -> {
            intent.putExtra(MODELS, models);
            intent.putExtra(DURATION, duration.toMillis());
            intent.putExtra(THREADS, threadCount);
        };
    }

    private long mTestDurationMillis = 0;
    private int mThreadCount = 0;
    private int[] mTestList = new int[0];
    private Context mContext;

    private ExecutorService mExecutorService = null;
    private final Set<Processor> activeTests = new HashSet<>();
    private CountDownLatch mParallelTestComplete = new CountDownLatch(1);
    private final List<Boolean> testCompletionResults = Collections.synchronizedList(
            new ArrayList<>());
    private final TimerTask mEndTestTask = new TimerTask() {
        @Override
        public void run() {
            Log.i(CrashTest.TAG, "\nEnding tests!!");
            endTests();
            mParallelTestComplete.countDown();
        }
    };

    private final Timer mTestEndTimer = new Timer("NNParallelTestActivityTestTerminationTimer");

    @Override
    public void init(Context context, Intent configParams) {
        mTestList = configParams.getIntArrayExtra(MODELS);
        mThreadCount = configParams.getIntExtra(THREADS, 10);
        mTestDurationMillis = configParams.getLongExtra(DURATION, 1000 * 60 * 10);
        mContext = context;

        mExecutorService = Executors.newFixedThreadPool(mThreadCount);
        testCompletionResults.clear();
    }

    @Override
    public Optional<String> call() {
        mTestEndTimer.schedule(mEndTestTask, mTestDurationMillis);
        for (int i = 0; i < mThreadCount; i++) {
            Processor testProcessor = createSubTestRunner(mTestList, i);

            activeTests.add(testProcessor);
            mExecutorService.submit(testProcessor);
        }

        return completedSuccessfully();
    }

    private Processor createSubTestRunner(final int[] testList, final int testIndex) {
        final Processor result = new Processor(mContext, new Processor.Callback() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onBenchmarkFinish(boolean ok) {
                Log.v(CrashTest.TAG, String
                        .format("Benchmark #%d completed %s", testIndex,
                                ok ? "successfully" : "with failure"));
                testCompletionResults.add(ok);
            }

            @Override
            public void onStatusUpdate(int testNumber, int numTests, String modelName) {
                Log.v(CrashTest.TAG,
                        String.format("Status update from test #%d, model '%s'", testNumber,
                                modelName));
            }
        }, testList);
        result.setUseNNApi(true);
        result.setCompleteInputSet(false);
        return result;
    }

    private void endTests() {
        for (Processor test : activeTests) {
            // Exit will block until the thread is completed
            test.exitWithTimeout(Duration.ofMinutes(1).toMillis());
        }
    }

    // This method blocks until the tests complete and returns true if all tests completed
    // successfully
    private Optional<String> completedSuccessfully() {
        try {
            boolean testsEnded = mParallelTestComplete.await(mTestDurationMillis, MILLISECONDS);
            if (!testsEnded) {
                Log.w(TAG, "Ending tests since they didn't complete on time");
                endTests();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        final long failedTestCount = testCompletionResults.stream().filter(
                testResult -> !testResult).count();
        if (failedTestCount > 0) {
            String failureMsg = String.format("%d out of %d test failed", failedTestCount,
                    testCompletionResults.size());
            Log.w(CrashTest.TAG, failureMsg);
            return failure(failureMsg);
        } else {
            Log.i(CrashTest.TAG, "Test completed successfully");
            return success();
        }
    }
}
