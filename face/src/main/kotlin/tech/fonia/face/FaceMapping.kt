package tech.fonia.face

import kotlin.math.asin
import kotlin.math.atan2

// FoniaFace/FaceMapping.swift. On iOS these types are internal, used by the ARKit and Vision
// sources inside the module. On Android the camera source lives in :features, so they are public.

/**
 * The ARKit blendshape coefficients Fonia reads, under ARKit's Swift names. MediaPipe Face
 * Landmarker reports its 52 blendshapes under the same names, except that it has no `tongueOut`.
 */
data class BlendshapeValues(
    val jawOpen: Float = 0f,
    val mouthSmileLeft: Float = 0f,
    val mouthSmileRight: Float = 0f,
    val mouthFunnel: Float = 0f,
    val mouthPucker: Float = 0f,
    val cheekPuff: Float = 0f,
    val tongueOut: Float = 0f,
) {
    companion object {
        val NAMES = listOf("jawOpen", "mouthSmileLeft", "mouthSmileRight", "mouthFunnel", "mouthPucker", "cheekPuff", "tongueOut")

        /** Coefficients by name, a missing one as 0 (`shapes[location]?.floatValue ?? 0`). */
        fun fromScores(scores: Map<String, Float>): BlendshapeValues = BlendshapeValues(
            jawOpen = scores["jawOpen"] ?: 0f,
            mouthSmileLeft = scores["mouthSmileLeft"] ?: 0f,
            mouthSmileRight = scores["mouthSmileRight"] ?: 0f,
            mouthFunnel = scores["mouthFunnel"] ?: 0f,
            mouthPucker = scores["mouthPucker"] ?: 0f,
            cheekPuff = scores["cheekPuff"] ?: 0f,
            tongueOut = scores["tongueOut"] ?: 0f,
        )
    }
}

/** Horizontal and vertical angle of a direction, from the +z axis, in degrees. */
data class DirectionAngles(val yaw: Float, val pitch: Float) {
    companion object {
        operator fun invoke(direction: Float3): DirectionAngles {
            val length = direction.length
            if (!(length > 0)) return DirectionAngles(yaw = 0f, pitch = 0f)
            return DirectionAngles(
                yaw = atan2(direction.x, direction.z) * 180f / PI_F,
                pitch = asin(SwiftMath.max(-1f, SwiftMath.min(1f, direction.y / length))) * 180f / PI_F,
            )
        }
    }
}

/**
 * `FaceFrame(blendshapes:faceToCamera:lookAt:)`.
 *
 * @param faceToCamera the face's pose in camera space, where +z points from the camera to the
 *   viewer. The face's own +z points out of the face, so facing the camera gives yaw and pitch 0.
 * @param lookAt ARKit's `lookAtPoint`, in face space.
 */
operator fun FaceFrame.Companion.invoke(blendshapes: BlendshapeValues, faceToCamera: Float4x4, lookAt: Float3): FaceFrame {
    val forward = faceToCamera.c2
    val head = DirectionAngles(Float3(forward.x, forward.y, forward.z))
    val gazeInCamera = faceToCamera * Float4(lookAt.x, lookAt.y, lookAt.z, 0f)
    val gaze = DirectionAngles(Float3(gazeInCamera.x, gazeInCamera.y, gazeInCamera.z))
    return FaceFrame(
        jawOpen = unit(blendshapes.jawOpen),
        smile = unit((blendshapes.mouthSmileLeft + blendshapes.mouthSmileRight) / 2f),
        funnel = unit(blendshapes.mouthFunnel),
        pucker = unit(blendshapes.mouthPucker),
        puff = unit(blendshapes.cheekPuff),
        tongueOut = unit(blendshapes.tongueOut),
        gazeYaw = gaze.yaw,
        gazePitch = gaze.pitch,
        headYaw = head.yaw,
        headPitch = head.pitch,
    )
}

/**
 * The Vision source's reading (VisionFaceSource.swift, `reading(from:)`): the head angles stand in
 * for the gaze, and the mouth comes from lip geometry when the landmarks give a shape. `pucker`,
 * `puff` and `tongueOut` stay 0 on this path.
 */
fun FaceFrame.Companion.fromLandmarks(yawRadians: Float?, pitchRadians: Float?, landmarks: MouthLandmarks?): FaceFrame {
    val yaw = (yawRadians ?: 0f) * 180f / PI_F
    val pitch = (pitchRadians ?: 0f) * 180f / PI_F
    val frame = FaceFrame(gazeYaw = yaw, gazePitch = pitch, headYaw = yaw, headPitch = pitch)
    val shape = landmarks?.let { MouthShape(it) } ?: return frame
    return frame.copy(jawOpen = shape.jaw, smile = shape.smile, funnel = shape.round)
}

/** Face landmarks in image coordinates, as Vision reports them. */
data class MouthLandmarks(
    val leftPupil: Float2,
    val rightPupil: Float2,
    val outerLips: List<Float2>,
    val innerLips: List<Float2>,
)

/**
 * Jaw, spread and rounding worked out from lip geometry, for devices without ARKit.
 *
 * Distances are divided by the distance between the pupils and measured along the eye line, so
 * they hold when the head tilts or moves away. The ranges come from typical adult proportions
 * (mouth width about 0.8 of the pupil distance at rest). They are starting values, not measured on
 * children, and the geometry cannot see cheek puff or the tongue.
 */
data class MouthShape(val jaw: Float, val smile: Float, val round: Float) {
    companion object {
        /** `init?(landmarks:)`: null when the pupils coincide or a lip contour is missing. */
        operator fun invoke(landmarks: MouthLandmarks): MouthShape? {
            val eyeLine = landmarks.rightPupil - landmarks.leftPupil
            val pupilDistance = eyeLine.length
            if (!(pupilDistance > 0) || landmarks.outerLips.isEmpty() || landmarks.innerLips.isEmpty()) return null

            val along = eyeLine / pupilDistance
            val across = Float2(-along.y, along.x)
            val width = extent(landmarks.outerLips, along) / pupilDistance
            val gap = extent(landmarks.innerLips, across) / pupilDistance

            return MouthShape(
                jaw = unit((gap - 0.04f) / 0.60f),
                smile = unit((width - 0.85f) / 0.30f),
                round = unit((0.70f - width) / 0.25f),
            )
        }
    }
}

private fun extent(points: List<Float2>, axis: Float2): Float {
    val positions = points.map { it.dot(axis) }
    return (SwiftMath.maxOrNull(positions) ?: 0f) - (SwiftMath.minOrNull(positions) ?: 0f)
}

private fun unit(value: Float): Float = SwiftMath.max(0f, SwiftMath.min(1f, value))
