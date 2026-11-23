package tech.fonia.judge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tech.fonia.model.SoundId

/** The talker group comes from the voice of each take, smoothed over the session; nobody is rejected for it. */
class TalkerVoiceTests {
    @Test
    fun theF0RuleHasThreeGroupsAndIgnoresNoise() {
        assertEquals(TalkerGroup.Man, TalkerVoice.groupOf(110f))
        assertEquals(TalkerGroup.Woman, TalkerVoice.groupOf(200f))
        assertEquals(TalkerGroup.Child, TalkerVoice.groupOf(290f))
        assertEquals(null, TalkerVoice.groupOf(0f))
        assertEquals(null, TalkerVoice.groupOf(Float.NaN))
        assertEquals(null, TalkerVoice.groupOf(900f))
    }

    @Test
    fun aManOnAChildProfileBecomesAManAtHisFirstVoicedTake() {
        val t = TalkerTracker(TalkerGroup.Child)
        assertFalse(t.heard)
        assertEquals(TalkerGroup.Man, t.observe(118f))
        assertTrue(t.heard)
    }

    @Test
    fun anUnvoicedTakeKeepsTheLastGroup() {
        val t = TalkerTracker(TalkerGroup.Child)
        t.observe(120f)
        assertEquals(TalkerGroup.Man, t.observe(0f)) // "sssss"
        assertEquals(TalkerGroup.Man, t.observe(Float.NaN))
        // and with no voice at all yet, the profile's prior stays
        assertEquals(TalkerGroup.Child, TalkerTracker(TalkerGroup.Child).observe(0f))
    }

    @Test
    fun oneHighWordDoesNotFlipASettledMan() {
        val t = TalkerTracker(TalkerGroup.Child)
        t.observe(164f)
        t.observe(123f)
        t.observe(150f)
        assertEquals(TalkerGroup.Man, t.observe(188f)) // an excited "Șoc!"
    }

    @Test
    fun aSessionOfChildTakesSettlesOnChild() {
        val t = TalkerTracker(TalkerGroup.Man)
        listOf(300f, 280f, 310f).forEach { t.observe(it) }
        assertEquals(TalkerGroup.Child, t.group)
    }

    @Test
    fun theTrackerSurvivesARoundTripThroughPreferences() {
        val t = TalkerTracker(TalkerGroup.Child)
        listOf(120f, 130f).forEach { t.observe(it) }
        val back = TalkerTracker.decode(t.encode(), TalkerGroup.Child)
        assertEquals(TalkerGroup.Man, back.group)
        assertEquals(t.smoothedHz, back.smoothedHz, 0.5f)
        assertEquals(TalkerGroup.Woman, TalkerTracker.decode("garbage", TalkerGroup.Woman).group)
        assertEquals(TalkerGroup.Child, TalkerTracker.decode(null, TalkerGroup.Child).group)
    }

    @Test
    fun medianOfVoicedFramesSkipsUnvoicedOnes() {
        assertEquals(120f, TalkerVoice.medianVoiced(floatArrayOf(0f, 110f, 0f, 120f, 130f)))
        assertTrue(TalkerVoice.medianVoiced(floatArrayOf(0f, 0f)).isNaN())
        assertTrue(TalkerVoice.medianVoiced(floatArrayOf(300f, 300f), minVoiced = 3).isNaN())
    }

    @Test
    fun aGatedTakeDoesNotMoveTheTalker() {
        val judge = TakeJudge(loadSpec())
        val tracker = TalkerTracker(TalkerGroup.Man)
        tracker.observe(115f)
        // a short beep at 300 Hz: no word, gated, says nothing about who speaks
        val m = judge.measureForVoice(JudgeRequest(SoundId.S, Level.Word, "semafor", Position.Initial, TalkerGroup.Man), clip(cat(quiet(0.6), vowel(0.15, 300f), quiet(1.0))), tracker)
        assertTrue(m.gate != null)
        assertEquals(TalkerGroup.Man, tracker.group)
    }

    @Test
    fun aLowVoiceIsMeasuredAsAManAndNeverGatedAsAnAdult() {
        val judge = TakeJudge(loadSpec())
        val tracker = TalkerTracker(TalkerGroup.Child)
        val m = judge.measureForVoice(
            JudgeRequest(SoundId.S, Level.Word, "asa", Position.Medial, TalkerGroup.Child),
            clip(vcv(sHiss(0.15), f0 = 110f)),
            tracker,
        )
        assertEquals(TalkerGroup.Man, m.request.talker)
        assertEquals(TalkerGroup.Man, tracker.group)
        assertTrue(m.gate != RetakeReason.AdultVoice, "${m.gate}")
    }

    @Test
    fun aChildVoiceStaysAChild() {
        val judge = TakeJudge(loadSpec())
        val tracker = TalkerTracker(TalkerGroup.Child)
        val m = judge.measureForVoice(
            JudgeRequest(SoundId.S, Level.Word, "asa", Position.Medial, TalkerGroup.Child),
            clip(vcv(sHiss(0.15), f0 = 290f)),
            tracker,
        )
        assertEquals(TalkerGroup.Child, m.request.talker)
    }
}
