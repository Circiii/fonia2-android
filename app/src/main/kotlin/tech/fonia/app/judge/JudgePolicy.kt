package tech.fonia.app.judge

import tech.fonia.app.text.T
import tech.fonia.judge.Feedback
import tech.fonia.judge.RuntimeContext
import tech.fonia.judge.TakeResult
import tech.fonia.judge.UnlockSpec
import tech.fonia.model.ErrorKind
import tech.fonia.model.SoundId
import tech.fonia.model.Verdict
import tech.fonia.store.JSONValue

/**
 * Turns the real judge's [TakeResult] into what the lesson shows (pure, JVM-tested). The unlock policy of plan 03 §7
 * and decision Q3, exactly:
 * - a [Verdict.NamedError] reaches the child **only** when the judge returned it (the judge already applied
 *   `spec/unlock.json`); when its entry is unlocked on adult imitations only, it waits for the adult's thumb
 *   ([ChildFacing.AdultFirst]);
 * - `withheldKind` / `adultNote` never reach the child: a kind "în probă" becomes an [AdultView] for the adult strip
 *   and the adult area; a locked kind is seen by nobody (it stays in the trace);
 * - `Unsure` asks the adult (the lesson lights the thumb row); `Retake` is never counted (the lesson's job).
 */
object JudgePolicy {
    fun map(result: TakeResult, sound: SoundId, unlock: UnlockSpec, feedback: Feedback?, runtime: RuntimeContext): Judged {
        val verdict = result.verdict
        val facing = if (verdict is Verdict.NamedError && unlock.entry(sound, verdict.kind)?.let { it.state == "unlocked" && it.evidenceLevel == "imitations" } == true) {
            ChildFacing.AdultFirst
        } else {
            ChildFacing.Show
        }
        val childLine = when (verdict) {
            is Verdict.NamedError -> feedback?.forKind(sound, verdict.kind)?.child ?: T.instruction(verdict.kind, sound)
            Verdict.Retake -> result.retakeReason?.let { feedback?.forRetake(it)?.child }
            else -> null
        }
        val note = result.adultNote
        val adult = when {
            // a named error: the adult sees the same kind, not "în probă"
            verdict is Verdict.NamedError -> AdultView(verdict.kind, verdict.heardAs, result.confidence, inTrial = false, line = adultLine(feedback, sound, verdict.kind, verdict.heardAs))
            note != null && note.inTrial -> AdultView(note.kind, note.heardAs, note.probability, inTrial = true, line = adultLine(feedback, sound, note.kind, note.heardAs))
            else -> null
        }
        val reason = when (verdict) {
            Verdict.Retake -> result.retakeReason?.let { feedback?.forRetake(it)?.adult }
            Verdict.Unsure -> feedback?.forVerdict("unsure")?.adult
            else -> null
        }
        return Judged(
            verdict = verdict,
            facing = facing,
            childLine = childLine,
            adult = adult,
            adultReason = reason,
            detail = trace(result, runtime, facing),
            f0Hz = result.evidence.capture?.f0MedianHz?.takeIf { it.isFinite() && it > 0f },
        )
    }

    private fun adultLine(feedback: Feedback?, sound: SoundId, kind: ErrorKind, heard: SoundId?): String =
        feedback?.forKind(sound, kind)?.adult ?: T.adultHint(heard, sound)

