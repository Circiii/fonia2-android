#include "FoniaCapture.h"

#include <utility>

namespace fonia {

CaptureCallback::CaptureCallback(std::shared_ptr<RingBox> ring, int32_t id, ErrorSink sink)
    : box_(std::move(ring)), ring_(box_->ring()), id_(id), sink_(sink) {}

void CaptureCallback::setLayout(int32_t channels, int32_t format) {
    channels_.store(channels, std::memory_order_relaxed);
    format_.store(format, std::memory_order_relaxed);
}

oboe::DataCallbackResult CaptureCallback::onAudioReady(oboe::AudioStream * /* stream */, void *audioData,
                                                       int32_t numFrames) {
    FNADeliver(ring_, audioData, numFrames, channels_.load(std::memory_order_relaxed),
               format_.load(std::memory_order_relaxed));
    return oboe::DataCallbackResult::Continue;
}

void CaptureCallback::onErrorAfterClose(oboe::AudioStream * /* stream */, oboe::Result error) {
    // A disconnect (route change, media server restart) closes the stream. Kotlin rebuilds it, as
    // MicrophoneGraph does on AVAudioEngineConfigurationChange; this only reports it.
    if (sink_ != nullptr) {
        sink_(id_, static_cast<int32_t>(error));
    }
}

void CaptureCallback::deliver(const float *interleaved, int32_t frames, int32_t channels) {
    FNADeliver(ring_, interleaved, frames, channels, FNA_FORMAT_FLOAT);
}

Capture::Capture(std::shared_ptr<RingBox> ring, int32_t id, ErrorSink sink)
    : callback_(std::make_shared<CaptureCallback>(std::move(ring), id, sink)) {}

Capture::~Capture() {
    close();
}

int32_t Capture::open(int32_t sampleRate, int32_t inputPreset, int32_t deviceId, int32_t *info) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setSampleRate(sampleRate)
        ->setInputPreset(static_cast<oboe::InputPreset>(inputPreset))
        // A session id lets Kotlin find and switch off platform noise suppression or gain control.
        ->setSessionId(oboe::SessionId::Allocate)
        // A child's voice is not shared with other apps that capture at the same time.
        ->setPrivacySensitiveMode(oboe::PrivacySensitiveMode::Enabled)
        ->setDataCallback(callback_)
        ->setErrorCallback(callback_);
    if (deviceId > 0) {
        builder.setDeviceId(deviceId);
    }

    oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        stream_.reset();
        return static_cast<int32_t>(result);
    }
    info[0] = stream_->getSampleRate();
    info[1] = stream_->getChannelCount();
    info[2] = static_cast<int32_t>(stream_->getFormat());
    info[3] = static_cast<int32_t>(stream_->getInputPreset());
    info[4] = stream_->getDeviceId();
    info[5] = stream_->getFramesPerBurst();
    info[6] = static_cast<int32_t>(stream_->getSharingMode());
    info[7] = static_cast<int32_t>(stream_->getPerformanceMode());
    info[8] = static_cast<int32_t>(stream_->getSessionId());
    callback_->setLayout(stream_->getChannelCount(), static_cast<int32_t>(stream_->getFormat()));
    return static_cast<int32_t>(oboe::Result::OK);
}

int32_t Capture::start() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!stream_) {
        return static_cast<int32_t>(oboe::Result::ErrorNull);
    }
    return static_cast<int32_t>(stream_->start());
}

void Capture::close() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
}

int32_t Capture::state() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!stream_) {
        return static_cast<int32_t>(oboe::StreamState::Closed);
    }
    return static_cast<int32_t>(stream_->getState());
}

double Capture::latencyMillis() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!stream_ || stream_->getState() != oboe::StreamState::Started) {
        return -1.0;
    }
    oboe::ResultWithValue<double> latency = stream_->calculateLatencyMillis();
    return latency ? latency.value() : -1.0;
}

void Capture::deliver(const float *interleaved, int32_t frames, int32_t channels) {
    callback_->deliver(interleaved, frames, channels);
}

} // namespace fonia
