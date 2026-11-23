package tech.fonia.face

import tech.fonia.face.FaceMeshIndices as I
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The quality gate with hysteresis (docs/v2/04 §3, "Poarta de calitate"): a problem must last
 * [QualitySpec.weakAfterMs] to turn the gate Weak, a clean stretch of [QualitySpec.goodAfterMs] turns it Good, and
 * no face for [QualitySpec.absentAfterMs] makes it Absent.
 */
class QualityGate(private val spec: QualitySpec = QualitySpec()) {
    var quality = FaceQuality.Absent
        private set
    var issue = QualityIssue.NoFace
        private set

    /** The instantaneous problem of the last frame, before hysteresis. */
    var rawIssue = QualityIssue.NoFace
        private set

    private val times = LongArray(10)
    private var timeCount = 0
    private var timeHead = 0
    private var lastPresent = -1L
    private var lastFrame = -1L
    private var problemSince = -1L
    private var problem = QualityIssue.None
    private var cleanSince = -1L

    fun reset() {
        quality = FaceQuality.Absent
        issue = QualityIssue.NoFace
        timeCount = 0
        lastPresent = -1L
        lastFrame = -1L
        problemSince = -1L
        cleanSince = -1L
    }

    fun update(f: FaceMeshFrame, m: MouthMeasures): FaceQuality {
        val t = f.tNanos
        val gap = if (lastFrame >= 0) t - lastFrame else 0L
        lastFrame = t
        times[timeHead] = t
        timeHead = (timeHead + 1) % times.size
        if (timeCount < times.size) timeCount++

        val present = f.isPresent && f.presence > spec.minPresence && m.valid
        if (!present) {
            rawIssue = QualityIssue.NoFace
            cleanSince = -1L
            if (lastPresent < 0 || t - lastPresent >= msToNanos(spec.absentAfterMs)) {
                quality = FaceQuality.Absent
                issue = QualityIssue.NoFace
            }
            return quality
        }
        lastPresent = t
        rawIssue = instantIssue(f, m, gap)
        if (rawIssue == QualityIssue.None) {
            problemSince = -1L
            if (cleanSince < 0) cleanSince = t
            if (quality != FaceQuality.Good && t - cleanSince >= msToNanos(spec.goodAfterMs)) {
                quality = FaceQuality.Good
                issue = QualityIssue.None
            } else if (quality == FaceQuality.Absent) {
                // Face found, not yet steady.
                quality = FaceQuality.Weak
                issue = QualityIssue.Unsteady
            }
        } else {
            cleanSince = -1L
            if (rawIssue != problem || problemSince < 0) {
                problem = rawIssue
                problemSince = t
            }
            if (quality == FaceQuality.Absent || t - problemSince >= msToNanos(spec.weakAfterMs)) {
                quality = FaceQuality.Weak
                issue = rawIssue
            }
        }
        return quality
    }

    private fun instantIssue(f: FaceMeshFrame, m: MouthMeasures, gapNanos: Long): QualityIssue {
        if (m.inFrame < spec.minInFrame) return QualityIssue.PartialFace
        if (m.mouthPxForGate < spec.minMouthPx) return QualityIssue.TooFar
        if (m.mouthPxForGate > spec.maxMouthShare * f.imageW) return QualityIssue.TooClose
        if (abs(f.headYaw) > spec.maxYawDeg || abs(f.headPitch) > spec.maxPitchDeg || abs(f.headRoll) > spec.maxRollDeg) {
            return QualityIssue.TurnToCamera
        }
        val luma = f.lumaMouth
        if (!luma.isNaN()) {
            if (luma < spec.minLumaMouth) return QualityIssue.TooDark
            if (luma > spec.maxLumaMouth || f.clippedShare > spec.maxClippedShare) return QualityIssue.TooBright
        }
        if (gapNanos > msToNanos(spec.maxFrameGapMs)) return QualityIssue.Unsteady
        if (timeCount >= times.size) {
            val oldest = times[timeHead]
            val newest = times[(timeHead + times.size - 1) % times.size]
            val span = (newest - oldest) / 1e9f
            if (span > 0f && (times.size - 1) / span < spec.minFps) return QualityIssue.Unsteady
        }
        return QualityIssue.None
    }
}

