package tech.fonia.features.session

import tech.fonia.domain.EnglishFocus
import tech.fonia.domain.SessionFocus
import tech.fonia.domain.SoundID
import tech.fonia.store.Attempt

// The extensions at the end of iOS `Session/SessionRun.swift` (lines 284-327). The first half needs them already
// (`PathModel` decides what is finished from `key`, the settings use `Settings`), so they live here; the port of
// `SessionRun` uses these and does not declare them again.

/** Good, nearly or not yet. The other two mean nobody could say. */
val Attempt.Outcome.isVerdict: Boolean
    get() = when (this) {
        Attempt.Outcome.Good, Attempt.Outcome.Almost, Attempt.Outcome.NotYet -> true
        Attempt.Outcome.Unclear, Attempt.Outcome.NotJudged -> false
    }

/** The sound the session is about, when it is about one. */
val SessionFocus.soundID: SoundID?
    get() = when (this) {
        is SessionFocus.Sound -> id
        is SessionFocus.Meet -> id
        is SessionFocus.SyllablesBack -> id
        is SessionFocus.Story -> id
        else -> null
    }

/** Sessions on the same thing share a history. Sounds keep theirs across rungs. */
val SessionFocus.key: String
    get() = when (this) {
        is SessionFocus.Sound -> "sound:${id.rawValue}"
        is SessionFocus.Meet -> "meet:${id.rawValue}"
        is SessionFocus.Warmups -> "warmups:${ids.joinToString(",")}"
        is SessionFocus.SyllablesBack -> "back:${id.rawValue}"
        is SessionFocus.Story -> "story:${id.rawValue}"
        SessionFocus.Voicing -> "voicing"
        SessionFocus.VoicingSay -> "voicing-say"
        is SessionFocus.DictionLesson -> "diction-lesson:${lesson.rawValue}"
        is SessionFocus.Diction -> "diction:${area.rawValue}"
        is SessionFocus.English -> when (val english = focus) {
            is EnglishFocus.Sentences -> "en-sentences:${english.contrast}"
            is EnglishFocus.Drill -> "en-drill:${english.contrast}"
            is EnglishFocus.Retest -> "en-retest:${english.contrast}"
            is EnglishFocus.Speaking -> "en-speaking:${english.contrast}"
            is EnglishFocus.Melody -> "en-melody:${english.lesson}"
        }
    }

/** The `UserDefaults` keys, kept in `FoniaPreferences` on Android. */
object Settings {
    const val keepRecordings = "keepChildRecordings"
    const val effectsOff = "effectsOff"

    /** Setări → Experimental: the drawings on the live mirror (iOS `FaceGuideSwitch`). Off until an adult turns them on. */
    const val faceGuides = "fonia.lab.faceGuides"

    /** Setări → Experimental: „Vorbit în public” in front of the camera (iOS `CameraCoachSwitch`). Off by default. */
    const val cameraCoach = "fonia.lab.cameraCoach"
}

/**
 * The camera coach waits behind its switch, as on iOS, until it has been tried on a real phone. A child never sees it,
 * switch or not: its report scores the talk.
 */
object CameraCoachSwitch {
    fun shows(stored: Boolean, isChild: Boolean): Boolean = stored && !isChild
}
