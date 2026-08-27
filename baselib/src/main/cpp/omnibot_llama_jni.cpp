#include "llama.h"

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <unistd.h>

namespace {
constexpr const char * TAG = "OmniBotLlama";

std::mutex g_mutex;
llama_model * g_model = nullptr;
llama_context * g_context = nullptr;
llama_sampler * g_sampler = nullptr;
std::atomic_bool g_cancelled{false};
bool g_backend_initialized = false;

void logError(const std::string & message) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", message.c_str());
}

void notifyError(JNIEnv * env, jobject callback, const std::string & message) {
    if (callback == nullptr) return;
    jclass callbackClass = env->GetObjectClass(callback);
    if (callbackClass == nullptr) return;
    jmethodID method = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
    if (method != nullptr) {
        jstring error = env->NewStringUTF(message.c_str());
        env->CallVoidMethod(callback, method, error);
        env->DeleteLocalRef(error);
    }
    env->DeleteLocalRef(callbackClass);
}

void notifyToken(JNIEnv * env, jobject callback, const std::string & token) {
    if (callback == nullptr || token.empty()) return;
    jclass callbackClass = env->GetObjectClass(callback);
    if (callbackClass == nullptr) return;
    jmethodID method = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    if (method != nullptr) {
        jstring value = env->NewStringUTF(token.c_str());
        env->CallVoidMethod(callback, method, value);
        env->DeleteLocalRef(value);
    }
    env->DeleteLocalRef(callbackClass);
}

int threadCount() {
    const long cpus = sysconf(_SC_NPROCESSORS_ONLN);
    if (cpus <= 1) return 1;
    return static_cast<int>(std::min<long>(cpus - 1, 8));
}

