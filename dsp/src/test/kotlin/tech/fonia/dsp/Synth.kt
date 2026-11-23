package tech.fonia.dsp

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Synthetic test signals, ported from the web suite. Deterministic, so failures reproduce.

const val testRate = 48000.0

/** Swift's `LinearCongruential`: UInt32 state, wrapping arithmetic, `Float(UInt32)` rounding to nearest. */
class LinearCongruential(seed: Int = 1) {
    var state: Int = seed

    fun unit(): Float {
        state = state * 1_664_525 + 1_013_904_223
        return (state.toLong() and 0xFFFFFFFFL).toFloat() / 4_294_967_296f
    }

    fun next(): Long {
        state = state * 1_664_525 + 1_013_904_223
        val high = state.toLong() and 0xFFFFFFFFL
        state = state * 1_664_525 + 1_013_904_223
        return (high shl 32) or (state.toLong() and 0xFFFFFFFFL)
    }
}

fun sampleCount(seconds: Double, rate: Double = testRate): Int = (seconds * rate).rounded().toInt()

/**
 * Adds `amplitude * sin(2π f t + phase)` to `output`: `vDSP_vramp` phases (A + n·B in Float),
 * `vvsinf`, then `vDSP_vsma` (A·B + C).
 */
fun addSine(output: FloatArray, hz: Float, phase: Float, amplitude: Float, rate: Double) {
    val step = 2f * PI_F * hz / rate.toFloat()
    for (i in output.indices) {
        val angle = phase + i.toFloat() * step
        output[i] = sin(angle) * amplitude + output[i]
    }
}

/** Noise from many random-phase sinusoids in [low, high]. */
fun bandNoise(seconds: Double, low: Float, high: Float, amp: Float = 0.2f, seed: Int = 7, rate: Double = testRate): FloatArray =
    shapedNoise(seconds, listOf(low to 0f, high to 0f), amp = amp, seed = seed, rate = rate)

/**
 * Noise whose spectrum follows `points`, (Hz, dB) interpolated linearly in dB, silent outside them.
 * This is what gives the sibilant fixtures a realistic shape instead of a flat band.
 */
fun shapedNoise(
    seconds: Double, points: List<Pair<Float, Float>>, amp: Float = 0.2f, seed: Int = 7, rate: Double = testRate,
): FloatArray {
    val n = sampleCount(seconds, rate)
    val output = FloatArray(n)
    val generator = LinearCongruential(seed)
    val components = 400
    if (points.isEmpty()) return output
    val first = points.first()
    val last = points.last()
    var totalPower = 0f
    repeat(components) {
        val hz = first.first + (last.first - first.first) * generator.unit()
        var db = first.second
        for ((a, b) in points.zipWithNext()) {
            if (hz >= a.first && hz <= b.first) {
                db = a.second + (b.second - a.second) * (hz - a.first) / (b.first - a.first)
            }
        }
        val gain = 10f.pow(db / 20f)
        totalPower += gain * gain / 2f
        addSine(output, hz, phase = generator.unit() * 2f * PI_F, amplitude = gain, rate = rate)
    }
    val normalize = amp / sqrt(totalPower)
    return FloatArray(n) { output[it] * normalize }
}

/**
 * Sibilant spectra shaped after the adult measurements in the web's analysis: /s/ peaks high, /ʃ/
 * peaks low and has little above 11 kHz.
 */
fun sNoise(seconds: Double, amp: Float = 0.12f, seed: Int = 7): FloatArray =
    shapedNoise(seconds, listOf(1500f to -40f, 5000f to -18f, 8500f to 0f, 12000f to -5f, 19000f to -22f), amp = amp, seed = seed)

fun shNoise(seconds: Double, amp: Float = 0.12f, seed: Int = 11): FloatArray =
    shapedNoise(
        seconds, listOf(1500f to -25f, 3500f to 0f, 5500f to -4f, 9000f to -18f, 13000f to -38f, 19000f to -55f),
        amp = amp, seed = seed,
    )

/** A lateral hiss after Valentini-Botinhao et al. 2012: energy just below 5 kHz, steep fall above 11 kHz. */
fun lateralNoise(seconds: Double, amp: Float = 0.12f, seed: Int = 13): FloatArray =
    shapedNoise(
        seconds,
        listOf(1500f to -30f, 4800f to -3f, 7200f to 0f, 9500f to -3f, 11000f to -8f, 14000f to -40f, 19000f to -60f),
        amp = amp, seed = seed,
    )

/** Weak, shapeless friction, like English TH. */
fun diffuseNoise(seconds: Double, amp: Float = 0.012f, seed: Int = 17): FloatArray =
    shapedNoise(seconds, listOf(1500f to 0f, 15000f to -6f), amp = amp, seed = seed)

private fun formant(hz: Float): Float =
    exp(-((hz - 700f) / 300f).pow(2f)) + 0.7f * exp(-((hz - 1200f) / 400f).pow(2f)) + 0.15f

