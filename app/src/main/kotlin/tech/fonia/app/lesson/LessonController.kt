package tech.fonia.app.lesson

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tech.fonia.app.TakeSources
import tech.fonia.app.content.LoadedContent
import tech.fonia.app.data.AttemptLog
import tech.fonia.app.data.TakeRecord
import tech.fonia.app.holdgames.HoldGame
import tech.fonia.app.holdgames.HoldInput
import tech.fonia.app.holdgames.Onsets
import tech.fonia.app.house.Exercise
import tech.fonia.app.judge.ChildFacing
import tech.fonia.app.judge.JudgeContext
import tech.fonia.app.judge.Judged
import tech.fonia.app.judge.TakeJudging
import tech.fonia.app.judge.TakeRequest
import tech.fonia.app.text.T
import tech.fonia.designsystem.components.MicCapsuleState
import tech.fonia.designsystem.components.StampKind
import tech.fonia.designsystem.letter.LetterCue
import tech.fonia.designsystem.letter.LetterDrive
import tech.fonia.designsystem.letter.LiveLetterState
import tech.fonia.lessons.ItemResult
import tech.fonia.lessons.LessonPlan
import tech.fonia.lessons.PlanItem
import tech.fonia.lessons.PlanStep
import tech.fonia.lessons.StepKind
import tech.fonia.live.AudioDirector
import tech.fonia.live.AudioState
import tech.fonia.live.AudioSuspendedException
import tech.fonia.live.Clip
import tech.fonia.live.LiveTarget
import tech.fonia.model.SoundId
import tech.fonia.model.Verdict
import tech.fonia.navigation.EndKey
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min

/** Where the attempt cycle is (chapter 01 §6.2). */
enum class Phase { Model, Listening, Thinking, Verdict, AdultAsk, Quiet, Paused, Choosing, Together, Break }

/** The stamp under the letter. */
data class Stamp(val kind: StampKind, val word: String)

/** What the adult row asks: the usual "A fost bine?", or (Q3) whether to show the child an instruction. */
enum class AdultQuestion { WasItGood, ShowInstruction }

/** Listen: what Foni said and what the child chose. */
data class ChoiceState(
    val word: String,
    val foil: String,
    val picture: String?,
    val pictureKey: String,
    val saidRight: Boolean,
    val answered: Boolean? = null,
) {
    val correct: Boolean? get() = answered?.let { it == saidRight }
}

/** A judged take: its verdict, what the judge gave, the row to save (or the id of the row already saved). */
private class Take(val verdict: Verdict, val record: TakeRecord?, val savedId: UUID? = null, val judged: Judged? = null, val clip: Clip? = null)

private sealed interface TakeOutcome {
    class Done(val take: Take) : TakeOutcome
    data object Retry : TakeOutcome
    data object Skip : TakeOutcome
}

/** What a finished run hands back: the LessonBuilder results, the counted takes, today's mission if one was given. */
data class RunResult(val plan: LessonPlan, val exercise: Exercise?, val results: List<ItemResult>, val counted: Int, val correct: Int, val missionItem: PlanItem?)

/**
 * Runs one LessonPlan (the guided session, or one exercise card as a block): every step type of chapter 01 §6.3 with
 * the take cycle — Foni's line (his clip through the audio director; text only when the clip is missing, the quiet
 * rule) → the child's turn (mic open, letter live) → judging → verdict → (adult) → next. The live band never goes
 * through state read in composition: [onFrame] feeds the [drive], the hold game and draw-only floats ([ring],
 * [micLevel], [tick]).
 */
