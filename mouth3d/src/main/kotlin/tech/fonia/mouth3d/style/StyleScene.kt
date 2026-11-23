package tech.fonia.mouth3d.style

import tech.fonia.mouth3d.Articulator
import tech.fonia.mouth3d.PoseSpec
import tech.fonia.mouth3d.SceneFrame
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** The colours of the stylised mouth as linear rgb triples (filled from `Mouth3DColors`). */
class StylePalette(
    val patch: FloatArray,
    val lips: FloatArray,
    val teeth: FloatArray,
    val gums: FloatArray,
    val tongue: FloatArray,
    val lining: FloatArray,
    val section: FloatArray,
    val rim: FloatArray,
    val airCold: FloatArray,
    val airWarm: FloatArray,
    val voice: FloatArray,
    val highlight: FloatArray,
) {
    companion object {
        fun rgb(argb: Long): FloatArray = floatArrayOf(((argb shr 16) and 255) / 255f, ((argb shr 8) and 255) / 255f, (argb and 255) / 255f)

        fun of(vararg argb: Long): StylePalette {
            require(argb.size == 12)
            val c = argb.map(::rgb)
            return StylePalette(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9], c[10], c[11])
        }
    }
}

/**
 * Camera of one frame: model-view, projection, the camera's axes in model space, the light, and the cut: [clip] is the
 * plane (a, b, c, d) whose positive side is kept, [lipZone] (z from, y from, y to) tells the head's cap where the lips'
 * red is.
 */
class StyleCamera(
    val mv: FloatArray,
    val proj: FloatArray,
    val right: FloatArray,
    val up: FloatArray,
    val vignette: FloatArray,
    val light: FloatArray,
    val clip: FloatArray = floatArrayOf(1f, 0f, 0f, 1000f),
    val lipZone: FloatArray = floatArrayOf(1000f, 0f, 0f, 0f),
)

/**
 * Turns a [SceneFrame] into the frame's geometry. One closed model ([HeadModel]) seen from any angle: the camera frames
 * the front (the mouth ≈ 78 % of the width) or the profile and blends between them with the yaw; the cut-away is a
 * plane sliding in from the near cheek to the midline, capped through the stencil, with [CutOverlay] on top.
 * Pure; the GL renderer and the JVM preview draw the same [MeshBuilder].
 */
object StyleScene {
    const val FOV_Y = 24f
    /** Where the cut plane starts (outside the near cheek) and ends (the midline). */
    const val CUT_FROM = -66f

    private val FRONT_CENTRE = floatArrayOf(0f, 0.5f, 2f)
    private const val FRONT_HALF_W = 33.5f / 0.78f
    private const val FRONT_HALF_H = 21f
    private val PROFILE_CENTRE = floatArrayOf(0f, -3f, -12f)
    private const val PROFILE_HALF_W = 38f
    private const val PROFILE_HALF_H = 35f

    private val ranges = HeadModel.Ranges()

    /** 0 front … 1 profile, from the yaw (either side). */
    fun sideness(yawDeg: Float) = abs(sin(Math.toRadians(yawDeg.coerceIn(-90f, 90f).toDouble()))).toFloat()

    /** +1: the camera looks at the model's −x side (yaw > 0), the cut keeps x ≥ plane; −1 mirrored. */
    fun side(yawDeg: Float) = if (yawDeg >= 0f) 1f else -1f

    fun cutX(frame: SceneFrame): Float {
        val k = frame.cutAmount
        val e = k * k * (3 - 2 * k)
        return side(frame.yawDeg) * Geo.lerp(CUT_FROM, 0f, e)
    }

