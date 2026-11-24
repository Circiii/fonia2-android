package tech.fonia.mouth3d.style

import tech.fonia.mouth3d.Pose
import tech.fonia.mouth3d.PoseChannel
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Mouth aperture, wet line and vermilion border in millimetres. Both lips meet at each commissure. */
class LipContours private constructor(
    private val spread: Float,
    private val round: Float,
    part: Float,
    jawDrop: Float,
) {
    constructor(pose: Pose, jawDrop: Float) : this(
        pose[PoseChannel.LipSpread], pose[PoseChannel.LipRound], pose[PoseChannel.LipPart], jawDrop,
    )

    val halfWidth = 24f + 6.5f * spread - 5.6f * round
    val upperLift = 0.11f + part * (5.8f + 0.7f * spread) + round * 1.15f
    val lowerDrop = 0.11f + part * 2.4f + jawDrop * (0.06f + 0.56f * Geo.smoothstep(0f, 0.45f, part)) + round * 0.6f
    val upperThick = 8.4f - 0.85f * spread + 0.55f * round
    val lowerThick = 9.9f - 0.6f * spread + 0.5f * round
    val cornerThick = 0.85f + 0.6f * round
    val protrusion = 5.6f * round

    private fun middle(c: Float) = -0.85f + (1.9f * spread - 0.8f * round) * c * c + 0.32f * c

    private fun cusp(x: Float, mean: Float, width: Float) = exp(-((x - mean) / width).pow2())

    fun inner(t: Float): P2 {
        val c = cos(t)
        val s = sin(t)
        val open = powf(abs(s), if (s >= 0f) 1.28f else 1.12f)
        // The central tubercle interrupts the upper wet line. It becomes shallower on closure.
        val tubercle = if (s > 0f) 0.72f * cusp(c, 0f, 0.22f) * Geo.smoothstep(0.4f, 2.4f, upperLift) else 0f
        val y = if (s >= 0f) upperLift * open - tubercle else -lowerDrop * open
        return P2(c * halfWidth, middle(c) + y)
    }

    fun outer(t: Float): P2 {
        val c = cos(t)
        val s = sin(t)
        val wet = inner(t)
        val pad = powf(abs(s), 1.38f)
        val bow = 1f - 0.17f * cusp(c, 0f, 0.14f) + 0.12f * cusp(abs(c), 0.29f, 0.17f)
        val lowerPads = 1f + 0.055f * cusp(abs(c), 0.36f, 0.22f)
        val rim = if (s >= 0f) upperThick * bow else -lowerThick * lowerPads
        return P2(c * (halfWidth + cornerThick), wet.y + pad * rim)
    }

    /** Wet-line depth falls into the modiolus at the corners, so the mouth follows the face in profile. */
    fun wetDepth(t: Float): Float {
        val c = cos(t)
        val s = sin(t)
        val center = if (s >= 0f) 9.9f else 8.6f - 0.18f * lowerDrop
        val corner = -5.4f - 1.7f * spread + 1.05f * round
        return Geo.lerp(corner, center + protrusion, powf(abs(s), 0.86f))
    }

    /** The lip color meets skin on this soft, moving border. */
    fun borderDepth(t: Float, border: P2, neutralBorder: P2): Float {
        val s = sin(t)
        val shoulder = 0.65f + 0.35f * abs(s)
        val displacement = if (s < 0f) -0.16f * lowerDrop else 0f
        return PerioralSkin.depth(neutralBorder.x, neutralBorder.y) + protrusion * 0.34f * shoulder + displacement -
            0.10f * (abs(border.x) - abs(neutralBorder.x))
    }

    val openingY get() = middle(0f) + (upperLift - lowerDrop) / 2f
    val frontZ get() = maxOf(wetDepth(HALF_PI), wetDepth(-HALF_PI))

    companion object {
        internal const val HALF_PI = 1.5707964f
        internal val neutral = LipContours(0f, 0f, 0f, 0f)
    }
}

/** A resampled lower face. The scan-style patch fades into the closed shell outside the working area. */
internal object PerioralSkin {
    const val RX = 65f
    const val RY_UP = 59f
    const val RY_DOWN = 60f
    const val ZC = -31f
    private const val AF = 37f

    fun depth(x: Float, y: Float): Float {
        val ry = Geo.lerp(RY_DOWN, RY_UP, Geo.smoothstep(-20f, 20f, y))
        val r = (x / RX).pow2() + (y / ry).pow2()
        val shell = ZC + AF * powf((1f - r).coerceAtLeast(0f), 1.76f)
        val span = Geo.lerp(27f, 40f, Geo.smoothstep(-42f, -13f, y))
        val useReference = (1f - Geo.smoothstep(span - 12f, span, abs(x))) *
            (1f - Geo.smoothstep(32f, 43f, abs(y))) * (1f - Geo.smoothstep(0.66f, 0.92f, r))
        return if (useReference <= 0f) shell else Geo.lerp(shell, ReferenceAnatomy.faceDepth(x, y), useReference)
    }
}