/**
 * The mouth-state engine (H2): [FaceMeshFrame] in, [MouthState] out, on the camera thread, allocation-free per frame
 * (the rest baseline is built once per session).
 *
 * 1. [MouthMeasures]: raw geometry ÷ eye distance;
 * 2. [QualityGate];
 * 3. [RestCalibrator] until the rest is taken (automatically at the start; [recalibrate] starts again);
 * 4. values relative to the rest (÷ W₀), One-Euro smoothing, rounding / spreading / closure (docs/v2/04 §3);
 * 5. the tongue heuristic ([TongueDetector]);
 * 6. confidences = gate × stability × the ceiling of each quantity.
 */
class MouthStateEngine(val spec: CameraSpec = CameraSpec.BUNDLED) {
    /** Iris diameter for the mm scale: 10.6 mm for 4–10-year-olds (default), 11.7 mm for adults, or from the coin. */
    @Volatile var irisMm: Float = spec.scale.irisMmChild

    @Volatile var scaleSource: ScaleSource = ScaleSource.Iris

    /** Whether the microphone hears speech now; the automatic rest avoids speaking frames. Any thread. */
    @Volatile var speaking: Boolean = false

    /**
     * The tongue heuristic (≈ a 64 × 64 colour pass per frame). Only the gymnastics warm-ups need it; a speech mirror
     * may switch it off to save CPU. Any thread.
     */
    @Volatile var tongueEnabled: Boolean = true

    val measures = MouthMeasures()
    val gate = QualityGate(spec.quality)
    val calibrator = RestCalibrator(spec.rest)
    val tongue = TongueDetector(spec.tongue)

    var baseline: RestBaseline = RestBaseline.default(spec.rest)
        private set

    @Volatile private var recalibrateRequested = false

    private val sm = spec.smoothing
    private val fWidth = OneEuro(sm.minCutoffHz, sm.beta, sm.dCutoffHz)
    private val fAperture = OneEuro(sm.minCutoffHz, sm.beta, sm.dCutoffHz)
    private val fJaw = OneEuro(sm.minCutoffHz, sm.beta, sm.dCutoffHz)
    private val fShape = OneEuro(sm.minCutoffHz, sm.beta, sm.dCutoffHz)
    private val fSmile = OneEuro(sm.minCutoffHz, sm.beta, sm.dCutoffHz)
    private val fProtrusion = OneEuro(sm.minCutoffHz, sm.beta, sm.dCutoffHz)

    private val irisRatios = FloatArray(spec.scale.medianFrames)
    private val irisScratch = FloatArray(spec.scale.medianFrames)
    private var irisCount = 0
    private var irisHead = 0

    private val recentAperture = FloatArray(5)
    private var recentCount = 0
    private var recentHead = 0
    private var closedFrames = 0

    /** Starts a new rest (e.g. at the start of each mirror session, or when the adult asks). Any thread. */
    fun recalibrate() {
        recalibrateRequested = true
    }

    /** Forgets everything (a new screen). Camera thread. */
    fun reset() {
        gate.reset()
        calibrator.reset()
        tongue.reset()
        baseline = RestBaseline.default(spec.rest)
        resetFilters()
        irisCount = 0
        recentCount = 0
        closedFrames = 0
    }

    private fun resetFilters() {
        fWidth.reset(); fAperture.reset(); fJaw.reset(); fShape.reset(); fSmile.reset(); fProtrusion.reset()
    }

