package tech.fonia.features.speaking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.domain.Language
import tech.fonia.dsp.EmphasisAnalysis
import tech.fonia.dsp.EmphasisCue
import tech.fonia.dsp.EmphasisState
import tech.fonia.dsp.EmphasisTally
import tech.fonia.dsp.EmphasisThresholds
import tech.fonia.dsp.TimeSpan
import tech.fonia.dsp.WordCues
import tech.fonia.dsp.WordEmphasis
import tech.fonia.features.reading.CopyRules
import tech.fonia.features.speaking.emphasis.EmphasisItem
import tech.fonia.features.speaking.emphasis.EmphasisTake
import tech.fonia.features.speaking.emphasis.EmphasisVerdict
import tech.fonia.features.speaking.emphasis.EmphasisWords

/**
 * What the exercise says about a word. It names the measured cue ("mai lung", "mai tare", "mai sus") and never a state
 * of the reader; results only (KR), never "greșit"; the copy rules of `tools/lint_copy.py`.
 */
class EmphasisWordsTests {
    private fun word(
        state: EmphasisState,
        passed: Set<EmphasisCue>,
        duration: Float? = 0.1f,
        loudness: Float? = 1f,
        pitch: Float? = 0.5f,
        index: Int = 1,
        isKey: Boolean = true,
    ) = WordEmphasis(
        index = index,
        span = TimeSpan(0.5, 0.8),
        isKey = isKey,
        cues = if (state == EmphasisState.NOT_MEASURED) null else WordCues(0.6, -20f, 0.12f, 0.12f, 1f, false, true),
        neighbours = listOf(0, 2),
        durationGain = duration,
        loudnessGainDb = loudness,
        pitchGainSt = pitch,
        passed = passed,
        prominence = 0.5f,
        state = state,
    )

    private val every = mutableListOf<String>()

    private fun said(text: String?): String? {
        text?.let { every.add(it) }
        return text
    }

    @Test
    fun theLineNamesTheCuesThatPassed() {
        assertEquals(
            "„azi” a fost mai lung și mai tare decât cuvintele din jur.",
            said(EmphasisWords.keyLine(word(EmphasisState.STRESSED, setOf(EmphasisCue.LOUDNESS, EmphasisCue.DURATION)), "azi")),
        )
        assertEquals(
            "„azi” a fost mai lung și mai sus decât cuvintele din jur.",
            said(EmphasisWords.keyLine(word(EmphasisState.STRESSED, setOf(EmphasisCue.PITCH, EmphasisCue.DURATION)), "azi")),
        )
        assertEquals(
            "„azi” a fost mai lung, mai tare și mai sus decât cuvintele din jur.",
            said(EmphasisWords.keyLine(word(EmphasisState.STRESSED, EmphasisCue.entries.toSet()), "azi")),
        )
        assertEquals(
            "„azi” a fost mai lung decât cuvintele din jur, dar nu și mai tare.",
            said(EmphasisWords.keyLine(word(EmphasisState.CLOSE, setOf(EmphasisCue.DURATION)), "azi")),
        )
        assertEquals(
            "„azi” a fost mai tare decât cuvintele din jur, dar nu și mai lung.",
            said(EmphasisWords.keyLine(word(EmphasisState.CLOSE, setOf(EmphasisCue.LOUDNESS)), "azi")),
        )
        assertEquals(
            "Vocea a urcat pe „azi”, dar el a fost cam la fel de lung și de tare ca vecinii lui.",
            said(EmphasisWords.keyLine(word(EmphasisState.CLOSE, setOf(EmphasisCue.PITCH)), "azi")),
        )
        assertEquals("„azi” a fost cam la fel de lung și de tare ca vecinii lui.", said(EmphasisWords.keyLine(word(EmphasisState.LEVEL, emptySet()), "azi")))
        assertEquals("Nu am auzit clar „azi”.", said(EmphasisWords.keyLine(word(EmphasisState.NOT_MEASURED, emptySet()), "azi")))
        checkSaid()
    }

    @Test
    fun theTipGoesForLengthAndLevelFirst() {
        fun tip(state: EmphasisState, passed: Set<EmphasisCue>): String? {
            val item = EmphasisItem("x", Language.Ro, null, MarkedText.parse("Nu, trenul pleacă *azi*."))
            val words = List(4) { i -> if (i == 3) word(state, passed, index = 3) else word(EmphasisState.LEVEL, emptySet(), index = i, isKey = false) }
            return said(EmphasisWords.tip(EmphasisTake(item, EmphasisAnalysis(words, EmphasisThresholds.v1), "")))
        }
        assertEquals(null, tip(EmphasisState.STRESSED, EmphasisCue.entries.toSet()))
        assertEquals("Spune-l și puțin mai tare.", tip(EmphasisState.CLOSE, setOf(EmphasisCue.DURATION)))
        assertEquals("Lungește-l puțin.", tip(EmphasisState.CLOSE, setOf(EmphasisCue.LOUDNESS)))
        assertEquals("Lungește-l puțin și spune-l mai tare.", tip(EmphasisState.CLOSE, setOf(EmphasisCue.PITCH)))
        assertEquals("Lungește-l puțin și spune-l mai tare.", tip(EmphasisState.LEVEL, emptySet()))
        assertEquals("Citește toată propoziția, cu telefonul aproape.", tip(EmphasisState.NOT_MEASURED, emptySet()))
        checkSaid()
    }

