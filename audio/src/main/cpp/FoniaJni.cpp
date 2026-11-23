// JNI bindings for tech.fonia.audio.NativeAudio. Nothing here runs on the audio thread: Kotlin calls
// these from its own threads, and the only up-call (a stream error) comes from Oboe's error thread.

#include <jni.h>

#include <memory>
#include <new>

#include "FoniaAudioRT.h"
#include "FoniaCapture.h"

namespace {

JavaVM *gVm = nullptr;
jclass gNativeAudio = nullptr;
jmethodID gOnCaptureError = nullptr;

using RingHandle = std::shared_ptr<fonia::RingBox>;

RingHandle *ringHandle(jlong handle) {
    return reinterpret_cast<RingHandle *>(handle);
}

fonia::Capture *capture(jlong handle) {
    return reinterpret_cast<fonia::Capture *>(handle);
}

/// Reports a stream error to Kotlin from Oboe's error thread, attaching it to the VM for the call.
void notifyError(int32_t id, int32_t error) {
    if (gVm == nullptr || gNativeAudio == nullptr || gOnCaptureError == nullptr) {
        return;
    }
    JNIEnv *env = nullptr;
    bool attached = false;
    jint status = gVm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (gVm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        attached = true;
    } else if (status != JNI_OK) {
        return;
    }
    env->CallStaticVoidMethod(gNativeAudio, gOnCaptureError, static_cast<jint>(id), static_cast<jint>(error));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    if (attached) {
        gVm->DetachCurrentThread();
    }
}

jint clampToInt(size_t value) {
    return value > 0x7fffffff ? 0x7fffffff : static_cast<jint>(value);
}

jlong ringCreate(JNIEnv *, jclass, jint capacity) {
    if (capacity <= 0) {
        return 0;
    }
    FNARing *raw = FNARingCreate(static_cast<size_t>(capacity));
    if (raw == nullptr) {
        return 0;
    }
    auto *box = new (std::nothrow) fonia::RingBox(raw);
    if (box == nullptr) {
        FNARingDestroy(raw);
        return 0;
    }
    auto *handle = new (std::nothrow) RingHandle(box);
    return reinterpret_cast<jlong>(handle);
}

void ringDestroy(JNIEnv *, jclass, jlong handle) {
    delete ringHandle(handle);
}

jint ringWrite(JNIEnv *env, jclass, jlong handle, jfloatArray src, jint offset, jint count) {
    if (count <= 0) {
        return 0;
    }
    FNARing *ring = (*ringHandle(handle))->ring();
    auto *samples = static_cast<float *>(env->GetPrimitiveArrayCritical(src, nullptr));
    if (samples == nullptr) {
        return 0;
    }
    size_t written = FNARingWrite(ring, samples + offset, static_cast<size_t>(count));
    env->ReleasePrimitiveArrayCritical(src, samples, JNI_ABORT);
    return static_cast<jint>(written);
}

jint ringRead(JNIEnv *env, jclass, jlong handle, jfloatArray dst, jint offset, jint count) {
    if (count <= 0) {
        return 0;
    }
    FNARing *ring = (*ringHandle(handle))->ring();
    auto *samples = static_cast<float *>(env->GetPrimitiveArrayCritical(dst, nullptr));
    if (samples == nullptr) {
        return 0;
    }
    size_t read = FNARingRead(ring, samples + offset, static_cast<size_t>(count));
    env->ReleasePrimitiveArrayCritical(dst, samples, 0);
    return static_cast<jint>(read);
}

jint ringCapacity(JNIEnv *, jclass, jlong handle) {
    return clampToInt(FNARingCapacity((*ringHandle(handle))->ring()));
}

jint ringAvailable(JNIEnv *, jclass, jlong handle) {
    return clampToInt(FNARingAvailable((*ringHandle(handle))->ring()));
}

jlong ringDropped(JNIEnv *, jclass, jlong handle) {
    return static_cast<jlong>(FNARingDropped((*ringHandle(handle))->ring()));
}

jlong captureCreate(JNIEnv *, jclass, jlong ring, jint id) {
    auto *created = new (std::nothrow) fonia::Capture(*ringHandle(ring), id, notifyError);
    return reinterpret_cast<jlong>(created);
}

jint captureOpen(JNIEnv *env, jclass, jlong handle, jint sampleRate, jint inputPreset, jint deviceId, jintArray info) {
    int32_t values[fonia::Capture::kInfoSize] = {};
    jint result = capture(handle)->open(sampleRate, inputPreset, deviceId, values);
    if (env->GetArrayLength(info) >= fonia::Capture::kInfoSize) {
        env->SetIntArrayRegion(info, 0, fonia::Capture::kInfoSize, values);
    }
    return result;
}

jint captureStart(JNIEnv *, jclass, jlong handle) {
    return capture(handle)->start();
}

void captureClose(JNIEnv *, jclass, jlong handle) {
    capture(handle)->close();
}

jint captureState(JNIEnv *, jclass, jlong handle) {
    return capture(handle)->state();
}

jdouble captureLatencyMillis(JNIEnv *, jclass, jlong handle) {
    return capture(handle)->latencyMillis();
}

void captureDestroy(JNIEnv *, jclass, jlong handle) {
    delete capture(handle);
}

jint captureDeliver(JNIEnv *env, jclass, jlong handle, jfloatArray interleaved, jint frames, jint channels) {
    if (frames <= 0 || channels <= 0 || env->GetArrayLength(interleaved) < frames * channels) {
        return 0;
    }
    auto *samples = static_cast<float *>(env->GetPrimitiveArrayCritical(interleaved, nullptr));
    if (samples == nullptr) {
        return 0;
    }
    capture(handle)->deliver(samples, frames, channels);
    env->ReleasePrimitiveArrayCritical(interleaved, samples, JNI_ABORT);
    return frames;
}

const JNINativeMethod kMethods[] = {
    {"ringCreate", "(I)J", reinterpret_cast<void *>(ringCreate)},
    {"ringDestroy", "(J)V", reinterpret_cast<void *>(ringDestroy)},
    {"ringWrite", "(J[FII)I", reinterpret_cast<void *>(ringWrite)},
    {"ringRead", "(J[FII)I", reinterpret_cast<void *>(ringRead)},
    {"ringCapacity", "(J)I", reinterpret_cast<void *>(ringCapacity)},
    {"ringAvailable", "(J)I", reinterpret_cast<void *>(ringAvailable)},
    {"ringDropped", "(J)J", reinterpret_cast<void *>(ringDropped)},
    {"captureCreate", "(JI)J", reinterpret_cast<void *>(captureCreate)},
    {"captureOpen", "(JIII[I)I", reinterpret_cast<void *>(captureOpen)},
    {"captureStart", "(J)I", reinterpret_cast<void *>(captureStart)},
    {"captureClose", "(J)V", reinterpret_cast<void *>(captureClose)},
    {"captureState", "(J)I", reinterpret_cast<void *>(captureState)},
    {"captureDestroy", "(J)V", reinterpret_cast<void *>(captureDestroy)},
    {"captureLatencyMillis", "(J)D", reinterpret_cast<void *>(captureLatencyMillis)},
    {"captureDeliver", "(J[FII)I", reinterpret_cast<void *>(captureDeliver)},
};

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass local = env->FindClass("tech/fonia/audio/NativeAudio");
    if (local == nullptr) {
        return JNI_ERR;
    }
    if (env->RegisterNatives(local, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) != JNI_OK) {
        return JNI_ERR;
    }
    gOnCaptureError = env->GetStaticMethodID(local, "onCaptureError", "(II)V");
    if (gOnCaptureError == nullptr) {
        return JNI_ERR;
    }
    gNativeAudio = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    gVm = vm;
    return JNI_VERSION_1_6;
}
