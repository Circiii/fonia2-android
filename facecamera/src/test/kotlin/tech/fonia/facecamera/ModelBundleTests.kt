package tech.fonia.facecamera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The pinned model bundle: checksum, contents and the canonical face. */
class ModelBundleTests {
    private val task = File(JvmFixtures.models, "face_landmarker.task")

    @Test
    fun pinnedChecksum() {
        assertEquals(3_758_596L, task.length())
        assertEquals(ModelBundle.SHA256, ModelBundle.sha256(task.readBytes()))
    }

    @Test
    fun aChangedBundleIsRefused() {
        val bytes = task.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        assertThrows(ModelBundle.InvalidBundle::class.java) { ModelBundle.parse(bytes) }
    }

    @Test
    fun threeModels() {
        val bundle = JvmFixtures.bundle
        // Sizes of the entries in the zip (MODELS.md).
        assertEquals(229_746, bundle.faceDetector.capacity())
        assertEquals(2_553_590, bundle.faceLandmarks.capacity())
        assertEquals(955_312, bundle.blendshapes.capacity())
        // A TFLite flatbuffer carries "TFL3" at offset 4.
        for (model in listOf(bundle.faceDetector, bundle.faceLandmarks, bundle.blendshapes)) {
            val id = ByteArray(4) { model.get(4 + it) }
            assertEquals("TFL3", String(id))
        }
    }

    @Test
    fun canonicalFace() {
        val geometry = JvmFixtures.bundle.geometry
        assertEquals(468, geometry.vertexCount)
        assertEquals("FACE_LANDMARK_PIPELINE", 1, geometry.inputSource)
        assertEquals(33, geometry.weights.count { it > 0f })
        assertEquals(1.055f, geometry.weights.sum(), 1e-4f)
        // The face's right eye corner on -x, its left on +x, the nose tip forward (+z): ARKit's face axes.
        assertArrayEquals(floatArrayOf(-4.446f, 2.664f, 3.173f), geometry.canonical.copyOfRange(33 * 3, 33 * 3 + 3), 1e-3f)
        assertArrayEquals(floatArrayOf(4.446f, 2.664f, 3.173f), geometry.canonical.copyOfRange(263 * 3, 263 * 3 + 3), 1e-3f)
        assertTrue(geometry.canonical[4 * 3 + 2] > geometry.canonical[33 * 3 + 2])
        assertEquals(0.07090994f, geometry.weights[4], 1e-7f)
    }
}
