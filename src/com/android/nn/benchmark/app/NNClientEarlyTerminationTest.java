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
import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;
import android.test.ActivityInstrumentationTestCase2;
import android.test.UiThreadTest;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import com.android.nn.benchmark.core.BenchmarkException;
import com.android.nn.benchmark.core.Processor;
import com.android.nn.benchmark.core.TestModels;

import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.Before;
import org.junit.Rule;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

public class NNClientEarlyTerminationTest extends
        ActivityInstrumentationTestCase2<NNParallelTestActivity> {

    private static final String TAG = "NNClientEarlyTermination";
    private static final Duration MAX_SEPARATE_PROCESS_EXECUTION_TIME = Duration.ofSeconds(70);
    public static final int NNAPI_CLIENTS_COUNT = 4;

    private final ExecutorService mDriverLivenessValidationExecutor =
            Executors.newSingleThreadExecutor();

    @Rule public TestName mTestName = new TestName();

    public NNClientEarlyTerminationTest() {
        super(NNParallelTestActivity.class);
    }

    @Before
    @Override
    public void setUp() {
        injectInstrumentation(InstrumentationRegistry.getInstrumentation());
        final Intent runSomeInferencesInASeparateProcess = runAllModelsOnNThreadsFor(
                NNAPI_CLIENTS_COUNT,
                MAX_SEPARATE_PROCESS_EXECUTION_TIME);
        setActivityIntent(runSomeInferencesInASeparateProcess);
    }

    private long ramdomInRange(long min, long max) {
        return min + (long) (Math.random() * (max - min));
    }

    @Test
    @LargeTest
    @UiThreadTest
    public void testDriverDoesNotFailWithParallelThreads()
            throws ExecutionException, InterruptedException, RemoteException {
        final NNParallelTestActivity activity = getActivity();

        final DriverLivenessChecker driverLivenessChecker = new DriverLivenessChecker(activity);
        Future<Boolean> driverDidNotCrash = mDriverLivenessValidationExecutor.submit(
                driverLivenessChecker);

        // Causing failure before tests would end on their own.
        final long maxTerminationTime = MAX_SEPARATE_PROCESS_EXECUTION_TIME.toMillis() / 2;
        final long minTerminationTime = MAX_SEPARATE_PROCESS_EXECUTION_TIME.toMillis() / 4;
        Thread.sleep(ramdomInRange(minTerminationTime, maxTerminationTime));

        try {
            activity.killTestProcess();
        } catch (RemoteException e) {
            driverLivenessChecker.stop();
            throw e;
        }

        NNParallelTestActivity.TestResult testResult = activity.testResult();
        driverLivenessChecker.stop();

        assertEquals("Remote process is expected to be killed",
                NNParallelTestActivity.TestResult.CRASH,
                testResult);

        assertTrue("Driver shouldn't crash if a client process is terminated",
                driverDidNotCrash.get());
    }

    private Intent runAllModelsOnNThreadsFor(int threadCount, Duration testDuration) {
        Intent intent = new Intent();
        intent.putExtra(
                NNParallelTestActivity.EXTRA_TEST_LIST, IntStream.range(0,
                        TestModels.modelsList().size()).toArray());
        intent.putExtra(NNParallelTestActivity.EXTRA_THREAD_COUNT, threadCount);
        intent.putExtra(NNParallelTestActivity.EXTRA_TEST_DURATION_MILLIS, testDuration.toMillis());
        intent.putExtra(NNParallelTestActivity.EXTRA_RUN_IN_SEPARATE_PROCESS, true);
        intent.putExtra(NNParallelTestActivity.EXTRA_TEST_NAME, mTestName.getMethodName());
        return intent;
    }

    static class DriverLivenessChecker implements Callable<Boolean> {
        final Processor mProcessor;
        private final AtomicBoolean mRun = new AtomicBoolean(true);

        public DriverLivenessChecker(Context context) {
            mProcessor = new Processor(context,
                    new Processor.Callback() {
                        @SuppressLint("DefaultLocale")
                        @Override
                        public void onBenchmarkFinish(boolean ok) {
                        }

                        @Override
                        public void onStatusUpdate(int testNumber, int numTests, String modelName) {
                        }
                    }, new int[0]);
            mProcessor.setUseNNApi(true);
            mProcessor.setCompleteInputSet(false);
        }

        public void stop() {
            mRun.set(false);
        }

        @Override
        public Boolean call() throws Exception {
            final Optional<TestModels.TestModelEntry> testModelMaybe =
                    TestModels.modelsList().stream()
                            .map(model ->
                                    new TestModels.TestModelEntry(
                                            model.mModelName,
                                            model.mBaselineSec,
                                            model.mInputShape,
                                            model.mInOutAssets,
                                            model.mInOutDatasets,
                                            model.mTestName,
                                            model.mModelFile,
                                            null, // Disable evaluation.
                                            model.mMinSdkVersion)).findFirst();
            if (!testModelMaybe.isPresent()) {
                Log.w(TAG, "No test model available to check NNAPI driver");
                return false;
            }

            final TestModels.TestModelEntry testModelEntry = testModelMaybe.get();
            while (mRun.get()) {
                try {
                    mProcessor.getInstrumentationResult(testModelEntry, 0, 3);
                } catch (IOException | BenchmarkException e) {
                    Log.e(TAG, String.format("Error running model %s", testModelEntry.mModelName));
                }
            }

            return true;
        }
    }
}
