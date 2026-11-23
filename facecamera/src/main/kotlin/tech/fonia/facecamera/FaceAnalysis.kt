package tech.fonia.facecamera

import tech.fonia.face.BlendshapeValues
import tech.fonia.face.FaceFrame
import tech.fonia.face.FaceObservation
import tech.fonia.face.Float3
import tech.fonia.face.Float4
import tech.fonia.face.Float4x4

/**
 * Everything one analysed frame gave, for the recording tool ([FaceRecording]) and the debug screen. Delivered on
 * the camera thread; the object and its arrays are reused, so read them inside the callback. Numbers only: no image
 * leaves the analysis.
 */
class FaceAnalysis internal constructor() {
    /** The camera's timestamp of the frame, nanoseconds. */
    var timestampNanos: Long = 0
        internal set
    var isPresent: Boolean = false
        internal set

    /** The model's 52 scores, in [Blendshapes.NAMES] order, before any mapping. Zero without a face. */
    val scores = FloatArray(Blendshapes.COUNT)

    /** The seven coefficients the seam carries ([BlendshapeValues.NAMES] order), after [BlendshapeCalibration]. */
    val mapped = FloatArray(BlendshapeValues.NAMES.size)

    /** Degrees, as [FaceFrame] reads them from the observation. */
    var headYaw = 0f
        internal set
    var headPitch = 0f
        internal set
    var gazeYaw = 0f
        internal set
    var gazePitch = 0f
        internal set

    /** How many eyes the gaze came from (0: the head direction stands in). */
    var eyesUsed = 0
        internal set

    /** The landmark model's own tongue score, which nothing in the app reads (PARITY.md). */
    var tongueOutScore = 0f
        internal set

    /** Whether the face came from the detector (true) or from tracking the previous frame. */
    var detected = false
        internal set

    /** Time spent analysing this frame, nanoseconds, from the copied pixels to the observation. */
    var processingNanos: Long = 0
        internal set

    internal fun clear(timestamp: Long, nanos: Long) {
        timestampNanos = timestamp
        isPresent = false
        scores.fill(0f)
        mapped.fill(0f)
        headYaw = 0f
        headPitch = 0f
        gazeYaw = 0f
        gazePitch = 0f
        eyesUsed = 0
        tongueOutScore = 0f
        detected = false
        processingNanos = nanos
    }

    internal fun fill(pipeline: FaceLandmarkerPipeline, observation: FaceObservation, timestamp: Long, nanos: Long) {
        timestampNanos = timestamp
        isPresent = true
        pipeline.scores.copyInto(scores)
        val blendshapes = observation.blendshapes
        mapped[0] = blendshapes.jawOpen
        mapped[1] = blendshapes.mouthSmileLeft
        mapped[2] = blendshapes.mouthSmileRight
        mapped[3] = blendshapes.mouthFunnel
        mapped[4] = blendshapes.mouthPucker
        mapped[5] = blendshapes.cheekPuff
        mapped[6] = blendshapes.tongueOut
        val frame = observation.toFrame()
        headYaw = frame.headYaw
        headPitch = frame.headPitch
        gazeYaw = frame.gazeYaw
        gazePitch = frame.gazePitch
        eyesUsed = pipeline.geometry.eyesUsed
        tongueOutScore = pipeline.tongueOut
        detected = pipeline.detected
        processingNanos = nanos
    }
}

/**
 * The seam's reading of the last frame: the calibrated coefficients, the head pose as `faceToCamera` (rotation
 * without the fit's scale, translation in metres as ARKit's) and the gaze in face space. [mapped] is scratch.
 */
internal fun FaceLandmarkerPipeline.observation(calibration: BlendshapeCalibration, mapped: FloatArray): FaceObservation =
    observationOf(scores, geometry, calibration, mapped)

/** The observation for 52 model [scores] and the pose and gaze in [geometry]. */
internal fun observationOf(scores: FloatArray, geometry: FaceGeometry, calibration: BlendshapeCalibration, mapped: FloatArray): FaceObservation {
    calibration.values(scores, mapped)
    val blendshapes = BlendshapeValues(
        jawOpen = mapped[0],
        mouthSmileLeft = mapped[1],
        mouthSmileRight = mapped[2],
        mouthFunnel = mapped[3],
        mouthPucker = mapped[4],
        cheekPuff = mapped[5],
        tongueOut = mapped[6],
    )
    val g = geometry
    // A degenerate fit (MediaPipe reports no geometry then): the face as if it looked into the camera.
    if (!g.valid) return FaceObservation(blendshapes, Float4x4.IDENTITY, Float3(0f, 0f, 1f))
    val r = g.rotationScale
    val s = g.scale
    val t = g.translation
    val pose = Float4x4(
        Float4((r[0] / s).toFloat(), (r[3] / s).toFloat(), (r[6] / s).toFloat(), 0f),
        Float4((r[1] / s).toFloat(), (r[4] / s).toFloat(), (r[7] / s).toFloat(), 0f),
        Float4((r[2] / s).toFloat(), (r[5] / s).toFloat(), (r[8] / s).toFloat(), 0f),
        Float4((t[0] / 100).toFloat(), (t[1] / 100).toFloat(), (t[2] / 100).toFloat(), 1f),
    )
    return FaceObservation(blendshapes, pose, Float3(g.lookAtX.toFloat(), g.lookAtY.toFloat(), g.lookAtZ.toFloat()))
}
