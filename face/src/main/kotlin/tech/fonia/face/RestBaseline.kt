package tech.fonia.face

import tech.fonia.face.FaceMeshIndices as I
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * This child's mouth at rest (docs/v2/04 §3, "Repausul"): every threshold is relative to it, because no tracker has
 * been validated on children's faces. Lives in RAM only and dies with the screen; nothing of it is saved.
 *
 * Lengths are ÷ E (eye distance) as [MouthMeasures] gives them; the rings are in the mouth frame: origin in the
 * middle of the corners, u along the corners (person's right → left), v up, unit W₀ (the rest width).
 */
class RestBaseline(
    val source: RestSource,
    /** W₀ ÷ E. */
    val widthRel: Float,
    /** Rest aperture ÷ W₀ (≈ 0 with closed lips). */
    val aperture: Float,
    val jawRel: Float,
    val outerHeightRel: Float,
    /** Mouth centre relative to the nose base, ÷ E, along the face's down and right axes. */
    val mouthDropRel: Float,
    val mouthSideRel: Float,
    val protrusionRel: Float,
    val innerAreaRel: Float,
    val pucker: Float,
    val funnel: Float,
    val smile: Float,
    val jawOpenScore: Float,
    /** Lower and upper lip band thickness ÷ E at rest. */
    val lowerBandRel: Float,
    val upperBandRel: Float,
    /** 20 × (u, v) each. */
    val ringOuter: FloatArray,
    val ringInner: FloatArray,
    val frames: Int,
    val widthCv: Float,
) {
    val isCalibrated: Boolean get() = source != RestSource.Default

    companion object {
        /** Population fall-backs and a generic closed-lips shape, until a rest is taken. */
        fun default(spec: RestSpec = RestSpec()): RestBaseline {
            val outer = FloatArray(I.RING * 2)
            val inner = FloatArray(I.RING * 2)
            // Corner (k = 0) over the upper lip (k = 1…9) to the other corner (k = 10) and under the lower lip.
            for (k in 0 until I.RING) {
                val t = PI * k / 10.0 // 0 … π upper, π … 2π lower
                val u = (-0.5 * cos(t)).toFloat()
                val upper = k <= 10
                val bow = if (upper) 1.0 - 0.25 * exp2(-(u * 9.0) * (u * 9.0)) else 1.0
                val height = if (upper) 0.17 * bow else 0.20
                val v = (sin(t) * height).toFloat() // sin > 0 on the upper half → up
                outer[k * 2] = u
                outer[k * 2 + 1] = v
                inner[k * 2] = u * 0.86f
                inner[k * 2 + 1] = if (k == 0 || k == 10) 0f else (if (upper) 0.004f else -0.004f)
            }
            return RestBaseline(
                RestSource.Default, spec.defaultWidthRel, 0f, spec.defaultJawRel, 0.30f, spec.defaultMouthDrop, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0.15f, 0.12f, outer, inner, 0, 0f,
            )
        }

        private fun exp2(x: Double) = kotlin.math.exp(x)

        /**
         * The rings of [f] in the mouth frame (u, v; unit = current corner distance). [outOuter]/[outInner]: 40 floats.
         */
        fun mouthFrameRings(f: FaceMeshFrame, outOuter: FloatArray, outInner: FloatArray) {
            val w = f.imageW.toFloat()
            val h = f.imageH.toFloat()
            val o = f.lipsOuter
            val crx = o[I.CORNER_RIGHT * 3] * w
            val cry = o[I.CORNER_RIGHT * 3 + 1] * h
            val clx = o[I.CORNER_LEFT * 3] * w
            val cly = o[I.CORNER_LEFT * 3 + 1] * h
            val ox = (crx + clx) * 0.5f
            val oy = (cry + cly) * 0.5f
            var ux = clx - crx
            var uy = cly - cry
            val len = sqrt(ux * ux + uy * uy)
            if (!(len > 0f)) return
            ux /= len
            uy /= len
            val upX = uy
            val upY = -ux
            for (k in 0 until I.RING) {
                for (pass in 0..1) {
                    val src = if (pass == 0) o else f.lipsInner
                    val dst = if (pass == 0) outOuter else outInner
                    val px = src[k * 3] * w - ox
                    val py = src[k * 3 + 1] * h - oy
                    dst[k * 2] = (px * ux + py * uy) / len
                    dst[k * 2 + 1] = (px * upX + py * upY) / len
                }
            }
        }
    }
}

/**
 * Takes the rest (docs/v2/04 §3): the median of ≥ [RestSpec.minFrames] gate-passing frames with the lips (nearly)
 * closed and the width steady (CV < 3 %). If that does not happen within [RestSpec.autoAfterMs], the rest is taken
 * from the [RestSpec.autoFrames] most closed frames in which the microphone heard no speech. Buffers are made once.
 */
class RestCalibrator(private val spec: RestSpec = RestSpec()) {
    private val capacity = 180
    private val stride = FEATURES + I.RING * 4
    private val buffer = FloatArray(capacity * stride)
    private val restLike = BooleanArray(capacity)
    private var count = 0
    private var head = 0
    private var startNanos = -1L
    private var consecutive = 0
    private val scratch = FloatArray(capacity)
    private val order = IntArray(capacity)
    private val ringO = FloatArray(I.RING * 2)
    private val ringI = FloatArray(I.RING * 2)

    /** The most closed calibration frame with a crop: the colour model of the tongue heuristic is taken from it. */
    val quietFrame = FaceMeshFrame()
    var hasQuietFrame = false
        private set
    private var quietAperture = Float.MAX_VALUE

    var result: RestBaseline? = null
        private set

    /** 0…1 for a progress ring. */
    val progress: Float get() = if (result != null) 1f else minOf(1f, consecutive.toFloat() / spec.minFrames)

    fun reset() {
        count = 0
        head = 0
        startNanos = -1L
        consecutive = 0
        result = null
        hasQuietFrame = false
        quietAperture = Float.MAX_VALUE
    }

    /**
     * One frame. [good]: the quality gate passed; [speaking]: the microphone hears speech (pass false without one).
     * Returns the baseline once it is taken (and from then on).
     */
    fun add(f: FaceMeshFrame, m: MouthMeasures, good: Boolean, speaking: Boolean): RestBaseline? {
        result?.let { return it }
        if (startNanos < 0) startNanos = f.tNanos
        if (good && m.valid && !speaking) {
            val apertureW0 = m.apertureRel / m.widthRel
            val isRest = apertureW0 <= spec.maxApertureW0
            val slot = head
            val b = slot * stride
            buffer[b] = m.widthRel
            buffer[b + 1] = apertureW0
            buffer[b + 2] = m.jawRel
            buffer[b + 3] = m.outerHeightRel
            buffer[b + 4] = m.mouthDropRel
            buffer[b + 5] = sideRel(f, m)
            buffer[b + 6] = m.protrusionRel
            buffer[b + 7] = m.innerAreaRel
            buffer[b + 8] = f.blendshapes[MeshBlendshapes.MOUTH_PUCKER]
            buffer[b + 9] = f.blendshapes[MeshBlendshapes.MOUTH_FUNNEL]
            buffer[b + 10] = (f.blendshapes[MeshBlendshapes.MOUTH_SMILE_LEFT] + f.blendshapes[MeshBlendshapes.MOUTH_SMILE_RIGHT]) * 0.5f
            buffer[b + 11] = f.blendshapes[MeshBlendshapes.JAW_OPEN]
            buffer[b + 12] = m.lowerBandRel
            buffer[b + 13] = m.upperBandRel
            RestBaseline.mouthFrameRings(f, ringO, ringI)
            ringO.copyInto(buffer, b + FEATURES)
            ringI.copyInto(buffer, b + FEATURES + I.RING * 2)
            restLike[slot] = isRest
            head = (head + 1) % capacity
            if (count < capacity) count++
            if (f.hasCrop && apertureW0 < quietAperture) {
                quietAperture = apertureW0
                quietFrame.copyFrom(f)
                hasQuietFrame = true
            }
            consecutive = if (isRest) consecutive + 1 else 0
            if (consecutive >= spec.minFrames) {
                // The last minFrames slots are all rest-like.
                val n = spec.minFrames
                for (k in 0 until n) order[k] = (head - 1 - k + capacity * 2) % capacity
                val cv = widthCv(n)
                if (cv < spec.maxWidthCv) {
                    result = build(RestSource.Calibrated, n, cv)
                    return result
                }
            }
        }
        if (f.tNanos - startNanos >= msToNanos(spec.autoAfterMs) && count >= spec.autoFrames) {
            // The most closed frames, whatever the steadiness.
            for (k in 0 until count) {
                order[k] = (head - 1 - k + capacity * 2) % capacity
                scratch[k] = buffer[order[k] * stride + 1]
            }
            // Selection sort of the first autoFrames (small n, no allocation).
            for (i in 0 until spec.autoFrames) {
                var best = i
                for (j in i + 1 until count) if (scratch[j] < scratch[best]) best = j
                val ts = scratch[i]; scratch[i] = scratch[best]; scratch[best] = ts
                val to = order[i]; order[i] = order[best]; order[best] = to
            }
            val n = spec.autoFrames
            result = build(RestSource.Auto, n, widthCv(n))
        }
        return result
    }

    private fun sideRel(f: FaceMeshFrame, m: MouthMeasures): Float {
        val w = f.imageW.toFloat()
        val h = f.imageH.toFloat()
        val o = f.lipsOuter
        val a = f.anchors
        val mcx = (o[I.CORNER_RIGHT * 3] + o[I.CORNER_LEFT * 3]) * 0.5f * w
        val mcy = (o[I.CORNER_RIGHT * 3 + 1] + o[I.CORNER_LEFT * 3 + 1]) * 0.5f * h
        val dx = mcx - a[I.NOSE_BASE * 3] * w
        val dy = mcy - a[I.NOSE_BASE * 3 + 1] * h
        return (dx * m.axisX + dy * m.axisY) / m.eyePx
    }

    private fun widthCv(n: Int): Float {
        var s = 0f
        var s2 = 0f
        for (k in 0 until n) {
            val v = buffer[order[k] * stride]
            s += v
            s2 += v * v
        }
        val mean = s / n
        val variance = maxOf(0f, s2 / n - mean * mean)
        return if (mean > 0f) sqrt(variance) / mean else 1f
    }

    private fun median(n: Int, offset: Int): Float {
        for (k in 0 until n) scratch[k] = buffer[order[k] * stride + offset]
        java.util.Arrays.sort(scratch, 0, n)
        return if (n % 2 == 1) scratch[n / 2] else (scratch[n / 2 - 1] + scratch[n / 2]) * 0.5f
    }

    private fun build(source: RestSource, n: Int, cv: Float): RestBaseline {
        val outer = FloatArray(I.RING * 2) { median(n, FEATURES + it) }
        val inner = FloatArray(I.RING * 2) { median(n, FEATURES + I.RING * 2 + it) }
        return RestBaseline(
            source = source,
            widthRel = median(n, 0), aperture = median(n, 1), jawRel = median(n, 2), outerHeightRel = median(n, 3),
            mouthDropRel = median(n, 4), mouthSideRel = median(n, 5), protrusionRel = median(n, 6),
            innerAreaRel = median(n, 7), pucker = median(n, 8), funnel = median(n, 9), smile = median(n, 10),
            jawOpenScore = median(n, 11), lowerBandRel = median(n, 12), upperBandRel = median(n, 13), ringOuter = outer, ringInner = inner, frames = n, widthCv = cv,
        )
    }

    private companion object {
        const val FEATURES = 14
    }
}