    /** Everything the judge gave, as the attempt's JSON payload (`detail.judge`). NaN values are left out. */
    fun trace(r: TakeResult, runtime: RuntimeContext, facing: ChildFacing): Map<String, JSONValue> = buildMap {
        val v = r.verdict
        put("verdict", s(v.key))
        if (v is Verdict.NamedError) {
            put("errorKind", s(v.kind.raw))
            v.heardAs?.let { put("heardAs", s(it.raw)) }
        }
        put("facing", s(facing.name))
        num(r.confidence)?.let { put("confidence", it) }
        r.retakeReason?.let { put("retakeReason", s(it.raw)) }
        put("head", s(r.head.raw))
        put("fastFinal", JSONValue.Bool(r.fastFinal))
        put("probabilities", obj(r.probabilities.mapNotNull { (k, p) -> num(p)?.let { k to it } }.toMap()))
        r.topError?.let { put("topError", s(it.raw)); num(r.topErrorProbability)?.let { p -> put("topErrorProbability", p) } }
        r.withheldKind?.let { put("withheldKind", s(it.raw)) }
        r.adultNote?.let { n ->
            put(
                "adultNote",
                obj(
                    buildMap {
                        put("kind", s(n.kind.raw))
                        num(n.probability)?.let { put("probability", it) }
                        n.heardAs?.let { put("heardAs", s(it.raw)) }
                        put("inTrial", JSONValue.Bool(n.inTrial))
                    },
                ),
            )
        }
        val e = r.evidence
        put("features", obj(e.features.mapNotNull { (k, x) -> num(x)?.let { k to it } }.toMap()))
        e.outcome?.let { put("dspOutcome", s(it.toString())) }
        e.capture?.let { c ->
            put(
                "capture",
                obj(
                    listOfNotNull(
                        num(c.durationSeconds)?.let { "durationSeconds" to it },
                        num(c.sampleRate)?.let { "sampleRate" to it },
                        num(c.speechSeconds)?.let { "speechSeconds" to it },
                        num(c.speechRmsDb)?.let { "speechRmsDb" to it },
                        num(c.dynamicRangeDb)?.let { "dynamicRangeDb" to it },
                        num(c.clippedFraction)?.let { "clippedFraction" to it },
                        num(c.bandwidthHz)?.let { "bandwidthHz" to it },
                        "dropped" to JSONValue.Number(c.dropped.toDouble()),
                        num(c.bandSnrDb)?.let { "bandSnrDb" to it },
                        num(c.bandRangeDb)?.let { "bandRangeDb" to it },
                        num(c.f0MedianHz)?.let { "f0MedianHz" to it },
                        "bandwidthTier" to s(c.bandwidthTier),
                    ).toMap(),
                ),
            )
        }
        put("segments", JSONValue.Array(e.segments.map { (a, b) -> JSONValue.Array(listOf(JSONValue.Number(round(a)), JSONValue.Number(round(b)))) }))
        put("blockedKinds", JSONValue.Array(e.blockedKinds.map { s(it.raw) }))
        put("notes", JSONValue.Array(e.notes.map { s(it) }))
        put(
            "versions",
            obj(
                mapOf(
                    "thresholds" to s(r.versions.thresholds), "fusion" to s(r.versions.fusion), "unlock" to s(r.versions.unlock),
                    "feedback" to s(r.versions.feedback), "featureVersion" to s(r.versions.featureVersion),
                ),
            ),
        )
        put("micros", obj(r.micros.mapValues { JSONValue.Number(it.value.toDouble()) }))
        put(
            "runtime",
            obj(
                buildMap {
                    put("adultPresent", JSONValue.Bool(runtime.adultPresent))
                    put("calibrationTier", JSONValue.Number(runtime.calibrationTier.toDouble()))
                    num(runtime.childOkOffset)?.let { put("childOkOffset", it) }
                    if (runtime.childErrOffset.isNotEmpty()) put("childErrOffset", obj(runtime.childErrOffset.entries.associate { it.key.raw to JSONValue.Number(round(it.value.toDouble())) }))
                },
            ),
        )
    }

    private fun s(x: String) = JSONValue.String(x)
    private fun obj(m: Map<String, JSONValue>) = JSONValue.Object(m)
    private fun round(x: Double) = Math.round(x * 10_000) / 10_000.0
    private fun num(x: Float): JSONValue? = if (x.isFinite()) JSONValue.Number(round(x.toDouble())) else null
    private fun num(x: Double): JSONValue? = if (x.isFinite()) JSONValue.Number(round(x)) else null
}
