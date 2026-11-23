package tech.fonia.speaking

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * A deterministic formant "voice" for tests and previews: speech-like syllables (moving formants, gliding pitch,
 * consonant noise), held hesitation vowels (fixed formants, flat pitch) and pauses, all over a quiet room tone. Not
 * speech, but it has the properties the detectors and the cut rely on, with known ground truth.
 */
object SyntheticVoice {
    enum class Kind { SPEECH, HESITATION, HUM, PAUSE }

    data class Part(val kind: Kind, val seconds: Double)

    /** Ground truth: where each part landed, seconds. */
    data class Placed(val kind: Kind, val start: Double, val end: Double)

    class Result(val samples: FloatArray, val sampleRate: Double, val parts: List<Placed>)

    private val vowels = arrayOf(
        doubleArrayOf(750.0, 1250.0, 2500.0), // a
        doubleArrayOf(450.0, 1900.0, 2600.0), // e
        doubleArrayOf(300.0, 2300.0, 3000.0), // i
        doubleArrayOf(480.0, 900.0, 2500.0), // o
        doubleArrayOf(320.0, 800.0, 2400.0), // u
    )
    private val schwa = doubleArrayOf(600.0, 1400.0, 2500.0) // ă

    fun render(parts: List<Part>, sampleRate: Double = 16000.0, seed: Int = 7, f0: Double = 120.0, roomDb: Double = -62.0): Result {
        val rnd = Random(seed)
        val total = parts.sumOf { it.seconds }
        val n = (total * sampleRate).toInt()
        val out = FloatArray(n)
        val room = 10.0.pow(roomDb / 20)
        var brown = 0.0
        for (i in 0 until n) {
            brown = 0.97 * brown + rnd.nextDouble(-1.0, 1.0)
            out[i] = (room * brown * 0.4).toFloat()
        }
        val placed = ArrayList<Placed>()
        var t = 0.0
        for (p in parts) {
            val a = (t * sampleRate).toInt()
            val b = min(n, ((t + p.seconds) * sampleRate).toInt())
            when (p.kind) {
                Kind.PAUSE -> {}
                Kind.HESITATION -> vowel(out, a, b, sampleRate, { f0 * (1 + 0.002 * sin(2 * PI * 5 * it)) }, { schwa }, 0.12)
                Kind.HUM -> vowel(out, a, b, sampleRate, { f0 * 0.98 }, { doubleArrayOf(250.0, 900.0, 2200.0) }, 0.08, tilt = 1.6)
                Kind.SPEECH -> speech(out, a, b, sampleRate, rnd, f0)
            }
            placed.add(Placed(p.kind, t, t + p.seconds))
            t += p.seconds
        }
        return Result(out, sampleRate, placed)
    }

    private fun speech(out: FloatArray, a: Int, b: Int, sr: Double, rnd: Random, f0: Double) {
        var s = a
        var prev = vowels[rnd.nextInt(vowels.size)]
        while (s < b) {
            val consonant = (sr * rnd.nextDouble(0.03, 0.06)).toInt()
            val syl = (sr * rnd.nextDouble(0.11, 0.2)).toInt()
            // Consonant: band noise burst.
            for (k in s until min(b, s + consonant)) {
                val env = sin(PI * (k - s) / consonant)
                out[k] += (rnd.nextDouble(-1.0, 1.0) * 0.03 * env).toFloat()
            }
            val vStart = min(b, s + consonant)
            val vEnd = min(b, vStart + syl)
            val target = vowels[rnd.nextInt(vowels.size)]
            val from = prev
            val glide = rnd.nextDouble(-2.5, 2.5)
            val base = f0 * 2.0.pow(rnd.nextDouble(-2.0, 2.0) / 12)
            val len = max(1, vEnd - vStart)
            vowel(out, vStart, vEnd, sr, { tt -> base * 2.0.pow(glide * (tt * sr / len) / 12) }, { tt ->
                val x = min(1.0, tt * sr / (0.6 * len))
                DoubleArray(3) { from[it] + (target[it] - from[it]) * x }
            }, 0.1 * rnd.nextDouble(0.6, 1.3))
            prev = target
            s = vEnd
        }
    }

    /** Harmonics of a time-varying f0 shaped by three resonances; [pitch] and [formants] take seconds into the stretch. */
    private fun vowel(
        out: FloatArray, a: Int, b: Int, sr: Double, pitch: (Double) -> Double, formants: (Double) -> DoubleArray,
        amp: Double, tilt: Double = 1.0,
    ) {
        if (b <= a) return
        val maxH = 40
        val phase = DoubleArray(maxH + 1)
        val ramp = (0.025 * sr).toInt()
        val bw = doubleArrayOf(90.0, 110.0, 160.0)
        var fm = formants(0.0)
        for (i in a until b) {
            val tt = (i - a) / sr
            if ((i - a) % 64 == 0) fm = formants(tt)
            val f = pitch(tt)
            var v = 0.0
            var k = 1
            while (k <= maxH && k * f < 4000) {
                val hf = k * f
                var g = 0.0
                for (j in 0 until 3) { val d = (hf - fm[j]) / bw[j]; g += 1 / (1 + d * d) * (if (j == 0) 1.0 else 0.5) }
                phase[k] += 2 * PI * hf / sr
                v += g * sin(phase[k]) / k.toDouble().pow(0.5 * tilt)
                k++
            }
            val e = min(1.0, min((i - a).toDouble() / ramp, (b - i).toDouble() / ramp))
            out[i] += (amp * v * (1 - exp(-4 * e)) / (1 - exp(-4.0))).toFloat()
        }
    }
}
