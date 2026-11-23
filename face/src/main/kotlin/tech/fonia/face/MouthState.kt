package tech.fonia.face

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

/** The quality gate's verdict (docs/v2/04 §3). With anything but [Good] the camera abstains. */
enum class FaceQuality { Good, Weak, Absent }

/** Why the gate is not [FaceQuality.Good]; a key the screen turns into a short hint. */
enum class QualityIssue(val key: String) {
    None("none"),

    /** „Unde ești? Vino în oglindă” */
    NoFace("noFace"),

    /** Part of the mouth or face is outside the picture. */
    PartialFace("partialFace"),

    /** „Vino mai aproape” */
    TooFar("tooFar"),

    /** „Puțin mai departe” */
    TooClose("tooClose"),

    /** „Uită-te la Foni” */
    TurnToCamera("turnToCamera"),

    /** „Mai multă lumină pe față” */
    TooDark("tooDark"),

    /** Burnt-out light on the face. */
    TooBright("tooBright"),

    /** Too few frames per second, or a gap: silent abstention. */
    Unsteady("unsteady"),
}

/** Where the millimetres come from. */
enum class ScaleSource { Iris, Coin }

/** Tongue outside the mouth (gymnastics only; the camera never sees the tongue during speech). */
enum class TongueState { None, Out, Left, Right, Up, Down, Unknown }

/** How the rest baseline was obtained. */
enum class RestSource {
    /** Population fall-backs: no rest taken yet. */
    Default,

    /** "Fă o față liniștită": ≥ 20 quiet, closed-mouth frames with steady width. */
    Calibrated,

    /** Taken automatically from the quietest frames after the rest window ran out. */
    Auto,
}

/**
 * The mouth, one analysed frame (docs/v2/04 §3, "Ieșirea"), smoothed. Mutable and reused: the engine refills it each
 * frame; [MouthLevels] hands copies to readers. Lengths ÷ W₀ (the child's rest mouth width) are what the checks use;
 * millimetres are for reports.
 */
class MouthState {
    var tNanos: Long = 0
    var quality: FaceQuality = FaceQuality.Absent
    var issue: QualityIssue = QualityIssue.NoFace
    var scaleSource: ScaleSource = ScaleSource.Iris
    var rest: RestSource = RestSource.Default

    var apertureMm: Float = 0f
    var widthMm: Float = 0f
    var jawOpenMm: Float = 0f
    var innerAreaMm2: Float = 0f

    /** Inner lip gap ÷ W₀. */
    var aperture: Float = 0f

    /** Mouth width ÷ W₀ (1 = rest). */
    var width: Float = 1f

    /** Jaw drop below rest ÷ W₀. */
    var jaw: Float = 0f

    /** 0…1, the live band's "mouth open": aperture ÷ (openFull·W₀). */
    var open: Float = 0f
    var round: Float = 0f
    var spread: Float = 0f

    /** Lips in front of the face relative to rest ÷ E; confidence ≤ 0.3. */
    var protrusion: Float = 0f

    /** Indicative only, adults only, never shown to the child. */
    var symmetry: Float = 1f
    var lipsClosed: Boolean = false
    var smile: Boolean = false
    var tongue: TongueState = TongueState.Unknown

    var confGeom: Float = 0f
    var confShape: Float = 0f
    var confTongue: Float = 0f

    /** Raw scores kept for fusion and debugging. */
    var jawOpenScore: Float = 0f
    var puckerScore: Float = 0f
    var funnelScore: Float = 0f
    var smileScore: Float = 0f
    var tongueModelScore: Float = 0f

    /** Red-tissue share gained over rest in the "out" zone, the colour heuristic's main number. */
    var tongueColourDelta: Float = 0f

    val isGood: Boolean get() = quality == FaceQuality.Good

    fun copyFrom(o: MouthState) {
        tNanos = o.tNanos; quality = o.quality; issue = o.issue; scaleSource = o.scaleSource; rest = o.rest
        apertureMm = o.apertureMm; widthMm = o.widthMm; jawOpenMm = o.jawOpenMm; innerAreaMm2 = o.innerAreaMm2
        aperture = o.aperture; width = o.width; jaw = o.jaw; open = o.open; round = o.round; spread = o.spread
        protrusion = o.protrusion; symmetry = o.symmetry; lipsClosed = o.lipsClosed; smile = o.smile; tongue = o.tongue
        confGeom = o.confGeom; confShape = o.confShape; confTongue = o.confTongue
        jawOpenScore = o.jawOpenScore; puckerScore = o.puckerScore; funnelScore = o.funnelScore; smileScore = o.smileScore
        tongueModelScore = o.tongueModelScore; tongueColourDelta = o.tongueColourDelta
    }

    /** The value of [feature] as the pose checks read it. */
    fun feature(feature: MouthFeature): Float = when (feature) {
        MouthFeature.APERTURE -> aperture
        MouthFeature.WIDTH -> width
        MouthFeature.ROUND -> round
        MouthFeature.SPREAD -> spread
        MouthFeature.JAW -> jaw
    }

    override fun toString(): String =
        "MouthState(q=$quality/$issue rest=$rest A=%.3f W=%.3f jaw=%.3f open=%.2f round=%.2f spread=%.2f closed=$lipsClosed tongue=$tongue)"
            .format(aperture, width, jaw, open, round, spread)
}

/** One-Euro filter (Casiez et al. 2012) on one scalar; time in nanoseconds. Allocation-free. */
class OneEuro(var minCutoffHz: Float, var beta: Float, var dCutoffHz: Float = 1f) {
    private var x = 0f
    private var dx = 0f
    private var last = 0L
    private var primed = false

    fun reset() {
        primed = false
    }

    fun filter(value: Float, tNanos: Long): Float {
        if (!primed || tNanos <= last) {
            if (!primed) {
                x = value
                dx = 0f
            }
            primed = true
            last = tNanos
            return x
        }
        val dt = (tNanos - last) / 1e9f
        last = tNanos
        val rawDx = (value - x) / dt
        dx += alpha(dCutoffHz, dt) * (rawDx - dx)
        val cutoff = minCutoffHz + beta * abs(dx)
        x += alpha(cutoff, dt) * (value - x)
        return x
    }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }
}

/**
 * A boolean that turns on after its condition held for [onHoldNanos] and off after the off-condition held for
 * [offHoldNanos] (docs/v2/04 §4 "Histerezis"; §5.1 timing).
 */
class Hysteresis(var onHoldNanos: Long, var offHoldNanos: Long) {
    var value = false
        private set
    private var since = -1L

    fun reset() {
        value = false
        since = -1L
    }

    /** [wantOn]: the on-condition holds now; [wantOff]: the off-condition holds now. */
    fun update(wantOn: Boolean, wantOff: Boolean, tNanos: Long): Boolean {
        val pushing = if (value) wantOff else wantOn
        if (!pushing) {
            since = -1L
        } else {
            if (since < 0) since = tNanos
            val hold = if (value) offHoldNanos else onHoldNanos
            if (tNanos - since >= hold) {
                value = !value
                since = -1L
            }
        }
        return value
    }
}

internal fun msToNanos(ms: Float): Long = (ms * 1e6f).toLong()

internal fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))
