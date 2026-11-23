package tech.fonia.facecamera

import tech.fonia.face.BlendshapeValues

/** The blendshape model's inputs and outputs (`face_blendshapes_graph.cc`). */
object Blendshapes {
    /** The 52 scores in model order, under ARKit's names. MediaPipe has `_neutral` where ARKit has `tongueOut`. */
    val NAMES: List<String> = listOf(
        "_neutral", "browDownLeft", "browDownRight", "browInnerUp", "browOuterUpLeft", "browOuterUpRight",
        "cheekPuff", "cheekSquintLeft", "cheekSquintRight", "eyeBlinkLeft", "eyeBlinkRight", "eyeLookDownLeft",
        "eyeLookDownRight", "eyeLookInLeft", "eyeLookInRight", "eyeLookOutLeft", "eyeLookOutRight", "eyeLookUpLeft",
        "eyeLookUpRight", "eyeSquintLeft", "eyeSquintRight", "eyeWideLeft", "eyeWideRight", "jawForward", "jawLeft",
        "jawOpen", "jawRight", "mouthClose", "mouthDimpleLeft", "mouthDimpleRight", "mouthFrownLeft",
        "mouthFrownRight", "mouthFunnel", "mouthLeft", "mouthLowerDownLeft", "mouthLowerDownRight", "mouthPressLeft",
        "mouthPressRight", "mouthPucker", "mouthRight", "mouthRollLower", "mouthRollUpper", "mouthShrugLower",
        "mouthShrugUpper", "mouthSmileLeft", "mouthSmileRight", "mouthStretchLeft", "mouthStretchRight",
        "mouthUpperUpLeft", "mouthUpperUpRight", "noseSneerLeft", "noseSneerRight",
    )

    const val COUNT = 52

    internal val EYE_BLINK_LEFT = NAMES.indexOf("eyeBlinkLeft")
    internal val EYE_BLINK_RIGHT = NAMES.indexOf("eyeBlinkRight")

    /** Where each of [BlendshapeValues.NAMES] sits in the 52 scores; -1 for `tongueOut`, which the model lacks. */
    internal val SEAM_INDICES: IntArray = IntArray(BlendshapeValues.NAMES.size) { NAMES.indexOf(BlendshapeValues.NAMES[it]) }

    /** The 146 landmarks the model reads (`kLandmarksSubsetIdxs`), as (x, y) in pixels. */
    internal val LANDMARK_SUBSET = intArrayOf(
        0, 1, 4, 5, 6, 7, 8, 10, 13, 14, 17, 21, 33, 37, 39,
        40, 46, 52, 53, 54, 55, 58, 61, 63, 65, 66, 67, 70, 78, 80,
        81, 82, 84, 87, 88, 91, 93, 95, 103, 105, 107, 109, 127, 132, 133,
        136, 144, 145, 146, 148, 149, 150, 152, 153, 154, 155, 157, 158, 159, 160,
        161, 162, 163, 168, 172, 173, 176, 178, 181, 185, 191, 195, 197, 234, 246,
        249, 251, 263, 267, 269, 270, 276, 282, 283, 284, 285, 288, 291, 293, 295,
        296, 297, 300, 308, 310, 311, 312, 314, 317, 318, 321, 323, 324, 332, 334,
        336, 338, 356, 361, 362, 365, 373, 374, 375, 377, 378, 379, 380, 381, 382,
        384, 385, 386, 387, 388, 389, 390, 397, 398, 400, 402, 405, 409, 415, 454,
        466, 468, 469, 470, 471, 472, 473, 474, 475, 476, 477,
    )
}

/**
 * The mapping layer between the model's scores and ARKit's scale, one curve per coefficient the seam carries.
 *
 * The mouth coach's thresholds (`smile > 0.42`, `jawOpen > 0.22`, …) were tuned by eye on ARKit. MediaPipe's
 * blendshape model was trained to imitate ARKit, but the same face need not give the same number. Each curve is
 * piecewise linear through `(model score, ARKit value)` knots, increasing, clamped at both ends; [IDENTITY] passes the
 * scores through. The curves are meant to be fitted from the recordings of FaceRecording: the same faces making the
 * same poses on an iPhone and on Android, matched quantile by quantile (PARITY.md).
 */
class BlendshapeCalibration(curves: Map<String, List<Pair<Float, Float>>> = emptyMap()) {
    private val knotsX: Array<FloatArray>
    private val knotsY: Array<FloatArray>

    init {
        for (name in curves.keys) require(name in BlendshapeValues.NAMES) { "$name is not a coefficient the coach reads" }
        val names = BlendshapeValues.NAMES
        knotsX = Array(names.size) { i -> curves[names[i]]?.map { it.first }?.toFloatArray() ?: floatArrayOf(0f, 1f) }
        knotsY = Array(names.size) { i -> curves[names[i]]?.map { it.second }?.toFloatArray() ?: floatArrayOf(0f, 1f) }
        for (i in names.indices) {
            val xs = knotsX[i]
            require(xs.size >= 2 && xs.size == knotsY[i].size) { "${names[i]}: at least two knots" }
            for (k in 1 until xs.size) require(xs[k] > xs[k - 1] && knotsY[i][k] >= knotsY[i][k - 1]) { "${names[i]}: knots must increase" }
        }
    }

    /** Coefficient [index] of [BlendshapeValues.NAMES] for a model [score]. */
    fun map(index: Int, score: Float): Float {
        val xs = knotsX[index]
        val ys = knotsY[index]
        if (score <= xs[0]) return ys[0]
        val last = xs.size - 1
        if (score >= xs[last]) return ys[last]
        var k = 1
        while (score > xs[k]) k++
        val t = (score - xs[k - 1]) / (xs[k] - xs[k - 1])
        return ys[k - 1] + t * (ys[k] - ys[k - 1])
    }

    /** The seven coefficients for 52 model [scores] (model order); `tongueOut` is 0, the model has none. */
    fun values(scores: FloatArray, out: FloatArray) {
        for (i in Blendshapes.SEAM_INDICES.indices) {
            val source = Blendshapes.SEAM_INDICES[i]
            out[i] = if (source < 0) 0f else map(i, scores[source])
        }
    }

    val isIdentity: Boolean
        get() = knotsX.indices.all { i -> knotsX[i].contentEquals(floatArrayOf(0f, 1f)) && knotsY[i].contentEquals(floatArrayOf(0f, 1f)) }

    companion object {
        /** Scores as they are, until recordings of the same faces on both platforms give the curves. */
        val IDENTITY = BlendshapeCalibration()
    }
}
