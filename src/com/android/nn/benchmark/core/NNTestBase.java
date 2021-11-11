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

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.util.Log;
import android.util.Pair;
import android.widget.TextView;
import androidx.test.InstrumentationRegistry;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import dalvik.system.BaseDexClassLoader;
import android.content.res.AssetFileDescriptor;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.AutoCloseInputStream;
import java.util.jar.JarFile;
import java.util.jar.JarEntry;

public class NNTestBase implements AutoCloseable {
    protected static final String TAG = "NN_TESTBASE";

    private static final String NNAPI_SL_LIBRARIES_LIST_ASSET_PATH = "sl_prebuilt_filelist.txt";

    // Used to load the 'native-lib' library on application startup.
    static {
        System.loadLibrary("nnbenchmark_jni");
    }

    // Does the device has any NNAPI accelerator?
    // We only consider a real device, not 'nnapi-reference'.
    public static native boolean hasAccelerator();

    /**
     * Fills resultList with the name of the available NNAPI accelerators
     *
     * @return False if any error occurred, true otherwise
     */
    public static native boolean getAcceleratorNames(List<String> resultList);
    public static native boolean hasNnApiDevice(String nnApiDeviceName);

    private static native long loadNnApiSlHandle(String nnApiSlPath);

    private static long nnapiSlHandle = 0;
    public static synchronized long getOrLoadNnApiSlHandle(Context context, boolean extractNnApiSupportLibrary)
        throws IOException {
      if (nnapiSlHandle == 0) {
        Log.i(TAG, "Initializing NNAPI SL.");

        String nnSupportLibFilePath = null;
        Log.i(TAG, "Preparing NNAPI SL");
        if (extractNnApiSupportLibrary) {
            nnSupportLibFilePath = extractAllAndGetNnApiSlPath(context, NNAPI_SL_LIB_NAME);
        } else {
            nnSupportLibFilePath = getNnApiSlPathFromApkLibraries(context, NNAPI_SL_LIB_NAME);
        }

        if (nnSupportLibFilePath != null) {
          nnapiSlHandle = loadNnApiSlHandle(nnSupportLibFilePath);
          if (nnapiSlHandle == 0) {
            Log.e(TAG, String
                .format("Unable load NNAPI SL from '%s'.", nnSupportLibFilePath));
          } else {
              Log.i(TAG, String
                  .format("Successfully loaded NNAPI SL from '%s'.", nnSupportLibFilePath));
          }
        } else {
          Log.e(TAG, String
              .format("Unable to find NNAPI SL entry point '%s' in embedded libraries path.",
                  NNAPI_SL_LIB_NAME));
        }
      }
      return nnapiSlHandle;
    }

    private static InputStream getInputStreamFromApk(String apkPath, String filePath) throws IOException {
        Log.i(TAG, String.format("Getting input stream from APK '%s' and file '%s'.", apkPath, filePath));

        JarFile jarFile = new JarFile(apkPath);
        JarEntry jarEntry = jarFile.getJarEntry(filePath);
        return jarFile.getInputStream(jarEntry);
    }

