package tech.fonia.facecamera

import java.nio.FloatBuffer
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A rectangle in normalized image coordinates (0...1 across the upright image), rotated by [rotation] radians:
 * MediaPipe's `NormalizedRect`.
 */
internal class NormRect {
    var centerX = 0f
    var centerY = 0f
    var width = 0f
    var height = 0f
    var rotation = 0f

    fun set(other: NormRect) {
        centerX = other.centerX
        centerY = other.centerY
        width = other.width
        height = other.height
        rotation = other.rotation
    }
}

/** MediaPipe's `NormalizeRadians`: into [-π, π). */
internal fun normalizeRadians(angle: Float): Float =
    (angle - 2 * Math.PI * floor((angle - (-Math.PI)) / (2 * Math.PI))).toFloat()

/**
 * `GetRotatedSubRectToRectTransformMatrix` (image_to_tensor_utils.cc): maps a point of the region, 0...1 on both
 * axes, to the image, 0...1. Row-major 4x4; only [0], [1], [3], [4], [5], [7] are used here.
 */
internal fun subRectToImage(
    centerX: Float,
    centerY: Float,
    width: Float,
    height: Float,
    rotation: Float,
    imageWidth: Int,
    imageHeight: Int,
    matrix: FloatArray,
) {
    val a = width
    val b = height
    val flip = 1f
    val c = kotlin.math.cos(rotation)
    val d = kotlin.math.sin(rotation)
    val e = centerX
    val f = centerY
    val g = 1.0f / imageWidth
    val h = 1.0f / imageHeight
    matrix[0] = a * c * flip * g
    matrix[1] = -b * d * g
    matrix[2] = 0f
    matrix[3] = (-0.5f * a * c * flip + 0.5f * b * d + e) * g
    matrix[4] = a * d * flip * h
    matrix[5] = b * c * h
    matrix[6] = 0f
    matrix[7] = (-0.5f * b * c - 0.5f * a * d * flip + f) * h
}

/**
 * The short-range BlazeFace detector's post-processing in MediaPipe's FaceDetectorGraph, for one face:
 * SSD anchors, box and keypoint decoding, sigmoid scores at 0.5, weighted non-maximum suppression (IoU 0.5) keeping
 * the best cluster, projection out of the letterboxed 128×128 input, then the face rectangle (box centre and size,
 * rotated so the eyes are level) enlarged 1.5 times.
 */
internal class FaceDetectionDecoder {
    private val anchorX = FloatArray(NUM_BOXES)
    private val anchorY = FloatArray(NUM_BOXES)
    private val scores = FloatArray(NUM_BOXES)
    private val boxes = FloatArray(NUM_BOXES * 4) // xmin, ymin, width, height
    private val keypoints = FloatArray(NUM_BOXES * NUM_KEYPOINTS * 2)
    private val order = IntArray(NUM_BOXES)
    private val projection = FloatArray(16)
    private val weightedKeypoints = FloatArray(NUM_KEYPOINTS * 2)

    /** Score of the detection behind the last rectangle. */
    var score = 0f
        private set

    init {
        // SsdAnchorsCalculator: 4 layers, strides 8, 16, 16, 16, aspect ratio 1 plus the interpolated scale, so
        // 2 anchors per cell on the 16×16 grid and 6 on the 8×8 one; fixed anchor size 1.
        val strides = intArrayOf(8, 16, 16, 16)
        var index = 0
        var layer = 0
        while (layer < strides.size) {
            var last = layer
            var perCell = 0
            while (last < strides.size && strides[last] == strides[layer]) {
                perCell += 2
                last++
            }
            val cells = ceil(1.0f * INPUT_SIZE / strides[layer]).toInt()
            for (y in 0 until cells) {
                for (x in 0 until cells) {
                    repeat(perCell) {
                        anchorX[index] = (x + 0.5f) * 1.0f / cells
                        anchorY[index] = (y + 0.5f) * 1.0f / cells
                        index++
                    }
                }
            }
            layer = last
        }
        check(index == NUM_BOXES)
    }

