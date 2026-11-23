package tech.fonia.live

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Synthetic test signals at 48 kHz: the worktree has no speech clips (they live in the content submodule). */
object Signals {
    const val RATE = 48_000.0

    /** Very faint white noise, like a quiet room. */
    fun quiet(seconds: Double, amplitude: Float = 3e-4f, seed: Int = 1): FloatArray {
        val r = Random(seed)
        return FloatArray((seconds * RATE).toInt()) { (r.nextFloat() * 2f - 1f) * amplitude }
    }

    /**
     * White noise through two cascaded RBJ band-pass biquads centred on [centerHz]: a hiss whose spectral centroid
     * sits near the centre (S ≈ 7 kHz, Ș ≈ 3.5 kHz).
     */
    fun hiss(centerHz: Double, seconds: Double, amplitude: Float = 0.25f, q: Double = 2.0, seed: Int = 7): FloatArray {
        val r = Random(seed)
        val n = (seconds * RATE).toInt()
        val x = FloatArray(n) { r.nextFloat() * 2f - 1f }
        bandPass(x, centerHz, q)
        bandPass(x, centerHz, q)
        normalize(x, amplitude)
        return x
    }

    /** A voiced vowel-like tone: [f0] with decaying harmonics. */
    fun voiced(f0: Double, seconds: Double, amplitude: Float = 0.3f): FloatArray {
        val n = (seconds * RATE).toInt()
        val x = FloatArray(n) { i ->
            var v = 0.0
            for (h in 1..12) v += sin(2 * PI * f0 * h * i / RATE) / h
            v.toFloat()
        }
        normalize(x, amplitude)
        return x
    }

    fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, at, p.size)
            at += p.size
        }
        return out
    }

    private fun bandPass(x: FloatArray, f: Double, q: Double) {
        val w = 2 * PI * f / RATE
        val alpha = sin(w) / (2 * q)
        val a0 = 1 + alpha
        val b0 = alpha / a0
        val b2 = -alpha / a0
        val a1 = -2 * cos(w) / a0
        val a2 = (1 - alpha) / a0
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in x.indices) {
            val x0 = x[i].toDouble()
            val y0 = b0 * x0 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
            x[i] = y0.toFloat()
        }
    }

    private fun normalize(x: FloatArray, peak: Float) {
        var m = 0f
        for (v in x) m = maxOf(m, kotlin.math.abs(v))
        if (m > 0f) for (i in x.indices) x[i] = x[i] / m * peak
    }
}

/** Feeds [samples] through [pipeline] hop by hop, calling [each] with the board's latest frame after every hop. */
fun LivePipeline.feed(samples: FloatArray, target: LiveTarget, start: Boolean = true, each: (LiveSnapshot, Int) -> Unit = { _, _ -> }) {
    if (start) start(Signals.RATE, target)
    val hop = LiveCapture.hopSize(Signals.RATE)
    var i = 0
    var t = 1L
    while (i < samples.size) {
        val n = minOf(hop, samples.size - i)
        process(samples, i, n, t)
        t += 10_000_000L
        each(board.latest(), i + n)
        i += n
    }
}
