package tech.fonia.facecamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The head pose from MediaPipe's own landmarks must be MediaPipe's facial transformation matrix; the gaze must
 * behave on real faces.
 */
class FaceGeometryTests {
    private val geometry = FaceGeometry(JvmFixtures.bundle.geometry)

    private fun pose(name: String): FaceReference {
        val ref = JvmFixtures.reference(name)
        geometry.estimatePose(ref.landmarks, ref.width, ref.height)
        geometry.estimateGaze(ref.landmarks, ref.blendshapes[Blendshapes.EYE_BLINK_LEFT], ref.blendshapes[Blendshapes.EYE_BLINK_RIGHT])
        return ref
    }

    @Test
    fun poseIsMediaPipesMatrix() {
        for (name in FACE_NAMES) {
            val ref = pose(name)
            assertTrue(geometry.valid)
            val ours = poseMatrix(geometry)
            assertEquals("$name rotation", 0.0, maxRotationDiff(ours, ref.matrix), 2e-4)
            for (row in 0 until 3) assertEquals("$name translation", ref.matrix[row * 4 + 3], ours[row * 4 + 3], 2e-3)
        }
    }

    @Test
    fun headAngles() {
        // pout: the head turned towards the right of the unmirrored image, which is positive yaw on iOS.
        pose("pout")
        val frame = observationOf(JvmFixtures.reference("pout").blendshapes, geometry, BlendshapeCalibration.IDENTITY, FloatArray(7)).toFrame()
        assertEquals(37.8f, frame.headYaw, 1f)
        // small_smile looks straight into the camera.
        pose("small_smile")
        val frontal = observationOf(JvmFixtures.reference("small_smile").blendshapes, geometry, BlendshapeCalibration.IDENTITY, FloatArray(7)).toFrame()
        assertTrue(abs(frontal.headYaw) < 5f && abs(frontal.headPitch) < 6f)
    }

    @Test
    fun gazeOfAPersonLookingIntoTheCamera() {
        // smile: head turned 6° and tipped 9°, eyes turned back into the camera.
        val ref = pose("smile")
        assertEquals(2, geometry.eyesUsed)
        val frame = observationOf(ref.blendshapes, geometry, BlendshapeCalibration.IDENTITY, FloatArray(7)).toFrame()
        assertTrue("head ${frame.headYaw}", frame.headYaw < -4f)
        assertTrue("gaze ${frame.gazeYaw}, ${frame.gazePitch}", abs(frame.gazeYaw) < 4f && abs(frame.gazePitch) < 4f)
        assertTrue(frame.gazeAtCamera > 0.8f)
    }

    @Test
    fun unreliableEyesFallBackToTheHead() {
        // pout: the near eye's iris reads past 35°, the far eye is turned away; wide_open: eyes squeezed shut.
        for (name in listOf("pout", "wide_open")) {
            val ref = pose(name)
            assertEquals(name, 0, geometry.eyesUsed)
            val frame = observationOf(ref.blendshapes, geometry, BlendshapeCalibration.IDENTITY, FloatArray(7)).toFrame()
            assertEquals(frame.headYaw, frame.gazeYaw, 1e-4f)
            assertEquals(frame.headPitch, frame.gazePitch, 1e-4f)
        }
    }

    @Test
    fun theSeamGetsARigidPoseInMetres() {
        val ref = pose("smile")
        val observation = observationOf(ref.blendshapes, geometry, BlendshapeCalibration.IDENTITY, FloatArray(7))
        val m = observation.faceToCamera
        for (column in listOf(m.c0, m.c1, m.c2)) {
            assertEquals(1f, sqrt(column.x * column.x + column.y * column.y + column.z * column.z), 1e-5f)
        }
        // MediaPipe's translation is in centimetres; ARKit's, and so the seam's, in metres. The face is in front of
        // the camera, which looks down −z.
        assertEquals((ref.matrix[11] / 100).toFloat(), m.c3.z, 1e-4f)
        assertTrue(m.c3.z < -0.1f && m.c3.z > -2f)
        assertEquals(1f, m.c3.w)
    }

    @Test
    fun largestEigenvector() {
        // diag(1, 4, 2, 3) turned by a rotation in the (1, 3) plane: the largest eigenvalue's vector is (0, cos, 0, sin).
        val c = 0.6
        val s = 0.8
        val a = DoubleArray(16)
        val d = doubleArrayOf(1.0, 4.0, 2.0, 3.0)
        val q = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, c, 0.0, -s, 0.0, 0.0, 1.0, 0.0, 0.0, s, 0.0, c)
        for (i in 0 until 4) for (j in 0 until 4) for (k in 0 until 4) a[i * 4 + j] += q[i * 4 + k] * d[k] * q[j * 4 + k]
        val v = DoubleArray(16)
        val best = FaceGeometry.largestEigenvector(a, v)
        assertEquals(4.0, a[best * 5], 1e-12)
        val sign = if (v[4 + best] < 0) -1 else 1
        assertEquals(0.0, v[best], 1e-12)
        assertEquals(c, sign * v[4 + best], 1e-12)
        assertEquals(0.0, v[8 + best], 1e-12)
        assertEquals(s, sign * v[12 + best], 1e-12)
    }
}
