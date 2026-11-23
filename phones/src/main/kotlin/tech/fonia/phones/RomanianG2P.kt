package tech.fonia.phones

import java.text.Normalizer
import java.util.Locale

/**
 * One sound of a pronunciation: its ZIPA tokens (one, or two for an affricate), the letters it comes from (indices
 * into the normalised text, for highlighting) and the practice sound it realises, if any.
 */
data class Phone(
    val tokens: List<String>,
    val letters: IntRange,
    val sound: TargetSound? = null,
    /** First phone of a word: the label sequence gets ZIPA's word-start mark `▁` before it. */
    val wordStart: Boolean = false,
)

/** A word (or a few words) as the model should hear it. */
class Pronunciation(val text: String, val phones: List<Phone>) {
    /** ZIPA tokens, with `▁` at the start of each word, exactly as the label sequence for CTC. */
    val labels: List<String>
        get() = phones.flatMap { phone -> if (phone.wordStart) listOf("▁") + phone.tokens else phone.tokens }

    /** Indices into [phones] where [sound] occurs, in order. */
    fun occurrences(sound: TargetSound): List<Int> = phones.indices.filter { phones[it].sound == sound }

    /** Practice sounds present, in order of first occurrence. */
    val sounds: List<TargetSound> get() = phones.mapNotNull { it.sound }.distinct()

    /** Space-separated phones, an affricate's two tokens written together (ts, tʃ, dʒ). */
    val ipa: String get() = phones.joinToString(" ") { it.tokens.joinToString("") }

    /** The same pronunciation with phone [index] replaced by [tokens] (empty for an omission). */
    fun replacing(index: Int, tokens: List<String>, sound: TargetSound? = null): Pronunciation {
        val old = phones[index]
        val replaced = phones.toMutableList()
        if (tokens.isEmpty()) {
            replaced.removeAt(index)
            // Keep the word start on the next phone of the same word, if there is one.
            if (old.wordStart && index < replaced.size && !replaced[index].wordStart) replaced[index] = replaced[index].copy(wordStart = true)
        } else {
            replaced[index] = old.copy(tokens = tokens, sound = sound)
        }
        return Pronunciation(text, replaced)
    }

    override fun toString(): String = "$text /$ipa/"
}

/**
 * Romanian spelling to ZIPA phones, by rule. Our own rules, written for the practice words: no eSpeak NG (GPL), no
 * lexicon with a non-permissive licence. It follows `phonemes()` in `content/tools/check_ro_content.py` for the
 * consonants and adds the vowel and glide rules the acoustic model needs.
 *
 * Consonants: `ce`/`ci` → t ʃ and `ge`/`gi` → d ʒ (the e/i is only a spelling mark before a, ă, â, î, o, u, as in
 * „ceapă”, „ciocolată”, „geantă”); `che`/`chi`, `ghe`/`ghi` → k, g; ț → t s; ș → ʃ; j → ʒ; x → k s.
 * Vowels: ă → ə, â/î → ɨ; „ea”, „oa” → e a, o a (ZIPA has no non-syllabic mark, and its Romanian training
 * transcriptions, made with Epitran/CharsiuG2P, spell them as two vowels).
 * Glides: i and u next to a vowel nucleus are j and w („cai”, „lei”, „iepure”, „leu”, „autobuz”); the pronouns
 * „el”, „ea”, „ei”, „ele”, „eu”, „este”… start with j.
 * Word-final i after a consonant („arici”, „lupi”) is only palatalisation in Romanian ([ʲ]); it is written `i`, the
 * way the model's training transcriptions wrote it and the way the model emits it on these words.
 *
 * Stress, vowel reduction and assimilation are not modelled: the score of a target compares candidates that share
 * the same context, so a context error costs both sides alike.
 */
object RomanianG2P {
    private const val VOWELS = "aăâeiîou"
    private const val MARKED = "aăâîou"

    /** Word forms that start with [j] before the written e (personal pronouns and the verb „a fi”). */
    private val jPronouns = setOf("e", "ea", "ei", "el", "ele", "eu", "este", "ești", "era", "erai", "eram", "erați", "erau")

    /** NFC, lower case, cedilla S and T (U+015F, U+0163) turned into ș and ț, as `normalizeRo()` in the research notes. */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            .replace("ş", "ș").replace("ţ", "ț")

