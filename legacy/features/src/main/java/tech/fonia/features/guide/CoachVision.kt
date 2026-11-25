package tech.fonia.features.guide

import tech.fonia.face.AbstainReason
import tech.fonia.face.CameraAdvice
import tech.fonia.face.EvidenceStatus
import tech.fonia.face.FaceQuality
import tech.fonia.face.MirrorSnapshot
import tech.fonia.face.QualityIssue
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * The one correction the self-view shows at a time, drawn over the child's own mouth (or around the face) so a
 * five-year-old understands it without reading: [words] are at most two, [glyph] names the big icon.
 */
enum class Correction(val words: String, val glyph: String) {
    None("", ""),
    Calibrate("Față liniștită", "calm"),
    NoFace("Unde ești?", "face"),
    WholeMouth("Toată gura", "face"),
    LookHere("Privește aici", "face"),
    Closer("Mai aproape", "closer"),
    Farther("Mai departe", "farther"),
    Light("Lumină", "sun"),
    LessLight("Mai puțină", "sun"),
    Smile("Zâmbește", "smile"),
    LessSmile("Mai puțin", "lessSmile"),
    Round("Buze rotunde", "round"),
    Relax("Relaxează", "smile"),
    Open("Deschide", "open"),
    Close("Închide", "close"),
    Part("Deschide", "open"),
    Match("Bravo!", "star"),
}

/** What the camera asks for in this snapshot, before any smoothing. */
fun correctionFor(snap: MirrorSnapshot): Correction {
    if (!snap.hasFace) return Correction.NoFace
    val state = snap.state
    if (state.quality != FaceQuality.Good) {
        return when (state.issue) {
            QualityIssue.NoFace -> Correction.NoFace
            QualityIssue.PartialFace -> Correction.WholeMouth
            QualityIssue.TooFar -> Correction.Closer
            QualityIssue.TooClose -> Correction.Farther
            QualityIssue.TurnToCamera -> Correction.LookHere
            QualityIssue.TooDark -> Correction.Light
            QualityIssue.TooBright -> Correction.LessLight
            QualityIssue.Unsteady, QualityIssue.None -> Correction.None
        }
    }
    val e = snap.evidence
    if (e.status == EvidenceStatus.Matches) return Correction.Match
    if (e.status == EvidenceStatus.Abstain && e.abstain == AbstainReason.Calibrating) return Correction.Calibrate
    if (e.status != EvidenceStatus.Adjust) return Correction.None
    return when (e.advice) {
        CameraAdvice.SPREAD_LIPS -> Correction.Smile
        CameraAdvice.LESS_SPREAD -> Correction.LessSmile
        CameraAdvice.ROUND_LIPS -> Correction.Round
        CameraAdvice.LESS_ROUND -> Correction.Relax
        CameraAdvice.OPEN_MORE -> Correction.Open
        CameraAdvice.OPEN_LESS -> Correction.Close
        CameraAdvice.PART_LIPS -> Correction.Part
        else -> Correction.None
    }
}

/**
 * Hysteresis for the shown correction: a new candidate must last [switchNanos] before it replaces the shown one, and a
 * shown correction stays at least [minShowNanos]; [Correction.Match] comes in faster (the matcher already has its own
 * hysteresis) so the praise is not late. No flicker between two hints.
 */
class CorrectionPicker(
    private val switchNanos: Long = 450_000_000L,
    private val minShowNanos: Long = 900_000_000L,
    private val matchNanos: Long = 120_000_000L,
) {
    var shown: Correction = Correction.None
        private set
    private var candidate: Correction = Correction.None
    private var candidateSince = 0L
    private var shownSince = 0L

    fun update(next: Correction, now: Long): Correction {
        if (next != candidate) {
            candidate = next
            candidateSince = now
        }
        if (candidate != shown) {
            val wait = if (candidate == Correction.Match) matchNanos else switchNanos
            val shownLongEnough = candidate == Correction.Match || shown == Correction.None || shown == Correction.Match || now - shownSince >= minShowNanos
            if (now - candidateSince >= wait && shownLongEnough) {
                shown = candidate
                shownSince = now
            }
        }
        return shown
    }

    fun reset() {
        shown = Correction.None
        candidate = Correction.None
    }
}

/**
 * How long the pose has been held: [fraction] fills over [requiredNanos] while matched and drains twice as fast when not;
 * [completed] turns true once per held pose (it re-arms after the fraction has dropped back below a half).
 */
