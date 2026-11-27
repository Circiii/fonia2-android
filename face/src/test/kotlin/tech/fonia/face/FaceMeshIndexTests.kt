package tech.fonia.face

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import tech.fonia.face.FaceMeshIndices as I
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The landmark indices of docs/v2/04 §2 were written from memory (09_DECIZII_NOI.md, "De verificat"). Here they are
 * checked against MediaPipe's `FACEMESH_LIPS` (face_mesh_connections.py) and on MediaPipe's own
 * landmarks of five real photos.
 */
@DisplayName("Face mesh indices (H1)")
class FaceMeshIndexTests {
    /** `FACEMESH_LIPS` from MediaPipe's face_mesh_connections.py, verbatim. */
    private val facemeshLips = listOf(
        61 to 146, 146 to 91, 91 to 181, 181 to 84, 84 to 17, 17 to 314, 314 to 405, 405 to 321, 321 to 375,
        375 to 291, 61 to 185, 185 to 40, 40 to 39, 39 to 37, 37 to 0, 0 to 267, 267 to 269, 269 to 270, 270 to 409,
        409 to 291, 78 to 95, 95 to 88, 88 to 178, 178 to 87, 87 to 14, 14 to 317, 317 to 402, 402 to 318, 318 to 324,
        324 to 308, 78 to 191, 191 to 80, 80 to 81, 81 to 82, 82 to 13, 13 to 312, 312 to 311, 311 to 310, 310 to 415,
        415 to 308,
    ).map { setOf(it.first, it.second) }.toSet()

    @Test
    @DisplayName("both lip rings are the closed loops of MediaPipe's FACEMESH_LIPS")
    fun ringsAreFacemeshLips() {
        for (ring in listOf(I.LIPS_OUTER, I.LIPS_INNER)) {
            assertEquals(20, ring.size)
            assertEquals(20, ring.toSet().size)
            for (k in ring.indices) {
                val edge = setOf(ring[k], ring[(k + 1) % ring.size])
                assertTrue(edge in facemeshLips, "edge $edge")
            }
        }
        // Every connection is used exactly once by the two loops.
        assertEquals(40, facemeshLips.size)
        assertEquals(61, I.LIPS_OUTER[I.CORNER_RIGHT]); assertEquals(291, I.LIPS_OUTER[I.CORNER_LEFT])
        assertEquals(0, I.LIPS_OUTER[I.UPPER_MID]); assertEquals(17, I.LIPS_OUTER[I.LOWER_MID])
        assertEquals(13, I.LIPS_INNER[I.UPPER_MID]); assertEquals(14, I.LIPS_INNER[I.LOWER_MID])
        assertEquals(listOf(468, 469, 470, 471, 472, 473, 474, 475, 476, 477), I.IRISES.toList())
        assertEquals(MeshBlendshapes.COUNT, MeshBlendshapes.NAMES.size)
        for ((name, index) in listOf(
            "jawOpen" to MeshBlendshapes.JAW_OPEN, "mouthPucker" to MeshBlendshapes.MOUTH_PUCKER,
            "mouthFunnel" to MeshBlendshapes.MOUTH_FUNNEL, "mouthSmileLeft" to MeshBlendshapes.MOUTH_SMILE_LEFT,
            "mouthSmileRight" to MeshBlendshapes.MOUTH_SMILE_RIGHT, "mouthClose" to MeshBlendshapes.MOUTH_CLOSE,
            "mouthStretchLeft" to MeshBlendshapes.MOUTH_STRETCH_LEFT, "cheekPuff" to MeshBlendshapes.CHEEK_PUFF,
        )) {
            assertEquals(name, MeshBlendshapes.NAMES[index])
        }
    }

    private class Photo(val name: String, val frame: FaceMeshFrame)

