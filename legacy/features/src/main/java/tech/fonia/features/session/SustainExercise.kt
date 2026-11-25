package tech.fonia.features.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tech.fonia.audio.LiveLevels
import tech.fonia.design.BlowFlame
import tech.fonia.design.CTALabel
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.Foni
import tech.fonia.design.FoniMood
import tech.fonia.design.FoniTone
import tech.fonia.design.FoniaText
import tech.fonia.design.HoldMeter
import tech.fonia.design.QuietButtonStyle
import tech.fonia.design.Rating
import tech.fonia.design.StyledButton
import tech.fonia.design.VoiceRings
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fDisplay
import tech.fonia.domain.FeedbackLevel
import tech.fonia.domain.SoundDef
import tech.fonia.domain.SoundDetector
import tech.fonia.domain.SoundFamily
import tech.fonia.domain.SustainRole
import tech.fonia.domain.SustainTarget
import tech.fonia.domain.Trial
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.LocalStillMeters
import tech.fonia.features.app.calibration
import tech.fonia.features.app.isChild
import tech.fonia.features.app.shortLetter
import tech.fonia.store.Attempt
import tech.fonia.design.FoniaSfx
import tech.fonia.design.SfxCue

/** Seconds held so far, read per frame by the bar without touching observed state. */
class HeldTime {
    // `Atomic<UInt64>` with relaxed ordering on iOS: one writer, readers on the drawing pass.
    @Volatile var value: Double = 0.0
}

/**
 * Holds a sound or a breath. The bar fills while the sound lasts; for a breath, a flame leans and goes out. The rings
 * around Foni take the colour the microphone hears.
 */
