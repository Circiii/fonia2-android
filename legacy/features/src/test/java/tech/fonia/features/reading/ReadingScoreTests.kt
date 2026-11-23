package tech.fonia.features.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The numbers of a probe (A §7.2), the published band (A §2.5, 03 §1.3) and the probe's own clock. */
class ReadingScoreTests {
    private val mono = list(ProbeKind.Mono, 60, 1)
    private val bi = list(ProbeKind.Bi, 50, 2)

    private fun list(kind: ProbeKind, size: Int, syllables: Int) = ReadingProbe(
        id = "T-${kind.rawValue}",
        set = "T",
        kind = kind,
        title = kind.title,
        words = (0 until size).map { index -> ProbeWord("w$index", List(syllables) { "s" }) },
    )

    @Test
    fun wcpmIsTheWordsReadWellInTheMinute() {
        val score = ProbeScore.of(mono, lastRead = 39, errorIndices = setOf(3, 10, 22), seconds = 60.0)
        assertEquals(40, score.wordsRead)
        assertEquals(3, score.errors)
        assertEquals(37, score.correct)
        assertEquals(37.0, score.wcpm, 1e-9)
        assertEquals(37, score.wcpmShown)
        assertEquals(37.0 / 40, score.accuracy, 1e-9)
        assertEquals(93, score.accuracyPercent)
        assertEquals(37.0 / 60, score.syllablesPerSecond, 1e-9)
        assertTrue(score.isUsable)
    }

    @Test
    fun finishingEarlyIsScaledToAMinute() {
        // The whole list in 45 s, two words read differently: 58 correct in 45 s.
        val score = ProbeScore.of(mono, lastRead = 59, errorIndices = listOf(0, 59), seconds = 45.0)
        assertEquals(60, score.wordsRead)
        assertEquals(58.0 * 60 / 45, score.wcpm, 1e-9)
        assertEquals(77, score.wcpmShown)
        assertEquals(58.0 / 45, score.syllablesPerSecond, 1e-9)
    }

    @Test
    fun marksPastTheLastWordReadDontCount() {
        val score = ProbeScore.of(mono, lastRead = 30, errorIndices = setOf(5, 30, 31, 50), seconds = 60.0)
        assertEquals(31, score.wordsRead)
        assertEquals(2, score.errors)
        assertEquals(29.0, score.wcpm, 1e-9)
    }

    @Test
    fun syllablesPerSecondCountOnlyTheWordsReadWell() {
        val score = ProbeScore.of(bi, lastRead = 19, errorIndices = setOf(1, 2), seconds = 60.0)
        assertEquals(40, score.syllablesRead)
        assertEquals(36, score.syllablesCorrect)
        assertEquals(0.6, score.syllablesPerSecond, 1e-9)
        assertEquals(18.0, score.wcpm, 1e-9)
    }

    @Test
    fun aTextCountsEachWordsOwnSyllables() {
        val text = ReadingProbe(
            "T-text", "T", ProbeKind.Text, "Titlu",
            ReadingContent.textWords("Ana are o pisică.", "A-na a-re o pi-si-că."),
        )
        assertEquals(listOf(2, 2, 1, 3), text.words.map { it.syllableCount })
        val score = ProbeScore.of(text, lastRead = 3, errorIndices = setOf(3), seconds = 6.0)
        assertEquals(5, score.syllablesCorrect)
        assertEquals(30.0, score.wcpm, 1e-9)
        assertEquals(listOf("pi", "si", "că"), text.words[3].syllables)
    }

    @Test
    fun theWindowIsOneMinute() {
        val score = ProbeScore.of(mono, lastRead = 59, errorIndices = emptySet(), seconds = 75.0)
        assertEquals(60.0, score.seconds, 0.0)
        assertEquals(60.0, score.wcpm, 1e-9)
    }

