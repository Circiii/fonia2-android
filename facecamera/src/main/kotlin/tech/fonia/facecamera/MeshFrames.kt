package tech.fonia.facecamera

import tech.fonia.face.FaceMeshFrame
import tech.fonia.face.FaceMeshIndices
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Fills [FaceMeshFrame] (the H1 seam into `:engine:face`) from the pipeline's last frame: the lip, anchor and iris
 * landmarks, all 52 blendshape scores, presence and the model's tongue score, the head pose, the light, and the
 * 64 × 64 mouth crop sampled from [frame] into the frame's reused buffer. Allocation-free; camera thread.
 */
internal class MeshFrameFiller {
    private val faceSample = FloatArray(FACE_GRID * FACE_GRID * 3)

    /** [found]: the pipeline found a face in this frame. */
    fun fill(pipeline: FaceLandmarkerPipeline, frame: RgbaFrame, tNanos: Long, found: Boolean, out: FaceMeshFrame) {
        out.clear(tNanos)
        out.imageW = frame.uprightWidth
        out.imageH = frame.uprightHeight
        if (!found) return
        out.isPresent = true
        out.detected = pipeline.detected
        out.presence = pipeline.presence
        out.tongueModelScore = pipeline.tongueOut
        out.setLandmarks(pipeline.landmarks)
        pipeline.scores.copyInto(out.blendshapes)
        fillPose(pipeline.geometry, out)
        out.placeCrop()
        sampleCrop(frame, out)
        sampleFaceLuma(frame, out)
    }

    private fun fillPose(g: FaceGeometry, out: FaceMeshFrame) {
        if (g.valid) {
            val r = g.rotationScale
            val s = g.scale
            // faceToCamera's third column (the face's +z in camera space), as FaceFrame reads yaw and pitch.
            val fx = r[2] / s
            val fy = r[5] / s
            val fz = r[8] / s
            val len = sqrt(fx * fx + fy * fy + fz * fz)
            if (len > 0) {
                out.headYaw = Math.toDegrees(atan2(fx, fz)).toFloat()
                out.headPitch = Math.toDegrees(asin((fy / len).coerceIn(-1.0, 1.0))).toFloat()
            }
            out.distanceM = (abs(g.translation[2]) / 100.0).toFloat()
        } else {
            out.headYaw = 0f
            out.headPitch = 0f
            out.distanceM = Float.NaN
        }
        val a = out.anchors
        val w = out.imageW.toFloat()
        val h = out.imageH.toFloat()
        val dx = ((a[FaceMeshIndices.EYE_LEFT_OUTER * 3] + a[FaceMeshIndices.EYE_LEFT_INNER * 3]) -
            (a[FaceMeshIndices.EYE_RIGHT_OUTER * 3] + a[FaceMeshIndices.EYE_RIGHT_INNER * 3])) * 0.5f * w
        val dy = ((a[FaceMeshIndices.EYE_LEFT_OUTER * 3 + 1] + a[FaceMeshIndices.EYE_LEFT_INNER * 3 + 1]) -
            (a[FaceMeshIndices.EYE_RIGHT_OUTER * 3 + 1] + a[FaceMeshIndices.EYE_RIGHT_INNER * 3 + 1])) * 0.5f * h
        out.headRoll = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    }

    private fun sampleCrop(frame: RgbaFrame, out: FaceMeshFrame) {
        if (!(out.cropSide > 2f)) return
        val n = FaceMeshFrame.CROP
        sampleNearest(frame, out.cropCenterX, out.cropCenterY, out.cropSide, out.cropAngle, n, out.mouthCrop)
        var sum = 0f
        var clipped = 0
        val c = out.mouthCrop
        for (p in 0 until n * n) {
            val y = 0.299f * c[p * 3] + 0.587f * c[p * 3 + 1] + 0.114f * c[p * 3 + 2]
            sum += y
            if (y >= CLIPPED) clipped++
        }
        out.lumaMouth = sum / (n * n) * 255f
        out.clippedShare = clipped.toFloat() / (n * n)
        out.hasCrop = true
    }

    private fun sampleFaceLuma(frame: RgbaFrame, out: FaceMeshFrame) {
        val a = out.anchors
        val w = out.imageW.toFloat()
        val h = out.imageH.toFloat()
        val left = a[FaceMeshIndices.FACE_RIGHT * 3] * w
        val right = a[FaceMeshIndices.FACE_LEFT * 3] * w
        val top = a[FaceMeshIndices.FOREHEAD * 3 + 1] * h
        val bottom = a[FaceMeshIndices.CHIN * 3 + 1] * h
        val side = maxOf(abs(right - left), abs(bottom - top)) * 0.7f
        if (!(side > 2f)) return
        sampleNearest(frame, (left + right) / 2, (top + bottom) / 2, side, out.cropAngle, FACE_GRID, faceSample)
        var sum = 0f
        for (p in 0 until FACE_GRID * FACE_GRID) {
            sum += 0.299f * faceSample[p * 3] + 0.587f * faceSample[p * 3 + 1] + 0.114f * faceSample[p * 3 + 2]
        }
        out.lumaFace = sum / (FACE_GRID * FACE_GRID) * 255f
    }

    companion object {
        private const val FACE_GRID = 16

        /**
         * A square of [side] upright pixels centred at ([cx], [cy]) and turned by [angle], sampled nearest-neighbour
         * into [n] × [n] RGB 0…1. Pixel (i, j) is the upright point p1 + i·s·ex + j·s·ey, exactly the geometry of
         * [FrameSampler] (MeshFrameTests), at a tenth of its cost: colour statistics need no sub-pixel accuracy.
         */
        fun sampleNearest(frame: RgbaFrame, cx: Float, cy: Float, side: Float, angle: Float, n: Int, out: FloatArray) {
            val ex = kotlin.math.cos(angle)
            val ey = kotlin.math.sin(angle)
            val step = side / n
            val half = side / 2f
            val p1x = cx - half * ex + half * ey
            val p1y = cy - half * ey - half * ex
            val uw = frame.uprightWidth
            val uh = frame.uprightHeight
            val lastX = frame.width - 1
            val lastY = frame.height - 1
            val px = frame.pixels
            val stride = frame.rowStride
            val rot = frame.rotationDegrees
            val mirrored = frame.mirrored
            var o = 0
            for (j in 0 until n) {
                for (i in 0 until n) {
                    var ux = (p1x + i * step * ex - j * step * ey + 0.5f).toInt().coerceIn(0, uw - 1)
                    val uy = (p1y + i * step * ey + j * step * ex + 0.5f).toInt().coerceIn(0, uh - 1)
                    if (mirrored) ux = uw - 1 - ux
                    val bx: Int
                    val by: Int
                    when (rot) {
                        0 -> { bx = ux; by = uy }
                        90 -> { bx = uy; by = lastY - ux }
                        180 -> { bx = lastX - ux; by = lastY - uy }
                        else -> { bx = lastX - uy; by = ux }
                    }
                    val k = by * stride + bx * 4
                    out[o] = (px[k].toInt() and 255) * INV255
                    out[o + 1] = (px[k + 1].toInt() and 255) * INV255
                    out[o + 2] = (px[k + 2].toInt() and 255) * INV255
                    o += 3
                }
            }
        }

        private const val INV255 = 1f / 255f
        private const val CLIPPED = 250f / 255f
    }
}
