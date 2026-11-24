package tech.fonia.features.session

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.fonia.audio.CapturedClip
import tech.fonia.design.Rating
import tech.fonia.domain.Exercise
import tech.fonia.domain.Language
import tech.fonia.domain.RepeatPrompt
import tech.fonia.domain.Rung
import tech.fonia.domain.SessionFocus
import tech.fonia.domain.SoundDef
import tech.fonia.domain.SoundID
import tech.fonia.dsp.TalkerCalibration
import tech.fonia.dsp.VoiceGroup
import tech.fonia.features.app.shortLetter
import tech.fonia.judge.Head
import tech.fonia.judge.HintKey
import tech.fonia.judge.PersonalReference
import tech.fonia.judge.PolicyLevel
import tech.fonia.judge.TakeVisual
import tech.fonia.judge.JudgeRequest
import tech.fonia.judge.JudgeSpec
import tech.fonia.judge.Level
import tech.fonia.judge.Position
import tech.fonia.judge.RuntimeContext
import tech.fonia.judge.TakeResult
import tech.fonia.judge.TalkerGroup
import tech.fonia.judge.TalkerTracker
import tech.fonia.judge.measureForVoice
import tech.fonia.live.Clip
import tech.fonia.ml.ZipaHost
import tech.fonia.model.ErrorKind
import tech.fonia.model.SoundId
import tech.fonia.model.Verdict
import tech.fonia.store.Attempt
import tech.fonia.judge.TakeJudge as NewTakeJudge

/**
 * The new take judge (`tech.fonia.judge.TakeJudge`, speech/JUDGE.md) behind his verdict screens. The spec comes from the
 * APK (`assets/spec`, the `specAssets` step of legacy/app) and is parsed once off the main thread; ZIPA (`ZipaHost`,
 * ml/README.md) is the second head when its model is in the APK (`-Pfonia.zipa=false` leaves it out: fast head only).
 *
 * A session calls [warmUp] when it starts and [trim] when it ends; each Romanian sound take goes through [judge], which
 * returns his [TakeVerdict] (same texts, same shape) plus what the living letter should do ([Judged.cue]).
 */
class SoundJudge(scope: CoroutineScope, private val zipa: ZipaHost?, load: suspend () -> JudgeSpec) {
    private val ready: Deferred<NewTakeJudge?> = scope.async(Dispatchers.IO) {
        runCatching { NewTakeJudge(load()) }.onFailure { Log.w(TAG, "no judge spec: $it") }.getOrNull()
    }
    private val scope = scope

    /** Set when the model could not load (no model in the APK, an emulator without it): the fast head decides. */
    @Volatile
    private var zipaOff = zipa == null

    /** What the letter does with a verdict. */
    enum class Cue { Correct, Almost, Instruct, Retake }

    data class Judged(
        val verdict: TakeVerdict,
        val cue: Cue,
        val kind: ErrorKind? = null,
        val heard: String? = null,
        /** For the log: which head decided and with what confidence. */
        val trace: String = "",
        /** How sure the judge is that this verdict is right (0..1): p(correct) for a good take, 1 - p(correct) for "almost". */
        val certainty: Float? = null,
        /** What the take looked like (the strip under the outcome word draws it); null for a gate. */
        val visual: TakeVisual? = null,
        /** The one hint to show, only when `unlock.hintAllowed` says so for this person's policy. */
        val hint: HintKey? = null,
        /** True when this take joined the person's own reference (the caller saves it). */
        val joinedReference: Boolean = false,
        /** The few numbers of [visual] to keep for his progress (null for a gate: nothing was measured). */
        val dot: TakeDot? = null,
    )

    fun warmUp() {
        val host = zipa ?: return
        if (zipaOff) return
        scope.launch(Dispatchers.Default) {
            try {
                host.warmUp()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                zipaOff = true
                Log.i(TAG, "ZIPA off (fast head only): $failure")
            }
        }
    }

    fun trim() {
        runCatching { zipa?.trim() }
    }

