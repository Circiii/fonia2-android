package tech.fonia.face

import tech.fonia.face.FaceMeshIndices as I
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The raw, unsmoothed geometry of one frame (docs/v2/04 §3), in units of the eye distance E (the distance between
 * the two eye centres, each the middle of its corners) so that it does not change with the distance to the camera.
 *
 * Horizontal lengths are divided by the measured E, which shrinks with yaw like they do, so yaw cancels. Vertical
 * lengths are divided by cos(pitch) and by the yaw-corrected E. Millimetres come from the iris (11.7 mm adult,
 * 10.6 mm child; ±4–10 %), only for reporting.
 */
class MouthMeasures {
    var valid = false

    /** Eye distance in image pixels as seen, and corrected for yaw. */
    var eyePx = 0f
    var unitPx = 0f

    /** Eye-line angle, radians (image y down). */
    var rollRad = 0f

    /** Mouth width (corner to corner) in px and ÷ E. */
    var widthPx = 0f
    var widthRel = 0f

    /** Inner lip gap (mean of the three middle vertical gaps, along the face's vertical) in px and ÷ E. */
    var aperturePx = 0f
    var apertureRel = 0f

    /** Outer lip height (0 to 17) ÷ E. */
    var outerHeightRel = 0f

    /**
     * Lip "band" thickness ÷ E: inner to outer contour along the face's vertical, over the three middle points, for
     * the lower and the upper lip. A tongue pushed out between the lips about doubles the lower band (real video,
     * RAPORT_CAMERA.md); speech changes it by at most ~35 %.
     */
    var lowerBandRel = 0f
    var upperBandRel = 0f

    /** Area of the mouth opening (inner ring, shoelace) ÷ E². */
    var innerAreaRel = 0f

    /** Chin (152) to nose base (2) ÷ E. */
    var jawRel = 0f

    /** Mouth centre (middle of the corners) below the nose base, along the face's vertical, ÷ E. */
    var mouthDropRel = 0f

    /** 1 − 2|dR − dL|/(dR + dL) of the corners' distances to the midline 168→2. Indicative only. */
    var symmetry = 1f

    /** Lips in front of the face sides (z), ÷ E. Relative only, low confidence. */
    var protrusionRel = 0f

    /** Mean iris diameter, px (both eyes, both axes). */
    var irisPx = 0f

    /** Mouth corner width in px for the quality gate. */
    var mouthPxForGate = 0f

    /** Share of the lip points, nose and chin inside the image. */
    var inFrame = 0f

    /** Face axes in image px: right (eye line, person's right → left) and down. */
    var axisX = 1f
    var axisY = 0f

