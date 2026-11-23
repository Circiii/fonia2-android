package tech.fonia.domain.adaptive

import java.time.LocalDate
import kotlin.math.exp
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tech.fonia.domain.AgeBand
import tech.fonia.domain.AttemptsSummary
import tech.fonia.domain.Fixture
import tech.fonia.domain.ProgressionState
import tech.fonia.domain.ProgressionStep
import tech.fonia.domain.Rung
import tech.fonia.domain.SeededRandom
import tech.fonia.domain.SessionBlock
import tech.fonia.domain.SessionFocus
import tech.fonia.domain.SessionRequest
import tech.fonia.domain.SoundID

/**
 * A simulated learner against the Challenge Point controller (T2): whatever the learner, the blocks' success stays in
 * the 60–80 % the research targets. A model of a child, not evidence about children: each step down the ladder is
 * easier by a logistic amount, skill grows with practice, and some takes get no verdict.
 */
@DisplayName("Adaptive: simulated learner")
class SimulatedLearnerTests {
    /** P(good take) at difficulty [step] for a learner of [skill]; [slope] is how much one step changes it. */
    private fun chance(skill: Double, step: Double, slope: Double) = 1 / (1 + exp(-slope * (skill - step)))

    private data class Learner(val name: String, val slope: Double, val learn: Double, val start: Double, val unjudged: Double)

    private val learners = listOf(
        Learner("steady", slope = 1.5, learn = 0.0, start = 4.3, unjudged = 0.0),
        Learner("small steps", slope = 0.8, learn = 0.0, start = 3.0, unjudged = 0.0),
        Learner("big steps", slope = 3.0, learn = 0.0, start = 5.5, unjudged = 0.0),
        Learner("learning", slope = 1.5, learn = 0.002, start = 1.5, unjudged = 0.0),
        Learner("noisy room", slope = 1.5, learn = 0.001, start = 3.0, unjudged = 0.2),
        Learner("fast learner", slope = 1.5, learn = 0.004, start = 0.5, unjudged = 0.1),
    )

    /** The controller alone over [blocks] blocks of six on the ladder up to sentences said fast (nine steps). */
    private fun controllerRun(learner: Learner, seed: ULong, blocks: Int = 400, burnIn: Int = 20): List<Double> {
        val random = SeededRandom(seed)
        val steps = ChallengePoint.steps(Rung.Sentences, fastAllowed = true)
        var state = ChallengeState(Rung.Sentences)
        var skill = learner.start
        val rates = mutableListOf<Double>()
        repeat(blocks + burnIn) { index ->
            var good = 0
            var total = 0
            val at = steps.indexOf(state.step).toDouble()
            repeat(6) {
                if (random.unit() >= learner.unjudged) {
                    total += 1
                    if (random.unit() < chance(skill, at, learner.slope)) good += 1
                }
                skill += learner.learn
            }
            val tally = BlockTally(good, total)
            if (index >= burnIn && total >= ChallengePoint.MIN_JUDGED_TAKES) rates += tally.rate
            state = ChallengePoint.afterBlock(state, tally, fastAllowed = true).state
        }
        return rates
    }

    @Test
    fun theControllerKeepsEveryLearnerInTheTargetBand() {
        for (learner in learners) {
            val means = (1..8).map { seed -> controllerRun(learner, seed.toULong()).average() }
            val pooled = means.average()
            println("${learner.name}: mean block success ${"%.3f".format(pooled)} (seeds ${"%.3f".format(means.min())}–${"%.3f".format(means.max())})")
            assertTrue(pooled in ChallengePoint.TARGET_LOW..ChallengePoint.TARGET_HIGH, "${learner.name}: $pooled")
            assertTrue(means.all { it in ChallengePoint.TARGET_LOW..ChallengePoint.TARGET_HIGH }, "${learner.name}: $means")
        }
    }

    @Test
    fun withoutTheControllerTheSameLearnersLeaveTheBand() {
        // The point of T2: a fixed difficulty is too easy or too hard for most of these learners.
        val fixed = learners.map { learner ->
            val random = SeededRandom(1uL)
            var skill = learner.start
            val rates = (0 until 400).map {
                var good = 0
                repeat(6) {
                    if (random.unit() < chance(skill, 7.0, learner.slope)) good += 1
                    skill += learner.learn
                }
                good / 6.0
            }
            rates.average()
        }
        assertTrue(fixed.count { it !in ChallengePoint.TARGET_LOW..ChallengePoint.TARGET_HIGH } >= learners.size / 2, "$fixed")
    }

