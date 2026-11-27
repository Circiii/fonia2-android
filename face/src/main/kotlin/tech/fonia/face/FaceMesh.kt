package tech.fonia.face

/**
 * Which of the face model's 478 landmarks reach `:engine:face`, and in which order (docs/v2/04 §2).
 *
 * Checked against MediaPipe's `face_mesh_connections.py` (`FACEMESH_LIPS`, `FACEMESH_LEFT_IRIS`,
 * `FACEMESH_RIGHT_IRIS`, Apache-2.0): both lip rings are the closed loops of `FACEMESH_LIPS`, walked
 * from the corner on the person's right (image left), over the upper lip, to the other corner and back under the
 * lower lip. `FaceMeshIndexTests` also checks them on MediaPipe's own landmarks of real photos.
 *
 * Conventions: x right and y down, normalised to the upright, UNMIRRORED image; z relative, at x's scale (negative
 * = towards the camera). "Left" and "right" always mean the person's own.
 */
object FaceMeshIndices {
    /** Outer lip contour, 20 points: 61 (right corner) … 0 (upper middle) … 291 (left corner) … 17 (lower middle). */
    val LIPS_OUTER = intArrayOf(61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291, 375, 321, 405, 314, 17, 84, 181, 91, 146)

    /** Inner lip contour (the mouth opening), 20 points: 78 … 13 (upper middle) … 308 … 14 (lower middle). */
    val LIPS_INNER = intArrayOf(78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308, 324, 318, 402, 317, 14, 87, 178, 88, 95)

    /** Reference points: nose tip, nose base, nose, forehead, chin, face sides, eye corners, nose bridge. */
    val ANCHORS = intArrayOf(1, 2, 4, 10, 152, 234, 454, 33, 133, 263, 362, 168)

    /** 468 right iris centre + 469–472, 473 left iris centre + 474–477. */
    val IRISES = intArrayOf(468, 469, 470, 471, 472, 473, 474, 475, 476, 477)

    const val RING = 20

    // Positions inside LIPS_OUTER / LIPS_INNER.
    const val CORNER_RIGHT = 0
    const val UPPER_MID = 5
    const val CORNER_LEFT = 10
    const val LOWER_MID = 15

    // Positions inside ANCHORS.
    const val NOSE_TIP = 0
    const val NOSE_BASE = 1
    const val FOREHEAD = 3
    const val CHIN = 4
    const val FACE_RIGHT = 5
    const val FACE_LEFT = 6
    const val EYE_RIGHT_OUTER = 7
    const val EYE_RIGHT_INNER = 8
    const val EYE_LEFT_OUTER = 9
    const val EYE_LEFT_INNER = 10
    const val NOSE_BRIDGE = 11

    // Positions inside IRISES.
    const val IRIS_RIGHT_CENTER = 0
    const val IRIS_LEFT_CENTER = 5
}

/**
 * The 52 blendshape scores of MediaPipe's model, in model order (the same list as `:platform:camera`'s
 * `Blendshapes.NAMES`; a test there checks they agree). MediaPipe has `_neutral` where ARKit has `tongueOut`.
 */
object MeshBlendshapes {
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
    const val CHEEK_PUFF = 6
    const val JAW_OPEN = 25
    const val MOUTH_CLOSE = 27
    const val MOUTH_FUNNEL = 32
    const val MOUTH_PUCKER = 38
    const val MOUTH_ROLL_LOWER = 40
    const val MOUTH_ROLL_UPPER = 41
    const val MOUTH_SMILE_LEFT = 44
    const val MOUTH_SMILE_RIGHT = 45
    const val MOUTH_STRETCH_LEFT = 46
    const val MOUTH_STRETCH_RIGHT = 47
}

/**
 * One analysed camera frame as `:engine:face` sees it: the landmarks that matter for the mouth, all 52 blendshape
 * scores, the head pose, the light, and a small upright colour crop around the mouth for the tongue heuristic.
 *
 * Allocation-free by design: the camera owns two instances and refills them ([copyFrom] for hand-over). The crop
 * lives only in this RAM buffer and is overwritten by the next frame; nothing here is ever written anywhere.
 */
class FaceMeshFrame {
    /** CLOCK_MONOTONIC capture time. */
    var tNanos: Long = 0
    var isPresent: Boolean = false

    /** The face came from the detector rather than from tracking the previous frame. */
    var detected: Boolean = false

    /** Upright, unmirrored analysis image size in pixels. */
    var imageW: Int = 0
    var imageH: Int = 0

    /** 20 × (x, y, z), [FaceMeshIndices.LIPS_OUTER] order. */
    val lipsOuter = FloatArray(FaceMeshIndices.RING * 3)

    /** 20 × (x, y, z), [FaceMeshIndices.LIPS_INNER] order. */
    val lipsInner = FloatArray(FaceMeshIndices.RING * 3)

    /** 12 × (x, y, z), [FaceMeshIndices.ANCHORS] order. */
    val anchors = FloatArray(FaceMeshIndices.ANCHORS.size * 3)

    /** 10 × (x, y, z): 468…477. */
    val irises = FloatArray(FaceMeshIndices.IRISES.size * 3)

    /** 52 scores, [MeshBlendshapes.NAMES] order, raw model output. */
    val blendshapes = FloatArray(MeshBlendshapes.COUNT)

    /** Head pose, degrees, as `FaceFrame` reads it: yaw positive towards the viewer's right of the unmirrored image, pitch up. */
    var headYaw: Float = 0f
    var headPitch: Float = 0f

