package tech.fonia.facecamera

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The head pose and the eye gaze, from the landmarks.
 *
 * **Pose.** MediaPipe's face geometry pipeline (`geometry_pipeline.cc`, FACE_LANDMARK_PIPELINE, the defaults of
 * FaceGeometryFromLandmarksGraph: a perspective camera with a 63° vertical field of view, near plane 1 cm, origin at
 * the top left). The 468 mesh landmarks are put on the near plane, given depth from their relative z, scaled twice
 * against the canonical face by weighted orthogonal Procrustes fits on the 33-landmark basis, and the final fit is the
 * pose: canonical face → camera, in centimetres. It equals MediaPipe's `facial_transformation_matrixes` (checked in
 * FaceGeometryTests and ModelParityTests). Axes: camera +x right on the upright, unmirrored image, +y up, +z towards
 * the viewer; face +x to the face's left, +y up, +z out of the face. These are ARKit's, so a face looking into the
 * camera has the identity rotation, as `faceToCamera` expects.
 *
 * **Gaze.** The landmark model also places the two irises (468 and 473). Both eyes go through the same conversion
 * into face space; there each eye's direction is taken from where its iris centre sits between the eye corners,
 * as if on an eyeball whose radius is [EYE_RADIUS_PER_WIDTH] of the corner-to-corner width:
 * `yaw = asin(dx / r)`, `pitch = asin(dy / r)`. An eye is left out when its angles are not plausible for an
 * open, visible eye (past [MAX_EYE_YAW_DEGREES] or [MAX_EYE_PITCH_DEGREES], which is what a closed or hidden eye
 * gives), when its blink score is over [MAX_BLINK], or when the head is turned away from it by more than
 * [MAX_HEAD_YAW_FOR_FAR_EYE_DEGREES]. The rest are averaged; with no eye left the gaze is the head direction,
 * `lookAt = (0, 0, 1)`. This is an estimate, not measured against ARKit's `lookAtPoint`: PresenceTracker, its
 * only reader, compares each reading with the speaker's own first moments, so a constant offset cancels out.
 */
internal class FaceGeometry(metadata: GeometryMetadata) {
    private val basis: IntArray
    private val sqrtWeights: DoubleArray
    private val canonical: DoubleArray // 3 per basis landmark
    private val totalWeight: Double

    // Scratch, sized for the basis.
    private val target = DoubleArray(BASIS_CAPACITY * 3)
    private val design = DoubleArray(9)
    private val n4 = DoubleArray(16)
    private val v4 = DoubleArray(16)

    /** Last pose: rotation × scale (row-major 3x3) and translation, centimetres. */
    val rotationScale = DoubleArray(9)
    val translation = DoubleArray(3)
    var scale = 1.0
        private set

    /** False when a fit was degenerate (a face squeezed to a point); the pose is then meaningless. */
    var valid = true
        private set

    /** Last gaze in face space, unit length. */
    var lookAtX = 0.0
        private set
    var lookAtY = 0.0
        private set
    var lookAtZ = 1.0
        private set
    var eyesUsed = 0
        private set

    // Screen-to-metric parameters of the last frame, for the eye points.
    private var left = 0.0
    private var bottom = 0.0
    private var xScale = 0.0
    private var yScale = 0.0
    private var depthOffset = 0.0
    private var totalScale = 1.0

    init {
        val ids = ArrayList<Int>()
        for (i in 0 until metadata.vertexCount) if (metadata.weights[i] > 0f) ids.add(i)
        check(ids.size <= BASIS_CAPACITY)
        basis = ids.toIntArray()
        sqrtWeights = DoubleArray(basis.size) { sqrt(metadata.weights[basis[it]].toDouble()) }
        canonical = DoubleArray(basis.size * 3) { metadata.canonical[basis[it / 3] * 3 + it % 3].toDouble() }
        var total = 0.0
        for (w in sqrtWeights) total += w * w
        totalWeight = total
    }

