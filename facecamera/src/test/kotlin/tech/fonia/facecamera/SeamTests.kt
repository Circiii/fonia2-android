package tech.fonia.facecamera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.face.BlendshapeValues
import tech.fonia.face.FaceCheck
import tech.fonia.face.FaceSourceKind
import java.io.StringWriter

/** From model scores to what the mouth coach reads, the mapping layer, the source choice and the recording tool. */
class SeamTests {
    private fun observation(name: String, calibration: BlendshapeCalibration = BlendshapeCalibration.IDENTITY): tech.fonia.face.FaceObservation {
        val ref = JvmFixtures.reference(name)
        val geometry = FaceGeometry(JvmFixtures.bundle.geometry)
        geometry.estimatePose(ref.landmarks, ref.width, ref.height)
        geometry.estimateGaze(ref.landmarks, ref.blendshapes[Blendshapes.EYE_BLINK_LEFT], ref.blendshapes[Blendshapes.EYE_BLINK_RIGHT])
        return observationOf(ref.blendshapes, geometry, calibration, FloatArray(7))
    }

    @Test
    fun scoresMapByArKitName() {
        val ref = JvmFixtures.reference("smile")
        val values = observation("smile").blendshapes
        fun score(name: String) = ref.blendshapes[Blendshapes.NAMES.indexOf(name)]
        assertEquals(score("jawOpen"), values.jawOpen)
        assertEquals(score("mouthSmileLeft"), values.mouthSmileLeft)
        assertEquals(score("mouthSmileRight"), values.mouthSmileRight)
        assertEquals(score("mouthFunnel"), values.mouthFunnel)
        assertEquals(score("mouthPucker"), values.mouthPucker)
        assertEquals(score("cheekPuff"), values.cheekPuff)
        assertEquals("MediaPipe has no tongueOut", 0f, values.tongueOut)
        assertEquals(-1, Blendshapes.SEAM_INDICES[BlendshapeValues.NAMES.indexOf("tongueOut")])
        // The same as :face's reading by name.
        val byName = Blendshapes.NAMES.indices.associate { Blendshapes.NAMES[it] to ref.blendshapes[it] }
        assertEquals(BlendshapeValues.fromScores(byName), values)
    }

    @Test
    fun theCoachsChecksOnRealFaces() {
        // Identity mapping, MediaPipe's own outputs on the five frames: what the coach's rules make of them.
        fun frame(name: String) = observation(name).toFrame()
        assertTrue(FaceCheck.SMILE.matches(frame("smile")))
        assertTrue(FaceCheck.SMILE.matches(frame("small_smile")))
        assertTrue(FaceCheck.OPEN.matches(frame("wide_open")))
        assertTrue(FaceCheck.WIDE_OPEN.matches(frame("wide_open")))
        assertFalse(FaceCheck.OPEN.matches(frame("small_smile")))
        assertTrue(FaceCheck.RELAXED.matches(frame("speaking")))
        assertTrue(FaceCheck.LIP_BITE.matches(frame("pout")))
        for (name in FACE_NAMES) assertFalse(name, FaceCheck.TONGUE_OUT.matches(frame(name)))
    }

    @Test
    fun calibrationCurves() {
        val identity = BlendshapeCalibration.IDENTITY
        assertTrue(identity.isIdentity)
        for (x in floatArrayOf(0f, 0.137f, 0.5f, 0.999f, 1f)) assertEquals(x, identity.map(0, x), 0f)
        assertEquals(0f, identity.map(0, -0.2f), 0f)
        assertEquals(1f, identity.map(0, 1.3f), 0f)

        val jaw = BlendshapeValues.NAMES.indexOf("jawOpen")
        val curve = BlendshapeCalibration(mapOf("jawOpen" to listOf(0f to 0f, 0.2f to 0.3f, 1f to 1f)))
        assertFalse(curve.isIdentity)
        assertEquals(0.15f, curve.map(jaw, 0.1f), 1e-6f)
        assertEquals(0.3f, curve.map(jaw, 0.2f), 1e-6f)
        assertEquals(0.65f, curve.map(jaw, 0.6f), 1e-6f)
        assertEquals("other coefficients untouched", 0.6f, curve.map(0 + 1, 0.6f), 0f)
        // Through the seam: jawOpen 0.249 on "smile" becomes 0.3 + 0.049·(0.7 / 0.8).
        val mapped = observation("smile", curve).blendshapes.jawOpen
        val raw = observation("smile").blendshapes.jawOpen
        assertEquals(0.3f + (raw - 0.2f) * (0.7f / 0.8f), mapped, 1e-6f)

        assertThrows(IllegalArgumentException::class.java) { BlendshapeCalibration(mapOf("browInnerUp" to listOf(0f to 0f, 1f to 1f))) }
        assertThrows(IllegalArgumentException::class.java) { BlendshapeCalibration(mapOf("jawOpen" to listOf(0f to 0f))) }
        assertThrows(IllegalArgumentException::class.java) { BlendshapeCalibration(mapOf("jawOpen" to listOf(0f to 0.5f, 1f to 0.2f))) }
    }

    @Test
    fun simulatorRuleOnAndroid() {
        // FaceSource.swift: the scripted face only where there is no camera to use; Android adds that this is an emulator.
        assertEquals(FaceSourceKind.FAKE, preferredFaceSourceKind(isEmulator = true, cameraSupported = false))
        assertEquals(FaceSourceKind.ARKIT, preferredFaceSourceKind(isEmulator = true, cameraSupported = true))
        assertEquals(FaceSourceKind.ARKIT, preferredFaceSourceKind(isEmulator = false, cameraSupported = true))
        assertEquals("a phone never shows a made-up mouth", FaceSourceKind.ARKIT, preferredFaceSourceKind(isEmulator = false, cameraSupported = false))
    }

    @Test
    fun recordingRows() {
        val analysis = FaceAnalysis()
        val ref = JvmFixtures.reference("smile")
        analysis.clear(timestamp = 5_000_000_000, nanos = 2_500_000)
        analysis.isPresent = true
        ref.blendshapes.copyInto(analysis.scores)
        floatArrayOf(0.25f, 0.7f, 0.8f, 0.03f, 0.03f, 0f, 0f).copyInto(analysis.mapped)
        analysis.headYaw = -6.5f
        analysis.eyesUsed = 2
        val out = StringWriter()
        FaceRecording(out).use { it.write("smile", analysis) }
        val lines = out.toString().trimEnd().split("\n")
        val header = lines[0].split(",")
        val row = lines[1].split(",")
        assertEquals(4 + 52 + 7 + 7, header.size)
        assertEquals(header.size, row.size)
        assertEquals(listOf("time_ms", "label", "present", "detected"), header.take(4))
        assertEquals("score__neutral", header[4])
        assertEquals("mapped_jawOpen", header[56])
        assertEquals(listOf("5000", "smile", "1", "0"), row.take(4))
        assertEquals(ref.blendshapes[25], row[4 + 25].toFloat())
        assertEquals(-6.5f, row[header.indexOf("headYaw")].toFloat())
        assertEquals(2.5, row.last().toDouble(), 1e-9)
        assertArrayEquals(analysis.mapped, header.indices.filter { header[it].startsWith("mapped_") }.map { row[it].toFloat() }.toFloatArray(), 0f)
    }
}
