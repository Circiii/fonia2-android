package tech.fonia.app.judge

import android.content.res.AssetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import tech.fonia.dsp.SibilantPlace
import tech.fonia.dsp.TalkerCalibration
import tech.fonia.dsp.analyzeClip
import tech.fonia.judge.Feedback
import tech.fonia.judge.Head
import tech.fonia.judge.PhoneEvidence
import tech.fonia.store.JSONValue
import tech.fonia.judge.JudgeRequest
import tech.fonia.judge.JudgeSpec
import tech.fonia.judge.Level
import tech.fonia.judge.Position
import tech.fonia.judge.RuntimeContext
import tech.fonia.judge.TakeJudge
import tech.fonia.judge.TalkerGroup
import tech.fonia.live.Clip
import tech.fonia.model.Rung
import tech.fonia.model.SoundId
import tech.fonia.model.Verdict
import java.io.File

/** The phoneme model's evidence for one take (ZIPA through `ZipaHost.score`), or null (timeout, no model, failure). */
fun interface PhoneScoring {
    suspend fun score(clip: Clip, request: JudgeRequest): PhoneEvidence?
}

/**
 * The real sound judge in the lesson: `tech.fonia.judge.TakeJudge` over the spec files bundled in the APK (`assets/spec`,
 * the `specAssets` build step). The spec is parsed once, off the main thread, when the graph is built; each take runs
 * `measure` + `decide` on [Dispatchers.Default] after `closeMic()`: the fast head, then — unless a gate decided or the
 * fast verdict is final — the full head with ZIPA's phone evidence when [phones] returns it in time ([HeadChoice]).
 * [JudgePolicy] turns the result into what the child and the adult see.
 */
class RealJudge(scope: CoroutineScope, private val phones: PhoneScoring? = null, load: suspend () -> JudgeSpec) : TakeJudging {
    private val ready: Deferred<Pair<TakeJudge, Feedback>> = scope.async(Dispatchers.IO) {
        val spec = load()
        TakeJudge(spec) to Feedback(spec.feedback)
    }

    override val version: String get() = VERSION

    /** The loaded judge (suspends until the spec is parsed). */
    suspend fun judge(): TakeJudge = ready.await().first

    override suspend fun judge(request: TakeRequest, clip: Clip): Judged {
        val (judge, feedback) = ready.await()
        return withContext(Dispatchers.Default) {
            val req = jvmRequest(request)
            val runtime = runtime(request.context, judge, request.sound)
            val measured = judge.measure(req, clip)
            val fast = judge.decide(measured, phones = null, runtime = runtime)
            val early = when {
                fast.head == Head.Gate -> HeadChoice.Path.Gate
                fast.fastFinal -> HeadChoice.Path.FastFinal
                else -> null
            }
            val scorer = phones
            val (result, path) = HeadChoice.choose(fast, early, scorer?.let { p -> suspend { p.score(clip, req) } }) { evidence ->
                judge.decide(measured, evidence, runtime = runtime)
            }
            val mapped = JudgePolicy.map(result, request.sound, judge.spec.unlock, feedback, runtime)
            val judged = mapped.copy(detail = mapped.detail + ("zipa" to JSONValue.String(path.raw)))
            if (judged.verdict == Verdict.Retake) judged else judged.copy(anchor = anchorOf(request, req, clip, result.evidence.segments))
        }
    }

    companion object {
        const val VERSION = "take-judge-v0"

        /** Never below the spec's own threshold, never above 0.97 in total (plan 03 §7). */
        const val MAX_TAU = 0.97f

        fun level(rung: Rung): Level = when (rung) {
            Rung.Isolated -> Level.Isolated
            Rung.Syllables -> Level.Syllable
            Rung.Sentences, Rung.Twister, Rung.Retell, Rung.Everyday -> Level.Sentence
            else -> Level.Word
        }

        fun position(rung: Rung): Position = when (rung) {
            Rung.WordsInitial -> Position.Initial
            Rung.WordsMedial -> Position.Medial
            Rung.WordsFinal -> Position.Final
            else -> Position.Any
        }

        fun group(t: Talker): TalkerGroup = when (t) {
            Talker.Child -> TalkerGroup.Child
            Talker.AdultHigh -> TalkerGroup.Woman
            Talker.AdultLow -> TalkerGroup.Man
        }

        fun jvmRequest(r: TakeRequest): JudgeRequest {
            val cal = r.context.calibration
            val child = r.context.talker == Talker.Child
            return JudgeRequest(
                sound = r.sound,
                level = level(r.level),
                text = if (r.level == Rung.Isolated) r.text.lowercase() else r.text,
                position = position(r.level),
                talker = group(r.context.talker),
                calibration = cal.anchorsJson?.let { runCatching { TalkerCalibration.fromJson(it) }.getOrNull() },
                childF0Hz = if (child) cal.childF0Hz else null,
            )
        }

        /** The RuntimeContext of plan 03 §7; offsets are clamped so `tauErr + offset ≤ 0.97`. */
        fun runtime(ctx: JudgeContext, judge: TakeJudge?, sound: SoundId): RuntimeContext {
            val cal = ctx.calibration
            val fusion = judge?.spec?.fusion?.get(sound)
            val err = cal.childErrOffset.mapValues { (k, off) ->
                val tau = fusion?.tauErr(k) ?: 0.85f
                off.coerceIn(0f, (MAX_TAU - tau).coerceAtLeast(0f))
            }
            return RuntimeContext(
                adultPresent = ctx.adultPresent,
                calibrationTier = cal.tier,
                childOkOffset = cal.childOkOffset,
                childErrOffset = err,
            )
        }

        /** S or Ș targets only (the anchors of `TalkerCalibration`): the longest frication inside the judged segment. */
        private fun anchorOf(r: TakeRequest, req: JudgeRequest, clip: Clip, segments: List<Pair<Double, Double>>): AnchorCandidate? {
            val place = when (r.sound) { SoundId.S -> SibilantPlace.S; SoundId.Sh -> SibilantPlace.SH; else -> return null }
            if (clip.isEmpty) return null
            val summary = runCatching { analyzeClip(clip.samples, clip.sampleRate, req.talker.voice, null) }.getOrNull() ?: return null
            val seg = segments.firstOrNull()
            val f = summary.fricatives
                .filter { fr -> seg == null || (fr.end > seg.first && fr.start < seg.second) }
                .maxByOrNull { it.end - it.start } ?: return null
            if (!f.centroidHz.isFinite() || !f.peakHz.isFinite() || !f.bandRatioDb.isFinite() || !f.tiltDb.isFinite()) return null
            return AnchorCandidate(place.rawValue, f.centroidHz, f.peakHz, f.bandRatioDb, f.tiltDb)
        }

        /** The spec from the APK assets (`assets/spec/…`, written by the `specAssets` build step). */
        fun fromAssets(assets: AssetManager): JudgeSpec {
            fun read(path: String) = assets.open("spec/$path").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val fusion = SoundId.entries.mapNotNull { runCatching { read("fusion/${JudgeSpec.fusionFileName(it)}") }.getOrNull() }
            return JudgeSpec.fromStrings(read("thresholds/population.json"), fusion, read("unlock.json"), read("feedback.json"))
        }

        /** The spec from the repository (JVM tests). */
        fun fromDir(dir: File): JudgeSpec = JudgeSpec.load(dir)
    }
}