    private static String extractAllAndGetNnApiSlPath(Context context, String entryPointName)
        throws IOException {
        try {
            BufferedReader slLibraryListReader
                = new BufferedReader(
                    new InputStreamReader(
                        context.getAssets().open(NNAPI_SL_LIBRARIES_LIST_ASSET_PATH)));
            String result = null;
            final String nnLibTargetFolder = context.getCodeCacheDir().toString();
            for (final String libraryFile : slLibraryListReader.lines().collect(Collectors.toList())) {
                String sourcePath = getNnApiSlPathFromApkLibraries(context, libraryFile);
                if (sourcePath == null) {
                    Log.w(TAG, String.format("Unable to find SL library '%s' to extract assuming is not part of this chipset distribution.", libraryFile));
                    continue;
                }

                String[] apkAndLibraryPaths = sourcePath.split("!");
                if (apkAndLibraryPaths.length != 2) {
                    Log.e(TAG, String.format("Unable to extract %s.", sourcePath));
                    return null;
                }

                File targetPath = new File(nnLibTargetFolder, libraryFile);
                try(InputStream in = getInputStreamFromApk(apkAndLibraryPaths[0],
                    // Removing leading '/'
                    apkAndLibraryPaths[1].substring(1));
                    OutputStream out = new FileOutputStream(targetPath)
                ) {
                    copyFull(in, out);
                }

                Log.i(TAG, String.format("Copied '%s' to '%s'.", sourcePath, targetPath));

                if (libraryFile.equals(entryPointName)) {
                    result = targetPath.getAbsolutePath();
                }
            }
            return result;
        } catch (IOException e) {
            Log.e(TAG, "Unable to find list of SL libraries under assets.", e);
            throw e;
        }
    }

    private static String getNnApiSlPathFromApkLibraries(Context context, String resourceName) {
        BaseDexClassLoader dexClassLoader = (BaseDexClassLoader) context.getClassLoader();
        // Removing the "lib" prefix and ".so" suffix.
        String libShortName = resourceName.substring(3, resourceName.length() - 3);
        String result = dexClassLoader.findLibrary(libShortName);
        if (result != null) {
            return result;
        }
        return dexClassLoader.findLibrary(resourceName);
    }

    private synchronized native long initModel(
            String modelFileName,
            int tfliteBackend,
            boolean enableIntermediateTensorsDump,
            String nnApiDeviceName,
            boolean mmapModel,
            String nnApiCacheDir,
            long nnApiLibHandle) throws NnApiDelegationFailure;

    private synchronized native void destroyModel(long modelHandle);

    private synchronized native boolean resizeInputTensors(long modelHandle, int[] inputShape);

    private synchronized native boolean runBenchmark(long modelHandle,
            List<InferenceInOutSequence> inOutList,
            List<InferenceResult> resultList,
            int inferencesSeqMaxCount,
            float timeoutSec,
            int flags);

    private synchronized native CompilationBenchmarkResult runCompilationBenchmark(
            long modelHandle, int maxNumIterations, float warmupTimeoutSec, float runTimeoutSec);

    private synchronized native void dumpAllLayers(
            long modelHandle,
            String dumpPath,
            List<InferenceInOutSequence> inOutList);

    public static List<String> availableAcceleratorNames() {
        List<String> availableAccelerators = new ArrayList<>();
        if (NNTestBase.getAcceleratorNames(availableAccelerators)) {
            return availableAccelerators.stream().filter(
                    acceleratorName -> !acceleratorName.equalsIgnoreCase(
                            "nnapi-reference")).collect(Collectors.toList());
        } else {
            Log.e(TAG, "Unable to retrieve accelerator names!!");
            return Collections.EMPTY_LIST;
        }
    }

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


    /** Collect only 1 benchmark result every 10 **/
    public static final int FLAG_SAMPLE_BENCHMARK_RESULTS = 1 << 2;

    private static final String NNAPI_SL_LIB_NAME = "libnnapi_sl_driver.so";

    protected Context mContext;
    protected TextView mText;
    private final String mModelName;
    private final String mModelFile;
    private long mModelHandle;
    private final int[] mInputShape;
    private final InferenceInOutSequence.FromAssets[] mInputOutputAssets;
    private final InferenceInOutSequence.FromDataset[] mInputOutputDatasets;
    private final EvaluatorConfig mEvaluatorConfig;
    private EvaluatorInterface mEvaluator;
    private boolean mHasGoldenOutputs;
    private TfLiteBackend mTfLiteBackend;
    private boolean mEnableIntermediateTensorsDump = false;
    private final int mMinSdkVersion;
    private Optional<String> mNNApiDeviceName = Optional.empty();
    private boolean mMmapModel = false;
    // Path where the current model has been stored for execution
    private String mTemporaryModelFilePath;
    private boolean mSampleResults;

