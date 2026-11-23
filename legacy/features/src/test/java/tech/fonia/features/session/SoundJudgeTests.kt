package tech.fonia.features.session

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.audio.CapturedClip
import tech.fonia.design.Rating
import tech.fonia.domain.ContentSource
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.SoundID
import tech.fonia.judge.Head
import tech.fonia.judge.JudgeEvidence
import tech.fonia.judge.JudgeRequest
import tech.fonia.judge.JudgeSpec
import tech.fonia.judge.Level
import tech.fonia.judge.SpecVersions
import tech.fonia.judge.TakeResult
import tech.fonia.model.ErrorKind
import tech.fonia.model.SoundId
import tech.fonia.model.Verdict
import tech.fonia.store.Attempt
import java.io.File

/** The new judge behind his verdict screens: his words, one short line, never a "wrong", retakes by itself. */
class SoundJudgeTests {
    private val contentDir = File(System.getProperty("fonia.content") ?: error("fonia.content is not set"))
    private val content by lazy { FoniaContent.load(ContentSource.directory(contentDir)) }
    private val spec by lazy { JudgeSpec.load(File(contentDir.absoluteFile.parentFile, "spec")) }

    private fun result(verdict: Verdict) = TakeResult(
        verdict = verdict,
        confidence = 0.9f,
        head = Head.Fast,
        evidence = JudgeEvidence(emptyMap(), null, null, emptyList(), emptySet(), emptyList()),
        versions = SpecVersions("", "", "", "", ""),
    )

    private fun checkLine(title: String) {
        assertTrue(title, title.split(' ').size <= 8)
        assertFalse(title, title.lowercase().contains("greșit"))
    }

    @Test
    fun correctIsHisPraiseAndTheLetterCelebrates() {
        val judged = SoundJudge.map(result(Verdict.Correct), content.sound(SoundID.S))
        assertEquals(Rating.yes, judged.verdict.rating)
        assertTrue(judged.verdict.title in Praise.short)
        assertEquals(SoundJudge.Cue.Correct, judged.cue)
        assertNull(judged.verdict.tip)
    }

    @Test
    fun aNamedErrorIsHisLineAndTheInstructionOnly() {
        val judged = SoundJudge.map(result(Verdict.NamedError(ErrorKind.PlaceSwap, SoundId.Sh)), content.sound(SoundID.S))
        assertEquals("Aproape. A sunat ca Ș", judged.verdict.title)
        assertEquals(SoundJudge.Cue.Instruct, judged.cue)
        assertEquals(ErrorKind.PlaceSwap, judged.kind)
        assertNull(judged.verdict.tip)
        assertFalse(judged.verdict.asksAdult)
        checkLine(judged.verdict.title)
    }

    @Test
    fun unsureAndRetakeAskNoOneAndCountNothing() {
        for (v in listOf(Verdict.Unsure, Verdict.Retake)) {
            val judged = SoundJudge.map(result(v), content.sound(SoundID.R))
            assertEquals("Mai spune o dată", judged.verdict.title)
            assertEquals(SoundJudge.Cue.Retake, judged.cue)
            assertEquals(Attempt.Outcome.Unclear, judged.verdict.outcome)
            assertFalse(judged.verdict.asksAdult)
        }
    }

    @Test
    fun aSureTrialKindShowsTheInstructionNotARetake() {
        val trial = result(Verdict.Unsure).copy(adultNote = tech.fonia.judge.AdultNote(ErrorKind.PlaceSwap, 0.97f, SoundId.Sh, inTrial = true))
        val judged = SoundJudge.map(trial, content.sound(SoundID.S))
        assertEquals(SoundJudge.Cue.Instruct, judged.cue)
        assertEquals(ErrorKind.PlaceSwap, judged.kind)
        assertEquals(Rating.nearly, judged.verdict.rating)
        assertFalse(judged.verdict.title.contains("Ș"))
        checkLine(judged.verdict.title)
        // an unsure one is still said again
        val unsure = result(Verdict.Unsure).copy(adultNote = tech.fonia.judge.AdultNote(ErrorKind.PlaceSwap, 0.6f, SoundId.Sh, inTrial = true))
        assertEquals(SoundJudge.Cue.Retake, SoundJudge.map(unsure, content.sound(SoundID.S)).cue)
    }

    @Test
    fun everyLineIsShortAndKind() {
        for (sound in SoundID.entries) for (kind in ErrorKind.entries) {
            checkLine(SoundJudge.map(result(Verdict.NamedError(kind)), content.sound(sound)).verdict.title)
        }
        checkLine(SoundJudge.map(result(Verdict.Almost), content.sound(SoundID.Sh)).verdict.title)
    }

    @Test
    fun silenceIsARetakeFromTheRealSpec() = runBlocking {
        val judge = SoundJudge(TestScope(), zipa = null) { spec }
        val silence = CapturedClip(FloatArray(48_000), 48_000.0)
        val judged = judge.judge(JudgeRequest(SoundId.S, Level.Word, "sare"), content.sound(SoundID.S), silence)
        assertEquals(SoundJudge.Cue.Retake, judged?.cue)
    }
}
