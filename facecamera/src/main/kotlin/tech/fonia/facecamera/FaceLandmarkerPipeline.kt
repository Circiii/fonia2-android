package tech.fonia.facecamera

import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * MediaPipe's Face Landmarker graph (v1.2.2, `face_landmarker_graph.cc` and its subgraphs) for one face, run by
 * hand on LiteRT instead of through the MediaPipe Tasks library:
 *
 * 1. no face tracked: the short-range detector on the whole frame, letterboxed to 128×128 ([FaceDetectionDecoder]);
 *    tracked: the region the previous frame's landmarks gave;
 * 2. the region, rotated so the eyes are level, resampled to 256×256 ([FrameSampler]); the landmark model gives
 *    478 landmarks, a face presence score (sigmoid, kept above 0.5) and a tongue score MediaPipe ignores;
 * 3. landmarks projected back to the frame; the next region is their bounding box, squared and enlarged 1.5 times;
 * 4. in stream mode the landmarks are smoothed ([LandmarkSmoother]);
 * 5. 146 of them, in pixels, go into the blendshape model: 52 scores;
 * 6. the pose and the gaze ([FaceGeometry]).
 *
 * [process] in stream mode is MediaPipe's VIDEO/LIVE_STREAM running mode, otherwise its IMAGE mode. Everything is
 * allocated up front; a frame allocates nothing. One thread at a time.
 */
internal class FaceLandmarkerPipeline(bundle: ModelBundle, threads: Int = 2) : AutoCloseable {
    private val detector = LiteRtModel(bundle.faceDetector, threads)
    private val landmarker = LiteRtModel(bundle.faceLandmarks, threads)
    private val blendshapeModel = LiteRtModel(bundle.blendshapes, 1)
    private val decoder = FaceDetectionDecoder()
    val geometry = FaceGeometry(bundle.geometry)
    private val smoother = LandmarkSmoother(LANDMARKS)

    private val detectorInput = FloatArray(DETECTOR_SIZE * DETECTOR_SIZE * 3)
    private val landmarkInput = FloatArray(LANDMARK_SIZE * LANDMARK_SIZE * 3)
    private val blendshapeInput = FloatArray(Blendshapes.LANDMARK_SUBSET.size * 2)
    private val projection = FloatArray(16)

    private val region = NormRect()
    private val nextRegion = NormRect()
    private var tracking = false

    /** The last frame's landmarks as the model gave them, projected onto the upright frame. */
    val rawLandmarks = FloatArray(LANDMARKS * 3)

    /** The last frame's landmarks after smoothing (stream mode) or as given (image mode): x, y, z per landmark. */
    val landmarks = FloatArray(LANDMARKS * 3)

    /** The last frame's 52 blendshape scores, in [Blendshapes.NAMES] order. */
    val scores = FloatArray(Blendshapes.COUNT)

    var presence = 0f
        private set

    /** The landmark model's own "tongue out of the mouth" output, which MediaPipe does not use. */
    var tongueOut = 0f
        private set

    /** Whether the last frame came from the detector rather than from tracking. */
    var detected = false
        private set

    /** Nanoseconds the last frame spent in: detector, landmarks, blendshapes, geometry, total. */
    val timings = LongArray(5)

    init {
        check(detector.inputSize() == DETECTOR_SIZE * DETECTOR_SIZE * 3)
        check(detector.outputSize(0) == FaceDetectionDecoder.NUM_BOXES * FaceDetectionDecoder.NUM_COORDS)
        check(detector.outputSize(1) == FaceDetectionDecoder.NUM_BOXES)
        check(landmarker.inputSize() == LANDMARK_SIZE * LANDMARK_SIZE * 3)
        check(landmarker.outputSize(0) == LANDMARKS * 3 && landmarker.outputSize(1) == 1 && landmarker.outputSize(2) == 1)
        check(blendshapeModel.inputSize() == blendshapeInput.size && blendshapeModel.outputSize(0) == Blendshapes.COUNT)
    }

    /** Forgets the tracked face and the smoothing (ARKit's `.resetTracking`). */
    fun reset() {
        tracking = false
        smoother.reset()
    }

    /** Analyses one frame; true when a face was found. [timestampMicros] only matters in [stream] mode. */
    fun process(frame: RgbaFrame, timestampMicros: Long, stream: Boolean): Boolean {
        val start = System.nanoTime()
        timings.fill(0)
        if (!stream) reset()
        val width = frame.uprightWidth
        val height = frame.uprightHeight

        detected = !tracking
        if (tracking) {
            region.set(nextRegion)
        } else {
            val found = detect(frame, width, height)
            timings[0] = System.nanoTime() - start
            if (!found) return lost(start)
        }

        val landmarksStart = System.nanoTime()
        FrameSampler.sample(
            frame, region.centerX * width, region.centerY * height, region.width * width, region.height * height,
            region.rotation, LANDMARK_SIZE, LANDMARK_SIZE, Border.REPLICATE, FrameSampler.valueScale(0f, 1f), 0f,
            landmarkInput,
        )
        landmarker.run(landmarkInput)
        presence = 1.0f / (1.0f + exp(-landmarker.outputs[1].get(0)))
        tongueOut = landmarker.outputs[2].get(0)
        timings[1] = System.nanoTime() - landmarksStart
        // ThresholdingCalculator: a face only above the threshold.
        if (!(presence.toDouble() > MIN_PRESENCE)) return lost(start)
        project(width, height)
        nextRegion(width, height)
        tracking = stream

        if (stream) smoother.apply(rawLandmarks, width, height, timestampMicros, landmarks) else rawLandmarks.copyInto(landmarks)

        val blendshapesStart = System.nanoTime()
        val subset = Blendshapes.LANDMARK_SUBSET
        for (k in subset.indices) {
            blendshapeInput[k * 2] = landmarks[subset[k] * 3] * width
            blendshapeInput[k * 2 + 1] = landmarks[subset[k] * 3 + 1] * height
        }
        blendshapeModel.run(blendshapeInput)
        for (i in 0 until Blendshapes.COUNT) scores[i] = blendshapeModel.outputs[0].get(i)
        timings[2] = System.nanoTime() - blendshapesStart

        val geometryStart = System.nanoTime()
        geometry.estimatePose(landmarks, width, height)
        geometry.estimateGaze(landmarks, scores[Blendshapes.EYE_BLINK_LEFT], scores[Blendshapes.EYE_BLINK_RIGHT])
        val end = System.nanoTime()
        timings[3] = end - geometryStart
        timings[4] = end - start
        return true
    }

