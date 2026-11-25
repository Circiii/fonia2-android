package tech.fonia.features.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.domain.SoundID
import tech.fonia.model.ErrorKind
import tech.fonia.model.SoundId
import java.io.File

/** Owner, 2026-11-24: the letter must answer his voice, calmly; no nonsense line; no retake because of a timer. */
class CalmHearingTests {
    private val letters = mapOf(
        SoundID.S to "S", SoundID.Sh to "Ș", SoundID.Z to "Z", SoundID.J to "J", SoundID.Ts to "Ț",
        SoundID.Ce to "CE", SoundID.Ge to "GE", SoundID.R to "R", SoundID.L to "L",
    )

    /** What "A sunat ca X" may name for each target: its place rival, or R ↔ L. */
    private val plausible = mapOf(
        SoundID.S to setOf("Ș"), SoundID.Sh to setOf("S"), SoundID.Z to setOf("J"), SoundID.J to setOf("Z"),
        SoundID.Ts to setOf("CE"), SoundID.Ce to setOf("Ț"), SoundID.Ge to setOf("Z sau J", "Z", "J"),
        SoundID.R to setOf("L"), SoundID.L to setOf("R"),
    )

    @Test
    fun everyNamedLineFitsItsSound() {
        for (id in SoundID.entries) for (kind in ErrorKind.entries) for (heard in listOf<SoundId?>(null) + SoundId.entries) {
            val title = SoundJudge.namedTitle(id, letters.getValue(id), kind, heard)
            val words = title.split(' ').filter { it.isNotBlank() }
            assertTrue("$id $kind $heard: '$title' is longer than 8 words", words.size <= 8)
            assertFalse(title.contains("greșit", ignoreCase = true))
            if (title.startsWith("Aproape. A sunat ca ")) {
                val named = title.removePrefix("Aproape. A sunat ca ")
                assertTrue("$id $kind $heard: '$title'", named in plausible.getValue(id))
            }
        }
    }

    @Test
    fun anLSentenceNeverSaysItSoundedLikeS() {
        for (kind in ErrorKind.entries) for (heard in listOf<SoundId?>(null) + SoundId.entries) {
            assertFalse(SoundJudge.namedTitle(SoundID.L, "L", kind, heard).contains("ca S"))
        }
        assertEquals("Aproape. Lipește limba sus", SoundJudge.namedTitle(SoundID.L, "L", ErrorKind.LtoGlide, null))
        assertEquals("Aproape. A sunat ca R", SoundJudge.namedTitle(SoundID.L, "L", ErrorKind.LtoR, SoundId.S))
        assertEquals("Aproape. A sunat ca Ș", SoundJudge.namedTitle(SoundID.S, "S", ErrorKind.PlaceSwap, null))
    }

    @Test
    fun theSpecFileIsTheDefaults() {
        val file = listOf(File("../../spec/pacing.json"), File("spec/pacing.json")).first { it.isFile }
        assertEquals(Pacing(), Pacing.parse(file.readText()))
    }

    @Test
    fun aLongSentenceGetsMoreTimeThanASyllable() {
        val p = Pacing()
        assertEquals(600L, p.judgeBudget(0.8, emulator = false))
        assertTrue(p.judgeBudget(3.0, emulator = false) >= 1200L)
        assertEquals(1500L, p.judgeBudget(9.0, emulator = false))
        assertEquals(3000L, p.judgeBudget(9.0, emulator = true))
        assertTrue("a verdict is readable", p.verdictGoodMs >= 1800 && p.verdictAlmostMs >= 1800)
    }

    private fun LiveSmoother.run(ms: Int, level: Float, fric: Boolean = false, sNess: Float = Float.NaN, aim: LiveAim = LiveAim.None) {
        repeat(ms / 8) { step(0.008f, level, 0.9f, fric, if (fric) 0.8f else 0f, sNess, 0f, 0f, aim) }
    }