class LessonController(
    val sound: SoundId,
    val sessionId: UUID,
    val plan: LessonPlan,
    val loaded: LoadedContent,
    val exercise: Exercise?,
    private val audio: AudioDirector,
    private val judge: TakeJudging,
    private val log: AttemptLog,
    private val tuning: LessonTuning,
    private val sources: TakeSources?,
    private val saveScope: CoroutineScope,
    private val demo: Boolean,
    micAllowed: Boolean,
    private val onFinished: (EndKey) -> Unit,
    private val onHome: () -> Unit,
    private val onResult: (RunResult) -> Unit = {},
    private val judgeContext: (adultSeen: Boolean) -> JudgeContext = { JudgeContext(adultPresent = it) },
    private val onAdultAnswered: () -> Unit = {},
    private val demoThumb: Boolean = true,
) {
    val content get() = loaded.content
    val letter = LiveLetterState()
    val word = LiveLetterState()
    val drive = LetterDrive().apply { inflation = REST_INFLATION }
    val game: HoldGame = HoldGame.of(sound)
    private val input = HoldInput()
    private val beatOnsets = Onsets()

    // ---- composition state (changes a few times per take)
    var phase by mutableStateOf(Phase.Model); private set
    var stepIndex by mutableIntStateOf(0); private set
    var step by mutableStateOf<PlanStep?>(plan.steps.firstOrNull()); private set
    var item by mutableStateOf<PlanItem?>(null); private set
    var line by mutableStateOf(""); private set
    var talking by mutableStateOf(false); private set
    var stamp by mutableStateOf<Stamp?>(null); private set
    var adultLit by mutableStateOf(false); private set
    var adultHint by mutableStateOf<String?>(null); private set
    var adultTrial by mutableStateOf<String?>(null); private set
    var adultQuestion by mutableStateOf(AdultQuestion.WasItGood); private set
    var micAvailable by mutableStateOf(micAllowed); private set
    var exitAsked by mutableStateOf(false)
    var lastAttempt by mutableStateOf<UUID?>(null); private set
    var choice by mutableStateOf<ChoiceState?>(null); private set
    /** Pairs: which card Foni is saying now (0 = first, 1 = second, -1 none); [pairTarget] = the one to say. */
    var pairSpoken by mutableIntStateOf(-1); private set
    var pairTarget by mutableStateOf<String?>(null); private set
    /** Retell: the picture whose line is being told. */
    var storyLine by mutableIntStateOf(-1); private set
    /** Mission: the card went to the adult. */
    var missionSent by mutableStateOf(false); private set

    // ---- draw-only state (read inside draw / layer lambdas only)
    val ring = mutableFloatStateOf(0f)
    val micLevel = mutableFloatStateOf(0f)
    val tick = mutableLongStateOf(0L)
    @Volatile var nudge = false; private set
    /** Syllable trains: bursts heard so far in this take (the wagons hop). */
    @Volatile var beats = 0; private set

    // ---- bookkeeping (main thread)
    private val detector = TakeEndDetector(tuning)
    @Volatile private var end: TakeEndDetector.End? = null
    private var popped = false
    private var bestHold = 0f
    private var lessonBestHold = 0f
    private var lastFrame = 0L
    private var correct = 0
    private var counted = 0
    private var quietRuns = 0
    private var adultAnswer: CompletableDeferred<Boolean>? = null
    private var adultSeen = false
    private var choiceAnswer: CompletableDeferred<Boolean>? = null
    private var doneTap: CompletableDeferred<Unit>? = null
    private val results = mutableListOf<ItemResult>()
    private var missionItem: PlanItem? = null

    val totalSteps: Int get() = plan.steps.size
    val autoAnswer: Boolean get() = demo || sources != null

    val capsule: MicCapsuleState
        get() = when {
            !micAvailable -> MicCapsuleState.Off
            phase == Phase.Listening -> MicCapsuleState.YourTurn
            phase == Phase.Thinking -> MicCapsuleState.Thinking
            else -> MicCapsuleState.Listen
        }

    val capsuleLabel: String
        get() = when (capsule) {
            MicCapsuleState.Off -> T.micOff
            MicCapsuleState.YourTurn -> T.micYourTurn
            MicCapsuleState.Thinking -> T.micThinking
            MicCapsuleState.Listen -> T.micListen
        }

    private val holdGoal: Float get() = step?.holdGoalSeconds?.toFloat() ?: tuning.holdGoalSeconds
    private val isHold: Boolean get() = item?.type == "hold"
    private fun state(): LiveLetterState = if (isHold || step?.kind == StepKind.Opening) letter else word

    /** Once per frame from the screen's frame loop: the only `board.latest()` reader. */
    fun onFrame(now: Long) {
        val dt = if (lastFrame == 0L) 0f else min(0.1f, (now - lastFrame) / 1e9f)
        lastFrame = now
        val listening = phase == Phase.Listening
        input.dt = dt
        input.listening = listening
        if (listening) {
            val s = audio.board.latest()
            input.level = s.level; input.speaking = s.speaking; input.fricative = s.fricative; input.sibilance = s.sibilance
            input.sNess = s.sNess; input.voicing = s.voicing; input.trill = s.trill; input.trillDetected = s.trillDetected
            input.onTargetNow = s.onTargetNow; input.holdSeconds = s.holdSeconds
            drive.feed(
                level = s.level, speaking = s.speaking, sNess = if (s.fricative) s.sNess else Float.NaN, fricative = s.sibilance,
                voicing = s.voicing, trill = s.trill, blow = s.blow, holdSeconds = s.holdSeconds,
            )
            micLevel.floatValue = max(s.level, s.levelSlow)
            if (item?.type == "train" && beatOnsets.feed(s.level, dt)) beats++
            if (isHold) {
                val p = game.progress
                if (!game.bursts) bestHold = max(bestHold, game.held)
                ring.floatValue = max(ring.floatValue * 0.92f, p)
                drive.inflation = 0.22f + 0.78f * p
                if (game.reached && !popped) { popped = true; letter.cue(LetterCue.Pop) } // silent: the mic is open
            } else {
                drive.inflation = 0.5f + 0.4f * s.level.coerceIn(0f, 1f)
            }
            nudge = detector.quietMillis(now) >= tuning.quietPulseMillis
            if (end == null) end = detector.onFrame(now, s.seq, s.speaking, evidence = s.fricative || s.voicing >= 0.35f || s.trillDetected)
        } else {
            input.level = 0f; input.speaking = false; input.fricative = false; input.voicing = 0f; input.trill = 0f
            input.trillDetected = false; input.onTargetNow = false
        }
        if (isHold || step?.kind == StepKind.Hold) game.update(input)
        tick.longValue = now
    }

    /** The whole run; cancelled when the screen goes away (the mic is always closed on the way out). */
    suspend fun run() {
        saveScope.launch { runCatching { log.startSession(sessionId, sound, exercise?.raw ?: plan.rung.raw) } }
        try {
            for ((i, s) in plan.steps.withIndex()) {
                stepIndex = i
                step = s
                item = null
                choice = null
                when (s.kind) {
                    StepKind.Opening -> opening(s)
                    StepKind.Move -> moveBreak(s)
                    StepKind.Warmup -> { intro(s); for (it in s.items) together(it) }
                    StepKind.Listen -> { intro(s); for (it in s.items) choose(it) }
                    else -> {
                        intro(s)
                        for (it in s.items) produce(s, it)
                        if (s.kind == StepKind.Final) say(s.lineKeys.getOrNull(1) ?: "end.1")
                    }
                }
            }
            finish(short = false)
        } catch (e: CancellationException) {
            throw e
        } finally {
            withContext(NonCancellable) { runCatching { audio.closeMic() } }
        }
    }

    // ---------------------------------------------------------------- Foni's voice

    /** Foni says a line: his clip if there is one (he "talks" while it plays), else the text alone for a reading pause. */
    private suspend fun say(key: String, fallback: String? = null) {
        val text = loaded.lineText(key) ?: fallback ?: return
        line = text
        playOrRead(loaded.clip(key), text)
    }

    private suspend fun playOrRead(clip: tech.fonia.live.PromptRef?, text: String) {
        if (clip != null) {
            talking = true
            val played = runCatching { audio.play(clip) }.getOrDefault(false)
            talking = false
            if (played) return
        }
        delay(readMillis(text))
    }

    private fun readMillis(text: String): Long = (700L + text.length * 45L).coerceIn(1000L, 2800L).let { if (demo) it / 2 else it }

    private suspend fun intro(s: PlanStep) {
        val key = s.lineKeys.firstOrNull() ?: return
        say(key, T.stepLine(s.kind, sound))
    }

    // ---------------------------------------------------------------- steps without the microphone

    private suspend fun opening(s: PlanStep) {
        phase = Phase.Model
        letter.cue(LetterCue.DropIn)
        for (key in s.lineKeys) say(key)
        letter.cue(LetterCue.Beat(0.8f))
        delay(300)
    }

    private suspend fun moveBreak(s: PlanStep) {
        phase = Phase.Break
        say(s.lineKeys.firstOrNull() ?: "move.1", T.moveLine)
        val tap = CompletableDeferred<Unit>().also { doneTap = it }
        withTimeoutOrNull(if (autoAnswer) 1500L else s.seconds.coerceIn(5, 15) * 1000L) { tap.await() }
        doneTap = null
    }

    /** Warm-up: do it with Foni; „Am făcut” (or by itself after a while). */
    private suspend fun together(it: PlanItem) {
        item = it
        phase = Phase.Together
        stamp = null
        val say = content.index.warmups.firstOrNull { w -> w.id == it.text }?.say ?: it.text
        line = say
        playOrRead(loaded.clip(it.voices.firstOrNull()), say)
        val tap = CompletableDeferred<Unit>().also { doneTap = it }
        withTimeoutOrNull(if (autoAnswer) 1200L else 9000L) { tap.await() }
        doneTap = null
        letter.cue(LetterCue.Correct)
        stamp = Stamp(StampKind.Correct, T.stampCorrect)
        delay(700)
        stamp = null
    }

    /** „Am făcut” / „Gata” for the warm-up and the movement break. */
    fun done() { doneTap?.complete(Unit) }

    /** Listen: Foni says the word right or mixed up; the child taps which. */
    private suspend fun choose(it: PlanItem) {
        item = it
        stamp = null
        val c = content.item(it.id)
        val right = it.variant != "foil"
        val st = ChoiceState(word = c?.text ?: it.text, foil = c?.foil ?: it.text, picture = c?.emoji, pictureKey = c?.icon ?: it.text, saidRight = right)
        choice = st
        phase = Phase.Model
        val said = if (right) st.word else st.foil
        val clip = loaded.clip(it.voices.firstOrNull())
        line = if (clip != null) T.listenAsk else T.listenSaysText(said)
        playOrRead(clip, line)
        line = T.listenAsk
        phase = Phase.Choosing
        val answer = CompletableDeferred<Boolean>().also { choiceAnswer = it }
        if (autoAnswer) saveScope.launch { delay(1400); answer.complete(right) }
        val tapped = answer.await()
        choiceAnswer = null
        choice = st.copy(answered = tapped)
        val ok = tapped == right
        phase = Phase.Verdict
        if (ok) {
            word.cue(LetterCue.Correct); letter.cue(LetterCue.Correct)
            stamp = Stamp(StampKind.Correct, T.stampCorrect)
            line = if (right) T.listenRight else T.listenCaught
            correct++
        } else {
            stamp = Stamp(StampKind.Almost, T.stampAlmost)
            line = T.listenAgain(st.word)
            // hear the difference: the right word once more
            loaded.clip(content.item(it.id)?.voice)?.let { runCatching { audio.play(it) } }
        }
        counted++
        if (it.countsForProgress) results += ItemResult(it.id, ok)
        delay(tuning.verdictMillis)
    }

    /** The child's tap in Listen: true = "Foni a zis bine". */
    fun chooseAnswer(saidRight: Boolean) { choiceAnswer?.complete(saidRight) }

    // ---------------------------------------------------------------- steps with the microphone

    private suspend fun produce(s: PlanStep, it: PlanItem) {
        item = it
        missionSent = false
        val mode = PlanRules.mode(s.kind, it)
        val takes = ItemTakes(it.takes, tuning.maxRetakes)
        var first = true
        while (true) {
            model(s, it, first)
            first = false
            val outcome = when {
                !micAvailable -> TakeOutcome.Done(thumbOnly(s, it))
                mode == ItemMode.MicAdult -> adultTake(s, it)
                else -> takeAndJudge(s, it)
            }
            val take = when (outcome) {
                TakeOutcome.Retry -> continue
                TakeOutcome.Skip -> { takes.skip(); break }
                is TakeOutcome.Done -> outcome.take
            }
            val effective = show(take, it)
            val next = takes.onVerdict(effective)
            if (next is ItemTakes.Next.Done) {
                if (next.counted) counted++
                if (it.countsForProgress && next.counted) results += ItemResult(it.id, next.success)
                if (it.type == "mission") { missionItem = it; missionSent = true; delay(900) }
                break
            }
        }
    }

    /** Before a take: Foni's model (the clip of the item, both words of a pair, the story told picture by picture). */
    private suspend fun model(s: PlanStep, it: PlanItem, first: Boolean) {
        phase = Phase.Model
        stamp = null
        adultLit = !micAvailable
        adultHint = null
        adultTrial = null
        adultQuestion = AdultQuestion.WasItGood
        drive.quiet()
        drive.listening = false
        drive.inflation = REST_INFLATION
        ring.floatValue = 0f
        beats = 0
        if (it.type == "hold") game.reset()
        state().cue(LetterCue.Rest)
        val c = content.item(it.id)
        when (it.type) {
            "hold" -> {
                line = c?.prompt ?: T.stepLine(StepKind.Hold, sound)
                if (first) playOrRead(loaded.clip(c?.promptVoice), line) else delay(tuning.modelMillis)
            }
            "pair" -> {
                val (target, other) = PlanRules.pairTarget(it, content) ?: (it.text to "")
                pairTarget = null
                line = T.pairListen
                if (first || it.model) {
                    for ((k, v) in it.voices.withIndex()) {
                        pairSpoken = k
                        val w = if (k == 0) (if (it.variant == "ba") c?.b else c?.a) else (if (it.variant == "ba") c?.a else c?.b)
                        playOrRead(loaded.clip(v), w ?: "")
                    }
                } else delay(tuning.modelMillis)
                pairSpoken = -1
                pairTarget = target
                line = T.pairSay(target)
                if (other.isNotEmpty()) delay(500)
            }
            "story" -> {
                line = T.retellListen
                val lines = c?.lines.orEmpty()
                for (k in lines.indices) {
                    storyLine = k
                    line = lines[k]
                    playOrRead(loaded.clip(c?.lineVoices?.getOrNull(k)), lines[k])
                }
                storyLine = -1
                line = T.retellYourTurn
            }
            "twister" -> {
                line = if (it.variant == "step") T.twisterFast else T.twisterSlow
                if (first && it.model) playOrRead(loaded.clip(it.voices.firstOrNull()), line) else delay(tuning.modelMillis)
            }
            "train" -> {
                line = T.trainLine
                state().cue(LetterCue.Beat(0.8f))
                delay(tuning.modelMillis)
            }
            "mission" -> {
                line = T.missionLine
                if (first) playOrRead(loaded.clip(it.voices.firstOrNull()), it.text) else delay(tuning.modelMillis)
            }
            else -> {
                line = if (it.type == "sentence") T.sentenceLine else T.wordsLine
                val clip = loaded.clip(it.voices.firstOrNull())
                if ((first && (it.model || s.kind == StepKind.Final)) && clip != null) playOrRead(clip, line)
                else { state().cue(LetterCue.Beat(0.8f)); delay(tuning.modelMillis) }
            }
        }
    }

    /** Opens the mic for one take and waits for its end. Null clip when the take did not happen. */
    private suspend fun record(s: PlanStep, it: PlanItem): Triple<Clip, Instant, TakeEndDetector.End>? {
        sources?.beforeTake(sound, it.type)
        popped = false
        bestHold = 0f
        end = null
        detector.reset(System.nanoTime(), PlanRules.maxSeconds(it, holdGoal), PlanRules.longTail(it))
        if (it.type == "hold") game.goal = if (game.bursts) plan.params.trainLength.coerceIn(3, 5).toFloat() else holdGoal
        val startedAt = Instant.now()
        try {
            audio.openMic(LiveTarget.of(sound))
        } catch (e: CancellationException) {
            throw e
        } catch (e: AudioSuspendedException) {
            pause(); return null
        } catch (e: Exception) {
            Log.w(TAG, "mic did not open: $e")
            micAvailable = false
            adultLit = true
            return null
        }
        drive.listening = true
        state().cue(LetterCue.Listen)
        phase = Phase.Listening
        var ended: TakeEndDetector.End? = null
        while (ended == null) {
            delay(16)
            if (audio.state.value is AudioState.Suspended) {
                runCatching { audio.closeMic() }
                drive.listening = false
                pause(); return null
            }
            ended = end
        }
        val clip = audio.closeMic()
        drive.listening = false
        drive.quiet()
        micLevel.floatValue = 0f
        nudge = false
        return Triple(clip, startedAt, ended)
    }

    private suspend fun silence(): TakeOutcome {
        quietRuns++
        phase = Phase.Model
        state().cue(LetterCue.Rest)
        if (quietRuns >= 2) {
            quietRuns = 0
            return if (askQuiet()) TakeOutcome.Retry else TakeOutcome.Skip
        }
        line = T.quietLine
        delay(1600)
        return TakeOutcome.Retry
    }

    private suspend fun takeAndJudge(s: PlanStep, it: PlanItem): TakeOutcome {
        val (clip, startedAt, ended) = record(s, it) ?: return TakeOutcome.Retry
        if (ended == TakeEndDetector.End.Silence) return silence()
        quietRuns = 0
        val endedAt = Instant.now()
        phase = Phase.Thinking
        val t0 = System.nanoTime()
        val budget = max(20_000L, (clip.duration * 1000).toLong() + 10_000L)
        val rung = PlanRules.judgeRung(s, it)
        val text = PlanRules.judgeText(it, sound, content)
        val request = TakeRequest(sound, rung, text, judgeContext(adultSeen))
        val judged = try {
            withTimeoutOrNull(budget) { judge.judge(request, clip) } ?: Judged(Verdict.Retake)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "judge failed: $e")
            Judged(Verdict.Unsure)
        }
        val spent = (System.nanoTime() - t0) / 1_000_000L
        if (spent < tuning.minThinkingMillis) delay(tuning.minThinkingMillis - spent)
        if (bestHold > lessonBestHold) lessonBestHold = bestHold
        Log.i(TAG, "take ${it.type} '$text' ($rung): ${judged.verdict} facing ${judged.facing} head ${(judged.detail["zipa"] as? tech.fonia.store.JSONValue.String)?.value} hold ${"%.2f".format(bestHold)}, end $ended, ${clip.duration} s")
        return TakeOutcome.Done(Take(
            judged.verdict, judged = judged, clip = clip, record = record(it, judged.verdict, startedAt, endedAt, clip.audioSource, ended.name),
        ))
    }

    private fun record(it: PlanItem, v: Verdict, startedAt: Instant, endedAt: Instant, source: String, endReason: String) = TakeRecord(
        sessionId = sessionId, sound = sound, step = step?.kind?.name?.lowercase() ?: it.type, text = it.text, verdict = v,
        holdSeconds = bestHold, startedAt = startedAt, endedAt = endedAt,
        audioSource = sources?.label ?: source, endReason = endReason, itemId = it.id, itemType = it.type,
    )

    /** Twister and story: the mic lets the letter answer; the adult decides (thumb), nobody else. */
    private suspend fun adultTake(s: PlanStep, it: PlanItem): TakeOutcome {
        val (clip, startedAt, ended) = record(s, it) ?: return TakeOutcome.Retry
        if (ended == TakeEndDetector.End.Silence) return silence()
        quietRuns = 0
        val id = save(record(it, Verdict.Unsure, startedAt, Instant.now(), clip.audioSource, ended.name), null, clip)
        lastAttempt = id
        phase = Phase.AdultAsk
        adultLit = true
        adultHint = T.adultJudges
        line = T.adultTurnLine
        val answer = CompletableDeferred<Boolean>().also { adultAnswer = it }
        if (demo) saveScope.launch { delay(1600); answer.complete(true) }
        val good = withTimeoutOrNull(tuning.unsureAdultMillis) { answer.await() }
        adultAnswer = null
        adultLit = false
        if (id != null && good != null) answer(id, good)
        val v = when (good) { true -> Verdict.Correct; false -> Verdict.Almost; null -> Verdict.Unsure }
        return TakeOutcome.Done(Take(v, record = null, savedId = id))
    }

    /** No microphone: the child says it, the adult answers with the thumb (never blocks the path). */
    private suspend fun thumbOnly(s: PlanStep, it: PlanItem): Take {
        phase = Phase.AdultAsk
        adultLit = true
        val startedAt = Instant.now()
        val answer = CompletableDeferred<Boolean>().also { adultAnswer = it }
        val good = answer.await()
        adultAnswer = null
        val id = save(record(it, Verdict.Unsure, startedAt, Instant.now(), "none", "adult"))
        if (id != null) answer(id, good)
        return Take(if (good) Verdict.Correct else Verdict.Almost, record = null, savedId = id)
    }

    /** Shows the verdict and returns the effective one (an adult thumbs-up after Unsure counts as Correct). */
    private suspend fun show(take: Take, current: PlanItem): Verdict {
        val verdict = take.verdict
        val judged = take.judged
        phase = Phase.Verdict
        val st = state()
        st.cue(LetterCue.Rest)
        if (take.record != null) lastAttempt = save(take.record, judged, take.clip) else if (take.savedId != null) lastAttempt = take.savedId
        judged?.adult?.takeIf { it.inTrial }?.let { adultTrial = T.adultTrial(it.line) }
        var effective = verdict
        when (verdict) {
            Verdict.Correct -> celebrate(current)
            Verdict.Almost -> { st.cue(LetterCue.Almost); stamp = Stamp(StampKind.Almost, T.stampAlmost); line = T.almostLine }
            is Verdict.NamedError -> {
                val show = judged?.facing != ChildFacing.AdultFirst || askAdultFirst(judged)
                phase = Phase.Verdict
                if (show) {
                    st.cue(LetterCue.Instruct(verdict.kind, verdict.heardAs?.letter))
                    stamp = Stamp(StampKind.Instruction, sound.letter)
                    line = judged?.childLine ?: T.instruction(verdict.kind, sound)
                    adultHint = judged?.adult?.line ?: T.adultHint(verdict.heardAs, sound)
                } else {
                    effective = Verdict.Almost
                    st.cue(LetterCue.Almost); stamp = Stamp(StampKind.Almost, T.stampAlmost); line = T.unsureLine
                    judged?.adult?.let { adultTrial = T.adultTrial(it.line) }
                }
            }
            Verdict.Unsure -> {
                if (judged == null) {
                    // adult-judged step without an answer: a kind word, nothing counted as good or not
                    st.cue(LetterCue.Rest); stamp = null; line = T.triedLine
                } else {
                    st.cue(LetterCue.Unsure); stamp = Stamp(StampKind.Unsure, "?"); line = T.unsureLine
                    adultLit = true
                    adultHint = judged.adult?.let { T.adultMaybe(it.heardAs, sound) } ?: T.adultHint(null, sound)
                    phase = Phase.AdultAsk
                    val answer = CompletableDeferred<Boolean>().also { adultAnswer = it }
                    if (demo) saveScope.launch { delay(1600); answer.complete(demoThumb) }
                    val good = withTimeoutOrNull(tuning.unsureAdultMillis) { answer.await() }
                    adultAnswer = null
                    adultLit = false
                    lastAttempt?.let { id -> if (good != null) answer(id, good) }
                    phase = Phase.Verdict
                    if (good == true) { effective = Verdict.Correct; st.cue(LetterCue.Rest); celebrate(current) }
                }
            }
            Verdict.Retake -> {
                st.cue(LetterCue.Retake); stamp = Stamp(StampKind.Retake, T.stampRetake)
                line = judged?.childLine ?: T.retakeLine
                adultHint = judged?.adultReason
            }
        }
        val unanswered = verdict == Verdict.Unsure && effective == Verdict.Unsure && judged != null
        delay(if (effective is Verdict.NamedError) tuning.instructMillis else if (unanswered) 600L else tuning.verdictMillis)
        if (effective == Verdict.Correct) correct++
        return effective
    }

    private suspend fun askAdultFirst(judged: Judged): Boolean {
        phase = Phase.AdultAsk
        adultLit = true
        adultQuestion = AdultQuestion.ShowInstruction
        adultHint = judged.adult?.line
        val answer = CompletableDeferred<Boolean>().also { adultAnswer = it }
        val heard = withTimeoutOrNull(tuning.unsureAdultMillis) { answer.await() }
        adultAnswer = null
        adultLit = false
        adultQuestion = AdultQuestion.WasItGood
        lastAttempt?.let { id -> if (heard != null) answer(id, !heard) }
        return heard == true
    }

    private fun answer(id: UUID, good: Boolean) {
        adultSeen = true
        saveScope.launch {
            runCatching { log.adult(id, good) }
            onAdultAnswered()
        }
    }

    private fun celebrate(current: PlanItem) {
        state().cue(LetterCue.Correct)
        stamp = Stamp(StampKind.Correct, T.stampCorrect)
        line = T.correctLine(sound, current.type == "hold")
    }

    private suspend fun save(record: TakeRecord, judged: Judged? = null, clip: Clip? = null): UUID? = try {
        log.save(record, judged, clip)
    } catch (e: Exception) {
        Log.e(TAG, "attempt not saved", e); null
    }

    /** The adult's thumb, from the band. */
    fun adult(good: Boolean) {
        val waiting = adultAnswer
        if (waiting != null && !waiting.isCompleted) { waiting.complete(good); return }
        val id = lastAttempt ?: return
        answer(id, good)
        if (good && phase == Phase.Verdict && stamp?.kind != StampKind.Correct) item?.let(::celebrate)
    }

    private var quietChoiceAnswer: CompletableDeferred<Boolean>? = null

    private suspend fun askQuiet(): Boolean {
        phase = Phase.Quiet
        line = T.quietLine
        val c = CompletableDeferred<Boolean>().also { quietChoiceAnswer = it }
        if (autoAnswer) saveScope.launch { delay(1500); c.complete(false) }
        val more = c.await()
        quietChoiceAnswer = null
        return more
    }

    /** „Mai ascult” (true) or „Sărim” (false) after the second silence. */
    fun quietChoice(listenMore: Boolean) { quietChoiceAnswer?.complete(listenMore) }

    private suspend fun pause() {
        phase = Phase.Paused
        line = T.pausedLine
        drive.quiet()
        val c = CompletableDeferred<Boolean>().also { quietChoiceAnswer = it }
        c.await()
        quietChoiceAnswer = null
    }

    /** „Continuăm” after a pause. */
    fun resume() { quietChoiceAnswer?.complete(true) }

    /** „Gata” in the exit sheet: END if at least one take counted, else straight back. */
    fun leave() {
        exitAsked = false
        if (counted > 0) finish(short = true) else onHome()
    }

    private var finished = false

    private fun finish(short: Boolean) {
        if (finished) return
        finished = true
        saveScope.launch { runCatching { log.finishSession(sessionId) } }
        onResult(RunResult(plan, exercise, results.toList(), counted, correct, missionItem))
        onFinished(
            EndKey(
                sessionId = sessionId.toString(), sound = sound, attempts = counted, correct = correct,
                bestHoldMillis = (lessonBestHold * 1000).toInt(), short = short, exercise = exercise?.raw,
            ),
        )
    }

    companion object {
        private const val TAG = "FoniaLesson"
        const val REST_INFLATION = 0.42f
    }
}
