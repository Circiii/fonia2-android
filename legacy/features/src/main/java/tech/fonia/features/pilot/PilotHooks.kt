package tech.fonia.features.pilot

import tech.fonia.domain.Exercise
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.Rung
import tech.fonia.domain.SoundID
import tech.fonia.domain.SustainTarget
import tech.fonia.domain.Trial
import tech.fonia.phones.RomanianG2P
import tech.fonia.store.Attempt
import java.time.Instant
import java.util.UUID

/** Where the target sound sits in what was said, read from the letters of the item. */
object SoundPositions {
    /**
     * [level] `isolated` and `sentence` decide alone; a single word or syllable is read from its first occurrence of
     * one of the sound's [graphemes] (`ce`/`ci` for CE/CI): at the start, at the end, or in between. An item made only
     * of the sound's letter ("sss") is the sound isolated.
     */
    fun of(text: String?, graphemes: List<String>, level: String?): SoundPosition {
        if (level == LEVEL_ISOLATED) return SoundPosition.Isolated
        if (level == "sentence") return SoundPosition.Sentence
        val words = RomanianG2P.normalize(text ?: return SoundPosition.Unknown).split(nonLetters).filter { it.isNotEmpty() }
        if (words.size > 1) return SoundPosition.Sentence
        val word = words.singleOrNull() ?: return SoundPosition.Unknown
        val marks = graphemes.map(RomanianG2P::normalize).filter { it.isNotEmpty() }
        if (marks.any { mark -> mark.length == 1 && word.all { it == mark[0] } }) return SoundPosition.Isolated
        val (index, mark) = marks.mapNotNull { mark -> word.indexOf(mark).takeIf { it >= 0 }?.let { it to mark } }.minByOrNull { it.first }
            ?: return SoundPosition.Unknown
        return when {
            index == 0 -> SoundPosition.Initial
            index + mark.length == word.length -> SoundPosition.Final
            else -> SoundPosition.Medial
        }
    }

    const val LEVEL_ISOLATED = "isolated"

    private val nonLetters = Regex("[^\\p{L}]+")
}

/** How the log counts an exercise: spoken, a listening choice, or neither (teaching steps). */
internal val Exercise.attemptMode: AttemptMode?
    get() = when {
        isProduction -> AttemptMode.Production
        isIdentification -> AttemptMode.Listening
        else -> null
    }

/** The sound an exercise works on, when it names one. */
internal val Exercise.practisedSound: SoundID?
    get() = when (this) {
        is Exercise.Repeat -> prompt.sound
        is Exercise.Name -> sound
        is Exercise.Twister -> sound
        is Exercise.Hear -> sound
        is Exercise.Intro -> sound
        is Exercise.Sustain -> (target as? SustainTarget.Sound)?.id
        else -> null
    }

/** `isolated`, `syllable`, `word` or `sentence`, for the items that are one of them. */
internal val Exercise.itemLevel: String?
    get() = when (this) {
        is Exercise.Repeat -> prompt.level.rawValue
        is Exercise.Name, is Exercise.Hear, is Exercise.Sort, is Exercise.Choose, is Exercise.Stress -> "word"
        is Exercise.Twister -> "sentence"
        is Exercise.Sustain -> if (target is SustainTarget.Sound) SoundPositions.LEVEL_ISOLATED else null
        else -> null
    }

/** Builds the log's rows from what the session and the mirror know when a take is settled. */
object PilotHooks {
    /** The take of a trial within its session, `<trial>.<retry>`: what ties a shadow score to its attempt. */
    fun takeKey(trialIndex: Int, retryToken: Int): String = "$trialIndex.$retryToken"

    /** One settled attempt of a session (`SessionRun.finishAttempt`); null for steps that are not attempts. */
    fun sessionAttempt(
        content: FoniaContent,
        profileId: UUID,
        sessionId: UUID,
        take: String,
        ticketSound: SoundID?,
        rung: Rung?,
        trial: Trial?,
        exercise: Exercise,
        machine: Attempt.Outcome,
        adult: Attempt.Outcome?,
        self: Attempt.Outcome?,
        decided: Attempt.Outcome,
        counted: Boolean,
        retry: Boolean,
        at: Instant = Instant.now(),
    ): AttemptRecord? {
        val mode = exercise.attemptMode ?: return null
        val sound = exercise.practisedSound ?: ticketSound
        val level = exercise.itemLevel
        return AttemptRecord(
            profileId = profileId,
            sessionId = sessionId,
            at = at,
            take = take,
            mode = mode,
            exercise = exercise.kind.rawValue,
            level = level,
            sound = sound?.rawValue,
            position = SoundPositions.of(exercise.itemText, graphemes(content, sound), level),
            rung = rung?.rawValue,
            block = trial?.block?.kind?.rawValue,
            feedback = trial?.feedback?.rawValue,
            machine = machine,
            adult = adult,
            self = self,
            decided = decided,
            counted = counted,
            retry = retry,
        )
    }

    /** One take at the mirror (the mouth coach): counted apart from the dose (03 §4.1). */
    fun mirrorAttempt(content: FoniaContent, profileId: UUID, take: String, sound: SoundID, text: String, machine: Attempt.Outcome, at: Instant = Instant.now()) =
        AttemptRecord(
            profileId = profileId,
            sessionId = null,
            at = at,
            take = take,
            mode = AttemptMode.Mirror,
            exercise = Exercise.Kind.Repeat.rawValue,
            level = "syllable",
            sound = sound.rawValue,
            position = SoundPositions.of(text, graphemes(content, sound), "syllable"),
            rung = null,
            block = null,
            feedback = null,
            machine = machine,
            adult = null,
            self = null,
            decided = machine,
            counted = false,
            retry = false,
        )

    fun graphemes(content: FoniaContent, sound: SoundID?): List<String> =
        sound?.let { runCatching { content.sound(it).graphemes }.getOrNull() }.orEmpty()
}