    @Test
    fun verdictsAreResultsOnly() {
        val item = EmphasisItem("x", Language.Ro, null, MarkedText.parse("Nu, trenul pleacă *azi*."))
        fun take(state: EmphasisState, passed: Set<EmphasisCue>, extra: Boolean = false): EmphasisTake {
            val words = List(4) { i ->
                when {
                    i == 3 -> word(state, passed, index = 3)
                    i == 1 && extra -> word(EmphasisState.STRESSED, setOf(EmphasisCue.DURATION, EmphasisCue.LOUDNESS), index = 1, isKey = false)
                    else -> word(EmphasisState.LEVEL, emptySet(), index = i, isKey = false)
                }
            }
            return EmphasisTake(item, EmphasisAnalysis(words, EmphasisThresholds.v1), "")
        }
        assertEquals(EmphasisVerdict.Stressed, take(EmphasisState.STRESSED, setOf(EmphasisCue.DURATION, EmphasisCue.LOUDNESS)).verdict)
        assertEquals(EmphasisVerdict.Almost, take(EmphasisState.CLOSE, setOf(EmphasisCue.DURATION)).verdict)
        assertEquals(EmphasisVerdict.NotYet, take(EmphasisState.LEVEL, emptySet()).verdict)
        assertEquals(EmphasisVerdict.NotHeard, take(EmphasisState.NOT_MEASURED, emptySet()).verdict)
        assertEquals(EmphasisVerdict.NotHeard, EmphasisTake(item, null, null).verdict)
        for (verdict in listOf(
            take(EmphasisState.STRESSED, setOf(EmphasisCue.DURATION, EmphasisCue.LOUDNESS)),
            take(EmphasisState.CLOSE, setOf(EmphasisCue.DURATION)),
            take(EmphasisState.LEVEL, emptySet()),
            EmphasisTake(item, null, null),
        )) {
            said(EmphasisWords.headline(verdict))
        }
        assertEquals("A ieșit în față și „trenul”.", said(EmphasisWords.extraLine(take(EmphasisState.LEVEL, emptySet(), extra = true))))
        checkSaid()
    }

    @Test
    fun valuesAreSignedWithARomanianComma() {
        val w = word(EmphasisState.CLOSE, setOf(EmphasisCue.DURATION), duration = 0.354f, loudness = 4.24f, pitch = -1f)
        assertEquals("+35 %", EmphasisWords.cueValue(EmphasisCue.DURATION, w))
        assertEquals("+4,2 dB", EmphasisWords.cueValue(EmphasisCue.LOUDNESS, w))
        assertEquals("−1,0 semitonuri", EmphasisWords.cueValue(EmphasisCue.PITCH, w))
        val flat = word(EmphasisState.LEVEL, emptySet(), duration = -0.05f, loudness = 0f, pitch = null)
        assertEquals("−5 %", EmphasisWords.cueValue(EmphasisCue.DURATION, flat))
        assertEquals("0,0 dB", EmphasisWords.cueValue(EmphasisCue.LOUDNESS, flat))
        assertEquals(null, EmphasisWords.cueValue(EmphasisCue.PITCH, flat))
        assertEquals("Mai lung", EmphasisWords.cueTitle(EmphasisCue.DURATION))
    }

    @Test
    fun theSetSummaryReadsTheEightyPercentReference() {
        val met = EmphasisWords.summary(EmphasisTally(keyWords = 7, measuredKeyWords = 6, stressedKeyWords = 5, measuredWords = 40, stressedWords = 7))
        assertEquals(listOf("Cuvinte marcate care au ieșit în față: 5 din 6.", "Reperul de 80 % e atins."), met)
        val under = EmphasisWords.summary(EmphasisTally(keyWords = 6, measuredKeyWords = 6, stressedKeyWords = 3, measuredWords = 40, stressedWords = 4))
        assertEquals("Reperul e de cel puțin 80 % din cuvintele marcate.", under[1])
        val tooMuch = EmphasisWords.summary(EmphasisTally(keyWords = 6, measuredKeyWords = 6, stressedKeyWords = 6, measuredWords = 40, stressedWords = 20))
        assertEquals("Au ieșit în față 50 % din toate cuvintele. Peste 40 %, cele importante nu se mai deosebesc.", tooMuch[2])
        assertEquals(listOf("Nu am putut măsura cuvintele marcate."), EmphasisWords.summary(EmphasisTally.EMPTY))
        every.addAll(met + under + tooMuch)
        checkSaid()
    }

    /** Everything said here: copy rules, no inferred state, never "greșit". */
    private fun checkSaid() {
        for (text in every) {
            assertEquals(text, emptyList<String>(), CopyRules.problems(text))
            assertEquals(text, emptyList<String>(), statesIn(text))
        }
        assertTrue(every.isNotEmpty())
    }
}
