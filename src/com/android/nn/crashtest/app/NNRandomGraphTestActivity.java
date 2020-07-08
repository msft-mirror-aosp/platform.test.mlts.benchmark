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
import com.android.nn.crashtest.core.test.RandomGraphTest;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class NNRandomGraphTestActivity extends Activity {
  private static final String TAG = "NN_RAND_MODEL";

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
        RandomGraphTest.MAX_TEST_DURATION, RandomGraphTest.DEFAULT_MAX_TEST_DURATION_MILLIS));
    mCoordinator.startTest(RandomGraphTest.class,
        RandomGraphTest.intentInitializer(
            intent.getIntExtra(RandomGraphTest.GRAPH_SIZE, RandomGraphTest.DEFAULT_GRAPH_SIZE),
            intent.getIntExtra(
                RandomGraphTest.DIMENSIONS_RANGE, RandomGraphTest.DEFAULT_DIMENSIONS_RANGE),
            intent.getIntExtra(RandomGraphTest.MODELS_COUNT, RandomGraphTest.DEFAULT_MODELS_COUNT),
            intent.getLongExtra(RandomGraphTest.PAUSE_BETWEEN_MODELS_MS,
                RandomGraphTest.DEFAULT_PAUSE_BETWEEN_MODELS_MILLIS),
            intent.getBooleanExtra(
                RandomGraphTest.COMPILATION_ONLY, RandomGraphTest.DEFAULT_COMPILATION_ONLY),
            intent.getStringExtra(RandomGraphTest.DEVICE_NAME),
            mDuration.toMillis(),
            intent.getStringExtra(RandomGraphTest.TEST_NAME)),
        mTestStatus,
        /*separateProcess=*/true, intent.getStringExtra(RandomGraphTest.TEST_NAME));
  }

  // This method blocks until the tests complete and returns true if all tests completed
  // successfully
  public CrashTestStatus.TestResult testResult() {
    try {
      final Duration testTimeout = mDuration.plus(Duration.ofSeconds(15));
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