    fun camera(frame: SceneFrame, width: Int, height: Int): StyleCamera {
        val sd = sideness(frame.yawDeg)
        // the plain profile frames the face (nose, lips, chin); the cut moves back to show the whole tongue
        val cutK = frame.cutAmount
        val pc = floatArrayOf(0f, Geo.lerp(-6f, PROFILE_CENTRE[1], cutK), Geo.lerp(3f, PROFILE_CENTRE[2], cutK))
        val c = FloatArray(3) { Geo.lerp(FRONT_CENTRE[it], pc[it], sd) }
        val aspect = width.toFloat() / max(1, height)
        val ty = tan(Math.toRadians(FOV_Y / 2.0)).toFloat()
        val tx = ty * aspect
        val hw = Geo.lerp(FRONT_HALF_W, Geo.lerp(34f, PROFILE_HALF_W, cutK), sd); val hh = Geo.lerp(FRONT_HALF_H, Geo.lerp(40f, PROFILE_HALF_H, cutK), sd)
        val dist = max(hw / tx, hh / ty) / max(0.2f, frame.zoom)
        val model = Mat4.mul(Mat4.rotX(frame.pitchDeg), Mat4.mul(Mat4.rotY(frame.yawDeg), Mat4.translate(-c[0], -c[1], -c[2])))
        val mv = Mat4.mul(Mat4.translate(0f, 0f, -dist), model)
        val proj = Mat4.perspective(FOV_Y, aspect, max(1f, dist - 140f), dist + 160f)
        val right = floatArrayOf(mv[0], mv[4], mv[8])
        val up = floatArrayOf(mv[1], mv[5], mv[9])
        val l = floatArrayOf(-0.4f, 0.62f, 0.68f)
        val ln = sqrt(l[0] * l[0] + l[1] * l[1] + l[2] * l[2])
        val s = side(frame.yawDeg)
        val cx = cutX(frame)
        val lips = LipContours(frame.pose, -HeadModel.jawOffset(SagittalShape.jawOf(frame.pose)).x)
        val lipZone = floatArrayOf(1.2f, -(lips.lowerDrop + lips.lowerThick) - 1f, lips.upperLift + lips.upperThick + 1f, 0f)
        return StyleCamera(
            mv, proj, right, up, floatArrayOf(0f, 0f, 1e4f, 1e4f), floatArrayOf(l[0] / ln, l[1] / ln, l[2] / ln),
            clip = if (frame.cutAmount > 0.001f) floatArrayOf(s, 0f, 0f, -s * cx) else floatArrayOf(1f, 0f, 0f, 1000f),
            lipZone = lipZone,
        )
    }

    /** Soft pulse of the highlight glow. */
    fun pulse(time: Float) = 0.68f + 0.32f * sin(2f * PI.toFloat() * 0.9f * time)

    fun build(mb: MeshBuilder, frame: SceneFrame, spec: PoseSpec, pal: StylePalette, highlight: Articulator?, cam: StyleCamera) {
        mb.reset()
        val sag = SagittalShape.of(frame.pose)
        val pulse = pulse(frame.timeSec)
        val g = pulse * 0.9f
        val glow = HeadModel.Glow(
            lips = if (highlight == Articulator.Lips) g else 0f,
            teeth = if (highlight == Articulator.Teeth) g else 0f,
            tongue = if (highlight == Articulator.Tongue) g else 0f,
        )
        HeadModel.build(mb, frame.pose, sag, pal, glow, frame.breath, ranges)

        val cut = frame.cutAmount
        if (cut > 0.001f) {
            val s = side(frame.yawDeg)
            val x = cutX(frame)
            // caps: tongue and teeth first, then the head's tissue over them, so the tongue's root and the teeth's
            // necks disappear into the floor and the gums exactly where they enter the tissue
            val tongueCap = SlabMix.mix(SlabMix.scale(pal.tongue, 0.97f), pal.highlight, if (highlight == Articulator.Tongue) 0.18f * pulse else 0f)
            capGroup(mb, ranges.tongue0, ranges.tongue1, x, s, Kind.FLAT, tongueCap)
            capGroup(mb, ranges.teeth0, ranges.teeth1, x, s, Kind.FLAT, SlabMix.scale(pal.teeth, 0.95f))
            capGroup(mb, ranges.head0, ranges.head1, x, s, Kind.CAP_HEAD, SlabMix.mix(pal.section, pal.lips, 0.1f))
            val overlay = Geo.smoothstep(0.8f, 1f, cut)
            CutOverlay.build(mb, sag, spec, pal, highlight, pulse, overlay, frame.timeSec, frame.closure, frame.sinceRelease, x, -s, cam.right, cam.up)
        }
        if (spec.flutter != null && cut < 0.5f) HeadModel.ticks(mb, frame, sag, 1f - Geo.smoothstep(0.15f, 0.5f, sideness(frame.yawDeg)))
        if (highlight == Articulator.Air && cut < 0.5f) {
            HeadModel.air(mb, frame, spec, sag, pal, cam.right, cam.up, 1f - Geo.smoothstep(0.2f, 0.6f, sideness(frame.yawDeg)))
        }
        mb.flush()
    }

    private fun capGroup(mb: MeshBuilder, from: Int, to: Int, x: Float, side: Float, kind: Float, color: FloatArray) {
        mb.stencil(from, to, clear = true)
        mb.kind = kind; mb.gloss = 0f; mb.glow = 0f
        mb.color(color)
        mb.cap(x, -75f, 75f, -100f, 50f, -side)
    }
}
