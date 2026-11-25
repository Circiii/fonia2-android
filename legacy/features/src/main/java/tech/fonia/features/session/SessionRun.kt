package tech.fonia.features.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tech.fonia.audio.CapturedClip
import tech.fonia.audio.RecordingWriter
import tech.fonia.design.Field
import tech.fonia.design.Rating
import tech.fonia.domain.AttemptsSummary
import tech.fonia.domain.Exercise
import tech.fonia.domain.Language
import tech.fonia.domain.FeedbackLevel
import tech.fonia.domain.Progression
import tech.fonia.domain.RepeatPrompt
import tech.fonia.domain.SustainRole
import tech.fonia.domain.ProgressionState
import tech.fonia.domain.ProgressionStep
import tech.fonia.domain.Rung
import tech.fonia.domain.Session
import tech.fonia.domain.SessionBlock
import tech.fonia.domain.SessionBuilder
import tech.fonia.domain.SessionFocus
import tech.fonia.domain.SessionRequest
import tech.fonia.domain.SoundID
import tech.fonia.domain.Track
import tech.fonia.domain.Trial
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.SessionTicket
import tech.fonia.features.app.SoundEffect
import tech.fonia.features.app.domainAge
import tech.fonia.features.app.field
import tech.fonia.features.app.ignoringFailure
import tech.fonia.features.app.index
import tech.fonia.features.app.isChild
import tech.fonia.features.app.progressionState
import tech.fonia.features.adaptive.AdaptiveRun
import tech.fonia.features.pilot.PilotHooks
import tech.fonia.features.pilot.logAttempt
import tech.fonia.features.pilot.logSessionEnd
import tech.fonia.features.pilot.logSessionStart
import tech.fonia.store.Attempt
import tech.fonia.store.JSONValue
import tech.fonia.store.Profile
import tech.fonia.store.Recording
import tech.fonia.store.SessionRecord
import tech.fonia.store.SoundProgress
import java.time.Instant
import java.util.UUID
import kotlin.math.max
import kotlin.random.Random

/**
 * One practice session from first trial to the end screen. Screens read it, and it talks to the store and the
 * progression rules, so a view never has to know how a block ends.
 *
 * `@Observable @MainActor` on iOS: the values the screens read are snapshot state, and every call comes from the main
 * thread. What iOS saves in `Task`s runs in [writes], one at a time and in the order it was asked for, as main-actor
 * tasks start in order.
 */