    @Test
    fun aProbeStoppedAtOnceIsNotUsable() {
        assertFalse(ProbeScore.of(mono, lastRead = 1, errorIndices = emptySet(), seconds = 3.0).isUsable)
        assertFalse(ProbeScore.of(mono, lastRead = -1, errorIndices = emptySet(), seconds = 60.0).isUsable)
        assertEquals(0.0, ProbeScore.of(mono, lastRead = -1, errorIndices = emptySet(), seconds = 60.0).accuracy, 0.0)
        assertEquals(0.0, ProbeScore(0, 0, 0.0, 0, 0).wcpm, 0.0)
    }

    @Test
    fun wordsPerMinuteRoundHalfUp() {
        assertEquals(26, ProbeScore.wholeWords(25.5))
        assertEquals(25, ProbeScore.wholeWords(25.49))
        assertEquals(0, ProbeScore.wholeWords(-3.0))
    }

    @Test
    fun theBandsAreTheMiddleColumnOfThePublishedTable() {
        assertEquals(26 to 40, PeafcNorms.band(ProbeKind.Mono).let { it.low to it.high })
        assertEquals(25 to 37, PeafcNorms.band(ProbeKind.Bi).let { it.low to it.high })
        assertEquals(16 to 25, PeafcNorms.band(ProbeKind.Tri).let { it.low to it.high })
        assertEquals(23 to 36, PeafcNorms.band(ProbeKind.Text).let { it.low to it.high })
        assertEquals(41.21, PeafcNorms.band(ProbeKind.Mono).mean, 0.0)
        assertEquals(38.74, PeafcNorms.band(ProbeKind.Text).mean, 0.0)
    }

    @Test
    fun aValueOnABoundIsInsideTheBand() {
        val cases = mapOf(
            ProbeKind.Mono to listOf(25 to BandPosition.Below, 26 to BandPosition.Within, 40 to BandPosition.Within, 41 to BandPosition.Above),
            ProbeKind.Bi to listOf(24 to BandPosition.Below, 25 to BandPosition.Within, 37 to BandPosition.Within, 38 to BandPosition.Above),
            ProbeKind.Tri to listOf(15 to BandPosition.Below, 16 to BandPosition.Within, 25 to BandPosition.Within, 26 to BandPosition.Above),
            // The source writes the low column as "23" and the middle one as 23–36: 23 is read as inside.
            ProbeKind.Text to listOf(22 to BandPosition.Below, 23 to BandPosition.Within, 36 to BandPosition.Within, 37 to BandPosition.Above),
        )
        for ((kind, list) in cases) {
            for ((value, position) in list) assertEquals("$kind $value", position, PeafcNorms.position(kind, value.toDouble()))
        }
    }

    @Test
    fun theBandComparesTheNumberOnTheScreen() {
        // 25.5 shows as 26, so it is inside the band for one-syllable words, as the page says.
        assertEquals(BandPosition.Within, PeafcNorms.position(ProbeKind.Mono, 25.5))
        assertEquals(BandPosition.Below, PeafcNorms.position(ProbeKind.Mono, 25.4))
        assertEquals(BandPosition.Above, PeafcNorms.position(ProbeKind.Mono, 40.5))
    }

    @Test
    fun theBandIsSaidAsABandWithItsSource() {
        assertEquals("sub banda raportată pentru final de clasa I", PeafcNorms.phrase(BandPosition.Below))
        assertEquals("în banda raportată pentru final de clasa I", PeafcNorms.phrase(BandPosition.Within))
        assertEquals("peste banda raportată pentru final de clasa I", PeafcNorms.phrase(BandPosition.Above))
        assertEquals("26–40 de cuvinte corecte pe minut, la cuvintele de o silabă", PeafcNorms.range(ProbeKind.Mono))
        assertTrue("Bodea Hațegan" in PeafcNorms.SOURCE && "1.977" in PeafcNorms.SOURCE_SHORT)
        val said = BandPosition.entries.map { PeafcNorms.phrase(it) } + ProbeKind.entries.map { PeafcNorms.range(it) } + PeafcNorms.SOURCE_SHORT
        for (text in said) assertEquals(text, emptyList<String>(), CopyRules.problems(text))
    }