    /**
     * The pose of [landmarks] (x, y, z per landmark, normalized as MediaPipe's NormalizedLandmark) seen in a
     * [width]×[height] upright image. Fills [rotationScale], [translation] and [scale].
     */
    fun estimatePose(landmarks: FloatArray, width: Int, height: Int) {
        // PerspectiveCameraFrustum, in float as MediaPipe.
        val degreesToRadians = 3.14159265358979323846f / 180f
        val heightAtNear = 2f * NEAR * tan(0.5f * degreesToRadians * VERTICAL_FOV_DEGREES)
        val widthAtNear = width * heightAtNear / height
        left = (-0.5f * widthAtNear).toDouble()
        bottom = (-0.5f * heightAtNear).toDouble()
        xScale = (0.5f * widthAtNear - -0.5f * widthAtNear).toDouble()
        yScale = (0.5f * heightAtNear - -0.5f * heightAtNear).toDouble()

        valid = true
        var sum = 0.0
        for (i in 0 until MESH_LANDMARKS) sum += landmarks[i * 3 + 2] * xScale
        depthOffset = sum / MESH_LANDMARKS

        // First scale: the screen points with z flipped.
        for (k in basis.indices) {
            val i = basis[k]
            target[k * 3] = projectX(landmarks[i * 3])
            target[k * 3 + 1] = projectY(landmarks[i * 3 + 1])
            target[k * 3 + 2] = -(landmarks[i * 3 + 2] * xScale)
        }
        solve()
        val firstScale = scale
        // Second scale: unprojected with the first.
        totalScale = firstScale
        for (k in basis.indices) metric(landmarks, basis[k], target, k)
        solve()
        totalScale = firstScale * scale
        // The pose: unprojected with both.
        for (k in basis.indices) metric(landmarks, basis[k], target, k)
        solve()
    }

    /**
     * The gaze from the irises, after [estimatePose] on the same landmarks. [eyeBlinkLeft] and [eyeBlinkRight] are
     * the blendshape scores of the face's own eyes.
     */
    fun estimateGaze(landmarks: FloatArray, eyeBlinkLeft: Float, eyeBlinkRight: Float) {
        val headYaw = Math.toDegrees(atan2(rotationScale[2], rotationScale[8]))
        var yawSum = 0.0
        var pitchSum = 0.0
        eyesUsed = 0
        // The face's right eye (on the left of the unmirrored image): corners 33 and 133, iris 468. A head turned to
        // its left (positive yaw) turns this eye towards the camera.
        if (eyeBlinkRight <= MAX_BLINK && headYaw > -MAX_HEAD_YAW_FOR_FAR_EYE_DEGREES) {
            if (eye(landmarks, RIGHT_EYE_OUTER, RIGHT_EYE_INNER, RIGHT_IRIS)) {
                yawSum += eyeYaw
                pitchSum += eyePitch
                eyesUsed++
            }
        }
        if (eyeBlinkLeft <= MAX_BLINK && headYaw < MAX_HEAD_YAW_FOR_FAR_EYE_DEGREES) {
            if (eye(landmarks, LEFT_EYE_OUTER, LEFT_EYE_INNER, LEFT_IRIS)) {
                yawSum += eyeYaw
                pitchSum += eyePitch
                eyesUsed++
            }
        }
        if (eyesUsed == 0) {
            lookAtX = 0.0
            lookAtY = 0.0
            lookAtZ = 1.0
            return
        }
        val yaw = yawSum / eyesUsed
        val pitch = pitchSum / eyesUsed
        lookAtX = sin(yaw) * cos(pitch)
        lookAtY = sin(pitch)
        lookAtZ = cos(yaw) * cos(pitch)
    }

    private val eyePoint = DoubleArray(9)
    private var eyeYaw = 0.0
    private var eyePitch = 0.0

    /** One eye's angles in face space; false when they are not plausible for an open, visible eye. */
    private fun eye(landmarks: FloatArray, outer: Int, inner: Int, iris: Int): Boolean {
        toFace(landmarks, outer, 0)
        toFace(landmarks, inner, 3)
        toFace(landmarks, iris, 6)
        val cx = (eyePoint[0] + eyePoint[3]) / 2
        val cy = (eyePoint[1] + eyePoint[4]) / 2
        val dx = eyePoint[0] - eyePoint[3]
        val dy = eyePoint[1] - eyePoint[4]
        val dz = eyePoint[2] - eyePoint[5]
        val radius = EYE_RADIUS_PER_WIDTH * sqrt(dx * dx + dy * dy + dz * dz)
        if (!(radius > 0)) return false
        val sx = (eyePoint[6] - cx) / radius
        val sy = (eyePoint[7] - cy) / radius
        if (abs(sx) >= 1 || abs(sy) >= 1) return false
        eyeYaw = asin(sx)
        eyePitch = asin(sy)
        return abs(Math.toDegrees(eyeYaw)) <= MAX_EYE_YAW_DEGREES && abs(Math.toDegrees(eyePitch)) <= MAX_EYE_PITCH_DEGREES
    }

