/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.nn.benchmark.core;

import android.app.Activity;
import android.content.res.AssetManager;
import android.util.Log;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class NNTestBase {
    protected final boolean USE_NNAPI = true;
    protected static final String TAG = "NN_TESTBASE";

    // Used to load the 'native-lib' library on application startup.
    static {
        System.loadLibrary("nnbenchmark");
    }

    private synchronized native long initModel(String modelFileName);

    private synchronized native void destroyModel(long modelHandle);

    private synchronized native boolean resizeInputTensors(long modelHandle, int[] inputShape);

    /** Discard inference output in inference results. */
    public static final int FLAG_DISCARD_INFERENCE_OUTPUT = 1 << 0;
    /** Do not expect golden outputs with inference inputs.
     *
     * Useful in cases where there's no straightforward golden output values
     * for the benchmark. This will also skip calculating basic (golden
     * output based) error metrics.
     */
    public static final int FLAG_IGNORE_GOLDEN_OUTPUT = 1 << 1;
    /** Run without NNAPI - useful for performance comparison against TFLite */
    public static final int FLAG_NO_NNAPI = 1 << 2;

    private synchronized native boolean runBenchmark(long modelHandle,
            List<InferenceInOut> inOutList,
            List<InferenceResult> resultList,
            int inferencesMaxCount,
            float maxTimeout,
            int flags);

    protected Activity mActivity;
    protected TextView mText;
    private String mModelName;
    private long mModelHandle;
    private int[] mInputShape;
    private InferenceInOut.FromAssets[] mInputOutputAssets;

    public NNTestBase(String modelName, int[] inputShape,
            InferenceInOut.FromAssets[] inputOutputAssets) {
        mModelName = modelName;
        mInputShape = inputShape;
        mInputOutputAssets = inputOutputAssets;
        mModelHandle = 0;
    }

    public final void createBaseTest(Activity ipact) {
        mActivity = ipact;
        String modelFileName = copyAssetToFile();
        if (modelFileName != null) {
            mModelHandle = initModel(modelFileName);
            if (mModelHandle != 0) {
                resizeInputTensors(mModelHandle, mInputShape);
            } else {
                Log.e(TAG, "Failed to init the model");
            }
        }
    }

    public String getTestInfo() {
        return mModelName;
    }

    private List<InferenceInOut> getInputOutputAssets() throws IOException {
        // TODO: Caching, dont read inputs for every inference
        List<InferenceInOut> inOutList = new ArrayList<>();
        for (InferenceInOut.FromAssets ioAsset : mInputOutputAssets) {
            inOutList.add(ioAsset.readAssets(mActivity.getAssets()));
        }
        return inOutList;
    }

    public InferenceResult runOneInference() throws IOException, BenchmarkException {
        return runBenchmark(getInputOutputAssets(), 1, 1.0f, FLAG_DISCARD_INFERENCE_OUTPUT).get(0);
    }

    public List<InferenceResult> runBenchmark(float timeoutSec, boolean noNNAPI)
            throws IOException, BenchmarkException {
        // Run as many as possible before timeout.
        int flags = FLAG_DISCARD_INFERENCE_OUTPUT;
        if (noNNAPI) {
          flags = flags | FLAG_NO_NNAPI;
        }
        return runBenchmark(getInputOutputAssets(), 0xFFFFFFF, timeoutSec, flags);
    }

    public List<InferenceResult> runBenchmark(List<InferenceInOut> inOutList,
            int inferencesMaxCount,
            float timeoutSec,
            int flags)
            throws IOException, BenchmarkException {
        if (mModelHandle != 0) {
            List<InferenceResult> resultList = new ArrayList<>();

            if (runBenchmark(mModelHandle, inOutList, resultList, inferencesMaxCount, timeoutSec, flags)) {
                return resultList;
            } else {
                throw new BenchmarkException("Failed to run benchmark");
            }
        }
        throw new IllegalStateException("mModelHandle is null");
    }

    public void destroy() {
        if (mModelHandle != 0) {
            destroyModel(mModelHandle);
            mModelHandle = 0;
        }
    }

    // We need to copy it to cache dir, so that TFlite can load it directly.
    private String copyAssetToFile() {
        String outFileName;
        String modelAssetName = mModelName + ".tflite";
        AssetManager assetManager = mActivity.getAssets();
        try {
            InputStream in = assetManager.open(modelAssetName);

            outFileName = mActivity.getCacheDir().getAbsolutePath() + "/" + modelAssetName;
            File outFile = new File(outFileName);
            OutputStream out = new FileOutputStream(outFile);

            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();

            in.close();
            out.close();
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy asset file: " + modelAssetName, e);
            return null;
        }
        return outFileName;
    }
}
