package tech.fonia.features.pilot

import android.content.Context
import android.os.Build
import tech.fonia.domain.Exercise
import tech.fonia.domain.SessionFocus
import tech.fonia.features.app.AppModel
import tech.fonia.features.session.SessionRun
import tech.fonia.features.session.attemptOutcome
import tech.fonia.design.Rating
import tech.fonia.store.Attempt
import tech.fonia.store.SessionRecord

// What the session tells the pilot log. Each call only queues a row (PilotLog never waits), so the session's own pace
// and saves stay as they are.

/**
 * The app version, for the install row. The phone's model is no longer written (iOS 2b32a40): with the summary it
 * narrowed down whose child it was. The Android version stays on the phone only, for the log's own checks.
 */
fun pilotEnvironment(context: Context): PilotEnvironment {
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    return PilotEnvironment(appVersion = version, phone = "", sdk = Build.VERSION.SDK_INT)
}

/** The rung a session practises, for a sound session. */
internal val SessionRun.practisedRung get() = (ticket.focus as? SessionFocus.Sound)?.rung

/** `SessionRun.start`: the session's row, with the day and the rung. */
fun AppModel.logSessionStart(run: SessionRun, record: SessionRecord) {
    pilot?.sessionStarted(record, run.practisedRung?.rawValue)
}

/** `SessionRun.finish` (the end screen) and `leaveEarly`. */
fun AppModel.logSessionEnd(run: SessionRun, finished: Boolean) {
    pilot?.sessionEnded(run.sessionID, finished)
}

/** `SessionRun.finishAttempt`, with what it decided; [take] is the trial's key from before it moved on. */
fun AppModel.logAttempt(
    run: SessionRun,
    take: String,
    exercise: Exercise,
    machine: Attempt.Outcome,
    adult: Attempt.Outcome?,
    selfRating: Rating?,
    decided: Attempt.Outcome,
    counted: Boolean,
    retry: Boolean,
) {
    val pilot = pilot ?: return
    val record = runCatching {
        PilotHooks.sessionAttempt(
            content = content,
            profileId = run.profile.id,
            sessionId = run.sessionID,
            take = take,
            ticketSound = run.ticket.sound,
            rung = run.practisedRung,
            trial = run.current,
            exercise = exercise,
            machine = machine,
            adult = adult,
            self = selfRating?.attemptOutcome,
            decided = decided,
            counted = counted,
            retry = retry,
        )
    }.getOrNull() ?: return
    pilot.attempt(record)
}