    /**
     * Null when this take is not one the new judge decides (no target sound, English, a voice task...).
     *
     * The hooks of speech/JUDGE.md (v2): [personal] measures against his own reference first and learns from takes
     * both heads accept ([Judged.joinedReference]: the caller saves it); the policy is chosen from the voice heard in
     * this take ([PolicyLevel.select]); the full head (ZIPA) runs on every take it can, within [fullBudgetMs], so the
     * reference can grow and the place evidence is the phoneme model's.
     */
    suspend fun judge(
        request: JudgeRequest,
        sound: SoundDef,
        take: CapturedClip,
        runtime: RuntimeContext = RuntimeContext(),
        /** The speaker's voice so far: the take is judged with the group heard in it, and moves the tracker. */
        talker: TalkerTracker? = null,
        /** His own reference (anchors, group, loudness); not thread-safe, so used under its own lock. */
        personal: PersonalReference? = null,
        profileIsAdult: Boolean = false,
    ): Judged? {
        val judge = ready.await() ?: return null
        val clip = Clip(take.samples, take.sampleRate, take.dropped)
        var used = request.talker
        var policy = runtime.policy
        var fastMs = 0L
        var fullMs = -1L
        var joined = false
        var hint: HintKey? = null
        val result: TakeResult = withContext(Dispatchers.Default) {
            val t0 = System.nanoTime()
            val base = personal?.let { synchronized(it) { it.apply(request) } } ?: request
            // His reference knows his group once it has three accepted voiced takes; until then the voice tracker does.
            val knowsGroup = personal?.let { synchronized(it) { it.talker() } } != null
            val measured = if (talker != null && !knowsGroup) {
                synchronized(talker) { judge.measureForVoice(base, clip, talker) }
            } else {
                judge.measure(base, clip)
            }
            used = measured.request.talker
            val rt = runtime.copy(policy = PolicyLevel.select(used, profileIsAdult))
            policy = rt.policy
            val fast = judge.decide(measured, runtime = rt)
            fastMs = (System.nanoTime() - t0) / 1_000_000
            val host = zipa
            val decided = if (fast.head == Head.Gate || host == null || zipaOff) {
                fast
            } else {
                val t1 = System.nanoTime()
                val phones = try {
                    host.score(clip, measured.request, fullBudgetMs)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
                fullMs = (System.nanoTime() - t1) / 1_000_000
                phones?.let { judge.decide(measured, it, runtime = rt) } ?: fast
            }
            if (personal != null) joined = synchronized(personal) { personal.update(decided) }
            if (judge.unlock.hintAllowed(decided, rt)) hint = decided.visual?.hint?.key
            decided
        }
        val dot = if (result.head == Head.Gate) null else TakeDot.of(result.visual, request.sound.raw, result.verdict, System.currentTimeMillis())
        val judged = map(result, sound).copy(visual = result.visual, hint = hint, joinedReference = joined, dot = dot)
        val f0 = result.evidence.features[tech.fonia.judge.Features.F0]
        Log.i(
            TAG,
            "take '${request.text}' ${request.sound.raw}/${request.level.raw} talker=${used.raw} policy=${policy.raw} " +
                "f0=${f0?.let { "%.0f".format(it) } ?: "-"} head=${result.head.raw} fast=${fastMs}ms " +
                "full=${if (fullMs < 0) "-" else "${fullMs}ms"}${if (fullMs > fullBudgetMs) " (over budget)" else ""} " +
                "budget=${fullBudgetMs}ms: ${judged.trace} -> ${judged.cue} \"${judged.verdict.title}\" " +
                "place=${result.visual?.placeAxis?.let { "%.2f".format(it.value) + if (it.inZone) " in" else " out" } ?: "-"} " +
                "hint=${hint?.raw ?: result.visual?.hint?.key?.raw?.let { "($it withheld)" } ?: "-"} joined=$joined",
        )
        return judged
    }

    /** The full head's budget: 600 ms on phones (N6); an x86 emulator runs ZIPA slower, so it gets 1,200 ms. */
    private val fullBudgetMs: Long get() = if (isEmulator) EMULATOR_FULL_BUDGET_MS else PHONE_FULL_BUDGET_MS

    companion object {
        private const val TAG = "FoniaJudge"
        const val PHONE_FULL_BUDGET_MS = 600L
        const val EMULATOR_FULL_BUDGET_MS = 1200L

        fun live(context: Context, scope: CoroutineScope): SoundJudge {
            val app = context.applicationContext
            val zipa = runCatching { ZipaHost.create(app) }.onFailure { Log.i(TAG, "no ZIPA: $it") }.getOrNull()
            return SoundJudge(scope, zipa) { fromAssets(app.assets) }
        }

        fun fromAssets(assets: AssetManager): JudgeSpec {
            fun read(path: String) = assets.open("spec/$path").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val fusion = SoundId.entries.mapNotNull { runCatching { read("fusion/${JudgeSpec.fusionFileName(it)}") }.getOrNull() }
            return JudgeSpec.fromStrings(read("thresholds/population.json"), fusion, read("unlock.json"), read("feedback.json"))
        }

        fun soundId(id: SoundID): SoundId = when (id) {
            SoundID.S -> SoundId.S
            SoundID.Sh -> SoundId.Sh
            SoundID.Z -> SoundId.Z
            SoundID.J -> SoundId.J
            SoundID.Ts -> SoundId.Ts
            SoundID.Ce -> SoundId.Ce
            SoundID.Ge -> SoundId.Ge
            SoundID.R -> SoundId.R
            SoundID.L -> SoundId.L
        }

        fun talker(group: VoiceGroup): TalkerGroup = when (group) {
            VoiceGroup.CHILD -> TalkerGroup.Child
            VoiceGroup.FEMALE -> TalkerGroup.Woman
            VoiceGroup.MALE -> TalkerGroup.Man
        }

        /**
         * What the new judge is asked for this exercise, or null when it is not its kind of take: a Romanian word,
         * syllable or sentence with a target sound.
         */
        fun request(
            exercise: Exercise,
            focus: SessionFocus,
            group: VoiceGroup,
            calibration: TalkerCalibration?,
        ): JudgeRequest? {
            val rung = (focus as? SessionFocus.Sound)?.rung
            val position = when (rung) {
                Rung.WordsInitial -> Position.Initial
                Rung.WordsMedial -> Position.Medial
                Rung.WordsFinal -> Position.Final
                else -> Position.Any
            }
            val (sound, text, level) = when (exercise) {
                is Exercise.Repeat -> {
                    val p = exercise.prompt
                    if (p.language != Language.Ro) return null
                    val s = p.sound ?: return null
                    // "ț-ț-ț", "ci-ci-ci", "ge-ge-ge": the judge v2 has a burst rule (Level.Syllable with '-').
                    Triple(s, p.text, when (p.level) {
                        RepeatPrompt.Level.Syllable -> Level.Syllable
                        RepeatPrompt.Level.Sentence -> Level.Sentence
                        else -> Level.Word
                    })
                }
                is Exercise.Name -> Triple(exercise.sound, exercise.item.text, Level.Word)
                is Exercise.Twister -> {
                    if (exercise.language != Language.Ro) return null
                    Triple(exercise.sound ?: return null, exercise.text, Level.Sentence)
                }
                else -> return null
            }
            return JudgeRequest(
                sound = soundId(sound),
                level = level,
                text = text,
                position = if (level == Level.Word) position else Position.Any,
                talker = talker(group),
                calibration = calibration,
            )
        }

        /** The sound this one is usually confused with (his `VerdictWriter.rival`). */
        private fun rival(id: SoundID): String = when (id) {
            SoundID.S -> "Ș"
            SoundID.Sh -> "S"
            SoundID.Z -> "J"
            SoundID.J -> "Z"
            SoundID.Ts -> "CE"
            SoundID.Ce -> "Ț"
            SoundID.Ge -> "Z sau J"
            SoundID.R, SoundID.L -> "S"
        }

        /** A trial kind this likely is shown as an instruction (see [map]). */
        const val TRIAL_INSTRUCT_AT = 0.9f

        /** "Mai spune o dată": not an attempt, said again at once, with no question for anyone. */
        val retake = TakeVerdict(rating = null, outcome = Attempt.Outcome.Unclear, title = "Mai spune o dată", keepsTake = false)

        /**
         * The judge's answer in his words: Correct → his praise, Almost → his "Aproape" line, a named error → his
         * "A sunat ca …" line and the instruction animation (no tip sentence), Unsure or a capture gate → a retake.
         * Never a "wrong", never more than one short line.
         */
        fun map(result: TakeResult, sound: SoundDef): Judged = mapVerdict(result, sound).copy(
            certainty = when (result.verdict) {
                Verdict.Almost -> 1f - result.confidence
                else -> result.confidence
            }.coerceIn(0f, 1f),
        )

        private fun mapVerdict(result: TakeResult, sound: SoundDef): Judged {
            val trace = "${result.head.raw} p=${"%.2f".format(result.confidence)}" + (result.retakeReason?.let { " retake=${it.raw}" } ?: "")
            return when (val v = result.verdict) {
                Verdict.Correct -> Judged(
                    TakeVerdict(rating = Rating.yes, outcome = Attempt.Outcome.Good, title = Praise.short.random()),
                    Cue.Correct, trace = trace,
                )
                Verdict.Almost -> Judged(
                    TakeVerdict(rating = Rating.nearly, outcome = Attempt.Outcome.Almost, title = "Aproape. Încă o dată, mai clar"),
                    Cue.Almost, trace = trace,
                )
                is Verdict.NamedError -> {
                    val heard = v.heardAs?.letter ?: rival(sound.id)
                    val title = when (v.kind) {
                        ErrorKind.Omission -> "N-am auzit ${sound.shortLetter}-ul"
                        ErrorKind.WeakOrShort -> "Sună puțin moale"
                        ErrorKind.Devoicing -> "Pornește vocea"
                        else -> "Aproape. A sunat ca $heard"
                    }
                    Judged(
                        TakeVerdict(rating = Rating.nearly, outcome = Attempt.Outcome.Almost, title = title, pose = sound.viseme),
                        Cue.Instruct, kind = v.kind, heard = v.heardAs?.letter, trace = trace,
                    )
                }
                Verdict.Unsure -> {
                    // A kind still "în probă" (spec/unlock.json) that the phone is sure of: no adult is asked any more,
                    // so the letter shows what to do (its instruction animation) under a kind "almost", without naming
                    // what was heard. Anything less sure is said again.
                    val note = result.adultNote
                    if (note != null && note.inTrial && note.probability >= TRIAL_INSTRUCT_AT) {
                        Judged(
                            TakeVerdict(rating = Rating.nearly, outcome = Attempt.Outcome.Almost, title = "Aproape. Uite cum", pose = sound.viseme),
                            Cue.Instruct, kind = note.kind, heard = null, trace = "$trace trial=${note.kind.raw}",
                        )
                    } else {
                        Judged(retake, Cue.Retake, trace = trace)
                    }
                }
                Verdict.Retake -> Judged(retake, Cue.Retake, trace = trace)
            }
        }
    }
}
