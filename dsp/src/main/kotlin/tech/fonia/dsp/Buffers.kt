package tech.fonia.dsp

/**
 * Swift's `FloatBuffer` is 16-byte aligned memory for vDSP. A JVM FloatArray is already the right
 * thing here: zero-initialised, and nothing in the port needs the alignment.
 */
internal typealias FloatBuffer = FloatArray

internal fun FloatBuffer.clear() = fill(0f)

/** A ring written twice, so the latest `capacity` samples are always contiguous. */
internal class MirrorRing(val capacity: Int) {
    private val storage = FloatBuffer(capacity * 2)
    private var writeIndex = 0

    /** The array the latest samples live in; they start at `latestOffset`, oldest first. */
    val storageArray: FloatArray get() = storage

    /** Oldest sample first: `storageArray[latestOffset ..< latestOffset + capacity]`. */
    val latestOffset: Int get() = writeIndex

    fun append(samples: FloatArray, offset: Int, count: Int) {
        var source = offset
        var remaining = count
        if (remaining > capacity) {
            source += remaining - capacity
            remaining = capacity
        }
        while (remaining > 0) {
            val run = min(remaining, capacity - writeIndex)
            System.arraycopy(samples, source, storage, writeIndex, run)
            System.arraycopy(samples, source, storage, writeIndex + capacity, run)
            writeIndex = (writeIndex + run) % capacity
            source += run
            remaining -= run
        }
    }

    fun append(value: Float) {
        storage[writeIndex] = value
        storage[writeIndex + capacity] = value
        writeIndex = (writeIndex + 1) % capacity
    }

    fun reset() {
        storage.clear()
        writeIndex = 0
    }
}
