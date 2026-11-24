package tech.fonia.features.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import tech.fonia.design.Rating
import tech.fonia.domain.CoachCheck
import tech.fonia.domain.CoachMicJudge
import tech.fonia.domain.ContentSource
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.SoundID
import tech.fonia.features.session.VerdictWriter
import java.io.File

/** What the mirror says after it listens: each microphone step by its own `mic` (iOS `CoachEarTests`). */
class CoachEarTests {
    private val content = FoniaContent.load(ContentSource.directory(File(System.getProperty("fonia.content") ?: "../content")))

    @Test
    fun aHeldVoicePassesAVoiceStep() {
        val verdict = CoachEar.voice(heard = true, voicedSeconds = 0.6, needed = CoachEar.heldVoice)
        assertEquals(Rating.yes, verdict.rating)
        assertEquals("Am auzit vocea", verdict.title)
    }

    @Test
    fun soundWithoutAVoiceAsksForTheVoiceWithThePlansOwnFix() {
        val verdict = CoachEar.voice(heard = true, voicedSeconds = 0.05, needed = CoachEar.heldVoice)
        assertEquals(Rating.notYet, verdict.rating)
        assertEquals("Nu aud vocea", verdict.title)
        val z = content.coachPlan("z")!!
        val fix = z.faults.firstOrNull { it.fault == tech.fonia.domain.CoachFault.Unvoiced }?.tip
        assertEquals(fix ?: verdict.tip, CoachEar.tip(CoachEar.Way.Voice, verdict, z))
    }

    @Test
    fun aShortSyllableNeedsLessVoiceThanALongHum() {
        assertEquals(Rating.yes, CoachEar.voice(heard = true, voicedSeconds = 0.18, needed = CoachEar.SHORT_VOICE).rating)
        assertEquals(Rating.notYet, CoachEar.voice(heard = true, voicedSeconds = 0.18, needed = CoachEar.heldVoice).rating)
    }

    @Test
    fun silenceIsNotAWrongTake() {
        val verdict = CoachEar.voice(heard = false, voicedSeconds = 0.0, needed = CoachEar.heldVoice)
        assertNull(verdict.rating)
        assertEquals(VerdictWriter.notHeard.title, verdict.title)
        assertEquals(VerdictWriter.notHeard.title, CoachEar.unjudged(heard = false).title)
    }

    @Test
    fun whereThePhoneCantJudgeItSaysSoAndAsksNobody() {
        val verdict = CoachEar.unjudged(heard = true)
        assertNull(verdict.rating)
        assertFalse(verdict.asksAdult)
        assertEquals("Te-am auzit", verdict.title)
        assertNotNull(verdict.tip)
        // and its own line is kept, never a fix for a fault nobody measured
        val th = content.coachPlan("en-th")!!
        assertEquals(verdict.tip, CoachEar.tip(CoachEar.Way.Unjudged, verdict, th))
    }

    @Test
    fun everyMicrophoneStepOfARomanianPlanGetsAVerdictFromThePhone() {
        for (plan in content.coachPlans.filter { it.language == tech.fonia.domain.Language.Ro }) {
            val judges = plan.steps.filter { it.check == CoachCheck.Mic }.map { it.mic } + plan.syllables?.judge
            for (mic in judges) {
                assertNotEquals("${plan.key} $mic", CoachEar.Way.Unjudged, CoachEar.way(mic, plan, content))
            }
        }
    }

    @Test
    fun aVoiceStepIsJudgedByVoiceNeverAsThePlansSyllable() {
        val z = content.coachPlan("z")!!
        val l = content.coachPlan("l")!!
        assertEquals(CoachEar.Way.Voice, CoachEar.way(CoachMicJudge.Voice, z, content))
        assertEquals(CoachEar.Way.Sound(SoundID.Z), CoachEar.way(CoachMicJudge.Sibilant, z, content))
        assertEquals(CoachEar.Way.Voice, CoachEar.way(l.syllables?.judge, l, content))
    }

    @Test
    fun aSoundWithNoCheckOfItsOwnIsNeverSentToTheSoundCheck() {
        val l = content.coachPlan("l")!!
        assertEquals(CoachEar.Way.Unjudged, CoachEar.way(CoachMicJudge.Sibilant, l, content))
        val th = content.coachPlan("en-th")!!
        assertEquals(CoachEar.Way.Unjudged, CoachEar.way(CoachMicJudge.Th, th, content))
    }
}
