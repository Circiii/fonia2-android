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
 * plane (a, b, c, d) whose positive side is kept. Each lip cut is a soft ellipse in the sagittal plane.
 */
class StyleCamera(
    val mv: FloatArray,
    val proj: FloatArray,
    val right: FloatArray,
    val up: FloatArray,
    val vignette: FloatArray,
    val light: FloatArray,
    val clip: FloatArray = floatArrayOf(1f, 0f, 0f, 1000f),
    val upperLipCut: FloatArray = floatArrayOf(0f, 1000f, 1f, 1f),
    val lowerLipCut: FloatArray = floatArrayOf(0f, 1000f, 1f, 1f),
    val cutAmount: Float = 0f,
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

    private val FRONT_CENTRE = floatArrayOf(0f, 1.5f, 4f)
    private const val FRONT_HALF_W = 30f / 0.74f
    private const val FRONT_HALF_H = 23f
    private val PROFILE_CENTRE = floatArrayOf(0f, -11f, -12f)
    private const val PROFILE_HALF_W = 38f
    private const val PROFILE_HALF_H = 42f

    private val meshRanges = ThreadLocal.withInitial { HeadModel.Ranges() }

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
        val pc = floatArrayOf(0f, Geo.lerp(0.5f, PROFILE_CENTRE[1], cutK), Geo.lerp(6f, PROFILE_CENTRE[2], cutK))
        val c = FloatArray(3) { Geo.lerp(FRONT_CENTRE[it], pc[it], sd) }
        val aspect = width.toFloat() / max(1, height)
        val ty = tan(Math.toRadians(FOV_Y / 2.0)).toFloat()
        val tx = ty * aspect
        val hw = Geo.lerp(FRONT_HALF_W, Geo.lerp(32f, PROFILE_HALF_W, cutK), sd); val hh = Geo.lerp(FRONT_HALF_H, Geo.lerp(25.5f, PROFILE_HALF_H, cutK), sd)
        val dist = max(hw / tx, hh / ty) / max(0.2f, frame.zoom)
        val model = Mat4.mul(Mat4.rotX(frame.pitchDeg), Mat4.mul(Mat4.rotY(frame.yawDeg), Mat4.translate(-c[0], -c[1], -c[2])))
        val mv = Mat4.mul(Mat4.translate(0f, 0f, -dist), model)
        val proj = Mat4.perspective(FOV_Y, aspect, max(1f, dist - 140f), dist + 160f)
        val right = floatArrayOf(mv[0], mv[4], mv[8])
        val up = floatArrayOf(mv[1], mv[5], mv[9])
        val l = floatArrayOf(-0.38f, 0.57f, 0.73f)
        val ln = sqrt(l[0] * l[0] + l[1] * l[1] + l[2] * l[2])
        val s = side(frame.yawDeg)
        val cx = cutX(frame)
        val lips = LipContours(frame.pose, -HeadModel.jawOffset(SagittalShape.jawOf(frame.pose)).x)
        fun lipCut(t: Float): FloatArray {
            val rim = lips.outer(t)
            val wet = lips.inner(t)
            val wetZ = lips.wetDepth(t)
            val rimZ = lips.borderDepth(t, rim, LipContours.neutral.outer(t))
            return floatArrayOf((rim.y + wet.y) * 0.5f, Geo.lerp(wetZ, rimZ, 0.38f) + 0.4f,
                abs(rim.y - wet.y) * 0.63f, 4.1f + 0.22f * abs(wetZ - rimZ))
        }
        return StyleCamera(
            mv, proj, right, up, floatArrayOf(0f, 0f, 1e4f, 1e4f), floatArrayOf(l[0] / ln, l[1] / ln, l[2] / ln),
            clip = if (frame.cutAmount > 0.001f) floatArrayOf(s, 0f, 0f, -s * cx) else floatArrayOf(1f, 0f, 0f, 1000f),
            upperLipCut = lipCut(LipContours.HALF_PI),
            lowerLipCut = lipCut(-LipContours.HALF_PI),
            cutAmount = cutK,
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
        val ranges = meshRanges.get()
        HeadModel.build(mb, frame.pose, sag, pal, glow, frame.breath, ranges)

        val cut = frame.cutAmount
        if (cut > 0.001f) {
            val s = side(frame.yawDeg)
            val x = cutX(frame)
            // caps: tongue and teeth first, then the head's tissue over them, so the tongue's root and the teeth's
            // necks disappear into the floor and the gums exactly where they enter the tissue
            val tongueCap = SlabMix.mix(SlabMix.scale(pal.tongue, 0.97f), pal.highlight, if (highlight == Articulator.Tongue) 0.18f * pulse else 0f)
            capGroup(mb, ranges.tongue0, ranges.tongue1, x, s, Kind.CAP_TONGUE, tongueCap)
            capGroup(mb, ranges.teeth0, ranges.teeth1, x, s, Kind.FLAT, SlabMix.scale(pal.teeth, 0.95f))
            capGroup(mb, ranges.head0, ranges.head1, x, s, Kind.CAP_HEAD, SlabMix.mix(pal.section, pal.lips, 0.1f))
            val overlay = Geo.smoothstep(0.8f, 1f, cut)
            val lips = LipContours(frame.pose, -HeadModel.jawOffset(sag.jaw).x)
            CutOverlay.build(mb, sag, lips, spec, pal, highlight, pulse, overlay, frame.timeSec, frame.closure, frame.sinceRelease, x, -s, cam.right, cam.up)
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
