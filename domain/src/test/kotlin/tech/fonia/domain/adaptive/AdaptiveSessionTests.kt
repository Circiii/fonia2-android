package tech.fonia.domain.adaptive

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tech.fonia.domain.AgeBand
import tech.fonia.domain.AttemptsSummary
import tech.fonia.domain.FeedbackLevel
import tech.fonia.domain.Fixture
import tech.fonia.domain.ProgressionState
import tech.fonia.domain.Rung
import tech.fonia.domain.SessionBlock
import tech.fonia.domain.SessionFocus
import tech.fonia.domain.SessionRequest
import tech.fonia.domain.SoundID

/** One session through the engine: the plan, the blocks it moves, what it logs, and what it keeps. */
@DisplayName("Adaptive: session")
class AdaptiveSessionTests {
    private val planner = AdaptivePlanner(Fixture.content)
    private val request = SessionRequest(age = AgeBand.SixToSeven, focus = SessionFocus.Sound(SoundID.S, Rung.WordsMedial), history = AttemptsSummary(sessionsCompleted = 2), seed = 3uL)

    private fun engine(saved: SoundAdaptive = SoundAdaptive(), review: Boolean = false) =
        AdaptiveSession(planner, SoundID.S, Rung.WordsMedial, AgeBand.SixToSeven, emptySet(), emptySet(), AdaptiveSession.seed("session-1"), saved, review)

    @Test
    fun theFirstSessionOfASoundIsAcquisitionAtTheRungsOwnMaterial() {
        val run = engine()
        val (planned, decisions) = run.plan(Fixture.builder.build(request))
        assertEquals(PracticeMode.Acquisition, run.mode.mode)
        assertEquals(Step(Rung.WordsMedial), run.challenge.step)
        val mode = decisions.first()
        assertEquals(AdaptiveDecision.Kind.Mode to "acquisition", mode.kind to mode.choice)
        assertEquals("new", mode.reason)
        val first = decisions[1]
        assertEquals(AdaptiveDecision.Kind.Block, first.kind)
        assertEquals(0 to Rung.WordsMedial, first.block to first.rung)
        assertEquals(true, first.kp)
        val blocks = planned.session.trials.drop(planned.firstBlockAt).filter { it.block.kind == SessionBlock.Kind.TargetA }
        assertTrue(blocks.all { it.feedback == FeedbackLevel.Teach })
    }

    @Test
    fun aBadBlockMakesTheRestOfTheSessionEasier() {
        val run = engine()
        val (planned, _) = run.plan(Fixture.builder.build(request))
        val (decision, remaining) = run.blockEnded(0, BlockTally(1, 6), emptyList())
        assertEquals("easier" to "below50", decision.choice to decision.reason)
        assertEquals(Rung.WordsInitial, decision.rung)
        assertEquals(planned.blocks - 1, remaining.size)
        assertEquals(1, remaining.first().trials.first().block.index)
        assertTrue(remaining.flatMap { it.itemRungs }.all { it == Rung.WordsInitial })
        assertTrue(remaining.flatMap { it.trials }.all { it.feedback == FeedbackLevel.Teach })
        assertTrue(run.state.challenge!!.kp)
    }

    @Test
    fun generalizationFadesItsResultsAsTheBlocksGoWell() {
        val saved = SoundAdaptive(setting = ModeSetting.Generalization)
        val run = engine(saved)
        run.plan(Fixture.builder.build(request))
        val (first, _) = run.blockEnded(0, BlockTally(5, 6), emptyList())
        assertEquals(3, first.kr)
        val (second, _) = run.blockEnded(1, BlockTally(6, 6), emptyList())
        // 11 of 12: harder, and the next block answers 2 takes of 6 (the KR share fades from 0.5 to about 0.33).
        assertEquals("harder", second.choice)
        assertEquals(Step(Rung.WordsMedial, fast = true), run.challenge.step)
        assertEquals(2, second.kr)
        assertEquals(false, second.kp)
    }

    @Test
    fun theDecisionsAreCodesNotText() {
        val run = engine()
        val (_, start) = run.plan(Fixture.builder.build(request))
        run.take(Rung.WordsMedial, ItemPosition.Medial, Evidence(true, EvidenceSource.Detector))
        val (block, _) = run.blockEnded(0, BlockTally(4, 6), listOf("vase"))
        val (_, end) = run.end("2026-11-06", ProgressionState(rung = Rung.WordsMedial, adultConfirmed = true), finished = true)
        val code = Regex("[a-zA-Z0-9]+")
        for (decision in start + block + end) {
            assertTrue(code.matches(decision.choice), decision.choice)
            assertTrue(code.matches(decision.reason), decision.reason)
        }
        assertEquals(listOf(AdaptiveDecision.Kind.Mastery, AdaptiveDecision.Kind.Rung), end.map { it.kind })
    }

    @Test
    fun theSessionsSuccessFeedsTheModeRule() {
        var saved = SoundAdaptive()
        for (day in listOf("2026-11-04", "2026-11-05")) {
            val run = engine(saved)
            run.plan(Fixture.builder.build(request))
            run.blockEnded(0, BlockTally(6, 6), emptyList())
            run.blockEnded(1, BlockTally(5, 6), emptyList())
            run.end(day, ProgressionState(rung = Rung.WordsMedial, adultConfirmed = true), finished = true)
            saved = run.state
        }
        assertEquals(listOf(SessionTally("2026-11-04", 11, 12), SessionTally("2026-11-05", 11, 12)), saved.sessions)
        val next = engine(saved)
        assertEquals(PracticeModes.Choice(PracticeMode.Generalization, PracticeModes.Reason.AtLeast80In2), next.mode)
    }

