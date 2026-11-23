package tech.fonia.app.lesson

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.app.data.SoundPractice
import tech.fonia.app.house.Exercise
import tech.fonia.app.judge.HeadChoice
import tech.fonia.lessons.LessonContent
import tech.fonia.lessons.StepKind
import tech.fonia.model.ErrorKind
import tech.fonia.model.SoundId
import tech.fonia.model.Verdict
import java.io.File

class PlanRulesTest {
    private val content by lazy { LessonContent.fromDirectory(File(System.getProperty("fonia.root") ?: "..", "spec/content/v2")) }

    @Test fun everyExerciseBuildsItsOwnStepForEverySound() {
        val all = SoundPractice(unlockAll = true)
        val expect = mapOf(
            Exercise.Listen to StepKind.Listen, Exercise.Hold to StepKind.Hold, Exercise.Syllables to StepKind.Syllables,
            Exercise.Words to StepKind.Words, Exercise.Pairs to StepKind.Pairs, Exercise.Sentence to StepKind.Sentence,
            Exercise.Twister to StepKind.Twister, Exercise.Retell to StepKind.Retell, Exercise.Mission to StepKind.Mission,
        )
        for (s in SoundId.entries) for ((ex, kind) in expect) {
            val plan = PlanMaker.exercise(content, s, ex, all) ?: error("$s $ex: no plan")
            assertTrue("$s $ex: ${plan.steps.map { it.kind }}", plan.steps.isNotEmpty() && plan.steps.all { it.kind == kind })
            assertTrue("$s $ex has items", plan.productions.isNotEmpty())
        }
    }

    @Test fun theTwisterHasATempoLadder() {
        val plan = PlanMaker.exercise(content, SoundId.R, Exercise.Twister, SoundPractice(unlockAll = true))!!
        assertEquals(listOf("slow", "step"), plan.steps.map { it.items.first().variant })
    }

    @Test fun guidedSessionsDifferPerSound() {
        val texts = SoundId.entries.map { s -> PlanMaker.guided(content, s, SoundPractice(), 0, false).productions.map { it.text } }
        assertEquals(9, texts.toSet().size)
    }

    @Test fun takesRetakesNeverCountAndNeverLoop() {
        val t = ItemTakes(takes = 2, maxRetakes = 2)
        assertEquals(ItemTakes.Next.Again, t.onVerdict(Verdict.Retake))
        assertEquals(ItemTakes.Next.Again, t.onVerdict(Verdict.Retake))
        assertEquals(ItemTakes.Next.Done(success = false, counted = false), t.onVerdict(Verdict.Retake))
        val u = ItemTakes(takes = 2)
        assertEquals(ItemTakes.Next.Again, u.onVerdict(Verdict.Almost))
        assertEquals(ItemTakes.Next.Done(success = true, counted = true), u.onVerdict(Verdict.Correct))
        val v = ItemTakes(takes = 5)
        val e = Verdict.NamedError(ErrorKind.PlaceSwap, SoundId.S)
        assertEquals(ItemTakes.Next.Again, v.onVerdict(e))
        assertEquals(ItemTakes.Next.Done(success = false, counted = true), v.onVerdict(e))
    }

    @Test fun targetsFindTheSoundInTheWord() {
        assertEquals(listOf(0..0, 2..2), PlanRules.targets("sase", SoundId.S))
        assertEquals(listOf(0..1), PlanRules.targets("cireașă", SoundId.Ce))
        assertEquals(listOf(0..1), PlanRules.targets("geantă", SoundId.Ge))
        assertEquals(emptyList<IntRange>(), PlanRules.targets("gară", SoundId.Ge))
        assertEquals(listOf(0..0, 3..3), PlanRules.targets("țânțar", SoundId.Ts))
    }

    // ---- the ZIPA-or-fast decision path (fakes)

    @Test fun aGateOrAFinalFastVerdictNeverWaitsForTheModel() = runTest {
        var asked = 0
        val gate = HeadChoice.choose("fast", HeadChoice.Path.Gate, { asked++; "phones" }) { "full:$it" }
        assertEquals("fast" to HeadChoice.Path.Gate, gate)
        val final = HeadChoice.choose("fast", HeadChoice.Path.FastFinal, { asked++; "phones" }) { "full:$it" }
        assertEquals("fast" to HeadChoice.Path.FastFinal, final)
        assertEquals(0, asked)
    }

    @Test fun phoneEvidenceInTimeReDecidesWithTheFullHead() = runTest {
        assertEquals("full:ev" to HeadChoice.Path.Full, HeadChoice.choose("fast", null, { "ev" }) { "full:$it" })
    }

    @Test fun noEvidenceOrNoModelKeepsTheFastHead() = runTest {
        assertEquals("fast" to HeadChoice.Path.NoEvidence, HeadChoice.choose<String, String>("fast", null, { null }) { "full:$it" })
        assertEquals("fast" to HeadChoice.Path.NoModel, HeadChoice.choose<String, String>("fast", null, null) { "full:$it" })
    }
}
