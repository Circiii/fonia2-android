package tech.fonia.features.session

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.fonia.audio.CapturedClip
import tech.fonia.audio.LiveLevels
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.Field
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.Foni
import tech.fonia.design.FoniMood
import tech.fonia.design.FoniTone
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaLabel
import tech.fonia.design.FoniaText
import tech.fonia.design.MarkedWord
import tech.fonia.design.Palette
import tech.fonia.design.PillButtonStyle
import tech.fonia.design.Rating
import tech.fonia.design.RatingChoice
import tech.fonia.design.RatingMark
import tech.fonia.design.RatingRow
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.VoiceBars
import tech.fonia.design.VoiceRings
import tech.fonia.design.fBody
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fButton
import tech.fonia.design.fPrompt
import tech.fonia.design.swiftSpring
import tech.fonia.design.ui
import tech.fonia.domain.Exercise
import tech.fonia.domain.FeedbackLevel
import tech.fonia.domain.IntonationAsk
import tech.fonia.domain.Language
import tech.fonia.domain.Prosody
import tech.fonia.domain.RepeatPrompt
import tech.fonia.domain.SoundDef
import tech.fonia.domain.SoundDetector
import tech.fonia.domain.Trial
import tech.fonia.features.app.ActivityIndicator
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.ClipDecoder
import tech.fonia.features.app.LocalStillMeters
import tech.fonia.features.app.SoundEffect
import tech.fonia.features.app.calibration
import tech.fonia.features.app.dspGroup
import tech.fonia.features.app.isChild
import tech.fonia.features.ml.judgeShadowed
import tech.fonia.features.ml.shadowSessionTake
import tech.fonia.design.FoniaSfx
import tech.fonia.design.SfxCue
import tech.fonia.designsystem.motion.BurstStyle
import tech.fonia.designsystem.motion.CelebrationBurst
import tech.fonia.designsystem.motion.rememberFoniaHaptics
import tech.fonia.designsystem.motion.staggeredEntrance
import tech.fonia.judge.RuntimeContext
import androidx.compose.ui.geometry.Offset
import tech.fonia.store.Attempt
import kotlin.math.max

/** `SpeakExercise.Phase`: the model voice, the take, the judging, the answer. */
enum class SpeakPhase { Model, Listening, Thinking, Result }

/**
 * Everything spoken goes through here: hear the model, the mic opens by itself, the take ends when the voice does, and
 * the answer comes back fast enough to keep the pace. Most attempts need no tap.
 */
