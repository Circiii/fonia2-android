#pragma once

// Port of FoniaAudioRT (iOS: Sources/FoniaAudioRT/include/FoniaAudioRT.h).
// Everything that runs on the real-time audio thread lives here, in plain C-callable C++:
// the lock-free single-producer, single-consumer ring and the delivery of one input buffer into it.

#include <stddef.h>
#include <stdint.h>

// iOS compiles these with -Werror=function-effects and marks them nonblocking. Clang 20 is the first
// to know the attribute; NDK r28 ships Clang 19, so there it expands to nothing and the rules
// (no allocation, no locks, no logging, no JNI) are kept by hand. CMakeLists.txt turns the check on
// as soon as the compiler supports it.
#if defined(__has_attribute)
#if __has_attribute(nonblocking)
#define FNA_NONBLOCKING __attribute__((nonblocking))
#endif
#endif
#ifndef FNA_NONBLOCKING
#define FNA_NONBLOCKING
#endif

// Sample formats, with the values of aaudio_format_t / oboe::AudioFormat.
#define FNA_FORMAT_I16 1
#define FNA_FORMAT_FLOAT 2
#define FNA_FORMAT_I32 4

#ifdef __cplusplus
extern "C" {
#endif

/// Lock-free single-producer, single-consumer ring of float samples.
/// One thread at a time may write and one thread at a time may read.
typedef struct FNARing FNARing;

/// Capacity is rounded up to a power of two. Returns NULL when memory cannot be obtained.
FNARing *FNARingCreate(size_t capacityPowerOfTwo);
void FNARingDestroy(FNARing *ring);

/// Copies as many samples as fit and returns that number. Samples that do not fit are counted as dropped.
size_t FNARingWrite(FNARing *ring, const float *src, size_t count) FNA_NONBLOCKING;

/// Copies up to `count` of the oldest samples and returns how many were copied.
size_t FNARingRead(FNARing *ring, float *dst, size_t count);

size_t FNARingCapacity(const FNARing *ring);
size_t FNARingAvailable(const FNARing *ring);
size_t FNARingDropped(const FNARing *ring);

/// What the input callback does with one buffer: writes channel 0 of `frames` interleaved frames into
/// `ring`. iOS's sink block gets non-interleaved Float32 and takes buffer 0; Oboe hands over interleaved
/// frames, so channel 0 is every `channels`-th sample. 16- and 32-bit integers are scaled to -1...1.
void FNADeliver(FNARing *ring, const void *data, int32_t frames, int32_t channels, int32_t format) FNA_NONBLOCKING;

#ifdef __cplusplus
}
#endif