@Composable
fun SustainExercise(trial: Trial, target: SustainTarget, seconds: Double, role: SustainRole, run: SessionRun, app: AppModel) {
    var isListening by remember { mutableStateOf(false) }
    var isDone by remember { mutableStateOf(false) }
    var flameOut by remember { mutableStateOf(false) }
    val held = remember { HeldTime() }
    var micOff by remember { mutableStateOf(false) }
    /** The take ended with a dead microphone: "Nu te aud" and what to do, instead of an empty bar. */
    var micSilent by remember { mutableStateOf(false) }
    /** The line the turn line ends on once the hold is over. */
    var ended by remember { mutableStateOf<String?>(null) }
    val haptics = LocalHapticFeedback.current
    val still = LocalStillMeters.current
    val letter = rememberLetterLink()
    val heard = remember { HeardLevel() }

    val sound: SoundDef? = (target as? SustainTarget.Sound)?.let { app.content.sound(it.id) }
    val title = if (sound != null) "Ține ${sound.shortLetter} cât poți" else "Suflă în lumânare"
    val field = run.field
    val levels = app.audio.levels
    val bias = placeBias(app.talkers.holdGroup(run.profile, sound), app.voice.calibrated(run.profile.id))

    /**
     * How fast the bar fills now ([HearRules.holdCredit]): the sound itself at full speed, a close one slowly, a hiss of
     * the other place barely ("aaa", or Ș on an S screen, can never fill it), so something moves for every try.
     */
    fun holdCredit(levels: LiveLevels): Float {
        val def = sound ?: return 0f
        return HearRules.holdCredit(
            detector = def.detector,
            warmTarget = def.id.family == SoundFamily.WarmAir,
            isFricative = levels.isFricative,
            // The place as the letter and the meter show it (recentred for a man's voice, or his own calibration).
            sNess = recentred(levels.sNess, bias),
            trill = levels.trillStrength,
            level = levels.level,
        )
    }

    fun skip() {
        app.scope.launch {
            app.audio.cancelListening()
            run.advance()
        }
    }

    // `.sensoryFeedback(.success, trigger: isDone)`.
    LaunchedEffect(isDone) { if (isDone) haptics.performHapticFeedback(HapticFeedbackType.Confirm) }

    // The held letter breathes with the voice: it grows while the sound lasts and sharpens for S, rounds for Ș.
    FeedLetter(
        letter, levels, listening = isListening, modelVoice = false, holdSeconds = { held.value.toFloat() },
        baseInflation = { (0.4 + 0.6 * (held.value / seconds)).toFloat().coerceIn(0.4f, 1f) },
        heard = { heard.value = it },
        placeBias = bias,
        aim = LiveAim.of(sound?.id),
    )

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        WaveTitle(title, fDisplay, field.foreground, Modifier.padding(top = 8.dp), letter = sound?.shortLetter, field = field)
        Spacer(Modifier.weight(1f))
        if (sound != null) HeldLetter(sound.shortLetter, letter, field)
        if (sound == null) {
            BlowFlame(isLive = isListening && !still, isOut = flameOut, strength = { levels.blowStrength }, modifier = Modifier.size(width = 130.dp, height = 210.dp))
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            VoiceRings(
                color = FoniTone(field).glow,
                isLive = isListening && !still,
                level = { levels.level },
                // A little smaller than before, so the live meters fit under the hold bar (v2-flow).
                modifier = Modifier.widthIn(max = 210.dp),
                heard = if (sound != null && trial.feedback == FeedbackLevel.Teach) ({ levels.heardColour }) else null,
            )
            Foni(
                Modifier.widthIn(max = 126.dp).foniReaction(if (isDone) Rating.yes else null),
                mood = if (isDone) FoniMood.happy else FoniMood.listening,
                level = { levels.level },
            )
            // Over Foni rather than under the line, so the sheet below keeps its size when it shows.
            NoMicCue(app, field, listening = isListening, modifier = Modifier.align(Alignment.BottomCenter))
        }
        Spacer(Modifier.weight(1f))
        HoldMeter(seconds = seconds, isLive = isListening && !still, color = field.deep, track = field.track, progress = { held.value / seconds })
        if (micSilent) {
            NoMicBox(field)
            StyledButton(onClick = { run.advance() }, style = FoniButtonStyle(field = field)) { CTALabel("Mai departe") }
        } else if (micOff) {
            FoniaText("Microfonul e oprit. Pornește-l din Setări, la Fonia.", style = fBodyStrong, color = field.foreground, textAlign = TextAlign.Center)
            StyledButton(onClick = { run.advance() }, style = FoniButtonStyle(field = field)) { CTALabel("Mai departe") }
        } else {
            // The same sheet as in the spoken tasks: the meters right above whose turn it is.
            DockSheet {
                if (sound != null && MeterPlan.shows(trial.feedback)) LiveMeters(sound.id, letter.drive, listening = isListening)
                val endLine = ended
                TurnLine(
                    mode = when {
                        isListening -> TurnMode.Listening
                        endLine != null -> TurnMode.Finished(endLine, praised = isDone)
                        else -> TurnMode.Model
                    },
                    field = field,
                    height = DockMetrics.rowHeight(run.profile.isChild),
                    heard = heard,
                    level = { levels.level },
                )
            }
        }
        if (role == SustainRole.Facilitation) {
            StyledButton(onClick = { skip() }, style = QuietButtonStyle(field.foreground)) { FoniaText("Sar peste") }
        }
    }

    ExerciseTask {
        app.audio.prepare()
        if (run.profile.isChild) app.audio.sayIfRecorded(title)
        if (!app.audio.requestMicrophone()) {
            micOff = true
            return@ExerciseTask
        }
        FoniaSfx.play(SfxCue.TakeStart) // before the microphone: startListening waits for it to end + 150 ms
        try {
            // The group heard in his voice so far; before any voiced take, the one most favourable to the target (a hiss
            // alone cannot tell a man's S from a child's Ș).
            val personal = app.voice.personal(run.profile.id)
            val (own, room) = synchronized(personal) { personal.calibration() to personal.usableRoom }
            app.audio.startListening(
                group = app.talkers.holdGroup(run.profile, sound), calibration = own ?: run.profile.calibration,
                fixtureText = sound?.hero?.says, room = room,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            micOff = true
            return@ExerciseTask
        }
        isListening = true
        val goal = seconds
        val isBreath = sound == null
        val started = System.nanoTime()
        var machine: Rating? = null
        var tick = System.nanoTime()
        while (isActive) {
            delay(40)
            // Real elapsed time: a busy phone (or emulator) wakes later than 40 ms, and the bar must not run slow.
            val now = System.nanoTime()
            val dt = ((now - tick) / 1e9).coerceIn(0.0, 0.2)
            tick = now
            // A sound held alone is judged by the acoustic rule (the phoneme model needs a syllable around the sound:
            // on a bare "ssss" it answers nothing, tools/eval/out/live_probe.txt). A flat hiss is a noise (a vacuum
            // cleaner, paper, running water), not S or Ș: it fills nothing.
            val credit = when {
                isBreath -> if (levels.blowStrength > 0.25f) 1f else 0f
                levels.isFricative && levels.hissFlatness > HISS_FLAT_MAX -> 0f
                else -> holdCredit(levels)
            }
            held.value = (held.value + dt * credit).coerceAtMost(goal)
            if (held.value >= goal) {
                machine = Rating.yes
                break
            }
            // Generous time to try, then it ends kindly with whatever came out.
            if ((System.nanoTime() - started) / 1e9 > goal + 7) break
        }
        if (!isActive) return@ExerciseTask
        app.audio.cancelListening()
        isListening = false
        app.talkers.observe(run.profile, app.audio.lastTakePitch())
        run.noteHeld(held.value)
        runCatching {
            android.util.Log.i("FoniaHold", "held ${"%.2f".format(held.value)} of ${"%.1f".format(goal)} s, ${if (machine == Rating.yes) "full" else "partial"}, role=$role")
        }
        if (machine == null && held.value / goal <= 0.1 && app.audio.lastTakeWasSilent) {
            // A dead microphone is not a failed try: say so, and let them go on.
            letter.rest()
            micSilent = true
            return@ExerciseTask
        }
        flameOut = machine == Rating.yes && isBreath
        isDone = machine == Rating.yes
        ended = when {
            !isDone -> "Gata"
            isBreath -> "Ai stins lumânarea. Bravo!"
            else -> "Ai ținut până la capăt. Bravo!"
        }
        if (machine == Rating.yes) {
            letter.pop()
            letter.correct()
            FoniaSfx.play(SfxCue.Pop) // the balloon POP is the reward; one sound, not a pile
        } else if (held.value / goal > 0.1) {
            letter.almost()
            FoniaSfx.play(SfxCue.TakeAlmost)
        } else {
            letter.rest()
        }
        delay(if (machine == Rating.yes) Pacing.current.sustainDoneMs else Pacing.current.sustainPartialMs)
        if (role == SustainRole.Facilitation) {
            run.advance()
        } else {
            // Nothing held at all is silence, not a wrong sound.
            val share = held.value / goal
            val outcome = if (machine == Rating.yes) {
                Attempt.Outcome.Good
            } else if (share > 0.5) {
                Attempt.Outcome.Almost
            } else if (share > 0.1) {
                Attempt.Outcome.NotYet
            } else {
                Attempt.Outcome.Unclear
            }
            run.finishAttempt(trial.exercise, machine = outcome, adult = null, take = null)
        }
    }
}
