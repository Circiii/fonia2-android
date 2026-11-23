package tech.fonia.app.data

import tech.fonia.app.judge.AnchorCandidate
import tech.fonia.model.ErrorKind
import tech.fonia.model.SoundId
import tech.fonia.store.Attempt
import tech.fonia.store.JSONValue
import java.time.Instant
import java.util.UUID

/**
 * One attempt row as the adult area and the calibration read it (pure projection of `attempt` + its `detail` JSON).
 * The judge's trace sits in `detail.judgeTrace`; the S/Ș anchor candidate in `detail.anchor`; the take's F0 in `detail.f0Hz`.
 */
data class AttemptView(
    val id: UUID,
    val sound: SoundId?,
    val step: String,
    val text: String,
    val ts: Instant,
    val localDay: String,
    /** `Verdict.key` the phone gave: correct, almost, namedError, unsure, retake. */
    val verdict: String,
    val errorKind: ErrorKind? = null,
    val heardAs: SoundId? = null,
    /** The kind the detector would have named without `spec/unlock.json`. */
    val withheldKind: ErrorKind? = null,
    /** The "în probă" note for the adult: kind + what was heard. */
    val trialKind: ErrorKind? = null,
    val trialHeardAs: SoundId? = null,
    val confidence: Double? = null,
    /** The adult's thumb: true = 👍 "a ieșit", false = "încă nu", null = not asked / not answered. */
    val adultGood: Boolean? = null,
    val anchor: AnchorCandidate? = null,
    val f0Hz: Float? = null,
    val recordingId: UUID? = null,
) {
    val judged: Boolean get() = verdict != "retake"

    /** The adult overrode the phone (said the opposite of a definite verdict). */
    val overridden: Boolean
        get() = adultGood != null && when (verdict) {
            "correct" -> adultGood == false
            "namedError", "almost" -> adultGood == true
            else -> false
        }

    companion object {
        fun from(a: Attempt): AttemptView {
            val d = a.detail ?: emptyMap()
            val trace = (d["judgeTrace"] as? JSONValue.Object)?.value ?: emptyMap()
            val note = (trace["adultNote"] as? JSONValue.Object)?.value
            val inTrial = (note?.get("inTrial") as? JSONValue.Bool)?.value == true
            val anchor = (d["anchor"] as? JSONValue.Object)?.value?.let { m ->
                val place = str(m["place"]) ?: return@let null
                AnchorCandidate(place, num(m["centroidHz"]) ?: return@let null, num(m["peakHz"]) ?: return@let null, num(m["bandRatioDb"]) ?: return@let null, num(m["tiltDb"]) ?: return@let null)
            }
            return AttemptView(
                id = a.id,
                sound = a.sound?.let(SoundId::of),
                step = str(d["step"]) ?: a.exerciseKind,
                text = a.target ?: "",
                ts = a.ts,
                localDay = a.localDay,
                verdict = str(d["verdict"]) ?: verdictOf(a.machineOutcome),
                errorKind = str(d["errorKind"])?.let(ErrorKind::of),
                heardAs = str(d["heardAs"])?.let(SoundId::of),
                withheldKind = str(trace["withheldKind"])?.let(ErrorKind::of),
                trialKind = if (inTrial) str(note?.get("kind"))?.let(ErrorKind::of) else null,
                trialHeardAs = if (inTrial) str(note?.get("heardAs"))?.let(SoundId::of) else null,
                confidence = (trace["confidence"] as? JSONValue.Number)?.value,
                adultGood = when (a.adultOutcome) {
                    Attempt.Outcome.Good -> true
                    null, Attempt.Outcome.NotJudged, Attempt.Outcome.Unclear -> null
                    else -> false
                },
                anchor = anchor,
                f0Hz = num(d["f0Hz"]),
                recordingId = a.recordingId,
            )
        }

        private fun verdictOf(o: Attempt.Outcome) = when (o) {
            Attempt.Outcome.Good -> "correct"
            Attempt.Outcome.Almost -> "almost"
            Attempt.Outcome.NotYet -> "namedError"
            Attempt.Outcome.Unclear -> "unsure"
            Attempt.Outcome.NotJudged -> "retake"
        }

        private fun str(v: JSONValue?) = (v as? JSONValue.String)?.value
        private fun num(v: JSONValue?) = (v as? JSONValue.Number)?.value?.toFloat()
    }
}