@Composable
fun SpeakExercise(trial: Trial, run: SessionRun, app: AppModel) {
    var phase by remember { mutableStateOf(SpeakPhase.Model) }
    var take by remember { mutableStateOf<CapturedClip?>(null) }
    var verdict by remember { mutableStateOf<TakeVerdict?>(null) }
    var adult by remember { mutableStateOf<Rating?>(null) }
    var waitingForChoice by remember { mutableStateOf(false) }
    /** A line in the capsule instead of the panel: "Mai spune o dată" while the trial starts over by itself. */
    var capsuleLine by remember { mutableStateOf<String?>(null) }
    /** What the new judge said about the last take (null: his judge decided). */
    var judged by remember { mutableStateOf<SoundJudge.Judged?>(null) }
    /** 3, 2, 1 in the capsule before it opens again after "Mai spune o dată". */
    var countdown by remember { mutableStateOf<Int?>(null) }
    /** „Vezi cum”: the sound's „Cum spun?” steps over the session, at this step (null: closed). */
    var howStep by remember { mutableStateOf<Int?>(null) }
    val haptics = rememberFoniaHaptics()
    val letter = rememberLetterLink()
    val heard = remember { HeardLevel() }
    val profile = run.profile

    val exercise = trial.exercise
    val field = run.field
    val sound: SoundDef? = when (exercise) {
        is Exercise.Repeat -> exercise.prompt.sound?.let { app.content.sound(it) }
        is Exercise.Name -> app.content.sound(exercise.sound)
        is Exercise.Twister -> exercise.sound?.let { app.content.sound(it) }
        else -> run.ticket.sound?.let { app.content.sound(it) }
    }

    /** Text the model voice says first. A picture to name has no model: the child has to find the word. */
    val modelText: Pair<String, Language>? = when (exercise) {
        is Exercise.Repeat -> exercise.prompt.text to exercise.prompt.language
        is Exercise.Twister -> exercise.text to exercise.language
        is Exercise.Intonation -> (if (exercise.ask == IntonationAsk.Question) exercise.question else exercise.statement) to Language.Ro
        is Exercise.Expression -> exercise.text to Language.Ro
        is Exercise.Pace -> exercise.text to Language.Ro
        is Exercise.Pauses -> exercise.spoken to Language.Ro
        is Exercise.Shadow -> exercise.text to exercise.language
        else -> null
    }

    val showsLiveAir = trial.feedback == FeedbackLevel.Teach && sound != null && sound.detector == SoundDetector.Sibilant

    val maximumSeconds = when (exercise) {
        is Exercise.Name -> 5.0
        is Exercise.Repeat -> if (exercise.prompt.level == RepeatPrompt.Level.Sentence) 10.0 else 5.0
        is Exercise.Retell -> 45.0
        is Exercise.Describe -> 15.0
        Exercise.Volume, Exercise.Glide -> 8.0
        else -> 14.0
    }

    /** How long a silence means the take is over. A pause or a child thinking mid-story isn't the end. */
    val endSilence = when (exercise) {
        is Exercise.Pauses -> 1.8
        is Exercise.Retell, is Exercise.Describe -> 2.5
        is Exercise.Pace, is Exercise.Shadow, is Exercise.Expression, is Exercise.Twister -> 1.2
        else -> 0.7
    }

    val startTimeout = when (exercise) {
        is Exercise.Retell, is Exercise.Describe -> 12.0
        else -> 6.0
    }

    val fixtureText: String? = when (exercise) {
        is Exercise.Name -> exercise.item.text
        else -> modelText?.first
    }
    val fixtureLanguage = modelText?.second ?: Language.Ro

    /** Says the answer out loud for a child, when there's a recording of it. Quick trials stay silent: they exist to keep the pace. */
    suspend fun announce(result: TakeVerdict) {
        if (!run.profile.isChild) return
        app.audio.sayIfRecorded(result.title)
        app.audio.sayIfRecorded(result.tip)
    }

    suspend fun modelAsClip(): CapturedClip? {
        val (text, language) = modelText ?: return null
        val url = app.audio.clipURL(text, language) ?: return null
        return try {
            ClipDecoder.decode(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /** The letter answers first: Correct, Almost, or the instruction animation of a named error. */
    fun cueLetter(result: TakeVerdict) {
        val j = judged
        val kind = j?.kind
        when {
            j?.cue == SoundJudge.Cue.Instruct && kind != null -> letter.instruct(kind, j.heard)
            result.rating == Rating.yes -> letter.correct()
            result.rating != null -> letter.almost()
            else -> Unit
        }
        // The sound of the outcome is his `app.audio.effect(...)` in showResult (now the new set, through the gate).
    }

    /**
     * The phone could not judge the take. Never a silent reset (owner, 2026-11-23): a dead microphone says "Nu te aud"
     * and what to do; otherwise the letter shows its retake mark, the capsule says "Mai spune o dată" and counts 3-2-1
     * before it opens again; after [SessionRun.maxAutoRetakes] it is a kind "almost" and the session moves on by itself.
     */
    suspend fun retakeByItself(): Boolean {
        when (HearRules.afterUnjudged(run.autoRetakes, run.maxAutoRetakes, app.audio.lastTakeWasSilent)) {
            HearRules.Unjudged.NoMicrophone -> {
                letter.rest()
                verdict = TakeVerdict(rating = null, outcome = Attempt.Outcome.Unclear, title = "Nu te aud", tip = noMicHint(), keepsTake = false)
                phase = SpeakPhase.Result
                // The quick check is never blocked by a dead microphone: the cue shows, then the next word comes.
                if (run.isCheck) {
                    delay(2600)
                    if (adult != null || waitingForChoice) return true
                    run.finishAttempt(exercise, machine = Attempt.Outcome.Unclear, adult = null, take = take)
                } else {
                    waitingForChoice = true
                }
            }
            HearRules.Unjudged.Retake -> {
                letter.retake()
                FoniaSfx.play(SfxCue.Retake)
                capsuleLine = SoundJudge.retake.title
                app.audio.sayIfRecorded(SoundJudge.retake.title)
                for (n in HearRules.retakeCountdown) {
                    countdown = n
                    delay(450)
                }
                run.retakeSameTrial()
            }
            HearRules.Unjudged.AlmostAndMoveOn -> {
                val kind = TakeVerdict(rating = Rating.nearly, outcome = Attempt.Outcome.Unclear, title = "Aproape! Mergem mai departe", keepsTake = false)
                verdict = kind
                letter.almost()
                app.audio.effect(SoundEffect.Almost)
                phase = SpeakPhase.Result
                delay(1500)
                if (adult != null || waitingForChoice) return true
                run.finishAttempt(exercise, machine = kind.outcome, adult = null, take = take)
            }
        }
        return true
    }

    /** A take with a strip (and a hint) under the outcome word stays this much longer, so it can be looked at. */
    fun stripExtraMs(): Long {
        val j = judged ?: return 0
        return when {
            j.hint != null -> 3000
            j.visual?.placeAxis != null || j.visual?.voicing != null || j.visual?.trill != null || j.visual?.closure != null -> 900
            else -> 0
        }
    }

    /** The feedback schedule decides how much is said and whether the flow waits for anyone. */
    suspend fun showResult(result: TakeVerdict) {
        cueLetter(result)
        // No adult question any more (owner, 2026-11-21): what the phone can't judge moves on with "Mai departe".
        val asked = trial.feedback == FeedbackLevel.SelfJudge
        val feedback = trial.feedback
        when {
            feedback == FeedbackLevel.Silent && !asked -> {
                // Block summary only. A soft pop says "I heard you", nothing more.
                app.audio.effect(SoundEffect.Pop)
                if (result.outcome == Attempt.Outcome.Unclear && result.title == VerdictWriter.notHeard.title) {
                    phase = SpeakPhase.Result
                    waitingForChoice = true
                    return
                }
                run.finishAttempt(exercise, machine = result.outcome, adult = null, take = take)
            }
            feedback == FeedbackLevel.Quick && !asked -> {
                result.rating?.let { app.audio.effect(it.effect) }
                phase = SpeakPhase.Result
                // The letter and one word say how it went, then the next one comes by itself; a tap on "Încă o dată"
                // (or any other button) stops the flow there.
                delay(if (result.rating == Rating.yes) 850 + stripExtraMs() else AUTO_NEXT_MS + stripExtraMs())
                if (adult != null || waitingForChoice) return
                run.finishAttempt(exercise, machine = result.outcome, adult = null, take = take)
            }
            feedback == FeedbackLevel.Teach && !asked && result.rating == Rating.yes -> {
                // A good take doesn't wait for a tap: the pace is what builds the sound.
                app.audio.effect(SoundEffect.Correct)
                phase = SpeakPhase.Result
                // Long enough to read the result, however long saying it took.
                val shown = System.nanoTime()
                announce(result)
                val said = (System.nanoTime() - shown) / 1_000_000
                delay(max(1300 + stripExtraMs() - said, 350))
                if (adult != null || waitingForChoice) return
                run.finishAttempt(exercise, machine = result.outcome, adult = null, take = take)
            }
            feedback == FeedbackLevel.Teach && !asked -> {
                // Not yet right: the letter shows what to do, one short line, then the session moves on by itself.
                result.rating?.let { app.audio.effect(it.effect) }
                phase = SpeakPhase.Result
                val shown = System.nanoTime()
                announce(result)
                val said = (System.nanoTime() - shown) / 1_000_000
                delay(max(AUTO_NEXT_MS + 500 + stripExtraMs() - said, 600))
                if (adult != null || waitingForChoice) return
                run.finishAttempt(exercise, machine = result.outcome, adult = null, take = take)
            }
            else -> {
                if (feedback != FeedbackLevel.SelfJudge) result.rating?.let { app.audio.effect(it.effect) }
                phase = SpeakPhase.Result
                waitingForChoice = true
                if (feedback != FeedbackLevel.SelfJudge) announce(result)
            }
        }
    }

    suspend fun finishTake() {
        val clip = app.audio.stopListening() ?: return
        take = clip
        phase = SpeakPhase.Thinking
        // The talker heard so far (a man on a child profile is judged as a man), moved by this take's own voice.
        val group = app.talkers.group(profile)
        val judge = TakeJudge(
            content = app.content,
            group = group,
            calibration = profile.calibration,
            transcriber = app.speech.transcriber,
            thresholds = app.audio.analysisThresholds,
        )
        // The new judge (speech/JUDGE.md, ZIPA as second head) decides Romanian sound takes; his judge the rest.
        val request = SoundJudge.request(exercise, run.ticket.focus, group, profile.calibration)
        val fresh = if (request != null && sound != null) {
            app.judge?.judge(
                request, sound, clip, runtimeFor(run),
                talker = app.talkers.tracker(profile),
                personal = app.voice.personal(profile.id),
                profileIsAdult = !profile.isChild,
            )?.also { j ->
                app.talkers.remember(profile)
                // No audio is kept: his reference (when this take joined it) and the few numbers of what it looked like.
                withContext(Dispatchers.IO) {
                    if (j.joinedReference) app.voice.savePersonal(profile.id)
                    j.dot?.let { app.voice.addDot(profile.id, it) }
                }
            }
        } else {
            null
        }
        if (fresh == null) app.talkers.observe(profile, app.audio.lastTakePitch())
        // When the judge could not place the hiss (no frication found where the target was expected), the strip still
        // shows where the live meter heard it: what he did is always seen.
        judged = fresh?.let { j -> j.copy(visual = TakeFeedbackRules.withLivePlace(j.visual, sound?.id, app.audio.lastTakeHiss())) }
        run.noteJudged(fresh?.certainty)
        if (fresh?.cue == SoundJudge.Cue.Retake && retakeByItself()) return
        var result = when {
            fresh != null -> fresh.verdict.also { app.shadowSessionTake(run, trial, clip, it) }
            exercise is Exercise.Shadow -> {
                // No recording to follow means nothing to compare with: a take can't be judged against itself.
                val model = modelAsClip()
                if (model != null) judge.shadow(model, clip) else TakeVerdict(rating = null, outcome = Attempt.Outcome.NotJudged, title = "Te-am auzit")
            }
            // His verdict as it is; the same take also goes to the ZIPA scorer of shadow mode, which never waits.
            else -> judge.judgeShadowed(app, run, trial, clip)
        }
        // A take nobody heard is said again by itself, as the new judge's retakes are.
        if (!result.keepsTake && result.rating == null && retakeByItself()) return
        if (run.profile.isChild) result = result.softenedForChild
        if (trial.feedback == FeedbackLevel.Silent) result = result.copy(tip = null)
        // The learner judges first: what the phone thinks stays hidden until they've picked.
        if (trial.feedback == FeedbackLevel.SelfJudge) result = result.copy(tip = null)
        verdict = result
        showResult(result)
    }

    suspend fun listen() {
        if (!app.audio.requestMicrophone()) {
            verdict = TakeVerdict(rating = null, outcome = Attempt.Outcome.Unclear, title = "Microfonul e oprit", tip = "Pornește-l din Setări, la Fonia.", keepsTake = false)
            phase = SpeakPhase.Result
            return
        }
        // "Your turn" BEFORE the microphone: startListening waits until it has ended + 150 ms (the quiet rule).
        FoniaSfx.play(SfxCue.TakeStart)
        try {
            app.audio.startListening(app.talkers.group(profile), profile.calibration, fixtureText, fixtureLanguage)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            runCatching { android.util.Log.w("FoniaTake", "capture could not start: $failure") }
            verdict = TakeVerdict(
                rating = null,
                outcome = Attempt.Outcome.Unclear,
                title = "Nu pot porni microfonul",
                tip = "Închide alte aplicații care îl folosesc și mai încearcă.",
                keepsTake = false,
            )
            phase = SpeakPhase.Result
            // The quick check never stops on a microphone that will not open: the next word comes.
            if (run.isCheck) {
                delay(2600)
                if (adult == null && !waitingForChoice) run.finishAttempt(exercise, machine = Attempt.Outcome.Unclear, adult = null, take = null)
            }
            return
        }
        phase = SpeakPhase.Listening
        app.audio.waitForTakeEnd(silence = endSilence, startTimeout = startTimeout, maximum = maximumSeconds)
        if (!currentCoroutineContext().isActive) return
        finishTake()
    }

    // The taps. Each one means someone is choosing, so nothing moves on by itself after it.
    fun pickAdult(rating: Rating) {
        waitingForChoice = true
        adult = rating
        val shown = verdict ?: return
        if (trial.feedback == FeedbackLevel.SelfJudge) {
            run.finishAttempt(exercise, machine = shown.outcome, adult = null, selfRating = rating, take = take)
            return
        }
        val anchor = shown.anchor
        val place = shown.anchorPlace
        if (rating == Rating.yes && anchor != null && place != null) {
            val profile = run.profile
            app.scope.launch { app.addAnchor(anchor, place, profile) }
        }
        run.finishAttempt(exercise, machine = shown.outcome, adult = rating.attemptOutcome, take = take)
    }

    fun next() {
        waitingForChoice = true
        val shown = verdict ?: return
        run.finishAttempt(exercise, machine = shown.outcome, adult = adult?.attemptOutcome, take = take)
    }

    fun retry() {
        waitingForChoice = true
        val shown = verdict ?: return
        run.finishAttempt(exercise, machine = shown.outcome, adult = adult?.attemptOutcome, take = take, retry = true)
    }

    fun replay() {
        val clip = take ?: return
        waitingForChoice = true
        app.scope.launch { app.audio.play(clip) }
    }

    fun playModel() {
        val model = modelText
        if (model == null) {
            if (exercise is Exercise.Name) {
                waitingForChoice = true
                app.scope.launch { app.audio.say(exercise.item.text) }
            }
            return
        }
        waitingForChoice = true
        app.scope.launch { app.audio.say(model.first, model.second) }
    }

    fun playModelSlowly() {
        val model = modelText ?: return
        waitingForChoice = true
        app.scope.launch { app.audio.saySlowly(model.first, model.second) }
    }

    fun playWord(word: MarkedWord) {
        val clip = take ?: return
        val outcome = verdict?.words?.firstOrNull { it.index == word.id } ?: return
        if (outcome.end <= outcome.start) return
        waitingForChoice = true
        app.scope.launch { app.audio.play(clip, outcome.start, outcome.end) }
    }

    // `.sensoryFeedback(trigger: verdict?.title)`: a success for "bine", a light tap for anything else.
    val title = verdict?.title
    LaunchedEffect(title) {
        val shown = verdict ?: return@LaunchedEffect
        if (shown.rating == Rating.yes) haptics.success() else haptics.tap()
    }
    FeedLetter(
        letter, app.audio.levels, listening = phase == SpeakPhase.Listening, modelVoice = phase == SpeakPhase.Model, heard = { heard.value = it },
        placeBias = placeBias(app.talkers.group(profile)),
    )

    val showsPanel = phase == SpeakPhase.Result && verdict != null
    val spring = swiftSpring<Float>(0.34, 0.14)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            trial.prosody?.cue?.let { cue ->
                FoniaText(cue, Modifier.fillMaxWidth(), style = fPrompt, color = field.foreground, textAlign = TextAlign.Center)
            }
            PromptView(
                exercise = exercise,
                sound = sound,
                field = field,
                verdict = verdict,
                phase = phase,
                modifier = Modifier.weight(1f).zIndex(1f),
                letter = letter,
                onTapWord = { playWord(it) },
            )
            AnimatedContent(
                targetState = showsPanel,
                transitionSpec = {
                    // The rings reach over the sheet above the row, so nothing is clipped while the row changes size.
                    if (targetState) {
                        (slideInVertically(swiftSpring<IntOffset>(0.34, 0.14)) { it } + fadeIn(spring)) togetherWith fadeOut(spring) using SizeTransform(clip = false)
                    } else {
                        fadeIn(spring) togetherWith (slideOutVertically(swiftSpring<IntOffset>(0.34, 0.14)) { it } + fadeOut(spring)) using SizeTransform(clip = false)
                    }
                },
                label = "bottom",
            ) { panel ->
                val shown = verdict
                if (panel && shown != null) {
                    ResultPanel(
                        verdict = shown,
                        judged = judged,
                        soundId = sound?.id,
                        field = field,
                        onHow = { key ->
                            waitingForChoice = true
                            howStep = sound?.let { HintWords.step(key, it.how) } ?: 0
                        },
                        feedback = trial.feedback,
                        adult = adult,
                        canReplay = take != null,
                        onPick = { pickAdult(it) },
                        onReplay = { replay() },
                        onModel = { playModel() },
                        onSlow = if (modelText == null) null else ({ playModelSlowly() }),
                        onRetry = if (shown.rating == Rating.yes) null else ({ retry() }),
                        onNext = { next() },
                    )
                } else {
                    ListeningBar(
                        sound = sound?.id?.takeIf { exercise.isRomanianSoundTake },
                        drive = letter.drive,
                        phase = phase,
                        field = field,
                        showsHeardColour = showsLiveAir,
                        app = app,
                        line = capsuleLine,
                        countdown = countdown,
                        heard = heard,
                        onStop = { app.audio.gate.requestStop() },
                    )
                }
            }
        }
        // „Vezi cum”: the sound's steps, the one the hint is about lit.
        val how = howStep
        if (how != null && sound != null) HowSheet(sound, how, field, app, onClose = { howStep = null })
        // A good take: a ring of stars over the word (the motion kit's burst, drawn in the session's overlay).
        CelebrationBurst(
            trigger = if (phase == SpeakPhase.Result && verdict?.rating == Rating.yes) verdict else null,
            modifier = Modifier.fillMaxSize(),
            style = BurstStyle.Stars,
            origin = Offset(0.5f, 0.38f),
        )
    }

    ExerciseTask {
        phase = SpeakPhase.Model
        app.audio.prepare()
        val model = modelText
        when {
            model != null -> run.modelSpeed()?.let { app.audio.sayAt(model.first, it, model.second) } ?: app.audio.say(model.first, model.second)
            exercise is Exercise.Retell -> {
                for (line in exercise.prompt.lines) {
                    if (!isActive) break
                    app.audio.say(line)
                }
                app.audio.say(exercise.prompt.prompt)
            }
            exercise is Exercise.Describe -> app.audio.say(exercise.prompt.prompt)
            exercise is Exercise.Name && run.profile.isChild -> app.audio.sayIfRecorded(NAME_PROMPT)
        }
        if (!isActive) return@ExerciseTask
        listen()
    }
}

const val NAME_PROMPT = "Ce e în poză?"

/** A Romanian take with a target sound: the ones the live meters speak about. */
private val tech.fonia.domain.Exercise.isRomanianSoundTake: Boolean
    get() = when (this) {
        is tech.fonia.domain.Exercise.Repeat -> prompt.language == Language.Ro && prompt.sound != null
        is tech.fonia.domain.Exercise.Name -> true
        is tech.fonia.domain.Exercise.Twister -> language == Language.Ro && sound != null
        else -> false
    }

/** How long a result that is not yet right stays before the next trial comes by itself. */
private const val AUTO_NEXT_MS = 1700L

/** What the session knows at decision time: no adult in the loop; calibrated once there are ten anchors. */
internal fun runtimeFor(run: SessionRun): RuntimeContext =
    RuntimeContext(adultPresent = false, calibrationTier = if ((run.profile.calibration?.anchors?.size ?: 0) >= 10) 1 else 0)

/** The waiting state under the prompt: Foni in the rings of the voice, and one line that always says whose turn it is. */
@Composable
private fun ListeningBar(
    sound: tech.fonia.domain.SoundID?,
    drive: tech.fonia.designsystem.letter.LetterDrive,
    phase: SpeakPhase,
    field: Field,
    showsHeardColour: Boolean,
    app: AppModel,
    line: String?,
    countdown: Int?,
    heard: HeardLevel,
    onStop: () -> Unit,
) {
    val levels = app.audio.levels
    val still = LocalStillMeters.current
    val level: () -> Float = if (phase == SpeakPhase.Model) ({ levels.talk }) else ({ levels.level })
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Live meters under the living letter while the microphone is open (place, voice, trill; at most two).
        LiveMeters(sound, drive, listening = phase == SpeakPhase.Listening)
        NoMicCue(app, field, listening = phase == SpeakPhase.Listening)
        // The rings are wider than the row: they spread over the sheet above and the capsule below, under both.
        Box(Modifier.fillMaxWidth().height(124.dp), contentAlignment = Alignment.Center) {
            VoiceRings(
                color = FoniTone(field).glow,
                isLive = (phase == SpeakPhase.Listening || phase == SpeakPhase.Model) && !still,
                level = level,
                modifier = Modifier.requiredSize(216.dp),
                heard = if (showsHeardColour && phase == SpeakPhase.Listening) ({ levels.heardColour }) else null,
            )
            Foni(Modifier.size(124.dp), mood = if (phase == SpeakPhase.Listening) FoniMood.listening else FoniMood.idle, level = level)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .background(Palette.sheet, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            when (phase) {
                SpeakPhase.Listening -> Row(
                    Modifier.padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HearMic(heard, field.deep)
                    HearLabel(heard)
                    VoiceBars(isLive = !still, color = field.deep, level = { levels.level }, modifier = Modifier.weight(1f).height(28.dp))
                }
                SpeakPhase.Thinking -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (line != null) {
                        FoniaIconView(FoniaIcon.retry, size = 22f, tint = field.deep)
                        FoniaText(line, style = fButton, color = Palette.ink)
                        if (countdown != null) {
                            AnimatedContent(targetState = countdown, label = "countdown") { n ->
                                FoniaText("$n", style = fButton, color = field.deep)
                            }
                        }
                    } else {
                        ActivityIndicator()
                        FoniaText("Ascult", style = fButton, color = Palette.ink)
                    }
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    FoniaIconView(FoniaIcon.speaker, size = 22f, tint = field.deep)
                    FoniaText("Ascultă", style = fButton, color = Palette.ink)
                }
            }
            if (phase == SpeakPhase.Listening) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(50))
                        .clickable(role = Role.Button, onClick = onStop)
                        .semantics { contentDescription = "Am terminat" },
                )
            }
        }
    }
}