    // If set to true the test will look for the NNAPI SL binaries in the app resources,
    // copy them into the app cache dir and configure the TfLite test to load NNAPI
    // from the library.
    private boolean mUseNnApiSupportLibrary = false;
    private boolean mExtractNnApiSupportLibrary = false;

    static final String USE_NNAPI_SL_PROPERTY = "useNnApiSupportLibrary";
    static final String EXTRACT_NNAPI_SL_PROPERTY = "extractNnApiSupportLibrary";

    private static boolean getBooleanTestParameter(String key, boolean defaultValue) {
      // All instrumentation arguments are passed as String so I have to convert the value here.
      return Boolean.parseBoolean(
          InstrumentationRegistry.getArguments().getString(key, "" + defaultValue));
    }

    public static boolean shouldUseNnApiSupportLibrary() {
      return getBooleanTestParameter(USE_NNAPI_SL_PROPERTY, false);
    }

    public static boolean shouldExtractNnApiSupportLibrary() {
        return getBooleanTestParameter(EXTRACT_NNAPI_SL_PROPERTY, false);
    }

    public NNTestBase(String modelName, String modelFile, int[] inputShape,
            InferenceInOutSequence.FromAssets[] inputOutputAssets,
            InferenceInOutSequence.FromDataset[] inputOutputDatasets,
            EvaluatorConfig evaluator, int minSdkVersion) {
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
        mEvaluatorConfig = evaluator;
        mMinSdkVersion = minSdkVersion;
        mSampleResults = false;
    }

    public void setTfLiteBackend(TfLiteBackend tfLiteBackend) {
        mTfLiteBackend = tfLiteBackend;
    }

    public void enableIntermediateTensorsDump() {
        enableIntermediateTensorsDump(true);
    }

    public void enableIntermediateTensorsDump(boolean value) {
        mEnableIntermediateTensorsDump = value;
    }

    public void useNNApi() {
      setTfLiteBackend(TfLiteBackend.NNAPI);
    }

    public  void setUseNnApiSupportLibrary(boolean value) {mUseNnApiSupportLibrary = value;}
    public  void setExtractNnApiSupportLibrary(boolean value) {mExtractNnApiSupportLibrary = value;}

    public void setNNApiDeviceName(String value) {
        if (mTfLiteBackend != TfLiteBackend.NNAPI) {
            Log.e(TAG, "Setting device name has no effect when not using NNAPI");
        }
        mNNApiDeviceName = Optional.ofNullable(value);
    }

    public void setMmapModel(boolean value) {
        mMmapModel = value;
    }

    public final boolean setupModel(Context ipcxt) throws IOException, NnApiDelegationFailure {
        mContext = ipcxt;
        long nnApiLibHandle = 0;
        if (mUseNnApiSupportLibrary) {
          nnApiLibHandle = getOrLoadNnApiSlHandle(mContext, mExtractNnApiSupportLibrary);
          if (nnApiLibHandle == 0) {
            Log.e(TAG, String
                .format("Unable to find NNAPI SL entry point '%s' in embedded libraries path.",
                    NNAPI_SL_LIB_NAME));
            throw new NnApiDelegationFailure(String
                .format("Unable to find NNAPI SL entry point '%s' in embedded libraries path.",
                    NNAPI_SL_LIB_NAME));
          }
        }
        if (mTemporaryModelFilePath != null) {
            deleteOrWarn(mTemporaryModelFilePath);
        }
        mTemporaryModelFilePath = copyAssetToFile();
        String nnApiCacheDir = mContext.getCodeCacheDir().toString();
        mModelHandle = initModel(
                mTemporaryModelFilePath, mTfLiteBackend.ordinal(), mEnableIntermediateTensorsDump,
                mNNApiDeviceName.orElse(null), mMmapModel, nnApiCacheDir, nnApiLibHandle);
        if (mModelHandle == 0) {
            Log.e(TAG, "Failed to init the model");
            return false;
        }
        if (!resizeInputTensors(mModelHandle, mInputShape)) {
            return false;
        }

        if (mEvaluatorConfig != null) {
            mEvaluator = mEvaluatorConfig.createEvaluator(mContext.getAssets());
        }
        return true;
    }