    @Test
    fun countsAreWrittenTheRomanianWay() {
        assertEquals("1 cuvânt corect pe minut", wordsPerMinute(1))
        assertEquals("12 cuvinte corecte pe minut", wordsPerMinute(12))
        assertEquals("38 de cuvinte corecte pe minut", wordsPerMinute(38))
        assertEquals("1,4", oneDecimal(1.4))
        assertEquals("1:00", clockText(60.0))
        assertEquals("0:07", clockText(7.9))
    }

    /** A clock the test moves by hand. */
    private class HandClock(var now: Long = 1_000_000_000L) : ProbeClock {
        override fun nanos(): Long = now

        fun advance(seconds: Double) {
            now += (seconds * 1e9).toLong()
        }
    }

    @Test
    fun theClockStartsAtTheFirstWordAndStopsAtAMinute() {
        val clock = HandClock()
        val run = ProbeRun(mono, clock)
        run.start(StartSource.Tap)
        assertEquals(ProbePhase.prep, run.phase) // nothing starts before the words are on the screen
        run.showWords()
        clock.advance(4.0) // the adult waits for the child
        run.start(StartSource.Tap)
        assertEquals(ProbePhase.running, run.phase)
        clock.advance(59.9)
        assertFalse(run.tick())
        assertEquals(59.9, run.shownSeconds, 1e-6)
        clock.advance(0.2)
        assertTrue(run.tick())
        assertEquals(ProbePhase.marking, run.phase)
        assertTrue(run.timeUp)
        assertEquals(60.0, run.seconds, 0.0)
        clock.advance(30.0) // marking the last word takes time, which doesn't count
        assertEquals(60.0, run.seconds, 0.0)
    }

    @Test
    fun stoppingEarlyKeepsTheSecondsRead() {
        val clock = HandClock()
        val run = ProbeRun(mono, clock)
        run.showWords()
        run.start(StartSource.Tap)
        clock.advance(42.5)
        run.stop()
        assertEquals(ProbePhase.marking, run.phase)
        assertFalse(run.timeUp)
        assertEquals(42.5, run.seconds, 1e-6)
    }

    @Test
    fun theMicrophoneStartsTheClockWhenItHeardTheVoice() {
        val clock = HandClock()
        val run = ProbeRun(mono, clock)
        run.showWords()
        val heard = clock.now
        clock.advance(0.03) // the gate is read a little after the first voiced frame
        run.start(StartSource.Voice, atNanos = heard)
        assertEquals(StartSource.Voice, run.startedBy)
        clock.advance(10.0)
        run.stop()
        assertEquals(10.03, run.seconds, 1e-6)
        // A start in the future is pulled back to now.
        val later = ProbeRun(mono, clock)
        later.showWords()
        later.start(StartSource.Voice, atNanos = clock.now + 5_000_000_000L)
        clock.advance(1.0)
        later.stop()
        assertEquals(1.0, later.seconds, 1e-6)
    }

    @Test
    fun tapsMarkDuringTheMinuteAndPointAtTheLastWordAfterIt() {
        val clock = HandClock()
        val run = ProbeRun(mono, clock)
        run.showWords()
        run.tap(3)
        assertTrue(run.marked.isEmpty()) // before the clock runs a tap does nothing
        run.start(StartSource.Tap)
        run.tap(3)
        run.tap(7)
        run.tap(7) // a self-correction: tapped again, read well
        run.tap(12)
        assertEquals(setOf(3, 12), run.marked)
        clock.advance(61.0)
        run.tick()
        assertNull(run.score())
        run.tap(10) // the default after the minute: the last word read
        assertEquals(10, run.lastRead)
        assertEquals(1, run.countedMarks)
        run.markMode = MarkMode.words
        run.tap(5)
        assertEquals(setOf(3, 5, 12), run.marked)
        val score = run.score()!!
        assertEquals(11, score.wordsRead)
        assertEquals(2, score.errors)
        assertEquals(9.0, score.wcpm, 1e-9)
        run.readEverything()
        assertEquals(59, run.lastRead)
        assertEquals(3, run.score()!!.errors)
    }
}
