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

package com.android.nn.benchmark.crashtest;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicReference;

public class CrashTestCoordinator {

    private static String TAG = "CrashTestCoordinator";

    private final Context mContext;
    private static final Timer mTestTimeoutTimer = new Timer("TestTimeoutTimer");
    private boolean mServiceBound;

    public interface CrashTestIntentInitializer {
        void addIntentParams(Intent intent);
    }

    public interface CrashTestCompletionListener {
        void testCrashed();

        void testSucceeded();

        void testFailed(String cause);

        void testHung();
    }

    public CrashTestCoordinator(Context context) {
        mContext = context;
    }

    class KeepAliveServiceConnection implements ServiceConnection {
        private final CrashTestCompletionListener mTestCompletionListener;
        private final TimerTask mTestHungNotifier;
        private Messenger mMessenger = null;

        KeepAliveServiceConnection(
                CrashTestCompletionListener testCompletionListener,
                TimerTask testHungNotifier) {
            mTestCompletionListener = testCompletionListener;
            mTestHungNotifier = testHungNotifier;
        }

        public boolean isServiceAlive() {
            if (mMessenger != null) {
                try {
                    mMessenger.send(Message.obtain(null, CrashTestService.HEARTBEAT));
                    return true;
                } catch (RemoteException notAlive) {
                    return false;
                }
            }
            return false;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, String.format("Service '%s' connected with binder %s", name, service));

            mMessenger = new Messenger(service);

            try {
                Message msg = Message.obtain(null, CrashTestService.SET_COMM_CHANNEL);
                msg.replyTo = new Messenger(new Handler(message -> {
                    switch (message.what) {
                        case CrashTestService.SUCCESS:
                            Log.i(TAG, "Test succeeded");
                            mTestCompletionListener.testSucceeded();
                            mTestHungNotifier.cancel();
                            unbindService();
                            break;

                        case CrashTestService.FAILURE:
                            String reason = msg.getData().getString(
                                    CrashTestService.FAILURE_DESCRIPTION);
                            Log.i(TAG, "Test failed with reason: " + reason);
                            mTestCompletionListener.testFailed(reason);
                            mTestHungNotifier.cancel();
                            unbindService();
                            break;
                    }
                    return true;
                }));
                mMessenger.send(msg);
            } catch (RemoteException e) {
                Log.w(TAG, "Unable to talk to service; it might have been shut down", e);
                mTestHungNotifier.cancel();
                mTestCompletionListener.testCrashed();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "Service disconnected");
            try {
                tryUnbindService();
            } catch (IllegalArgumentException serviceUnreachable) {
                Log.w(CrashTest.TAG, "Test crashed!!!", serviceUnreachable);
            }
            mTestCompletionListener.testCrashed();
            mTestHungNotifier.cancel();
        }
    }

    private final AtomicReference<KeepAliveServiceConnection> serviceConnection =
            new AtomicReference<>(null);

    /**
     * @throws IllegalStateException if unable to start the service
     */
    public void startTest(Class<? extends CrashTest> crashTestClass,
            CrashTestIntentInitializer intentParamsProvider,
            CrashTestCompletionListener testCompletionListener, long testTimeoutMillis,
            boolean separateProcess) {

        final Intent crashTestServiceIntent = new Intent(mContext,
                separateProcess ? OutOfProcessCrashTestService.class
                        : InProcessCrashTestService.class);
        crashTestServiceIntent.putExtra(CrashTestService.EXTRA_KEY_CRASH_TEST_CLASS,
                crashTestClass.getName());
        intentParamsProvider.addIntentParams(crashTestServiceIntent);

        final TimerTask testHungNotifier = new TimerTask() {
            @Override
            public void run() {
                Log.i(TAG, "Test is hung");
                testCompletionListener.testHung();
            }
        };

        serviceConnection.set(new KeepAliveServiceConnection(
                testCompletionListener, testHungNotifier));

        mServiceBound = mContext.bindService(crashTestServiceIntent, serviceConnection.get(),
                Context.BIND_AUTO_CREATE);
        Log.i(TAG, String.format("Crash test service started %s? %b",
                separateProcess ? " in a separate process"
                        : "in a local process", mServiceBound));

        if (!mServiceBound) {
            throw new IllegalStateException("Unsable to start service");
        }
        if (testTimeoutMillis > 0l) {
            Log.i(TAG, "Starting timeout timer");
            mTestTimeoutTimer.schedule(testHungNotifier, testTimeoutMillis + 200);
            mTestTimeoutTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    Log.i(TAG, "Timeout task running");
                    KeepAliveServiceConnection sc = serviceConnection.get();
                    if (sc != null && sc.isServiceAlive()) {
                        Log.i(TAG, "Unbinding service");
                        try {
                            tryUnbindService();
                        } catch (Exception e) {
                            Log.e(TAG, "Error trying to unbind service", e);
                            testCompletionListener.testCrashed();
                            testHungNotifier.cancel();
                        }
                    } else {
                        testCompletionListener.testCrashed();
                        testHungNotifier.cancel();
                    }
                }
            }, testTimeoutMillis);
        }
    }

    public void shutdown() {
        unbindService();
    }

    // Could generate an IllegalArgumentException if the service is unreachable.
    private void tryUnbindService() {
        KeepAliveServiceConnection sc = serviceConnection.get();
        if (sc != null) {
            if (mServiceBound) {
                mServiceBound = false;
                mContext.unbindService(sc);
            }
            serviceConnection.compareAndSet(sc, null);
        }
    }

    private void unbindService() {
        try {
            tryUnbindService();
        } catch (Exception e) {
            Log.e(TAG, "Error trying to unbind service", e);
        }
    }
}
