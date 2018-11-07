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
import android.util.Pair;
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

    private synchronized native long initModel(String modelFileName,
            boolean useNNApi,
            boolean enableIntermediateTensorsDump);

    private synchronized native void destroyModel(long modelHandle);

    private synchronized native boolean resizeInputTensors(long modelHandle, int[] inputShape);

    /** Discard inference output in inference results. */
    public static final int FLAG_DISCARD_INFERENCE_OUTPUT = 1 << 0;
    /**
     * Do not expect golden outputs with inference inputs.
     *
     * Useful in cases where there's no straightforward golden output values
     * for the benchmark. This will also skip calculating basic (golden
     * output based) error metrics.
     */
    public static final int FLAG_IGNORE_GOLDEN_OUTPUT = 1 << 1;

    private synchronized native boolean runBenchmark(long modelHandle,
            List<InferenceInOutSequence> inOutList,
            List<InferenceResult> resultList,
            int inferencesSeqMaxCount,
            float maxTimeout,
            int flags);

    private synchronized native void dumpAllLayers(
            long modelHandle,
            String dumpPath,
            List<InferenceInOutSequence> inOutList);

    protected Activity mActivity;
    protected TextView mText;
    private String mModelName;
    private String mModelFile;
    private long mModelHandle;
    private int[] mInputShape;
    private InferenceInOutSequence.FromAssets[] mInputOutputAssets;
    private InferenceInOutSequence.FromDataset[] mInputOutputDatasets;
    private EvaluatorConfig mEvaluatorConfig;
    private EvaluatorInterface mEvaluator;
    private boolean mHasGoldenOutputs;
    private boolean mUseNNApi;
    private boolean mEnableIntermediateTensorsDump;

    public NNTestBase(String modelName, String modelFile, int[] inputShape,
            InferenceInOutSequence.FromAssets[] inputOutputAssets,
            InferenceInOutSequence.FromDataset[] inputOutputDatasets,
            EvaluatorConfig evaluator,
            boolean useNNApi,
            boolean enableIntermediateTensorsDump) {
        if (inputOutputAssets == null && inputOutputDatasets == null) {
            throw new IllegalArgumentException(
                    "Neither inputOutputAssets or inputOutputDatasets given - no inputs");
        }
        if (inputOutputAssets != null && inputOutputDatasets != null) {
            throw new IllegalArgumentException(
                    "Both inputOutputAssets or inputOutputDatasets given. Only one" +
                            "supported at once.");
        }
        mModelName = modelName;
        mModelFile = modelFile;
        mInputShape = inputShape;
        mInputOutputAssets = inputOutputAssets;
        mInputOutputDatasets = inputOutputDatasets;
        mModelHandle = 0;
        mUseNNApi = useNNApi;
        mEnableIntermediateTensorsDump = enableIntermediateTensorsDump;
        mEvaluatorConfig = evaluator;
    }

    public final void setupModel(Activity ipact) {
        mActivity = ipact;
        String modelFileName = copyAssetToFile();
        if (modelFileName != null) {
            mModelHandle = initModel(modelFileName, mUseNNApi, mEnableIntermediateTensorsDump);
            if (mModelHandle != 0) {
                resizeInputTensors(mModelHandle, mInputShape);
            } else {
                Log.e(TAG, "Failed to init the model");
            }
        }
        if (mEvaluatorConfig != null) {
            mEvaluator = mEvaluatorConfig.createEvaluator(mActivity.getAssets());
        }
    }

    public String getTestInfo() {
        return mModelName;
    }

    public EvaluatorInterface getEvaluator() {
        return mEvaluator;
    }

    private List<InferenceInOutSequence> getInputOutputAssets() throws IOException {
        // TODO: Caching, don't read inputs for every inference
        List<InferenceInOutSequence> inOutList = new ArrayList<>();
        if (mInputOutputAssets != null) {
            for (InferenceInOutSequence.FromAssets ioAsset : mInputOutputAssets) {
                inOutList.add(ioAsset.readAssets(mActivity.getAssets()));
            }
        }
        if (mInputOutputDatasets != null) {
            for (InferenceInOutSequence.FromDataset dataset : mInputOutputDatasets) {
                inOutList.addAll(dataset.readDataset(mActivity.getAssets(),
                        mActivity.getCacheDir()));
            }
        }

        Boolean lastGolden = null;
        for (InferenceInOutSequence sequence : inOutList) {
            mHasGoldenOutputs = sequence.hasGoldenOutput();
            if (lastGolden == null) {
                lastGolden = new Boolean(mHasGoldenOutputs);
            } else {
                if (lastGolden.booleanValue() != mHasGoldenOutputs) {
                    throw new IllegalArgumentException("Some inputs for " + mModelName +
                            " have outputs while some don't.");
                }
            }
        }
        return inOutList;
    }

    public int getDefaultFlags() {
        int flags = 0;
        if (!mHasGoldenOutputs) {
            flags = flags | FLAG_IGNORE_GOLDEN_OUTPUT;
        }
        if (mEvaluator == null) {
            flags = flags | FLAG_DISCARD_INFERENCE_OUTPUT;
        }
        return flags;
    }

    public void dumpAllLayers(File dumpDir, int inputAssetIndex, int inputAssetSize)
            throws IOException, BenchmarkException {
        if (!dumpDir.exists() || !dumpDir.isDirectory()) {
            throw new IllegalArgumentException("dumpDir doesn't exist or is not a directory");
        }
        if (!mEnableIntermediateTensorsDump) {
            throw new IllegalStateException("mEnableIntermediateTensorsDump is " +
                    "set to false, impossible to proceed");
        }

        List<InferenceInOutSequence> ios = getInputOutputAssets();
        dumpAllLayers(mModelHandle, dumpDir.toString(),
                ios.subList(inputAssetIndex, inputAssetSize));
    }

    public Pair<List<InferenceInOutSequence>, List<InferenceResult>> runInferenceOnce()
            throws IOException, BenchmarkException {
        List<InferenceInOutSequence> ios = getInputOutputAssets();
        int flags = getDefaultFlags();
        Pair<List<InferenceInOutSequence>, List<InferenceResult>> output =
                runBenchmark(ios, 1, Float.MAX_VALUE, flags);
        return output;
    }

    public Pair<List<InferenceInOutSequence>, List<InferenceResult>> runBenchmark(float timeoutSec)
            throws IOException, BenchmarkException {
        // Run as many as possible before timeout.
        int flags = getDefaultFlags();
        return runBenchmark(getInputOutputAssets(), 0xFFFFFFF, timeoutSec, flags);
    }

    /** Run through whole input set (once or mutliple times). */
    public Pair<List<InferenceInOutSequence>, List<InferenceResult>> runBenchmarkCompleteInputSet(
            int setRepeat,
            float timeoutSec)
            throws IOException, BenchmarkException {
        int flags = getDefaultFlags();
        List<InferenceInOutSequence> ios = getInputOutputAssets();
        int totalSequenceInferencesCount = ios.size() * setRepeat;
        int extpectedResults = 0;
        for (InferenceInOutSequence iosSeq : ios) {
            extpectedResults += iosSeq.size();
        }
        extpectedResults *= setRepeat;

        Pair<List<InferenceInOutSequence>, List<InferenceResult>> result =
                runBenchmark(ios, totalSequenceInferencesCount, timeoutSec,
                        flags);
        if (result.second.size() != extpectedResults ) {
            // We reached a timeout or failed to evaluate whole set for other reason, abort.
            throw new IllegalStateException(
                    "Failed to evaluate complete input set, expected: "
                            + extpectedResults +
                            ", received: " + result.second.size());
        }
        return result;
    }

    public Pair<List<InferenceInOutSequence>, List<InferenceResult>> runBenchmark(
            List<InferenceInOutSequence> inOutList,
            int inferencesSeqMaxCount,
            float timeoutSec,
            int flags)
            throws IOException, BenchmarkException {
        if (mModelHandle != 0) {
            List<InferenceResult> resultList = new ArrayList<>();

            if (runBenchmark(mModelHandle, inOutList, resultList, inferencesSeqMaxCount,
                    timeoutSec, flags)) {
                return new Pair<List<InferenceInOutSequence>, List<InferenceResult>>(
                        inOutList, resultList);
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
        String modelAssetName = mModelFile + ".tflite";
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
