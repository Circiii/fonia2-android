package tech.fonia.domain.adaptive

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tech.fonia.domain.AgeBand
import tech.fonia.domain.AttemptsSummary
import tech.fonia.domain.Exercise
import tech.fonia.domain.FeedbackLevel
import tech.fonia.domain.Fixture
import tech.fonia.domain.Prosody
import tech.fonia.domain.Rung
import tech.fonia.domain.SessionBlock
import tech.fonia.domain.SessionFocus
import tech.fonia.domain.SessionRequest
import tech.fonia.domain.SoundID

/** The blocks the adaptive pilot lays out, and the material they draw from, which must be the iOS sessions' own. */
@DisplayName("Adaptive: planner")
class AdaptivePlannerTests {
    private val planner = AdaptivePlanner(Fixture.content)
    private val material = AdaptiveMaterial(Fixture.content)

    private fun iosSession(sound: SoundID, rung: Rung, avoiding: Set<SoundID>) = Fixture.builder.build(
        SessionRequest(
            // The longest sessions (66 takes): 30 draws from target A, 24 from target B, enough to see every item.
            age = AgeBand.TeenAdult,
            focus = SessionFocus.Sound(sound, rung),
            history = AttemptsSummary(sessionsCompleted = 3),
            seed = 7uL,
            avoidingSounds = avoiding,
        ),
    )

    @Test
    fun eachRungsMaterialIsWhatTheIOSSessionPractises() {
        for (avoiding in listOf(emptySet(), setOf(SoundID.R, SoundID.Sh))) {
            for (sound in SoundID.entries) {
                for (rung in Rung.entries) {
                    val trials = iosSession(sound, rung, avoiding).trials.filter { it.exercise.isProduction }
                    val primary = material.primary(sound, rung, avoiding).exercises
                    val secondary = material.secondary(sound, rung, avoiding).exercises
                    val targetA = trials.filter { it.block.kind == SessionBlock.Kind.TargetA }.map { it.exercise }.toSet()
                    val targetB = trials.filter { it.block.kind == SessionBlock.Kind.TargetB }.map { it.exercise }.toSet()
                    val where = "${sound.rawValue} ${rung.rawValue} avoiding $avoiding"
                    assertEquals(primary.toSet(), targetA, "target A of $where")
                    assertEquals(secondary.toSet(), targetB, "target B of $where")
                }
            }
        }
    }

    @Test
    fun theMinimalPairBlocksListenFirstAsOnIOS() {
        val ios = iosSession(SoundID.S, Rung.Pairs, emptySet()).trials
            .filter { it.block.kind == SessionBlock.Kind.TargetA && !it.exercise.isProduction }.map { it.exercise }.toSet()
        assertTrue(material.perception(SoundID.S).toSet().containsAll(ios))
        val block = planner.block(setup(Step(Rung.Pairs), PracticeMode.Acquisition), 0)
        assertEquals(3, block.trials.takeWhile { !it.exercise.isProduction }.size)
        assertTrue(block.trials.take(3).all { it.feedback == FeedbackLevel.Quick && it.exercise.isIdentification })
    }

    private fun setup(step: Step, mode: PracticeMode, sound: SoundID = SoundID.S, kp: Boolean = false, success: Double? = null, age: AgeBand = AgeBand.SixToSeven) =
        BlockSetup(sound, step, mode, kp, success, age, emptySet(), emptySet(), seed = 11uL)

    @Test
    fun acquisitionIsBlockedPracticeOnFewTargetsWithTheModelFirst() {
        val block = planner.block(setup(Step(Rung.WordsInitial), PracticeMode.Acquisition), 0)
        val items = block.trials.map { it.exercise }
        assertEquals(6, items.size)
        // Two items, each three times in a row.
        assertEquals(2, items.distinct().size)
        assertEquals(items[0], items[1])
        assertEquals(items[1], items[2])
        assertEquals(items[3], items[5])
        assertNotEquals(items[2], items[3])
        // No picture to name without a model: every take is "repeat after me".
        assertTrue(items.all { it is Exercise.Repeat })
        assertTrue(block.trials.all { it.feedback == FeedbackLevel.Teach && it.prosody == null })
        assertTrue(block.itemRungs.all { it == Rung.WordsInitial })
        assertTrue(block.trials.all { it.block == SessionBlock(SessionBlock.Kind.TargetA, 0) })
    }