    fun measure(f: FaceMeshFrame) {
        valid = false
        if (!f.isPresent || f.imageW <= 0 || f.imageH <= 0) return
        val w = f.imageW.toFloat()
        val h = f.imageH.toFloat()
        val a = f.anchors
        val erx = (a[I.EYE_RIGHT_OUTER * 3] + a[I.EYE_RIGHT_INNER * 3]) * 0.5f * w
        val ery = (a[I.EYE_RIGHT_OUTER * 3 + 1] + a[I.EYE_RIGHT_INNER * 3 + 1]) * 0.5f * h
        val elx = (a[I.EYE_LEFT_OUTER * 3] + a[I.EYE_LEFT_INNER * 3]) * 0.5f * w
        val ely = (a[I.EYE_LEFT_OUTER * 3 + 1] + a[I.EYE_LEFT_INNER * 3 + 1]) * 0.5f * h
        val ex = elx - erx
        val ey = ely - ery
        eyePx = sqrt(ex * ex + ey * ey)
        if (!(eyePx > 1f)) return
        axisX = ex / eyePx
        axisY = ey / eyePx
        // Down = the eye line turned 90° clockwise on an image whose y points down.
        val downX = -axisY
        val downY = axisX
        rollRad = atan2(ey, ex)
        val cosYaw = max(cos(Math.toRadians(f.headYaw.toDouble())).toFloat(), MIN_COS)
        val cosPitch = max(cos(Math.toRadians(f.headPitch.toDouble())).toFloat(), MIN_COS)
        unitPx = eyePx / cosYaw

        val o = f.lipsOuter
        val n = f.lipsInner
        val crx = o[I.CORNER_RIGHT * 3] * w
        val cry = o[I.CORNER_RIGHT * 3 + 1] * h
        val clx = o[I.CORNER_LEFT * 3] * w
        val cly = o[I.CORNER_LEFT * 3 + 1] * h
        widthPx = dist(crx, cry, clx, cly)
        widthRel = widthPx / eyePx
        mouthPxForGate = widthPx

        // Three middle gaps of the inner ring: (82, 87), (13, 14), (312, 317), projected on the face's vertical.
        var gap = 0f
        for (k in 4..6) {
            val up = k
            val lo = I.RING - k
            val dx = (n[lo * 3] - n[up * 3]) * w
            val dy = (n[lo * 3 + 1] - n[up * 3 + 1]) * h
            gap += max(0f, dx * downX + dy * downY)
        }
        aperturePx = gap / 3f
        apertureRel = aperturePx / cosPitch / unitPx

        val ohx = (o[I.LOWER_MID * 3] - o[I.UPPER_MID * 3]) * w
        val ohy = (o[I.LOWER_MID * 3 + 1] - o[I.UPPER_MID * 3 + 1]) * h
        outerHeightRel = max(0f, ohx * downX + ohy * downY) / cosPitch / unitPx

        var lower = 0f
        var upper = 0f
        for (k in 4..6) {
            val lo = I.RING - k
            lower += max(0f, (o[lo * 3] - n[lo * 3]) * w * downX + (o[lo * 3 + 1] - n[lo * 3 + 1]) * h * downY)
            upper += max(0f, (n[k * 3] - o[k * 3]) * w * downX + (n[k * 3 + 1] - o[k * 3 + 1]) * h * downY)
        }
        lowerBandRel = lower / 3f / cosPitch / unitPx
        upperBandRel = upper / 3f / cosPitch / unitPx

        var area = 0f
        for (k in 0 until I.RING) {
            val j = (k + 1) % I.RING
            area += n[k * 3] * w * n[j * 3 + 1] * h - n[j * 3] * w * n[k * 3 + 1] * h
        }
        innerAreaRel = abs(area) * 0.5f * cosYaw / (cosPitch * eyePx * eyePx)

        val nbx = a[I.NOSE_BASE * 3] * w
        val nby = a[I.NOSE_BASE * 3 + 1] * h
        val chx = a[I.CHIN * 3] * w
        val chy = a[I.CHIN * 3 + 1] * h
        jawRel = dist(nbx, nby, chx, chy) / cosPitch / unitPx
        val mcx = (crx + clx) * 0.5f
        val mcy = (cry + cly) * 0.5f
        mouthDropRel = ((mcx - nbx) * downX + (mcy - nby) * downY) / cosPitch / unitPx

        // Midline from the nose bridge (168) to the nose base (2); corners' perpendicular distances.
        val bx = a[I.NOSE_BRIDGE * 3] * w
        val by = a[I.NOSE_BRIDGE * 3 + 1] * h
        val mx = nbx - bx
        val my = nby - by
        val ml = sqrt(mx * mx + my * my)
        symmetry = if (ml > 1f) {
            val dR = abs((crx - bx) * my - (cry - by) * mx) / ml
            val dL = abs((clx - bx) * my - (cly - by) * mx) / ml
            if (dR + dL > 0f) 1f - 2f * abs(dR - dL) / (dR + dL) else 1f
        } else {
            1f
        }

        val zSides = (a[I.FACE_RIGHT * 3 + 2] + a[I.FACE_LEFT * 3 + 2]) * 0.5f
        val zLips = (o[I.UPPER_MID * 3 + 2] + o[I.LOWER_MID * 3 + 2]) * 0.5f
        protrusionRel = (zSides - zLips) * w / unitPx

        val ir = f.irises
        var d = 0f
        d += dist(ir[1 * 3] * w, ir[1 * 3 + 1] * h, ir[3 * 3] * w, ir[3 * 3 + 1] * h)
        d += dist(ir[2 * 3] * w, ir[2 * 3 + 1] * h, ir[4 * 3] * w, ir[4 * 3 + 1] * h)
        d += dist(ir[6 * 3] * w, ir[6 * 3 + 1] * h, ir[8 * 3] * w, ir[8 * 3 + 1] * h)
        d += dist(ir[7 * 3] * w, ir[7 * 3 + 1] * h, ir[9 * 3] * w, ir[9 * 3 + 1] * h)
        irisPx = d / 4f

        // The mouth region only: a close-up selfie may cut the forehead or the cheeks, which does not matter here.
        var inside = countInside(o)
        for (k in MOUTH_ANCHORS) {
            val x = a[k * 3]
            val y = a[k * 3 + 1]
            if (x >= 0f && x <= 1f && y >= 0f && y <= 1f) inside++
        }
        inFrame = inside.toFloat() / (I.RING + MOUTH_ANCHORS.size)
        valid = true
    }

    private fun countInside(arr: FloatArray): Int {
        var inside = 0
        for (k in 0 until arr.size / 3) {
            val x = arr[k * 3]
            val y = arr[k * 3 + 1]
            if (x >= 0f && x <= 1f && y >= 0f && y <= 1f) inside++
        }
        return inside
    }

    private fun dist(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0
        val dy = y1 - y0
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        /** cos(50°): beyond that the corrections would only amplify noise (the gate is at 20° anyway). */
        private val MOUTH_ANCHORS = intArrayOf(I.NOSE_TIP, I.NOSE_BASE, I.CHIN)

        private val MIN_COS = cos(Math.toRadians(50.0)).toFloat()

        internal fun clamp01(x: Float) = min(1f, max(0f, x))
    }
}
