package tech.fonia.speaking

import org.junit.jupiter.api.Test
import tech.fonia.speech.TranscriptWord
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LexicalDetectorTest {
    /** Words at 0.3 s each with [gaps] seconds of silence after the given indices. */
    private fun words(text: String, gaps: Map<Int, Double> = emptyMap(), prob: Double = 0.95): List<TranscriptWord> {
        var t = 0.0
        return text.split(' ').mapIndexed { i, w ->
            val word = TranscriptWord(w, t, t + 0.3, prob)
            t += 0.3 + (gaps[i] ?: 0.02)
            word
        }
    }

    private fun hits(text: String, gaps: Map<Int, Double> = emptyMap()) = LexicalDetector.detect(words(text, gaps))
    private val cut = SpeakingThresholds.v1.cut.cutConfidence

    @Test
    fun `hesitation tokens, including the recogniser's schwa`() {
        val h = hits("Bună ziua. Ăăă, astăzi Ə, îîî mmm vreau")
        assertEquals(listOf("ăăă", "ə", "îîî", "mmm"), h.filter { it.kind == SpanKind.HESITATION }.map { it.label })
    }

    @Test
    fun `single vowels that are words stay words`() {
        assertTrue(hits("a venit o fată e aici").none { it.kind == SpanKind.HESITATION })
    }

    @Test
    fun `deci and practic set apart are fillers, but not inside a clause`() {
        val filler = hits("proiectul nostru. Deci, practic, am construit").filter { it.kind == SpanKind.MARKER }
        assertEquals(listOf("deci", "practic"), filler.map { it.label })
        assertTrue(filler.all { it.confidence >= cut })
        // Conclusion "deci" mid-sentence, and "practic" as an adjective: never cut.
        val meaningful = hits("am muncit mult deci am reușit un exercițiu practic bun")
        assertTrue(meaningful.none { it.confidence >= cut }, "$meaningful")
    }

    @Test
    fun `a one-sided marker is offered but not cut`() {
        val h = hits("am terminat. Deci am plecat acasă").single { it.kind == SpanKind.MARKER }
        assertTrue(h.confidence < cut && h.confidence >= 0.35, "$h")
    }

    @Test
    fun `stii followed by ca is a verb`() {
        assertTrue(hits("tu știi că vine mâine").none { it.kind == SpanKind.MARKER })
        assertTrue(hits("e greu, știi, să vorbești").any { it.label == "știi" && it.confidence >= cut })
    }

    @Test
    fun `cum sa zic is one filler`() {
        val h = hits("Și, cum să zic, cam asta e tot.")
        assertEquals("cum să zic", h.single { it.kind == SpanKind.MARKER }.label)
    }

    @Test
    fun `repetitions cut the first copy, emphatic doubles stay`() {
        val r = hits("am am construit o aplicație în care în care copiii").filter { it.kind == SpanKind.REPETITION }
        assertEquals(listOf("am", "în care"), r.map { it.label })
        assertEquals(0, r[0].first)
        assertTrue(hits("e foarte foarte bine").none { it.kind == SpanKind.REPETITION })
    }

    @Test
    fun `false starts`() {
        val f = hits("despre pro- proiectul nostru").filter { it.kind == SpanKind.FALSE_START }
        assertEquals(1, f.size)
        assertEquals(1, f[0].first)
        // A short function word before a longer word is not a false start ("a avea", "o oră").
        assertTrue(hits("a avea o oră").none { it.kind == SpanKind.FALSE_START })
    }

    @Test
    fun `cedilla spellings normalise to comma letters`() {
        assertEquals("știi", LexicalDetector.normalize("Ştii,"))
        assertEquals("ăăă", LexicalDetector.normalize("„Ăăă…”"))
    }
}
