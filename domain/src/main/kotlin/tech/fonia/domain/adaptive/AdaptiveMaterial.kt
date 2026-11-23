package tech.fonia.domain.adaptive

import tech.fonia.domain.Exercise
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.Language
import tech.fonia.domain.RepeatPrompt
import tech.fonia.domain.Rung
import tech.fonia.domain.SortSide
import tech.fonia.domain.SoundDef
import tech.fonia.domain.SoundFamily
import tech.fonia.domain.SoundID
import tech.fonia.domain.SustainRole
import tech.fonia.domain.SustainTarget
import tech.fonia.domain.WordItem
import tech.fonia.domain.WordLists
import tech.fonia.domain.characters
import tech.fonia.domain.nfc

/** Items of one rung's material, each with the rung it belongs to (the rung of its mastery key). */
data class Material(val rung: Rung, val exercises: List<Exercise>)

/**
 * What each rung practises: the two targets `SessionBuilder.targets` gives a sound session (same lists, filters and
 * order), read here so the adaptive blocks can draw a rung's material without changing the iOS builder.
 * `AdaptiveMaterialTests` checks that these are the items the iOS sessions put in their target blocks.
 */
class AdaptiveMaterial(private val content: FoniaContent) {
    /** The rung's own material: what its target A blocks draw from. */
    fun primary(sound: SoundID, rung: Rung, avoiding: Set<SoundID>): Material = targets(content.sound(sound), rung, avoiding).first

    /** The second target, at or below the rung: what iOS mixes in (target B and the mixed block). */
    fun secondary(sound: SoundID, rung: Rung, avoiding: Set<SoundID>): Material = targets(content.sound(sound), rung, avoiding).second

    /** The listening items of the minimal-pair rung, which iOS puts before the productions of each of its blocks. */
    fun perception(sound: SoundID): List<Exercise> {
        val def = content.sound(sound)
        val result = soundIdentification(def).toMutableList()
        for (contrast in def.contrasts) {
            val other = content.sound(contrast.with)
            result += def.words.positional
                .filter { it.recorded && !it.otherSounds.contains(contrast.with) }
                .map { Exercise.Sort(a = def.id, b = contrast.with, item = it, answer = SortSide.A) }
            result += other.words.positional
                .filter { it.recorded && !it.otherSounds.contains(def.id) }
                .map { Exercise.Sort(a = def.id, b = contrast.with, item = it, answer = SortSide.B) }
        }
        return result
    }

    private fun targets(sound: SoundDef, rung: Rung, avoiding: Set<SoundID>): Pair<Material, Material> {
        val id = sound.id
        val syllableRepeat = { syllables: List<String> ->
            syllables.map { Exercise.Repeat(RepeatPrompt(text = it, language = Language.Ro, sound = id, level = RepeatPrompt.Level.Syllable)) }
        }
        val wordsAt = { list: (WordLists) -> List<WordItem> -> simpleWords(sound, list, avoiding).map { exercise(it, id) } }
        val clusterWords = sound.words(WordLists::clusters, avoiding = avoiding).map { exercise(it, id) }
        val sentences: List<Exercise> = sound.sentences.map {
            Exercise.Repeat(RepeatPrompt(text = it, language = Language.Ro, sound = id, level = RepeatPrompt.Level.Sentence))
        }
        val allSyllables = sound.syllables.direct + sound.syllables.inverse + sound.syllables.between
        val retell = sound.retell.map { Exercise.Retell(it) }
        val describe = sound.describe.map { Exercise.Describe(it) }

        return when (rung) {
            Rung.Isolated ->
                Material(Rung.Isolated, sound.isolatedPractice) to Material(Rung.Syllables, syllableRepeat(sound.syllables.direct.take(4)))
            Rung.Syllables ->
                Material(Rung.Syllables, syllableRepeat(sound.syllables.direct)) to
                    Material(Rung.Syllables, syllableRepeat(sound.syllables.inverse + sound.syllables.between))
            Rung.WordsInitial -> Material(Rung.WordsInitial, wordsAt(WordLists::initial)) to Material(Rung.Syllables, syllableRepeat(allSyllables))
            Rung.WordsMedial -> Material(Rung.WordsMedial, wordsAt(WordLists::medial)) to Material(Rung.WordsInitial, wordsAt(WordLists::initial))
            Rung.WordsFinal -> Material(Rung.WordsFinal, wordsAt(WordLists::final)) to Material(Rung.WordsMedial, wordsAt(WordLists::medial))
            Rung.Clusters -> {
                // Interference filter: long words that hold a sound the child is avoiding stay out.
                val longWords = sound.words.positional
                    .filter { word -> isLong(word.text) && word.otherSounds.none { it in avoiding } }
                    .map { exercise(it, id) }
                Material(Rung.Clusters, clusterWords + longWords) to Material(Rung.WordsFinal, wordsAt(WordLists::final))
            }
            Rung.Pairs -> Material(Rung.Pairs, pairProduction(sound)) to Material(Rung.Clusters, clusterWords)
            Rung.Sentences -> Material(Rung.Sentences, sentences) to Material(Rung.Pairs, pairProduction(sound))
            Rung.Retell -> Material(Rung.Retell, retell + describe + sentences) to Material(Rung.Sentences, sentences)
            Rung.Everyday -> Material(Rung.Everyday, describe + retell + sentences) to Material(Rung.Sentences, sentences)
        }
    }

