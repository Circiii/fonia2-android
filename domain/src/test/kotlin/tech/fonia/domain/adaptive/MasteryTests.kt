package tech.fonia.domain.adaptive

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tech.fonia.domain.Fixture
import tech.fonia.domain.ProgressionState
import tech.fonia.domain.ProgressionStep
import tech.fonia.domain.Rung
import tech.fonia.domain.SoundID

/** T1 (03 §1.6; G §6.4): the BKT state per child × sound × position × rung, with the research's thresholds. */
@DisplayName("Adaptive: mastery (BKT)")
class MasteryTests {
    private val detectorGood = Evidence(correct = true, source = EvidenceSource.Detector)
    private val detectorNotYet = Evidence(correct = false, source = EvidenceSource.Detector)

    @Test
    fun theResearchNumbers() {
        assertEquals(0.90, Bkt.MASTERED_AT)
        assertEquals(2, Bkt.MASTERED_DAYS)
        // Slip and guess fixed from the judges' error rates: P1's 10 % ceiling on false rejections, guess under 0.3.
        assertEquals(0.10, Bkt.DETECTOR_SLIP)
        assertTrue(Bkt.DETECTOR_GUESS <= 0.30)
        assertTrue(Bkt.ADULT_WEIGHT > 1, "the adult's rating weighs more")
    }

    @Test
    fun aSureVerdictIsOneOrZeroAndAnUnsureOneIsNothing() {
        assertEquals(detectorGood, Evidence.of(TakeOutcome.Good, adult = null))
        assertEquals(detectorNotYet, Evidence.of(TakeOutcome.Almost, adult = null))
        assertEquals(detectorNotYet, Evidence.of(TakeOutcome.NotYet, adult = null))
        // "Nu am auzit clar" and a take nobody judged leave the state alone.
        assertNull(Evidence.of(TakeOutcome.Unclear, adult = null))
        assertNull(Evidence.of(TakeOutcome.NotJudged, adult = null))
    }

    @Test
    fun theAdultsRatingWinsOverThePhones() {
        assertEquals(Evidence(true, EvidenceSource.Adult), Evidence.of(TakeOutcome.NotYet, adult = TakeOutcome.Good))
        assertEquals(Evidence(false, EvidenceSource.Adult), Evidence.of(TakeOutcome.Good, adult = TakeOutcome.Almost))
        assertEquals(Evidence(true, EvidenceSource.Adult), Evidence.of(TakeOutcome.Unclear, adult = TakeOutcome.Good))
    }

    @Test
    fun oneTakeMovesPByBayesThenTheChanceToLearn() {
        // From P(L0) = 0.1: correct 0.1·0.9 / (0.1·0.9 + 0.9·0.3) = 0.25, plus 0.75·0.01 learning.
        assertEquals(0.2575, Bkt.update(Bkt.P_INIT, detectorGood), 1e-12)
        // Not yet: 0.1·0.1 / (0.1·0.1 + 0.9·0.7) = 0.015625, plus 0.984375·0.01.
        assertEquals(0.02546875, Bkt.update(Bkt.P_INIT, detectorNotYet), 1e-12)
    }

    @Test
    fun anAdultsRatingCountsAsTwoJudgedTakes() {
        // Bayes twice on the same evidence, then one chance to learn: 0.1 → 0.25 → 0.5 → 0.505.
        assertEquals(0.505, Bkt.update(Bkt.P_INIT, Evidence(true, EvidenceSource.Adult)), 1e-12)
        val adultNo = Bkt.update(0.6, Evidence(false, EvidenceSource.Adult))
        assertTrue(adultNo < Bkt.update(0.6, detectorNotYet), "an adult's no lowers P more than the phone's")
    }

    @Test
    fun fourGoodTakesInARowCrossNinety() {
        var p = Bkt.P_INIT
        repeat(3) { p = Bkt.update(p, detectorGood) }
        assertTrue(p < Bkt.MASTERED_AT)
        p = Bkt.update(p, detectorGood)
        assertTrue(p >= Bkt.MASTERED_AT, "P = $p")
    }

    @Test
    fun theKeyPositionFollowsTheRung() {
        assertEquals(ItemPosition.Isolated, Bkt.keyPosition(Rung.Isolated, ItemPosition.Sentence))
        assertEquals(ItemPosition.Sentence, Bkt.keyPosition(Rung.Retell, null))
        assertEquals(ItemPosition.Medial, Bkt.keyPosition(Rung.WordsMedial, ItemPosition.Medial))
        // The rival word of a minimal pair holds no target sound: no key.
        assertNull(Bkt.keyPosition(Rung.Pairs, null))
    }

    private fun session(saved: SoundAdaptive, ceiling: Rung = Rung.WordsInitial, review: Boolean = false) =
        AdaptiveSession(AdaptivePlanner(Fixture.content), SoundID.S, ceiling, tech.fonia.domain.AgeBand.SixToSeven, emptySet(), emptySet(), 1uL, saved, review)

    private val open = ProgressionState(rung = Rung.WordsInitial, adultConfirmed = true)

