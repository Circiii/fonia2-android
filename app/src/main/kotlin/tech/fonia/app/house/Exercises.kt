package tech.fonia.app.house

import tech.fonia.app.data.SoundPractice
import tech.fonia.lessons.LessonContent
import tech.fonia.model.Rung
import tech.fonia.model.SoundId

/**
 * The exercise cards of „Casa sunetului”. [rung] is the rung whose material the card plays (LessonBuilder, one block);
 * null for the two viewing aids (MIRROR, the 3D mouth), which are always open. [raw] is the launch-flag name.
 */
enum class Exercise(val raw: String, val rung: Rung?) {
    Listen("listen", Rung.Listen),
    Hold("hold", Rung.Isolated),
    Syllables("syllables", Rung.Syllables),
    Words("words", Rung.WordsInitial),
    Pairs("pairs", Rung.Pairs),
    Sentence("sentence", Rung.Sentences),
    Twister("twister", Rung.Twister),
    Retell("retell", Rung.Retell),
    Mirror("mirror", null),
    Mouth3D("mouth3d", null),
    Mission("mission", Rung.Everyday),
    ;

    /** Opens a lesson run (LESSON with one block); MIRROR and the 3D mouth open their own screens. */
    val playsInLesson: Boolean get() = rung != null

    companion object {
        fun of(raw: String?): Exercise? = entries.firstOrNull { it.raw == raw?.lowercase() }
    }
}

/** Why a card is closed. Gentle: a closed card still shows what is inside, with a small lock. */
enum class CardLock {
    Open,
    /** Every rung after `isolated` waits for an adult to confirm the sound alone (chapter 06 §1, the lock). */
    NeedsAdult,
    /** Not reached yet on the ladder. */
    NotYet,
}

/**
 * The lock rules (pure). Everything up to `isolated` (listening, holding, the mirror, the 3D mouth) is always open;
 * after it, a card opens once an adult confirmed the sound alone AND the ladder reached the card's rung — or when the
 * adult chose „Deblochează tot pentru acest sunet”. Everything reached stays replayable: the child chooses.
 */
object ExerciseLocks {
    fun lockOf(exercise: Exercise, practice: SoundPractice): CardLock {
        val rung = exercise.rung ?: return CardLock.Open
        if (rung.ordinal <= Rung.Isolated.ordinal) return CardLock.Open
        if (practice.unlockAll) return CardLock.Open
        if (!practice.adultConfirmed) return CardLock.NeedsAdult
        return if (practice.rung.ordinal >= rung.ordinal) CardLock.Open else CardLock.NotYet
    }

    /** The rung a card plays: the furthest reached rung of its family (Words → the reached word position). */
    fun rungFor(exercise: Exercise, practice: SoundPractice): Rung? {
        val base = exercise.rung ?: return null
        val reached = practice.rung
        return when (exercise) {
            Exercise.Words -> if (reached in WORD_RUNGS) reached else if (reached.ordinal > Rung.Clusters.ordinal) Rung.Clusters else base
            Exercise.Syllables -> if (reached == Rung.Back) Rung.Back else base
            else -> base
        }
    }

    val WORD_RUNGS = setOf(Rung.WordsInitial, Rung.WordsMedial, Rung.WordsFinal, Rung.Clusters)
}

/** One card as the house draws it. [dots] filled of [dotsTotal] = how often it was played (never a score). */
data class ExerciseCard(
    val exercise: Exercise,
    val title: String,
    val subtitle: String,
    val count: Int,
    val lock: CardLock,
    val dots: Int,
    val dotsTotal: Int = 3,
)

