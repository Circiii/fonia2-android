package tech.fonia.speaking.coach

import tech.fonia.face.FaceMeshFrame
import tech.fonia.face.FaceMeshIndices
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max

/**
 * One camera frame as the coach needs it. Built from the face model's [FaceMeshFrame] with [fill], or written by a
 * scripted track (fixtures). Mutable and reused: the camera thread refills one instance per frame.
 *
 * Conventions follow [FaceMeshFrame]: the upright, unmirrored image, yaw positive towards the image's right, pitch up.
 */
class FaceSample {
    var tSeconds: Double = 0.0
    var present: Boolean = false
    var yawDeg: Double = 0.0
    var pitchDeg: Double = 0.0
    var rollDeg: Double = 0.0

    /** The nose bridge, in eye distances (so a sway reads the same near or far from the phone). */
    var posX: Double = 0.0
    var posY: Double = 0.0

    /** Irises across the eyes: 0 = in the middle, +1 = at the image-right corner (mean of both eyes). */
    var eyeH: Double = 0.0

    /** eyeLookUp − eyeLookDown, mean of both eyes (blendshapes, 0…1 each). */
    var eyeV: Double = 0.0

    /** Brow raise (blendshapes) and smile-muscle activity (blendshapes); movement only, never a feeling. */
    var brow: Double = 0.0
    var smile: Double = 0.0

    /** For the quality gate: eye distance ÷ picture width, the nose bridge in 0…1 of the picture, face brightness 0…255 (NaN unknown). */
    var eyeSpan: Double = Double.NaN
    var centerX: Double = Double.NaN
    var centerY: Double = Double.NaN
    var luma: Double = Double.NaN

    fun set(o: FaceSample) {
        tSeconds = o.tSeconds; present = o.present; yawDeg = o.yawDeg; pitchDeg = o.pitchDeg; rollDeg = o.rollDeg
        posX = o.posX; posY = o.posY; eyeH = o.eyeH; eyeV = o.eyeV; brow = o.brow; smile = o.smile
        eyeSpan = o.eyeSpan; centerX = o.centerX; centerY = o.centerY; luma = o.luma
    }

    /** Reads [frame] (landmarks, irises, the 52 scores, pose); [tSeconds] is the coach's clock. */
    fun fill(frame: FaceMeshFrame, tSeconds: Double) {
        this.tSeconds = tSeconds
        present = frame.isPresent
        if (!present) return
        val w = frame.imageW.coerceAtLeast(1).toDouble()
        val h = frame.imageH.coerceAtLeast(1).toDouble()
        val a = frame.anchors
        fun ax(i: Int) = a[i * 3] * w
        fun ay(i: Int) = a[i * 3 + 1] * h
        val rOutX = ax(FaceMeshIndices.EYE_RIGHT_OUTER)
        val rInX = ax(FaceMeshIndices.EYE_RIGHT_INNER)
        val lOutX = ax(FaceMeshIndices.EYE_LEFT_OUTER)
        val lInX = ax(FaceMeshIndices.EYE_LEFT_INNER)
        val ercx = (rOutX + rInX) / 2
        val ercy = (ay(FaceMeshIndices.EYE_RIGHT_OUTER) + ay(FaceMeshIndices.EYE_RIGHT_INNER)) / 2
        val elcx = (lOutX + lInX) / 2
        val elcy = (ay(FaceMeshIndices.EYE_LEFT_OUTER) + ay(FaceMeshIndices.EYE_LEFT_INNER)) / 2
        val eye = hypot(elcx - ercx, elcy - ercy).coerceAtLeast(1.0)
        posX = ax(FaceMeshIndices.NOSE_BRIDGE) / eye
        eyeSpan = eye / w
        centerX = ax(FaceMeshIndices.NOSE_BRIDGE) / w
        centerY = ay(FaceMeshIndices.NOSE_BRIDGE) / h
        luma = frame.lumaFace.toDouble()
        posY = ay(FaceMeshIndices.NOSE_BRIDGE) / eye
        yawDeg = frame.headYaw.toDouble()
        pitchDeg = frame.headPitch.toDouble()
        rollDeg = frame.headRoll.toDouble()
        // The person's right eye is on the image's left: corners 33 (outer, left) → 133 (inner); left eye 362 → 263.
        val ir = frame.irises
        val rIris = ir[FaceMeshIndices.IRIS_RIGHT_CENTER * 3] * w
        val lIris = ir[FaceMeshIndices.IRIS_LEFT_CENTER * 3] * w
        val tR = ratio(rIris, rOutX, rInX)
        val tL = ratio(lIris, lInX, lOutX)
        eyeH = when {
            !tR.isNaN() && !tL.isNaN() -> (tR + tL) - 1.0 // ((tR + tL) / 2 − 0.5) × 2
            !tR.isNaN() -> (tR - 0.5) * 2
            !tL.isNaN() -> (tL - 0.5) * 2
            else -> 0.0
        }
        val b = frame.blendshapes
        eyeV = ((b[B_LOOK_UP_L] + b[B_LOOK_UP_R]) - (b[B_LOOK_DOWN_L] + b[B_LOOK_DOWN_R])) / 2.0
        brow = b[B_BROW_INNER_UP] * 0.5 + (b[B_BROW_OUTER_UP_L] + b[B_BROW_OUTER_UP_R]) * 0.25
        smile = (b[B_SMILE_L] + b[B_SMILE_R]) / 2.0
    }

