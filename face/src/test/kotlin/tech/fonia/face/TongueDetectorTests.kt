package tech.fonia.face

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tech.fonia.face.FaceMeshIndices as I
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tongue-out heuristic (H4) on painted mouths: skin, lips between the rings, a dark opening, and a pink tongue
 * ellipse in face units. Real-video behaviour is in output/camera/RAPORT_CAMERA.md.
 */
@DisplayName("Tongue-out heuristic (H4)")
class TongueDetectorTests {
    private val skin = floatArrayOf(0.86f, 0.67f, 0.56f)
    private val lip = floatArrayOf(0.72f, 0.36f, 0.38f)
    private val tongueColour = floatArrayOf(0.84f, 0.42f, 0.48f)
    private val dark = floatArrayOf(0.16f, 0.08f, 0.08f)

    /** A tongue ellipse: centre (face units, E) and radii. */
    private data class Tongue(val x: Float, val y: Float, val rx: Float, val ry: Float)

    private fun paint(face: SyntheticFace, f: FaceMeshFrame, tongue: Tongue?, lipColour: FloatArray = lip, seed: Int = 1) {
        val n = FaceMeshFrame.CROP
        val w = f.imageW.toFloat()
        val h = f.imageH.toFloat()
        val ox = FloatArray(20) { f.lipsOuter[it * 3] * w }
        val oy = FloatArray(20) { f.lipsOuter[it * 3 + 1] * h }
        val ix = FloatArray(20) { f.lipsInner[it * 3] * w }
        val iy = FloatArray(20) { f.lipsInner[it * 3 + 1] * h }
        val ex = cos(f.cropAngle)
        val ey = sin(f.cropAngle)
        val step = f.cropSide / n
        val half = f.cropSide / 2
        val p1x = f.cropCenterX - half * ex + half * ey
        val p1y = f.cropCenterY - half * ey - half * ex
        val tc = tongue?.let { face.toImage(it.x, face.mouthCenterY + it.y) }
        val rnd = java.util.Random(seed.toLong())
        for (j in 0 until n) for (i in 0 until n) {
            val px = p1x + i * step * ex - j * step * ey
            val py = p1y + i * step * ey + j * step * ex
            var c = when {
                TongueDetector.inside(ix, iy, px, py) -> dark
                TongueDetector.inside(ox, oy, px, py) -> lipColour
                else -> skin
            }
            if (tongue != null && tc != null) {
                val dx = (px - tc.first) / (tongue.rx * face.eyePx)
                val dy = (py - tc.second) / (tongue.ry * face.eyePx)
                if (dx * dx + dy * dy <= 1f) c = tongueColour
            }
            val idx = (j * n + i) * 3
            for (k in 0..2) f.mouthCrop[idx + k] = (c[k] + (rnd.nextFloat() - 0.5f) * 0.03f).coerceIn(0f, 1f)
        }
        f.hasCrop = true
    }

    private fun run(
        frames: List<Pair<SyntheticFace, Tongue?>>,
        lipColour: FloatArray = lip,
    ): Pair<MouthStateEngine, List<TongueState>> {
        val engine = MouthStateEngine(CameraSpec())
        val f = FaceMeshFrame()
        val s = MouthState()
        var t = 0L
        val rest = SyntheticFace()
        repeat(40) {
            rest.frame(t, f)
            paint(rest, f, null, lipColour, it)
            engine.process(f, s)
            t += FRAME_NANOS
        }
        val states = ArrayList<TongueState>()
        for ((k, pair) in frames.withIndex()) {
            pair.first.frame(t, f)
            paint(pair.first, f, pair.second, lipColour, 100 + k)
            engine.process(f, s)
            states.add(s.tongue)
            t += FRAME_NANOS
        }
        return Pair(engine, states)
    }

    private val open = SyntheticFace(aperture = 0.25f, jawExtra = 0.05f)

    /** What the mesh does with a tongue pushed out over the lower lip (real video): the lower lip band doubles. */
    private val tongueFace = SyntheticFace(aperture = 0.04f, jawExtra = 0.12f, lowerLip = 0.30f)

