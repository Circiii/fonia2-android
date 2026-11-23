package tech.fonia.audio

import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-free ring between a capture source and the analysis task: one writer and one reader may run at a
 * time, and their accesses are ordered with acquire/release atomics.
 *
 * On a device it is FoniaAudioRT's C ring ([NativeCaptureRing]), so the Oboe callback writes into it with
 * no Kotlin and no JNI on the audio thread. On the JVM, where the native library cannot load, it is a
 * line-by-line Kotlin twin ([JvmCaptureRing]) so the ring and the capture engine run in unit tests.
 *
 * iOS frees the ring in `deinit`; Kotlin has none, so the owner calls [close]. A native ring that a
 * microphone stream still writes into lives on until that stream is gone.
 */
abstract class CaptureRing internal constructor() : AutoCloseable {
    abstract val capacity: Int

    /** Space the writer can fill without dropping samples. */
    val free: Int get() = capacity - available

    /** Samples that did not fit since the ring was created. */
    abstract val dropped: Int

    internal abstract val available: Int

    /** Copies as many of `samples[offset until offset + count]` as fit and returns that number. */
    abstract fun write(samples: FloatArray, offset: Int = 0, count: Int = samples.size - offset): Int

    /** Copies up to [count] of the oldest samples into `buffer[offset...]` and returns how many were copied. */
    internal abstract fun read(buffer: FloatArray, offset: Int = 0, count: Int = buffer.size - offset): Int

    companion object {
        /** Capacity is rounded up to a power of two. */
        operator fun invoke(capacity: Int): CaptureRing {
            require(capacity > 0) { "Cannot allocate a capture ring of $capacity samples" }
            return if (NativeAudio.isAvailable) NativeCaptureRing(capacity) else JvmCaptureRing(capacity)
        }
    }
}

/** FoniaAudioRT's ring (`FNARing`), reached through JNI from Kotlin and directly from the Oboe callback. */
internal class NativeCaptureRing(capacity: Int) : CaptureRing() {
    internal companion object {
        internal const val CLOSED = 1L shl 62
    }

    internal val handle: Long = NativeAudio.ringCreate(capacity)

    /** The closed flag plus the number of calls in flight, so [close] never frees memory a call still uses. */
    internal val users = AtomicLong(0)

    init {
        check(handle != 0L) { "Cannot allocate a capture ring of $capacity samples" }
    }

    override val capacity: Int = withHandle(0) { NativeAudio.ringCapacity(it) }

    override val dropped: Int
        get() = withHandle(0) { NativeAudio.ringDropped(it).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }

    override val available: Int get() = withHandle(0) { NativeAudio.ringAvailable(it) }

    override fun write(samples: FloatArray, offset: Int, count: Int): Int {
        checkRange(samples, offset, count)
        return withHandle(0) { NativeAudio.ringWrite(it, samples, offset, count) }
    }

    override fun read(buffer: FloatArray, offset: Int, count: Int): Int {
        checkRange(buffer, offset, count)
        return withHandle(0) { NativeAudio.ringRead(it, buffer, offset, count) }
    }

    /** Runs [body] with the native handle, or returns [closedValue] once the ring is closed. */
    internal inline fun <T> withHandle(closedValue: T, body: (Long) -> T): T {
        while (true) {
            val state = users.get()
            if (state and CLOSED != 0L) return closedValue
            if (users.compareAndSet(state, state + 1)) break
        }
        try {
            return body(handle)
        } finally {
            if (users.decrementAndGet() == CLOSED) NativeAudio.ringDestroy(handle)
        }
    }

    override fun close() {
        while (true) {
            val state = users.get()
            if (state and CLOSED != 0L) return
            if (users.compareAndSet(state, state or CLOSED)) {
                if (state == 0L) NativeAudio.ringDestroy(handle)
                return
            }
        }
    }

    private fun checkRange(array: FloatArray, offset: Int, count: Int) {
        require(offset >= 0 && count >= 0 && offset + count <= array.size) {
            "Range $offset+$count is outside an array of ${array.size}"
        }
    }
}

/** The Kotlin twin of `FNARing`, statement for statement: `get` is an acquire load, `lazySet` a release store. */
internal class JvmCaptureRing(capacityPowerOfTwo: Int) : CaptureRing() {
    private val mask: Long
    private val data: FloatArray
    private val head = AtomicLong(0)
    private val tail = AtomicLong(0)
    private val droppedCount = AtomicLong(0)

    init {
        var size = 1L
        while (size < capacityPowerOfTwo) size = size shl 1
        check(size <= Int.MAX_VALUE) { "Cannot allocate a capture ring of $capacityPowerOfTwo samples" }
        mask = size - 1
        data = FloatArray(size.toInt())
    }

    override val capacity: Int get() = (mask + 1).toInt()

    override val dropped: Int get() = droppedCount.get().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    override val available: Int get() = (head.get() - tail.get()).toInt()

    override fun write(samples: FloatArray, offset: Int, count: Int): Int {
        val head = head.get()
        val tail = tail.get()
        val room = (mask + 1) - (head - tail)
        val accepted = minOf(count.toLong(), room).toInt()
        for (i in 0 until accepted) {
            data[((head + i) and mask).toInt()] = samples[offset + i]
        }
        this.head.lazySet(head + accepted)
        if (accepted < count) droppedCount.addAndGet((count - accepted).toLong())
        return accepted
    }

    override fun read(buffer: FloatArray, offset: Int, count: Int): Int {
        val tail = tail.get()
        val head = head.get()
        val ready = head - tail
        val taken = minOf(count.toLong(), ready).toInt()
        for (i in 0 until taken) {
            buffer[offset + i] = data[((tail + i) and mask).toInt()]
        }
        this.tail.lazySet(tail + taken)
        return taken
    }

    override fun close() = Unit
}