    private fun ratio(x: Double, from: Double, to: Double): Double {
        val span = to - from
        if (abs(span) < 1e-6) return Double.NaN
        val t = (x - from) / span
        return if (t < -0.2 || t > 1.2) Double.NaN else t
    }

    companion object {
        // Indices in MeshBlendshapes.NAMES.
        const val B_BROW_INNER_UP = 3
        const val B_BROW_OUTER_UP_L = 4
        const val B_BROW_OUTER_UP_R = 5
        const val B_LOOK_DOWN_L = 11
        const val B_LOOK_DOWN_R = 12
        const val B_LOOK_UP_L = 17
        const val B_LOOK_UP_R = 18
        const val B_SMILE_L = 44
        const val B_SMILE_R = 45
    }
}

/**
 * What the camera says about delivery, live: eye contact with the lens, head movement (sway, bob), and how much the
 * face moves (brows, smile muscles, nods). It measures movement and variation only; it never names a feeling.
 *
 * [onFrame] on ONE thread (the camera thread); readings are published to volatile fields for any thread. The lens
 * baseline: call [startBaseline] when he is asked to look at the lens (the countdown) and [endBaseline] after it.
 */
class FaceCoach(private val spec: CoachSpec = CoachSpec.v1, private val calibration: GazeCalibration? = null) {
    private val gaze = spec.gaze
    private val motion = spec.motion
    private val faceSpec = spec.face

    private val ring = 256
    private val t = DoubleArray(ring)
    private val present = BooleanArray(ring)
    private val looking = BooleanArray(ring)
    private val known = BooleanArray(ring)
    private val px = DoubleArray(ring)
    private val py = DoubleArray(ring)
    private val yaw = DoubleArray(ring)
    private val pitch = DoubleArray(ring)
    private val roll = DoubleArray(ring)
    private val brow = DoubleArray(ring)
    private val smile = DoubleArray(ring)
    private var n = 0L

    private var gx = Double.NaN
    private var gy = Double.NaN
    private var lastT = Double.NaN
    private var isLooking = false

    private var collecting = false
    private val baseX = DoubleArray(ring)
    private val baseY = DoubleArray(ring)
    private var baseN = 0
    // With his own calibration: his centre and his tolerances, and the countdown baseline is not needed.
    private var bx = calibration?.centreX ?: 0.0
    private var by = calibration?.centreY ?: 0.0
    private var tolScale = if (calibration != null) 1.0 else gaze.fallbackToleranceScale
    private val tolUp = calibration?.upDeg ?: gaze.tolerancePitchUpDeg
    private val tolDown = calibration?.downDeg ?: gaze.tolerancePitchDownDeg

    @Volatile var baselineTaken: Boolean = false
        private set

