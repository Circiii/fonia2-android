package tech.fonia.features.ml

import tech.fonia.audio.CapturedClip
import tech.fonia.domain.Exercise
import tech.fonia.domain.Language
import tech.fonia.domain.RepeatPrompt
import tech.fonia.domain.SoundID
import tech.fonia.domain.Trial
import tech.fonia.features.app.AppModel
import tech.fonia.features.pilot.PilotHooks
import tech.fonia.features.pilot.ShadowContext
import tech.fonia.features.session.SessionRun
import tech.fonia.features.session.TakeJudge
import tech.fonia.features.session.TakeVerdict
import tech.fonia.store.Profile
import java.util.UUID

// Where the screens hand a judged take to shadow mode. The verdict is TakeJudge's and is returned as it is: shadow mode
// only gets a reference to the same audio, queued for its own thread (03 §1.2 P13).

/**
 * `SpeakExercise`'s judging: TakeJudge's verdict, unchanged and returned as soon as TakeJudge has it, after the take
 * has been queued for the shadow scorer of [app] (which returns at once).
 */
suspend fun TakeJudge.judgeShadowed(app: AppModel, run: SessionRun, trial: Trial, clip: CapturedClip): TakeVerdict =
    judge(trial.exercise, clip).also { verdict -> app.shadowSessionTake(run, trial, clip, verdict) }

/** The trial on screen, scored in shadow mode: its key is the one the session logs the attempt under. */
fun AppModel.shadowSessionTake(run: SessionRun, trial: Trial, clip: CapturedClip, verdict: TakeVerdict) {
    ml?.afterJudge(
        context = ShadowContext.Session,
        profileId = run.profile.id,
        sessionId = run.sessionID,
        take = PilotHooks.takeKey(run.index, run.retryToken),
        exercise = trial.exercise,
        clip = clip,
        verdict = verdict,
        feedback = trial.feedback,
        content = content,
        thresholds = audio.analysisThresholds,
        fixtures = audio.usesFixtures,
    )
}

/**
 * The mirror's take (the mouth coach judges the plan's first syllable): scored in shadow mode, and counted in the log
 * as a mirror attempt, apart from the dose.
 */
fun AppModel.shadowMirrorTake(profile: Profile, sound: SoundID, spoken: String, clip: CapturedClip, verdict: TakeVerdict) {
    val take = "mirror." + UUID.randomUUID().toString().take(8)
    ml?.afterJudge(
        context = ShadowContext.Mirror,
        profileId = profile.id,
        sessionId = null,
        take = take,
        exercise = Exercise.Repeat(RepeatPrompt(text = spoken, language = Language.Ro, sound = sound, level = RepeatPrompt.Level.Syllable)),
        clip = clip,
        verdict = verdict,
        feedback = null,
        content = content,
        thresholds = audio.analysisThresholds,
        fixtures = audio.usesFixtures,
    )
    runCatching { pilot?.attempt(PilotHooks.mirrorAttempt(content, profile.id, take, sound, spoken, verdict.outcome)) }
}
