// JNI bridge for tech.fonia.whisper.WhisperCppNative. The hot loop is decode(): it copies the step's
// logits and alignment row straight into the caller's arrays with Set<Float>ArrayRegion and allocates
// nothing, on either side of the boundary; the native buffers it reads were sized at load.

#include <jni.h>
#include <android/log.h>

#include <cstdio>
#include <mutex>
#include <vector>

#include "fonia_whisper.h"
#include "ggml.h"
#include "whisper.h"

namespace {

constexpr const char * TAG = "FoniaWhisper";

// whisper.cpp and ggml print progress at INFO while loading; keep warnings and errors only.
void log_to_logcat(ggml_log_level level, const char * text, void *) {
    int priority;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: priority = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN: priority = ANDROID_LOG_WARN; break;
        default: return;
    }
    __android_log_write(priority, TAG, text);
}

std::once_flag log_once;

fonia_whisper * from(jlong handle) { return reinterpret_cast<fonia_whisper *>(handle); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_tech_fonia_whisper_WhisperCppNative_load(JNIEnv * env, jclass, jstring model_path, jint n_threads, jintArray heads) {
    std::call_once(log_once, [] { whisper_log_set(log_to_logcat, nullptr); });

    const char * path = env->GetStringUTFChars(model_path, nullptr);
    jsize n = heads == nullptr ? 0 : env->GetArrayLength(heads);
    std::vector<jint> pairs(n);
    if (n > 0) {
        env->GetIntArrayRegion(heads, 0, n, pairs.data());
    }
    char error[512] = { 0 };
    fonia_whisper * fw = fonia_whisper_load(path, n_threads, reinterpret_cast<const int *>(pairs.data()), n / 2, error, sizeof(error));
    env->ReleaseStringUTFChars(model_path, path);
    if (fw == nullptr) {
        __android_log_write(ANDROID_LOG_ERROR, TAG, error);
        return 0;
    }
    return reinterpret_cast<jlong>(fw);
}

JNIEXPORT void JNICALL
Java_tech_fonia_whisper_WhisperCppNative_free(JNIEnv *, jclass, jlong handle) {
    fonia_whisper_free(from(handle));
}

// [n_vocab, n_audio_ctx, n_text_ctx, window_samples, has_alignment]
JNIEXPORT jintArray JNICALL
Java_tech_fonia_whisper_WhisperCppNative_dimensions(JNIEnv * env, jclass, jlong handle) {
    const fonia_whisper * fw = from(handle);
    const jint values[5] = {
        fonia_whisper_n_vocab(fw),
        fonia_whisper_n_audio_ctx(fw),
        fonia_whisper_n_text_ctx(fw),
        fonia_whisper_window_samples(fw),
        fonia_whisper_has_alignment(fw) ? 1 : 0,
    };
    jintArray out = env->NewIntArray(5);
    env->SetIntArrayRegion(out, 0, 5, values);
    return out;
}

JNIEXPORT jint JNICALL
Java_tech_fonia_whisper_WhisperCppNative_encode(JNIEnv * env, jclass, jlong handle, jfloatArray samples) {
    fonia_whisper * fw = from(handle);
    const jsize n = env->GetArrayLength(samples);
    if (n <= 0 || n > fonia_whisper_window_samples(fw)) {
        return -1;
    }
    env->GetFloatArrayRegion(samples, 0, n, fonia_whisper_samples(fw));
    return fonia_whisper_encode(fw, n);
}

JNIEXPORT jint JNICALL
Java_tech_fonia_whisper_WhisperCppNative_decode(JNIEnv * env, jclass, jlong handle, jint token, jint n_past,
                                                jfloatArray logits, jfloatArray alignment) {
    fonia_whisper * fw = from(handle);
    const int status = fonia_whisper_decode(fw, token, n_past);
    if (status != 0) {
        return status;
    }
    env->SetFloatArrayRegion(logits, 0, fonia_whisper_n_vocab(fw), fonia_whisper_logits(fw));
    if (alignment != nullptr && fonia_whisper_has_alignment(fw)) {
        env->SetFloatArrayRegion(alignment, 0, fonia_whisper_n_audio_ctx(fw), fonia_whisper_alignment(fw));
    }
    return 0;
}

JNIEXPORT jstring JNICALL
Java_tech_fonia_whisper_WhisperCppNative_systemInfo(JNIEnv * env, jclass) {
    return env->NewStringUTF(fonia_whisper_system_info());
}

}  // extern "C"