    /** Seconds of the last frame with a face, and of the last frame at all. */
    @Volatile var lastFaceSeconds: Double = Double.NEGATIVE_INFINITY
        private set
    @Volatile var lastFrameSeconds: Double = Double.NEGATIVE_INFINITY
        private set

    @Volatile var lookingNow: Boolean = false
        private set

    /** Share of the last [CoachSpec.Gaze.windowSeconds] spent looking away (no face counts as away). */
    @Volatile var awayFraction: Double = Double.NaN
        private set

    /** Distance of the gaze from the lens in tolerances (1 = the edge). */
    @Volatile var gazeDeviation: Double = Double.NaN
        private set

    /** Movement index: 1 = the tolerance of sway or turn; NaN = no face. */
    @Volatile var motionIndex: Double = Double.NaN
        private set

    /** 0…1 facial movement (brows, smile muscles, nods); NaN = no face. */
    @Volatile var faceActivity01: Double = Double.NaN
        private set

    /** What is wrong with the picture right now (null = fine); any thread. */
    @Volatile var issueNow: InputIssue? = null
        private set

    /** Frames seen and frames measured, for the report's coverage. */
    @Volatile var framesTotal: Long = 0
        private set
    @Volatile var framesUsable: Long = 0
        private set

    private fun issueOf(s: FaceSample): InputIssue? {
        val q = spec.quality
        return when {
            !s.present -> InputIssue.NoFace
            !s.luma.isNaN() && s.luma < q.minLuma -> InputIssue.Dark
            !s.eyeSpan.isNaN() && s.eyeSpan < q.minEyeSpan -> InputIssue.TooFar
            !s.eyeSpan.isNaN() && s.eyeSpan > q.maxEyeSpan -> InputIssue.TooClose
            !s.centerX.isNaN() && (s.centerX < q.edgeMargin || s.centerX > 1 - q.edgeMargin || s.centerY < q.edgeMargin || s.centerY > 1 - q.edgeMargin) -> InputIssue.OffCentre
            else -> null
        }
    }

    fun startBaseline() {
        baseN = 0
        collecting = true
    }

    /** Ends the baseline: the median gaze while he looked at the lens. Too few frames keep the default (straight ahead). */
    fun endBaseline() {
        collecting = false
        val need = 6
        if (baseN >= need && calibration == null) {
            bx = median(baseX, baseN).coerceIn(-gaze.baselineClampDeg, gaze.baselineClampDeg)
            by = median(baseY, baseN).coerceIn(-gaze.baselineClampDeg, gaze.baselineClampDeg)
            tolScale = 1.0
            baselineTaken = true
        }
    }

    fun onFrame(s: FaceSample) {
        val now = s.tSeconds
        lastFrameSeconds = now
        val k = (n % ring).toInt()
        t[k] = now
        framesTotal++
        val issue = issueOf(s)
        issueNow = issue
        // A frame that fails the gate is not measured: it leaves no reading, rather than a wrong one.
        val usable = s.present && issue == null
        if (usable) framesUsable++
        present[k] = usable
        known[k] = usable
        if (usable) {
            lastFaceSeconds = now
            val rawX = rawGazeX(s, spec)
            val rawY = rawGazeY(s, spec)
            val dt = if (lastT.isNaN()) 1.0 else (now - lastT).coerceIn(0.0, 1.0)
            val a = 1.0 - exp(-dt / gaze.smoothingSeconds)
            if (gx.isNaN()) { gx = rawX; gy = rawY } else { gx += (rawX - gx) * a; gy += (rawY - gy) * a }
            lastT = now
            if (collecting && baseN < ring) {
                baseX[baseN] = rawX
                baseY[baseN] = rawY
                baseN++
            }
            val dy = gy - by
            val tolY = if (dy > 0) tolUp else tolDown
            val d = hypot((gx - bx) / (gaze.toleranceYawDeg * tolScale), dy / (tolY * tolScale))
            gazeDeviation = d
            isLooking = if (isLooking) d < gaze.leaveAbove else d < gaze.enterBelow
            px[k] = s.posX; py[k] = s.posY; yaw[k] = s.yawDeg; pitch[k] = s.pitchDeg; roll[k] = s.rollDeg
            brow[k] = s.brow; smile[k] = s.smile
        } else {
            isLooking = false
            lastT = Double.NaN
            gx = Double.NaN
        }
        looking[k] = isLooking
        lookingNow = isLooking
        n++
        publish(now)
    }

