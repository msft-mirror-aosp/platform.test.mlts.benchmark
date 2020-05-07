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

package com.android.nn.crashtest;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.NumberPicker;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.android.nn.benchmark.core.TestModels;
import com.android.nn.benchmark.core.TestModelsListLoader;
import com.android.nn.benchmark.crashtest.CrashTestCoordinator;
import com.android.nn.benchmark.crashtest.test.RunModelsInParallel;
import com.android.nn.benchmark.util.TestExternalStorageActivity;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "NN_STRESS_TEST";
    private static final int JOB_FREQUENCY_MILLIS = 15 * 60 * 1000; // 15 minutes
    private final AtomicInteger mSelectedModelIndex = new AtomicInteger(-1);
    private final AtomicBoolean mUseSeparateProcess = new AtomicBoolean(true);
    AtomicBoolean mTestRunning = new AtomicBoolean(false);
    private Button mStartTestButton;
    private TextView mMessage;
    private NumberPicker mThreadCount;
    private NumberPicker mTestDurationMinutes;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        TestExternalStorageActivity.testWriteExternalStorage(this, true);

        mStartTestButton = (Button) findViewById(R.id.start_button);
        mMessage = (TextView) findViewById(R.id.message);

        try {
            TestModelsListLoader.parseFromAssets(getAssets());
        } catch (IOException e) {
            Log.e(TAG, "Could not load models", e);
        }

        final List<String> modelNames = new ArrayList<>();
        modelNames.add("All Models");
        modelNames.addAll(TestModels.modelsList().stream().map(
                TestModels.TestModelEntry::getTestName).collect(
                Collectors.toList()));
        final ArrayAdapter<CharSequence> modelsAdapter = new ArrayAdapter(this,
                android.R.layout.simple_spinner_item,
                modelNames);
        modelsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        final Spinner testModelSpinner = (Spinner) findViewById(R.id.test_model);
        testModelSpinner.setAdapter(modelsAdapter);
        testModelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mSelectedModelIndex.set((int) id - 1);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                mSelectedModelIndex.set(-1);
            }
        });

        final ArrayAdapter<CharSequence> testTypeAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"Separate process", "In process"});
        testTypeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        final Spinner testTypeSpinner = (Spinner) findViewById(R.id.test_type);
        testTypeSpinner.setAdapter(testTypeAdapter);
        testTypeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mUseSeparateProcess.set(position == 0);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                mUseSeparateProcess.set(true);
            }
        });


        mThreadCount = (NumberPicker) findViewById(R.id.thread_count);
        mTestDurationMinutes = (NumberPicker) findViewById(R.id.duration_minutes);

        mThreadCount.setMinValue(1);
        mThreadCount.setMaxValue(20);

        mTestDurationMinutes.setMinValue(1);
        mTestDurationMinutes.setMaxValue(60);
    }

    void testStopped(String msg) {
        Log.i(TAG, "Test stopped " + msg);
        mTestRunning.set(false);
        runOnUiThread(() -> {
            mMessage.append(msg + "\n");
            mStartTestButton.setEnabled(true);
        });
    }

    public void startTestClicked(View v) {
        Log.i(TAG, "Starting test");

        if (mTestRunning.getAndSet(true)) {
            return;
        }

        mStartTestButton.setEnabled(false);

        startInferenceTest();
    }

    @SuppressLint("DefaultLocale")
    private void startInferenceTest() {
        CrashTestCoordinator coordinator = new CrashTestCoordinator(this);

        int threadCount = mThreadCount.getValue();
        int testDurationMinutes = mTestDurationMinutes.getValue();

        int[] testList = mSelectedModelIndex.get() < 0 ? IntStream.range(0,
                TestModels.modelsList().size()).toArray() : new int[]{mSelectedModelIndex.get()};

        CrashTestCoordinator.CrashTestCompletionListener testCompletionListener =
                new CrashTestCoordinator.CrashTestCompletionListener() {
                    @Override
                    public void testCrashed() {
                        testStopped("Test crashed");
                    }

                    @Override
                    public void testSucceeded() {
                        testStopped("Test succeeded");
                    }

                    @Override
                    public void testFailed(String reason) {
                        testStopped("Test failed with reason " + reason);
                    }

                    @Override
                    public void testProgressing(Optional<String> description) {
                        Log.i(TAG, "> " + description.orElse("Test progressing.."));
                        // Ignoring message to avoid cluttering the test Text Area
                        runOnUiThread(() -> mMessage.append("."));
                    }
                };

        final int testTimeoutMillis = testDurationMinutes * 1500;
        final String testName = "in-app-test@" + System.currentTimeMillis();
        coordinator.startTest(RunModelsInParallel.class,
                RunModelsInParallel.intentInitializer(testList, threadCount,
                        Duration.ofMinutes(testDurationMinutes),
                        testName, null, false), testCompletionListener,
                mUseSeparateProcess.get(), testName);

        mMessage.setText(
                String.format("Inference test started with %d threads for %d minutes\n",
                        threadCount,
                        testDurationMinutes));
    }

}
