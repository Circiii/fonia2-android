package tech.fonia.domain.adaptive

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tech.fonia.domain.Exercise
import tech.fonia.domain.FeedbackLevel
import tech.fonia.domain.Language
import tech.fonia.domain.RepeatPrompt
import tech.fonia.domain.SoundID
import tech.fonia.domain.SustainRole
import tech.fonia.domain.SustainTarget

/** P5 (03 §1.2; J §1.3) and T3 (03 §1.6; G §6.2): the two modes, the automatic rule, and how feedback fades. */
@DisplayName("Adaptive: modes and feedback")
class ModesAndFeedbackTests {
    private fun session(good: Int, judged: Int = 10, day: String = "2026-11-01") = SessionTally(day, good, judged)

    private val auto = ModeSetting.Auto

    @Test
    fun theResearchNumbers() {
        assertEquals(0.60, PracticeModes.ACQUISITION_BELOW)
        assertEquals(0.80, PracticeModes.GENERALIZATION_AT)
        assertEquals(2, PracticeModes.GENERALIZATION_SESSIONS)
        assertEquals(0.50, FeedbackFading.KR_START)
        assertEquals(0.20, FeedbackFading.KR_END)
    }

    @Test
    fun aNewSoundStartsInAcquisition() {
        assertEquals(PracticeModes.Choice(PracticeMode.Acquisition, PracticeModes.Reason.NewSound), PracticeModes.choose(auto, null, emptyList()))
    }

    @Test
    fun underSixtyIsAcquisition() {
        val choice = PracticeModes.choose(auto, PracticeMode.Generalization, listOf(session(9), session(5)))
        assertEquals(PracticeModes.Choice(PracticeMode.Acquisition, PracticeModes.Reason.Below60), choice)
    }

    @Test
    fun eightyInTwoSessionsIsGeneralization() {
        val choice = PracticeModes.choose(auto, PracticeMode.Acquisition, listOf(session(5), session(8), session(9)))
        assertEquals(PracticeModes.Choice(PracticeMode.Generalization, PracticeModes.Reason.AtLeast80In2), choice)
        // One session at 80 % is not two.
        assertEquals(PracticeModes.Choice(PracticeMode.Acquisition, PracticeModes.Reason.Kept), PracticeModes.choose(auto, PracticeMode.Acquisition, listOf(session(5), session(9))))
    }

    @Test
    fun inBetweenKeepsTheLastMode() {
        assertEquals(PracticeMode.Generalization, PracticeModes.choose(auto, PracticeMode.Generalization, listOf(session(10), session(7))).mode)
        assertEquals(PracticeMode.Acquisition, PracticeModes.choose(auto, PracticeMode.Acquisition, listOf(session(5), session(7))).mode)
    }

    @Test
    fun aSessionWithLessThanABlockJudgedDoesNotCount() {
        // 5 of 5 is not a block's worth: the rule still sees one session.
        assertEquals(PracticeModes.Reason.Kept, PracticeModes.choose(auto, PracticeMode.Acquisition, listOf(session(9), session(5, judged = 5))).reason)
    }

    @Test
    fun theLogopedsChoiceWins() {
        val strong = listOf(session(10), session(10))
        assertEquals(PracticeModes.Choice(PracticeMode.Acquisition, PracticeModes.Reason.SetByAdult), PracticeModes.choose(ModeSetting.Acquisition, null, strong))
        assertEquals(PracticeModes.Choice(PracticeMode.Generalization, PracticeModes.Reason.SetByAdult), PracticeModes.choose(ModeSetting.Generalization, null, emptyList()))
    }

    @Test
    fun resultsAreGivenOnHalfTheTakesThenFadeToAFifth() {
        assertEquals(0.50, FeedbackFading.krShare(null))
        assertEquals(0.50, FeedbackFading.krShare(0.70))
        assertEquals(0.50, FeedbackFading.krShare(0.80))
        assertEquals(0.35, FeedbackFading.krShare(0.90), 1e-12)
        assertEquals(0.20, FeedbackFading.krShare(1.00), 1e-12)
        // On blocks of six: 3, then 2, then 1.
        assertEquals(3, FeedbackFading.krCount(0.50))
        assertEquals(2, FeedbackFading.krCount(0.35))
        assertEquals(1, FeedbackFading.krCount(0.20))
    }

    private val words = List(6) { Exercise.Repeat(RepeatPrompt(text = "sare$it", language = Language.Ro, sound = SoundID.S, level = RepeatPrompt.Level.Word)) }

    @Test
    fun acquisitionShowsHowItSoundedOnEveryTake() {
        assertEquals(List(6) { FeedbackLevel.Teach }, FeedbackFading.levels(PracticeMode.Acquisition, kp = false, recentSuccess = 1.0, exercises = words, seed = 1uL))
    }

    @Test
    fun knowledgeOfPerformanceAfterABadBlockOverridesGeneralization() {
        assertEquals(List(6) { FeedbackLevel.Teach }, FeedbackFading.levels(PracticeMode.Generalization, kp = true, recentSuccess = 0.4, exercises = words, seed = 1uL))
    }

    @Test
    fun generalizationAnswersSomeTakesChosenAtRandom() {
        val start = FeedbackFading.levels(PracticeMode.Generalization, kp = false, recentSuccess = 0.8, exercises = words, seed = 1uL)
        assertEquals(3, start.count { it == FeedbackLevel.Quick })
        assertEquals(3, start.count { it == FeedbackLevel.Silent })
        val faded = FeedbackFading.levels(PracticeMode.Generalization, kp = false, recentSuccess = 1.0, exercises = words, seed = 1uL)
        assertEquals(1, faded.count { it == FeedbackLevel.Quick })
        // Same seed, same draw; over many seeds every take gets its turn.
        assertEquals(start, FeedbackFading.levels(PracticeMode.Generalization, kp = false, recentSuccess = 0.8, exercises = words, seed = 1uL))
        val answered = (1..50).map { FeedbackFading.levels(PracticeMode.Generalization, false, 0.8, words, it.toULong()) }
        assertTrue((0 until 6).all { at -> answered.any { it[at] == FeedbackLevel.Quick } && answered.any { it[at] == FeedbackLevel.Silent } })
    }

    @Test
    fun aHeldSoundKeepsItsLiveMeter() {
        val held = words.take(5) + Exercise.Sustain(SustainTarget.Sound(SoundID.S), seconds = 3.0, role = SustainRole.Practice)
        assertEquals(FeedbackLevel.Teach, FeedbackFading.levels(PracticeMode.Generalization, false, 1.0, held, 3uL).last())
    }
}