/** What the microphone hears, as the colour of the sound's family. Null while it isn't a hiss. */
val LiveLevels.heardColour: Color?
    get() {
        if (!isFricative) return null
        return if (sNess >= 0.5f) FoniTone.cold.glow else FoniTone.warm.glow
    }

/** The result, sized to the feedback level: a mark, a line, and only what's needed next. */
@Composable
private fun ResultPanel(
    verdict: TakeVerdict,
    judged: SoundJudge.Judged?,
    soundId: tech.fonia.domain.SoundID?,
    field: Field,
    onHow: (tech.fonia.judge.HintKey) -> Unit,
    feedback: FeedbackLevel,
    adult: Rating?,
    canReplay: Boolean,
    onPick: (Rating) -> Unit,
    onReplay: () -> Unit,
    onModel: () -> Unit,
    onSlow: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    onNext: () -> Unit,
) {
    // Only the learner's own rating is asked (self-judge trials); never an adult.
    val asks = feedback == FeedbackLevel.SelfJudge
    val question = "Cum ți s-a părut?"
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.sheet, ContinuousRoundedRectangle(28.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!asks || feedback != FeedbackLevel.SelfJudge) {
            Row(Modifier.fillMaxWidth().staggeredEntrance(0), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Foni(Modifier.size(62.dp).foniReaction(verdict.rating), mood = if (verdict.rating == Rating.yes) FoniMood.happy else FoniMood.listening)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        verdict.rating?.let { RatingMark(it, Modifier.size(24.dp)) }
                        // One word says how it went ("Aproape"); the letter shows the rest.
                        PraiseTitle(if (verdict.rating == Rating.nearly) verdict.title.substringBefore(". ") else verdict.title, popping = verdict.rating == Rating.yes)
                    }
                    verdict.detail?.let { FoniaText(it, style = fBody, color = Palette.inkSoft) }
                }
            }
        }
        verdict.tip?.let { FoniaText(it, Modifier.staggeredEntrance(1), style = ui(18, UIWeight.medium, TextRole.body), color = Palette.ink, textAlign = TextAlign.Center) }
        // What the take looked like, then the one hint (only when the policy allows it): seen, not said.
        val visual = judged?.visual
        if (visual != null && soundId != null && !asks) TakeStrip(visual, soundId, field, correct = verdict.rating == Rating.yes, modifier = Modifier.staggeredEntrance(1))
        val hint = judged?.hint
        if (hint != null && verdict.rating != Rating.yes && !asks) HintCard(hint, field, onHow = { onHow(hint) }, modifier = Modifier.staggeredEntrance(2))

        Box(Modifier.staggeredEntrance(2)) {
            FittingRow(spacing = 10.dp) {
                if (canReplay) Pill("Ascultă-te", FoniaIcon.listenBack, onReplay)
                Pill("Așa se spune", FoniaIcon.speaker, onModel)
                if (onSlow != null) Pill("Mai rar", FoniaIcon.glide, onSlow)
            }
        }

        if (asks) {
            FoniaText(question, style = fBodyStrong, color = Palette.ink, textAlign = TextAlign.Center)
            RatingRow(
                choices = listOf(RatingChoice(Rating.yes, "Bine"), RatingChoice(Rating.nearly, "Aproape"), RatingChoice(Rating.notYet, "Încă nu")),
                onPick = onPick,
                suggested = if (feedback == FeedbackLevel.SelfJudge) null else verdict.rating,
                selected = adult,
            )
        } else {
            Row(Modifier.fillMaxWidth().staggeredEntrance(3), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onRetry != null) {
                    StyledButton(
                        onClick = onRetry,
                        style = FoniButtonStyle(field = Field.paper, size = FoniButtonStyle.Size.regular, kind = FoniButtonStyle.Kind.secondary),
                        modifier = Modifier.weight(1f),
                    ) { FoniaText("Încă o dată") }
                }
                StyledButton(
                    onClick = onNext,
                    style = FoniButtonStyle(field = Field.paper, size = FoniButtonStyle.Size.regular),
                    modifier = Modifier.weight(1f),
                ) { FoniaText("Mai departe") }
            }
        }
    }
}