void freeRuntimeLocked() {
    if (g_sampler != nullptr) {
        llama_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    if (g_context != nullptr) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

bool applyChatTemplate(
    llama_model * model,
    const std::vector<std::string> & roles,
    const std::vector<std::string> & contents,
    std::string & output
) {
    if (roles.size() != contents.size() || roles.empty()) return false;

    std::vector<llama_chat_message> messages;
    messages.reserve(roles.size());
    for (size_t i = 0; i < roles.size(); ++i) {
        messages.push_back({roles[i].c_str(), contents[i].c_str()});
    }

    const char * tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl == nullptr || tmpl[0] == '\0') {
        // Conservative fallback for instruct models without chat_template metadata.
        output.clear();
        for (size_t i = 0; i < messages.size(); ++i) {
            output += messages[i].role;
            output += ": ";
            output += messages[i].content;
            output += "\n";
        }
        output += "assistant: ";
        return true;
    }

    int32_t required = llama_chat_apply_template(
        tmpl, messages.data(), messages.size(), true, nullptr, 0);
    if (required < 0) return false;

    std::vector<char> buffer(static_cast<size_t>(required) + 1);
    int32_t written = llama_chat_apply_template(
        tmpl, messages.data(), messages.size(), true, buffer.data(), static_cast<int32_t>(buffer.size()));
    if (written < 0) return false;

    output.assign(buffer.data(), static_cast<size_t>(written));
    return true;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_cn_com_omnimind_baselib_llm_LocalInferenceEngineNative_nativeLoadModel(
    JNIEnv * env, jclass, jstring jModelPath, jint contextSize) {
    const char * modelPath = env->GetStringUTFChars(jModelPath, nullptr);
    if (modelPath == nullptr) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(g_mutex);
    freeRuntimeLocked();

    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
    }

    llama_model_params modelParams = llama_model_default_params();
    // CPU-first is deliberate: no experimental Android accelerator is enabled here.
    modelParams.n_gpu_layers = 0;

    g_model = llama_model_load_from_file(modelPath, modelParams);
    env->ReleaseStringUTFChars(jModelPath, modelPath);

    if (g_model == nullptr) {
        logError("Unable to load GGUF model");
        return JNI_FALSE;
    }

    llama_context_params contextParams = llama_context_default_params();
    const int trainedContext = llama_model_n_ctx_train(g_model);
    const uint32_t requestedContext = contextSize > 0 ? static_cast<uint32_t>(contextSize) : 4096U;
    contextParams.n_ctx = static_cast<uint32_t>(std::min<int64_t>(requestedContext, trainedContext > 0 ? trainedContext : requestedContext));
    contextParams.n_batch = std::min<uint32_t>(contextParams.n_ctx, 512U);
    contextParams.n_ubatch = std::min<uint32_t>(contextParams.n_batch, 256U);

    g_context = llama_init_from_model(g_model, contextParams);
    if (g_context == nullptr) {
        logError("Unable to create llama context");
        freeRuntimeLocked();
        return JNI_FALSE;
    }

    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    g_cancelled.store(false);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_cn_com_omnimind_baselib_llm_LocalInferenceEngineNative_nativeUnloadModel(
    JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    freeRuntimeLocked();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_cn_com_omnimind_baselib_llm_LocalInferenceEngineNative_nativeIsLoaded(
    JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return (g_model != nullptr && g_context != nullptr && g_sampler != nullptr) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_cn_com_omnimind_baselib_llm_LocalInferenceEngineNative_nativeModelInfo(
    JNIEnv * env, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model == nullptr) return env->NewStringUTF("{}");

    char description[256] = {};
    llama_model_desc(g_model, description, sizeof(description));
    const uint64_t size = llama_model_size(g_model);
    const uint64_t params = llama_model_n_params(g_model);
    const int32_t context = llama_model_n_ctx_train(g_model);

    std::string json = "{\"description\":\"" + std::string(description) +
        "\",\"sizeBytes\":" + std::to_string(size) +
        ",\"parameterCount\":" + std::to_string(params) +
        ",\"contextLength\":" + std::to_string(context) + "}";
    return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_cn_com_omnimind_baselib_llm_LocalInferenceEngineNative_nativeCancel(
    JNIEnv *, jclass) {
    g_cancelled.store(true);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_cn_com_omnimind_baselib_llm_LocalInferenceEngineNative_nativeGenerate(
    JNIEnv * env,
    jclass,
    jobject callback,
    jobjectArray jRoles,
    jobjectArray jContents,
    jint maxTokens,
    jfloat temperature,
    jfloat topP) {
    if (callback == nullptr) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model == nullptr || g_context == nullptr || g_sampler == nullptr) {
        notifyError(env, callback, "Offline model is not loaded.");
        return JNI_FALSE;
    }

    const jsize count = env->GetArrayLength(jRoles);
    if (count <= 0 || env->GetArrayLength(jContents) != count) {
        notifyError(env, callback, "Invalid chat message list.");
        return JNI_FALSE;
    }

    std::vector<std::string> roles;
    std::vector<std::string> contents;
    roles.reserve(count);
    contents.reserve(count);

    for (jsize i = 0; i < count; ++i) {
        jstring jRole = static_cast<jstring>(env->GetObjectArrayElement(jRoles, i));
        jstring jContent = static_cast<jstring>(env->GetObjectArrayElement(jContents, i));
        if (jRole == nullptr || jContent == nullptr) {
            if (jRole) env->DeleteLocalRef(jRole);
            if (jContent) env->DeleteLocalRef(jContent);
            notifyError(env, callback, "Invalid chat message.");
            return JNI_FALSE;
        }
        const char * role = env->GetStringUTFChars(jRole, nullptr);
        const char * content = env->GetStringUTFChars(jContent, nullptr);
        roles.emplace_back(role ? role : "user");
        contents.emplace_back(content ? content : "");
        if (role) env->ReleaseStringUTFChars(jRole, role);
        if (content) env->ReleaseStringUTFChars(jContent, content);
        env->DeleteLocalRef(jRole);
        env->DeleteLocalRef(jContent);
    }

    std::string prompt;
    if (!applyChatTemplate(g_model, roles, contents, prompt)) {
        notifyError(env, callback, "The selected GGUF model uses an unsupported chat template.");
        return JNI_FALSE;
    }

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    const int nPrompt = -llama_tokenize(vocab, prompt.c_str(), prompt.size(), nullptr, 0, true, true);
    if (nPrompt <= 0) {
        notifyError(env, callback, "Failed to tokenize the offline prompt.");
        return JNI_FALSE;
    }

    const uint32_t contextSize = llama_n_ctx(g_context);
    const int maxNewTokens = std::max(1, std::min<int>(maxTokens > 0 ? maxTokens : 512, static_cast<int>(contextSize)));
    if (static_cast<uint32_t>(nPrompt + maxNewTokens) >= contextSize) {
        notifyError(env, callback, "The conversation is too large for the selected model context window.");
        return JNI_FALSE;
    }

    std::vector<llama_token> promptTokens(static_cast<size_t>(nPrompt));
    if (llama_tokenize(vocab, prompt.c_str(), prompt.size(), promptTokens.data(), promptTokens.size(), true, true) < 0) {
        notifyError(env, callback, "Failed to tokenize the offline prompt.");
        return JNI_FALSE;
    }

    llama_memory_clear(llama_get_memory(g_context), true);
    g_cancelled.store(false);

    // Recreate the sampler for every request so generation parameters are request-scoped.
    llama_sampler_free(g_sampler);
    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    const float safeTemperature = temperature > 0.0f ? temperature : 0.7f;
    const float safeTopP = topP > 0.0f && topP <= 1.0f ? topP : 0.9f;
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(safeTopP, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(safeTemperature));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    llama_batch batch = llama_batch_get_one(promptTokens.data(), promptTokens.size());
    if (llama_decode(g_context, batch) != 0) {
        notifyError(env, callback, "Offline model failed during prompt evaluation.");
        return JNI_FALSE;
    }

    for (int generated = 0; generated < maxNewTokens; ++generated) {
        if (g_cancelled.load()) return JNI_TRUE;

        const llama_token token = llama_sampler_sample(g_sampler, g_context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;

        char piece[512];
        const int pieceLength = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, true);
        if (pieceLength < 0) {
            notifyError(env, callback, "Failed to decode an offline token.");
            return JNI_FALSE;
        }
        notifyToken(env, callback, std::string(piece, static_cast<size_t>(pieceLength)));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return JNI_FALSE;
        }

        batch = llama_batch_get_one(const_cast<llama_token *>(&token), 1);
        if (llama_decode(g_context, batch) != 0) {
            notifyError(env, callback, "Offline model failed during generation.");
            return JNI_FALSE;
        }
    }

    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_cn_com_omnimind_baselib_llm_LocalInferenceEngineNative_nativeShutdown(
    JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    freeRuntimeLocked();
    if (g_backend_initialized) {
        llama_backend_free();
        g_backend_initialized = false;
    }
}
