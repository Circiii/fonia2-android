package tech.fonia.features.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.features.home.PathModel

/** Days and stars, opened up (iOS `TallyTests`). */
class TallyTests {
    @Test
    fun theLongestRunCountsDaysInARowAcrossAMonthsEnd() {
        assertEquals(0, PathModel.longestRun(emptySet()))
        assertEquals(1, PathModel.longestRun(setOf("2026-11-17")))
        assertEquals(2, PathModel.longestRun(setOf("2026-11-17", "2026-11-19", "2026-11-23", "2026-11-24", "2026-11-26")))
        assertEquals(3, PathModel.longestRun(setOf("2026-10-30", "2026-10-31", "2026-11-01", "2026-11-05")))
    }

    @Test
    fun aDayOffLaterNeverLowersTheRecord() {
        val before = PathModel.longestRun(setOf("2026-11-01", "2026-11-02", "2026-11-03"))
        val after = PathModel.longestRun(setOf("2026-11-01", "2026-11-02", "2026-11-03", "2026-11-10"))
        assertEquals(before, after)
    }

    @Test
    fun theNextMarkIsTheOneTheEndOfALessonCelebrates() {
        assertEquals(1, Tally.nextMilestone(0))
        assertEquals(10, Tally.nextMilestone(9))
        assertEquals(150, Tally.nextMilestone(100))
        assertEquals(200, Tally.nextMilestone(151))
        for (total in listOf(1, 5, 10, 25, 50, 75, 100, 150, 200)) {
            assertEquals("$total", total, Tally.milestones(total).last())
        }
    }

    @Test
    fun theMarksReachedSoFar() {
        assertTrue(Tally.milestones(0).isEmpty())
        assertEquals(listOf(1, 5), Tally.milestones(9))
        assertEquals(listOf(1, 5, 10, 25, 50, 75, 100, 150), Tally.milestones(160))
    }
}