    @Test
    fun generalizationMixesTheStepWithTheMaterialBelowIt() {
        val blocks = planner.blocks(setup(Step(Rung.WordsMedial), PracticeMode.Generalization, success = 0.8), 0, 12)
        val rungs = blocks.flatMap { it.itemRungs }.filterNotNull()
        // Two in three from the step (medial words), the rest from the target below it (initial words).
        assertEquals(setOf(Rung.WordsMedial, Rung.WordsInitial), rungs.toSet())
        assertTrue(rungs.count { it == Rung.WordsMedial } > rungs.count { it == Rung.WordsInitial })
        // Knowledge of results on 3 of 6, the rest wait for the block's summary; a prosody from the spinner.
        for (block in blocks) {
            assertEquals(3, block.trials.count { it.feedback == FeedbackLevel.Quick })
            assertEquals(3, block.trials.count { it.feedback == FeedbackLevel.Silent })
            assertTrue(block.trials.all { it.prosody in AgeBand.SixToSeven.prosodies })
        }
        // Names stay names: the model is not played first.
        assertTrue(blocks.flatMap { it.trials }.any { it.exercise is Exercise.Name })
    }

    @Test
    fun aFastStepSaysEveryItemFast() {
        val block = planner.block(setup(Step(Rung.WordsInitial, fast = true), PracticeMode.Generalization), 2)
        assertTrue(block.trials.all { it.prosody == Prosody.Fast })
        assertTrue(planner.fastAllowed(SoundID.S, Rung.WordsInitial, AgeBand.SixToSeven, emptySet()))
        // Four- and five-year-olds have no "fast" on their spinner; a held S has no pace.
        assertTrue(!planner.fastAllowed(SoundID.S, Rung.WordsInitial, AgeBand.FourToFive, emptySet()))
        assertTrue(!planner.fastAllowed(SoundID.S, Rung.Isolated, AgeBand.SixToSeven, emptySet()))
    }

    @Test
    fun theSessionKeepsTheIOSOpeningMissionAndDose() {
        val request = SessionRequest(
            age = AgeBand.SixToSeven,
            focus = SessionFocus.Sound(SoundID.S, Rung.Everyday),
            history = AttemptsSummary(sessionsCompleted = 0),
            seed = 5uL,
            includesOralWarmup = true,
        )
        val base = Fixture.builder.build(request)
        val planned = planner.session(base, setup(Step(Rung.Sentences), PracticeMode.Acquisition))
        val practice = setOf(SessionBlock.Kind.TargetA, SessionBlock.Kind.TargetB, SessionBlock.Kind.Mixed)
        val head = base.trials.takeWhile { it.block.kind !in practice }
        assertEquals(head, planned.session.trials.take(head.size))
        assertEquals(base.trials.last(), planned.session.trials.last())
        assertEquals(SessionBlock.Kind.Mission, planned.session.trials.last().block.kind)
        // The builder's closing item stands in for a mixed take, which the planned blocks replace.
        assertTrue(planned.session.trials.none { it.block.kind == SessionBlock.Kind.Closing })
        assertEquals(base.targetAttempts, planned.session.targetAttempts)
        assertEquals(base.timeLimitSeconds, planned.session.timeLimitSeconds)
        val blocks = planned.session.trials.filter { it.block.kind == SessionBlock.Kind.TargetA }.groupBy { it.block.index }
        assertEquals(base.targetAttempts / 6, blocks.size)
        assertTrue(blocks.values.all { block -> block.count { it.countsAsAttempt } == 6 })
        assertEquals(planned.session.trials.size, planned.itemRungs.size)
    }

    @Test
    fun theSameSeedGivesTheSameBlocks() {
        assertEquals(planner.blocks(setup(Step(Rung.Syllables), PracticeMode.Generalization), 0, 4), planner.blocks(setup(Step(Rung.Syllables), PracticeMode.Generalization), 0, 4))
    }

    @Test
    fun everySoundAndRungPlansFullBlocks() {
        for (sound in SoundID.entries) {
            for (rung in Rung.entries) {
                for (mode in PracticeMode.entries) {
                    val block = planner.block(setup(Step(rung), mode, sound = sound), 1)
                    assertEquals(6, block.trials.count { it.exercise.isProduction }, "${sound.rawValue} ${rung.rawValue} ${mode.rawValue}")
                }
            }
        }
    }
}
