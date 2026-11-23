package tech.fonia.features.speaking

import tech.fonia.audio.CapturedClip
import tech.fonia.speech.TranscriptWord
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Speech made of harmonic syllables, after the DSP tests' synthetic signals (dsp `Synth.swift` and its port): every
 * word is as many syllables as it has vowel groups, so the clip analysis finds its nuclei, and the time of every word
 * is known exactly. That is what a scripted recogniser hands back as word times. Deterministic.
 */
object SyntheticSpeech {
    /** A take and the words in it, as the recogniser would write them, with their times. */
    class Spoken(val samples: FloatArray, val rate: Double, val words: List<TranscriptWord>) {
        val seconds: Double get() = samples.size / rate
        val clip: CapturedClip get() = CapturedClip(samples, rate)
    }

    private const val VOWELS = "aăâeiîouy"

    /** Vowel groups, between 1 and 4: enough to give a long word more syllables than a short one. */
    fun syllables(word: String): Int {
        val letters = word.lowercase().filter { it.isLetter() }
        var groups = 0
        var inVowel = false
        for (c in letters) {
            val vowel = c in VOWELS
            if (vowel && !inVowel) groups += 1
            inVowel = vowel
        }
        return groups.coerceIn(1, 4)
    }

    /**
     * [words] said one after the other. Each [stressed] word has its first syllable twice as long, more than twice as
     * loud and a quarter higher. [pauseAfter] gives the silence after each word; [heard] how the transcript writes
     * it; [dropped] words are left out of the take (and out of the transcript). [f0] is the voice's pitch.
     */
    fun say(
        words: List<String>,
        stressed: Set<Int> = emptySet(),
        pauseAfter: (Int) -> Double = { 0.06 },
        heard: (Int, String) -> String = { _, word -> word },
        dropped: Set<Int> = emptySet(),
        rate: Double = 16000.0,
        f0: Float = 190f,
        lead: Double = 0.4,
        seed: Int = 3,
    ): Spoken {
        val pieces = ArrayList<FloatArray>()
        var t = 0.0
        fun add(piece: FloatArray) {
            pieces.add(piece)
            t += piece.size / rate
        }
        val timed = ArrayList<TranscriptWord>()
        add(FloatArray((lead * rate).toInt()))
        for ((i, word) in words.withIndex()) {
            if (i in dropped) continue
            val start = t
            val count = syllables(word)
            val pitch = f0 - (i % 9) * 1.5f
            for (s in 0 until count) {
                val strong = i in stressed && s == 0
                val seconds = (if (strong) 0.28 else 0.13 + 0.02 * ((i * 7 + s * 3) % 3)).toDouble()
                val amp = if (strong) 0.5f else 0.2f + 0.02f * ((i + s) % 2)
                val high = if (strong) pitch * 1.25f else pitch
                add(syllable(seconds, high, high - 8f, amp, rate))
                if (s < count - 1) add(FloatArray((0.045 * rate).toInt()))
            }
            timed.add(TranscriptWord(word = heard(i, word), start = start, end = t, prob = 0.92))
            add(FloatArray((pauseAfter(i) * rate).toInt()))
        }
        add(FloatArray((0.4 * rate).toInt()))
        val samples = FloatArray(pieces.sumOf { it.size })
        var at = 0
        for (piece in pieces) {
            piece.copyInto(samples, at)
            at += piece.size
        }
        // A little room noise, so the floors are realistic.
        var state = seed
        for (k in samples.indices) {
            state = state * 1_664_525 + 1_013_904_223
            samples[k] += ((state.toLong() and 0xFFFFFFFFL).toFloat() / 4_294_967_296f * 2f - 1f) * 0.0005f
        }
        return Spoken(samples, rate, timed)
    }

    /** Pauses as a reader makes them: longer after a full stop, shorter after a comma, short between words. */
    fun readerPauses(words: List<TextWord>, skip: Set<Int> = emptySet(), extra: Map<Int, Double> = emptyMap()): (Int) -> Double = { i ->
        val word = words[i]
        extra[i] ?: when {
            i in skip -> 0.05
            word.endsSentence -> 0.6
            word.mark != null -> 0.4
            else -> 0.06
        }
    }

    private fun formant(hz: Float): Float =
        exp(-((hz - 700f) / 300f).pow(2f)) + 0.7f * exp(-((hz - 1200f) / 400f).pow(2f)) + 0.15f

    /** One voiced syllable: the pitch glides from [f0Start] to [f0End], the level rises and falls. */
    private fun syllable(seconds: Double, f0Start: Float, f0End: Float, amp: Float, rate: Double): FloatArray {
        val n = (seconds * rate).toInt()
        val output = FloatArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val x = i.toFloat() / n.toFloat()
            val f0 = f0Start + (f0End - f0Start) * x
            phase += 2 * PI * f0 / rate
            var value = 0f
            var k = 1
            while (k * f0 < 4000f) {
                value += formant(k * f0) / k * sin(k * phase).toFloat()
                k += 1
            }
            output[i] = value * amp * sin(PI.toFloat() * x).pow(0.6f)
        }
        return output
    }
}
