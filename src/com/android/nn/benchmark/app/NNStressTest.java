/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.test.suitebuilder.annotation.LargeTest;
import com.android.nn.benchmark.core.TestModels;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Tests that ensure stability of NNAPI by putting it under heavy load.
 */
@RunWith(Parameterized.class)
public class NNStressTest extends BenchmarkTestBase {
    private static final String TAG = NNStressTest.class.getSimpleName();

    private static final String[] MODEL_NAMES = NNScoringTest.MODEL_NAMES;

    private static final float STRESS_TEST_WARMUP_SECONDS = 0; // No warmup.
    private static final float STRESS_TEST_RUNTIME_SECONDS = 60 * 60; // 1 hour.

    public NNStressTest(TestModels.TestModelEntry model) {
        super(model);
    }

    @Parameters(name = "{0}")
    public static List<TestModels.TestModelEntry> modelsList() {
        List<TestModels.TestModelEntry> models = new ArrayList<>();
        for (String modelName : MODEL_NAMES) {
            TestModels.TestModelEntry model = TestModels.getModelByName(modelName);
            models.add(
                new TestModels.TestModelEntry(
                    model.mModelName,
                    model.mBaselineSec,
                    model.mInputShape,
                    model.mInOutAssets,
                    model.mInOutDatasets,
                    model.mTestName,
                    model.mModelFile,
                    null, // Disable evaluation.
                    model.mMinSdkVersion));
        }
        return Collections.unmodifiableList(models);
    }

    @Test
    @LargeTest
    public void stressTestNNAPI() throws IOException {
        setUseNNApi(true);
        setCompleteInputSet(false);
        TestAction ta = new TestAction(mModel, STRESS_TEST_WARMUP_SECONDS,
            STRESS_TEST_RUNTIME_SECONDS);
        runTest(ta, mModel.getTestName());
    }
}
