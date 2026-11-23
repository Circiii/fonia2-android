package tech.fonia.face

import tech.fonia.model.SoundId

/** What the camera says about the target gesture. */
enum class EvidenceStatus {
    /** The visible gesture matches the target (with hysteresis). */
    Matches,

    /** Not (yet) matching; [CameraEvidence.advice] says what to change. */
    Adjust,

    /** The camera does not judge: quality gate down, no face, or nothing visible to check. */
    Abstain,
}

/** Why the camera abstains. */
enum class AbstainReason {
    None,
    Quality,
    NoFace,
    NothingVisible,

    /**
     * The rest is not taken yet. On real video, judging width and rounding against the population fall-back gave
     * false "matches" (a narrow mouth read as rounded), so the camera waits for this child's rest.
     */
    Calibrating,
}

/**
 * The camera's evidence for one sound, one frame (reused). Never a verdict on the sound: per docs/v2/04 §1 the
 * microphone judges, the camera checks the visible gesture and chooses the advice.
 */
class CameraEvidence {
    var sound: SoundId? = null
    var tNanos: Long = 0
    var status: EvidenceStatus = EvidenceStatus.Abstain
    var abstain: AbstainReason = AbstainReason.NoFace

    /** The quality issue behind [AbstainReason.Quality]. */
    var issue: QualityIssue = QualityIssue.NoFace

    /** m = min over the visible checks, 0…1 (raw, this frame). */
    var match: Float = 0f

    /** Hysteresis state: matched (on at m ≥ 0.75 held 300 ms, off below 0.55 held 400 ms). */
    var matched: Boolean = false

    /** The most-needed change, or null when matching or abstaining. */
    var advice: CameraAdvice? = null

    /** Second change, if another check is also off. */
    var advice2: CameraAdvice? = null

    /** Per-check scores, in the pose's check order. */
    val checkScores = FloatArray(8)
    var confidence: Float = 0f

    /** The rest was not taken yet: thresholds use population fall-backs. */
    var provisional: Boolean = true

    /** The pose declares the tongue invisible to the camera (always so in speech). */
    var tongueVisible: Boolean = false

    fun copyFrom(o: CameraEvidence) {
        sound = o.sound; tNanos = o.tNanos; status = o.status; abstain = o.abstain; issue = o.issue; match = o.match
        matched = o.matched; advice = o.advice; advice2 = o.advice2; o.checkScores.copyInto(checkScores)
        confidence = o.confidence; provisional = o.provisional; tongueVisible = o.tongueVisible
    }

    override fun toString() = "CameraEvidence($sound $status m=%.2f matched=$matched advice=${advice?.key} abstain=$abstain/$issue)".format(match)
}

/** Turns each [MouthState] into [CameraEvidence] for one [PoseSpec]. One instance per target; not thread-safe. */
class TargetMatcher(val pose: PoseSpec, private val spec: MatchSpec = MatchSpec()) {
    private val hysteresis = Hysteresis(msToNanos(spec.onHoldMs), msToNanos(spec.offHoldMs))

    init {
        require(pose.checks.size <= 8) { "at most 8 camera checks" }
    }

    fun reset() = hysteresis.reset()

    fun update(state: MouthState, out: CameraEvidence): CameraEvidence {
        out.sound = pose.sound
        out.tNanos = state.tNanos
        out.tongueVisible = pose.visible.tongue
        out.provisional = state.rest == RestSource.Default
        out.issue = state.issue
        out.advice = null
        out.advice2 = null
        out.checkScores.fill(0f)
        if (state.quality != FaceQuality.Good) {
            out.status = EvidenceStatus.Abstain
            out.abstain = if (state.quality == FaceQuality.Absent) AbstainReason.NoFace else AbstainReason.Quality
            out.match = 0f
            out.confidence = 0f
            // The gate is down: nothing is judged, and the match fades as if the condition were off.
            out.matched = hysteresis.update(false, true, state.tNanos)
            return out
        }
        if (state.rest == RestSource.Default) {
            out.status = EvidenceStatus.Abstain
            out.abstain = AbstainReason.Calibrating
            out.match = 0f
            out.confidence = 0f
            out.matched = hysteresis.update(false, true, state.tNanos)
            return out
        }
        if (pose.checks.isEmpty()) {
            out.status = EvidenceStatus.Abstain
            out.abstain = AbstainReason.NothingVisible
            out.match = 0f
            out.matched = false
            out.confidence = 0f
            return out
        }
        var m = 1f
        var worst = -1
        var worstScore = 2f
        var second = -1
        var secondScore = 2f
        for (k in pose.checks.indices) {
            val check = pose.checks[k]
            val s = check.score(state.feature(check.feature))
            out.checkScores[k] = s
            if (s < m) m = s
            if (s < 1f) {
                if (s < worstScore) {
                    second = worst; secondScore = worstScore
                    worst = k; worstScore = s
                } else if (s < secondScore) {
                    second = k; secondScore = s
                }
            }
        }
        out.match = m
        val matched = hysteresis.update(m >= spec.onAt, m < spec.offBelow, state.tNanos)
        out.matched = matched
        out.abstain = AbstainReason.None
        if (worst >= 0) {
            val c = pose.checks[worst]
            out.advice = c.advice(state.feature(c.feature))
        }
        if (second >= 0) {
            val c = pose.checks[second]
            out.advice2 = c.advice(state.feature(c.feature)).takeIf { it != out.advice }
        }
        out.status = if (matched) EvidenceStatus.Matches else EvidenceStatus.Adjust
        out.confidence = state.confShape
        return out
    }
}