    internal fun anchor(i: Int): Pair<Float, Float> = anchorX[i] to anchorY[i]

    /**
     * Decodes the model outputs for a [imageWidth]×[imageHeight] upright image (letterboxed into the square input)
     * into [rect]. Returns false when no face scores 0.5.
     */
    fun decode(rawBoxes: FloatBuffer, rawScores: FloatBuffer, imageWidth: Int, imageHeight: Int, rect: NormRect): Boolean {
        var count = 0
        for (i in 0 until NUM_BOXES) {
            var s = rawScores.get(i)
            s = if (s < -SCORE_CLIPPING) -SCORE_CLIPPING else s
            s = if (s > SCORE_CLIPPING) SCORE_CLIPPING else s
            s = 1.0f / (1.0f + exp(-s))
            if (s < MIN_SCORE) continue
            val base = i * NUM_COORDS
            // reverse_output_order: x, y, w, h, then (x, y) per keypoint.
            val xCenter = rawBoxes.get(base) / INPUT_SIZE * 1f + anchorX[i]
            val yCenter = rawBoxes.get(base + 1) / INPUT_SIZE * 1f + anchorY[i]
            val w = rawBoxes.get(base + 2) / INPUT_SIZE * 1f
            val h = rawBoxes.get(base + 3) / INPUT_SIZE * 1f
            val ymin = yCenter - h / 2f
            val xmin = xCenter - w / 2f
            val ymax = yCenter + h / 2f
            val xmax = xCenter + w / 2f
            val width = xmax - xmin
            val height = ymax - ymin
            if (width < 0 || height < 0 || width.isNaN() || height.isNaN()) continue
            scores[count] = s
            boxes[count * 4] = xmin
            boxes[count * 4 + 1] = ymin
            boxes[count * 4 + 2] = width
            boxes[count * 4 + 3] = height
            for (k in 0 until NUM_KEYPOINTS) {
                val offset = base + KEYPOINT_OFFSET + k * 2
                keypoints[(count * NUM_KEYPOINTS + k) * 2] = rawBoxes.get(offset) / INPUT_SIZE * 1f + anchorX[i]
                keypoints[(count * NUM_KEYPOINTS + k) * 2 + 1] = rawBoxes.get(offset + 1) / INPUT_SIZE * 1f + anchorY[i]
            }
            order[count] = count
            count++
        }
        if (count == 0) return false

        // Highest score first (insertion sort: there are a few dozen candidates at most).
        for (i in 1 until count) {
            val item = order[i]
            var j = i - 1
            while (j >= 0 && scores[order[j]] < scores[item]) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = item
        }

        // WeightedNonMaxSuppression, first cluster: everything overlapping the best box by more than 0.5 IoU.
        val top = order[0]
        var totalScore = 0f
        var wxmin = 0f
        var wymin = 0f
        var wxmax = 0f
        var wymax = 0f
        weightedKeypoints.fill(0f)
        for (n in 0 until count) {
            val c = order[n]
            if (!(overlap(c, top) > MIN_SUPPRESSION)) continue
            val s = scores[c]
            totalScore += s
            wxmin += boxes[c * 4] * s
            wymin += boxes[c * 4 + 1] * s
            wxmax += (boxes[c * 4] + boxes[c * 4 + 2]) * s
            wymax += (boxes[c * 4 + 1] + boxes[c * 4 + 3]) * s
            for (k in 0 until NUM_KEYPOINTS * 2) weightedKeypoints[k] += keypoints[c * NUM_KEYPOINTS * 2 + k] * s
        }
        score = scores[top]
        val xmin = wxmin / totalScore
        val ymin = wymin / totalScore
        val width = (wxmax / totalScore) - xmin
        val height = (wymax / totalScore) - ymin
        for (k in 0 until NUM_KEYPOINTS * 2) weightedKeypoints[k] = weightedKeypoints[k] / totalScore

        // DetectionProjectionCalculator with the letterbox matrix of the padded, square region.
        val tensorAspect = INPUT_SIZE.toFloat() / INPUT_SIZE
        val roiAspect = imageHeight.toFloat() / imageWidth
        val roiWidth: Float
        val roiHeight: Float
        if (tensorAspect > roiAspect) {
            roiWidth = imageWidth.toFloat()
            roiHeight = imageWidth * tensorAspect
        } else {
            roiWidth = imageHeight / tensorAspect
            roiHeight = imageHeight.toFloat()
        }
        subRectToImage(0.5f * imageWidth, 0.5f * imageHeight, roiWidth, roiHeight, 0f, imageWidth, imageHeight, projection)
        val m = projection
        var left = Float.MAX_VALUE
        var topEdge = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (corner in 0 until 4) {
            val px = if (corner == 1 || corner == 2) xmin + width else xmin
            val py = if (corner >= 2) ymin + height else ymin
            val x = px * m[0] + py * m[1] + m[3]
            val y = px * m[4] + py * m[5] + m[7]
            left = min(left, x)
            topEdge = min(topEdge, y)
            right = max(right, x)
            bottom = max(bottom, y)
        }
        val eye0x = weightedKeypoints[0] * m[0] + weightedKeypoints[1] * m[1] + m[3]
        val eye0y = weightedKeypoints[0] * m[4] + weightedKeypoints[1] * m[5] + m[7]
        val eye1x = weightedKeypoints[2] * m[0] + weightedKeypoints[3] * m[1] + m[3]
        val eye1y = weightedKeypoints[2] * m[4] + weightedKeypoints[3] * m[5] + m[7]

        // DetectionsToRectsCalculator: the box, rotated so that keypoint 0 → 1 (the eyes) is horizontal.
        val boxWidth = right - left
        val boxHeight = bottom - topEdge
        rect.centerX = left + boxWidth / 2
        rect.centerY = topEdge + boxHeight / 2
        val x0 = eye0x * imageWidth
        val y0 = eye0y * imageHeight
        val x1 = eye1x * imageWidth
        val y1 = eye1y * imageHeight
        rect.rotation = normalizeRadians(0f - atan2(-(y1 - y0), x1 - x0))
        // RectTransformationCalculator: scale 1.5, not squared.
        rect.width = boxWidth * RECT_SCALE
        rect.height = boxHeight * RECT_SCALE
        return true
    }