/** What the content holds for each card of one sound (counts and a short example), pure and testable on the JVM. */
data class ContentSummary(val counts: Map<Exercise, Int>, val examples: Map<Exercise, String>) {
    companion object {
        fun of(content: LessonContent, sound: SoundId): ContentSummary {
            fun items(vararg rungs: Rung) = rungs.flatMap { content.items(sound, it) }
            val words = items(Rung.WordsInitial, Rung.WordsMedial, Rung.WordsFinal, Rung.Clusters)
            val listen = items(Rung.Listen)
            val pairs = items(Rung.Pairs)
            val sentences = items(Rung.Sentences)
            val twisters = content.twisters(sound)
            val stories = items(Rung.Retell)
            val missions = items(Rung.Everyday)
            val syllables = items(Rung.Syllables, Rung.Back)
            val holds = items(Rung.Isolated)
            val counts = mapOf(
                Exercise.Listen to listen.size,
                Exercise.Hold to holds.size,
                Exercise.Syllables to syllables.size,
                Exercise.Words to words.size,
                Exercise.Pairs to pairs.size,
                Exercise.Sentence to sentences.size,
                Exercise.Twister to twisters.size,
                Exercise.Retell to stories.size,
                Exercise.Mirror to 1,
                Exercise.Mouth3D to 1,
                Exercise.Mission to missions.size,
            )
            val examples = mapOf(
                Exercise.Listen to listen.take(2).joinToString(", ") { it.text },
                Exercise.Hold to (holds.firstOrNull()?.text ?: sound.letter.lowercase()),
                Exercise.Syllables to syllables.take(3).joinToString(" ") { it.text },
                Exercise.Words to words.take(2).joinToString(", ") { it.text },
                Exercise.Pairs to (pairs.firstOrNull()?.let { "${it.a} – ${it.b}" } ?: ""),
                Exercise.Sentence to (sentences.firstOrNull()?.text ?: ""),
                Exercise.Twister to (twisters.firstOrNull()?.text ?: ""),
                Exercise.Retell to (stories.firstOrNull()?.lines?.firstOrNull() ?: ""),
                Exercise.Mission to (missions.firstOrNull()?.text ?: ""),
            )
            return ContentSummary(counts, examples)
        }
    }
}

/**
 * The catalogue of one sound: eleven cards in the house's order, each with this sound's own title (the `Hold` card is
 * the character's signature game: Sâsâilă's path, Șușu's steam, Zuzu's flower…), counts and examples from content v2,
 * the lock and the dots. A card whose content is empty is left out.
 */
object ExerciseCatalog {
    val ORDER = listOf(
        Exercise.Listen, Exercise.Hold, Exercise.Syllables, Exercise.Words, Exercise.Pairs, Exercise.Sentence,
        Exercise.Twister, Exercise.Retell, Exercise.Mirror, Exercise.Mouth3D, Exercise.Mission,
    )

    fun forSound(sound: SoundId, summary: ContentSummary, practice: SoundPractice): List<ExerciseCard> =
        ORDER.mapNotNull { ex ->
            val count = summary.counts[ex] ?: 0
            if (count == 0) return@mapNotNull null
            ExerciseCard(
                exercise = ex,
                title = HouseTexts.cardTitle(ex),
                subtitle = HouseTexts.cardSubtitle(ex, sound, count, summary.examples[ex].orEmpty()),
                count = count,
                lock = ExerciseLocks.lockOf(ex, practice),
                dots = (practice.plays[ex] ?: 0).coerceAtMost(3),
            )
        }
}

/** The house's words (Romanian, short; the child sees the titles, the adult reads the subtitles). */
object HouseTexts {
    fun cardTitle(e: Exercise): String = when (e) {
        Exercise.Listen -> "Ascultă"
        Exercise.Hold -> "Ține sunetul"
        Exercise.Syllables -> "Silabe"
        Exercise.Words -> "Cuvinte"
        Exercise.Pairs -> "Perechi"
        Exercise.Sentence -> "Propoziții"
        Exercise.Twister -> "Frământări"
        Exercise.Retell -> "Poveste"
        Exercise.Mirror -> "Oglinda"
        Exercise.Mouth3D -> "Vezi gura"
        Exercise.Mission -> "Misiunea de azi"
    }

    /** The signature game of each sound's character (chapter 06 §4). */
    fun holdGame(s: SoundId): String = when (s) {
        SoundId.S -> "Sâsâilă se lungește"
        SoundId.Sh -> "Trenulețul Șușu"
        SoundId.Z -> "Zuzu zboară la floare"
        SoundId.J -> "Vâjâilă și morișca"
        SoundId.Ts -> "Țuțu sare"
        SoundId.Ce -> "Cici stropește"
        SoundId.Ge -> "Gigel pornește"
        SoundId.R -> "Rică mârâie"
        SoundId.L -> "Lalalu cântă"
    }

    fun cardSubtitle(e: Exercise, s: SoundId, count: Int, example: String): String = when (e) {
        Exercise.Listen -> "Bine sau încurcat?"
        Exercise.Hold -> holdGame(s)
        Exercise.Syllables -> example
        Exercise.Words -> "$count cuvinte cu poze"
        Exercise.Pairs -> example
        Exercise.Sentence -> "$count propoziții"
        Exercise.Twister -> "Rar, apoi mai repede"
        Exercise.Retell -> "Trei poze, o poveste"
        Exercise.Mirror -> "Vezi-ți buzele"
        Exercise.Mouth3D -> "Gura, din toate părțile"
        Exercise.Mission -> "Pentru acasă"
    }
}