class HoldTimer(private val requiredNanos: Long = 1_000_000_000L) {
    var fraction = 0f
        private set
    private var armed = true
    private var last = 0L

    /** Returns true on the frame the hold completes. */
    fun update(matched: Boolean, now: Long): Boolean {
        val dt = if (last == 0L) 0L else (now - last).coerceIn(0L, 100_000_000L)
        last = now
        val step = dt.toFloat() / requiredNanos
        fraction = (if (matched) fraction + step else fraction - 2f * step).coerceIn(0f, 1f)
        if (fraction < 0.5f) armed = true
        if (armed && fraction >= 1f) {
            armed = false
            return true
        }
        return false
    }

    fun reset() {
        fraction = 0f
        armed = true
        last = 0L
    }
}

/**
 * The self-view's auto-framing: a zoom [z] and a translation ([tx], [ty]) applied to the mirrored preview (transform
 * origin at the top left), so the mouth is big and sits a little below the middle. Damped (exponential, [zoomTau] and
 * [panTau] seconds), with a dead band so a still face gives a still picture, the zoom clamped to [1, [maxZoom]] so it
 * never gets dizzy, the picture always covering the pane, and back to the whole frame after [lostNanos] without a face.
 */
class CropFollower(
    private val maxZoom: Float = 1.35f,
    private val mouthShare: Float = 0.24f,
    private val zoomTau: Float = 0.55f,
    private val panTau: Float = 0.35f,
    private val lostNanos: Long = 900_000_000L,
) {
    var z = 1f
        private set
    var tx = 0f
        private set
    var ty = 0f
        private set
    private var targetZ = 1f
    private var lastSeen = 0L
    private var last = 0L

    /**
     * One frame. [mouthCx], [mouthCy], [mouthW] are in pane pixels before the zoom ([hasMouth] false when there is no
     * face); [w] × [h] is the pane.
     */
    fun update(hasMouth: Boolean, mouthCx: Float, mouthCy: Float, mouthW: Float, w: Float, h: Float, now: Long, reduceMotion: Boolean) {
        val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
        last = now
        if (w <= 0f || h <= 0f) return
        var wantZ: Float
        var wantTx: Float
        var wantTy: Float
        if (hasMouth && mouthW > 1f) {
            lastSeen = now
            wantZ = (mouthShare * w / mouthW).coerceIn(1f, maxZoom)
            // Dead band: small changes of the mouth's width (speaking, smiling) do not breathe the zoom.
            if (abs(wantZ - targetZ) / targetZ < 0.12f) wantZ = targetZ else targetZ = wantZ
            wantTx = w * 0.5f - mouthCx * z
            wantTy = h * 0.52f - mouthCy * z
        } else if (now - lastSeen > lostNanos) {
            targetZ = 1f
            wantZ = 1f
            wantTx = 0f
            wantTy = 0f
        } else {
            return
        }
        val kz = if (reduceMotion) 1f else 1f - exp(-dt / zoomTau)
        val kp = if (reduceMotion) 1f else 1f - exp(-dt / panTau)
        z += (wantZ - z) * kz
        tx += (wantTx - tx) * kp
        ty += (wantTy - ty) * kp
        // The zoomed picture must still cover the pane.
        tx = tx.coerceIn(min(0f, w - w * z), 0f)
        ty = ty.coerceIn(min(0f, h - h * z), 0f)
    }

    fun toScreenX(x: Float) = x * z + tx
    fun toScreenY(y: Float) = y * z + ty
}

/**
 * Maps the snapshot's lip ring (normalised, unmirrored image coordinates) into pane pixels the way the mirrored
 * FILL_CENTER preview shows it, into [out] (20 × (x, y)); false when there is nothing to map.
 */
fun lipsToPane(snap: MirrorSnapshot, w: Float, h: Float, out: FloatArray): Boolean {
    if (!snap.hasFace || snap.imageW <= 0 || snap.imageH <= 0 || w <= 0f || h <= 0f) return false
    val iw = snap.imageW.toFloat()
    val ih = snap.imageH.toFloat()
    val scale = max(w / iw, h / ih)
    val dx = (w - iw * scale) / 2f
    val dy = (h - ih * scale) / 2f
    for (k in 0 until 20) {
        out[k * 2] = (1f - snap.lipsOuter[k * 2]) * iw * scale + dx
        out[k * 2 + 1] = snap.lipsOuter[k * 2 + 1] * ih * scale + dy
    }
    return true
}
