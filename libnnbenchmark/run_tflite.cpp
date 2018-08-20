/**
 * Copyright 2017 The Android Open Source Project
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

#include "run_tflite.h"

#include "tensorflow/contrib/lite/kernels/register.h"

#include <android/log.h>
#include <cstdio>
#include <sys/time.h>
#include <dlfcn.h>

#define LOG_TAG "NN_BENCHMARK"

#define FATAL(fmt,...) do { \
  __android_log_print(ANDROID_LOG_FATAL, LOG_TAG, fmt, ##__VA_ARGS__); \
    assert(false); \
} while(0)

namespace {

long long currentTimeInUsec() {
    timeval tv;
    gettimeofday(&tv, NULL);
    return ((tv.tv_sec * 1000000L) + tv.tv_usec);
}

// Workaround for build systems that make difficult to pick the correct NDK API level.
// NDK tracing methods are dynamically loaded from libandroid.so.
typedef void *(*fp_ATrace_beginSection)(const char *sectionName);
typedef void *(*fp_ATrace_endSection)();
struct TraceFunc {
    fp_ATrace_beginSection ATrace_beginSection;
    fp_ATrace_endSection ATrace_endSection;
};
TraceFunc setupTraceFunc() {
  void *lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
  if (lib == nullptr) {
      FATAL("unable to open libandroid.so");
  }
  return {
      reinterpret_cast<fp_ATrace_beginSection>(dlsym(lib, "ATrace_beginSection")),
      reinterpret_cast<fp_ATrace_endSection>(dlsym(lib, "ATrace_endSection"))
  };
}
static TraceFunc kTraceFunc { setupTraceFunc() };


}  // namespace

BenchmarkModel::BenchmarkModel(const char* modelfile) {
    // Memory map the model. NOTE this needs lifetime greater than or equal
    // to interpreter context.
    mTfliteModel = tflite::FlatBufferModel::BuildFromFile(modelfile);
    if (!mTfliteModel) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                            "Failed to load model %s", modelfile);
        return;
    }

    tflite::ops::builtin::BuiltinOpResolver resolver;
    tflite::InterpreterBuilder(*mTfliteModel, resolver)(&mTfliteInterpreter);
    if (!mTfliteInterpreter) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                            "Failed to create TFlite interpreter");
        return;
    }
}

BenchmarkModel::~BenchmarkModel() {
}

bool BenchmarkModel::setInput(const uint8_t* dataPtr, size_t length) {
    int input = mTfliteInterpreter->inputs()[0];
    auto* input_tensor = mTfliteInterpreter->tensor(input);

    switch (input_tensor->type) {
        case kTfLiteFloat32:
        case kTfLiteUInt8: {
            void* raw = input_tensor->data.raw;
            memcpy(raw, dataPtr, length);
            break;
        }
        default:
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                                "Input tensor type not supported");
            return false;
    }
    return true;
}
void BenchmarkModel::saveInferenceOutput(InferenceResult* result) {
      int output = mTfliteInterpreter->outputs()[0];
      auto* output_tensor = mTfliteInterpreter->tensor(output);

      result->inferenceOutput.insert(result->inferenceOutput.end(),
                                     output_tensor->data.uint8,
                                     output_tensor->data.uint8 + output_tensor->bytes);

}

void BenchmarkModel::getOutputError(const uint8_t* expected_data, size_t length,
                                    InferenceResult* result) {
      int output = mTfliteInterpreter->outputs()[0];
      auto* output_tensor = mTfliteInterpreter->tensor(output);
      if (output_tensor->bytes != length) {
          FATAL("Wrong size of output tensor, expected %zu, is %zu", output_tensor->bytes, length);
      }

      size_t elements_count = 0;
      float err_sum = 0.0;
      float max_error = 0.0;
      switch (output_tensor->type) {
          case kTfLiteUInt8: {
              uint8_t* output_raw = mTfliteInterpreter->typed_tensor<uint8_t>(output);
              elements_count = output_tensor->bytes;
              for (size_t i = 0;i < output_tensor->bytes; ++i) {
                  float err = ((float)output_raw[i]) - ((float)expected_data[i]);
                  if (err > max_error) max_error = err;
                  err_sum += err*err;
              }
              break;
          }
          case kTfLiteFloat32: {
              const float* expected = reinterpret_cast<const float*>(expected_data);
              float* output_raw = mTfliteInterpreter->typed_tensor<float>(output);
              elements_count = output_tensor->bytes / sizeof(float);
              for (size_t i = 0;i < output_tensor->bytes / sizeof(float); ++i) {
                  float err = output_raw[i] - expected[i];
                  if (err > max_error) max_error = err;
                  err_sum += err*err;
              }
              break;
          }
          default:
              FATAL("Output sensor type %d not supported", output_tensor->type);
      }
      result->meanSquareError = err_sum / elements_count;
      result->maxSingleError = max_error;
}

bool BenchmarkModel::resizeInputTensors(std::vector<int> shape) {
    // The benchmark only expects single input tensor, hardcoded as 0.
    int input = mTfliteInterpreter->inputs()[0];
    mTfliteInterpreter->ResizeInputTensor(input, shape);
    if (mTfliteInterpreter->AllocateTensors() != kTfLiteOk) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to allocate tensors!");
        return false;
    }
    return true;
}

bool BenchmarkModel::runInference(bool use_nnapi) {
    mTfliteInterpreter->UseNNAPI(use_nnapi);

    auto status = mTfliteInterpreter->Invoke();
    if (status != kTfLiteOk) {
      __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to invoke: %d!", (int)status);
      return false;
    }
    return true;
}

bool BenchmarkModel::benchmark(const std::vector<InferenceInOut> &inOutData,
                               int inferencesMaxCount,
                               float timeout,
                               int flags,
                               std::vector<InferenceResult> *results) {

    if (inOutData.size() == 0) {
        FATAL("Input/output vector is empty");
    }

    float inferenceTotal = 0.0;
    const bool use_nnapi = !(flags & FLAG_NO_NNAPI);
    for(int i = 0;i < inferencesMaxCount; i++) {
        const InferenceInOut & data = inOutData[i % inOutData.size()];

        long long startTime = currentTimeInUsec();
        // For NNAPI systrace usage documentation, see
        // frameworks/ml/nn/common/include/Tracing.h.
        kTraceFunc.ATrace_beginSection("[NN_LA_PE]BenchmarkModel::benchmark");
        setInput(data.input, data.input_size);
        const bool success = runInference(use_nnapi);
        kTraceFunc.ATrace_endSection();
        long long endTime = currentTimeInUsec();
        if (!success) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Inference %d failed", i);
            return false;
        }

        float inferenceTime = static_cast<float>(endTime - startTime) / 1000000.0f;
        InferenceResult result { inferenceTime, 0.0f, 0.0f, {}};
        if ((flags & FLAG_IGNORE_GOLDEN_OUTPUT) == 0) {
            getOutputError(data.output, data.output_size, &result);
        }

        if ((flags & FLAG_DISCARD_INFERENCE_OUTPUT) == 0) {
            saveInferenceOutput(&result);
        }
        results->push_back(result);

        // Timeout?
        inferenceTotal += inferenceTime;
        if (inferenceTotal > timeout) {
            return true;
        }
    }
    return true;
}
