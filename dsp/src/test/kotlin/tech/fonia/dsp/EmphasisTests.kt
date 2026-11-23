package tech.fonia.dsp

import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The emphasis rule (V2, H F2) on synthetic speech whose words have known spans: a word is a run of harmonic
// syllables, so its length, level and pitch are set by hand and the DSP has to find them again.

private const val rate = 48000.0

/** A synthetic sentence and where each of its words is. */
private class Sentence(val signal: FloatArray, val spans: List<TimeSpan>)

/** One word: its syllables, each (seconds, f0 at start, f0 at end, amplitude). */
private class Word(vararg val syllables: SyllableSpec)

private fun plain(f0: Float = 200f) = Word(SyllableSpec(0.14, f0, f0 - 8f, 0.25f), SyllableSpec(0.13, f0 - 8f, f0 - 14f, 0.22f))

private fun sentence(words: List<Word>, wordGap: Double = 0.07, syllableGap: Double = 0.05, pauseAfter: Map<Int, Double> = emptyMap()): Sentence {
    val pieces = ArrayList<FloatArray>()
    val spans = ArrayList<TimeSpan>()
    var t = 0.0
    fun add(piece: FloatArray) {
        pieces.add(piece)
        t += piece.size / rate
    }
    add(silence(0.3, rate))
    for ((w, word) in words.withIndex()) {
        val start = t
        for ((s, part) in word.syllables.withIndex()) {
            add(syllable(part.seconds, part.f0Start, part.f0End, part.amp, rate))
            if (s < word.syllables.size - 1) add(silence(syllableGap, rate))
        }
        spans.add(TimeSpan(start, t))
        add(silence(pauseAfter[w] ?: wordGap, rate))
    }
    add(silence(0.3, rate))
    val total = pieces.sumOf { it.size }
    val signal = FloatArray(total)
    var at = 0
    for (piece in pieces) {
        piece.copyInto(signal, at)
        at += piece.size
    }
    return Sentence(withNoise(signal, amp = 0.0005f), spans)
}

private fun analyse(s: Sentence, keys: Set<Int> = emptySet(), spans: List<TimeSpan?> = s.spans): EmphasisAnalysis =
    analyzeEmphasis(analyzeClip(s.signal, rate, VoiceGroup.FEMALE, null), spans, keys)

class EmphasisTests {
    /** Longer, louder and higher: all three cues against the plain words around it. */
    private val strong = Word(SyllableSpec(0.28, 240f, 262f, 0.55f), SyllableSpec(0.14, 230f, 215f, 0.3f))

    /** Longer and louder, at the same pitch as the rest. */
    private val longLoud = Word(SyllableSpec(0.28, 200f, 192f, 0.55f), SyllableSpec(0.14, 192f, 186f, 0.3f))

    /** Only the pitch goes up. */
    private val onlyHigh = Word(SyllableSpec(0.14, 262f, 268f, 0.25f), SyllableSpec(0.13, 262f, 255f, 0.22f))

    /** Longer and higher, not louder. */
    private val longHigh = Word(SyllableSpec(0.28, 262f, 268f, 0.25f), SyllableSpec(0.14, 262f, 255f, 0.22f))

    @Test
    fun aLongerLouderHigherWordIsStressed() {
        val analysis = analyse(sentence(listOf(plain(), plain(), strong, plain(), plain(), plain())), keys = setOf(2))
        val key = analysis.words[2]
        assertEquals(EmphasisState.STRESSED, key.state, "$key")
        assertEquals(setOf(EmphasisCue.DURATION, EmphasisCue.LOUDNESS, EmphasisCue.PITCH), key.passed, "$key")
        assertTrue(key.durationGain!! >= 0.3f && key.loudnessGainDb!! >= 3f && key.pitchGainSt!! >= 2f, "$key")
        for (other in analysis.words.filter { it.index != 2 }) assertFalse(other.isStressed, "$other")
        val tally = analysis.tally
        assertEquals(1, tally.stressedKeyWords)
        assertEquals(1f, tally.keyShare)
        assertTrue(tally.meetsKeyTarget)
        assertFalse(tally.everythingStressed)
        assertTrue(analysis.extraStressed.isEmpty())
    }