    @Test
    fun theLetterAnswersWithin100msAndSettlesSlowly() {
        val s = LiveSmoother()
        s.run(48, 0.8f)
        assertTrue("on within ~50 ms: ${s.level}", s.level > 0.3f)
        assertTrue(HearRules.hears(s.level))
        s.run(400, 0.8f)
        s.run(100, 0f)
        assertTrue("still going down after 100 ms: ${s.level}", s.level > 0.2f)
        s.run(1500, 0f)
        assertTrue(s.level < 0.02f)
    }

    @Test
    fun smallWobblesDoNotMoveIt() {
        val s = LiveSmoother()
        s.run(600, 0.5f)
        val before = s.level
        repeat(100) { i -> s.step(0.008f, if (i % 2 == 0) 0.52f else 0.48f, 0.9f, false, 0f, Float.NaN, 0f, 0f, LiveAim.None) }
        assertEquals(before, s.level, 0.01f)
    }

    @Test
    fun theTargetLightsAfterAMomentAndDoesNotFlicker() {
        val s = LiveSmoother()
        val aim = LiveAim.Place(sharp = true)
        s.run(64, 0.6f, fric = true, sNess = 0.9f, aim = aim)
        assertFalse("not after one blip", s.hit)
        s.run(200, 0.6f, fric = true, sNess = 0.9f, aim = aim)
        assertTrue(s.hit)
        // A 100 ms gap in the hiss keeps it lit.
        s.run(100, 0.6f, aim = aim)
        assertTrue(s.hit)
        s.run(600, 0.6f, aim = aim)
        assertFalse(s.hit)
        // A hiss whose live flag flickers frame to frame still lights it.
        s.reset()
        repeat(40) { i -> s.step(0.008f, 0.6f, 0.2f, i % 2 == 0, 0.8f, if (i % 2 == 0) 0.9f else Float.NaN, 0f, 0f, aim) }
        assertTrue("a flickering hiss lights it", s.hit)
        // Ș on an S target never lights it.
        s.reset()
        s.run(800, 0.6f, fric = true, sNess = 0.1f, aim = aim)
        assertFalse(s.hit)
        assertTrue(s.inflation(REST_INFLATION) <= Pacing().voiceInflationTop + 1e-3f)
    }

    @Test
    fun theLetterStaysStillUntilItsOwnSoundIsHeard() {
        // A voice with no hiss on an S target: nothing lights and the letter keeps its resting size.
        val s = LiveSmoother()
        s.run(400, 0.7f, aim = LiveAim.of(SoundID.S))
        assertFalse(s.hit)
        assertEquals(REST_INFLATION, s.inflation(REST_INFLATION), 1e-3f)
        // Without a target the letter still follows the voice.
        assertTrue(s.inflation(REST_INFLATION, voiceMoves = true) > REST_INFLATION + 0.2f)

        // When the phoneme model answers, it decides: "not heard" keeps the letter dark even under a hiss on the
        // right side, "heard" lights it at once.
        val m = LiveSmoother()
        val aim = LiveAim.of(SoundID.S)
        repeat(30) { m.step(0.016f, 0.6f, 0.2f, true, 0.8f, 0.9f, 0f, 0f, aim, spotted = 0f) }
        assertFalse("the model heard no S", m.hit)
        repeat(2) { m.step(0.016f, 0.6f, 0.2f, true, 0.8f, 0.9f, 0f, 0f, aim, spotted = 1f) }
        assertTrue("the model heard S", m.hit)
        // L: no acoustic place, but the model's answer lights it too.
        val l = LiveSmoother()
        repeat(2) { l.step(0.016f, 0.6f, 0.9f, false, 0f, Float.NaN, 0f, 0f, LiveAim.of(SoundID.L), spotted = 1f) }
        assertTrue(l.hit)
    }
}

/** „Calibrare”: the room, his level and the pieces of a held hiss. */
class CalibrationMathTests {
    private val rate = 48_000.0

    private fun noise(seconds: Double, amp: Float, seed: Int = 1): FloatArray {
        val r = java.util.Random(seed.toLong())
        return FloatArray((seconds * rate).toInt()) { (r.nextGaussian() * amp).toFloat() }
    }

