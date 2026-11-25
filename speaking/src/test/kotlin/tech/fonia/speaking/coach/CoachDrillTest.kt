package tech.fonia.speaking.coach

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CoachDrillTest {
    private fun scores(vararg pairs: Pair<Area, Int?>) = CoachScores(Area.entries.associateWith { a -> pairs.firstOrNull { it.first == a }?.second })

    @Test
    fun weakestAreaIsTheLowestAverageWithTwoValues() {
        val history = listOf(
            scores(Area.Pace to 70, Area.Voice to 40, Area.Gaze to 90),
            scores(Area.Pace to 60, Area.Voice to 50, Area.Gaze to 20),
            scores(Area.Pace to 65, Area.Voice to 55),
        )
        // Gaze averages 55 (two values), Voice 48.3: Voice.
        assertEquals(Area.Voice, weakestArea(history))
    }

    @Test
    fun noDrillWithoutHistoryOrWhenAllIsGood() {
        assertNull(weakestArea(listOf(scores(Area.Pace to 10))))
        assertNull(weakestArea(List(3) { scores(Area.Pace to 90, Area.Voice to 85) }))
    }

    @Test
    fun aFocusedArbiterShowsOnlyItsArea() {
        val spec = CoachSpec.v1
        val active = BooleanArray(Cue.entries.size)
        active[Cue.Look.ordinal] = true
        active[Cue.Louder.ordinal] = true
        val arbiter = CueArbiter(spec, focus = Area.Voice)
        var shown: Cue? = null
        var t = 0.0
        while (t < 10.0) {
            shown = arbiter.update(t, active, warm = true, speaking = true) ?: shown
            t += 0.1
        }
        assertEquals(Cue.Louder, shown)
    }
}
