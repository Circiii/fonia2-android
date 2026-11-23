package tech.fonia.facecamera

import kotlin.math.abs

/**
 * MediaPipe's landmark smoothing in stream mode (`LandmarksSmoothingCalculator` with the One Euro filter of
 * FaceLandmarksDetectorGraph: min cutoff 0.05, beta 80, derivative cutoff 1). Each coordinate of each landmark is
 * filtered in pixels, the speed scaled by the face size. The blendshapes and the pose are computed from the smoothed
 * landmarks, as in MediaPipe; the next frame's region comes from the raw ones.
 *
 * Every coordinate's filter sees the same timestamps, so they share one clock. Float and double are used where the
 * C++ uses them (`OneEuroFilter` in double, `LowPassFilter` state in float).
 */
internal class LandmarkSmoother(private val count: Int) {
    private val size = count * 3
    private val xInitialized = BooleanArray(size)
    private val xRaw = FloatArray(size)
    private val xStored = FloatArray(size)
    private val dxInitialized = BooleanArray(size)
    private val dxStored = FloatArray(size)
    private var lastTimeNanos = UNINITIALIZED
    private var frequency = FREQUENCY

    /** Forgets the past: the next frame passes through unchanged (a lost face, a new run). */
    fun reset() {
        xInitialized.fill(false)
        dxInitialized.fill(false)
        lastTimeNanos = UNINITIALIZED
        frequency = FREQUENCY
    }

    /**
     * Smooths [landmarks] (x, y, z normalized to the [width]×[height] image) at [timestampMicros] into [out].
     */
    fun apply(landmarks: FloatArray, width: Int, height: Int, timestampMicros: Long, out: FloatArray) {
        // GetObjectScale on the landmarks in pixels.
        var xMin = Float.MAX_VALUE
        var xMax = -Float.MAX_VALUE
        var yMin = Float.MAX_VALUE
        var yMax = -Float.MAX_VALUE
        for (i in 0 until count) {
            val x = landmarks[i * 3] * width
            val y = landmarks[i * 3 + 1] * height
            if (x < xMin) xMin = x
            if (x > xMax) xMax = x
            if (y < yMin) yMin = y
            if (y > yMax) yMax = y
        }
        val objectScale = ((xMax - xMin) + (yMax - yMin)) / 2.0f
        if (objectScale < MIN_ALLOWED_OBJECT_SCALE) {
            landmarks.copyInto(out, 0, 0, size)
            return
        }
        val valueScale = (1.0f / objectScale).toDouble()

        // OneEuroFilter::Apply's clock, shared by all coordinates.
        val now = timestampMicros * 1000
        if (lastTimeNanos >= now) {
            landmarks.copyInto(out, 0, 0, size)
            return
        }
        if (lastTimeNanos != 0L && now != 0L) frequency = 1.0 / ((now - lastTimeNanos) * 1e-9)
        lastTimeNanos = now
        val derivativeAlpha = alpha(DERIVATIVE_CUTOFF).toFloat()

        for (j in 0 until size) {
            val axisScale = if (j % 3 == 1) height else width
            val value = (landmarks[j] * axisScale).toDouble()
            var derivative = 0.0
            if (xInitialized[j]) derivative = (value - xRaw[j]) * valueScale * frequency
            val smoothedDerivative = lowPass(dxInitialized, dxStored, null, j, derivative.toFloat(), derivativeAlpha)
            val cutoff = MIN_CUTOFF + BETA * abs(smoothedDerivative.toDouble())
            val filtered = lowPass(xInitialized, xStored, xRaw, j, value.toFloat(), alpha(cutoff).toFloat())
            out[j] = filtered / axisScale
        }
    }

    private fun alpha(cutoff: Double): Double {
        val te = 1.0 / frequency
        val tau = 1.0 / (2 * Math.PI * cutoff)
        return 1.0 / (1.0 + tau / te)
    }

    /** `LowPassFilter::ApplyWithAlpha`. */
    private fun lowPass(initialized: BooleanArray, stored: FloatArray, raw: FloatArray?, j: Int, value: Float, alpha: Float): Float {
        val result = if (initialized[j]) {
            ((alpha * value).toDouble() + (1.0 - alpha) * stored[j]).toFloat()
        } else {
            initialized[j] = true
            value
        }
        raw?.set(j, value)
        stored[j] = result
        return result
    }

    companion object {
        private const val UNINITIALIZED = -1L
        private const val FREQUENCY = 30.0
        private const val MIN_CUTOFF = 0.05
        private const val BETA = 80.0
        private const val DERIVATIVE_CUTOFF = 1.0
        private const val MIN_ALLOWED_OBJECT_SCALE = 1e-6f
    }
}
