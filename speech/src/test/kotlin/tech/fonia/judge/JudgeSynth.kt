package tech.fonia.judge

import tech.fonia.live.Clip
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Synthetic takes for the judge's tests: deterministic, so failures reproduce.

const val RATE = 48000.0

fun specDir(): File = File(requireNotNull(System.getProperty("fonia.spec")) { "fonia.spec is not set" })

fun loadSpec(): JudgeSpec = JudgeSpec.load(specDir())

class Lcg(seed: Int) {
    private var state = seed

    fun unit(): Float {
        state = state * 1_664_525 + 1_013_904_223
        return (state.toLong() and 0xFFFFFFFFL).toFloat() / 4_294_967_296f
    }
}

fun n(seconds: Double): Int = (seconds * RATE).toInt()

/** Noise whose spectrum follows (Hz, dB) points, from 300 random-phase sines. */
fun shaped(seconds: Double, points: List<Pair<Float, Float>>, amp: Float, seed: Int = 7): FloatArray {
    val out = FloatArray(n(seconds))
    val g = Lcg(seed)
    var power = 0.0
    repeat(300) {
        val hz = points.first().first + (points.last().first - points.first().first) * g.unit()
        var db = points.first().second
        for ((a, b) in points.zipWithNext()) if (hz >= a.first && hz <= b.first) db = a.second + (b.second - a.second) * (hz - a.first) / (b.first - a.first)
        val gain = 10f.pow(db / 20f)
        power += gain * gain / 2.0
        val phase = g.unit() * 2 * PI
        val step = 2 * PI * hz / RATE
        for (i in out.indices) out[i] += (gain * sin(phase + i * step)).toFloat()
    }
    val k = (amp / sqrt(power)).toFloat()
    for (i in out.indices) out[i] *= k
    return out
}

fun sHiss(seconds: Double, amp: Float = 0.1f) =
    shaped(seconds, listOf(1500f to -40f, 5000f to -18f, 8500f to 0f, 12000f to -5f, 19000f to -22f), amp)

fun shHiss(seconds: Double, amp: Float = 0.1f) =
    shaped(seconds, listOf(1500f to -25f, 3500f to 0f, 5500f to -4f, 9000f to -18f, 13000f to -38f, 19000f to -55f), amp, seed = 11)

/** A vowel-like harmonic tone. */
fun vowel(seconds: Double, f0: Float = 220f, amp: Float = 0.3f): FloatArray {
    val out = FloatArray(n(seconds))
    val harmonics = (4000f / f0).toInt()
    for (k in 1..harmonics) {
        val hz = k * f0
        val g = exp(-((hz - 700f) / 300f).pow(2)) + 0.7f * exp(-((hz - 1200f) / 400f).pow(2)) + 0.15f
        val step = 2 * PI * hz / RATE
        for (i in out.indices) out[i] += (g / k * sin(i * step)).toFloat()
    }
    val peak = out.maxOf { abs(it) }
    for (i in out.indices) out[i] = out[i] / peak * amp
    // 10 ms fades so edges do not click.
    val fade = n(0.01)
    for (i in 0 until minOf(fade, out.size / 2)) {
        out[i] *= i.toFloat() / fade
        out[out.size - 1 - i] *= i.toFloat() / fade
    }
    return out
}

fun quiet(seconds: Double, amp: Float = 0.0005f, seed: Int = 3): FloatArray {
    val g = Lcg(seed)
    return FloatArray(n(seconds)) { (g.unit() * 2 - 1) * amp }
}

fun cat(vararg parts: FloatArray): FloatArray {
    val out = FloatArray(parts.sumOf { it.size })
    var o = 0
    for (p in parts) { p.copyInto(out, o); o += p.size }
    return out
}

fun clip(samples: FloatArray, dropped: Int = 0) = Clip(samples, RATE, dropped = dropped, audioSource = "fixture")

/** "asa"-like: silence, vowel, hiss, vowel, silence. */
fun vcv(hiss: FloatArray, f0: Float = 220f) = cat(quiet(0.3), vowel(0.22, f0), hiss, vowel(0.25, f0), quiet(0.3))
