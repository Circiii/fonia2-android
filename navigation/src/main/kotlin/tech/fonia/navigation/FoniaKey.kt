package tech.fonia.navigation

import tech.fonia.model.Rung
import tech.fonia.model.SoundId

/**
 * The screen keys of Fonia 2 (docs/v2/02 §8). Pure Kotlin: the shell (`:app`) keeps a back stack of these and draws
 * one screen per key. Lesson steps (`Hold`, `Words`…) are states inside LESSON, never entries in the stack.
 */
sealed interface FoniaKey {
    /** Which shell draws the key (§2 of chapter 01). */
    val shell: Shell get() = Shell.Kid

    enum class Shell { Kid, Adult, Teen, None }
}

/** PATH: the road of the nine sounds. Root of the kid shell. */
data object PathKey : FoniaKey

/**
 * LESSON for one sound at [rung]; [sessionId] ties every attempt of the run together. [exercise] null = the guided
 * 4-minute session (LessonBuilder); otherwise one exercise card of the sound house (`listen`, `hold`, `syllables`,
 * `words`, `pairs`, `sentence`, `twister`, `retell`, `mission`) played as one block. [oneMore]: „Încă una?” (~90 s).
 */
data class LessonKey(
    val sound: SoundId,
    val rung: Rung = Rung.Isolated,
    val sessionId: String,
    val exercise: String? = null,
    val oneMore: Boolean = false,
) : FoniaKey

/**
 * END after a lesson. The summary travels with the key so END draws at once; the attempts themselves are in the
 * store under [sessionId].
 */
data class EndKey(
    val sessionId: String,
    val sound: SoundId,
    val attempts: Int,
    val correct: Int,
    val bestHoldMillis: Int,
    /** True after an early exit (the short END). */
    val short: Boolean = false,
    /** The exercise card that was played, null after the guided session. */
    val exercise: String? = null,
) : FoniaKey

/** MIRROR (chapter 04), from PATH or from a lesson tip. */
data class MirrorKey(val sound: SoundId? = null) : FoniaKey

/** "Pentru adult", behind the AdultGate; its own stack in the adult shell. */
data object AdultHomeKey : FoniaKey {
    override val shell: FoniaKey.Shell get() = FoniaKey.Shell.Adult
}

/** The letter lab (debug entry: long-press on the PATH logo, or `--ez lab true`). */
data object LabKey : FoniaKey {
    override val shell: FoniaKey.Shell get() = FoniaKey.Shell.None
}

// ---- added by the shell package (G2/G8 short + adult area); additive only

/** The short onboarding (ON-0, ON-1, ON-2, ON-5). Root of the stack on the first start. */
data object OnboardingKey : FoniaKey

/** `AdultGate` in front of [then] (an adult-shell key); passing it replaces the gate with [then]. */
data class AdultGateKey(val then: FoniaKey) : FoniaKey {
    override val shell: FoniaKey.Shell get() = FoniaKey.Shell.Adult
}

/** ADULT-CALIBRATE: replay the child's last takes and mark the good ones. */
data object AdultCalibrateKey : FoniaKey {
    override val shell: FoniaKey.Shell get() = FoniaKey.Shell.Adult
}

/** ADULT-SETTINGS. */
data object AdultSettingsKey : FoniaKey {
    override val shell: FoniaKey.Shell get() = FoniaKey.Shell.Adult
}

/** The licences screen, from ADULT-SETTINGS. */
data object AdultLicensesKey : FoniaKey {
    override val shell: FoniaKey.Shell get() = FoniaKey.Shell.Adult
}

/** TEEN-HOME („Doar eu”): a stub until `:feature:speaking` lands. */
data object TeenHomeKey : FoniaKey {
    override val shell: FoniaKey.Shell get() = FoniaKey.Shell.Teen
}

// ---- added by the app package M2 (public speaking, `:feature:speaking`); additive only

/** NO-UM, „Tu, fără ăăă” (chapter 01 §10), from TEEN-HOME. [topic] null = „Liber”. */
data class NoUmKey(val topic: String? = null) : FoniaKey {
    override val shell: FoniaKey.Shell get() = FoniaKey.Shell.Teen
}

/** MINUTE, „Un minut pe zi” (chapter 01 §10), from TEEN-HOME. */
data object MinuteKey : FoniaKey {
    override val shell: FoniaKey.Shell get() = FoniaKey.Shell.Teen
}

// ---- added by the app package M3 (sound house, exercises, 3D mouth); additive only

/** „Casa sunetului”: one sound's character, its guided session and the exercise cards the child chooses from. */
data class SoundHouseKey(val sound: SoundId) : FoniaKey

/** „Vezi gura”: the stylised 3D mouth for [sound] (front view; the cut-away only where it is approved). */
data class Mouth3DKey(val sound: SoundId) : FoniaKey

/** The adult's page for one sound: confirm the sound alone (the lock), „Deblochează tot”, today's mission. */
data class AdultSoundKey(val sound: SoundId) : FoniaKey {
    override val shell: FoniaKey.Shell get() = FoniaKey.Shell.Adult
}
