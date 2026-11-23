// Port of FoniaAudioRT.m: the same ring, statement for statement, with C++ atomics in place of C11 ones.

#include "FoniaAudioRT.h"

#include <atomic>
#include <cstdlib>
#include <cstring>
#include <new>

struct FNARing {
    size_t mask = 0;
    float *data = nullptr;
    std::atomic<size_t> head{0};
    std::atomic<size_t> tail{0};
    std::atomic<size_t> dropped{0};
};

// The audio thread must never take a lock hidden inside an atomic.
static_assert(std::atomic<size_t>::is_always_lock_free, "size_t atomics must be lock-free");

FNARing *FNARingCreate(size_t capacityPowerOfTwo) {
    if (capacityPowerOfTwo == 0 || capacityPowerOfTwo > SIZE_MAX / (2 * sizeof(float))) {
        return nullptr;
    }
    size_t capacity = 1;
    while (capacity < capacityPowerOfTwo) {
        capacity <<= 1;
    }

    FNARing *ring = new (std::nothrow) FNARing();
    float *data = static_cast<float *>(std::malloc(capacity * sizeof(float)));
    if (ring == nullptr || data == nullptr) {
        delete ring;
        std::free(data);
        return nullptr;
    }
    // Touching every page here keeps page faults off the audio thread.
    std::memset(data, 0, capacity * sizeof(float));

    ring->mask = capacity - 1;
    ring->data = data;
    return ring;
}

void FNARingDestroy(FNARing *ring) {
    if (ring == nullptr) {
        return;
    }
    std::free(ring->data);
    delete ring;
}

size_t FNARingWrite(FNARing *ring, const float *src, size_t count) FNA_NONBLOCKING {
    size_t head = ring->head.load(std::memory_order_relaxed);
    size_t tail = ring->tail.load(std::memory_order_acquire);
    size_t room = (ring->mask + 1) - (head - tail);
    size_t accepted = count < room ? count : room;
    for (size_t i = 0; i < accepted; i++) {
        ring->data[(head + i) & ring->mask] = src[i];
    }
    ring->head.store(head + accepted, std::memory_order_release);
    if (accepted < count) {
        ring->dropped.fetch_add(count - accepted, std::memory_order_relaxed);
    }
    return accepted;
}

size_t FNARingRead(FNARing *ring, float *dst, size_t count) {
    size_t tail = ring->tail.load(std::memory_order_relaxed);
    size_t head = ring->head.load(std::memory_order_acquire);
    size_t ready = head - tail;
    size_t taken = count < ready ? count : ready;
    for (size_t i = 0; i < taken; i++) {
        dst[i] = ring->data[(tail + i) & ring->mask];
    }
    ring->tail.store(tail + taken, std::memory_order_release);
    return taken;
}

size_t FNARingCapacity(const FNARing *ring) {
    return ring->mask + 1;
}

size_t FNARingAvailable(const FNARing *ring) {
    size_t tail = ring->tail.load(std::memory_order_acquire);
    size_t head = ring->head.load(std::memory_order_acquire);
    return head - tail;
}

size_t FNARingDropped(const FNARing *ring) {
    return ring->dropped.load(std::memory_order_relaxed);
}

void FNADeliver(FNARing *ring, const void *data, int32_t frames, int32_t channels, int32_t format) FNA_NONBLOCKING {
    if (ring == nullptr || data == nullptr || frames <= 0 || channels <= 0) {
        return;
    }
    if (format == FNA_FORMAT_FLOAT && channels == 1) {
        FNARingWrite(ring, static_cast<const float *>(data), static_cast<size_t>(frames));
        return;
    }
    // Converting through a small stack buffer keeps the callback free of allocation.
    constexpr int32_t kChunk = 256;
    float chunk[kChunk];
    const size_t stride = static_cast<size_t>(channels);
    for (int32_t done = 0; done < frames;) {
        const int32_t n = frames - done < kChunk ? frames - done : kChunk;
        const size_t first = static_cast<size_t>(done) * stride;
        if (format == FNA_FORMAT_FLOAT) {
            const float *src = static_cast<const float *>(data) + first;
            for (int32_t i = 0; i < n; i++) {
                chunk[i] = src[static_cast<size_t>(i) * stride];
            }
        } else if (format == FNA_FORMAT_I16) {
            const int16_t *src = static_cast<const int16_t *>(data) + first;
            for (int32_t i = 0; i < n; i++) {
                chunk[i] = static_cast<float>(src[static_cast<size_t>(i) * stride]) * (1.0f / 32768.0f);
            }
        } else if (format == FNA_FORMAT_I32) {
            const int32_t *src = static_cast<const int32_t *>(data) + first;
            for (int32_t i = 0; i < n; i++) {
                chunk[i] = static_cast<float>(src[static_cast<size_t>(i) * stride]) * (1.0f / 2147483648.0f);
            }
        } else {
            // Other formats are refused when the stream opens.
            return;
        }
        FNARingWrite(ring, chunk, static_cast<size_t>(n));
        done += n;
    }
}