    @Test
    @DisplayName("lips and skin separate at rest; a closed or merely open mouth is no tongue")
    fun noTongue() {
        val (engine, states) = run(List(20) { Pair(SyntheticFace(), null) } + List(20) { Pair(open, null) })
        assertTrue(engine.tongue.calibrated)
        assertTrue(engine.tongue.separation > 1.5f, "d′ ${engine.tongue.separation}")
        assertTrue(states.all { it == TongueState.None }, "$states")
    }

    @Test
    @DisplayName("a tongue out over the lower lip (band doubled + red) is Out after the vote and hold, then None")
    fun out() {
        val tongue = Tongue(0f, 0.2f, 0.16f, 0.2f)
        val (engine, states) = run(List(20) { Pair(tongueFace, tongue) } + List(20) { Pair(open, null) })
        assertEquals(TongueState.Out, states[19], "$states")
        assertTrue(engine.tongue.delta[TongueDetector.Z_OUT] < 0.25f)
        assertEquals(TongueState.None, states.last(), "$states")
        // Not immediately: the 5-frame vote and the 250 ms hold.
        assertEquals(TongueState.None, states[2])
    }

    @Test
    @DisplayName("red in the zone without a thicker lip band is not Out (a lower lip moving in, as in speech)")
    fun colourAloneIsNotOut() {
        val states = run(List(20) { Pair(open, Tongue(0f, 0.2f, 0.16f, 0.2f)) }).second
        assertTrue(states.none { it == TongueState.Out }, "$states")
    }

    @Test
    @DisplayName("the model's own tongue score says Out, even without a colour model")
    fun modelScore() {
        val states = run(List(20) { Pair(open.copy(tongueModel = 0.8f), null) }, lipColour = skin).second
        assertEquals(TongueState.Out, states.last(), "$states")
    }

    @Test
    @DisplayName("left and right are the person's own: the tongue towards the image's right is Left")
    fun sides() {
        val left = run(List(20) { Pair(open, Tongue(0.48f, 0f, 0.22f, 0.09f)) }).second
        assertEquals(TongueState.Left, left.last(), "$left")
        val right = run(List(20) { Pair(open, Tongue(-0.48f, 0f, 0.22f, 0.09f)) }).second
        assertEquals(TongueState.Right, right.last(), "$right")
    }

    @Test
    @DisplayName("tongue down towards the chin is Down")
    fun down() {
        val states = run(List(20) { Pair(open, Tongue(0f, 0.38f, 0.17f, 0.36f)) }).second
        assertEquals(TongueState.Down, states.last(), "$states")
    }

    @Test
    @DisplayName("when the lips have the skin's colour the colour path says Unknown for the session")
    fun unknown() {
        val (engine, states) = run(List(10) { Pair(open, Tongue(0f, 0.2f, 0.16f, 0.2f)) }, lipColour = skin)
        assertTrue(!engine.tongue.calibrated)
        assertTrue(states.all { it == TongueState.Unknown }, "$states")
        // Without colours, only the model or a lip band ≥ 1.8 × rest says Out.
        val band = run(List(20) { Pair(tongueFace, null) }, lipColour = skin).second
        assertEquals(TongueState.Out, band.last(), "$band")
    }

    @Test
    @DisplayName("the zones follow docs/v2/04 §5.1")
    fun zones() {
        assertEquals(TongueDetector.Z_OUT, TongueDetector.zoneOf(0f, -0.2f))
        assertEquals(TongueDetector.Z_DOWN, TongueDetector.zoneOf(0.1f, -0.7f))
        assertEquals(TongueDetector.Z_UP, TongueDetector.zoneOf(0f, 0.3f))
        assertEquals(TongueDetector.Z_LEFT, TongueDetector.zoneOf(0.6f, 0f))
        assertEquals(TongueDetector.Z_RIGHT, TongueDetector.zoneOf(-0.6f, 0.1f))
        assertEquals(-1, TongueDetector.zoneOf(0f, 0f))
        assertEquals(I.RING, 20)
    }
}
