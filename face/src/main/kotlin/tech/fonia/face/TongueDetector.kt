package tech.fonia.face

import tech.fonia.face.FaceMeshIndices as I
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The heuristic tongue-out detector of docs/v2/04 §5.1 (H4), for the gymnastics warm-ups only: the camera never sees
 * the tongue while the child speaks.
 *
 * - **Colour model at rest.** On a calm frame of the rest, lip pixels (between the rings) and skin pixels (bands
 *   above the upper lip and below the lower lip) give two Gaussians in normalised chromaticity (r, g), plus the skin's
 *   luma and saturation. A pixel is red tissue when it is closer (Mahalanobis) to the lip colour than to the skin
 *   and within [TongueSpec.maxLipDistance] of the lip; teeth are bright and pale; the inside of the mouth is dark.
 *   If lips and skin do not separate at rest (d′ < [TongueSpec.minSeparation]) the detector says Unknown for the
 *   session (similar lip and skin colours, lipstick, odd light).
 * - **Zones** in the mouth frame (origin between the corners, u to the person's left, v up, unit W₀). The share of
 *   red tissue in each zone is compared with its share at rest. Only red tissue connected to the mouth opening
 *   counts (a coarse 24 × 24 grid flood-filled from the opening), so a hand or a sweet in front of the mouth does
 *   not.
 * - **Rule.** A zone holds the tongue when its share gained ≥ [TongueSpec.minDeltaShare], the connected red blob is
 *   ≥ [TongueSpec.minAreaW0]·W₀² and the mouth is open (≥ [TongueSpec.minApertureMm] or a jaw drop). Then a 5-frame
 *   majority vote and on/off holds.
 * - **Model score.** The landmark model has its own tongue-out output, which MediaPipe drops. It is added as
 *   evidence for [TongueState.Out] when [TongueSpec.useModelScore]; its behaviour on real video is in RAPORT_CAMERA.md.
 *
 * Allocation-free per frame; the crop is read, never kept.
 */
class TongueDetector(private val spec: TongueSpec = TongueSpec()) {
    // Colour model.
    var calibrated = false
        private set
    var separation = 0f
        private set
    private var lipR = 0f
    private var lipG = 0f
    private val lipInv = FloatArray(3) // a, b, c of the inverse covariance [a b; b c]
    private var skinR = 0f
    private var skinG = 0f
    private val skinInv = FloatArray(3)
    private var skinY = 0f
    private var skinSat = 0f
    private val restShare = FloatArray(ZONES)

    // Scratch.
    private val cellRed = IntArray(G * G)
    private val cellIn = BooleanArray(G * G)
    private val queue = IntArray(G * G)
    private val polyX = FloatArray(I.RING)
    private val polyY = FloatArray(I.RING)
    private val outerX = FloatArray(I.RING)
    private val outerY = FloatArray(I.RING)
    private val zoneRed = IntArray(ZONES)
    private val zoneAll = IntArray(ZONES)
    private val pixClass = ByteArray(N * N)
    private val pixZone = ByteArray(N * N)

    /** Last frame's red-share gain per zone (Out, Down, Up, Left, Right). */
    val delta = FloatArray(ZONES)
    var blobAreaW0 = 0f
        private set

    private val votes = IntArray(16)
    private var voteHead = 0
    private var voteCount = 0
    private var state = TongueState.Unknown
    private var pending = TongueState.None
    private var pendingSince = -1L

    /** Raw per-frame class before voting. */
    var rawState = TongueState.Unknown
        private set

    fun reset() {
        calibrated = false
        separation = 0f
        voteCount = 0
        state = TongueState.Unknown
        pendingSince = -1L
    }

    /**
     * Learns the lip and skin colours from a calm rest frame [f] (with its crop). Returns false (and the detector
     * stays Unknown) when the colours do not separate.
     */
    fun calibrate(f: FaceMeshFrame, m: MouthMeasures, rest: RestBaseline): Boolean {
        calibrated = false
        if (!f.hasCrop || !m.valid) return false
        val w0 = rest.widthRel * m.eyePx
        if (!prepare(f, w0)) return false
        // Ring extents in v, to place the skin bands just outside the lips.
        var top = -1f
        var bottom = 1f
        for (k in 0 until I.RING) {
            val (u, v) = toUv(outerX[k], outerY[k], f, w0)
            if (v > top) top = v
            if (v < bottom) bottom = v
            if (u.isNaN()) return false
        }
        // Pass 1: skin luma.
        var ySum = 0.0
        var yN = 0
        forEachPixel(f, w0) { idx, u, v, inOuter, inInner ->
            if (!inOuter && isSkinBand(u, v, top, bottom)) {
                ySum += luma(f.mouthCrop, idx)
                yN++
            }
        }
        if (yN < 20) return false
        val yMean = (ySum / yN).toFloat()
        // Pass 2: lip and skin chromaticity statistics, ignoring dark and burnt pixels.
        val lip = DoubleArray(6)
        val skin = DoubleArray(6)
        var satSum = 0.0
        forEachPixel(f, w0) { idx, u, v, inOuter, inInner ->
            val y = luma(f.mouthCrop, idx)
            if (y < spec.darkLuma * yMean || y > 0.98f) return@forEachPixel
            val target = when {
                inOuter && !inInner -> lip
                !inOuter && isSkinBand(u, v, top, bottom) -> skin
                else -> null
            } ?: return@forEachPixel
            val c = f.mouthCrop
            val s = c[idx] + c[idx + 1] + c[idx + 2]
            if (s <= 1e-4f) return@forEachPixel
            val r = (c[idx] / s).toDouble()
            val g = (c[idx + 1] / s).toDouble()
            target[0] += 1.0; target[1] += r; target[2] += g; target[3] += r * r; target[4] += r * g; target[5] += g * g
            if (target === skin) satSum += saturation(c, idx)
        }
        if (lip[0] < 15 || skin[0] < 20) return false
        lipR = (lip[1] / lip[0]).toFloat(); lipG = (lip[2] / lip[0]).toFloat()
        skinR = (skin[1] / skin[0]).toFloat(); skinG = (skin[2] / skin[0]).toFloat()
        val lc = covariance(lip, lipR, lipG)
        val sc = covariance(skin, skinR, skinG)
        invert(lc, lipInv)
        invert(sc, skinInv)
        skinY = yMean
        skinSat = (satSum / skin[0]).toFloat()
        // d′ along the line between the means.
        val dr = lipR - skinR
        val dg = lipG - skinG
        val d2 = dr * dr + dg * dg
        if (d2 <= 0f) return false
        val varL = (dr * dr * lc[0] + 2 * dr * dg * lc[1] + dg * dg * lc[2]) / d2
        val varS = (dr * dr * sc[0] + 2 * dr * dg * sc[1] + dg * dg * sc[2]) / d2
        separation = sqrt(d2) / sqrt(max(1e-9f, 0.5f * (varL + varS)))
        if (separation < spec.minSeparation) return false
        calibrated = true
        // Rest shares, measured the same way as every frame.
        analyse(f, m, rest)
        for (z in 0 until ZONES) restShare[z] = share(z)
        delta.fill(0f)
        voteCount = 0
        state = TongueState.None
        return true
    }

    /**
     * One frame; [apertureMm] and [jaw] (÷ W₀) come from the smoothed mouth state, [bandRatio] is the lower lip band
     * ÷ its rest thickness. Returns the voted state.
     *
     * Out = the model's own score ≥ [TongueSpec.modelScoreOn] (works for a big tongue, even in odd colours), or a
     * lower lip band ≥ [TongueSpec.bandRatioOut] × rest confirmed by red tissue gained in the "out" zone (a tongue
     * pushed between the lips, where the model stays near 0). Colour alone never says Out: on real speech and smiles
     * the lower lip itself moved into the zone. Up/Down/Left/Right are colour-only (unvalidated).
     */
    fun update(
        f: FaceMeshFrame, m: MouthMeasures, rest: RestBaseline, apertureMm: Float, jaw: Float, good: Boolean, bandRatio: Float,
    ): TongueState {
        val modelOut = spec.useModelScore && f.isPresent && f.tongueModelScore >= spec.modelScoreOn
        if (!good || !m.valid) {
            rawState = TongueState.Unknown
            delta.fill(0f)
            return vote(TongueState.Unknown, f.tNanos)
        }
        val bandKnown = rest.isCalibrated
        if (!calibrated || !f.hasCrop) {
            // No colour model (lips and skin too alike, d′ < minSeparation): the model, or a much thicker band alone.
            delta.fill(0f)
            val bandAlone = bandKnown && bandRatio >= spec.bandOnlyRatio
            rawState = if (modelOut || bandAlone) TongueState.Out else TongueState.Unknown
            return vote(rawState, f.tNanos)
        }
        analyse(f, m, rest)
        var best = -1
        var bestDelta = 0f
        for (z in 0 until ZONES) {
            delta[z] = share(z) - restShare[z]
            if (delta[z] > bestDelta) {
                bestDelta = delta[z]
                best = z
            }
        }
        val open = apertureMm >= spec.minApertureMm || jaw >= 0.10f
        val bandOut = bandKnown && bandRatio >= spec.bandRatioOut && delta[Z_OUT] >= spec.colourConfirm
        var raw = TongueState.None
        if (modelOut || bandOut) {
            raw = TongueState.Out
        } else if (spec.colourOnlyZones && open && best >= 0 && bestDelta >= spec.minDeltaShare && blobAreaW0 >= spec.minAreaW0) {
            raw = when (best) {
                Z_DOWN -> if (delta[Z_OUT] >= spec.minDeltaShare * 0.6f) TongueState.Down else TongueState.None
                Z_UP -> TongueState.Up
                Z_LEFT -> TongueState.Left
                Z_RIGHT -> TongueState.Right
                else -> TongueState.None
            }
        }
        rawState = raw
        return vote(raw, f.tNanos)
    }

    /** Confidence of the last state: the colour separation and the margin over the threshold, 0…1. */
    val confidence: Float
        get() = if (!calibrated) 0f else MouthMeasures.clamp01((separation - spec.minSeparation) / 2f) * 0.8f

    private fun vote(raw: TongueState, t: Long): TongueState {
        votes[voteHead] = raw.ordinal
        voteHead = (voteHead + 1) % spec.voteFrames
        if (voteCount < spec.voteFrames) voteCount++
        // Majority class over the window.
        var bestClass = TongueState.None.ordinal
        var bestN = 0
        for (c in TongueState.entries.indices) {
            var n = 0
            for (k in 0 until voteCount) if (votes[k] == c) n++
            if (n > bestN) {
                bestN = n
                bestClass = c
            }
        }
        val majority = TongueState.entries[bestClass]
        if (majority == state) {
            pendingSince = -1L
            return state
        }
        if (majority != pending || pendingSince < 0) {
            pending = majority
            pendingSince = t
        }
        val hold = msToNanos(if (majority == TongueState.None || majority == TongueState.Unknown) spec.offHoldMs else spec.onHoldMs)
        if (t - pendingSince >= hold) {
            state = majority
            pendingSince = -1L
        }
        return state
    }

    private fun share(z: Int): Float = if (zoneAll[z] > 0) zoneRed[z].toFloat() / zoneAll[z] else 0f

    // ---- geometry ----

    // Crop → image: p = p1 + i·s·ex + j·s·ey. Image → mouth frame: u = (p − o)·ux / W₀, v = (p − o)·up / W₀.
    private var p1x = 0f
    private var p1y = 0f
    private var exX = 1f
    private var exY = 0f
    private var step = 1f
    private var ox = 0f
    private var oy = 0f
    private var uxX = 1f
    private var uxY = 0f

    /** Sets the transforms for [f] and puts the rings into crop coordinates. */
    private fun prepare(f: FaceMeshFrame, w0: Float): Boolean {
        if (!(w0 > 1f) || !(f.cropSide > 1f)) return false
        val w = f.imageW.toFloat()
        val h = f.imageH.toFloat()
        exX = cos(f.cropAngle)
        exY = sin(f.cropAngle)
        step = f.cropSide / N
        val half = f.cropSide / 2f
        p1x = f.cropCenterX - half * exX + half * exY
        p1y = f.cropCenterY - half * exY - half * exX
        val o = f.lipsOuter
        val crx = o[I.CORNER_RIGHT * 3] * w
        val cry = o[I.CORNER_RIGHT * 3 + 1] * h
        val clx = o[I.CORNER_LEFT * 3] * w
        val cly = o[I.CORNER_LEFT * 3 + 1] * h
        ox = (crx + clx) * 0.5f
        oy = (cry + cly) * 0.5f
        var dx = clx - crx
        var dy = cly - cry
        val len = sqrt(dx * dx + dy * dy)
        if (!(len > 0f)) return false
        dx /= len
        dy /= len
        uxX = dx
        uxY = dy
        for (k in 0 until I.RING) {
            toCrop(f.lipsInner[k * 3] * w, f.lipsInner[k * 3 + 1] * h)
            polyX[k] = tmpX; polyY[k] = tmpY
            toCrop(o[k * 3] * w, o[k * 3 + 1] * h)
            outerX[k] = tmpX; outerY[k] = tmpY
        }
        return true
    }

    private var tmpX = 0f
    private var tmpY = 0f

    private fun toCrop(px: Float, py: Float) {
        val dx = px - p1x
        val dy = py - p1y
        // ey = (−exY, exX)
        tmpX = (dx * exX + dy * exY) / step
        tmpY = (-dx * exY + dy * exX) / step
    }

    private fun toUv(cx: Float, cy: Float, @Suppress("UNUSED_PARAMETER") f: FaceMeshFrame, w0: Float): Pair<Float, Float> {
        val px = p1x + cx * step * exX - cy * step * exY
        val py = p1y + cx * step * exY + cy * step * exX
        val dx = px - ox
        val dy = py - oy
        return Pair((dx * uxX + dy * uxY) / w0, (dx * uxY - dy * uxX) / w0)
    }

    private inline fun forEachPixel(f: FaceMeshFrame, w0: Float, block: (idx: Int, u: Float, v: Float, inOuter: Boolean, inInner: Boolean) -> Unit) {
        // u, v are affine in (i, j).
        val su = step / w0
        val baseX = p1x - ox
        val baseY = p1y - oy
        val u0 = (baseX * uxX + baseY * uxY) / w0
        val v0 = (baseX * uxY - baseY * uxX) / w0
        val duI = (exX * uxX + exY * uxY) * su
        val dvI = (exX * uxY - exY * uxX) * su
        val duJ = (-exY * uxX + exX * uxY) * su
        val dvJ = (-exY * uxY - exX * uxX) * su
        for (j in 0 until N) {
            for (i in 0 until N) {
                val u = u0 + duI * i + duJ * j
                val v = v0 + dvI * i + dvJ * j
                val cx = i.toFloat()
                val cy = j.toFloat()
                block((j * N + i) * 3, u, v, inside(outerX, outerY, cx, cy), inside(polyX, polyY, cx, cy))
            }
        }
    }

    /** Classifies every crop pixel, finds the red blob connected to the mouth opening and counts it per zone. */
    private fun analyse(f: FaceMeshFrame, m: MouthMeasures, rest: RestBaseline) {
        val w0 = rest.widthRel * m.eyePx
        zoneRed.fill(0)
        zoneAll.fill(0)
        blobAreaW0 = 0f
        cellRed.fill(0)
        if (!prepare(f, w0)) return
        val su = step / w0
        val baseX = p1x - ox
        val baseY = p1y - oy
        val u0 = (baseX * uxX + baseY * uxY) / w0
        val v0 = (baseX * uxY - baseY * uxX) / w0
        val duI = (exX * uxX + exY * uxY) * su
        val dvI = (exX * uxY - exY * uxX) * su
        val duJ = (-exY * uxX + exX * uxY) * su
        val dvJ = (-exY * uxY - exX * uxX) * su
        val c = f.mouthCrop
        for (j in 0 until N) {
            for (i in 0 until N) {
                val p = j * N + i
                val idx = p * 3
                val red = isRed(c, idx)
                pixClass[p] = if (red) 1 else 0
                val u = u0 + duI * i + duJ * j
                val v = v0 + dvI * i + dvJ * j
                val z = zoneOf(u, v)
                pixZone[p] = z.toByte()
                if (red) cellRed[(j / CELL) * G + (i / CELL)]++
            }
        }
        // Seeds: cells inside the opening polygon, and the cell of its centroid.
        cellIn.fill(false)
        var head = 0
        var tail = 0
        var cxSum = 0f
        var cySum = 0f
        for (k in 0 until I.RING) {
            cxSum += polyX[k]
            cySum += polyY[k]
        }
        val centroidCell = cellOf(cxSum / I.RING, cySum / I.RING)
        for (cell in 0 until G * G) {
            val ccx = (cell % G) * CELL + CELL / 2f
            val ccy = (cell / G) * CELL + CELL / 2f
            if (cell == centroidCell || inside(polyX, polyY, ccx, ccy)) {
                cellIn[cell] = true
                queue[tail++] = cell
            }
        }
        val redCell = CELL * CELL / 2
        while (head < tail) {
            val cell = queue[head++]
            val gx = cell % G
            val gy = cell / G
            for (d in 0 until 4) {
                val nx = gx + DX[d]
                val ny = gy + DY[d]
                if (nx < 0 || ny < 0 || nx >= G || ny >= G) continue
                val nCell = ny * G + nx
                if (!cellIn[nCell] && cellRed[nCell] >= redCell) {
                    cellIn[nCell] = true
                    queue[tail++] = nCell
                }
            }
        }
        var blob = 0
        for (p in 0 until N * N) {
            val z = pixZone[p].toInt()
            val i = p % N
            val j = p / N
            val connected = cellIn[(j / CELL) * G + (i / CELL)]
            val red = pixClass[p].toInt() == 1 && connected
            if (red) blob++
            if (z >= 0) {
                zoneAll[z]++
                if (red) zoneRed[z]++
            }
        }
        blobAreaW0 = blob * su * su
    }

    private fun cellOf(x: Float, y: Float): Int {
        val gx = (x / CELL).toInt().coerceIn(0, G - 1)
        val gy = (y / CELL).toInt().coerceIn(0, G - 1)
        return gy * G + gx
    }

    private fun isRed(c: FloatArray, idx: Int): Boolean {
        val y = luma(c, idx)
        if (y < spec.darkLuma * skinY) return false // inside of the mouth, shadow
        if (y > spec.teethLuma * skinY && saturation(c, idx) < spec.teethSaturation * skinSat) return false // teeth
        val s = c[idx] + c[idx + 1] + c[idx + 2]
        if (s <= 1e-4f) return false
        val r = c[idx] / s
        val g = c[idx + 1] / s
        val dl = mahalanobis(r - lipR, g - lipG, lipInv)
        val ds = mahalanobis(r - skinR, g - skinG, skinInv)
        return dl < ds && dl < spec.maxLipDistance * spec.maxLipDistance
    }

    private fun isSkinBand(u: Float, v: Float, top: Float, bottom: Float): Boolean =
        (kotlin.math.abs(u) <= 0.35f && v >= top + 0.04f && v <= top + 0.16f) ||
            (kotlin.math.abs(u) <= 0.40f && v <= bottom - 0.10f && v >= bottom - 0.35f)

    companion object {
        const val N = FaceMeshFrame.CROP
        private const val CELL = 4
        private const val G = N / CELL
        const val ZONES = 5
        const val Z_OUT = 0
        const val Z_DOWN = 1
        const val Z_UP = 2
        const val Z_LEFT = 3
        const val Z_RIGHT = 4
        private val DX = intArrayOf(1, -1, 0, 0)
        private val DY = intArrayOf(0, 0, 1, -1)

        /** The zones of docs/v2/04 §5.1 (u to the person's left, v up, unit W₀); −1 outside all. */
        fun zoneOf(u: Float, v: Float): Int {
            val au = kotlin.math.abs(u)
            return when {
                au <= 0.25f && v >= -0.45f && v <= -0.05f -> Z_OUT
                au <= 0.30f && v >= -0.90f && v < -0.45f -> Z_DOWN
                au <= 0.25f && v >= 0.20f && v <= 0.55f -> Z_UP
                u >= 0.50f && u <= 0.85f && kotlin.math.abs(v) <= 0.20f -> Z_LEFT
                u <= -0.50f && u >= -0.85f && kotlin.math.abs(v) <= 0.20f -> Z_RIGHT
                else -> -1
            }
        }

        fun luma(c: FloatArray, idx: Int): Float = 0.299f * c[idx] + 0.587f * c[idx + 1] + 0.114f * c[idx + 2]

        fun saturation(c: FloatArray, idx: Int): Float {
            val mx = max(c[idx], max(c[idx + 1], c[idx + 2]))
            val mn = min(c[idx], min(c[idx + 1], c[idx + 2]))
            return if (mx > 1e-4f) (mx - mn) / mx else 0f
        }

        private fun mahalanobis(dr: Float, dg: Float, inv: FloatArray): Float = dr * dr * inv[0] + 2 * dr * dg * inv[1] + dg * dg * inv[2]

        private fun covariance(s: DoubleArray, mr: Float, mg: Float): FloatArray {
            val n = s[0]
            return floatArrayOf(
                (s[3] / n - mr * mr).toFloat() + 1e-5f,
                (s[4] / n - mr * mg).toFloat(),
                (s[5] / n - mg * mg).toFloat() + 1e-5f,
            )
        }

        private fun invert(cv: FloatArray, out: FloatArray) {
            val det = cv[0] * cv[2] - cv[1] * cv[1]
            val d = if (det > 1e-12f) det else 1e-12f
            out[0] = cv[2] / d
            out[1] = -cv[1] / d
            out[2] = cv[0] / d
        }

        /** Even–odd point-in-polygon. */
        fun inside(xs: FloatArray, ys: FloatArray, x: Float, y: Float): Boolean {
            var c = false
            var j = xs.size - 1
            for (i in xs.indices) {
                if ((ys[i] > y) != (ys[j] > y) && x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]) c = !c
                j = i
            }
            return c
        }
    }
}