    /** Landmark [i] in face space (the inverse pose of its metric position), into [eyePoint] at [at]. */
    private fun toFace(landmarks: FloatArray, i: Int, at: Int) {
        metric(landmarks, i, eyePoint, at / 3)
        val px = eyePoint[at] - translation[0]
        val py = eyePoint[at + 1] - translation[1]
        val pz = eyePoint[at + 2] - translation[2]
        val s2 = scale * scale
        // (sR)ᵀ / s² = Rᵀ / s.
        eyePoint[at] = (rotationScale[0] * px + rotationScale[3] * py + rotationScale[6] * pz) / s2
        eyePoint[at + 1] = (rotationScale[1] * px + rotationScale[4] * py + rotationScale[7] * pz) / s2
        eyePoint[at + 2] = (rotationScale[2] * px + rotationScale[5] * py + rotationScale[8] * pz) / s2
    }

    private fun projectX(x: Float): Double = x * xScale + left

    private fun projectY(y: Float): Double = (1f - y) * yScale + bottom

    /** ProjectXY, MoveAndRescaleZ, UnprojectXY and ChangeHandedness for one landmark, into [out] at slot [slot]. */
    private fun metric(landmarks: FloatArray, i: Int, out: DoubleArray, slot: Int) {
        val z = (landmarks[i * 3 + 2] * xScale - depthOffset + NEAR) / totalScale
        out[slot * 3] = projectX(landmarks[i * 3]) * z / NEAR
        out[slot * 3 + 1] = projectY(landmarks[i * 3 + 1]) * z / NEAR
        out[slot * 3 + 2] = -z
    }

    /**
     * `FloatPrecisionProcrustesSolver::SolveWeightedOrthogonalProblem` from the canonical basis to [target]: sets
     * [rotationScale], [translation] and [scale]. The rotation that maximises trace(Rᵀ·design) is found with Horn's
     * quaternion method, which gives the same rotation as the SVD with its reflection fix.
     */
    private fun solve() {
        val n = basis.size
        // Weighted centre of mass of the sources.
        var comX = 0.0
        var comY = 0.0
        var comZ = 0.0
        for (k in 0 until n) {
            val w2 = sqrtWeights[k] * sqrtWeights[k]
            comX += canonical[k * 3] * w2
            comY += canonical[k * 3 + 1] * w2
            comZ += canonical[k * 3 + 2] * w2
        }
        comX /= totalWeight
        comY /= totalWeight
        comZ /= totalWeight
        // design = Σ (√w·t)(√w·(s − com))ᵀ, and the scale's denominator Σ (√w·(s − com))·(√w·s).
        design.fill(0.0)
        var denominator = 0.0
        for (k in 0 until n) {
            val sw = sqrtWeights[k]
            val tx = target[k * 3] * sw
            val ty = target[k * 3 + 1] * sw
            val tz = target[k * 3 + 2] * sw
            val cx = (canonical[k * 3] - comX) * sw
            val cy = (canonical[k * 3 + 1] - comY) * sw
            val cz = (canonical[k * 3 + 2] - comZ) * sw
            design[0] += tx * cx; design[1] += tx * cy; design[2] += tx * cz
            design[3] += ty * cx; design[4] += ty * cy; design[5] += ty * cz
            design[6] += tz * cx; design[7] += tz * cy; design[8] += tz * cz
            denominator += cx * canonical[k * 3] * sw + cy * canonical[k * 3 + 1] * sw + cz * canonical[k * 3 + 2] * sw
        }
        rotation(design, rotationScale)
        // numerator = Σ (R·c)·(√w·t) = trace(Rᵀ·design).
        var numerator = 0.0
        for (j in 0 until 9) numerator += rotationScale[j] * design[j]
        // MediaPipe rejects a fit whose scale expression is degenerate; so does this.
        valid = valid && denominator > EPSILON && numerator / denominator > EPSILON
        scale = if (valid) numerator / denominator else 1.0
        for (j in 0 until 9) rotationScale[j] *= scale
        var txSum = 0.0
        var tySum = 0.0
        var tzSum = 0.0
        for (k in 0 until n) {
            val w2 = sqrtWeights[k] * sqrtWeights[k]
            val sx = canonical[k * 3]
            val sy = canonical[k * 3 + 1]
            val sz = canonical[k * 3 + 2]
            txSum += (target[k * 3] - (rotationScale[0] * sx + rotationScale[1] * sy + rotationScale[2] * sz)) * w2
            tySum += (target[k * 3 + 1] - (rotationScale[3] * sx + rotationScale[4] * sy + rotationScale[5] * sz)) * w2
            tzSum += (target[k * 3 + 2] - (rotationScale[6] * sx + rotationScale[7] * sy + rotationScale[8] * sz)) * w2
        }
        translation[0] = txSum / totalWeight
        translation[1] = tySum / totalWeight
        translation[2] = tzSum / totalWeight
    }