    /** No face: the next frame starts again from the detector, and the smoothing forgets (as MediaPipe resets it). */
    private fun lost(start: Long): Boolean {
        timings[4] = System.nanoTime() - start
        reset()
        return false
    }

    private fun detect(frame: RgbaFrame, width: Int, height: Int): Boolean {
        // The whole frame, letterboxed into the square input (keep_aspect_ratio, BORDER_ZERO), values in [-1, 1].
        val roiWidth: Float
        val roiHeight: Float
        if (1f > height.toFloat() / width) {
            roiWidth = width.toFloat()
            roiHeight = width * 1f
        } else {
            roiWidth = height / 1f
            roiHeight = height.toFloat()
        }
        FrameSampler.sample(
            frame, 0.5f * width, 0.5f * height, roiWidth, roiHeight, 0f, DETECTOR_SIZE, DETECTOR_SIZE, Border.ZERO,
            FrameSampler.valueScale(-1f, 1f), -1f, detectorInput,
        )
        detector.run(detectorInput)
        return decoder.decode(detector.outputs[0], detector.outputs[1], width, height, region)
    }

    /** TensorsToLandmarks, LandmarkLetterboxRemoval (no padding) and LandmarkProjection with the image size. */
    private fun project(width: Int, height: Int) {
        subRectToImage(
            region.centerX * width, region.centerY * height, region.width * width, region.height * height,
            region.rotation, width, height, projection,
        )
        val m = projection
        // CalculateZScale: the length of the projected (0,0)→(1,0) segment.
        val ax = 0f * m[0] + 0f * m[1] + 0f * m[2] + m[3]
        val ay = 0f * m[4] + 0f * m[5] + 0f * m[6] + m[7]
        val bx = 1f * m[0] + 0f * m[1] + 0f * m[2] + m[3]
        val by = 1f * m[4] + 0f * m[5] + 0f * m[6] + m[7]
        val zScale = sqrt((bx - ax).toDouble().pow(2) + (by - ay).toDouble().pow(2)).toFloat()
        val raw = landmarker.outputs[0]
        for (i in 0 until LANDMARKS) {
            val x = raw.get(i * 3) / LANDMARK_SIZE
            val y = raw.get(i * 3 + 1) / LANDMARK_SIZE
            val z = raw.get(i * 3 + 2) / LANDMARK_SIZE / 1.0f
            rawLandmarks[i * 3] = x * m[0] + y * m[1] + z * m[2] + m[3]
            rawLandmarks[i * 3 + 1] = x * m[4] + y * m[5] + z * m[6] + m[7]
            rawLandmarks[i * 3 + 2] = zScale * z
        }
    }

    /**
     * LandmarksToDetection, DetectionsToRects (rotation from landmark 33 to 263) and RectTransformation
     * (square on the long side, scale 1.5): where to look in the next frame.
     */
    private fun nextRegion(width: Int, height: Int) {
        var xMin = Float.MAX_VALUE
        var xMax = FLT_MIN
        var yMin = Float.MAX_VALUE
        var yMax = FLT_MIN
        for (i in 0 until LANDMARKS) {
            val x = rawLandmarks[i * 3]
            val y = rawLandmarks[i * 3 + 1]
            xMin = kotlin.math.min(xMin, x)
            xMax = max(xMax, x)
            yMin = kotlin.math.min(yMin, y)
            yMax = max(yMax, y)
        }
        val boxWidth = xMax - xMin
        val boxHeight = yMax - yMin
        nextRegion.centerX = xMin + boxWidth / 2
        nextRegion.centerY = yMin + boxHeight / 2
        val x0 = rawLandmarks[FaceGeometry.RIGHT_EYE_OUTER * 3] * width
        val y0 = rawLandmarks[FaceGeometry.RIGHT_EYE_OUTER * 3 + 1] * height
        val x1 = rawLandmarks[FaceGeometry.LEFT_EYE_OUTER * 3] * width
        val y1 = rawLandmarks[FaceGeometry.LEFT_EYE_OUTER * 3 + 1] * height
        nextRegion.rotation = normalizeRadians(0f - atan2(-(y1 - y0), x1 - x0))
        val longSide = max(boxWidth * width, boxHeight * height)
        nextRegion.width = longSide / width * NEXT_REGION_SCALE
        nextRegion.height = longSide / height * NEXT_REGION_SCALE
    }

    override fun close() {
        detector.close()
        landmarker.close()
        blendshapeModel.close()
    }

    companion object {
        const val LANDMARKS = 478
        const val DETECTOR_SIZE = 128
        const val LANDMARK_SIZE = 256
        /** `min_face_presence_confidence` (and the tracking threshold), as the defaults of the task. */
        const val MIN_PRESENCE = 0.5
        const val NEXT_REGION_SCALE = 1.5f

        /** C++ `std::numeric_limits<float>::min()`, which LandmarksToDetectionCalculator starts its maxima from. */
        private const val FLT_MIN = 1.17549435e-38f
    }
}
