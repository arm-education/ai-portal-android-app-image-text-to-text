#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <chrono>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"
#include "llama.h"
#include "mtmd-helper.h"
#include "mtmd.h"

namespace {

constexpr const char * LOG_TAG = "VisionChatNative";

struct Engine {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    mtmd_context * vision = nullptr;
    common_chat_templates_ptr templates;
    int batchSize = 512;

    ~Engine() {
        templates.reset();
        if (vision != nullptr) {
            mtmd_free(vision);
        }
        if (context != nullptr) {
            llama_free(context);
        }
        if (model != nullptr) {
            llama_model_free(model);
        }
    }
};

std::mutex engineMutex;
std::unique_ptr<Engine> engine;
std::atomic_bool cancelled{false};
std::once_flag backendOnce;

int androidLogPriority(ggml_log_level level) {
    switch (level) {
        case GGML_LOG_LEVEL_ERROR:
            return ANDROID_LOG_ERROR;
        case GGML_LOG_LEVEL_WARN:
            return ANDROID_LOG_WARN;
        case GGML_LOG_LEVEL_DEBUG:
            return ANDROID_LOG_DEBUG;
        default:
            return ANDROID_LOG_INFO;
    }
}

void androidLogCallback(ggml_log_level level, const char * text, void *) {
    if (text == nullptr) {
        return;
    }

    const bool isKleidiaiMessage = std::strstr(text, "kleidiai:") != nullptr;
    if (!isKleidiaiMessage && level != GGML_LOG_LEVEL_WARN && level != GGML_LOG_LEVEL_ERROR) {
        return;
    }

    __android_log_write(androidLogPriority(level), "llama.cpp", text);
}

const char * kleidiaiQ8KernelPreference() {
    if (ggml_cpu_has_sme2() && ggml_cpu_has_sme()) {
        return "SME2";
    }
    if (ggml_cpu_has_sme()) {
        return "SME";
    }
    if (ggml_cpu_has_matmul_int8() && ggml_cpu_has_dotprod()) {
        return "I8MM";
    }
    if (ggml_cpu_has_dotprod()) {
        return "Dot Product";
    }
    return "no compatible optimized kernel";
}

void logCpuDispatch() {
    __android_log_print(
            ANDROID_LOG_INFO,
            LOG_TAG,
            "CPU features: dotprod=%d i8mm=%d sme=%d sme2=%d; "
            "KleidiAI Q8_0 preference=%s",
            ggml_cpu_has_dotprod(),
            ggml_cpu_has_matmul_int8(),
            ggml_cpu_has_sme(),
            ggml_cpu_has_sme2(),
            kleidiaiQ8KernelPreference()
    );
}

long elapsedMillis(const std::chrono::steady_clock::time_point & start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - start
    ).count();
}

void throwRuntime(JNIEnv * env, const std::string & message) {
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", message.c_str());
    jclass exceptionClass = env->FindClass("java/lang/RuntimeException");
    env->ThrowNew(exceptionClass, message.c_str());
}

std::string fromJavaString(JNIEnv * env, jstring value) {
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        throw std::runtime_error("Could not read a Java string.");
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring toJavaString(JNIEnv * env, const std::string & value) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(value.size()));
    env->SetByteArrayRegion(
            bytes,
            0,
            static_cast<jsize>(value.size()),
            reinterpret_cast<const jbyte *>(value.data())
    );
    jclass stringClass = env->FindClass("java/lang/String");
    jmethodID constructor = env->GetMethodID(
            stringClass,
            "<init>",
            "([BLjava/lang/String;)V"
    );
    jstring utf8 = env->NewStringUTF("UTF-8");
    return static_cast<jstring>(env->NewObject(stringClass, constructor, bytes, utf8));
}

std::vector<unsigned char> toRgb(JNIEnv * env, jintArray pixels, int width, int height) {
    const jsize count = env->GetArrayLength(pixels);
    if (width <= 0 || height <= 0 || count != width * height) {
        throw std::runtime_error("The image pixel buffer has an invalid size.");
    }
    jint * argb = env->GetIntArrayElements(pixels, nullptr);
    if (argb == nullptr) {
        throw std::runtime_error("Could not access the image pixels.");
    }
    std::vector<unsigned char> rgb(static_cast<size_t>(count) * 3);
    for (jsize index = 0; index < count; index++) {
        const uint32_t pixel = static_cast<uint32_t>(argb[index]);
        rgb[index * 3] = static_cast<unsigned char>((pixel >> 16) & 0xff);
        rgb[index * 3 + 1] = static_cast<unsigned char>((pixel >> 8) & 0xff);
        rgb[index * 3 + 2] = static_cast<unsigned char>(pixel & 0xff);
    }
    env->ReleaseIntArrayElements(pixels, argb, JNI_ABORT);
    return rgb;
}