    private fun photo(name: String): Photo {
        val dir = File(System.getProperty("fonia.facecamera.faces"))
        val o = Json.parseToJsonElement(File(dir, "$name.json").readText()).jsonObject
        val all = FloatArray(478 * 3)
        o["landmarks"]!!.jsonArray.forEachIndexed { i, p ->
            val a = p.jsonArray
            for (c in 0..2) all[i * 3 + c] = a[c].jsonPrimitive.float
        }
        val f = FaceMeshFrame()
        f.isPresent = true
        f.presence = 1f
        f.imageW = o["width"]!!.jsonPrimitive.int
        f.imageH = o["height"]!!.jsonPrimitive.int
        f.setLandmarks(all)
        return Photo(name, f)
    }

    @Test
    @DisplayName("on MediaPipe's landmarks of real photos the rings sit where a mouth is")
    fun realPhotos() {
        val photos = listOf("smile", "small_smile", "speaking", "pout", "wide_open").map(::photo)
        val m = MouthMeasures()
        val aperture = HashMap<String, Float>()
        val width = HashMap<String, Float>()
        for (p in photos) {
            val f = p.frame
            val w = f.imageW
            val h = f.imageH
            fun x(arr: FloatArray, k: Int) = arr[k * 3] * w
            fun y(arr: FloatArray, k: Int) = arr[k * 3 + 1] * h
            // The person's right corner (61) is on the image's left; the corners are shared by both rings' ends.
            assertTrue(x(f.lipsOuter, I.CORNER_RIGHT) < x(f.lipsOuter, I.CORNER_LEFT), p.name)
            assertTrue(x(f.lipsInner, I.CORNER_RIGHT) < x(f.lipsInner, I.CORNER_LEFT), p.name)
            // Top to bottom: nose base, outer upper (0), inner upper (13), inner lower (14), outer lower (17), chin.
            val ys = listOf(
                y(f.anchors, I.NOSE_BASE), y(f.lipsOuter, I.UPPER_MID), y(f.lipsInner, I.UPPER_MID),
                y(f.lipsInner, I.LOWER_MID), y(f.lipsOuter, I.LOWER_MID), y(f.anchors, I.CHIN),
            )
            for (k in 1 until ys.size) assertTrue(ys[k] >= ys[k - 1] - 0.5f, "${p.name}: order $ys")
            // Every inner point lies inside (or on) the outer ring.
            val ox = FloatArray(20) { x(f.lipsOuter, it) }
            val oy = FloatArray(20) { y(f.lipsOuter, it) }
            for (k in 0 until 20) {
                if (k == I.CORNER_RIGHT || k == I.CORNER_LEFT) continue
                assertTrue(TongueDetector.inside(ox, oy, x(f.lipsInner, k), y(f.lipsInner, k)), "${p.name}: inner $k outside")
            }
            // Upper ring points above the corner line, lower ones below (in a frontal photo).
            val cy = (y(f.lipsOuter, 0) + y(f.lipsOuter, 10)) / 2
            for (k in 2..8) assertTrue(y(f.lipsOuter, k) < cy, "${p.name}: outer $k")
            for (k in 12..18) assertTrue(y(f.lipsOuter, k) > cy, "${p.name}: outer $k")
            // Irises: centre inside its four points, on the right side.
            assertTrue(f.irises[0] < f.irises[5 * 3], "${p.name}: right iris on the image left")
            m.measure(f)
            assertTrue(m.valid)
            aperture[p.name] = m.apertureRel
            width[p.name] = m.widthRel
            // Mouth ≈ 0.6–1.1 eye distances wide; eye distance ≈ 5–6 iris diameters (63 / 11.7 = 5.4).
            assertTrue(m.widthRel in 0.35f..1.2f, "${p.name}: width ${m.widthRel}")
            assertTrue(m.eyePx / m.irisPx in 4.3f..7.5f, "${p.name}: eye/iris ${m.eyePx / m.irisPx}")
        }
        // The photos ordered by visible opening.
        assertTrue(aperture["wide_open"]!! > aperture["speaking"]!!)
        assertTrue(aperture["speaking"]!! > aperture["small_smile"]!!)
        assertTrue(aperture["pout"]!! < 0.03f, "pout ${aperture["pout"]}")
        assertTrue(width["pout"]!! < width["smile"]!!)
    }
}
