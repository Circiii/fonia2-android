package tech.fonia.features.pilot

import tech.fonia.domain.adaptive.AdaptiveDecision
import tech.fonia.store.Attempt
import java.time.Instant
import java.util.UUID

// What the pilot log keeps (03_cercetare_produs.md §4.1), one class per table. No audio, no words the child said or
// typed, no names: sounds, positions and outcomes are codes, profiles are UUIDs on the phone and a random pseudonym in
// the export. Everything stays on the phone until an adult shares the summary for the logoped.

/** How a take counts: spoken on the phone (the dose), a listening choice, or the mirror. Counted apart (§4.1). */
enum class AttemptMode(val code: String) {
    Production("production"),
    Listening("listening"),
    Mirror("mirror"),
}

/** Where the target sound sits in what was said, for the dose by sound and position. */
enum class SoundPosition(val code: String) {
    Isolated("isolated"),
    Initial("initial"),
    Medial("medial"),
    Final("final"),
    Sentence("sentence"),
    Unknown(""),
}

/** Whether a take was scored in shadow mode, and if not, why. */
enum class ShadowStatus(val code: String) {
    Scored("scored"),

    /** The scorer threw. */
    Failed("failed"),

    /** No model on the phone, or a phone the runtime doesn't run on. */
    Unavailable("unavailable"),

    /** The queue was full: a newer take pushed this one out before it was scored. */
    Dropped("dropped"),
}

/** Where the shadow take came from. */
enum class ShadowContext(val code: String) {
    Session("session"),
    Mirror("mirror"),
}

/** One production, listening or mirror attempt (§4.1 "Doză", "Expunerea la feedback", "Calibrarea părintelui"). */
data class AttemptRecord(
    val profileId: UUID,
    /** Null for the mirror, which runs outside a session. */
    val sessionId: UUID?,
    val at: Instant,
    /** The take within its session (`<trial>.<retry>`), which ties a shadow score to its attempt. */
    val take: String,
    val mode: AttemptMode,
    /** `Exercise.Kind` raw value. */
    val exercise: String,
    /** `isolated`, `syllable`, `word` or `sentence`; null when not a spoken item. */
    val level: String?,
    /** `SoundID` raw value. */
    val sound: String?,
    val position: SoundPosition,
    /** The rung of the session (`Rung` raw value). */
    val rung: String?,
    /** `SessionBlock.Kind` raw value. */
    val block: String?,
    /** `FeedbackLevel` raw value: how much the learner was told in the moment (KR schedule). */
    val feedback: String?,
    /** What the phone said. */
    val machine: Attempt.Outcome,
    /** What the adult said, if they rated it. */
    val adult: Attempt.Outcome?,
    /** The learner's own rating, if the trial asked for it. */
    val self: Attempt.Outcome?,
    /** What counts, as the session decides it: the adult's, else the phone's verdict, else the learner's own. */
    val decided: Attempt.Outcome,
    /** `Trial.countsAsAttempt`. */
    val counted: Boolean,
    val retry: Boolean,
) {
    /**
     * A production of a target sound with a verdict (good, almost or not yet): the dose of 03 §1.2 P6, the takes the
     * session's own count keeps (a take nobody could judge stays out, as in `SessionRun.finishAttempt`).
     */
    val dose: Boolean
        get() = mode == AttemptMode.Production && counted && sound != null && decided.isVerdictOutcome
}

/** One take scored in shadow mode next to the verdict the child got (§4.1 "Acord aplicație–logoped", P13). */
data class ShadowRecord(
    val profileId: UUID,
    val sessionId: UUID?,
    val at: Instant,
    val take: String,
    val context: ShadowContext,
    val sound: String,
    val position: SoundPosition,
    val level: String?,
    val feedback: String?,
    /** The verdict TakeJudge gave (`Attempt.Outcome` raw value): what the child saw, if the schedule showed it. */
    val shown: Attempt.Outcome,
    val status: ShadowStatus,
    /** `Verdict` of :phones: correct, tryAgain or notHeard. */
    val verdict: String? = null,
    /** Why ZIPA abstained: tooShort, poorFit, uncertain, noTarget. */
    val abstention: String? = null,
    /** Uncalibrated posterior of the verdict. */
    val confidence: Double? = null,
    /** Lowest log posterior ratio of the target against its best alternative, in nats. */
    val lpr: Double? = null,
    /** The likeliest substitute (`Alternative.id`), whatever the verdict. */
    val heardAs: String? = null,
    /** How well the best candidate explains the audio, per label (near 0 when it is this word). */
    val fit: Double? = null,
    /** Model output frames, 20 ms each. */
    val frames: Int? = null,
    /** Occurrences of the sound in the item. */
    val targets: Int? = null,
    /**
     * Time the scorer took (resampling, network, decision); time spent loading the model first, when this take had to
     * (0 when it was loaded already); and time the take waited in the queue.
     */
    val scoreMs: Long? = null,
    val loadMs: Long? = null,
    val waitMs: Long = 0,
    /** The take as captured: rate, length, samples lost, an SNR estimate (no audio is kept). */
    val sampleRate: Int,
    val durationMs: Int,
    val droppedSamples: Int,
    val snrDb: Double?,
    /** A Bluetooth or sub-32 kHz microphone, for which the S/Ș analysis is off. */
    val narrowBand: Boolean,
    /** `microphone`, or `fixture` when the takes come from recorded clips (emulator, UI tests). */
    val input: String,
    /** `ModelEntry.label` and the decision thresholds, so a score can be read against the version that made it. */
    val model: String?,
    val policy: String?,
    /** The DSP thresholds the shown verdict came from. */
    val dsp: String,
)

/** Something an adult did with the data (§2.3 "jurnal de acces"). */
enum class AccessAction(val code: String) {
    Export("export"),
}

/**
 * One decision of the adaptive pilot (docs/PLAN.md §7.4): the mode of a session, the step and the feedback of a block,
 * a position × rung at the end of a session, a rung passed or kept, or an adult's switch. Codes and numbers only.
 */
data class AdaptiveRecord(
    val profileId: UUID,
    /** Null for a switch an adult changed outside a session. */
    val sessionId: UUID?,
    val at: Instant,
    val decision: AdaptiveDecision,
)

/** Adaptive decisions of one kind on one day, from the detailed rows and from the counters kept after the detail period. */
data class AdaptiveCounter(val day: String, val sound: String, val kind: String, val choice: String, val reason: String, val decisions: Int)

/** `Attempt.Outcome.isVerdict` of the session, without depending on the session package. */
internal val Attempt.Outcome.isVerdictOutcome: Boolean
    get() = this == Attempt.Outcome.Good || this == Attempt.Outcome.Almost || this == Attempt.Outcome.NotYet
