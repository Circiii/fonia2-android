package tech.fonia.speaking.coach

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/** Syllable-like bursts: a harmonic vowel at [f0] (or a gliding f0), raised-cosine envelopes at [rate] per second of speech. */
internal object Bursts {
    class Take(val samples: FloatArray, val pitch: FloatArray, val rate: Double)

    /**
     * [seconds] of speech: phrases of [phraseSyllables] syllables at [rate] syllables/s, separated by [pauseSeconds]
     * of room tone. [pitch] gives f0 at a time (Hz). Returns the samples and the f0 per 10 ms hop (0 = silence).
     */
    fun render(
        seconds: Double,
        rate: Double,
        sampleRate: Double = 16000.0,
        gainDb: Double = -20.0,
        phraseSyllables: Int = 8,
        pauseSeconds: Double = 0.4,
        pitch: (Double) -> Double = { 110.0 },
        gainAt: (Double) -> Double = { 0.0 },
    ): Take {
        val n = (seconds * sampleRate).toInt()
        val out = FloatArray(n)
        val hops = (seconds / 0.01).toInt() + 1
        val f0s = FloatArray(hops)
        val syll = 1.0 / rate
        var t = 0.0
        var phase = 0.0
        val noise = java.util.Random(3)
        var i = 0
        while (i < n) {
            out[i] = (noise.nextGaussian() * 10.0.pow(-65.0 / 20)).toFloat()
            i++
        }
        while (t < seconds) {
            for (s in 0 until phraseSyllables) {
                val start = t
                val end = t + syll
                if (end > seconds) break
                val a = (start * sampleRate).toInt()
                val b = (end * sampleRate).toInt()
                for (k in a until b) {
                    val time = k / sampleRate
                    val u = (time - start) / syll
                    // Vowel 70 % of the syllable, a dip for the consonant.
                    val env = if (u < 0.75) sin(PI * u / 0.75).pow(2) else 0.02
                    val f0 = pitch(time)
                    phase += 2 * PI * f0 / sampleRate
                    var v = 0.0
                    for (hn in 1..12) v += sin(phase * hn) / hn * (if (hn * f0 in 300.0..2500.0) 1.6 else 0.6)
                    val g = 10.0.pow((gainDb + gainAt(time)) / 20)
                    out[k] += (v * env * g * 0.3).toFloat()
                    val hop = (time / 0.01).toInt()
                    if (env > 0.2) f0s[hop] = f0.toFloat()
                }
                t = end
            }
            t += pauseSeconds
        }
        return Take(out, f0s, rate)
    }

    /** Runs [take] through a fresh coach in 10 ms blocks, calling [each] after every block with the time. */
    fun run(take: Take, coach: AudioCoach, each: (Double) -> Unit = {}) {
        val hop = (coach.sampleRate * 0.01).toInt()
        var i = 0
        while (i < take.samples.size) {
            val count = minOf(hop, take.samples.size - i)
            val h = i / hop
            coach.process(take.samples, i, count, take.pitch.getOrElse(h) { 0f })
            i += count
            each(i / coach.sampleRate)
        }
    }

    fun maxOf(a: Double, b: Double) = max(a, b)
}
