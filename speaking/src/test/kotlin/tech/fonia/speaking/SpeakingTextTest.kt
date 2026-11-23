package tech.fonia.speaking

import org.junit.jupiter.api.Test
import tech.fonia.speaking.SyntheticVoice.Kind
import tech.fonia.speaking.SyntheticVoice.Part
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class SpeakingTextTest {
    @Test
    fun `plural rule of plan 01 section 11`() {
        val f = SpeakingText::fillers
        assertEquals("0 umpluturi", f(0))
        assertEquals("1 umplutură", f(1))
        assertEquals("2 umpluturi", f(2))
        assertEquals("19 umpluturi", f(19))
        assertEquals("20 de umpluturi", f(20))
        assertEquals("21 de umpluturi", f(21))
        assertEquals("100 de umpluturi", f(100))
        assertEquals("101 umpluturi", f(101))
        assertEquals("6,2 secunde", SpeakingText.seconds(6.2))
        assertEquals("1 secundă", SpeakingText.seconds(1.0))
        assertEquals("58 de secunde", SpeakingText.seconds(58.0))
    }

    @Test
    fun `cleaned line`() {
        val s = CleanStats(20.0, 13.8, 6.2, 4, 0, 0, 1, 12.0, 0.0)
        assertEquals("Ai scos 4 umpluturi. 6,2 secunde mai scurt.", SpeakingText.cleaned(s))
        assertEquals("Umpluturi: 3 → 0", SpeakingText.comparison(3, 0))
        assertTrue(SpeakingText.comparison(3, 3).contains("Încearcă o pauză"))
    }

    /** Every line the engine can produce, on a fixture and a synthetic take: no forbidden word, no cedilla letter. */
    @Test
    fun `no forbidden words, no grades, comma-below letters only`() {
        val sample = SpeakingFixtures.sample()
        val voice = SyntheticVoice.render(listOf(Part(Kind.PAUSE, 0.4), Part(Kind.SPEECH, 2.0), Part(Kind.HESITATION, 0.5), Part(Kind.PAUSE, 1.5), Part(Kind.SPEECH, 1.5)))
        val analysis = SpeakingAnalyzer().analyze(voice.samples, voice.sampleRate)
        val clean = CleanCut.render(voice.samples, voice.sampleRate, analysis)
        val lines = ArrayList<String>()
        for (s in listOf(sample.transcript.summary, analysis.transcript.summary)) {
            lines += SpeakingText.lines(s)
            lines += SpeakingText.worked(s)
            lines += SpeakingText.toTry(s)
            lines += SpeakingText.spoken(s)
        }
        lines += SpeakingText.headline(analysis, analysis.defaultCuts)
        lines += SpeakingText.cleaned(clean.stats)
        lines += SpeakingText.cleaned(sample.stats)
        assertClean(lines.joinToString("\n"))
    }

    companion object {
        val forbidden = listOf(
            "diagnostic", "depistare", "screening", "evaluare", "terapie", "tratament", "tratează", "corectează", "clinic",
            "risc", "severitate", "tulburare", "scor", "notă", "procent", "test", "greșit", "greșeală", "rău", "%",
            "nervos", "emoțion", "stres", "anxi", "bravo", "excelent", "slab",
        )

        fun assertClean(text: String) {
            val low = text.lowercase()
            for (w in forbidden) assertFalse(low.contains(w), "forbidden '$w' in: $text")
            assertFalse(text.contains('ş') || text.contains('ţ') || text.contains('Ş') || text.contains('Ţ'), "cedilla letter in: $text")
        }
    }

    @Test
    fun `the thresholds file is the code defaults`() {
        val root = System.getProperty("fonia.root")?.let { File(it) } ?: return
        val file = SpeakingThresholds.parse(File(root, "spec/thresholds/speaking.json").readText())
        val code = SpeakingThresholds()
        assertEquals(code.analysis, file.analysis)
        assertEquals(code.hesitation, file.hesitation)
        assertEquals(code.pause, file.pause)
        assertEquals(code.cut, file.cut)
        assertEquals(code.prosody, file.prosody)
        assertEquals(code.quality, file.quality)
        assertEquals(code.version, file.version)
        assertTrue(file.lexicon.markers.size >= 15)
        assertEquals(file, SpeakingThresholds.v1, "the bundled resource is the spec file")
    }
}
