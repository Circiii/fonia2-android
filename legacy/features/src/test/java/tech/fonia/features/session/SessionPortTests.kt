package tech.fonia.features.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.audio.CaptureInfo
import tech.fonia.design.Rating
import tech.fonia.domain.SoundID
import tech.fonia.dsp.SibilantPlace
import tech.fonia.dsp.SoundOutcome
import tech.fonia.dsp.TimeSpan
import tech.fonia.dsp.UnclearReason
import tech.fonia.features.app.AudioHub
import tech.fonia.features.speaking.TalkReport
import tech.fonia.features.speaking.todaysIndex
import tech.fonia.store.Attempt
import java.time.LocalDate

/**
 * Port-only checks, not iOS tests: the places where the port had to reproduce a Foundation behaviour (the day of the
 * era, the Romanian number format, Swift's `stride`), the S/Ș switch for narrow-band microphones, and a few verdict
 * texts against the iOS sources.
 */
class SessionPortTests {
    /** iOS showed "Prezintă-te" (index 0 of 5) as the subject of the day on Thursday 5 November 2026. */
    @Test
    fun theSubjectOfTheDayFollowsFoundationsDayOfTheEra() {
        assertEquals(0, todaysIndex(5, LocalDate.of(2026, 11, 5)))
        assertEquals(1, todaysIndex(5, LocalDate.of(2026, 11, 6)))
        assertEquals(0, todaysIndex(0, LocalDate.of(2026, 11, 6)))
    }

    /** `.number.precision(.fractionLength(1)).locale(ro_RO)`: a comma, half to even on the shortest decimal. */
    @Test
    fun ratesReadTheRomanianWay() {
        assertEquals("4,2", romanianDecimal(4.2))
        assertEquals("4,2", romanianDecimal(4.25))
        assertEquals("4,4", romanianDecimal(4.35))
        assertEquals("3,0", romanianDecimal(3.0))
        assertEquals("12,3", romanianDecimal(12.34))
    }

    /** `stride(from:through:by: 5)` over the speech, in 15 s windows. */
    @Test
    fun theLineOfPaceMovesFiveSecondsAtATime() {
        val syllables = (0 until 160).map { it * 0.25 }
        val line = TalkReport.paceLine(syllables, 0.0, 40.0)
        assertEquals(6, line.size)
        assertEquals(60.0 / 15.0, line[0], 1e-9)
        assertEquals(listOf(0.0, 0.0), TalkReport.paceLine(emptyList(), 2.0, 2.0))
        assertEquals(listOf(1.0, 1.0), TalkReport.paceLine(listOf(1.0, 2.0, 3.0), 0.0, 3.0))
    }

    @Test
    fun phrasesAreCutAtPausesOfAQuarterSecond() {
        val runs = TalkReport.phraseLengths(listOf(0.1, 0.2, 0.3, 1.1, 1.2, 2.5), listOf(TimeSpan(0.5, 0.9), TimeSpan(2.0, 2.1)))
        assertEquals(listOf(3, 3), runs)
    }

    /** S/Ș is off under 32 kHz, as iOS's analysers decide from the rate, and for a Bluetooth headset microphone. */
    @Test
    fun narrowBandMicrophonesSwitchSibilantsOff() {
        assertTrue(AudioHub.sibilantsOff(null, 16_000.0))
        assertTrue(!AudioHub.sibilantsOff(null, 48_000.0))
        val headset = CaptureInfo(48_000.0, null, tech.fonia.audio.InputPreset.VoiceRecognition, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 1, false, 480, emptyList())
        assertTrue(AudioHub.sibilantsOff(headset, 48_000.0))
        assertEquals(Double.POSITIVE_INFINITY, AudioHub.withoutSibilants.sibilant.minSampleRateHz, 0.0)
    }

    /** A few texts, copied from Verdict.swift. */
    @Test
    fun verdictsSayWhatIOSSays() {
        val content = SessionRunTests.content
        val s = content.sound(SoundID.S)
        val wrong = VerdictWriter.sibilant(SoundOutcome.WrongPlace(SibilantPlace.SH, 2, 3), s)
        assertEquals("Aproape. A sunat ca Ș", wrong.title)
        assertEquals("De 2 ori din 3.", wrong.detail)
        assertEquals(Rating.nearly, wrong.rating)
        val once = VerdictWriter.sibilant(SoundOutcome.Partial(1, 3), s)
        assertEquals("Am auzit S o dată din 3.", once.detail)
        val missing = VerdictWriter.sibilant(SoundOutcome.Missing, s)
        assertEquals("N-am auzit S-ul", missing.title)
        assertEquals(Attempt.Outcome.NotYet, missing.outcome)
        val narrow = VerdictWriter.sibilant(SoundOutcome.Unclear(UnclearReason.LOW_BANDWIDTH), s)
        assertEquals("Nu aud bine sunetul", narrow.title)
        assertTrue(narrow.asksAdult)
        assertEquals(VerdictWriter.notHeard, VerdictWriter.sibilant(SoundOutcome.NotHeard, s))
        assertTrue(Praise.short.contains(VerdictWriter.sibilant(SoundOutcome.Good, s).title))
        assertEquals("Bravo! Limba a vibrat.", VerdictWriter.rhotic(SoundOutcome.Trill, content.sound(SoundID.R)).title)
        val many = VerdictWriter.rhotic(SoundOutcome.Partial(20, 30), content.sound(SoundID.R))
        assertEquals("De 20 de ori din 30.", many.detail)
        assertNull(VerdictWriter.personJudges(heard = true, tip = null, pose = null).rating)
    }
}