    fun process(f: FaceMeshFrame, out: MouthState): MouthState {
        if (recalibrateRequested) {
            recalibrateRequested = false
            calibrator.reset()
            tongue.reset()
            baseline = RestBaseline.default(spec.rest)
        }
        val m = measures
        m.measure(f)
        val q = gate.update(f, m)
        out.tNanos = f.tNanos
        out.quality = q
        out.issue = gate.issue
        out.scaleSource = scaleSource
        out.tongueModelScore = f.tongueModelScore
        if (!m.valid) {
            out.confGeom = 0f
            out.confShape = 0f
            out.confTongue = 0f
            out.tongue = if (tongue.calibrated) TongueState.None else TongueState.Unknown
            out.rest = baseline.source
            if (q == FaceQuality.Absent) resetFilters()
            return out
        }
        val good = q == FaceQuality.Good

        if (!baseline.isCalibrated) {
            val taken = calibrator.add(f, m, good, speaking)
            if (taken != null) {
                baseline = taken
                resetFilters()
                if (calibrator.hasQuietFrame) {
                    val quiet = calibrator.quietFrame
                    // The tongue colours are learnt on the calmest rest frame, with that frame's own geometry.
                    val qm = MouthMeasures()
                    qm.measure(quiet)
                    tongue.calibrate(quiet, qm, baseline)
                }
            }
        }
        val b = baseline

        // Millimetre scale: median of iris / eye-distance ratios.
        if (m.irisPx > 0f) {
            irisRatios[irisHead] = m.irisPx / m.unitPx
            irisHead = (irisHead + 1) % irisRatios.size
            if (irisCount < irisRatios.size) irisCount++
        }
        val mmPerPx = if (irisCount > 0) irisMm / (medianIris() * m.unitPx) else Float.NaN

        val t = f.tNanos
        val bs = f.blendshapes
        val widthN = fWidth.filter(m.widthRel / b.widthRel, t)
        val apertureW0 = max(0f, fAperture.filter(m.apertureRel / b.widthRel, t))
        val jawW0 = fJaw.filter((m.jawRel - b.jawRel) / b.widthRel, t)
        val shapeRaw = max(bs[MeshBlendshapes.MOUTH_PUCKER] - b.pucker, bs[MeshBlendshapes.MOUTH_FUNNEL] - b.funnel)
        val shape = fShape.filter(shapeRaw, t)
        val smileScore = fSmile.filter((bs[MeshBlendshapes.MOUTH_SMILE_LEFT] + bs[MeshBlendshapes.MOUTH_SMILE_RIGHT]) * 0.5f, t)
        val protrusion = fProtrusion.filter(m.protrusionRel - b.protrusionRel, t)

        val ms = spec.measure
        val openGate = 1f - MouthMeasures.clamp01((apertureW0 - ms.roundOpenGateStart) / ms.roundOpenGateSpan)
        val geomRound = MouthMeasures.clamp01((1f - widthN) / ms.roundWidthSpan) * openGate
        val shapeRound = MouthMeasures.clamp01(shape / ms.roundShapeSpan)
        out.round = ms.roundGeomWeight * geomRound + (1f - ms.roundGeomWeight) * shapeRound
        out.spread = MouthMeasures.clamp01((widthN - 1f) / ms.spreadSpan)
        out.width = widthN
        out.aperture = apertureW0
        out.jaw = jawW0
        out.open = MouthMeasures.clamp01(apertureW0 / ms.openFull)
        out.protrusion = protrusion
        out.symmetry = m.symmetry
        out.smile = out.spread >= ms.smileSpread && smileScore - b.smile >= ms.smileShape
        out.jawOpenScore = bs[MeshBlendshapes.JAW_OPEN]
        out.puckerScore = bs[MeshBlendshapes.MOUTH_PUCKER]
        out.funnelScore = bs[MeshBlendshapes.MOUTH_FUNNEL]
        out.smileScore = smileScore
        out.rest = b.source

        val w0Px = b.widthRel * m.unitPx // yaw-corrected rest width, px
        if (!mmPerPx.isNaN()) {
            out.widthMm = widthN * w0Px * mmPerPx
            out.apertureMm = apertureW0 * w0Px * mmPerPx
            out.jawOpenMm = jawW0 * w0Px * mmPerPx
            out.innerAreaMm2 = m.innerAreaRel * m.unitPx * m.unitPx * mmPerPx * mmPerPx
        }
        val w0Mm = w0Px * mmPerPx
        val closedLimit = if (w0Mm > 0f) max(ms.closedMm / w0Mm, ms.closedApertureW0) else ms.closedApertureW0
        val areaW0 = m.innerAreaRel / (b.widthRel * b.widthRel)
        closedFrames = if (apertureW0 <= closedLimit && areaW0 <= ms.closedAreaW0) closedFrames + 1 else 0
        out.lipsClosed = closedFrames >= 2

        // Stability: std-dev of the aperture over the last 5 frames.
        recentAperture[recentHead] = apertureW0
        recentHead = (recentHead + 1) % recentAperture.size
        if (recentCount < recentAperture.size) recentCount++
        var mean = 0f
        for (k in 0 until recentCount) mean += recentAperture[k]
        mean /= recentCount
        var v = 0f
        for (k in 0 until recentCount) v += (recentAperture[k] - mean) * (recentAperture[k] - mean)
        val sd = sqrt(v / recentCount)
        val stability = 1f / (1f + sd / ms.stabilityRef)
        val gateFactor = if (good) 1f else 0f
        val restFactor = if (b.isCalibrated) 1f else 0.6f
        out.confGeom = gateFactor * stability * 0.9f
        out.confShape = gateFactor * stability * 0.7f * restFactor

        if (tongueEnabled) {
            val bandRatio = if (b.lowerBandRel > 0f) m.lowerBandRel / b.lowerBandRel else 1f
            out.tongue = tongue.update(f, m, b, out.apertureMm, jawW0, good, bandRatio)
            out.confTongue = gateFactor * tongue.confidence
        } else {
            out.tongue = TongueState.Unknown
            out.confTongue = 0f
        }
        out.tongueColourDelta = tongue.delta[TongueDetector.Z_OUT]
        return out
    }

