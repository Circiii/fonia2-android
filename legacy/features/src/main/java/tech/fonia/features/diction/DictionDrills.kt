package tech.fonia.features.diction

import tech.fonia.domain.Exercise
import tech.fonia.domain.FeedbackLevel
import tech.fonia.domain.Language
import tech.fonia.domain.RepeatPrompt
import tech.fonia.domain.SessionBlock
import tech.fonia.domain.SoundDef
import tech.fonia.domain.SoundID
import tech.fonia.domain.Trial
import tech.fonia.lessons.ContentItem
import tech.fonia.lessons.LessonContent
import tech.fonia.model.SoundId

/** The steps of a twister's tempo ladder: the model is played at this speed and the drill strip shows the animal. */
enum class Tempo(val speed: Float) { Slow(0.75f), Medium(1f), Fast(1.18f) }

/** One drill of the ladder: a short run of trials, 1-2 minutes. [chips] are what the strip above the take shows. */
data class Drill(
    val index: Int,
    val title: String,
    val detail: String,
    val trials: List<Trial>,
    val chips: List<String>,
    /** Per trial, for the twister: the tempo of the model and of the take. */
    val tempos: List<Tempo>? = null,
    /** A drill with no judged take (the warm-up) passes when it is played to the end. */
    val judged: Boolean = true,
)

/**
 * „Antrenament de dicție”: the progression of the classic Romanian diction manuals (articulation warm-up → the sound
 * alone → syllable ladders with every vowel, direct then reversed → words by position → pairs with the sound it is
 * confused with → short sentences → a tongue twister slow, medium, fast → a short text), filled only with the original,
 * validated v2 texts of this repo (`spec/content/v2`) and the app's own content. Nothing from any book.
 */
object DictionDrills {
    const val PASS_SHARE = 0.6

    private fun repeat(text: String, sound: SoundID, level: RepeatPrompt.Level, picture: String? = null, feedback: FeedbackLevel = FeedbackLevel.Quick) =
        Trial(Exercise.Repeat(RepeatPrompt(text = text, language = Language.Ro, sound = sound, picture = picture, level = level)), feedback, SessionBlock(SessionBlock.Kind.TargetB))

    private fun Exercise.trial(feedback: FeedbackLevel = FeedbackLevel.Quick) = Trial(this, feedback, SessionBlock(SessionBlock.Kind.TargetB))

    fun soundId(id: SoundID): SoundId = SoundId.entries.first { it.raw == id.rawValue }

    /** `"🛷"` → `"1f6f7"`, the name of a word picture, when there is one. */
    fun pictureName(emoji: String?, hasPicture: (String) -> Boolean): String? {
        if (emoji.isNullOrEmpty()) return null
        val name = emoji.codePoints().toArray().filter { it != 0xFE0F }.joinToString("-") { Integer.toHexString(it) }
        return name.takeIf(hasPicture)
    }