    fun pronounce(text: String): Pronunciation {
        val normalized = normalize(text)
        val phones = ArrayList<Phone>()
        var index = 0
        while (index < normalized.length) {
            if (!normalized[index].isLetter()) {
                index++
                continue
            }
            var end = index
            while (end < normalized.length && normalized[end].isLetter()) end++
            word(normalized, index, end, phones)
            index = end
        }
        return Pronunciation(normalized, phones)
    }

    private class Builder(val out: MutableList<Phone>) {
        var startOfWord = true

        /** The previous phone of this word was a vowel nucleus (not a glide, not a consonant). */
        var afterNucleus = false

        fun add(tokens: List<String>, letters: IntRange, sound: TargetSound? = null, nucleus: Boolean = false) {
            out += Phone(tokens, letters, sound, wordStart = startOfWord)
            startOfWord = false
            afterNucleus = nucleus
        }
    }

    private fun word(text: String, start: Int, end: Int, out: MutableList<Phone>) {
        val b = Builder(out)
        fun at(i: Int): Char? = if (i in start until end) text[i] else null
        fun isVowel(c: Char?) = c != null && c in VOWELS
        val wordText = text.substring(start, end)
        var i = start
        while (i < end) {
            val c = text[i]
            val next = at(i + 1)
            val after = at(i + 2)
            when (c) {
                'c', 'g' -> {
                    val velar = if (c == 'c') "k" else "g"
                    when {
                        next == 'h' && (after == 'e' || after == 'i') -> {
                            b.add(listOf(velar), i..i + 1)
                            i += 2
                        }
                        next == 'e' || next == 'i' -> {
                            val sound = if (c == 'c') TargetSound.Ce else TargetSound.Ge
                            // „cea”, „cio”, „ciu”, „gea”, „gio”, „giu”: the e/i only marks the affricate.
                            if (after != null && after in MARKED) {
                                b.add(sound.canonical, i..i + 1, sound)
                                i += 2
                            } else {
                                b.add(sound.canonical, i..i, sound)
                                i += 1
                            }
                        }
                        else -> {
                            b.add(listOf(velar), i..i)
                            i += 1
                        }
                    }
                }
                'x' -> {
                    b.add(listOf("k"), i..i)
                    b.add(listOf("s"), i..i, TargetSound.S)
                    i += 1
                }
                'i' -> {
                    val nextVowel = isVowel(next)
                    val glide = when {
                        i == start && nextVowel -> true // „iepure”, „iarnă”
                        b.afterNucleus -> true // „cai”, „lei”, „câini”, „băiat”, „copii”
                        else -> false
                    }
                    if (glide) b.add(listOf("j"), i..i) else b.add(listOf("i"), i..i, nucleus = true)
                    i += 1
                }
                'u' -> {
                    // „au”, „eu”, „ou”, „ău”: u after a nucleus is the glide w („leu”, „autobuz”, „dinozaur”).
                    if (b.afterNucleus) b.add(listOf("w"), i..i) else b.add(listOf("u"), i..i, nucleus = true)
                    i += 1
                }
                'e' -> {
                    if (i == start && wordText in jPronouns) b.add(listOf("j"), i..i)
                    b.add(listOf("e"), i..i, nucleus = true)
                    i += 1
                }
                else -> {
                    val phone = letter(c)
                    if (phone != null) b.add(phone.first, i..i, phone.second, nucleus = c in VOWELS)
                    i += 1
                }
            }
        }
    }

    private fun letter(c: Char): Pair<List<String>, TargetSound?>? = when (c) {
        'a' -> listOf("a") to null
        'ă' -> listOf("ə") to null
        'â', 'î' -> listOf("ɨ") to null
        'o' -> listOf("o") to null
        's' -> TargetSound.S.canonical to TargetSound.S
        'ș' -> TargetSound.Sh.canonical to TargetSound.Sh
        'z' -> TargetSound.Z.canonical to TargetSound.Z
        'j' -> TargetSound.J.canonical to TargetSound.J
        'ț' -> TargetSound.Ts.canonical to TargetSound.Ts
        'r' -> TargetSound.R.canonical to TargetSound.R
        'l' -> TargetSound.L.canonical to TargetSound.L
        'q', 'k' -> listOf("k") to null
        'w' -> listOf("v") to null
        'y' -> listOf("i") to null
        'b', 'd', 'f', 'h', 'm', 'n', 'p', 't', 'v' -> listOf(c.toString()) to null
        'é', 'è', 'ê' -> listOf("e") to null
        else -> null
    }
}