    /** IoU of two decoded boxes, as `OverlapSimilarity(INTERSECTION_OVER_UNION)`. */
    private fun overlap(i: Int, j: Int): Float {
        val ax0 = boxes[i * 4]
        val ay0 = boxes[i * 4 + 1]
        val ax1 = ax0 + boxes[i * 4 + 2]
        val ay1 = ay0 + boxes[i * 4 + 3]
        val bx0 = boxes[j * 4]
        val by0 = boxes[j * 4 + 1]
        val bx1 = bx0 + boxes[j * 4 + 2]
        val by1 = by0 + boxes[j * 4 + 3]
        if (bx0 > ax1 || bx1 < ax0 || by0 > ay1 || by1 < ay0) return 0f
        val intersection = (min(ax1, bx1) - max(ax0, bx0)) * (min(ay1, by1) - max(ay0, by0))
        val normalization = boxes[i * 4 + 2] * boxes[i * 4 + 3] + boxes[j * 4 + 2] * boxes[j * 4 + 3] - intersection
        return if (normalization > 0f) intersection / normalization else 0f
    }

    companion object {
        const val INPUT_SIZE = 128
        const val NUM_BOXES = 896
        const val NUM_COORDS = 16
        const val NUM_KEYPOINTS = 6
        const val KEYPOINT_OFFSET = 4
        const val SCORE_CLIPPING = 100f
        /** `min_face_detection_confidence`. */
        const val MIN_SCORE = 0.5f
        /** FaceDetectorGraphOptions `min_suppression_threshold`. */
        const val MIN_SUPPRESSION = 0.5f
        const val RECT_SCALE = 1.5f
    }
}