    /**
     * The whole engine over days: one session a day, blocks planned from the real material, the phone's judge with
     * its errors (it misses 5 % of good takes and passes 5 % of the others), BKT passing rungs on two days, the
     * controller moving inside sessions. A learner who improves climbs the ladder; one who does not stays put; both
     * practise inside the band.
     */
    @Test
    fun overWeeksTheRungsFollowTheLearnerAndTheBlocksStayInTheBand() {
        val planner = AdaptivePlanner(Fixture.content)
        fun run(learn: Double, start: Double, days: Int, seed: ULong): Pair<Rung, List<Double>> {
            val random = SeededRandom(seed)
            var saved = SoundAdaptive()
            var ceiling = Rung.WordsInitial
            var skill = start
            val rates = mutableListOf<Double>()
            for (day in 0 until days) {
                val request = SessionRequest(age = AgeBand.SixToSeven, focus = SessionFocus.Sound(SoundID.S, ceiling), history = AttemptsSummary(sessionsCompleted = 1), seed = seed + day.toULong())
                val session = AdaptiveSession(planner, SoundID.S, ceiling, AgeBand.SixToSeven, emptySet(), emptySet(), seed * 31uL + day.toULong(), saved, isReview = false)
                val (plan, _) = session.plan(Fixture.builder.build(request))
                var trials = plan.session.trials
                var rungs = plan.itemRungs
                var at = plan.firstBlockAt
                for (block in 0 until plan.blocks) {
                    var good = 0
                    var total = 0
                    val blockTrials = trials.drop(at).takeWhile { it.block == SessionBlock(SessionBlock.Kind.TargetA, block) }
                    for ((offset, trial) in blockTrials.withIndex()) {
                        val itemRung = rungs[at + offset] ?: continue
                        val difficulty = itemRung.ordinal + if (trial.prosody == tech.fonia.domain.Prosody.Fast) 0.5 else 0.0
                        val produced = random.unit() < chance(skill, difficulty, 1.5)
                        skill += learn
                        val heard = random.unit() >= 0.1
                        val judgedGood = if (produced) random.unit() >= 0.05 else random.unit() < 0.05
                        val evidence = if (heard) Evidence(judgedGood, EvidenceSource.Detector) else null
                        if (heard) {
                            total += 1
                            if (judgedGood) good += 1
                        }
                        session.take(itemRung, naturalPosition(itemRung), evidence)
                    }
                    if (total >= ChallengePoint.MIN_JUDGED_TAKES) rates += good.toDouble() / total
                    val (_, remaining) = session.blockEnded(block, BlockTally(good, total), blockTrials.mapNotNull { it.exercise.itemText })
                    at += blockTrials.size
                    trials = trials.take(at) + remaining.flatMap { it.trials }
                    rungs = rungs.take(at) + remaining.flatMap { it.itemRungs }
                }
                val (step, _) = session.end(LocalDate.of(2026, 11, 1).plusDays(day.toLong()).toString(), ProgressionState(rung = ceiling, adultConfirmed = true), finished = true)
                saved = session.state
                when (step) {
                    is ProgressionStep.Advance -> ceiling = step.to
                    // Past sentences an adult listens and agrees ("Am ascultat, e bine").
                    is ProgressionStep.NeedsAdult -> ceiling = step.next
                    else -> Unit
                }
            }
            return ceiling to rates
        }
        val improving = (1..4).map { run(learn = 0.003, start = 2.0, days = 60, seed = it.toULong()) }
        val stuck = (1..4).map { run(learn = 0.0, start = 1.6, days = 30, seed = it.toULong()) }
        for ((ceiling, rates) in improving) println("improving: reached ${ceiling.rawValue}, mean block success ${"%.3f".format(rates.average())}")
        for ((ceiling, rates) in stuck) println("not improving: stayed at ${ceiling.rawValue}, mean block success ${"%.3f".format(rates.average())}")
        assertTrue(improving.all { it.first >= Rung.Clusters }, "${improving.map { it.first }}")
        assertTrue(stuck.all { it.first == Rung.WordsInitial }, "${stuck.map { it.first }}")
        for ((_, rates) in improving + stuck) assertTrue(rates.average() in ChallengePoint.TARGET_LOW..ChallengePoint.TARGET_HIGH, "${rates.average()}")
        assertEquals(4, improving.size)
    }

    /** The position the material of each rung mostly holds the sound in (S's lists: initial, medial, final words). */
    private fun naturalPosition(rung: Rung): ItemPosition = when (rung) {
        Rung.Isolated -> ItemPosition.Isolated
        Rung.Syllables, Rung.WordsInitial, Rung.Clusters, Rung.Pairs -> ItemPosition.Initial
        Rung.WordsMedial -> ItemPosition.Medial
        Rung.WordsFinal -> ItemPosition.Final
        Rung.Sentences, Rung.Retell, Rung.Everyday -> ItemPosition.Sentence
    }
}
