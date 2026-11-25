package tech.fonia.judge

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tech.fonia.dsp.SibilantAnchor
import tech.fonia.dsp.SibilantPlace

/** The guided calibration keeps only what a voice really gave: two different hisses, heard above the room. */
class CalibrationGuardTests {
    private fun s(n: Int) = List(n) { SibilantAnchor(SibilantPlace.S, 8200f + it * 50, 8000f, 2f, 8f) }
    private fun sh(n: Int) = List(n) { SibilantAnchor(SibilantPlace.SH, 4100f + it * 50, 3900f, 9f, -6f) }

    /** What a dead microphone gave on the emulator (2026-11-25): the same noise for S and for Ș, speech at -64 dBFS. */
    private fun noise(place: SibilantPlace, n: Int) = List(n) { SibilantAnchor(place, 14000f, 12890f, -14.7f, 5.9f) }

    private val room = PersonalReference.Room(floorDb = -62f, speechDb = -28f, f0Hz = 260f)
    private val deadRoom = PersonalReference.Room(floorDb = -98f, speechDb = -64f, f0Hz = 111f)

    @Test
    fun aRealVoiceCalibrates() {
        val p = PersonalReference()
        assertTrue(p.calibrate(room, s(6), sh(6)))
        assertTrue(p.active)
        assertNotNull(p.calibration())
        assertNotNull(p.usableRoom)
    }

    @Test
    fun noiseIsNotACalibration() {
        val p = PersonalReference()
        assertFalse(p.calibrate(deadRoom, noise(SibilantPlace.S, 6), noise(SibilantPlace.SH, 6)))
        assertFalse(p.active)
        assertNull(p.calibration())
        assertNull(p.usableRoom)
        // The same hiss twice in a good room is refused too.
        assertFalse(p.calibrate(room, s(6), s(6)))
        // A quiet room with real hisses but no voice heard is refused.
        assertFalse(p.calibrate(deadRoom, s(6), sh(6)))
    }

    @Test
    fun aSavedNoiseReferenceIsIgnoredAndReplaced() {
        val state = PersonalReference.State(
            anchors = (noise(SibilantPlace.S, 6) + noise(SibilantPlace.SH, 6))
                .map { PersonalReference.AnchorJson(it.place.rawValue, it.centroidHz, it.peakHz, it.bandRatioDb, it.tiltDb) },
            room = deadRoom,
        )
        val p = PersonalReference.fromJson(JudgeSpec.json.encodeToString(PersonalReference.State.serializer(), state))
        assertFalse(p.active)
        assertNull(p.calibration())
        assertNull(p.usableRoom)
        assertTrue(p.calibrate(room, s(6), sh(6)))
        assertTrue(p.active)
    }

    @Test
    fun aFailedRetryKeepsAGoodReference() {
        val p = PersonalReference()
        assertTrue(p.calibrate(room, s(6), sh(6)))
        assertFalse(p.calibrate(deadRoom, noise(SibilantPlace.S, 6), noise(SibilantPlace.SH, 6)))
        assertTrue(p.active)
        assertNotNull(p.usableRoom)
    }
}
