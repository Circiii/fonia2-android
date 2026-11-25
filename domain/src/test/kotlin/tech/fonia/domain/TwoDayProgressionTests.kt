package tech.fonia.domain

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

// Port of the suite "Progression over two days" in FoniaDomainTests/ProgressionTests.swift: 7 test functions.

@DisplayName("Progression over two days")
class TwoDayProgressionTests {
    private val zone: ZoneId = ZoneId.of("Europe/Bucharest")

    private fun day(day: Int, hour: Int = 10): Instant = LocalDateTime.of(2026, 11, day, hour, 0).atZone(zone).toInstant()

    private val state = ProgressionState(rung = Rung.WordsInitial, adultConfirmed = true)

    private fun spread(history: List<BlockResult>, now: Instant): DaySpread =
        DaySpread.Required(earlierGoodDay = Progression.hasGoodBlockOnEarlierDay(history, Rung.WordsInitial, now, zone))

    @Test
    fun oneGoodBlockDoesNotMoveUp() {
        val (after, step) = Progression.afterBlock(correct = 6, state = state, daySpread = spread(emptyList(), day(20)))
        assertEquals(ProgressionStep.Stay, step)
        assertEquals(Rung.WordsInitial, after.rung)
    }

    @Test
    fun goodBlocksOnTwoDaysMoveUp() {
        val history = listOf(BlockResult(Rung.WordsInitial, good = 5, total = 6, endedAt = day(18)))
        val (after, step) = Progression.afterBlock(correct = 5, state = state, daySpread = spread(history, day(20)))
        assertEquals(ProgressionStep.Advance(to = Rung.WordsMedial), step)
        assertEquals(Rung.WordsMedial, after.rung)
    }

    @Test
    fun twoGoodBlocksOnTheSameDayDoNotMoveUp() {
        val history = listOf(BlockResult(Rung.WordsInitial, good = 6, total = 6, endedAt = day(20, hour = 8)))
        assertEquals(ProgressionStep.Stay, Progression.afterBlock(correct = 6, state = state, daySpread = spread(history, day(20, hour = 19))).step)
    }

    @Test
    fun justBeforeMidnightAndJustAfterAreTwoDays() {
        val history = listOf(BlockResult(Rung.WordsInitial, good = 6, total = 6, endedAt = day(19, hour = 23)))
        assertTrue(Progression.hasGoodBlockOnEarlierDay(history, Rung.WordsInitial, day(20, hour = 0), zone))
    }

    @Test
    fun onlyAFullGoodBlockAtTheSameRungCounts() {
        val now = day(20)
        val elsewhere = listOf(BlockResult(Rung.Syllables, good = 6, total = 6, endedAt = day(18)))
        val weak = listOf(BlockResult(Rung.WordsInitial, good = 4, total = 6, endedAt = day(18)))
        val short = listOf(BlockResult(Rung.WordsInitial, good = 5, total = 5, endedAt = day(18)))
        for (history in listOf(elsewhere, weak, short)) {
            assertFalse(Progression.hasGoodBlockOnEarlierDay(history, Rung.WordsInitial, now, zone))
        }
    }

    @Test
    fun withoutTheRuleOneGoodBlockStillMovesUp() {
        assertEquals(
            ProgressionStep.Advance(to = Rung.WordsMedial),
            Progression.afterBlock(correct = 5, state = state, daySpread = DaySpread.NotRequired).step,
        )
    }

    @Test
    fun theRuleNeverMovesAnyoneDownOrSkipsTheAdultSteps() {
        val noEarlierDay = DaySpread.Required(earlierGoodDay = false)
        assertEquals(ProgressionStep.Drop(to = Rung.Syllables), Progression.afterBlock(correct = 1, state = state, daySpread = noEarlierDay).step)
        val sentences = ProgressionState(rung = Rung.Sentences, adultConfirmed = true)
        assertEquals(ProgressionStep.NeedsAdult(next = Rung.Retell), Progression.afterBlock(correct = 6, state = sentences, daySpread = noEarlierDay).step)
        val locked = ProgressionState(establishCorrect = 6)
        assertEquals(ProgressionStep.NeedsAdult(next = Rung.Syllables), Progression.afterBlock(correct = 6, state = locked, daySpread = noEarlierDay).step)
    }
}
