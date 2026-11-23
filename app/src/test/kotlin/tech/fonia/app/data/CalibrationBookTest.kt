package tech.fonia.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.app.judge.AnchorCandidate
import tech.fonia.dsp.TalkerCalibration
import tech.fonia.model.ErrorKind
import tech.fonia.model.SoundId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class CalibrationBookTest {
    private var t = 0L
    private fun take(
        sound: SoundId = SoundId.S, verdict: String = "unsure", good: Boolean? = null, anchor: AnchorCandidate? = null,
        f0: Float? = null, kind: ErrorKind? = null, withheld: ErrorKind? = null, trial: ErrorKind? = null, day: String = "2026-11-21",
    ) = AttemptView(
        id = UUID.randomUUID(), sound = sound, step = "hold", text = sound.letter, ts = Instant.ofEpochSecond(1_000_000 + t++),
        localDay = day, verdict = verdict, errorKind = kind, withheldKind = withheld, trialKind = trial, trialHeardAs = if (trial != null) SoundId.Sh else null,
        adultGood = good, anchor = anchor, f0Hz = f0,
    )

    private val sAnchor = AnchorCandidate("s", 8200f, 8800f, 6f, 3f)

    @Test fun fiveConfirmedTakesCalibrateTheSound() {
        val four = List(4) { take(good = true) } + take(verdict = "retake", good = true) + take(good = false)
        assertFalse(CalibrationBook.build(four).calibrated(SoundId.S))
        assertEquals(4, CalibrationBook.build(four).forSound(SoundId.S).confirmedGood) // a retake never counts
        val five = four + take(verdict = "correct", good = true)
        val book = CalibrationBook.build(five)
        assertTrue(book.calibrated(SoundId.S))
        assertEquals(1, book.forSound(SoundId.S).tier)
        assertFalse(book.calibrated(SoundId.Sh))
    }

    @Test fun confirmedSibilantsBecomeAnchors() {
        val book = CalibrationBook.build(listOf(take(good = true, anchor = sAnchor), take(good = false, anchor = sAnchor), take(good = null, anchor = sAnchor)))
        val json = book.forSound(SoundId.Sh).anchorsJson
        assertNotNull("the child's anchors serve every sibilant", json)
        assertEquals(1, TalkerCalibration.fromJson(json!!).anchors.size)
        assertNull(CalibrationBook.build(listOf(take(good = false, anchor = sAnchor))).forSound(SoundId.S).anchorsJson)
    }

    @Test fun childF0IsTheMedianOfConfirmedTakesOnceThereAreThree() {
        val two = listOf(take(good = true, f0 = 250f), take(good = true, f0 = 300f))
        assertNull(CalibrationBook.build(two).forSound(SoundId.S).childF0Hz)
        val three = two + take(good = true, f0 = 280f) + take(good = false, f0 = 120f)
        assertEquals(280f, CalibrationBook.build(three).forSound(SoundId.S).childF0Hz!!, 0.01f)
        assertNull("not for an adult talker", CalibrationBook.build(three, childTalker = false).forSound(SoundId.S).childF0Hz)
    }

    @Test fun threeDisagreementsOnPraiseRaiseTheOkThreshold() {
        val two = List(2) { take(verdict = "correct", good = false) } + List(5) { take(verdict = "correct", good = true) }
        assertEquals(0f, CalibrationBook.build(two).forSound(SoundId.S).childOkOffset, 0f)
        val three = two + take(verdict = "correct", good = false)
        assertEquals(CalibrationBook.STEP, CalibrationBook.build(three).forSound(SoundId.S).childOkOffset, 0f)
        // only the last 20 answers count
        val old = three + List(20) { take(verdict = "correct", good = true) }
        assertEquals(0f, CalibrationBook.build(old).forSound(SoundId.S).childOkOffset, 0f)
    }

    @Test fun anErrorTheAdultDidNotHearRaisesThatKind() {
        val takes = listOf(
            take(verdict = "almost", good = true, withheld = ErrorKind.PlaceSwap),
            take(verdict = "namedError", good = true, kind = ErrorKind.PlaceSwap),
            take(verdict = "almost", good = false, withheld = ErrorKind.PlaceSwap),
            take(verdict = "unsure", good = true, trial = ErrorKind.Stopping), // Unsure + 👍 is a success, not an offset
        )
        val off = CalibrationBook.build(takes).forSound(SoundId.S).childErrOffset
        assertEquals(0.1f, off.getValue(ErrorKind.PlaceSwap), 1e-6f)
        assertFalse(ErrorKind.Stopping in off)
    }

    @Test fun adultReportCountsInNumbersOnly() {
        val takes = listOf(
            take(verdict = "correct", day = "2026-11-21"),
            take(verdict = "unsure", good = true, trial = ErrorKind.PlaceSwap, day = "2026-11-21"),
            take(verdict = "almost", trial = ErrorKind.PlaceSwap, day = "2026-11-19"),
            take(verdict = "retake", day = "2026-11-21"),
            take(verdict = "correct", day = "2026-11-01"),
            take(sound = SoundId.R, verdict = "unsure", day = "2026-11-20"),
        )
        val r = AdultReport.build(takes, CalibrationBook.build(takes), LocalDate.parse("2026-11-21"))
        assertEquals(3, r.daysThisWeek)
        assertEquals(5, r.attemptsThisWeek)
        val s = r.sounds.first { it.sound == SoundId.S }
        assertEquals(3, s.today)
        assertEquals(4, s.week)
        assertEquals(3, s.judgedWeek)
        assertEquals(1, s.correctWeek)
        assertEquals(1, s.abstainedWeek)
        assertEquals(1, s.adultAnsweredWeek)
        assertEquals(listOf(TrialObservation(ErrorKind.PlaceSwap, SoundId.Sh, 2)), s.trial)
        assertEquals(listOf(SoundId.S, SoundId.R), r.sounds.map { it.sound })
    }
}