    @Test
    fun theControllerIsKeptForTheNextSessionOnTheSameRung() {
        val run = engine()
        run.plan(Fixture.builder.build(request))
        run.blockEnded(0, BlockTally(2, 6), emptyList())
        val next = engine(run.state)
        assertEquals(Step(Rung.WordsInitial), next.challenge.step)
        assertTrue(next.challenge.kp)
    }

    @Test
    fun aReviewSessionKeepsNoController() {
        val saved = SoundAdaptive(challenge = ChallengeState(Rung.WordsMedial, step = Step(Rung.Syllables)))
        val run = AdaptiveSession(planner, SoundID.S, Rung.WordsInitial, AgeBand.SixToSeven, emptySet(), emptySet(), 1uL, saved, isReview = true)
        assertEquals(Step(Rung.WordsInitial), run.challenge.step)
        run.plan(Fixture.builder.build(request.copy(focus = SessionFocus.Sound(SoundID.S, Rung.WordsInitial))))
        run.blockEnded(0, BlockTally(0, 6), emptyList())
        assertEquals(saved.challenge, run.state.challenge)
    }

    @Test
    fun itemsUsedEarlierInTheSessionComeLast() {
        val run = engine()
        val (planned, _) = run.plan(Fixture.builder.build(request))
        val firstItems = planned.session.trials.drop(planned.firstBlockAt).take(6).mapNotNull { it.exercise.itemText }.toSet()
        val (_, remaining) = run.blockEnded(0, BlockTally(4, 6), firstItems)
        val nextItems = remaining.first().trials.mapNotNull { it.exercise.itemText }.toSet()
        assertTrue(nextItems.none { it in firstItems }, "$firstItems then $nextItems")
    }

    @Test
    fun offTheSessionIsTheIOSOneAndOnOnlySoundSessionsChange() {
        val on = AdaptiveProfile(enabled = true)
        assertEquals(Fixture.builder.build(request), AdaptiveSession.build(Fixture.builder, planner, request, profile = null))
        assertEquals(Fixture.builder.build(request), AdaptiveSession.build(Fixture.builder, planner, request, AdaptiveProfile(enabled = false, sounds = mapOf(SoundID.S to SoundAdaptive()))))
        assertNotEquals(Fixture.builder.build(request), AdaptiveSession.build(Fixture.builder, planner, request, on))
        val meet = request.copy(focus = SessionFocus.Meet(SoundID.S))
        assertEquals(Fixture.builder.build(meet), AdaptiveSession.build(Fixture.builder, planner, meet, on))
    }

    @Test
    fun theSavedStateRoundTrips() {
        val profile = AdaptiveProfile(
            enabled = true,
            sounds = mapOf(
                SoundID.S to SoundAdaptive(
                    setting = ModeSetting.Generalization,
                    mode = PracticeMode.Generalization,
                    sessions = listOf(SessionTally("2026-11-05", 20, 24)),
                    challenge = ChallengeState(Rung.WordsMedial, Step(Rung.WordsInitial, fast = true), listOf(BlockTally(5, 6)), listOf(BlockTally(2, 6), BlockTally(5, 6)), kp = true),
                    mastery = listOf(KeyMastery(ItemPosition.Initial, Rung.WordsInitial, 0.93, 40, listOf("2026-11-04", "2026-11-05"))),
                ),
                SoundID.R to SoundAdaptive(),
            ),
        )
        assertEquals(profile, AdaptiveCodec.decode(AdaptiveCodec.encode(profile)))
        assertEquals(AdaptiveProfile(), AdaptiveCodec.decode("{}"))
    }

    @Test
    fun aDamagedValueFallsBackAndTheRestIsKept() {
        val text = """{"format":1,"enabled":true,"sounds":{"x":{},"s":{"setting":"nope","mastery":[{"position":"initial","rung":"wordsInitial","p":7,"takes":3},{"position":"?","rung":"wordsInitial"}]}}}"""
        val profile = AdaptiveCodec.decode(text)
        assertTrue(profile.enabled)
        val s = profile.sound(SoundID.S)
        assertEquals(ModeSetting.Auto, s.setting)
        assertEquals(listOf(KeyMastery(ItemPosition.Initial, Rung.WordsInitial, Bkt.P_INIT, 3)), s.mastery)
        assertNull(s.challenge)
        assertEquals(setOf(SoundID.S), profile.sounds.keys)
    }

    @Test
    fun theSummaryForTheLogopedHasCodesNumbersAndTheRules() {
        val profile = AdaptiveProfile(true, mapOf(SoundID.S to SoundAdaptive(mastery = listOf(KeyMastery(ItemPosition.Initial, Rung.WordsInitial, 0.934, 40, listOf("2026-11-04", "2026-11-05"))))))
        val summary = AdaptiveCodec.summary(profile).toString()
        assertTrue(summary.contains(""""p":0.93"""))
        assertTrue(summary.contains(""""mastered":true"""))
        assertTrue(summary.contains(""""masteredAt":0.9"""))
        assertTrue(summary.contains(""""harderAbove":0.85"""))
        assertFalse(summary.contains("sare"))
    }
}
