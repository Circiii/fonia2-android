package tech.fonia.features.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import tech.fonia.features.app.dspGroup
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
    val haptics = LocalHapticFeedback.current
    val still = LocalStillMeters.current
    val letter = rememberLetterLink()

    val sound: SoundDef? = (target as? SustainTarget.Sound)?.let { app.content.sound(it.id) }
    val title = if (sound != null) "Ține ${sound.shortLetter} cât poți" else "Suflă în lumânare"
    val field = run.field
    val levels = app.audio.levels

    /** A sibilant counts only as itself: "aaa", or Ș on an S screen, doesn't fill the bar. */
    fun isHolding(levels: LiveLevels): Boolean {
        val def = sound ?: return false
        return when (def.detector) {
            SoundDetector.Sibilant -> {
                if (!levels.isFricative) return false
                if (def.id.family == SoundFamily.WarmAir) levels.sNess < 0.5f else levels.sNess >= 0.5f
            }
            SoundDetector.Rhotic -> levels.trillStrength > 0.3f
            SoundDetector.None -> levels.level > 0.3f
        }
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
    FeedLetter(letter, levels, listening = isListening, modelVoice = false, holdSeconds = { held.value.toFloat() })

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        WaveTitle(title, fDisplay, field.foreground, Modifier.padding(top = 8.dp), letter = sound?.shortLetter, field = field)
        Spacer(Modifier.weight(1f))
        if (sound != null) HeldLetter(sound.shortLetter, letter, field)
        if (sound == null) {
            BlowFlame(isLive = isListening && !still, isOut = flameOut, strength = { levels.blowStrength }, modifier = Modifier.size(width = 130.dp, height = 210.dp))
        }
        Box(contentAlignment = Alignment.Center) {
            VoiceRings(
                color = FoniTone(field).glow,
                isLive = isListening && !still,
                level = { levels.level },
                modifier = Modifier.widthIn(max = 250.dp),
                heard = if (sound != null && trial.feedback == FeedbackLevel.Teach) ({ levels.heardColour }) else null,
            )
            Foni(
                Modifier.widthIn(max = 150.dp).foniReaction(if (isDone) Rating.yes else null),
                mood = if (isDone) FoniMood.happy else FoniMood.listening,
                level = { levels.level },
            )
        }
        Spacer(Modifier.weight(1f))
        HoldMeter(seconds = seconds, isLive = isListening && !still, color = field.deep, track = field.track, progress = { held.value / seconds })
        if (micOff) {
            FoniaText("Microfonul e oprit. Pornește-l din Setări, la Fonia.", style = fBodyStrong, color = field.foreground, textAlign = TextAlign.Center)
            StyledButton(onClick = { run.advance() }, style = FoniButtonStyle(field = field)) { CTALabel("Mai departe") }
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
            app.audio.startListening(group = run.profile.dspGroup, calibration = run.profile.calibration, fixtureText = sound?.hero?.says)
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
        while (isActive) {
            delay(40)
            val active = if (isBreath) levels.blowStrength > 0.25f else isHolding(levels)
            if (active) held.value += 0.04
            letter.drive.inflation = (0.4 + 0.6 * (held.value / goal)).toFloat().coerceIn(0.4f, 1f)
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
        flameOut = machine == Rating.yes && isBreath
        isDone = machine == Rating.yes
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
        delay(if (machine == Rating.yes) 1300 else 900)
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