    @Test
    fun theRoomAndTheVoiceAreMeasured() {
        val quiet = noise(2.0, 0.0003f)
        val floor = CalibrationMath.floorDb(quiet, rate)
        assertTrue("floor $floor", floor in -75f..-65f)
        val voice = FloatArray((3.0 * rate).toInt()) { (0.05 * kotlin.math.sin(2 * Math.PI * 110 * it / rate)).toFloat() }
        val speech = CalibrationMath.speechDb(voice, rate, floor)
        assertTrue("speech $speech", speech in -32f..-26f)
        assertTrue(CalibrationMath.speechDb(quiet, rate, floor).isNaN())
    }

    @Test
    fun aHeldHissGivesEnoughPiecesEachBetweenQuiet() {
        val quiet = noise(2.0, 0.0003f)
        val floor = CalibrationMath.floorDb(quiet, rate)
        val hiss = noise(0.3, 0.0003f, 2) + noise(2.6, 0.05f, 3) + noise(0.3, 0.0003f, 4)
        val pad = CalibrationMath.padFrom(quiet, rate)
        val pieces = CalibrationMath.pieces(hiss, rate, floor, pad)
        assertTrue("pieces ${pieces.size}", pieces.size >= 5)
        assertTrue(pieces.all { it.size > pad.size * 2 })
        assertTrue(CalibrationMath.pieces(quiet, rate, floor, pad).isEmpty())
    }
}

/** The calibration on a man's real held S and Ș (output/aude/clips, ElevenLabs „Roger”); skipped when absent. */
class CalibrationOnAMansVoiceTests {
    private val dir = File(System.getProperty("user.home"), "Desktop/HSIC/output/aude/clips")

    private fun wav(name: String): FloatArray {
        val bytes = File(dir, "$name.wav").readBytes()
        var at = 12
        while (at + 8 <= bytes.size) {
            val id = String(bytes, at, 4, Charsets.US_ASCII)
            val len = java.nio.ByteBuffer.wrap(bytes, at + 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                val b = java.nio.ByteBuffer.wrap(bytes, at + 8, len).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                return FloatArray(len / 2) { b.short / 32768f }
            }
            at += 8 + len
        }
        error("no data")
    }

    @Test
    fun hisOwnBoundaryPutsHisSOnTheSSide() {
        org.junit.Assume.assumeTrue(File(dir, "held_s5.wav").isFile && File(dir, "held_sh5.wav").isFile)
        val rate = 48_000.0
        val quiet = wav("silence").let { if (it.all { s -> s == 0f }) FloatArray(it.size) { i -> ((i * 7919 % 200) - 100) * 3e-5f } else it }
        val s = wav("held_s5")
        val sh = wav("held_sh5")
        val floor = CalibrationMath.floorDb(quiet, rate)
        val pad = CalibrationMath.padFrom(quiet, rate)
        val group = tech.fonia.dsp.VoiceGroup.MALE
        fun anchors(x: FloatArray, place: tech.fonia.dsp.SibilantPlace) = CalibrationMath.pieces(x, rate, floor, pad).mapNotNull { p ->
            tech.fonia.dsp.analyzeClip(p, rate, group, null).fricatives.maxByOrNull { it.end - it.start }
                ?.let { tech.fonia.dsp.SibilantAnchor(place, it.centroidHz, it.peakHz, it.bandRatioDb, it.tiltDb) }
        }
        val ref = tech.fonia.judge.PersonalReference()
        val active = ref.calibrate(
            tech.fonia.judge.PersonalReference.Room(floor, floor + 40f, 110f),
            anchors(s, tech.fonia.dsp.SibilantPlace.S), anchors(sh, tech.fonia.dsp.SibilantPlace.SH),
        )
        assertTrue("five anchors per place", active)
        fun place(x: FloatArray, cal: tech.fonia.dsp.TalkerCalibration?) =
            tech.fonia.dsp.analyzeClip(x, rate, group, cal).fricatives.map { it.sNess }.sorted().let { it[it.size / 2] }
        val before = place(s, null)
        val after = place(s, ref.calibration())
        println("held S place: prior $before, his own $after; Ș: ${place(sh, null)} → ${place(sh, ref.calibration())}")
        assertTrue("S after calibration $after", after > 0.6f)
        assertTrue("Ș after calibration", place(sh, ref.calibration()) < 0.4f)
    }
}