/** A vowel-like harmonic tone with formant emphasis around 700 and 1200 Hz. */
fun vowel(seconds: Double, f0: Float = 220f, amp: Float = 0.3f, rate: Double = testRate): FloatArray {
    val output = FloatArray(sampleCount(seconds, rate))
    val harmonics = maxOf(1, (4000f / f0).toInt())
    for (k in 1..harmonics) {
        val hz = k.toFloat() * f0
        addSine(output, hz, phase = 0f, amplitude = formant(hz) / k.toFloat(), rate = rate)
    }
    var peak = if (output.isEmpty()) 1f else kotlin.math.abs(output[0])
    for (value in output) if (peak < kotlin.math.abs(value)) peak = kotlin.math.abs(value)
    return FloatArray(output.size) { output[it] / peak * amp }
}

fun shaped(signal: FloatArray, rate: Double = testRate, gain: (Double) -> Float): FloatArray =
    FloatArray(signal.size) { signal[it] * gain(it.toDouble() / rate) }

/** A vowel with sharp periodic closures at `rate` Hz. */
fun trillSignal(seconds: Double, rate: Double = 25.0, f0: Float = 220f): FloatArray =
    shaped(vowel(seconds, f0 = f0)) { t ->
        // About 30% of each cycle is a deep closure.
        if ((t * rate) % 1.0 < 0.3) 0.06f else 1f
    }

fun wobble(seconds: Double, rate: Double = 5.0): FloatArray =
    shaped(vowel(seconds)) { t -> 0.6f + 0.4f * kotlin.math.sin(2 * Math.PI * rate * t).toFloat() }

/** V-closure-V, with a soft-edged dip so the closure resembles a tongue contact. */
fun tapSignal(closureMs: Double, flank: Double = 0.18, depth: Float = 0.08f, f0: Float = 220f): FloatArray {
    val total = flank * 2 + closureMs / 1000
    val middle = total / 2
    val half = closureMs / 2000
    return shaped(vowel(total, f0 = f0)) { t -> if (kotlin.math.abs(t - middle) < half) depth else 1f }
}

fun silence(seconds: Double, rate: Double = testRate): FloatArray = FloatArray(sampleCount(seconds, rate))

/** A little background noise so floors are realistic. */
fun withNoise(signal: FloatArray, amp: Float = 0.002f, seed: Int = 3): FloatArray {
    val generator = LinearCongruential(seed)
    return FloatArray(signal.size) { signal[it] + (generator.unit() * 2f - 1f) * amp }
}

fun envelope(signal: FloatArray, rate: Double = testRate): Pair<FloatArray, Float> {
    val tracker = EnvelopeTracker(rate, DSPThresholds.v1.envelope)
    val values = envelopeOf(signal, tracker)
    return values to tracker.rate.toFloat()
}

fun analyze(
    signal: FloatArray, group: VoiceGroup = VoiceGroup.FEMALE, calibration: TalkerCalibration? = null, rate: Double = testRate,
    thresholds: DSPThresholds = DSPThresholds.v1,
): ClipSummary = analyzeClip(signal, rate, group, calibration, thresholds)

/** One voiced syllable: F0 glides from `f0Start` to `f0End`, loudness rises and falls. */
fun syllable(seconds: Double, f0Start: Float, f0End: Float, amp: Float, rate: Double = 16000.0): FloatArray {
    val n = sampleCount(seconds, rate)
    val output = FloatArray(n)
    var phase = 0f
    val twoPi = 2f * PI_F
    for (i in 0 until n) {
        val x = i.toFloat() / n.toFloat()
        val f0 = f0Start + (f0End - f0Start) * x
        phase += twoPi * f0 / rate.toFloat()
        var value = 0f
        var k = 1f
        while (k * f0 < 4000f) {
            val hz = k * f0
            value += formant(hz) / k * sin(k * phase)
            k += 1f
        }
        output[i] = value * amp * sin(PI_F * x).pow(0.6f)
    }
    return output
}

/** Swift's `SyllableSpec`: `(seconds, f0Start, f0End, amp)`. */
data class SyllableSpec(val seconds: Double, val f0Start: Float, val f0End: Float, val amp: Float)

/** A word or phrase with 60 ms closures between syllables and padding around it. */
fun speech(
    parts: List<SyllableSpec>, gap: Double = 0.06, pauseAfter: Int = -1, pause: Double = 0.4, rate: Double = 16000.0,
): FloatArray {
    var pieces = silence(0.3, rate)
    for ((i, part) in parts.withIndex()) {
        pieces += syllable(part.seconds, part.f0Start, part.f0End, part.amp, rate)
        pieces += silence(if (i == pauseAfter) pause else gap, rate)
    }
    pieces += silence(0.3, rate)
    return withNoise(pieces, amp = 0.0005f)
}

/** A clean S- or Ș-carrying pseudo-word: vowel, frication, vowel. */
fun carrier(frication: FloatArray, f0: Float = 220f): FloatArray =
    withNoise(silence(0.2) + vowel(0.2, f0 = f0) + frication + vowel(0.2, f0 = f0) + silence(0.2))

/** Swift's `values.indices.max { values[$0] < values[$1] }`: the first index of the largest value. */
fun argmax(values: FloatArray): Int {
    var best = 0
    for (i in values.indices) if (values[best] < values[i]) best = i
    return best
}