    @Test
    fun twoCuesOfThreeAreEnough() {
        val longAndLoud = analyse(sentence(listOf(plain(), plain(), longLoud, plain(), plain())), keys = setOf(2)).words[2]
        assertEquals(EmphasisState.STRESSED, longAndLoud.state, "$longAndLoud")
        assertEquals(setOf(EmphasisCue.DURATION, EmphasisCue.LOUDNESS), longAndLoud.passed, "$longAndLoud")
        assertTrue(abs(longAndLoud.pitchGainSt!!) < 2f, "$longAndLoud")

        val longAndHigh = analyse(sentence(listOf(plain(), plain(), longHigh, plain(), plain())), keys = setOf(2)).words[2]
        assertEquals(EmphasisState.STRESSED, longAndHigh.state, "$longAndHigh")
        assertEquals(setOf(EmphasisCue.DURATION, EmphasisCue.PITCH), longAndHigh.passed, "$longAndHigh")
    }

    @Test
    fun pitchAloneIsCloseButNotStressed() {
        val high = analyse(sentence(listOf(plain(), plain(), onlyHigh, plain(), plain())), keys = setOf(2)).words[2]
        assertEquals(EmphasisState.CLOSE, high.state, "$high")
        assertEquals(setOf(EmphasisCue.PITCH), high.passed, "$high")
        assertTrue(high.pitchGainSt!! >= 4f, "$high")
        // Duration and loudness weigh 1.5 each, pitch 1: a word stressed by length and level stands out more than
        // one that only went up, even by twice its bar.
        val longAndLoud = analyse(sentence(listOf(plain(), plain(), longLoud, plain(), plain())), keys = setOf(2)).words[2]
        assertTrue(longAndLoud.prominence!! > high.prominence!!, "${longAndLoud.prominence} vs ${high.prominence}")
        assertFalse(analyse(sentence(listOf(plain(), plain(), onlyHigh, plain(), plain())), keys = setOf(2)).tally.meetsKeyTarget)
    }

    @Test
    fun aLevelReadingStressesNothing() {
        val analysis = analyse(sentence(List(9) { plain(200f - it) }), keys = setOf(4))
        for (word in analysis.words) {
            assertTrue(word.isMeasured, "$word")
            assertFalse(word.isStressed, "$word")
        }
        assertEquals(0f, analysis.tally.stressedShare)
        assertEquals(0f, analysis.tally.keyShare)
        assertFalse(analysis.tally.everythingStressed)
    }

    @Test
    fun aWordThatWasNotReadIsNeitherMeasuredNorANeighbour() {
        val s = sentence(listOf(plain(), plain(), strong, plain(), plain()))
        val spans: List<TimeSpan?> = s.spans.mapIndexed { i, span -> if (i == 1) null else span }
        val analysis = analyse(s, keys = setOf(1, 2), spans = spans)
        assertEquals(EmphasisState.NOT_MEASURED, analysis.words[1].state)
        assertNull(analysis.words[1].cues)
        assertFalse(1 in analysis.words[2].neighbours)
        assertTrue(analysis.words[2].isStressed, "${analysis.words[2]}")
        // The word that was not read does not count against the key-word share.
        assertEquals(2, analysis.tally.keyWords)
        assertEquals(1, analysis.tally.measuredKeyWords)
        assertEquals(1f, analysis.tally.keyShare)
    }

    @Test
    fun stressingEveryOtherWordIsTooMuch() {
        val words = List(10) { if (it % 2 == 1) strong else plain() }
        val analysis = analyse(sentence(words))
        val stressed = analysis.words.filter { it.isStressed }.map { it.index }
        assertEquals(listOf(1, 3, 5, 7, 9), stressed)
        assertEquals(0.5f, analysis.tally.stressedShare)
        assertTrue(analysis.tally.everythingStressed)
        assertEquals(stressed, analysis.extraStressed.map { it.index })
    }

    @Test
    fun aShortSentenceIsNeverEverythingStressed() {
        // "Nu azi, ci mâine": two key words out of four, both stressed, is the aim, not too much.
        val analysis = analyse(sentence(listOf(plain(), strong, plain(), strong)), keys = setOf(1, 3))
        assertEquals(2, analysis.tally.stressedKeyWords, "${analysis.words}")
        assertFalse(analysis.tally.everythingStressed)
    }

