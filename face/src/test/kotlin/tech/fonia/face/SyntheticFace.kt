package tech.fonia.face

import tech.fonia.face.FaceMeshIndices as I
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A made-up face in the mesh layout, for unit tests: everything in units of the eye distance E, placed on an
 * upright unmirrored image with a centre, a size, an in-plane roll and a yaw/pitch foreshortening.
 */
data class SyntheticFace(
    val imageW: Int = 480,
    val imageH: Int = 640,
    val centerX: Float = 240f,
    val centerY: Float = 260f,
    /** Eye distance in pixels. */
    val eyePx: Float = 120f,
    val rollDeg: Float = 0f,
    val yawDeg: Float = 0f,
    val pitchDeg: Float = 0f,
    /** Mouth width ÷ E. */
    val mouthWidth: Float = 0.8f,
    /** Inner lip gap ÷ E. */
    val aperture: Float = 0f,
    val upperLip: Float = 0.12f,
    val lowerLip: Float = 0.14f,
    /** Nose base to chin ÷ E at rest; the jaw opens with the aperture. */
    val jawRest: Float = 1.0f,
    val jawExtra: Float = 0f,
    val mouthDrop: Float = 0.3f,
    val irisE: Float = 11.7f / 63f,
    val pucker: Float = 0f,
    val funnel: Float = 0f,
    val smile: Float = 0f,
    val jawOpenScore: Float = 0f,
    val lumaMouth: Float = 140f,
    val presence: Float = 0.99f,
    val tongueModel: Float = 0f,
) {
    private val noseBaseY = 0.8f

    /** Mouth centre in face units. */
    val mouthCenterY: Float get() = noseBaseY + mouthDrop

    fun frame(tNanos: Long, into: FaceMeshFrame = FaceMeshFrame()): FaceMeshFrame {
        val f = into
        f.tNanos = tNanos
        f.isPresent = true
        f.imageW = imageW
        f.imageH = imageH
        f.headYaw = yawDeg
        f.headPitch = pitchDeg
        f.headRoll = rollDeg
        f.presence = presence
        f.tongueModelScore = tongueModel
        f.lumaMouth = lumaMouth
        f.lumaFace = lumaMouth
        f.clippedShare = 0f
        f.blendshapes.fill(0f)
        f.blendshapes[MeshBlendshapes.MOUTH_PUCKER] = pucker
        f.blendshapes[MeshBlendshapes.MOUTH_FUNNEL] = funnel
        f.blendshapes[MeshBlendshapes.MOUTH_SMILE_LEFT] = smile
        f.blendshapes[MeshBlendshapes.MOUTH_SMILE_RIGHT] = smile
        f.blendshapes[MeshBlendshapes.JAW_OPEN] = jawOpenScore
        val mc = mouthCenterY
        for (k in 0 until I.RING) {
            val t = Math.PI * k / 10.0
            val c = cos(t).toFloat()
            val s = abs(sin(t).toFloat())
            val upper = k in 1..9
            val lower = k in 11..19
            val ix = -mouthWidth * 0.9f / 2f * c
            val iy = mc + when {
                upper -> -aperture / 2f * s
                lower -> aperture / 2f * s
                else -> 0f
            }
            val ox = -mouthWidth / 2f * c
            val oy = mc + when {
                upper -> -(aperture / 2f + upperLip) * s
                lower -> (aperture / 2f + lowerLip) * s
                else -> 0f
            }
            put(f.lipsInner, k, ix, iy, -0.05f)
            put(f.lipsOuter, k, ox, oy, -0.08f)
        }
        val chinY = noseBaseY + jawRest + jawExtra + aperture * 0.5f
        val anchorsLocal = floatArrayOf(
            0f, 0.65f, -0.3f, // 1 nose tip
            0f, noseBaseY, -0.15f, // 2 nose base
            0f, 0.6f, -0.3f, // 4
            0f, -0.9f, 0f, // 10 forehead
            0f, chinY, 0f, // 152 chin
            -1.1f, 0.5f, 0.3f, // 234 face right (image left)
            1.1f, 0.5f, 0.3f, // 454 face left
            -0.72f, 0f, 0f, // 33 right eye outer
            -0.28f, 0f, 0f, // 133 right eye inner
            0.72f, 0f, 0f, // 263 left eye outer
            0.28f, 0f, 0f, // 362 left eye inner
            0f, 0.05f, -0.1f, // 168 nose bridge
        )
        for (k in 0 until I.ANCHORS.size) put(f.anchors, k, anchorsLocal[k * 3], anchorsLocal[k * 3 + 1], anchorsLocal[k * 3 + 2])
        val r = irisE / 2f
        for (eye in 0..1) {
            val ex = if (eye == 0) -0.5f else 0.5f
            val b = eye * 5
            put(f.irises, b, ex, 0f, 0f)
            put(f.irises, b + 1, ex + r, 0f, 0f)
            put(f.irises, b + 2, ex, -r, 0f)
            put(f.irises, b + 3, ex - r, 0f, 0f)
            put(f.irises, b + 4, ex, r, 0f)
        }
        f.hasCrop = false
        f.placeCrop()
        return f
    }

    /** Face units → image pixels: foreshortening, roll, scale, translation. */
    fun toImage(x: Float, y: Float): Pair<Float, Float> {
        val fx = x * cos(Math.toRadians(yawDeg.toDouble())).toFloat()
        val fy = y * cos(Math.toRadians(pitchDeg.toDouble())).toFloat()
        val a = Math.toRadians(rollDeg.toDouble())
        val rx = (fx * cos(a) - fy * sin(a)).toFloat()
        val ry = (fx * sin(a) + fy * cos(a)).toFloat()
        return Pair(centerX + rx * eyePx, centerY + ry * eyePx)
    }

    private fun put(arr: FloatArray, k: Int, x: Float, y: Float, z: Float) {
        val (px, py) = toImage(x, y)
        arr[k * 3] = px / imageW
        arr[k * 3 + 1] = py / imageH
        arr[k * 3 + 2] = z * eyePx / imageW
    }
}

/** Feeds [n] frames of [face] at 30 fps from [start] into [engine]; returns the last state and the next time. */
fun MouthStateEngine.feed(face: SyntheticFace, n: Int, start: Long, state: MouthState = MouthState()): Pair<MouthState, Long> {
    val frame = FaceMeshFrame()
    var t = start
    repeat(n) {
        face.frame(t, frame)
        process(frame, state)
        t += FRAME_NANOS
    }
    return Pair(state, t)
}

const val FRAME_NANOS = 33_333_333L