    /** In-plane tilt from the eye line, degrees, positive clockwise on the unmirrored image. */
    var headRoll: Float = 0f

    /** Camera-to-face distance in metres from the pose fit (assumes a 63° field of view), NaN when unknown. */
    var distanceM: Float = Float.NaN

    /** Face presence (0…1) and the landmark model's own tongue-out score (0…1, unused by MediaPipe). */
    var presence: Float = 0f
    var tongueModelScore: Float = 0f

    /** Mean luma 0…255 over the face box and the mouth crop; share of the mouth crop that is burnt out (≥ 250). */
    var lumaFace: Float = Float.NaN
    var lumaMouth: Float = Float.NaN
    var clippedShare: Float = 0f

    /** The mouth crop: [CROP] × [CROP] RGB, 0…1, row after row, upright; valid when [hasCrop]. */
    val mouthCrop = FloatArray(CROP * CROP * 3)
    var hasCrop: Boolean = false

    /** Where the crop sits: centre (image px), side (image px), angle of its x axis (radians, image y down). */
    var cropCenterX: Float = 0f
    var cropCenterY: Float = 0f
    var cropSide: Float = 0f
    var cropAngle: Float = 0f

    fun clear(tNanos: Long) {
        this.tNanos = tNanos
        isPresent = false
        detected = false
        hasCrop = false
        presence = 0f
        tongueModelScore = 0f
        lumaFace = Float.NaN
        lumaMouth = Float.NaN
        clippedShare = 0f
    }

    fun copyFrom(o: FaceMeshFrame) {
        tNanos = o.tNanos; isPresent = o.isPresent; detected = o.detected; imageW = o.imageW; imageH = o.imageH
        o.lipsOuter.copyInto(lipsOuter); o.lipsInner.copyInto(lipsInner); o.anchors.copyInto(anchors)
        o.irises.copyInto(irises); o.blendshapes.copyInto(blendshapes)
        headYaw = o.headYaw; headPitch = o.headPitch; headRoll = o.headRoll; distanceM = o.distanceM
        presence = o.presence; tongueModelScore = o.tongueModelScore
        lumaFace = o.lumaFace; lumaMouth = o.lumaMouth; clippedShare = o.clippedShare
        hasCrop = o.hasCrop
        if (o.hasCrop) o.mouthCrop.copyInto(mouthCrop)
        cropCenterX = o.cropCenterX; cropCenterY = o.cropCenterY; cropSide = o.cropSide; cropAngle = o.cropAngle
    }

    /**
     * Fills the landmark arrays from a full 478 × (x, y, z) set, as the camera module and the tests have it.
     */
    fun setLandmarks(all: FloatArray) {
        pick(all, FaceMeshIndices.LIPS_OUTER, lipsOuter)
        pick(all, FaceMeshIndices.LIPS_INNER, lipsInner)
        pick(all, FaceMeshIndices.ANCHORS, anchors)
        pick(all, FaceMeshIndices.IRISES, irises)
    }

    /**
     * Places the mouth crop for the current landmarks (call after [setLandmarks] and with [imageW]/[imageH] set):
     * a square of side 1.7 × the eye distance, centred 0.15 eye distances below the middle of the mouth corners,
     * turned with the eye line. It covers every tongue zone of docs/v2/04 §5.1 for an ordinary mouth.
     */
    fun placeCrop() {
        val w = imageW.toFloat()
        val h = imageH.toFloat()
        val erx = (ax(FaceMeshIndices.EYE_RIGHT_OUTER) + ax(FaceMeshIndices.EYE_RIGHT_INNER)) * 0.5f * w
        val ery = (ay(FaceMeshIndices.EYE_RIGHT_OUTER) + ay(FaceMeshIndices.EYE_RIGHT_INNER)) * 0.5f * h
        val elx = (ax(FaceMeshIndices.EYE_LEFT_OUTER) + ax(FaceMeshIndices.EYE_LEFT_INNER)) * 0.5f * w
        val ely = (ay(FaceMeshIndices.EYE_LEFT_OUTER) + ay(FaceMeshIndices.EYE_LEFT_INNER)) * 0.5f * h
        val dx = elx - erx
        val dy = ely - ery
        val eye = kotlin.math.sqrt(dx * dx + dy * dy)
        val angle = kotlin.math.atan2(dy, dx)
        val mx = (lipsOuter[FaceMeshIndices.CORNER_RIGHT * 3] + lipsOuter[FaceMeshIndices.CORNER_LEFT * 3]) * 0.5f * w
        val my = (lipsOuter[FaceMeshIndices.CORNER_RIGHT * 3 + 1] + lipsOuter[FaceMeshIndices.CORNER_LEFT * 3 + 1]) * 0.5f * h
        // Down the face = the eye line turned 90° clockwise (image y down).
        val downX = -kotlin.math.sin(angle)
        val downY = kotlin.math.cos(angle)
        cropCenterX = mx + downX * CROP_DROP * eye
        cropCenterY = my + downY * CROP_DROP * eye
        cropSide = CROP_SIDE * eye
        cropAngle = angle
    }

    private fun ax(k: Int) = anchors[k * 3]
    private fun ay(k: Int) = anchors[k * 3 + 1]

    companion object {
        const val CROP = 64
        const val CROP_SIDE = 1.7f
        const val CROP_DROP = 0.15f

        private fun pick(all: FloatArray, indices: IntArray, out: FloatArray) {
            for (k in indices.indices) {
                val i = indices[k] * 3
                out[k * 3] = all[i]
                out[k * 3 + 1] = all[i + 1]
                out[k * 3 + 2] = all[i + 2]
            }
        }
    }
}
