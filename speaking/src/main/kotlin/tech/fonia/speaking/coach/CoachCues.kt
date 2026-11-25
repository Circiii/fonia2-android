package tech.fonia.speaking.coach

import kotlin.math.exp

/**
 * The live cues, most urgent first. [word] is what the living Allo letters say (≤ 3 words); [key] names it in
 * `spec/thresholds/speech-coach.json`. [Good] is the positive state, shown when everything is in range.
 */
enum class Cue(val key: String, val word: String) {
    Look("look", "Uită-te la cameră"),
    Louder("louder", "Mai tare"),
    Slower("slower", "Mai rar"),
    Faster("faster", "Mai repede"),
    Calmer("calmer", "Stai mai liniștit"),
    Livelier("livelier", "Mai viu!"),
    Good("good", "Așa!"),
    ;

    /** The area this cue is about (null for [Good]). */
    val area: Area?
        get() = when (this) {
            Look -> Area.Gaze
            Louder -> Area.Voice
            Slower, Faster -> Area.Pace
            Calmer -> Area.Calm
            Livelier -> Area.Lively
            Good -> null
        }

    companion object {
        /** The cues that come from a condition (all but [Good]), in priority order. */
        val conditions: List<Cue> = entries.filter { it != Good }
    }
}

/**
 * The area to practise next: the lowest average over his last talks (at least two measured values), ties going to
 * the order of [Area]. Null when there is not enough history, or when every area already averages [goodEnough]+.
 */
fun weakestArea(history: List<CoachScores>, goodEnough: Int = 80): Area? {
    var best: Area? = null
    var bestMean = Double.MAX_VALUE
    for (area in Area.entries) {
        val values = history.takeLast(5).mapNotNull { it[area] }
        if (values.size < 2) continue
        val mean = values.average()
        if (mean < bestMean) { bestMean = mean; best = area }
    }
    return best?.takeIf { bestMean < goodEnough }
}

/**
 * One cue at a time, never flickering. A condition must hold [CoachSpec.onSeconds] before its cue shows; a shown cue
 * stays at least [CoachSpec.Cues.minShowSeconds] and until its condition clears; a cleared cue waits
 * [CoachSpec.Cues.cooldownSeconds] before it may come back, and nothing shows for [CoachSpec.Cues.gapSeconds] after any
 * cue. A more urgent cue may take over after the minimum, and a cue steps aside after [CoachSpec.Cues.maxShowSeconds]
 * (it comes back after its cooldown if the condition still holds). "Așa!" shows when nothing has been wrong for [CoachSpec.Cues.goodAfterSeconds] (Presentation Trainer, Schneider
 * et al. 2015: one modality at a time, with a pause between feedbacks).
 *
 * Pure and single-threaded: call [update] from one place (the coach's tick).
 */
class CueArbiter(
    private val spec: CoachSpec = CoachSpec.v1,
    /** A focused drill: only the cues about this area show (and "Așa!"). Null: every cue. */
    private val focus: Area? = null,
) {
    private val cues = spec.cues
    private val heldSince = DoubleArray(Cue.entries.size) { Double.NaN }
    private val clearedAt = DoubleArray(Cue.entries.size) { Double.NEGATIVE_INFINITY }
    private var lastClear = Double.NEGATIVE_INFINITY
    private var goodSince = Double.NaN

    var shown: Cue? = null
        private set
    var shownSince: Double = 0.0
        private set

    /**
     * [active] is indexed by [Cue.ordinal] (Good's entry is ignored); [warm] says enough speech was heard for voice
     * cues; [speaking] (recently) lets "Așa!" show only while he talks. Returns the cue to show now (or null).
     */
    fun update(t: Double, active: BooleanArray, warm: Boolean, speaking: Boolean): Cue? {
        var anyActive = false
        for (c in Cue.conditions) {
            val on = active[c.ordinal] && (warm || c == Cue.Look) && (focus == null || c.area == focus)
            if (on) {
                anyActive = true
                if (heldSince[c.ordinal].isNaN()) heldSince[c.ordinal] = t
            } else {
                heldSince[c.ordinal] = Double.NaN
            }
        }
        val current = shown
        if (current == Cue.Good) {
            val ready = readyCandidate(t)
            if (t - shownSince >= cues.goodShowSeconds || (ready != null && t - shownSince >= 1.0)) clear(Cue.Good, t)
        } else if (current != null && t - shownSince >= cues.minShowSeconds) {
            val ready = readyCandidate(t)
            when {
                heldSince[current.ordinal].isNaN() -> clear(current, t)
                // A more urgent cue takes over; a cue that has said its piece long enough steps aside for a while.
                ready != null && ready.ordinal < current.ordinal -> { clear(current, t); show(ready, t) }
                t - shownSince >= cues.maxShowSeconds -> clear(current, t)
            }
        }
        if (shown == null && t - lastClear >= cues.gapSeconds) {
            readyCandidate(t)?.let { show(it, t) }
        }
        // The positive state.
        if (anyActive || !speaking || !warm) {
            goodSince = Double.NaN
        } else if (goodSince.isNaN()) {
            goodSince = t
        }
        if (shown == null && !goodSince.isNaN() && t - goodSince >= cues.goodAfterSeconds &&
            t - clearedAt[Cue.Good.ordinal] >= cues.goodCooldownSeconds && t - lastClear >= cues.gapSeconds
        ) {
            show(Cue.Good, t)
        }
        return shown
    }

    private fun readyCandidate(t: Double): Cue? {
        for (c in Cue.conditions) {
            val since = heldSince[c.ordinal]
            if (since.isNaN()) continue
            if (t - since < spec.onSeconds(c)) continue
            if (t - clearedAt[c.ordinal] < cues.cooldownSeconds) continue
            return c
        }
        return null
    }

    private fun show(c: Cue, t: Double) {
        shown = c
        shownSince = t
    }

    private fun clear(c: Cue, t: Double) {
        clearedAt[c.ordinal] = t
        lastClear = t
        shown = null
        if (c == Cue.Good) goodSince = Double.NaN
    }

    fun reset() {
        heldSince.fill(Double.NaN)
        clearedAt.fill(Double.NEGATIVE_INFINITY)
        lastClear = Double.NEGATIVE_INFINITY
        goodSince = Double.NaN
        shown = null
    }
}

/**
 * The gauges' follower: rises with [attackSeconds], falls with [releaseSeconds] (exponential, frame-rate independent),
 * so a gauge answers at once and never trembles. Step it once per drawn frame.
 */
class Envelope(private val attackSeconds: Double, private val releaseSeconds: Double, initial: Double = 0.0) {
    var value: Double = initial
        private set

    fun step(target: Double, dtSeconds: Double): Double {
        if (target.isNaN()) return value
        val tau = if (target > value) attackSeconds else releaseSeconds
        val a = 1.0 - exp(-dtSeconds.coerceIn(0.0, 0.25) / tau)
        value += (target - value) * a
        return value
    }

    fun snap(v: Double) {
        value = v
    }

    companion object {
        fun of(spec: CoachSpec, initial: Double = 0.0) = Envelope(spec.smoothing.attackSeconds, spec.smoothing.releaseSeconds, initial)
    }
}
