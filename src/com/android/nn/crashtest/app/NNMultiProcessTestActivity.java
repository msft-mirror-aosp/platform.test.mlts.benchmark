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

package com.android.nn.crashtest.app;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import com.android.nn.crashtest.core.CrashTestCoordinator;
import com.android.nn.crashtest.core.test.RunModelsInMultipleProcesses;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class NNMultiProcessTestActivity extends Activity {
  private static final String TAG = "NNMultiProcessTest";
  public static final Duration MAX_TEST_DELAY_BEFORE_HANG = Duration.ofSeconds(30);

  private final CrashTestStatus mTestStatus = new CrashTestStatus(this::logMessage);
  private final CrashTestCoordinator mCoordinator = new CrashTestCoordinator(this);
  private Duration mDuration;

  protected void logMessage(String msg) {
    Log.i(TAG, msg);
  }

  @Override
  protected void onResume() {
    super.onResume();

    final Intent intent = getIntent();

    mDuration = Duration.ofMillis(intent.getLongExtra(
        RunModelsInMultipleProcesses.TEST_DURATION, Duration.ofSeconds(30).toMillis()));
    mCoordinator.startTest(RunModelsInMultipleProcesses.class,
        RunModelsInMultipleProcesses.intentInitializer(
            intent.getStringExtra(RunModelsInMultipleProcesses.TEST_NAME),
            intent.getStringExtra(RunModelsInMultipleProcesses.MODEL_NAME),
            intent.getIntExtra(RunModelsInMultipleProcesses.PROCESSES, 3),
            intent.getIntExtra(RunModelsInMultipleProcesses.THREADS, 1), mDuration,
            intent.getStringExtra(RunModelsInMultipleProcesses.NNAPI_DEVICE_NAME),
            intent.getBooleanExtra(RunModelsInMultipleProcesses.JUST_COMPILE, false),
            intent.getIntExtra(RunModelsInMultipleProcesses.CLIENT_FAILURE_RATE_PERCENT, 0)),
        mTestStatus,
        /*separateProcess=*/false, intent.getStringExtra(RunModelsInMultipleProcesses.TEST_NAME));
  }

  // This method blocks until the tests complete and returns true if all tests completed
  // successfully
  public CrashTestStatus.TestResult testResult() {
    try {
      final Duration testTimeout = mDuration.plus(MAX_TEST_DELAY_BEFORE_HANG);
      boolean completed =
          mTestStatus.waitForCompletion(testTimeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!completed) {
        Log.w(TAG, String.format("Test didn't comoplete within %s. Returning HANG", testTimeout));
        return CrashTestStatus.TestResult.HANG;
      }
      return mTestStatus.result();
    } catch (InterruptedException e) {
      Log.w(TAG, "Interrupted while waiting for test completion. Returning HANG");
      return CrashTestStatus.TestResult.HANG;
    }
  }
}