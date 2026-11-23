package tech.fonia.speaking

import org.junit.jupiter.api.Test
import tech.fonia.speech.Transcript
import tech.fonia.speech.TranscriptWord
import tech.fonia.speaking.SyntheticVoice.Kind
import tech.fonia.speaking.SyntheticVoice.Part
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CleanCutTest {
    private val parts = listOf(
        Part(Kind.PAUSE, 0.5), Part(Kind.SPEECH, 1.2), Part(Kind.PAUSE, 0.3), Part(Kind.HESITATION, 0.6),
        Part(Kind.PAUSE, 0.3), Part(Kind.SPEECH, 1.5), Part(Kind.PAUSE, 1.8), Part(Kind.SPEECH, 1.0), Part(Kind.PAUSE, 0.4),
    )
    private val voice = SyntheticVoice.render(parts, sampleRate = 48000.0)
    private val analysis = SpeakingAnalyzer().analyze(voice.samples, voice.sampleRate)
    private val hesitation = voice.parts.first { it.kind == Kind.HESITATION }

    @Test
    fun `finds the held vowel from the sound alone and nothing in the speech`() {
        val cut = analysis.spans.filter { it.cut }
        assertEquals(1, cut.size, "spans: ${analysis.spans}")
        val s = cut.single()
        assertEquals(SpanKind.HESITATION, s.kind)
        assertTrue(s.start <= hesitation.start + 0.08 && s.end >= hesitation.end - 0.08, "span $s vs $hesitation")
        assertEquals(SpanSource.ACOUSTIC, s.source)
    }

    @Test
    fun `cleaned take is shorter by exactly what was removed, with no click and quiet edges`() {
        val clean = CleanCut.render(voice.samples, voice.sampleRate, analysis)
        val removed = clean.edits.sumOf { it.end - it.start }
        assertEquals(voice.samples.size - removed, clean.samples.size)
        val q = CutQuality.check(voice.samples, clean, analysis.frames.speechMedianDb)
        assertTrue(q.allClean, "joins: ${q.joins}")
        assertTrue(q.joins.all { it.quietEdges }, "joins: ${q.joins}")
        // The hesitation is gone and the long pause is now about 0.6 s.
        assertTrue(clean.timeMap.isRemoved((hesitation.start + hesitation.end) / 2))
        assertTrue(clean.stats.pausesShortened >= 1)
        assertEquals(1, clean.stats.fillersRemoved)
        val pause = voice.parts.filter { it.kind == Kind.PAUSE }[3]
        val kept = clean.timeMap.toNew(pause.end) - clean.timeMap.toNew(pause.start)
        assertTrue(kept in 0.45..0.8, "long pause kept $kept s")
    }

    @Test
    fun `room tone stays in a shortened pause`() {
        val clean = CleanCut.render(voice.samples, voice.sampleRate, analysis)
        val pause = voice.parts.filter { it.kind == Kind.PAUSE }[3]
        val a = (clean.timeMap.toNew(pause.start) * voice.sampleRate).toInt() + 2000
        val b = (clean.timeMap.toNew(pause.end) * voice.sampleRate).toInt() - 2000
        var zeros = 0
        for (k in a until b) if (clean.samples[k] == 0f) zeros++
        assertTrue(CleanCut.rms(clean.samples, a, b - a) > 1e-5, "no dead silence")
        assertTrue(zeros < (b - a) / 100)
    }

    @Test
    fun `time map goes both ways and is monotonic`() {
        val clean = CleanCut.render(voice.samples, voice.sampleRate, analysis)
        val map = clean.timeMap
        var prev = -1.0
        var t = 0.0
        while (t < voice.samples.size / voice.sampleRate) {
            val n = map.toNew(t)
            assertTrue(n >= prev - 1e-9, "monotonic at $t")
            if (!map.isRemoved(t)) assertTrue(abs(map.toOld(n) - t) < 1e-6, "round trip at $t")
            prev = n
            t += 0.01
        }
        assertTrue(abs(map.newDuration - clean.samples.size / voice.sampleRate) < 1e-9)
    }

    @Test
    fun `nothing cut means the same samples`() {
        val clean = CleanCut.render(voice.samples, voice.sampleRate, analysis, cutIds = emptySet())
        // Only the long pause is shortened when no span is chosen.
        assertTrue(clean.edits.all { it.reason == EditReason.LONG_PAUSE })
        assertEquals(0, clean.stats.fillersRemoved)
    }

    @Test
    fun `a cut never enters a kept word, even when the recogniser's timing overlaps it`() {
        // A transcript whose word after the hesitation starts 40 ms inside it (timing jitter), and a filler token.
        val s1 = voice.parts[1]
        val s2 = voice.parts[5]
        val words = listOf(
            TranscriptWord("Bună", s1.start, s1.end, 0.95),
            TranscriptWord("ăăă,", hesitation.start + 0.1, hesitation.end - 0.1, 0.6),
            TranscriptWord("astăzi", s2.start - 0.04, s2.end, 0.4),
            TranscriptWord("gata.", voice.parts[7].start, voice.parts[7].end, 0.9),
        )
        val a = SpeakingAnalyzer().analyze(voice.samples, voice.sampleRate, Transcript("", words, 0.0, 0.0))
        val span = a.spans.single { it.cut }
        assertEquals(SpanSource.BOTH, span.source)
        val clean = CleanCut.render(voice.samples, voice.sampleRate, a)
        val guardS = SpeakingThresholds.v1.cut.guardUncertainMs / 1000
        for (e in clean.edits) {
            val es = e.start / voice.sampleRate
            val ee = e.end / voice.sampleRate
            for ((i, w) in a.words.withIndex()) {
                if (i == 1) continue
                assertTrue(ee <= w.start - 0.02 || es >= w.end + 0.02, "edit $es-$ee enters '${w.word}' ${w.start}-${w.end}")
            }
            // "astăzi" is doubtful (p = 0.4): the wider guard applies on its side.
            if (e.reason == EditReason.HESITATION) assertTrue(ee <= a.words[2].start - guardS + 0.03)
        }
    }

    @Test
    fun `edits merge and snap to quiet points`() {
        val clean = CleanCut.render(voice.samples, voice.sampleRate, analysis)
        for (e in clean.edits) {
            assertTrue(e.half * 2 / voice.sampleRate * 1000 in 9.0..21.0, "crossfade ${e.half * 2 / voice.sampleRate * 1000} ms")
        }
        val sorted = clean.edits.zipWithNext().all { (x, y) -> x.end + x.half <= y.start - y.half }
        assertTrue(sorted)
    }
}
