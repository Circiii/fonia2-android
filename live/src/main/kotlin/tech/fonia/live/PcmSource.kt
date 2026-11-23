package tech.fonia.live

import tech.fonia.dsp.decodeWAV
import java.io.File
import java.util.concurrent.locks.LockSupport
import kotlin.math.min

/**
 * Where live PCM comes from (port). `:platform:audio` implements it over the Oboe ring; [ArrayPcmSource] and
 * [WavPcmSource] feed a file on the JVM and in the emulator's fixture mode. One reader: the analysis thread.
 */
interface PcmSource {
    /** Rate of the samples [read] returns now. May change when a route change rebuilds the stream. */
    val sampleRate: Double

    /** Copies up to [count] of the oldest unread mono samples. Returns how many (0 = none ready yet), or -1 once the source has ended. */
    fun read(dst: FloatArray, offset: Int, count: Int): Int

    /** CLOCK_MONOTONIC capture time of the last sample [read] returned; 0 when the source cannot tell. */
    fun newestCaptureNanos(): Long = 0L

    /** Samples the source lost (overruns). */
    val dropped: Int get() = 0

    /** Waits until data may be ready, at most [maxNanos]. The default sleeps; a native source can block on a semaphore. */
    fun awaitData(maxNanos: Long) {
        LockSupport.parkNanos(maxNanos)
    }

    /** The real input source, saved with each attempt (`unprocessed`, `voiceRecognition`, `fixture`, …). */
    val audioSource: String get() = "unknown"
}

/**
 * Mono samples from memory. [realTime] releases them as a microphone would (the take lasts as long as the audio);
 * otherwise everything is ready at once and [read] returns -1 at the end.
 */
class ArrayPcmSource(
    private val samples: FloatArray,
    override val sampleRate: Double,
    private val realTime: Boolean = false,
    private val clock: NanoClock = NanoClock.System,
    override val audioSource: String = "fixture",
) : PcmSource {
    private var position = 0
    private var startedNanos = 0L

    override fun read(dst: FloatArray, offset: Int, count: Int): Int {
        if (position >= samples.size) return -1
        val due = if (realTime) {
            if (startedNanos == 0L) startedNanos = clock.nanos()
            val elapsed = (clock.nanos() - startedNanos) / 1e9
            min(samples.size.toDouble(), elapsed * sampleRate).toInt()
        } else {
            samples.size
        }
        val n = min(count, due - position)
        if (n <= 0) return 0
        System.arraycopy(samples, position, dst, offset, n)
        position += n
        return n
    }

    override fun newestCaptureNanos(): Long =
        if (realTime && startedNanos != 0L) startedNanos + (position / sampleRate * 1e9).toLong() else 0L
}

/** WAV files (PCM 16/24/32-bit or float, decoded by `:engine:dsp`) as a [PcmSource]. */
object WavPcmSource {
    fun open(file: File, realTime: Boolean = false, clock: NanoClock = NanoClock.System): ArrayPcmSource =
        fromBytes(file.readBytes(), realTime, clock)

    fun fromBytes(bytes: ByteArray, realTime: Boolean = false, clock: NanoClock = NanoClock.System): ArrayPcmSource {
        val wav = decodeWAV(bytes)
        return ArrayPcmSource(wav.pcm, wav.sampleRate, realTime, clock, "fixture")
    }
}