    /**
     * The ladder of [sound]: 8 drills (fewer when the content has no item of a kind). [hasClip] says whether a text has
     * a recorded or generated clip (shorter clips first); [hasPicture] whether a picture name exists.
     */
    fun build(sound: SoundDef, v2: LessonContent, hasClip: (String) -> Boolean, hasPicture: (String) -> Boolean): List<Drill> {
        val id = sound.id
        val items = v2.sound(soundId(id)).items
        fun of(type: String) = items.filter { it.type == type }
        val drills = mutableListOf<Drill>()
        fun add(title: String, detail: String, trials: List<Trial>, chips: List<String>, tempos: List<Tempo>? = null, judged: Boolean = true) {
            if (trials.isEmpty()) return
            drills += Drill(drills.size, title, detail, trials, chips, tempos, judged)
        }

        // 1. Articulation warm-up: the app's own mouth exercises for this sound.
        val warmups = sound.warmups.take(3)
        add("Încălzirea", "Gura și limba se pregătesc", warmups.map { Exercise.Warmup(it).trial(FeedbackLevel.Teach) }, warmups.map { "•" }, judged = false)

        // 2. The sound alone: held, or in bursts (Ț, CE, GE), or repeated syllables (R, L).
        val alone = sound.isolatedPractice
        add("Sunetul singur", "Ține-l sau spune-l scurt", alone.map { it.trial(FeedbackLevel.Teach) }, alone.map { sound.letter.split("/").first() })

        // 3. The syllable ladder: with every vowel, direct (sa so su…) then reversed (as os us…).
        val direct = of("syllable").filter { it.shape == "CV" }
        val reversed = of("syllable").filter { it.shape == "VC" }
        val ladder = (direct + reversed).map { it.text }.distinct().take(12)
        add("Scara silabelor", "Cu toate vocalele, apoi invers", ladder.map { repeat(it, id, RepeatPrompt.Level.Syllable) }, ladder)

        // 4. Words in columns by position: at the start, in the middle, at the end.
        fun words(pos: String) = of("word").filter { it.pos == pos }.sortedWith(compareBy<ContentItem>({ !hasClip(it.text) }, { it.difficulty }, { it.text.length })).take(3)
        val columns = words("initial") + words("medial") + words("final")
        add(
            "Cuvinte pe coloane",
            "La început, la mijloc, la sfârșit",
            columns.map { repeat(it.text, id, RepeatPrompt.Level.Word, pictureName(it.emoji, hasPicture)) },
            columns.map { it.text },
        )

        // 5. Pairs with the sound it is mistaken for: each word of the pair, its own sound judged.
        val pairs = of("pair").filter { it.a != null && it.b != null }
            .sortedWith(compareBy({ !(hasClip(it.a!!) && hasClip(it.b!!)) }, { it.difficulty })).take(3)
        val pairTrials = pairs.flatMap { pair ->
            val other = pair.contrast?.let { SoundID.fromRawValue(it) }
            listOfNotNull(
                repeat(pair.a!!, id, RepeatPrompt.Level.Word),
                other?.let { repeat(pair.b!!, it, RepeatPrompt.Level.Word) },
            )
        }
        add("Perechi", "Două cuvinte care sună aproape la fel", pairTrials, pairTrials.map { (it.exercise as Exercise.Repeat).prompt.text })

        // 6. Short sentences.
        val sentences = of("sentence").sortedWith(compareBy({ !hasClip(it.text) }, { it.difficulty })).take(3)
        add("Propoziții", "Scurte, cu sunetul de mai multe ori", sentences.map { repeat(it.text, id, RepeatPrompt.Level.Sentence) }, sentences.mapIndexed { i, _ -> "${i + 1}" })

        // 7. A tongue twister, slow → medium → fast; only the target sound is judged.
        val twister = v2.twisters(soundId(id)).sortedWith(compareBy({ !hasClip(it.text) }, { it.difficulty })).firstOrNull()
        if (twister != null) {
            val tempos = Tempo.entries.toList()
            add(
                "Frământarea",
                "Rar, mai repede, repede",
                tempos.map { Exercise.Twister(twister.text, Language.Ro, id).trial() },
                tempos.map { it.name },
                tempos = tempos,
            )
        }

        // 8. A short text: three sentences that tell a little story.
        val story = of("story").sortedBy { it.difficulty }.firstOrNull()
        if (story != null && story.lines.isNotEmpty()) {
            add("Un text scurt", "Trei propoziții, una după alta", story.lines.map { repeat(it, id, RepeatPrompt.Level.Sentence) }, story.lines.mapIndexed { i, _ -> "${i + 1}" })
        }
        return drills
    }

    /**
     * The first drill is always open; each next one opens when the one before passed (its best share ≥ [PASS_SHARE]);
     * a drill once played stays open.
     */
    fun isOpen(index: Int, best: Map<Int, Double>): Boolean =
        index == 0 || index in best || (best[index - 1] ?: -1.0) >= PASS_SHARE

    /** The share of good takes among the judged ones; a drill with nothing judged that was played through counts 1. */
    fun share(good: Int, judged: Int, drillIsJudged: Boolean): Double = when {
        !drillIsJudged -> 1.0
        judged == 0 -> 0.0
        else -> good.toDouble() / judged
    }
}
