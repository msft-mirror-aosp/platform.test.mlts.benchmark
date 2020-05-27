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

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.test.ActivityInstrumentationTestCase2;
import android.test.UiThreadTest;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import com.android.nn.benchmark.core.NNTestBase;
import com.android.nn.benchmark.core.TestModels;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runners.Parameterized.Parameters;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

abstract class NNParallelInferenceTest extends
        ActivityInstrumentationTestCase2<NNParallelTestActivity> {

    static final String TAG = "NNParallelInferenceTest";

    @Rule
    public TestName mTestName = new TestName();

    private final int mThreadCount;
    private final Duration mTestDuration;
    private final String mAcceleratorName;

    protected abstract boolean runTestsInSeparateProcess();

    protected NNParallelInferenceTest(int threadCount, Duration testDuration,
            String acceleratorName) {
        super(NNParallelTestActivity.class);
        mThreadCount = threadCount;
        mTestDuration = testDuration;
        mAcceleratorName = acceleratorName;
    }

    @Before
    @Override
    public void setUp() {
        injectInstrumentation(InstrumentationRegistry.getInstrumentation());
        setActivityIntent(runAllModelsOnNThreadsForOnAccelerator(mThreadCount, mTestDuration,
                mAcceleratorName));
    }

    @Test
    @LargeTest
    @UiThreadTest
    public void shouldNotFailWithParallelThreads() {
        Bundle testData = new Bundle();
        testData.putString("Test name", mTestName.getMethodName());
        testData.putString("Test status", "Started");
        getInstrumentation().sendStatus(Activity.RESULT_FIRST_USER, testData);

        NNParallelTestActivity.TestResult testResult = getActivity().testResult();
        assertEquals("Test didn't complete successfully", NNParallelTestActivity.TestResult.SUCCESS,
                testResult);

        testData.putString("Test status", "Completed");
        getInstrumentation().sendStatus(Activity.RESULT_OK, testData);
    }

    @After
    @Override
    public void tearDown() throws Exception {
        Log.i(TAG, "Tearing down test");
        super.tearDown();
    }

    @Parameters(name = "{0} threads for {1} on accelerator {2}")
    public static Iterable<Object[]> threadCountValues() {
        final Object[] lowParallelWorkloadShortTest = new Object[]{2, Duration.ofMinutes(10)};
        final Object[] midParallelWorkloadShortTest = new Object[]{4, Duration.ofMinutes(10)};
        final Object[] highParallelWorkloadLongTest = new Object[]{8, Duration.ofMinutes(30)};

        List<Object[]> threadsAndDuration = Arrays.asList(lowParallelWorkloadShortTest,
                midParallelWorkloadShortTest,
                highParallelWorkloadLongTest);

        List<String> accelerators = NNTestBase.availableAcceleratorNames();
        accelerators.add(null); // running tests with no target accelerator too

        return threadsAndDuration.stream().flatMap(
                currThreadAndDuration -> accelerators.stream().map(accelerator -> {
                    Object[] result = Arrays.copyOf(currThreadAndDuration,
                            currThreadAndDuration.length + 1);
                    result[currThreadAndDuration.length] = accelerator;
                    return result;
                })).collect(Collectors.toList());
    }

    private Intent runAllModelsOnNThreadsForOnAccelerator(int threadCount, Duration testDuration,
            String acceleratorName) {
        Intent intent = new Intent();

        int modelsCount = TestModels.modelsList().size();
        intent.putExtra(
                NNParallelTestActivity.EXTRA_TEST_LIST, IntStream.range(0, modelsCount).toArray());
        intent.putExtra(NNParallelTestActivity.EXTRA_THREAD_COUNT, threadCount);
        intent.putExtra(NNParallelTestActivity.EXTRA_TEST_DURATION_MILLIS, testDuration.toMillis());
        intent.putExtra(NNParallelTestActivity.EXTRA_RUN_IN_SEPARATE_PROCESS,
                runTestsInSeparateProcess());
        intent.putExtra(NNParallelTestActivity.EXTRA_TEST_NAME, mTestName.getMethodName());
        if (acceleratorName != null) {
            intent.putExtra(NNParallelTestActivity.EXTRA_ACCELERATOR_NAME, acceleratorName);
            intent.putExtra(NNParallelTestActivity.EXTRA_IGNORE_UNSUPPORTED_MODELS, true);
        }
        return intent;
    }
}
