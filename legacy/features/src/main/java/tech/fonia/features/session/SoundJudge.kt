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
import kotlinx.coroutines.flow.first
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
import tech.fonia.ml.RoAsrHost
import tech.fonia.ml.ZipaHost
import tech.fonia.model.ErrorKind
import tech.fonia.model.SoundId
import tech.fonia.model.Verdict
import tech.fonia.store.Attempt
import tech.fonia.judge.TakeJudge as NewTakeJudge
import tech.fonia.words.WordMatcher
import tech.fonia.words.WordsAdvice
import tech.fonia.words.WordsCheck

/**
 * The new take judge (`tech.fonia.judge.TakeJudge`, speech/JUDGE.md) behind his verdict screens. The spec comes from the
 * APK (`assets/spec`, the `specAssets` step of legacy/app) and is parsed once off the main thread; ZIPA (`ZipaHost`,
 * ml/README.md) is the second head when its model is in the APK (`-Pfonia.zipa=false` leaves it out: fast head only).
 *
 * A session calls [warmUp] when it starts and [trim] when it ends; each Romanian sound take goes through [judge], which
 * returns his [TakeVerdict] (same texts, same shape) plus what the living letter should do ([Judged.cue]).
 */
class SoundJudge(
    scope: CoroutineScope,
    val zipa: ZipaHost?,
    /** The Romanian word recogniser, when the APK carries it: it checks that a sentence's words were said. */
    val words: RoAsrHost? = null,
    load: suspend () -> JudgeSpec,
) {
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
        runCatching { words?.trim() }
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
        val seconds = take.samples.size / take.sampleRate
        val budget = fullBudgetMs(seconds)
        // Sentences and single words: the word recogniser listens to the same take on its own thread while the sound
        // heads work. In a sentence it checks the words were said; for one word it refuses another word.
        val isSentence = request.level == tech.fonia.judge.Level.Sentence
        val heardWords = words?.takeIf { isSentence || request.level == tech.fonia.judge.Level.Word }?.let { host ->
            scope.async { host.hear(take.samples, take.sampleRate, timeoutMs = (2_000 + 400 * seconds).toLong()) }
        }
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
                // The first take of a session can come while the phoneme model is still loading: wait for it (up to
                // 8 s) so the budget below counts the judging only, not the loading. Otherwise the first take of every
                // session is judged by the fast head alone (seen on the emulator: a wrong word praised).
                if (host.state.value != tech.fonia.ml.HostState.Warm) {
                    if (host.state.value == tech.fonia.ml.HostState.Cold) warmUp()
                    kotlinx.coroutines.withTimeoutOrNull(8_000) {
                        host.state.first { it == tech.fonia.ml.HostState.Warm || it == tech.fonia.ml.HostState.Failed }
                    }
                }
                val t1 = System.nanoTime()
                val phones = try {
                    host.score(clip, measured.request, budget)
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
        val bySound = map(result, sound).copy(visual = result.visual, hint = hint, joinedReference = joined, dot = dot)
        val heardNow = heardWords?.await()
        val check = heardNow?.takeIf { isSentence }?.let { WordMatcher.check(request.text, it) }
        val judged = when {
            check != null -> withWords(bySound, check)
            // One word, praised by the sound heads, but the recogniser is sure another word was said („val” for
            // „pară”): not praised, said again. The practised sound itself is never the recogniser's business.
            heardNow != null && !isSentence && bySound.cue == Cue.Correct &&
                tech.fonia.words.OtherWord.isOther(request.text, request.sound.raw, heardNow) -> {
                Log.i(WORDS_TAG, "take '${request.text}' heard '${heardNow.text}' conf=${"%.2f".format(heardNow.confidence)}: another word, not praised")
                bySound.copy(
                    verdict = TakeVerdict(rating = null, outcome = Attempt.Outcome.Unclear, title = "Mai spune o dată", keepsTake = false),
                    cue = Cue.Retake, kind = null, hint = null, dot = null, joinedReference = false,
                    trace = "${bySound.trace} otherWord(asr='${heardNow.text}')",
                )
            }
            else -> {
                if (heardNow != null && !isSentence) Log.i(WORDS_TAG, "take '${request.text}' heard '${heardNow.text}' conf=${"%.2f".format(heardNow.confidence)}")
                bySound
            }
        }
        if (check != null) {
            val t = words?.lastTimings
            Log.i(
                WORDS_TAG,
                "take '${request.text}' heard '${check.heardText}' said=${check.said}/${check.words.size} " +
                    "abstain=${check.abstain ?: "-"} conf=${"%.2f".format(check.confidence)} [${check.trace()}] " +
                    "sound=${bySound.cue} -> ${judged.cue} \"${judged.verdict.title}\" " +
                    "asr=${t?.let { "%.0f".format(it.featureMs + it.modelMs) } ?: "-"}ms rtf=${t?.let { "%.3f".format(it.realTimeFactor) } ?: "-"}",
            )
        }
        val f0 = result.evidence.features[tech.fonia.judge.Features.F0]
        Log.i(
            TAG,
            "take '${request.text}' ${request.sound.raw}/${request.level.raw} talker=${used.raw} policy=${policy.raw} " +
                "f0=${f0?.let { "%.0f".format(it) } ?: "-"} head=${result.head.raw} fast=${fastMs}ms " +
                "full=${if (fullMs < 0) "-" else "${fullMs}ms"}${if (fullMs > budget) " (over budget)" else ""} " +
                "budget=${budget}ms: ${judged.trace} -> ${judged.cue} \"${judged.verdict.title}\" " +
                "place=${result.visual?.placeAxis?.let { "%.2f".format(it.value) + if (it.inZone) " in" else " out" } ?: "-"} " +
                "hint=${hint?.raw ?: result.visual?.hint?.key?.raw?.let { "($it withheld)" } ?: "-"} joined=$joined",
        )
        return judged
    }

    /**
     * The full head's budget for a take of [seconds]: 600 ms on phones (N6) plus 300 ms per second of take over one,
     * at most 1.5 s (`spec/pacing.json`); twice that on an x86 emulator. A fixed 600 ms sent the owner's long, correct
     * sentences back to the fast head (full=602ms) and so to "Mai spune o dată" (2026-11-24).
     */
    private fun fullBudgetMs(seconds: Double): Long = Pacing.current.judgeBudget(seconds, isEmulator)

    /**
     * What the words add to the sound verdict of a sentence. The recogniser never judges the sound; it only knows
     * which words were said. So: a sentence with words missing is not praised (he hears which word to add), and a
     * sentence the sound heads could not decide, but whose every word was heard, is accepted for what is true about
     * it. When the recogniser abstains (quiet, nothing, unsure), the sound verdict stands.
     */
    private fun withWords(bySound: Judged, check: WordsCheck): Judged {
        if (check.abstain != null || check.words.isEmpty()) return bySound
        val note = "words=${check.said}/${check.words.size}"
        return when {
            bySound.cue == Cue.Correct && !check.mostSaid() -> bySound.copy(
                verdict = TakeVerdict(
                    rating = Rating.nearly, outcome = Attempt.Outcome.Almost,
                    title = WordsAdvice.next(check) ?: "Mai spune o dată, cu toate cuvintele.",
                ),
                cue = Cue.Almost, trace = "${bySound.trace} $note",
            )
            // In a sentence the sound is short by nature: "too short / not heard" against every word heard (the word
            // with the sound included, written with its letter) is the length rule misfiring, not a missing sound.
            // Seen with a clear adult voice: „Păpușa are o șapcă.” 4/4 words, p=0.97, yet "N-am auzit Ș-ul".
            (bySound.cue == Cue.Instruct || bySound.cue == Cue.Almost) && check.allSaid() &&
                (bySound.kind == tech.fonia.model.ErrorKind.WeakOrShort || bySound.kind == tech.fonia.model.ErrorKind.Omission) -> bySound.copy(
                verdict = TakeVerdict(rating = Rating.yes, outcome = Attempt.Outcome.Good, title = "Ai spus toată propoziția!"),
                cue = Cue.Correct, kind = null, hint = null, trace = "${bySound.trace} $note lengthRuleOverruled",
            )
            bySound.cue == Cue.Retake && check.allSaid() -> bySound.copy(
                verdict = TakeVerdict(rating = Rating.yes, outcome = Attempt.Outcome.Good, title = "Ai spus toată propoziția!"),
                cue = Cue.Correct, trace = "${bySound.trace} $note",
            )
            else -> bySound.copy(trace = "${bySound.trace} $note")
        }
    }

    companion object {
        private const val TAG = "FoniaJudge"
        private const val WORDS_TAG = "FoniaWords"

        fun live(context: Context, scope: CoroutineScope): SoundJudge {
            val app = context.applicationContext
            val zipa = runCatching { ZipaHost.create(app) }.onFailure { Log.i(TAG, "no ZIPA: $it") }.getOrNull()
            val words = runCatching { RoAsrHost.create(app) }.onFailure { Log.i(WORDS_TAG, "no word recogniser: $it") }.getOrNull()
            Log.i(WORDS_TAG, if (words != null) "word recogniser in this APK: ${words.version.tag}" else "this APK has no word recogniser")
            if (tech.fonia.features.IS_DEBUG_BUILD && words != null) benchWords(app, scope, words)
            return SoundJudge(scope, zipa, words) { fromAssets(app.assets) }
        }

        /**
         * Debug builds: every WAV (16-bit PCM) named `asrbench_1.wav` … `asrbench_9.wav` in the app's external `files/` goes through the word
         * recogniser once at start, and the log says what it heard and how long it took on this phone.
         */
        private fun benchWords(app: Context, scope: CoroutineScope, words: RoAsrHost) {
            // By name, not by listing; the app's own folder first (filled with `run-as`), then the external one.
            val dirs = listOfNotNull(app.filesDir, app.getExternalFilesDir(null))
            val files = (1..9).mapNotNull { n -> dirs.map { java.io.File(it, "asrbench_$n.wav") }.firstOrNull { it.canRead() } }
            Log.i(WORDS_TAG, "bench: ${files.size} clip(s) found")
            if (files.isEmpty()) return
            scope.launch(Dispatchers.IO) {
                for (file in files) {
                    val bytes = file.readBytes()
                    val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    val channels = buffer.getShort(22).toInt().coerceAtLeast(1)
                    val rate = buffer.getInt(24).toDouble()
                    var at = 12
                    while (at + 8 <= bytes.size && String(bytes, at, 4, Charsets.US_ASCII) != "data") at += 8 + buffer.getInt(at + 4)
                    if (at + 8 > bytes.size) continue
                    val count = minOf(buffer.getInt(at + 4), bytes.size - at - 8) / 2 / channels
                    val samples = FloatArray(count) { buffer.getShort(at + 8 + it * 2 * channels) / 32768f }
                    val t0 = System.nanoTime()
                    val heard = words.hear(samples, rate, timeoutMs = 120_000)
                    val wall = (System.nanoTime() - t0) / 1e6
                    val t = words.lastTimings
                    Log.i(
                        WORDS_TAG,
                        "bench ${file.name} ${"%.1f".format(count / rate)}s wall=${"%.0f".format(wall)}ms load=${"%.0f".format(t.loadMs)}ms " +
                            "features=${"%.0f".format(t.featureMs)}ms model=${"%.0f".format(t.modelMs)}ms rtf=${"%.3f".format(t.realTimeFactor)} " +
                            "conf=${heard?.let { "%.2f".format(it.confidence) } ?: "-"} -> '${heard?.text ?: "(nothing)"}'",
                    )
                }
            }
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

        /** The sound a sibilant is usually confused with by place (his `VerdictWriter.rival`); null for R and L. */
        internal fun placeRival(id: SoundID): String? = when (id) {
            SoundID.S -> "Ș"
            SoundID.Sh -> "S"
            SoundID.Z -> "J"
            SoundID.J -> "Z"
            SoundID.Ts -> "CE"
            SoundID.Ce -> "Ț"
            SoundID.Ge -> "Z sau J"
            SoundID.R, SoundID.L -> null
        }

        /** What "A sunat ca X" may say for this target and kind: only a swap the sound can really make. */
        private fun swapHeard(id: SoundID, kind: ErrorKind, heardAs: SoundId?): String? {
            val heard = heardAs?.letter
            return when (kind) {
                ErrorKind.PlaceSwap, ErrorKind.AffricatePlace -> {
                    val rival = placeRival(id) ?: return null
                    // The judge's own "heard as" only when it is that rival (GE: Z or J); anything else is not named.
                    heard?.takeIf { rival.split(" sau ").contains(it) } ?: rival
                }
                ErrorKind.RtoL -> if (id == SoundID.R) "L" else null
                ErrorKind.LtoR -> if (id == SoundID.L) "R" else null
                else -> null
            }
        }

        /**
         * The one line under a named error, per target sound (owner, 2026-11-24: an L sentence said "A sunat ca S").
         * Only a swap the sound can really make is named; every other kind says what to do, in at most 8 words.
         */
        fun namedTitle(id: SoundID, letter: String, kind: ErrorKind, heardAs: SoundId?): String {
            swapHeard(id, kind, heardAs)?.let { return "Aproape. A sunat ca $it" }
            return when (kind) {
                ErrorKind.Omission -> "N-am auzit $letter-ul"
                ErrorKind.WeakOrShort -> "Sună puțin moale"
                ErrorKind.Devoicing -> "Pornește vocea"
                ErrorKind.Stopping -> "Aproape. Lasă aerul să curgă"
                ErrorKind.Deaffrication -> "Aproape. Atinge, apoi lasă aerul"
                ErrorKind.Lateral -> "Aproape. Aerul pe mijloc"
                ErrorKind.Interdental -> "Aproape. Limba după dinți"
                ErrorKind.RtoGlide, ErrorKind.RtoVowel -> "Aproape. Vârful limbii sus"
                ErrorKind.RUvular -> "Aproape. Vârful limbii lucrează"
                ErrorKind.LtoGlide -> "Aproape. Lipește limba sus"
                // Only when the kind does not belong to this sound (an R swap on an L): a plain "almost".
                ErrorKind.PlaceSwap, ErrorKind.AffricatePlace, ErrorKind.RtoL, ErrorKind.LtoR -> "Aproape. Încă o dată, mai clar"
            }
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
                    val title = namedTitle(sound.id, sound.shortLetter, v.kind, v.heardAs)
                    Judged(
                        TakeVerdict(rating = Rating.nearly, outcome = Attempt.Outcome.Almost, title = title, pose = sound.viseme),
                        Cue.Instruct, kind = v.kind, heard = swapHeard(sound.id, v.kind, v.heardAs), trace = trace,
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
                // A capture gate says what to change, so "say it again" is never a riddle (max 8 words, nothing blamed).
                Verdict.Retake -> Judged(
                    when (result.retakeReason) {
                        tech.fonia.judge.RetakeReason.TooQuiet -> retake.copy(title = "Mai tare și mai aproape de telefon")
                        tech.fonia.judge.RetakeReason.NoSpeech -> retake.copy(title = "Nu te-am auzit. Mai spune o dată")
                        tech.fonia.judge.RetakeReason.Clipped -> retake.copy(title = "Puțin mai încet sau mai departe")
                        tech.fonia.judge.RetakeReason.TooShort -> retake.copy(title = "Ține sunetul puțin mai mult")
                        tech.fonia.judge.RetakeReason.Noisy -> retake.copy(title = "E zgomot. Vino mai aproape")
                        else -> retake
                    },
                    Cue.Retake, trace = trace,
                )
            }
        }
    }
}