    public String getTestInfo() {
        return mModelName;
    }

    public EvaluatorInterface getEvaluator() {
        return mEvaluator;
    }

    public void checkSdkVersion() throws UnsupportedSdkException {
        if (mMinSdkVersion > 0 && Build.VERSION.SDK_INT < mMinSdkVersion) {
            throw new UnsupportedSdkException("SDK version not supported. Mininum required: " +
                    mMinSdkVersion + ", current version: " + Build.VERSION.SDK_INT);
        }
    }

    private void deleteOrWarn(String path) {
        if (!new File(path).delete()) {
            Log.w(TAG, String.format(
                    "Unable to delete file '%s'. This might cause device to run out of space.",
                    path));
        }
    }


    private List<InferenceInOutSequence> getInputOutputAssets() throws IOException {
        // TODO: Caching, don't read inputs for every inference
        List<InferenceInOutSequence> inOutList =
                getInputOutputAssets(mContext, mInputOutputAssets, mInputOutputDatasets);

        Boolean lastGolden = null;
        for (InferenceInOutSequence sequence : inOutList) {
            mHasGoldenOutputs = sequence.hasGoldenOutput();
            if (lastGolden == null) {
                lastGolden = mHasGoldenOutputs;
            } else {
                if (lastGolden != mHasGoldenOutputs) {
                    throw new IllegalArgumentException(
                            "Some inputs for " + mModelName + " have outputs while some don't.");
                }
            }
        }
        return inOutList;
    }

