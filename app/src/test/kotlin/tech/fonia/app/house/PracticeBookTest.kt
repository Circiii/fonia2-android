package tech.fonia.app.house

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.app.data.Ladder
import tech.fonia.app.data.MemoryPracticeStore
import tech.fonia.app.data.PendingMission
import tech.fonia.app.data.PracticeBook
import tech.fonia.app.data.PracticeCodec
import tech.fonia.app.data.PracticeState
import tech.fonia.app.data.SoundPractice
import tech.fonia.app.lesson.PlanMaker
import tech.fonia.lessons.ItemResult
import tech.fonia.lessons.LessonContent
import tech.fonia.model.Rung
import tech.fonia.model.SoundId
import java.io.File
import java.time.LocalDate

class PracticeBookTest {
    private val content by lazy { LessonContent.fromDirectory(File(System.getProperty("fonia.root") ?: "..", "spec/content/v2")) }

    @Test fun historyAndLadderSurviveTheFile() = runTest {
        val store = MemoryPracticeStore()
        val book = PracticeBook(store)
        val plan = PlanMaker.guided(content, SoundId.Sh, SoundPractice(), daysSinceLast = 0, oneMore = false)
        val results = plan.productions.filter { it.countsForProgress }.map { ItemResult(it.id, true) }.distinctBy { it.itemId }
        book.update { st ->
            st.with(SoundId.Sh) { Ladder.afterGuided(it, plan, results) }.copy(
                lastSound = SoundId.Sh, days = setOf("2026-11-20", "2026-11-21"),
                mission = PendingMission(SoundId.Sh, "sh.m.1", "Spune: șase!", "2026-11-21"),
            )
        }
        val again = PracticeBook(store).state() // a fresh book reads the file
        val p = again.of(SoundId.Sh)
        assertEquals(1, p.sessions)
        assertEquals(results.size, p.isolatedWins)
        assertEquals(results.size, p.history.items.size)
        assertTrue(p.history.items.values.all { it.box == 1 && it.lastSuccess })
        assertEquals(SoundId.Sh, again.lastSound)
        assertEquals("sh.m.1", again.mission?.itemId)
        assertEquals(PracticeCodec.encode(again), store.read())
    }

    @Test fun isolatedNeverMovesByItselfItWaitsForTheAdult() {
        val p = SoundPractice(isolatedWins = Ladder.ISOLATED_WINS)
        assertTrue(Ladder.needsAdult(p))
        val confirmed = Ladder.confirmIsolated(p)
        assertEquals(Rung.Syllables, confirmed.rung)
        assertFalse(Ladder.needsAdult(confirmed))
    }

    @Test fun aGoodGuidedSessionMovesOneRungUpAWeakOneNever() {
        val start = Ladder.confirmIsolated(SoundPractice())
        val plan = PlanMaker.guided(content, SoundId.S, start, 0, false)
        val ids = plan.steps.filter { it.block in 2..3 }.flatMap { s -> s.items.filter { it.countsForProgress }.map { it.id } }
        val good = Ladder.afterGuided(start, plan, ids.map { ItemResult(it, true) })
        assertEquals(Rung.Back, good.rung)
        val weak = Ladder.afterGuided(start, plan, ids.mapIndexed { k, id -> ItemResult(id, k % 2 == 0) })
        assertEquals(Rung.Syllables, weak.rung)
    }

    @Test fun exercisesCountTheirDots() {
        val p = Ladder.afterExercise(SoundPractice(), Exercise.Pairs, null, emptyList())
        assertEquals(1, p.plays[Exercise.Pairs])
    }

    @Test fun theRowShowsOnlyWhileWholeTheTotalOnlyGrows() {
        val today = LocalDate.of(2026, 11, 21)
        val st = PracticeState(days = setOf("2026-11-19", "2026-11-20", "2026-11-21", "2026-11-10"))
        assertEquals(3, st.streak(today))
        assertEquals(4, st.totalDays)
        assertEquals(2, st.copy(days = st.days - "2026-11-21").streak(today)) // today not yet practised: the row still stands
        assertEquals(0, st.streak(LocalDate.of(2026, 11, 24)))
    }

    @Test fun aBrokenFileStartsClean() = runTest {
        val book = PracticeBook(MemoryPracticeStore("{not json"))
        assertEquals(PracticeState(), book.state())
    }
}
