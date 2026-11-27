package tech.fonia.features.session

import tech.fonia.domain.ChoosePrompt
import tech.fonia.domain.Exercise
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.Language
import tech.fonia.domain.Session
import tech.fonia.domain.SessionBlock
import tech.fonia.domain.SessionFocus
import tech.fonia.domain.SoundID
import tech.fonia.domain.WordItem
import kotlin.random.Random

/**
 * New listening items on every run, so what he hears does not repeat each time.
 *
 * Why it repeated: the session builder (kept identical to iOS, `ios-sessions.txt`) draws the listening block from tiny
 * pools: „Ce ai auzit?” plays all six `voicingPairs` every time, only shuffled; a sound's identification deck has its
 * hero pair plus the few recorded contrast pairs (GE: four items for seven draws), and its decks ignore what was heard
 * before. So the builder stays as it is, and the session then redraws its listening block here, from a wider pool (the
 * same pools plus the v2 content's pairs and words that have a clip), avoiding what the last runs on this focus played,
 * with a new order and a new answer side each time. Only items whose clip exists are drawn: the phone never says them
 * in a synthetic voice.
 */
class ListeningVariety(
    private val content: FoniaContent,
    private val v2: tech.fonia.lessons.LessonContent?,
    private val hasClip: (String) -> Boolean,
    private val hasPicture: (String) -> Boolean,
) {
    /** One pair heard in a „Ce ai auzit?”: either word can be the answer. */
    data class Pair2(val a: WordItem, val b: WordItem) {
        val key: String get() = listOf(a.text, b.text).sorted().joinToString("|")
    }

    fun vary(session: Session, focus: SessionFocus, recent: Set<String>, random: Random): Session {
        val trials = session.trials
        val listening = trials.indices.filter { trials[it].block.kind == SessionBlock.Kind.Listening }
        if (listening.isEmpty()) return session
        val pairs = when (focus) {
            SessionFocus.Voicing -> voicingPool()
            is SessionFocus.Sound -> identificationPool(focus.id)
            is SessionFocus.Meet -> emptyList()
            else -> return session
        }
        val words = when (focus) {
            is SessionFocus.Sound -> wordPool(focus.id)
            is SessionFocus.Meet -> wordPool(focus.id)
            else -> emptyList()
        }
        val chooseAt = listening.filter { trials[it].exercise is Exercise.Choose }
        val hearAt = listening.filter { (trials[it].exercise as? Exercise.Hear)?.has == true }
        val drawnPairs = draw(pairs, chooseAt.size, recent, random) { setOf(it.a.text, it.b.text) }
        val drawnWords = draw(words, hearAt.size, recent, random) { setOf(it.text) }
        val out = trials.toMutableList()
        chooseAt.forEachIndexed { k, at ->
            val pair = drawnPairs.getOrNull(k) ?: return@forEachIndexed
            val old = (out[at].exercise as Exercise.Choose).prompt
            val (answer, other) = if (random.nextBoolean()) pair.a to pair.b else pair.b to pair.a
            out[at] = out[at].copy(exercise = Exercise.Choose(ChoosePrompt(answer = answer, other = other, language = Language.Ro, voice = old.voice, cue = old.cue)))
        }
        hearAt.forEachIndexed { k, at ->
            val word = drawnWords.getOrNull(k) ?: return@forEachIndexed
            val old = out[at].exercise as Exercise.Hear
            out[at] = out[at].copy(exercise = old.copy(item = word))
        }
        // A new order inside the listening block (the block keeps its place in the session).
        val shuffled = listening.map { out[it] }.shuffled(random)
        listening.forEachIndexed { k, at -> out[at] = shuffled[k] }
        return session.copy(trials = out)
    }

    /**
     * [count] items: first those holding nothing heard in the last runs, then the rest, each part shuffled; when the
     * pool is smaller than [count] it is dealt again (never the same item twice in a row). Empty pool: nothing.
     */
    internal fun <T> draw(pool: List<T>, count: Int, recent: Set<String>, random: Random, texts: (T) -> Set<String>): List<T> {
        if (pool.isEmpty() || count <= 0) return emptyList()
        val fresh = pool.filter { item -> texts(item).none { it in recent } }.shuffled(random)
        val used = pool.filter { item -> texts(item).any { it in recent } }.shuffled(random)
        val out = ArrayList<T>()
        var deck = fresh + used
        while (out.size < count) {
            for (item in deck) {
                if (out.size >= count) break
                if (out.lastOrNull() == item && deck.size > 1) continue
                out += item
            }
            deck = pool.shuffled(random)
        }
        return out
    }

    /** „Ce ai auzit?”: his six voicing pairs, plus the v2 pairs of the voiced/voiceless sibilants (Z–S, J–Ș, GE–CE). */
    internal fun voicingPool(): List<Pair2> {
        val legacy = content.listening.voicingPairs.filter { it.a.recorded && it.b.recorded }.map { Pair2(it.a, it.b) }
        val voicing = setOf(setOf("z", "s"), setOf("j", "ș"), setOf("ge", "ce"))
        val extra = v2Pairs { sound, contrast -> setOf(sound, contrast) in voicing }
        return (legacy + extra).distinctBy { it.key }
    }

    /** A sound's identification: the builder's own pairs (hero and recorded contrasts) plus its v2 pairs with clips. */
    internal fun identificationPool(id: SoundID): List<Pair2> {
        val sound = content.sound(id)
        val own = sound.contrasts.flatMap { contrast ->
            val other = content.sound(contrast.with)
            val hero = Pair2(WordItem(text = sound.hero.says, picture = sound.hero.picture), WordItem(text = other.hero.says, picture = other.hero.picture))
            listOf(hero) + contrast.pairs.filter { it.isRecorded }.map { Pair2(it.a, it.b) }
        }
        val extra = v2Pairs { s, _ -> s == id.rawValue }
        return (own + extra).distinctBy { it.key }.filter { hasClip(it.a.text) && hasClip(it.b.text) }
    }

    /** Words that hold the sound: his recorded ones, plus the v2 words with a clip. */
    internal fun wordPool(id: SoundID): List<WordItem> {
        val own = content.sound(id).words.positional.filter { it.recorded }
        val extra = v2?.sounds?.values?.firstOrNull { it.sound == id.rawValue }?.items.orEmpty()
            .filter { it.type == "word" && hasClip(it.text) }
            .map { WordItem(text = it.text, picture = picture(it.emoji)) }
        return (own + extra).distinctBy { it.text.lowercase() }.filter { hasClip(it.text) }
    }

    private fun v2Pairs(take: (sound: String, contrast: String) -> Boolean): List<Pair2> {
        val sounds = v2?.sounds?.values ?: return emptyList()
        return sounds.flatMap { sc ->
            sc.items.filter { it.type == "pair" && it.a != null && it.b != null && take(sc.sound, it.contrast.orEmpty()) }
                .filter { hasClip(it.a!!) && hasClip(it.b!!) }
                .map { Pair2(WordItem(text = it.a!!, picture = picture(it.emojiA)), WordItem(text = it.b!!, picture = picture(it.emojiB))) }
        }
    }

    /** An emoji as the picture name of `images/pictures` (its code points in hex, "1f3b2"), when that picture exists. */
    private fun picture(emoji: String?): String? {
        if (emoji.isNullOrEmpty()) return null
        val points = emoji.codePoints().toArray().filter { it != 0xFE0F }.joinToString("-") { Integer.toHexString(it) }
        return points.takeIf { hasPicture(it) }
    }
}