    private fun medianIris(): Float {
        irisRatios.copyInto(irisScratch, 0, 0, irisCount)
        java.util.Arrays.sort(irisScratch, 0, irisCount)
        return irisScratch[irisCount / 2]
    }

    /**
     * The target lip rings for [pose] on the current face [f] (docs/v2/04 §4), in normalised image coordinates
     * (unmirrored), 20 × (x, y) each. Built from this child's rest rings: `u' = u·wide`, `v' = v·(1 + 0.25·round) ±
     * (open/2)·cos²(πu)`. Calibrated, the target hangs from the nose base (it follows the head, not the lips);
     * before the rest it sits between the current corners. False when there is no face.
     */
    fun targetRings(f: FaceMeshFrame, pose: LipPose, outerOut: FloatArray, innerOut: FloatArray): Boolean {
        val m = measures
        if (!m.valid || !f.isPresent) return false
        val b = baseline
        val w = f.imageW.toFloat()
        val h = f.imageH.toFloat()
        val ax = m.axisX
        val ay = m.axisY
        val upX = ay
        val upY = -ax
        val ox: Float
        val oy: Float
        if (b.isCalibrated) {
            val cosPitch = max(cos(Math.toRadians(f.headPitch.toDouble())).toFloat(), 0.64f)
            val drop = b.mouthDropRel * m.unitPx * cosPitch
            val side = b.mouthSideRel * m.eyePx
            val nbx = f.anchors[I.NOSE_BASE * 3] * w
            val nby = f.anchors[I.NOSE_BASE * 3 + 1] * h
            ox = nbx - upX * drop + ax * side
            oy = nby - upY * drop + ay * side
        } else {
            val o = f.lipsOuter
            ox = (o[I.CORNER_RIGHT * 3] + o[I.CORNER_LEFT * 3]) * 0.5f * w
            oy = (o[I.CORNER_RIGHT * 3 + 1] + o[I.CORNER_LEFT * 3 + 1]) * 0.5f * h
        }
        // Before the rest: scaled to the current mouth, not to the population fall-back (which can be 30 % off).
        val w0 = if (b.isCalibrated) b.widthRel * m.eyePx else m.widthPx
        place(b.ringOuter, pose, ox, oy, ax, ay, upX, upY, w0, w, h, outerOut)
        place(b.ringInner, pose, ox, oy, ax, ay, upX, upY, w0, w, h, innerOut)
        return true
    }

    private fun place(
        ring: FloatArray, pose: LipPose, ox: Float, oy: Float, ax: Float, ay: Float, upX: Float, upY: Float,
        w0: Float, w: Float, h: Float, out: FloatArray,
    ) {
        for (k in 0 until I.RING) {
            val u = ring[k * 2]
            val v = ring[k * 2 + 1]
            val c = cos(Math.PI * u).toFloat()
            val lift = pose.open * 0.5f * c * c
            val sign = when {
                k == I.CORNER_RIGHT || k == I.CORNER_LEFT -> 0f
                k < I.CORNER_LEFT -> 1f
                else -> -1f
            }
            val u2 = u * pose.wide
            val v2 = v * (1f + 0.25f * pose.round) + sign * lift
            out[k * 2] = (ox + (u2 * ax + v2 * upX) * w0) / w
            out[k * 2 + 1] = (oy + (u2 * ay + v2 * upY) * w0) / h
        }
    }
}
