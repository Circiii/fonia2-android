package tech.fonia.speaking.coach

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeakerBaselineTest {
    private val spec = CoachSpec.v1

    private fun talk(pace: Double, range: Double, drill: Boolean = false) = TalkLandmarks(pace, range, drill)

    @Test
    fun fewerThanThreeTalksSayNothing() {
        assertNull(SpeakerBaseline.of(emptyList(), spec))
        assertNull(SpeakerBaseline.of(listOf(talk(5.0, 8.0), talk(5.2, 7.0)), spec))
        assertNull(AgainstUsual.of(6.5, 4.0, SpeakerBaseline.of(listOf(talk(5.0, 8.0), talk(5.2, 7.0)), spec), spec))
    }

    @Test
    fun theHabitIsTheMedianOfTheLastWholeTalks() {
        val talks = listOf(talk(9.0, 20.0)) + // the oldest: out of the last ten
            (1..10).map { talk(4.0 + it * 0.1, 5.0 + it * 0.5) } +
            listOf(talk(2.0, 1.0, drill = true)) // a drill does not make the habit
        val b = SpeakerBaseline.of(talks, spec)!!
        assertEquals(4.55, b.pace, 1e-9)
        assertEquals(7.75, b.pitchRangeSt, 1e-9)
        assertEquals(10, b.paceTalks)
    }

    @Test
    fun oneOddTalkDoesNotMoveTheHabit() {
        val b = SpeakerBaseline.of(listOf(talk(5.0, 8.0), talk(5.1, 8.0), talk(12.0, 30.0)), spec)!!
        assertEquals(5.1, b.pace, 1e-9)
        assertEquals(8.0, b.pitchRangeSt, 1e-9)
    }

    @Test
    fun unmeasuredTalksAreSkippedPerMeasure() {
        val talks = listOf(talk(5.0, Double.NaN), talk(5.0, Double.NaN), talk(5.0, 8.0), talk(Double.NaN, 8.0))
        val b = SpeakerBaseline.of(talks, spec)!!
        assertEquals(5.0, b.pace, 1e-9)
        assertTrue(b.pitchRangeSt.isNaN(), "only two talks measured the range")
        val against = AgainstUsual.of(5.1, 3.0, b, spec)!!
        assertEquals(Versus.Usual, against.pace)
        assertNull(against.pitchRange)
    }

    @Test
    fun thisTalkAgainstTheHabit() {
        val b = SpeakerBaseline.of(List(5) { talk(5.0, 8.0) }, spec)!!
        assertEquals(Versus.Above, AgainstUsual.of(5.6, 8.0, b, spec)!!.pace)
        assertEquals(Versus.Below, AgainstUsual.of(4.4, 8.0, b, spec)!!.pace)
        assertEquals(Versus.Usual, AgainstUsual.of(5.4, 8.0, b, spec)!!.pace)
        assertEquals(Versus.Below, AgainstUsual.of(5.0, 5.5, b, spec)!!.pitchRange)
        assertEquals(Versus.Above, AgainstUsual.of(5.0, 10.5, b, spec)!!.pitchRange)
        assertEquals(Versus.Usual, AgainstUsual.of(5.0, 9.0, b, spec)!!.pitchRange)
        // No measure this time: nothing to say.
        assertNull(AgainstUsual.of(Double.NaN, Double.NaN, b, spec))
    }

    @Test
    fun theReportCarriesTheComparison() {
        val ticks = (0 until 80).map { i ->
            CoachTick(t = i * 0.25, speaking = true, rate = 4.6, quiet = false, loudness01 = 0.7, looking = true, motion = 0.3, liveliness = 0.7, cue = null)
        }
        val audio = AudioSummary(
            seconds = 20.0, speechSeconds = 18.0, nuclei = 80, articulationRate = 6.0, phonationSeconds = 15.0,
            medianLevelDb = -30.0, floorDb = -60.0, pitchSpreadSt = 2.0, longPauses = emptyList(), pitchRangeSt = 4.0,
            pauses = PauseCounts(short = 3, medium = 6, long = 1),
        )
        val baseline = SpeakerBaseline.of(List(4) { talk(5.0, 8.0) }, spec)
        val r = CoachReportBuilder.build(ticks, 20.0, audio, emptyList(), null, spec, baseline)
        assertNotNull(r.usual)
        val usual = r.usual!!
        assertEquals(Versus.Above, usual.pace)
        assertEquals(Versus.Below, usual.pitchRange)
        assertEquals(PauseCounts(3, 6, 1), r.pauses)
        assertEquals(4.0, r.pitchRangeSt, 1e-9)
        // Before three talks, nothing.
        assertNull(CoachReportBuilder.build(ticks, 20.0, audio, emptyList(), null, spec, null).usual)
    }

    @Test
    fun theDrillGoesByHisHabit() {
        val slow = SpeakerBaseline.of(List(3) { talk(4.0, 8.0) }, spec)
        val fast = SpeakerBaseline.of(List(3) { talk(6.2, 8.0) }, spec)
        assertEquals(CoachText.tooSlow, CoachText.drill(Area.Pace, slow, spec))
        assertEquals(CoachText.tooFast, CoachText.drill(Area.Pace, fast, spec))
        assertEquals(CoachText.tooFast, CoachText.drill(Area.Pace, null, spec))
        assertEquals(CoachText.lively, CoachText.drill(Area.Lively, slow, spec))
        assertEquals("De obicei: 4,0 silabe pe secundă.", CoachText.drillHabit(Area.Pace, slow))
        assertEquals("De obicei, tonul urcă și coboară pe 8 semitonuri.", CoachText.drillHabit(Area.Lively, slow))
        assertNull(CoachText.drillHabit(Area.Gaze, slow))
        assertNull(CoachText.drillHabit(Area.Pace, null))
    }

    @Test
    fun pausesFallInThreeKinds() {
        val p = spec.pauses
        assertNull(PauseKind.of(0.05, p))
        assertNull(PauseKind.of(0.12, p))
        assertEquals(PauseKind.Short, PauseKind.of(0.13, p))
        assertEquals(PauseKind.Short, PauseKind.of(0.19, p))
        assertEquals(PauseKind.Medium, PauseKind.of(20 * 0.01, p))
        assertEquals(PauseKind.Medium, PauseKind.of(0.6, p))
        assertEquals(PauseKind.Medium, PauseKind.of(100 * 0.01, p))
        assertEquals(PauseKind.Long, PauseKind.of(1.01, p))
        assertEquals(PauseKind.Long, PauseKind.of(4.0, p))
    }

    @Test
    fun theCoachCountsThePausesByKind() {
        // Phrases of 8 syllables with 0.4 s between them: medium pauses, no long ones.
        val medium = AudioCoach(16000.0, spec).also { Bursts.run(Bursts.render(seconds = 16.0, rate = 4.5, pauseSeconds = 0.4), it) }.summary()
        assertTrue(medium.pauses.medium >= 5, "medium ${medium.pauses}")
        assertEquals(0, medium.pauses.long, "long ${medium.pauses}")
        assertTrue(medium.longPauses.isEmpty())
        // With 1.6 s between phrases: long pauses, kept with their place for the strip.
        val long = AudioCoach(16000.0, spec).also { Bursts.run(Bursts.render(seconds = 16.0, rate = 4.5, pauseSeconds = 1.6), it) }.summary()
        assertTrue(long.pauses.long >= 3, "long ${long.pauses}")
        assertEquals(long.pauses.long, long.longPauses.size)
        assertTrue(long.longPauses.all { it.seconds > 1.0 })
        assertTrue(long.pitchRangeSt.isFinite())
    }

    @Test
    fun theStripSaysWhereTheFillersAndLongPausesWere() {
        val report = CoachReportBuilder.build(
            emptyList(), 70.0,
            AudioSummary(
                seconds = 70.0, speechSeconds = 60.0, nuclei = 0, articulationRate = Double.NaN, phonationSeconds = 50.0,
                medianLevelDb = -30.0, floorDb = -60.0, pitchSpreadSt = Double.NaN, longPauses = listOf(Span(20.0, 21.5)),
            ),
            listOf(FillerMark(12.0, 12.4), FillerMark(40.2, 40.6), FillerMark(30.0, 30.3, "deci"), FillerMark(65.0, 65.3)),
            null, spec,
        )
        val marks = FluencyStrip.marks(report)
        assertEquals(5, marks.size)
        assertEquals(listOf(12.0, 20.0, 30.0, 40.2, 65.0), marks.map { it.start })
        assertEquals(listOf("„ăăă”", "„deci”", "pauză lungă"), FluencyStrip.groups(report).map { it.first })
        assertEquals(
            "„ăăă” de 3 ori: la 0:12, 0:40 și 1:05. „deci” o dată: la 0:30. 1 pauză lungă: la 0:20.",
            FluencyStrip.describe(report),
        )
        val empty = CoachReportBuilder.build(emptyList(), 10.0, null, emptyList(), null, spec)
        assertEquals("Fără „ăăă” și fără pauze lungi.", FluencyStrip.describe(empty))
    }

    @Test
    fun theNewSentencesFollowTheRules() {
        val texts = Versus.entries.flatMap { listOf(UsualText.pace(it), UsualText.pitchRange(it)) } +
            listOf(
                UsualText.paceDetail(5.8, 5.0, spec), UsualText.pitchRangeDetail(6.2, 8.0),
                UsualText.pauses(PauseCounts(0, 0, 0))!!, UsualText.pauses(PauseCounts(1, 4, 0))!!,
                UsualText.pauses(PauseCounts(1, 4, 1))!!, UsualText.pauses(PauseCounts(1, 1, 3))!!,
            )
        for (t in texts) {
            assertFalse(t.contains('ş') || t.contains('ţ') || t.contains('Ş') || t.contains('Ţ'), "cedilla in „$t”")
            for (word in listOf("greșit", "fericit", "trist", "nervos", "emoți", "încrede", "sigur pe", "diagnostic", "terapie")) {
                assertFalse(t.lowercase().contains(word), "„$t” names $word")
            }
        }
        assertEquals("5,8 silabe pe secundă; de obicei 5,0. Reperul general: 3,3–6,6.", UsualText.paceDetail(5.8, 5.0, spec))
        assertEquals("Pe 6 semitonuri; de obicei pe 8.", UsualText.pitchRangeDetail(6.2, 8.0))
        assertNull(UsualText.pauses(null))
        assertEquals("de 20 de ori", UsualText.times(20))
        assertEquals(
            listOf("scurte" to "sub 0,2 s", "medii" to "0,2–1 s", "lungi" to "peste 1 s"),
            UsualText.pauseKinds(spec),
        )
    }
}
