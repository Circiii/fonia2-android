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
import tech.fonia.judge.JudgeRequest
import tech.fonia.judge.JudgeSpec
import tech.fonia.judge.Level
import tech.fonia.judge.Position
import tech.fonia.judge.RuntimeContext
import tech.fonia.judge.TakeResult
import tech.fonia.judge.TalkerGroup
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

    /** Null when this take is not one the new judge decides (no target sound, English, a voice task...). */
    suspend fun judge(request: JudgeRequest, sound: SoundDef, take: CapturedClip, runtime: RuntimeContext = RuntimeContext()): Judged? {
        val judge = ready.await() ?: return null
        val clip = Clip(take.samples, take.sampleRate, take.dropped)
        val result: TakeResult = withContext(Dispatchers.Default) {
            val measured = judge.measure(request, clip)
            val fast = judge.decide(measured, runtime = runtime)
            val host = zipa
            if (fast.head == Head.Gate || fast.fastFinal || host == null || zipaOff) {
                fast
            } else {
                val phones = try {
                    host.score(clip, request)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
                phones?.let { judge.decide(measured, it, runtime = runtime) } ?: fast
            }
        }
        val judged = map(result, sound)
        Log.i(TAG, "take '${request.text}' ${request.sound.raw}/${request.level.raw}: ${judged.trace} -> ${judged.cue} \"${judged.verdict.title}\"")
        return judged
    }

    companion object {
        private const val TAG = "FoniaJudge"

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
        /** Ț, CE and GE: practised as bursts ("ț-ț-ț") at the isolated rung. */
        private val burstSounds = setOf(SoundID.Ts, SoundID.Ce, SoundID.Ge)

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
                    // "ț-ț-ț": the new judge has no rule for bursts yet, so his check judges them, leniently (§2.5).
                    if (p.level == RepeatPrompt.Level.Syllable && s in burstSounds && p.text.contains('-')) return null
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

        /** "Mai spune o dată": not an attempt, said again at once, with no question for anyone. */
        val retake = TakeVerdict(rating = null, outcome = Attempt.Outcome.Unclear, title = "Mai spune o dată", keepsTake = false)

        /**
         * The judge's answer in his words: Correct → his praise, Almost → his "Aproape" line, a named error → his
         * "A sunat ca …" line and the instruction animation (no tip sentence), Unsure or a capture gate → a retake.
         * Never a "wrong", never more than one short line.
         */
        fun map(result: TakeResult, sound: SoundDef): Judged {
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
                Verdict.Unsure, Verdict.Retake -> Judged(retake, Cue.Retake, trace = trace)
            }
        }
    }
}
