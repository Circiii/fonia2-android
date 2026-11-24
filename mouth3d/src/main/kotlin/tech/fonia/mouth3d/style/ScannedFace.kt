package tech.fonia.mouth3d.style

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Lower face from Infinite, 3D Head Scan by Lee Perry-Smith. The mouth bag is replaced with the articulated tract. */
internal object ScannedFace {
    private const val STRIDE = 11 // neutral xyz, UV, upper/lower opening, width, protrusion, jaw, wetness
    private val points: FloatArray
    private val triangles: IntArray
    val rim: IntArray
    val cosine: FloatArray
    val sine: FloatArray

    fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/fonia/perioral/" + name)) {
        "Missing perioral asset " + name
    }

    init {
        val b = ByteBuffer.wrap(resource("surface.bin").use { it.readBytes() }).order(ByteOrder.LITTLE_ENDIAN)
        require(b.int == 0x314e4f46)
        val vertices = b.int
        val faces = b.int
        val rimVertices = b.int
        val upperCount = b.int
        points = FloatArray(vertices * STRIDE) { b.float }
        triangles = IntArray(faces * 3) { b.short.toInt() and 65535 }
        rim = IntArray(rimVertices) { b.short.toInt() and 65535 }
        require(!b.hasRemaining() && triangles.all { it in 0 until vertices } && rim.all { it in 0 until vertices })
        cosine = FloatArray(rim.size)
        sine = FloatArray(rim.size)
        val arc = FloatArray(rim.size + 1)
        for (i in rim.indices) {
            val a = rim[i] * STRIDE
            val c = rim[(i + 1) % rim.size] * STRIDE
            arc[i + 1] = arc[i] + sqrt((points[a] - points[c]).pow2() +
                (points[a + 1] - points[c + 1]).pow2() + (points[a + 2] - points[c + 2]).pow2())
        }
        val left = arc[upperCount - 1]
        for (i in rim.indices) {
            val upper = i < upperCount
            val c = if (upper) 1f - 2f * arc[i] / left else 2f * (arc[i] - left) / (arc.last() - left) - 1f
            cosine[i] = c.coerceIn(-1f, 1f)
            sine[i] = sqrt((1f - cosine[i].pow2()).coerceAtLeast(0f)) * if (upper) 1f else -1f
        }
    }

    fun build(mb: MeshBuilder, lips: LipContours, jaw: Float, pal: StylePalette, highlight: Float, breath: Float): Int {
        val start = mb.vertexCount
        val width = lips.halfWidth / 24f - 1f
        val a = HeadModel.jawAngle(jaw)
        val c = cos(a)
        val s = sin(a)
        mb.kind = Kind.SCAN
        // Natural renders the capture's pigment. Custom stage palettes retain their colour cast.
        mb.r = pal.patch[0] / (230f / 255f)
        mb.g = pal.patch[1] / (181f / 255f)
        mb.b = pal.patch[2] / (156f / 255f)
        mb.a = 1f
        for (o in points.indices step STRIDE) {
            val x = points[o] * (1f + width * points[o + 7] + .0007f * breath * points[o + 8])
            val y = points[o + 1]
            val z = points[o + 2]
            val lift = lips.upperLift * points[o + 5] - lips.lowerDrop * points[o + 6]
            val chin = points[o + 9]
            val wet = points[o + 10]
            mb.gloss = wet
            mb.glow = highlight * wet
            mb.vertex(x, Geo.lerp(y, HeadModel.jawY(y, z, c, s), chin) + lift,
                Geo.lerp(z, HeadModel.jawZ(y, z, c, s), chin) + lips.protrusion * points[o + 8] - 3.2f * width * points[o + 7],
                0f, 0f, 0f, points[o + 3], points[o + 4])
        }
        val data = mb.data
        val stride = MeshBuilder.STRIDE
        for (i in triangles.indices step 3) {
            val ai = start + triangles[i]
            val bi = start + triangles[i + 1]
            val ci = start + triangles[i + 2]
            mb.tri(ai, bi, ci)
            val av = ai * stride
            val bv = bi * stride
            val cv = ci * stride
            val ux = data[bv] - data[av]; val uy = data[bv + 1] - data[av + 1]; val uz = data[bv + 2] - data[av + 2]
            val vx = data[cv] - data[av]; val vy = data[cv + 1] - data[av + 1]; val vz = data[cv + 2] - data[av + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (j in i..i + 2) {
                val n = (start + triangles[j]) * stride + 3
                data[n] += nx; data[n + 1] += ny; data[n + 2] += nz
            }
        }
        for (i in start until mb.vertexCount) {
            val n = i * stride + 3
            val length = sqrt(data[n].pow2() + data[n + 1].pow2() + data[n + 2].pow2()).coerceAtLeast(1e-8f)
            data[n] /= length; data[n + 1] /= length; data[n + 2] /= length
        }
        mb.glow = 0f
        return start
    }
}