    public static List<InferenceInOutSequence> getInputOutputAssets(Context context,
            InferenceInOutSequence.FromAssets[] inputOutputAssets,
            InferenceInOutSequence.FromDataset[] inputOutputDatasets) throws IOException {
        // TODO: Caching, don't read inputs for every inference
        List<InferenceInOutSequence> inOutList = new ArrayList<>();
        if (inputOutputAssets != null) {
            for (InferenceInOutSequence.FromAssets ioAsset : inputOutputAssets) {
                inOutList.add(ioAsset.readAssets(context.getAssets()));
            }
        }
        if (inputOutputDatasets != null) {
            for (InferenceInOutSequence.FromDataset dataset : inputOutputDatasets) {
                inOutList.addAll(dataset.readDataset(context.getAssets(), context.getCacheDir()));
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
        // For very long tests we will collect only a sample of the results
        if (mSampleResults) {
            flags = flags | FLAG_SAMPLE_BENCHMARK_RESULTS;
        }
        return flags;
    }

    public void dumpAllLayers(File dumpDir, int inputAssetIndex, int inputAssetSize)
            throws IOException {
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

    /** Run through whole input set (once or multiple times). */
    public Pair<List<InferenceInOutSequence>, List<InferenceResult>> runBenchmarkCompleteInputSet(
            int minInferences,
            float timeoutSec)
            throws IOException, BenchmarkException {
        int flags = getDefaultFlags();
        List<InferenceInOutSequence> ios = getInputOutputAssets();
        int setInferences = 0;
        for (InferenceInOutSequence iosSeq : ios) {
            setInferences += iosSeq.size();
        }
        int setRepeat = (minInferences + setInferences - 1) / setInferences; // ceil.
        int totalSequenceInferencesCount = ios.size() * setRepeat;
        int expectedResults = setInferences * setRepeat;

        Pair<List<InferenceInOutSequence>, List<InferenceResult>> result =
                runBenchmark(ios, totalSequenceInferencesCount, timeoutSec,
                        flags);
        if (result.second.size() != expectedResults) {
            // We reached a timeout or failed to evaluate whole set for other reason, abort.
            @SuppressLint("DefaultLocale")
            final String errorMsg = String.format(
                    "Failed to evaluate complete input set, in %f seconds expected: %d, received:"
                            + " %d",
                    timeoutSec, expectedResults, result.second.size());
            Log.w(TAG, errorMsg);
            throw new IllegalStateException(errorMsg);
        }
        return result;
    }

    public Pair<List<InferenceInOutSequence>, List<InferenceResult>> runBenchmark(
            List<InferenceInOutSequence> inOutList,
            int inferencesSeqMaxCount,
            float timeoutSec,
            int flags)
            throws IOException, BenchmarkException {
        if (mModelHandle == 0) {
            throw new UnsupportedModelException("Unsupported model");
        }
        List<InferenceResult> resultList = new ArrayList<>();
        if (!runBenchmark(mModelHandle, inOutList, resultList, inferencesSeqMaxCount,
                timeoutSec, flags)) {
            throw new BenchmarkException("Failed to run benchmark");
        }
        return new Pair<List<InferenceInOutSequence>, List<InferenceResult>>(
                inOutList, resultList);
    }

    public CompilationBenchmarkResult runCompilationBenchmark(float warmupTimeoutSec,
            float runTimeoutSec, int maxIterations) throws IOException, BenchmarkException {
        if (mModelHandle == 0) {
            throw new UnsupportedModelException("Unsupported model");
        }
        CompilationBenchmarkResult result = runCompilationBenchmark(
                mModelHandle, maxIterations, warmupTimeoutSec, runTimeoutSec);
        if (result == null) {
            throw new BenchmarkException("Failed to run compilation benchmark");
        }
        return result;
    }

    public void destroy() {
        if (mModelHandle != 0) {
            destroyModel(mModelHandle);
            mModelHandle = 0;
        }
        if (mTemporaryModelFilePath != null) {
            deleteOrWarn(mTemporaryModelFilePath);
            mTemporaryModelFilePath = null;
        }
    }

    private final Random mRandom = new Random(System.currentTimeMillis());

    // We need to copy it to cache dir, so that TFlite can load it directly.
    private String copyAssetToFile() throws IOException {
        @SuppressLint("DefaultLocale")
        String outFileName =
                String.format("%s/%s-%d-%d.tflite", mContext.getCacheDir().getAbsolutePath(),
                        mModelFile,
                        Thread.currentThread().getId(), mRandom.nextInt(10000));

        copyAssetToFile(mContext, mModelFile + ".tflite", outFileName);
        return outFileName;
    }

    public static boolean copyModelToFile(Context context, String modelFileName, File targetFile)
            throws IOException {
        if (!targetFile.exists() && !targetFile.createNewFile()) {
            Log.w(TAG, String.format("Unable to create file %s", targetFile.getAbsolutePath()));
            return false;
        }
        NNTestBase.copyAssetToFile(context, modelFileName, targetFile.getAbsolutePath());
        return true;
    }

    public static void copyAssetToFile(Context context, String modelAssetName, String targetPath)
            throws IOException {
        AssetManager assetManager = context.getAssets();
        try {
            File outFile = new File(targetPath);

            try (InputStream in = assetManager.open(modelAssetName);
                 FileOutputStream out = new FileOutputStream(outFile)) {
                copyFull(in, out);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy asset file: " + modelAssetName, e);
            throw e;
        }
    }

    private static void copyFull(InputStream in, OutputStream out) throws IOException {
        byte[] byteBuffer = new byte[1024];
        int readBytes = -1;
        while ((readBytes = in.read(byteBuffer)) != -1) {
            out.write(byteBuffer, 0, readBytes);
        }
    }

    @Override
    public void close() {
        destroy();
    }

    public void setSampleResult(boolean sampleResults) {
        this.mSampleResults = sampleResults;
    }
}