    @Test
    fun keyWordsAreLeftOutOfEachOthersComparison() {
        val s = sentence(listOf(plain(), strong, plain(), strong, plain()))
        val withKeys = analyse(s, keys = setOf(1, 3))
        assertEquals(listOf(0, 2, 4), withKeys.words[1].neighbours)
        assertEquals(listOf(2, 0, 4), withKeys.words[3].neighbours)
        val blind = analyse(s)
        assertEquals(listOf(0, 2, 3), blind.words[1].neighbours)
        assertTrue(withKeys.words[1].loudnessGainDb!! > blind.words[1].loudnessGainDb!!)
    }

    @Test
    fun theWordBeforeAPauseCountsItsLastVowelLess() {
        // A phrase-final word is long anyway, so its last vowel counts 0.75 of its length. Here that vowel is also
        // the word's loudest, so it is the one measured.
        val lastLong = Word(SyllableSpec(0.14, 200f, 192f, 0.24f), SyllableSpec(0.2, 192f, 180f, 0.27f))
        val analysis = analyse(sentence(listOf(plain(), plain(), lastLong, plain(), plain()), pauseAfter = mapOf(2 to 0.5)))
        val cues = assertNotNull(analysis.words[2].cues)
        assertTrue(cues.phraseFinal)
        assertEquals(cues.rawDurationSeconds * DSPThresholds.v1.stress.finalLengthening, cues.durationSeconds, 1e-6f)
        assertTrue(analysis.words[2].durationGain!! < 0.3f, "${analysis.words[2]}")
        assertFalse(analysis.words[2].isStressed)
        assertFalse(analysis.words[1].cues!!.phraseFinal)
        assertTrue(analysis.words[4].cues!!.phraseFinal)
        // Without the pause the same word is not phrase-final and keeps its whole length.
        val running = analyse(sentence(listOf(plain(), plain(), lastLong, plain(), plain()))).words[2].cues!!
        assertFalse(running.phraseFinal)
        assertEquals(running.rawDurationSeconds, running.durationSeconds)
    }

    @Test
    fun aWordIsMeasuredOnItsLoudestVowelNotOnItsLength() {
        // A three-syllable word said like the others is not "longer" than its two-syllable neighbours.
        val long = Word(
            SyllableSpec(0.14, 200f, 194f, 0.25f), SyllableSpec(0.13, 194f, 190f, 0.22f), SyllableSpec(0.13, 190f, 186f, 0.22f),
        )
        val analysis = analyse(sentence(listOf(plain(), plain(), long, plain(), plain())))
        val word = analysis.words[2]
        assertTrue(word.cues!!.fromNucleus)
        assertTrue(abs(word.durationGain!!) < 0.3f, "$word")
        assertFalse(word.isStressed)
    }

    @Test
    fun talliesAddUpOverASet() {
        val stressed = analyse(sentence(listOf(plain(), plain(), strong, plain(), plain())), keys = setOf(2)).tally
        val level = analyse(sentence(listOf(plain(), plain(), plain(), plain(), plain())), keys = setOf(2)).tally
        val set = listOf(stressed, stressed, stressed, stressed, level).fold(EmphasisTally.EMPTY) { sum, t -> sum + t }
        assertEquals(5, set.keyWords)
        assertEquals(4, set.stressedKeyWords)
        assertEquals(0.8f, set.keyShare)
        assertTrue(set.meetsKeyTarget)
        val fewer = listOf(stressed, stressed, stressed, level, level).fold(EmphasisTally.EMPTY) { sum, t -> sum + t }
        assertFalse(fewer.meetsKeyTarget)
        assertEquals(25, set.measuredWords)
    }

    @Test
    fun nothingToCompareWithIsNotMeasured() {
        val alone = analyse(sentence(listOf(strong)), keys = setOf(0))
        assertEquals(EmphasisState.NOT_MEASURED, alone.words[0].state)
        assertNull(alone.tally.keyShare)
        val silent = analyzeEmphasis(analyzeClip(silence(1.0, rate), rate, VoiceGroup.FEMALE, null), listOf(TimeSpan(0.2, 0.5), TimeSpan(0.6, 0.8)))
        assertTrue(silent.words.none { it.isMeasured })
    }
}