    /** A session with [good] good takes out of [takes] on (initial, wordsInitial), ended on [day]. */
    private fun practise(saved: SoundAdaptive, day: String, takes: Int = 6, good: Int = takes, uncertain: Int = 0): Pair<AdaptiveSession, ProgressionStep> {
        val run = session(saved)
        repeat(takes) { run.take(Rung.WordsInitial, ItemPosition.Initial, if (it < good) detectorGood else detectorNotYet) }
        repeat(uncertain) { run.take(Rung.WordsInitial, ItemPosition.Initial, null) }
        run.blockEnded(0, BlockTally(good, takes), emptyList())
        return run to run.end(day, open, finished = true).first
    }

    @Test
    fun aRungIsPassedAtNinetyInTwoSessionsOnDifferentDays() {
        val (first, step1) = practise(SoundAdaptive(), "2026-11-02")
        assertEquals(ProgressionStep.Stay, step1)
        assertEquals(listOf("2026-11-02"), first.state.key(ItemPosition.Initial, Rung.WordsInitial).masteredOn)
        // Same day again: still one day.
        val (second, step2) = practise(first.state, "2026-11-02")
        assertEquals(ProgressionStep.Stay, step2)
        assertFalse(second.state.key(ItemPosition.Initial, Rung.WordsInitial).isMastered)
        // Another day: two days at P ≥ 0.9, the rung is passed.
        val (third, step3) = practise(second.state, "2026-11-03")
        assertTrue(third.state.key(ItemPosition.Initial, Rung.WordsInitial).isMastered)
        assertEquals(ProgressionStep.Advance(Rung.WordsMedial), step3)
    }

    @Test
    fun aSessionWithTooFewTakesOfTheKeyIsNotOneOfTheDays() {
        val strong = SoundAdaptive().withKey(KeyMastery(ItemPosition.Initial, Rung.WordsInitial, pKnown = 0.95, takes = 30, masteredOn = listOf("2026-11-01")))
        val (run, step) = practise(strong, "2026-11-04", takes = Bkt.MIN_TAKES_IN_SESSION - 1)
        assertEquals(listOf("2026-11-01"), run.state.key(ItemPosition.Initial, Rung.WordsInitial).masteredOn)
        assertEquals(ProgressionStep.Stay, step)
    }

    @Test
    fun unsureTakesChangeNothing() {
        val run = session(SoundAdaptive())
        repeat(20) { run.take(Rung.WordsInitial, ItemPosition.Initial, null) }
        val key = run.state.key(ItemPosition.Initial, Rung.WordsInitial)
        assertEquals(Bkt.P_INIT, key.pKnown)
        assertEquals(0, key.takes)
        val mastery = run.end("2026-11-02", open, finished = true).second.single { it.kind == AdaptiveDecision.Kind.Mastery }
        assertEquals(20, mastery.skipped)
        assertEquals(0, mastery.total)
    }

    @Test
    fun mostlyNotYetKeepsTheRung() {
        var saved = SoundAdaptive()
        for (day in 1..5) {
            val (run, step) = practise(saved, "2026-11-0$day", takes = 12, good = 4)
            assertEquals(ProgressionStep.Stay, step)
            saved = run.state
        }
        assertTrue(saved.key(ItemPosition.Initial, Rung.WordsInitial).pKnown < Bkt.MASTERED_AT)
    }

    @Test
    fun theLocksOfIOSStayInFront() {
        val mastered = SoundAdaptive().withKey(KeyMastery(ItemPosition.Isolated, Rung.Isolated, pKnown = 0.97, takes = 40, masteredOn = listOf("2026-11-01", "2026-11-02")))
        // The isolated sound with the lock on: an adult listens before the syllables open.
        val isolated = session(mastered, ceiling = Rung.Isolated)
        val (step, decisions) = isolated.end("2026-11-03", ProgressionState(), finished = true)
        assertEquals(ProgressionStep.NeedsAdult(Rung.Syllables), step)
        assertEquals("locked", decisions.single { it.kind == AdaptiveDecision.Kind.Rung }.reason)
        // Past sentences only a person moves on, as on iOS.
        val sentences = SoundAdaptive().withKey(KeyMastery(ItemPosition.Sentence, Rung.Sentences, pKnown = 0.97, takes = 40, masteredOn = listOf("2026-11-01", "2026-11-02")))
        val (step2, _) = session(sentences, ceiling = Rung.Sentences).end("2026-11-03", ProgressionState(rung = Rung.Sentences, adultConfirmed = true), finished = true)
        assertEquals(ProgressionStep.NeedsAdult(Rung.Retell), step2)
    }

    @Test
    fun reviewAndLeavingEarlyMoveNothing() {
        val mastered = SoundAdaptive().withKey(KeyMastery(ItemPosition.Initial, Rung.WordsInitial, pKnown = 0.97, takes = 40, masteredOn = listOf("2026-11-01", "2026-11-02")))
        assertEquals(ProgressionStep.Stay to "review", session(mastered, review = true).end("2026-11-03", open, true).let { it.first to it.second.last().reason })
        assertEquals(ProgressionStep.Stay to "leftEarly", session(mastered).end("2026-11-03", open, false).let { it.first to it.second.last().reason })
        assertEquals(ProgressionStep.Advance(Rung.WordsMedial), session(mastered).end("2026-11-03", open, true).first)
    }
}