@Composable
private fun Pill(title: String, icon: FoniaIcon, onClick: () -> Unit) {
    StyledButton(onClick = onClick, style = PillButtonStyle()) { FoniaLabel(title, icon, iconSize = 20f) }
}

/**
 * `ViewThatFits(in: .horizontal) { HStack(spacing:) { items }; VStack(spacing:) { items } }` for items that each take
 * all the width offered: side by side, sharing the row, when their natural widths fit; else one under the other.
 */
@Composable
internal fun FittingRow(spacing: Dp, content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxWidth()) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val width = constraints.maxWidth
        val natural = measurables.sumOf { it.maxIntrinsicWidth(Constraints.Infinity) } + gap * (measurables.size - 1).coerceAtLeast(0)
        if (measurables.isEmpty()) return@Layout layout(width, 0) {}
        if (natural <= width) {
            val each = (width - gap * (measurables.size - 1)) / measurables.size
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(each.coerceAtLeast(0))) }
            val height = placeables.maxOf { it.height }
            layout(width, height) {
                var x = 0
                for (placeable in placeables) {
                    placeable.place(x, (height - placeable.height) / 2)
                    x += each + gap
                }
            }
        } else {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(width)) }
            val height = placeables.sumOf { it.height } + gap * (placeables.size - 1)
            layout(width, height) {
                var y = 0
                for (placeable in placeables) {
                    placeable.place(0, y)
                    y += placeable.height + gap
                }
            }
        }
    }
}

/** Mixed practice asks for the same word in different ways. Neutral says nothing. */
val Prosody.cue: String?
    get() = when (this) {
        Prosody.Neutral -> null
        Prosody.Loud -> "Tare"
        Prosody.Slow -> "Rar"
        Prosody.Fast -> "Repede"
        Prosody.Question -> "Ca o întrebare"
        Prosody.Exclamation -> "Cu bucurie"
    }