class SessionRun(
    val ticket: SessionTicket,
    val profile: Profile,
    private val app: AppModel,
    progress: SoundProgress?,
    history: AttemptsSummary,
    /** Items the listening blocks of the last runs on this focus played: drawn last ([ListeningVariety]). */
    recentListening: Set<String> = emptySet(),
    /** Where the saving runs: the app's lifetime, as a `Task { }` outlives its screen. */
    private val writes: CoroutineScope = app.scope,
) {
    enum class Phase { Trial, Finished }

    /** iOS's session; for a profile in the adaptive pilot, its blocks are planned again after each one (PLAN §7.4). */
    var session: Session by mutableStateOf(Session(trials = emptyList(), targetAttempts = 0))
        private set
    val field: Field

    var index: Int by mutableIntStateOf(0)
        private set
    var phase: Phase by mutableStateOf(Phase.Trial)
        private set
    var attempts: Int by mutableIntStateOf(0)
        private set
    var goodAttempts: Int by mutableIntStateOf(0)
        private set
    var identified: Int by mutableIntStateOf(0)
        private set
    var identifiedRight: Int by mutableIntStateOf(0)
        private set

    /** Bumps when the same trial starts over, so its view restarts cleanly. */
    var retryToken: Int by mutableIntStateOf(0)
        private set
    var needsAdultNext: Rung? by mutableStateOf(null)
        private set

    /** The rung this session opened by his own success, for the recap ("Ai deschis …"). */
    var movedUpTo: Rung? by mutableStateOf(null)
        private set

    /** The summary at the end of a block whose trials said nothing in the moment. */
    var blockNote: String? by mutableStateOf(null)
        private set

    /** The hero's chapter for this lesson, told to a child as the lesson opens. Null when there is none. */
    val chapter: String?

    /**
     * True while the chapter is told before the first trial. When the session opens on the hero's intro, the intro
     * tells the chapter itself and this stays false.
     */
    var isTellingChapter: Boolean by mutableStateOf(false)
        private set

    /** The latest short item the learner said well in this session, kept for the closing trial. */
    private var lastSaidWell: Exercise? = null

    // `@ObservationIgnored`: plain fields, read by no screen.
    private val startedNanos = System.nanoTime()
    private val record: SessionRecord
    private var state: ProgressionState

    /** Going back over an earlier rung is practice. It can't move the learner anywhere. */
    private val isReview: Boolean
    private var progress: SoundProgress? = progress
    private var blockGood = 0
    private var blockTotal = 0
    private var didStoreSession = false

    /** One finish per trial, however many taps and timers race for it. */
    private var trialSettled = false
    private var isClosed = false

    /** Keeps the saves in order: a fair lock, taken in the order the saves are started. */
    private val saving = Mutex()

    /** The adaptive pilot's part (docs/PLAN.md §7.4), set in [start] for a profile switched on; null keeps iOS's run. */
    private var adaptive: AdaptiveRun? = null
    private val history: AttemptsSummary = history

    init {
        // `UInt64.random(in: .min ... .max)`.
        val seed = Random.nextLong().toULong()
        // iOS builds with the bundled clips: a recorded English sentence is shadowed before its solo take.
        val builder = SessionBuilder(app.content) { app.audio.hasClip(it, Language.En) }
        val avoiding = profile.targetSounds.mapNotNull { SoundID.fromRawValue(it) }.toSet() - setOfNotNull(ticket.sound)
        var built = builder.build(
            SessionRequest(age = profile.domainAge, focus = ticket.focus, history = history, seed = seed, avoidingSounds = avoiding),
        )
        // `#if DEBUG`: `LaunchArguments` only reads these two in a debug build.
        if (app.arguments.speakOnly) {
            built = Session(trials = built.trials.filter { it.exercise.isProduction }, targetAttempts = built.targetAttempts)
        }
        if (app.arguments.short) {
            built = Session(trials = built.trials.take(2), targetAttempts = 2)
        }
        // The quick check and the diction drills bring their own trials: every one is played, none is cut by a dose.
        ticket.custom?.let { custom ->
            val trials = if (app.arguments.short) custom.trials.take(4) else custom.trials
            built = Session(trials = trials, targetAttempts = 0, timeLimitSeconds = 3600)
        }
        // Listening items differ from run to run (the builder's own draw is kept for iOS parity; see ListeningVariety).
        if (ticket.custom == null) {
            built = runCatching {
                ListeningVariety(app.content, app.lessonContent, { app.audio.hasClip(it, Language.Ro) }, app.hasPicture)
                    .vary(built, ticket.focus, recentListening, kotlin.random.Random(seed.toLong()))
            }.getOrDefault(built)
        }
        // A hero with a story is for children. Adults start at the mouth.
        if (!profile.isChild && ticket.custom == null) {
            built = Session(trials = built.trials.filter { it.exercise !is Exercise.Intro }, targetAttempts = built.targetAttempts)
        }
        session = built
        // The story is for children, as the hero's intro is.
        chapter = if (profile.isChild && ticket.custom == null) ticket.sound?.let { app.content.sound(it).chapter(ticket.focus) } else null
        field = ticket.sound?.family?.field ?: (if (profile.isChild) Field.cold else Field.paper)
        record = SessionRecord(
            profileId = profile.id,
            track = track(ticket.focus),
            sound = ticket.sound?.rawValue,
            focus = when (val kind = ticket.custom?.kind) {
                is tech.fonia.features.app.CustomRun.Kind.Drill -> "drill:${kind.sound.rawValue}:${kind.index}"
                else -> ticket.focus.key
            },
            // `Int64(bitPattern: seed)`.
            seed = seed.toLong(),
        )
        var initial = ticket.sound?.let { profile.progressionState(it, progress) } ?: ProgressionState()
        // The rung being practised is the one that counts, whatever was last saved.
        val focus = ticket.focus
        if (focus is SessionFocus.Sound) {
            isReview = focus.rung.index < (progress?.rung ?: 0)
            initial = initial.copy(rung = focus.rung)
        } else {
            isReview = false
        }
        state = initial
    }

    val current: Trial? get() = session.trials.getOrNull(index)

    /** The speed the model of the trial on screen is played at (a twister's tempo ladder); null: normal. */
    fun modelSpeed(): Float? = ticket.custom?.modelSpeeds?.getOrNull(index)

    /** The quick check: its takes stay in memory for the result screen and are never stored as practice. */
    val isCheck: Boolean get() = ticket.custom?.kind == tech.fonia.features.app.CustomRun.Kind.Check

    /** How many times a trial is said again by itself before it moves on: once in the quick check, twice elsewhere. */
    val maxAutoRetakes: Int get() = if (isCheck) 1 else 2

    /**
     * The last take of each trial of a custom run (the check, a drill): its sound, what was decided and how sure the
     * judge was. A trial said again keeps only its last take.
     */
    val takes = androidx.compose.runtime.mutableStateMapOf<Int, tech.fonia.features.check.CheckTake>()

    /** How sure the new judge was of the take being finished (null: his judge decided). */
    private var pendingCertainty: Float? = null

    /** Called by the take cycle right before it finishes an attempt the new judge decided. */
    fun noteJudged(certainty: Float?) {
        pendingCertainty = certainty
    }

    /** The longest held sound of this session, in seconds (the recap shows it). */
    var longestHold: Double by androidx.compose.runtime.mutableDoubleStateOf(0.0)
        private set

    fun noteHeld(seconds: Double) {
        if (seconds > longestHold) longestHold = seconds
    }

    /** The id of this session's row: the pilot log and shadow mode tie their rows to it. */
    val sessionID: UUID get() = record.id

    /**
     * How far through the session, by attempts: that is what the dose counts. Retries and the closing item can go past
     * the dose, and the bar stays full then.
     */
    val progressValue: Double
        get() {
            val byTrial = index.toDouble() / max(session.trials.size, 1).toDouble()
            if (session.targetAttempts <= 0) return byTrial
            // Listening and teaching screens move the bar too, so he always sees it advance (v2-flow).
            return minOf(1.0, max(byTrial, attempts.toDouble() / session.targetAttempts.toDouble()))
        }

    /** True when the session opens on the hero's intro for this sound, which then tells the chapter. */
    val opensOnIntro: Boolean
        get() = (session.trials.firstOrNull()?.exercise as? Exercise.Intro)?.sound?.let { it == ticket.sound } ?: false

    /** The chapter is over: the first trial shows. */
    fun finishChapter() {
        isTellingChapter = false
    }

    private val elapsedSeconds: Double get() = (System.nanoTime() - startedNanos) / 1e9

    val minutes: Int get() = max(1, (elapsedSeconds / 60).toInt())

    suspend fun start() {
        // The phone model warms while the first trial shows (ml/README.md: warm while in a lesson).
        app.judge?.warmUp()
        app.audio.prepare()
        // Before the first trial shows: the adaptive pilot plans its blocks, or leaves iOS's session as it is.
        if (ticket.custom == null) AdaptiveRun.start(app, profile, ticket, record.id, progress, history, session, ::later)?.let { (run, planned) ->
            adaptive = run
            session = planned
        }
        isTellingChapter = chapter != null && !opensOnIntro
        if (isCheck) return
        ignoringFailure { app.repository.addSession(record) }
        app.logSessionStart(this, record)
    }

    /** Moves on without counting anything: teaching screens and helpers. */
    fun advance() {
        if (isClosed || trialSettled) return
        trialSettled = true
        moveToNext()
    }

    /**
     * Records one finished attempt and moves on. A take nobody could judge (no voice, too loud, the phone abstaining
     * with no adult answer) says nothing about the sound: it stays out of the block and the dose, so it can't push
     * anyone down a rung.
     */
    fun finishAttempt(
        exercise: Exercise,
        machine: Attempt.Outcome,
        adult: Attempt.Outcome?,
        selfRating: Rating? = null,
        take: CapturedClip?,
        retry: Boolean = false,
    ) {
        if (isClosed || trialSettled) return
        trialSettled = true
        val takeKey = PilotHooks.takeKey(index, retryToken)
        val machineJudged = machine.isVerdict
        val decided = adult ?: (if (machineJudged) machine else (selfRating?.attemptOutcome ?: machine))
        val counts = current?.countsAsAttempt ?: false
        if (counts && decided.isVerdict) {
            attempts += 1
            if (decided == Attempt.Outcome.Good) goodAttempts += 1
            if (decided == Attempt.Outcome.Good && current?.block?.kind != SessionBlock.Kind.Closing && isShortItem(exercise)) {
                lastSaidWell = exercise
            }
            // A retry is the same item again: it adds to the dose but must not change a block's size.
            if (!retry) {
                blockTotal += 1
                if (decided == Attempt.Outcome.Good) blockGood += 1
            }
            if (exercise.isIdentification) {
                identified += 1
                if (decided == Attempt.Outcome.Good) identifiedRight += 1
            }
        }
        if (ticket.custom != null && counts) {
            soundOf(exercise)?.let { sound -> takes[index] = tech.fonia.features.check.CheckTake(sound, decided, pendingCertainty) }
        }
        pendingCertainty = null
        if (counts && !isCheck) store(exercise, machine, adult, selfRating, take)
        if (!isCheck) app.logAttempt(this, takeKey, exercise, machine, adult, selfRating, decided, counts, retry)
        if (counts) adaptive?.take(index, exercise, machine, adult)
        if (retry) {
            retryToken += 1
            trialSettled = false
            return
        }
        closeBlockIfNeeded()
        moveToNext()
    }

    /** How many times the trial on screen was said again because the phone could not judge it ("Mai spune o dată"). */
    val autoRetakes: Int get() = retakes[index] ?: 0
    private val retakes = HashMap<Int, Int>()

    /**
     * The same trial again at once, counting nothing (a retake is not an attempt, speech/JUDGE.md): the take was too
     * quiet, too short, or the phone was unsure. No one is asked anything.
     */
    fun retakeSameTrial() {
        if (isClosed || trialSettled) return
        retakes[index] = autoRetakes + 1
        retryToken += 1
    }

    fun clearBlockNote() {
        blockNote = null
    }

    /** Runs [block] after every save asked for before it. */
    private fun later(block: suspend () -> Unit) {
        writes.launch { saving.withLock { ignoringFailure(block) } }
    }

    /** Waits until every save asked for so far has run. For the tests, which read the store back. */
    internal suspend fun awaitSaves() {
        saving.withLock { }
    }

    private fun store(exercise: Exercise, machine: Attempt.Outcome, adult: Attempt.Outcome?, selfRating: Rating?, take: CapturedClip?) {
        val repository = app.repository
        val profileID = profile.id
        val sessionID = record.id
        val sound = ticket.sound?.rawValue
        val target = exercise.itemText ?: label(exercise)
        val kind = exercise.kind.rawValue
        val keepsAudio = !profile.isChild || (app.preferences?.bool(Settings.keepRecordings) ?: false)
        val detail = selfRating?.let { mapOf<String, JSONValue>("selfRating" to JSONValue.String(it.rawValue)) }
        // `Task.detached(priority: .utility)`: the take is written off the main thread.
        later {
            withContext(Dispatchers.Default) {
                var recordingID: UUID? = null
                if (keepsAudio && take != null && machine != Attempt.Outcome.Unclear) {
                    val recording = Recording(profileId = profileID, label = target ?: "", sound = sound, duration = take.duration)
                    val written = runCatching {
                        val file = repository.recordingFileURL(recording)
                        RecordingWriter.writeM4A(take, file)
                    }.isSuccess
                    if (written) {
                        ignoringFailure { repository.addRecording(recording) }
                        recordingID = recording.id
                    }
                }
                repository.addAttempt(
                    Attempt(
                        sessionId = sessionID,
                        profileId = profileID,
                        exerciseKind = kind,
                        sound = sound,
                        target = target,
                        machineOutcome = machine,
                        adultOutcome = adult,
                        detail = detail,
                        recordingId = recordingID,
                    ),
                )
            }
        }
    }

    private fun closeBlockIfNeeded() {
        val trial = current ?: return
        val next = session.trials.getOrNull(index + 1)
        if (next?.block == trial.block) return
        try {
            noteSilentBlock(trial.block)
            // In the adaptive pilot the engine decides after its blocks, in place of `Progression.afterBlock`.
            adaptive?.let { engine ->
                closeAdaptiveBlock(engine, trial)
                return
            }
            val sound = ticket.sound
            if (trial.block.kind != SessionBlock.Kind.TargetA || isReview || sound == null || blockTotal <= 0) return
            var updated = progress ?: SoundProgress(profileId = profile.id, sound = sound.rawValue)
            updated = updated.copy(
                blockHistory = updated.blockHistory + SoundProgress.Block(rung = rungOfSession.index, good = blockGood, total = blockTotal, endedAt = Instant.now()),
            )
            // Later target blocks still practise this session's rung, so once one block has moved the learner, the
            // rest of the session can't move them again.
            if (state.rung == rungOfSession) {
                val outcome = OwnSuccess.afterBlock(correct = blockGood, attempted = blockTotal, state = state)
                state = outcome.state
                when (val step = outcome.step) {
                    is ProgressionStep.Advance -> {
                        updated = updated.copy(rung = step.to.index)
                        movedUpTo = step.to
                    }
                    is ProgressionStep.Drop -> updated = updated.copy(rung = step.to.index)
                    is ProgressionStep.NeedsAdult -> needsAdultNext = step.next
                    ProgressionStep.Stay -> Unit
                }
            }
            progress = updated
            val repository = app.repository
            val saved = updated
            later { repository.saveSoundProgress(saved) }
        } finally {
            // Swift's `defer`.
            blockGood = 0
            blockTotal = 0
        }
    }

    /**
     * An adaptive block ended: the rest of the session is the engine's new plan (T2, T3), and the block joins the
     * sound's history under the rung it practised, as iOS's target blocks do. The rung itself moves only at the end.
     */
    private fun closeAdaptiveBlock(engine: AdaptiveRun, trial: Trial) {
        val ended = engine.blockEnded(index, session, good = blockGood, total = blockTotal) ?: return
        session = session.copy(trials = session.trials.take(index + 1) + ended.remaining)
        val sound = ticket.sound
        if (isReview || sound == null || blockTotal <= 0) return
        val current = progress ?: SoundProgress(profileId = profile.id, sound = sound.rawValue)
        val updated = current.copy(
            blockHistory = current.blockHistory + SoundProgress.Block(rung = ended.rung.index, good = blockGood, total = blockTotal, endedAt = Instant.now()),
        )
        progress = updated
        val repository = app.repository
        later { repository.saveSoundProgress(updated) }
    }

    /** The adaptive session ended: the rung moves on, or waits for an adult, by T1 and the iOS locks. */
    private fun closeAdaptiveSession(engine: AdaptiveRun, finished: Boolean) {
        val sound = ticket.sound
        when (val step = OwnSuccess.withoutAdult(engine.end(finished, state))) {
            is ProgressionStep.Advance -> if (sound != null) {
                movedUpTo = step.to
                val updated = (progress ?: SoundProgress(profileId = profile.id, sound = sound.rawValue)).copy(rung = step.to.index)
                progress = updated
                val repository = app.repository
                later { repository.saveSoundProgress(updated) }
            }
            is ProgressionStep.NeedsAdult -> needsAdultNext = step.next
            else -> Unit
        }
    }

    /** Trials with no response in the moment end with a plain count, so the learner isn't left guessing. */
    private fun noteSilentBlock(block: SessionBlock) {
        val silent = session.trials.filter { it.block == block && it.feedback == FeedbackLevel.Silent && it.countsAsAttempt }
        if (silent.size < 3 || blockTotal < 3 || block.kind == SessionBlock.Kind.Retest) return
        blockNote = blockNote(blockGood, blockTotal, profile.isChild)
    }

    private val rungOfSession: Rung
        get() = (ticket.focus as? SessionFocus.Sound)?.rung ?: Rung.Isolated

    private fun moveToNext() {
        val next = session.trials.getOrNull(index + 1)
        val overTime = elapsedSeconds >= session.timeLimitSeconds.toDouble()
        val doseDone = session.targetAttempts > 0 && attempts >= session.targetAttempts
        // Stop at a block's edge, so a time limit doesn't cut a block's verdict short.
        // A mission isn't practice on the phone, so it always gets its turn after the dose.
        val atBlockEdge = next?.block != current?.block
        val stops = next == null || ((overTime || doseDone) && atBlockEdge && next.block.kind != SessionBlock.Kind.Mission)
        // A session that stops early still ends on its easy item: one short take, then the mission or the end.
        val closing = closingIndex
        if (stops && closing != null && closing > index) {
            tech.fonia.design.FoniaSfx.play(tech.fonia.design.SfxCue.BlockDone)
            enter(closing)
        } else if (stops) {
            finish()
        } else {
            tech.fonia.design.FoniaSfx.play(if (atBlockEdge) tech.fonia.design.SfxCue.BlockDone else tech.fonia.design.SfxCue.Card)
            enter(index + 1)
        }
    }

    private val closingIndex: Int?
        get() = session.trials.indexOfFirst { it.block.kind == SessionBlock.Kind.Closing }.takeIf { it >= 0 }

    private fun enter(next: Int) {
        val easy = lastSaidWell
        if (session.trials[next].block.kind == SessionBlock.Kind.Closing && easy != null) useForClosing(easy, next)
        index = next
        trialSettled = false
    }

    /** The closing item becomes the latest one the learner said well in this session: the surest last win. */
    private fun useForClosing(exercise: Exercise, position: Int) {
        val closing = session.trials[position]
        if (closing.exercise == exercise) return
        val trials = session.trials.toMutableList()
        trials[position] = Trial(exercise = exercise, feedback = if (isSetUp(exercise)) FeedbackLevel.Quick else FeedbackLevel.Teach, block = closing.block)
        session = Session(trials = trials, targetAttempts = session.targetAttempts, timeLimitSeconds = session.timeLimitSeconds)
    }

    fun finish() {
        if (phase == Phase.Finished) return
        adaptive?.let { closeAdaptiveSession(it, finished = true) }
        phase = Phase.Finished
        app.judge?.trim()
        app.audio.effect(SoundEffect.Star)
        tech.fonia.design.FoniaSfx.play(tech.fonia.design.SfxCue.SessionEnd)
        if (didStoreSession || isCheck) return
        didStoreSession = true
        val repository = app.repository
        val id = record.id
        later { repository.finishSession(id) }
        app.logSessionEnd(this, finished = true)
    }

    suspend fun leaveEarly() {
        isClosed = true
        app.judge?.trim()
        if (phase != Phase.Finished) adaptive?.let { closeAdaptiveSession(it, finished = false) }
        if (phase != Phase.Finished && !isCheck) app.logSessionEnd(this, finished = false)
        app.audio.shutDown()
    }

    companion object {
        /** A child is told a part of the lesson is done, never how many came out right. An adult gets the count. */
        fun blockNote(good: Int, total: Int, isChild: Boolean): String = when {
            isChild -> "Încă o parte gata. Bravo!"
            good == 0 -> "Mai exersăm puțin."
            else -> "$good din $total au sunat bine."
        }

        /** The target sound of a spoken exercise. */
        internal fun soundOf(exercise: Exercise): SoundID? = when (exercise) {
            is Exercise.Repeat -> exercise.prompt.sound
            is Exercise.Name -> exercise.sound
            is Exercise.Twister -> exercise.sound
            is Exercise.Sustain -> (exercise.target as? tech.fonia.domain.SustainTarget.Sound)?.id
            else -> null
        }

        /** A syllable, a word, a held sound: short enough to make a sure last item. Sentences and stories are not. */
        internal fun isShortItem(exercise: Exercise): Boolean = when (exercise) {
            is Exercise.Repeat -> exercise.prompt.level != RepeatPrompt.Level.Sentence
            is Exercise.Name -> true
            is Exercise.Sustain -> exercise.role == SustainRole.Practice
            else -> false
        }

        /** Syllables and held sounds still get the live visual; words get a short response. */
        private fun isSetUp(exercise: Exercise): Boolean = when (exercise) {
            is Exercise.Sustain -> false
            is Exercise.Repeat -> exercise.prompt.level != RepeatPrompt.Level.Syllable
            else -> true
        }

        private fun track(focus: SessionFocus): Profile.Track = when (focus.track) {
            Track.Sounds -> Profile.Track.Ro
            Track.English -> Profile.Track.En
            Track.Diction -> Profile.Track.Dictie
        }

        private fun label(exercise: Exercise): String? = when (exercise) {
            Exercise.Volume -> "Volum"
            Exercise.Glide -> "Urcă și coboară"
            is Exercise.Sustain -> "Sunet ținut"
            else -> null
        }
    }
}