std::string tokenPiece(const llama_vocab * vocab, llama_token token) {
    std::vector<char> buffer(128);
    int length = llama_token_to_piece(
            vocab,
            token,
            buffer.data(),
            static_cast<int32_t>(buffer.size()),
            0,
            true
    );
    if (length < 0) {
        buffer.resize(static_cast<size_t>(-length));
        length = llama_token_to_piece(
                vocab,
                token,
                buffer.data(),
                static_cast<int32_t>(buffer.size()),
                0,
                true
        );
    }
    if (length < 0) {
        throw std::runtime_error("Could not decode an output token.");
    }
    return {buffer.data(), static_cast<size_t>(length)};
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_arm_learningpath_visionchat_NativeVisionBridge_loadModel(
        JNIEnv * env,
        jclass,
        jstring modelPathValue,
        jstring projectorPathValue,
        jint contextSize,
        jint threads
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    try {
        std::call_once(backendOnce, [] {
            llama_log_set(androidLogCallback, nullptr);
            ggml_backend_load_all();
            llama_backend_init();
            logCpuDispatch();
        });
        const auto started = std::chrono::steady_clock::now();
        const std::string modelPath = fromJavaString(env, modelPathValue);
        const std::string projectorPath = fromJavaString(env, projectorPathValue);

        auto next = std::make_unique<Engine>();
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Loading language model");
        llama_model_params modelParameters = llama_model_default_params();
        modelParameters.n_gpu_layers = 0;
        next->model = llama_model_load_from_file(modelPath.c_str(), modelParameters);
        if (next->model == nullptr) {
            throw std::runtime_error("llama.cpp could not load the language model GGUF.");
        }
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Language model loaded");

        llama_context_params contextParameters = llama_context_default_params();
        contextParameters.n_ctx = static_cast<uint32_t>(contextSize);
        contextParameters.n_batch = static_cast<uint32_t>(next->batchSize);
        contextParameters.n_ubatch = static_cast<uint32_t>(next->batchSize);
        contextParameters.n_threads = threads;
        contextParameters.n_threads_batch = threads;
        next->context = llama_init_from_model(next->model, contextParameters);
        if (next->context == nullptr) {
            throw std::runtime_error("llama.cpp could not create the language-model context.");
        }
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Language-model context created");

        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Loading vision projector");
        mtmd_context_params visionParameters = mtmd_context_params_default();
        visionParameters.use_gpu = false;
        visionParameters.print_timings = true;
        visionParameters.n_threads = threads;
        visionParameters.warmup = false;
        next->vision = mtmd_init_from_file(
                projectorPath.c_str(),
                next->model,
                visionParameters
        );
        if (next->vision == nullptr || !mtmd_support_vision(next->vision)) {
            throw std::runtime_error("libmtmd could not load a compatible vision projector.");
        }
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Vision projector loaded");
        next->templates = common_chat_templates_init(next->model, "");
        if (!next->templates) {
            throw std::runtime_error("The model does not contain a usable chat template.");
        }
        engine = std::move(next);
        return static_cast<jlong>(elapsedMillis(started));
    } catch (const std::exception & exception) {
        engine.reset();
        throwRuntime(env, exception.what());
        return 0;
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_arm_learningpath_visionchat_NativeVisionBridge_generate(
        JNIEnv * env,
        jclass,
        jintArray pixels,
        jint width,
        jint height,
        jstring promptValue,
        jint maxTokens,
        jfloat temperature,
        jint topK,
        jfloat topP
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    try {
        if (!engine) {
            throw std::runtime_error("Load a model before generating a response.");
        }
        cancelled = false;
        llama_memory_clear(llama_get_memory(engine->context), true);

        std::vector<unsigned char> rgb = toRgb(env, pixels, width, height);
        mtmd::bitmap bitmap(width, height, rgb.data());
        if (!bitmap.ptr) {
            throw std::runtime_error("libmtmd could not create an image input.");
        }

        const std::string marker = mtmd_default_marker();
        common_chat_msg message;
        message.role = "user";
        message.content = marker + "\n" + fromJavaString(env, promptValue);
        const std::vector<common_chat_msg> history;
        const std::string formatted = common_chat_format_single(
                engine->templates.get(),
                history,
                message,
                true,
                true
        );
        const size_t markerPosition = formatted.find(marker);
        if (markerPosition == std::string::npos) {
            throw std::runtime_error("The chat template removed the image marker.");
        }
        const std::string before = formatted.substr(0, markerPosition);
        const std::string after = formatted.substr(markerPosition + marker.size());
        mtmd_input_text beforeText{before.data(), before.size(), false, true};
        mtmd_input_text afterText{after.data(), after.size(), false, true};
        mtmd_input_part beforePart{&beforeText, nullptr};
        mtmd_input_part bitmapPart{nullptr, bitmap.ptr.get()};
        mtmd_input_part afterPart{&afterText, nullptr};
        const mtmd_input_part * parts[]{&beforePart, &bitmapPart, &afterPart};
        mtmd::input_chunks_ptr chunks(mtmd_input_chunks_init());
        if (mtmd_tokenize_from_parts(engine->vision, chunks.get(), parts, 3, true) != 0) {
            throw std::runtime_error("libmtmd could not tokenize the image and prompt.");
        }

        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Evaluating image and prompt");
        const auto evaluationStarted = std::chrono::steady_clock::now();
        llama_pos nextPosition = 0;
        if (mtmd_helper_eval_chunks(
                engine->vision,
                engine->context,
                chunks.get(),
                0,
                0,
                engine->batchSize,
                true,
                &nextPosition
        ) != 0) {
            throw std::runtime_error("The image and prompt evaluation failed.");
        }
        const long evaluationMillis = elapsedMillis(evaluationStarted);
        __android_log_print(
                ANDROID_LOG_INFO,
                LOG_TAG,
                "Image and prompt evaluated in %ld ms",
                evaluationMillis
        );

        llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(topK));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

        llama_batch batch = llama_batch_init(1, 0, 1);
        const llama_vocab * vocab = llama_model_get_vocab(engine->model);
        std::string answer;
        int generated = 0;
        const auto decodeStarted = std::chrono::steady_clock::now();
        for (int index = 0; index < maxTokens && !cancelled; index++) {
            const llama_token token = llama_sampler_sample(sampler, engine->context, -1);
            llama_sampler_accept(sampler, token);
            if (llama_vocab_is_eog(vocab, token)) {
                break;
            }
            answer += tokenPiece(vocab, token);
            generated++;

            common_batch_clear(batch);
            common_batch_add(batch, token, nextPosition++, {0}, true);
            if (llama_decode(engine->context, batch) != 0) {
                llama_batch_free(batch);
                llama_sampler_free(sampler);
                throw std::runtime_error("The language-model decode step failed.");
            }
        }
        const long decodeMillis = elapsedMillis(decodeStarted);
        __android_log_print(
                ANDROID_LOG_INFO,
                LOG_TAG,
                "Generated %d tokens in %ld ms",
                generated,
                decodeMillis
        );
        llama_batch_free(batch);
        llama_sampler_free(sampler);

        jclass stringClass = env->FindClass("java/lang/String");
        jobjectArray result = env->NewObjectArray(4, stringClass, nullptr);
        env->SetObjectArrayElement(result, 0, toJavaString(env, answer));
        env->SetObjectArrayElement(result, 1, env->NewStringUTF(std::to_string(evaluationMillis).c_str()));
        env->SetObjectArrayElement(result, 2, env->NewStringUTF(std::to_string(decodeMillis).c_str()));
        env->SetObjectArrayElement(result, 3, env->NewStringUTF(std::to_string(generated).c_str()));
        return result;
    } catch (const std::exception & exception) {
        throwRuntime(env, exception.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_arm_learningpath_visionchat_NativeVisionBridge_cancel(JNIEnv *, jclass) {
    cancelled = true;
}

extern "C" JNIEXPORT void JNICALL
Java_org_arm_learningpath_visionchat_NativeVisionBridge_close(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(engineMutex);
    engine.reset();
}
