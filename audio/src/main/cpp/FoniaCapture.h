#pragma once

// The Android side of FNAMakeSinkNode: an Oboe input stream whose data callback writes channel 0 into
// an FNARing and does nothing else. iOS attaches a sink node to AVAudioEngine; here the stream is the
// whole graph.

#include <oboe/Oboe.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>

#include "FoniaAudioRT.h"

namespace fonia {

/// Owns one ring. Shared by the Kotlin handle and every callback that may still write into it,
/// so the memory goes away only when the last of them lets go (iOS keeps it alive with ARC).
class RingBox {
public:
    explicit RingBox(FNARing *ring) : ring_(ring) {}
    ~RingBox() { FNARingDestroy(ring_); }
    RingBox(const RingBox &) = delete;
    RingBox &operator=(const RingBox &) = delete;

    FNARing *ring() const { return ring_; }

private:
    FNARing *const ring_;
};

/// Called on Oboe's error thread, never on the audio thread, with the id Kotlin gave the capture.
using ErrorSink = void (*)(int32_t id, int32_t error);

/// The data and error callbacks. The data callback copies into the ring: no allocation, no locks,
/// no logging, no JNI. It holds the ring through a shared pointer, so the ring outlives the stream.
class CaptureCallback final : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    CaptureCallback(std::shared_ptr<RingBox> ring, int32_t id, ErrorSink sink);

    /// The layout of the opened stream, set before it starts.
    void setLayout(int32_t channels, int32_t format);

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

    /// Runs the data callback's delivery on a buffer the caller provides. Tests use it to show the
    /// callback writes channel 0 into the ring without opening the microphone.
    void deliver(const float *interleaved, int32_t frames, int32_t channels);

private:
    std::shared_ptr<RingBox> box_;
    FNARing *const ring_;
    const int32_t id_;
    const ErrorSink sink_;
    std::atomic<int32_t> channels_{1};
    std::atomic<int32_t> format_{FNA_FORMAT_FLOAT};
};

/// One microphone stream at a time, opened, started and closed from Kotlin (never from the callback).
class Capture {
public:
    Capture(std::shared_ptr<RingBox> ring, int32_t id, ErrorSink sink);
    ~Capture();

    /// Opens an input stream. Fills `info` with: sample rate, channel count, format, input preset,
    /// device id, frames per burst, sharing mode, performance mode, session id. Returns an oboe::Result.
    int32_t open(int32_t sampleRate, int32_t inputPreset, int32_t deviceId, int32_t *info);
    int32_t start();
    /// Stops and closes the stream. No callback runs after this returns.
    void close();
    /// The oboe::StreamState of the stream, or Closed when there is none.
    int32_t state();
    /// Oboe's estimate of the input latency (age of the newest frame the callback received), in ms; -1 when the
    /// stream cannot tell (no stream, or no timestamps on this device). Never called on the audio thread.
    double latencyMillis();
    void deliver(const float *interleaved, int32_t frames, int32_t channels);

    static constexpr int32_t kInfoSize = 9;

private:
    std::mutex mutex_;
    std::shared_ptr<CaptureCallback> callback_;
    std::shared_ptr<oboe::AudioStream> stream_;
};

} // namespace fonia
