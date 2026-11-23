package tech.fonia.dsp

import kotlin.math.cos
import kotlin.math.sqrt

/** Loudness envelope at a fine hop, measured over a Hann window spanning several pitch periods. */
internal class EnvelopeTracker(sampleRate: Double, thresholds: DSPThresholds.Envelope) {
    val hop: Int = max(1, (thresholds.hopSeconds * sampleRate).rounded().toInt())
    val rate: Double = sampleRate / hop.toDouble()
    private val window: FloatBuffer
    private val squared: FloatBuffer
    private val ring: MirrorRing
    private var sinceHop = 0

    init {
        val length = max(2, (thresholds.windowSeconds * sampleRate).rounded().toInt())
        window = FloatBuffer(length)
        squared = FloatBuffer(length)
        ring = MirrorRing(length)
        var sum = 0f
        for (i in 0 until length) {
            val value = 0.5f - 0.5f * cos(2f * PI_F * (i.toFloat() + 0.5f) / length.toFloat())
            window[i] = value
            sum += value
        }
        for (i in 0 until length) window[i] /= sum
    }

    /** Calls `emit` once per completed hop. */
    fun push(samples: FloatArray, offset: Int, count: Int, emit: FloatSink) {
        var source = offset
        var remaining = count
        while (remaining > 0) {
            val run = min(remaining, hop - sinceHop)
            ring.append(samples, source, run)
            source += run
            remaining -= run
            sinceHop += run
            if (sinceHop == hop) {
                sinceHop = 0
                emit.accept(currentValue())
            }
        }
    }

    private fun currentValue(): Float {
        // vDSP_vsq, then vDSP_dotpr with the window.
        val latest = ring.storageArray
        val start = ring.latestOffset
        for (i in 0 until ring.capacity) {
            val value = latest[start + i]
            squared[i] = value * value
        }
        val energy = dotpr(squared, 0, window, 0, ring.capacity)
        return sqrt(max(0f, energy))
    }

    fun reset() {
        ring.reset()
        sinceHop = 0
    }
}

internal fun envelopeOf(pcm: FloatArray, tracker: EnvelopeTracker): FloatArray {
    val output = FloatList(pcm.size / tracker.hop)
    tracker.push(pcm, 0, pcm.size) { output.add(it) }
    return output.toFloatArray()
}