    /** The proper rotation R (row-major) maximising trace(Rᵀ·m), via Horn's quaternion. */
    private fun rotation(m: DoubleArray, r: DoubleArray) {
        // Horn uses S = Σ source·targetᵀ = mᵀ.
        val sxx = m[0]
        val sxy = m[3]
        val sxz = m[6]
        val syx = m[1]
        val syy = m[4]
        val syz = m[7]
        val szx = m[2]
        val szy = m[5]
        val szz = m[8]
        val n = n4
        n[0] = sxx + syy + szz; n[1] = syz - szy; n[2] = szx - sxz; n[3] = sxy - syx
        n[4] = n[1]; n[5] = sxx - syy - szz; n[6] = sxy + syx; n[7] = szx + sxz
        n[8] = n[2]; n[9] = n[6]; n[10] = -sxx + syy - szz; n[11] = syz + szy
        n[12] = n[3]; n[13] = n[7]; n[14] = n[11]; n[15] = -sxx - syy + szz
        val best = largestEigenvector(n, v4)
        val q0 = v4[best]
        val qx = v4[4 + best]
        val qy = v4[8 + best]
        val qz = v4[12 + best]
        r[0] = q0 * q0 + qx * qx - qy * qy - qz * qz
        r[1] = 2 * (qx * qy - q0 * qz)
        r[2] = 2 * (qx * qz + q0 * qy)
        r[3] = 2 * (qy * qx + q0 * qz)
        r[4] = q0 * q0 - qx * qx + qy * qy - qz * qz
        r[5] = 2 * (qy * qz - q0 * qx)
        r[6] = 2 * (qz * qx - q0 * qy)
        r[7] = 2 * (qz * qy + q0 * qx)
        r[8] = q0 * q0 - qx * qx - qy * qy + qz * qz
    }

    companion object {
        const val MESH_LANDMARKS = 468
        private const val BASIS_CAPACITY = 64
        private const val EPSILON = 1e-9
        private const val NEAR = 1f
        private const val VERTICAL_FOV_DEGREES = 63f

        const val EYE_RADIUS_PER_WIDTH = 0.45
        const val MAX_EYE_YAW_DEGREES = 35.0
        const val MAX_EYE_PITCH_DEGREES = 30.0
        const val MAX_BLINK = 0.5f
        const val MAX_HEAD_YAW_FOR_FAR_EYE_DEGREES = 30.0

        const val RIGHT_EYE_OUTER = 33
        const val RIGHT_EYE_INNER = 133
        const val RIGHT_IRIS = 468
        const val LEFT_EYE_OUTER = 263
        const val LEFT_EYE_INNER = 362
        const val LEFT_IRIS = 473

        /**
         * Cyclic Jacobi on the symmetric 4x4 [a] (destroyed); eigenvectors as the columns of [v] (row-major).
         * Returns the column of the largest eigenvalue.
         */
        internal fun largestEigenvector(a: DoubleArray, v: DoubleArray): Int {
            for (i in 0 until 16) v[i] = if (i % 5 == 0) 1.0 else 0.0
            for (sweep in 0 until 64) {
                var off = 0.0
                var diagonal = 0.0
                for (p in 0 until 4) {
                    diagonal += a[p * 5] * a[p * 5]
                    for (q in p + 1 until 4) off += a[p * 4 + q] * a[p * 4 + q]
                }
                if (off <= 1e-28 * diagonal || off == 0.0) break
                for (p in 0 until 4) {
                    for (q in p + 1 until 4) {
                        val apq = a[p * 4 + q]
                        if (abs(apq) < 1e-300) continue
                        val theta = (a[q * 4 + q] - a[p * 4 + p]) / (2 * apq)
                        val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                        val c = 1 / sqrt(t * t + 1)
                        val s = t * c
                        for (k in 0 until 4) {
                            val akp = a[k * 4 + p]
                            val akq = a[k * 4 + q]
                            a[k * 4 + p] = c * akp - s * akq
                            a[k * 4 + q] = s * akp + c * akq
                        }
                        for (k in 0 until 4) {
                            val apk = a[p * 4 + k]
                            val aqk = a[q * 4 + k]
                            a[p * 4 + k] = c * apk - s * aqk
                            a[q * 4 + k] = s * apk + c * aqk
                        }
                        for (k in 0 until 4) {
                            val vkp = v[k * 4 + p]
                            val vkq = v[k * 4 + q]
                            v[k * 4 + p] = c * vkp - s * vkq
                            v[k * 4 + q] = s * vkp + c * vkq
                        }
                    }
                }
            }
            var best = 0
            for (i in 1 until 4) if (a[i * 5] > a[best * 5]) best = i
            return best
        }
    }
}
