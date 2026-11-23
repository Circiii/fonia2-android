package tech.fonia.app.judge

import tech.fonia.live.Clip
import tech.fonia.model.ErrorKind
import tech.fonia.model.Rung
import tech.fonia.model.SoundId
import tech.fonia.model.Verdict
import tech.fonia.store.JSONValue

/**
 * Whose voice the phone hears (the judge's `TalkerGroup`). A child by default; an adult or teen tester picks one of
 * the adult options in onboarding or ADULT-SETTINGS, so the adult-voice gate does not send them back.
 */
enum class Talker(val raw: String) {
    Child("child"),
    AdultHigh("adultHigh"),
    AdultLow("adultLow"),
    ;

    companion object {
        fun of(raw: String?): Talker = entries.firstOrNull { it.raw == raw } ?: Child
    }
}

/** What the session knows about the child on this sound (from [CalibrationBook]). */
data class SoundCalibration(
    /** Adult-confirmed good takes on this sound. */
    val confirmedGood: Int = 0,
    /** 0 = group prior, 1 = „calibrat pe copil” (≥ [CalibrationBook.CALIBRATED_AT] confirmed takes). */
    val tier: Int = 0,
    /** S/Ș anchors as `TalkerCalibration` JSON (`:engine:dsp`), null when there are none. */
    val anchorsJson: String? = null,
    /** The child's median F0 from confirmed takes (adult-voice gate), null until known. */
    val childF0Hz: Float? = null,
    val childOkOffset: Float = 0f,
    val childErrOffset: Map<ErrorKind, Float> = emptyMap(),
)

/** The runtime context of one take (plan 03 §7: unlock conditions and adult-disagreement offsets). */
data class JudgeContext(
    val talker: Talker = Talker.Child,
    val adultPresent: Boolean = false,
    val calibration: SoundCalibration = SoundCalibration(),
)

/** What was asked of the take. [level]: [Rung.Isolated] for `Hold`, a words rung for `Words`. */
data class TakeRequest(
    val sound: SoundId,
    val level: Rung,
    val text: String,
    val context: JudgeContext = JudgeContext(),
)

/** How the verdict may reach the child (decision Q3 of docs/v2/09). */
enum class ChildFacing {
    /** Show the verdict as it is. */
    Show,

    /**
     * A [Verdict.NamedError] unlocked on adult imitations only: the instruction reaches the child only after the adult
     * confirms with the thumb; otherwise the take stays "în probă" for the adult.
     */
    AdultFirst,
}

/** What the adult may see about a take (never the child): a kind the detector suspected. */
data class AdultView(
    val kind: ErrorKind,
    val heardAs: SoundId?,
    val probability: Float,
    /** „în probă”: the kind is in trial in `spec/unlock.json`; the child is not told. */
    val inTrial: Boolean,
    /** The adult's sentence from `spec/feedback.json` (neutral words). */
    val line: String,
)

/** An S/Ș measurement that becomes an anchor of the child's own norm if the adult confirms the take (plan 03 §6). */
data class AnchorCandidate(
    /** "s" or "sh" (`SibilantPlace.rawValue`). */
    val place: String,
    val centroidHz: Float,
    val peakHz: Float,
    val bandRatioDb: Float,
    val tiltDb: Float,
)

/** One judged take: what the child sees, what the adult sees, and everything to save. */
data class Judged(
    val verdict: Verdict,
    val facing: ChildFacing = ChildFacing.Show,
    /** The child's line for a [Verdict.NamedError] (≤ 8 words), null otherwise. */
    val childLine: String? = null,
    val adult: AdultView? = null,
    /** The adult's line for a retake ("Telefonul a auzit prea încet.") or Unsure. */
    val adultReason: String? = null,
    /** The whole trace for the attempt row (`detail.judge`): verdict, probabilities, features, versions… No audio. */
    val detail: Map<String, JSONValue> = emptyMap(),
    val anchor: AnchorCandidate? = null,
    /** Median F0 of the take, Hz (NaN-free), for the child's F0 once confirmed. */
    val f0Hz: Float? = null,
)

/**
 * The one seam between the lesson and whoever judges a take. [tech.fonia.app.AppGraph.judge] holds the instance: the
 * real [RealJudge] (`tech.fonia.judge.TakeJudge`); [ProvisionalJudge] stays as a test double.
 */
interface TakeJudging {
    /** Name + version saved with every attempt (`detail.judge`). */
    val version: String

    /** Judges one finished take (after `closeMic()`), off the main thread. */
    suspend fun judge(request: TakeRequest, clip: Clip): Judged
}
