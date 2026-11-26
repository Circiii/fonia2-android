package tech.fonia.domain

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

// Port of FoniaDomainTests/ProbeTests.swift. The bundled-lists test reads an asset of :legacy:features, so it lives
// there (NewWordsProbeTests).

@DisplayName("Cuvinte noi")
class ProbeTests {
    private fun record(
        level: ProbeLevel = ProbeLevel.Words,
        good: Int,
        keepWorking: Int = 0,
        items: Int,
        finished: Boolean = true,
    ): NewWordsRecord {
        val marks: List<ProbeMark?> = List(good) { ProbeMark.Good } + List(keepWorking) { ProbeMark.KeepWorking } +
            List(maxOf(0, items - good - keepWorking)) { null }
        return NewWordsRecord(
            date = Instant.now(),
            sound = "s",
            level = level,
            items = marks.mapIndexed { index, mark -> ProbeItem(text = "w$index", phone = "good", adult = mark) },
            finished = finished,
        )
    }

    @Test
    @DisplayName("every fourth lesson from words on, never at 4–5")
    fun due() {
        assertFalse(ProbeRules.isDue(Rung.WordsInitial, lessonsSince = 2, age = AgeBand.SixToSeven))
        assertTrue(ProbeRules.isDue(Rung.WordsInitial, lessonsSince = 3, age = AgeBand.SixToSeven))
        assertTrue(ProbeRules.isDue(Rung.Sentences, lessonsSince = 7, age = AgeBand.TeenAdult))
        assertFalse(ProbeRules.isDue(Rung.Syllables, lessonsSince = 9, age = AgeBand.EightToTen))
        assertFalse(ProbeRules.isDue(Rung.WordsInitial, lessonsSince = 9, age = AgeBand.FourToFive))
    }

    @Test
    @DisplayName("readers from eight on; sentences only once the adult said yes")
    fun readsAndLevel() {
        assertTrue(ProbeRules.reads(AgeBand.EightToTen))
        assertTrue(ProbeRules.reads(AgeBand.TeenAdult))
        assertFalse(ProbeRules.reads(AgeBand.SixToSeven))
        assertEquals(ProbeLevel.Words, ProbeRules.level("s", sentencesFor = emptySet()))
        assertEquals(ProbeLevel.Sentences, ProbeRules.level("s", sentencesFor = setOf("s")))
        assertEquals(ProbeLevel.Words, ProbeRules.level("r", sentencesFor = setOf("s")))
    }

    @Test
    @DisplayName("the tally counts the adult's marks and compares only what both judged")
    fun tally() {
        val probe = NewWordsRecord(
            date = Instant.now(),
            sound = "s",
            level = ProbeLevel.Words,
            items = listOf(
                ProbeItem(text = "a", phone = "good", adult = ProbeMark.Good),
                ProbeItem(text = "b", phone = "notYet", adult = ProbeMark.Good),
                ProbeItem(text = "c", phone = "unclear", adult = ProbeMark.KeepWorking),
                ProbeItem(text = "d", phone = "almost", adult = ProbeMark.Skip),
                ProbeItem(text = "e", phone = "notYet", adult = null),
                ProbeItem(text = "f"),
            ),
            finished = false,
        )
        val tally = probe.tally
        assertEquals(6, tally.items)
        assertEquals(2, tally.good)
        assertEquals(1, tally.keepWorking)
        assertEquals(1, tally.skipped)
        assertEquals(2, tally.unmarked)
        assertEquals(4, tally.phoneJudged)
        assertEquals(1, tally.phoneGood)
        assertEquals(2, tally.compared)
        assertEquals(1, tally.agreed)
        val summary = ProbeSummary(probe)
        assertEquals(2.0 / 3.0, summary.goodShare)
        assertNull(ProbeSummary(record(good = 0, items = 5)).goodShare)
    }

    @Test
    @DisplayName("marks land on their items; extra marks are dropped")
    fun marks() {
        val marked = record(good = 0, items = 3).withMarks(listOf(ProbeMark.Good, null, ProbeMark.Skip, ProbeMark.Good))
        assertEquals(listOf(ProbeMark.Good, null, ProbeMark.Skip), marked.items.map { it.adult })
    }

    @Test
    @DisplayName("two finished word probes at 80% suggest sentences")
    fun suggestsSentences() {
        assertFalse(ProbeRules.suggestsSentences(listOf(record(good = 25, items = 25))))
        assertTrue(ProbeRules.suggestsSentences(listOf(record(good = 20, items = 25), record(good = 22, items = 25))))
        assertFalse(ProbeRules.suggestsSentences(listOf(record(good = 19, items = 25), record(good = 25, items = 25))))
        // An unfinished probe is not one of the two.
        assertFalse(
            ProbeRules.suggestsSentences(
                listOf(record(good = 25, items = 25), record(good = 25, items = 25, finished = false)),
            ),
        )
    }

    @Test
    @DisplayName("a sentence probe above 80% says to tell the therapist")
    fun tellsTherapist() {
        assertTrue(ProbeRules.tellsTherapist(listOf(record(ProbeLevel.Sentences, good = 9, items = 10))))
        assertFalse(ProbeRules.tellsTherapist(listOf(record(ProbeLevel.Sentences, good = 8, items = 10))))
        assertFalse(ProbeRules.tellsTherapist(listOf(record(ProbeLevel.Words, good = 25, items = 25))))
    }

    @Test
    @DisplayName("lists parse their review status; an unknown sound has no items")
    fun parse() {
        val draft = ProbeLists.parse(
            """{ "review": { "status": "draft" }, "sounds": { "s": { "words": ["sită"], "sentences": ["Sora."] } } }""",
        )
        assertFalse(draft.isApproved)
        assertEquals(listOf("sită"), draft.items("s", ProbeLevel.Words))
        assertEquals(listOf("Sora."), draft.items("s", ProbeLevel.Sentences))
        assertTrue(draft.items("x", ProbeLevel.Words).isEmpty())
        val approved = ProbeLists.parse("""{ "review": { "status": "approved" }, "sounds": {} }""")
        assertTrue(approved.isApproved)
    }
}
