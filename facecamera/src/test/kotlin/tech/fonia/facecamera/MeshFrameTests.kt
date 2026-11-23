package tech.fonia.facecamera

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.fonia.face.FaceMeshFrame
import tech.fonia.face.MeshBlendshapes
import kotlin.math.cos
import kotlin.math.sin

/** The H1 seam's contract between this module and `:engine:face`. */
class MeshFrameTests {
    @Test
    fun blendshapeNamesAgree() {
        assertEquals(Blendshapes.NAMES, MeshBlendshapes.NAMES)
    }

    /**
     * The tongue heuristic maps crop pixel (i, j) back to the image as p1 + i·s·ex + j·s·ey (s = side / 96, ex at the
     * crop angle, image y down). FrameSampler must put exactly that image point there, also when turned.
     */
    @Test
    fun cropGeometryMatchesTheEngine() {
        val w = 320
        val h = 240
        // R = x, G = y (in pixels, below 256), B = 0.
        val rgba = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 4
            rgba[i] = x.coerceAtMost(255).toByte(); rgba[i + 1] = y.toByte(); rgba[i + 2] = 0; rgba[i + 3] = -1
        }
        val frame = RgbaFrame().apply { set(rgba, w, h, w * 4, 0, false) }
        val f = FaceMeshFrame()
        f.cropCenterX = 130f
        f.cropCenterY = 120f
        f.cropSide = 120f
        f.cropAngle = 0.3f
        val n = FaceMeshFrame.CROP
        FrameSampler.sample(frame, f.cropCenterX, f.cropCenterY, f.cropSide, f.cropSide, f.cropAngle, n, n, Border.REPLICATE, FrameSampler.valueScale(0f, 1f), 0f, f.mouthCrop)
        val ex = cos(f.cropAngle)
        val ey = sin(f.cropAngle)
        val step = f.cropSide / n
        val half = f.cropSide / 2
        val p1x = f.cropCenterX - half * ex + half * ey
        val p1y = f.cropCenterY - half * ey - half * ex
        val fast = FloatArray(n * n * 3)
        MeshFrameFiller.sampleNearest(frame, f.cropCenterX, f.cropCenterY, f.cropSide, f.cropAngle, n, fast)
        for ((i, j) in listOf(0 to 0, n - 1 to 0, 0 to n - 1, n / 2 to n / 2, 10 to n - 20)) {
            val px = p1x + i * step * ex - j * step * ey
            val py = p1y + i * step * ey + j * step * ex
            val idx = (j * n + i) * 3
            assertEquals("x at ($i, $j)", px, f.mouthCrop[idx] * 255f, 1.0f)
            assertEquals("y at ($i, $j)", py, f.mouthCrop[idx + 1] * 255f, 1.0f)
            assertEquals("fast x at ($i, $j)", px, fast[idx] * 255f, 0.75f)
            assertEquals("fast y at ($i, $j)", py, fast[idx + 1] * 255f, 0.75f)
        }
    }
}
