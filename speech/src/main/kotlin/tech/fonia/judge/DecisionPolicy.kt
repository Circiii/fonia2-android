package tech.fonia.judge

import tech.fonia.model.ErrorKind
import tech.fonia.model.SoundId
import tech.fonia.model.Verdict

/** What P5 decided, before the result is assembled. */
data class Decision(
    val verdict: Verdict,
    val confidence: Float,
    val topError: ErrorKind?,
    val topErrorProbability: Float,
    val withheld: ErrorKind?,
    val adultNote: AdultNote?,
)

/** How `spec/unlock.json` answers for one (sound, kind) on this take. */
enum class UnlockAnswer { Allowed, Trial, Locked }

/** The unlock registry (plan 03 §7, Q3): a kind is named to the child only when unlocked and its conditions hold. */
class UnlockView(val spec: UnlockSpec, val everything: Boolean = false) {
    fun answer(sound: SoundId, kind: ErrorKind, p: Float, tauErr: Float, runtime: RuntimeContext, capture: CaptureReport?): UnlockAnswer {
        if (runtime.policy == PolicyLevel.Self) selfAnswer(kind, p)?.let { return it }
        val e = spec.entry(sound, kind) ?: return UnlockAnswer.Locked
        return when (e.state) {
            "unlocked" -> {
                val bandOk = e.requires.minBandwidthHz == null || (capture?.bandwidthHz ?: 0f) >= e.requires.minBandwidthHz
                val cameraOk = !e.requires.camera || runtime.camera
                val levelOk = when (e.evidenceLevel) {
                    "children" -> true
                    // Q3: on adult imitations the child sees the kind only with an adult present, a calibrated child and a
                    // stricter threshold (the pilot rule's runtime block).
                    "imitations" -> {
                        val rt = spec.rules["pilot"]?.runtime ?: UnlockRuntime()
                        (!rt.adultPresent || runtime.adultPresent) && runtime.calibrationTier >= rt.calibrationTier &&
                            p >= tauErr + rt.tauErrExtra
                    }
                    else -> false
                }
                if (bandOk && cameraOk && levelOk) UnlockAnswer.Allowed else UnlockAnswer.Trial
            }
            "trial" -> UnlockAnswer.Trial
            else -> UnlockAnswer.Locked
        }
    }

    /** The `self` rule: Allowed at or above its confidence, else null (the registry answers as for a child). */
    private fun selfAnswer(kind: ErrorKind, p: Float): UnlockAnswer? {
        val rule = spec.rules["self"] ?: return null
        val min = rule.minConfidence ?: return null
        if (rule.kinds != null && kind.raw !in rule.kinds) return null
        return if (p >= min) UnlockAnswer.Allowed else null
    }

    /**
     * Whether the visual block's hint may be shown on this take: always for [PolicyLevel.Self] at or above the `self`
     * rule's confidence; for a child only when the take named an unlocked kind (today: never).
     */
    fun hintAllowed(result: TakeResult, runtime: RuntimeContext): Boolean {
        val hint = result.visual?.hint ?: return false
        if (runtime.policy == PolicyLevel.Self) {
            val min = spec.rules["self"]?.minConfidence ?: return false
            return hint.confidence >= min
        }
        return result.verdict is Verdict.NamedError
    }

    companion object {
        /** Every kind treated as unlocked: the detector's own answer, for evaluation only. */
        fun everythingUnlocked(): UnlockView = UnlockView(UnlockSpec(version = "eval-all-unlocked", entries = emptyList()), everything = true)
    }
}

/** P5 (plan 03 §7, the `decide` sketch), with the fast head's stricter thresholds when the phoneme model is absent. */
object DecisionPolicy {
    fun decide(
        p: Probs, head: Head, sound: SoundId, unlock: UnlockView, runtime: RuntimeContext, blocked: Set<ErrorKind>,
        capture: CaptureReport?,
    ): Decision {
        val d = p.spec.decision
        val fast = head != Head.Full
        val tauOk = (if (fast) d.tauOkFast else d.tauOk) + runtime.childOkOffset
        val ranked = p.ranked
        val (k, pk) = ranked.firstOrNull() ?: (null to 0f)
        if (p.correct >= tauOk) return Decision(Verdict.Correct, p.correct, k, pk, null, null)
        if (k == null) {
            return if (p.correct >= d.tauAlmost) Decision(Verdict.Almost, p.correct, null, 0f, null, null)
            else Decision(Verdict.Unsure, p.correct, null, 0f, null, null)
        }
        val second = ranked.getOrNull(1)?.second ?: 0f
        val tauErr = p.spec.tauErr(k) + (if (fast) d.tauErrFastExtra else 0f) + (runtime.childErrOffset[k] ?: 0f)
        val nameable = pk >= tauErr && pk - second >= d.delta && p.sumCoarserThan(p.spec.level(k)) <= d.coarseMax && k !in blocked
        val heard = p.spec.heardAs(k)
        var withheld: ErrorKind? = null
        var note: AdultNote? = null
        if (nameable) {
            val answer = if (unlock.everything) UnlockAnswer.Allowed else unlock.answer(sound, k, pk, tauErr, runtime, capture)
            when (answer) {
                UnlockAnswer.Allowed -> return Decision(Verdict.NamedError(k, heard), pk, k, pk, null, null)
                UnlockAnswer.Trial -> {
                    withheld = k
                    note = AdultNote(k, pk, heard, inTrial = true)
                }
                UnlockAnswer.Locked -> withheld = k
            }
        }
        if (p.correct >= d.tauAlmost) return Decision(Verdict.Almost, p.correct, k, pk, withheld, note)
        // Unsure asks the adult; a trial kind that is the most likely class rides along "în probă".
        if (note == null && !unlock.everything && pk > p.correct && k !in blocked &&
            unlock.answer(sound, k, pk, tauErr, runtime, capture) == UnlockAnswer.Trial
        ) {
            note = AdultNote(k, pk, heard, inTrial = true)
        }
        return Decision(Verdict.Unsure, maxOf(p.correct, pk), k, pk, withheld, note)
    }
}