    /** Only recorded words, as in `SessionBuilder.soundIdentification`. */
    private fun soundIdentification(sound: SoundDef): List<Exercise> {
        val result = mutableListOf<Exercise>()
        for (contrast in sound.contrasts) {
            val other = content.sound(contrast.with)
            val mine = WordItem(text = sound.hero.says, picture = sound.hero.picture)
            val theirs = WordItem(text = other.hero.says, picture = other.hero.picture)
            result += bothWays(mine, theirs)
            for (pair in contrast.pairs) {
                if (pair.isRecorded) result += bothWays(pair.a, pair.b)
            }
        }
        return result
    }

    companion object {
        private fun pairProduction(sound: SoundDef): List<Exercise> =
            sound.contrasts.flatMap { it.pairs }.flatMap { pair ->
                val result = mutableListOf<Exercise>()
                for ((word, rival) in listOf(pair.a to pair.b, pair.b to pair.a)) {
                    result += Exercise.Repeat(
                        RepeatPrompt(text = word.text, language = Language.Ro, sound = sound.id, picture = word.picture, level = RepeatPrompt.Level.Word, rival = rival.text),
                    )
                }
                pair.spoken?.let { spoken ->
                    result += Exercise.Repeat(RepeatPrompt(text = spoken, language = Language.Ro, sound = sound.id, level = RepeatPrompt.Level.Word))
                }
                result
            }

        /** `SessionBuilder.exercise`: a picture to name when the word can be named from it, else repeat after the model. */
        private fun exercise(word: WordItem, sound: SoundID): Exercise {
            if (word.namable && word.picture != null) return Exercise.Name(sound = sound, item = word)
            return Exercise.Repeat(RepeatPrompt(text = word.text, language = Language.Ro, sound = sound, picture = word.picture, level = RepeatPrompt.Level.Word))
        }

        /** `SessionBuilder.simpleWords`: cluster-free words first when there are enough. */
        private fun simpleWords(sound: SoundDef, list: (WordLists) -> List<WordItem>, avoiding: Set<SoundID>): List<WordItem> {
            val kept = sound.words(list, avoiding = avoiding)
            val clean = kept.filter { it.clusters.isEmpty() }
            return if (clean.size >= 3) clean else kept
        }

        private val vowels: Set<String> = "aăâeiîou".characters().map(::nfc).toSet()

        /** `SessionBuilder.isLong`: at least three groups of vowels, over Characters. */
        private fun isLong(word: String): Boolean {
            var groups = 0
            var inVowels = false
            for (letter in word.characters()) {
                val vowel = nfc(letter) in vowels
                if (vowel && !inVowels) groups += 1
                inVowels = vowel
            }
            return groups >= 3
        }

        private fun bothWays(first: WordItem, second: WordItem): List<Exercise> = listOf(
            Exercise.Choose(tech.fonia.domain.ChoosePrompt(answer = first, other = second, language = Language.Ro)),
            Exercise.Choose(tech.fonia.domain.ChoosePrompt(answer = second, other = first, language = Language.Ro)),
        )
    }
}
