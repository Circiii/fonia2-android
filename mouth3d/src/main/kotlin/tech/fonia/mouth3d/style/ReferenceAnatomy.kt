package tech.fonia.mouth3d.style

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Millimetre samples and low poly crowns registered to the upper incisal edge. */
internal object ReferenceAnatomy {
    private const val FACE_COLS = 53
    private const val FACE_ROWS = 45
    private val face = AnatomyReferenceData.face.littleEndian().asShortBuffer()

    private class Crown(val upper: Boolean, val points: FloatArray, val triangles: IntArray)

    private val crowns = ScannedFace.resource("crowns.bin").use { it.readBytes() }.littleEndian().run {
        List(28) {
            val upper = get().toInt() != 0
            val vertices = short.toInt()
            val triangles = short.toInt()
            val points = FloatArray(vertices * 7)
            for (i in points.indices step 7) {
                for (j in 0..2) points[i + j] = short / 100f
                for (j in 3..5) points[i + j] = get() / 127f
                points[i + 6] = (get().toInt() and 255) / 255f
            }
            Crown(upper, points, IntArray(triangles * 3) { short.toInt() and 65535 })
        }
    }

    fun faceDepth(x: Float, y: Float): Float {
        val u = ((x + 52f) / 2f).coerceIn(0f, FACE_COLS - 1f)
        val v = ((y + 44f) / 2f).coerceIn(0f, FACE_ROWS - 1f)
        val i = floor(u).toInt().coerceAtMost(FACE_COLS - 2)
        val j = floor(v).toInt().coerceAtMost(FACE_ROWS - 2)
        val o = j * FACE_COLS + i
        val a = Geo.lerp(face[o].toFloat(), face[o + 1].toFloat(), u - i)
        val b = Geo.lerp(face[o + FACE_COLS].toFloat(), face[o + FACE_COLS + 1].toFloat(), u - i)
        return Geo.lerp(a, b, v - j) * 0.01f
    }

    fun dentition(mb: MeshBuilder, pal: StylePalette, jaw: Float, glow: Float) {
        mb.kind = Kind.ENAMEL
        mb.gloss = 0.19f
        mb.glow = glow
        val a = HeadModel.jawAngle(jaw)
        val c = cos(a)
        val s = sin(a)
        for (crown in crowns) {
            val start = mb.vertexCount
            val p = crown.points
            for (i in p.indices step 7) {
                val neck = p[i + 6]
                // More amber near the gingival margin; neutral, slightly translucent incisal enamel.
                val warm = Geo.smoothstep(0.48f, 1f, neck)
                val edge = 1f - Geo.smoothstep(0f, 0.28f, neck)
                val x = p[i]
                val y = p[i + 1]
                val z = p[i + 2]
                val interior = 1f - 0.44f * Geo.smoothstep(-3f, -31f, z)
                mb.r = pal.teeth[0] * (1f - 0.09f * warm - 0.04f * edge) * interior
                mb.g = pal.teeth[1] * (1f - 0.14f * warm - 0.025f * edge) * interior
                mb.b = pal.teeth[2] * (1f - 0.20f * warm + 0.01f * edge) * interior
                val nx = p[i + 3]
                val ny = p[i + 4]
                val nz = p[i + 5]
                if (crown.upper) {
                    mb.vertex(x, y, z, nx, ny, nz)
                } else {
                    mb.vertex(x, HeadModel.jawY(y, z, c, s), HeadModel.jawZ(y, z, c, s), nx, ny * c - nz * s, ny * s + nz * c)
                }
            }
            val triangles = crown.triangles
            for (i in triangles.indices step 3) mb.tri(start + triangles[i], start + triangles[i + 1], start + triangles[i + 2])
        }
        mb.glow = 0f
    }

    private fun ByteArray.littleEndian(): ByteBuffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
}