    private fun publish(now: Double) {
        // Gaze window.
        var total = 0
        var away = 0
        forEachSince(now - gaze.windowSeconds) { k ->
            if (known[k]) {
                total++
                if (!looking[k]) away++
            }
        }
        awayFraction = if (total >= 3) away.toDouble() / total else Double.NaN

        // Motion window: spread of position and angles over the face frames.
        val mx = Stats(); val my = Stats(); val myaw = Stats(); val mpitch = Stats(); val mroll = Stats()
        forEachSince(now - motion.windowSeconds) { k ->
            if (present[k]) { mx.add(px[k]); my.add(py[k]); myaw.add(yaw[k]); mpitch.add(pitch[k]); mroll.add(roll[k]) }
        }
        motionIndex = if (mx.n >= 5) {
            val pos = hypot(mx.sd, my.sd) / motion.positionToleranceEyes
            val ang = max(myaw.sd, max(mpitch.sd, mroll.sd)) / motion.angleToleranceDeg
            max(pos, ang)
        } else {
            Double.NaN
        }

        // Facial movement window.
        val sb = Stats(); val ss = Stats(); val sp = Stats()
        forEachSince(now - faceSpec.windowSeconds) { k ->
            if (present[k]) { sb.add(brow[k]); ss.add(smile[k]); sp.add(pitch[k]) }
        }
        faceActivity01 = if (sb.n >= 5) {
            val activity = faceSpec.browWeight * sb.sd + faceSpec.smileWeight * ss.sd + faceSpec.nodWeight * sp.sd
            ((activity - faceSpec.activityLow) / (faceSpec.activityHigh - faceSpec.activityLow)).coerceIn(0.0, 1.0)
        } else {
            Double.NaN
        }
    }

    private inline fun forEachSince(from: Double, body: (Int) -> Unit) {
        var i = n - 1
        val stop = max(0L, n - ring)
        while (i >= stop) {
            val k = (i % ring).toInt()
            if (t[k] < from) break
            body(k)
            i--
        }
    }

    private fun median(values: DoubleArray, count: Int): Double {
        val copy = values.copyOf(count)
        copy.sort()
        return copy[count / 2]
    }

    /** Running mean and SD (Welford), on the stack of the caller. */
    private class Stats {
        var n = 0
        private var mean = 0.0
        private var m2 = 0.0
        fun add(x: Double) {
            n++
            val d = x - mean
            mean += d / n
            m2 += d * (x - mean)
        }
        val sd: Double get() = if (n < 2) 0.0 else safeSqrt(m2 / (n - 1))
    }

    companion object {
        /** Where he looks, sideways: the head's turn plus the eyes' turn inside it (degrees; 0 = into the camera). */
        fun rawGazeX(s: FaceSample, spec: CoachSpec): Double = s.yawDeg + spec.gaze.irisGainDeg * s.eyeH

        /** The same, up (+) and down (−). */
        fun rawGazeY(s: FaceSample, spec: CoachSpec): Double = s.pitchDeg + spec.gaze.lookGainDeg * s.eyeV + spec.gaze.verticalOffsetDeg
    }

    /** True when frames arrive (the camera runs); false when it never started or stopped. */
    fun hasCamera(nowSeconds: Double): Boolean = nowSeconds - lastFrameSeconds < 1.0

    fun faceVisible(nowSeconds: Double): Boolean = nowSeconds - lastFaceSeconds < 0.6

}

/** Why the coach cannot measure right now, with the few words that fix it. Never a judgement of him. */
enum class InputIssue(val key: String, val hint: String) {
    NoFace("noFace", "Nu te văd"),
    Dark("dark", "Mai multă lumină"),
    TooFar("far", "Mai aproape"),
    TooClose("close", "Puțin mai departe"),
    OffCentre("offCentre", "Vino în mijloc"),
    Noisy("noisy", "E zgomot în jur"),
}
