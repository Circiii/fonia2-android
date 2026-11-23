package tech.fonia.speech

import kotlin.math.abs

data class WordAlignment(
    /** Index into the target words; null for an extra word. */
    val target: Int?,
    /** Index into the heard words; null for a dropped word. */
    val heard: Int?,
)

data class PairVariants(val target: List<String>, val rival: List<String>)

/**
 * FoniaSpeech/SpeechText.swift. Lengths, prefixes and distances count Characters (grapheme
 * clusters) and compare them by canonical equivalence, as Swift does.
 */
object SpeechText {
    private val englishNumbers = mapOf(
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10", "eleven" to "11",
        "twelve" to "12", "twenty" to "20", "hundred" to "100",
    )
    private val romanianNumbers = mapOf(
        "zero" to "0", "unu" to "1", "una" to "1", "doi" to "2", "două" to "2", "trei" to "3",
        "patru" to "4", "cinci" to "5", "șase" to "6", "șapte" to "7", "opt" to "8", "nouă" to "9",
        "zece" to "10", "douăzeci" to "20", "sută" to "100",
    )

    // s and t with cedilla (U+015F, U+0163) become s and t with comma below (U+0219, U+021B); U+2019 and ` become '.
    private val wordReplacements = mapOf("\u015F" to "\u0219", "\u0163" to "\u021B", "\u2019" to "'", "`" to "'")
    private val dashReplacements = mapOf("\u2013" to " ", "\u2014" to " ")
    private val hesitation = Regex("u+h*m+|u+h+|e+r+m*|a+h+|h+m+|m{2,}|ă+m*|î+m*|â+m*|ă+h*")

    /** Lower case, cedilla diacritics to comma ones, edge punctuation stripped (inner ' and - kept), numbers as digits. */
    fun normalizedWord(word: String, language: SpeechLanguage = SpeechLanguage.EN): String {
        val cleaned = trimmedToWord(
            SwiftString.replacingCharacters(SwiftString.lowercased(SwiftString.nfc(word)), wordReplacements),
        )
        val numbers = if (language == SpeechLanguage.RO) romanianNumbers else englishNumbers
        return SwiftString.lookup(numbers, cleaned) ?: cleaned
    }

    fun trimmedToWord(word: String): String {
        var first = -1
        var last = -1
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            val n = Character.charCount(cp)
            if (isWordScalar(cp)) {
                if (first < 0) first = i
                last = i + n
            }
            i += n
        }
        return if (first < 0) "" else word.substring(first, last)
    }

    fun hasWordCharacter(text: String): Boolean = text.codePoints().anyMatch(::isWordScalar)

    private fun isWordScalar(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.UPPERCASE_LETTER.toInt(),
        Character.LOWERCASE_LETTER.toInt(),
        Character.TITLECASE_LETTER.toInt(),
        Character.MODIFIER_LETTER.toInt(),
        Character.OTHER_LETTER.toInt(),
        Character.DECIMAL_DIGIT_NUMBER.toInt(),
        Character.LETTER_NUMBER.toInt(),
        Character.OTHER_NUMBER.toInt(),
        -> true
        else -> false
    }

    fun rawWords(text: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        // split(whereSeparator: \.isWhitespace) goes Character by Character and drops empty pieces.
        for (character in SwiftString.characters(SwiftString.replacingCharacters(text, dashReplacements))) {
            if (SwiftString.isWhitespace(character)) {
                if (current.isNotEmpty()) {
                    out.add(current.toString())
                    current.setLength(0)
                }
            } else {
                current.append(character)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    fun words(text: String, language: SpeechLanguage = SpeechLanguage.EN): List<String> =
        rawWords(text).map { normalizedWord(it, language) }.filter { it.isNotEmpty() }

    /** Levenshtein distance divided by the longer length (0 same, 1 different). */
    fun wordDistance(a: String, b: String): Double {
        if (SwiftString.equal(a, b)) return 0.0
        val x = SwiftString.characters(a).map(SwiftString::nfc)
        val y = SwiftString.characters(b).map(SwiftString::nfc)
        if (x.isEmpty() || y.isEmpty()) return 1.0
        var previous = IntArray(y.size + 1) { it }
        for (i in 1..x.size) {
            val current = IntArray(y.size + 1)
            current[0] = i
            for (j in 1..y.size) {
                current[j] = minOf(minOf(previous[j] + 1, current[j - 1] + 1), previous[j - 1] + (if (x[i - 1] == y[j - 1]) 0 else 1))
            }
            previous = current
        }
        return previous[y.size].toDouble() / maxOf(x.size, y.size).toDouble()
    }

    /** Substitutions cost the normalised spelling distance. */
    fun alignWords(target: List<String>, heard: List<String>): List<WordAlignment> {
        val n = target.size
        val m = heard.size
        val d = Array(n + 1) { DoubleArray(m + 1) }
        for (j in 0..m) d[0][j] = j.toDouble()
        for (i in 0..n) d[i][0] = i.toDouble()
        if (n > 0 && m > 0) {
            for (i in 1..n) {
                for (j in 1..m) {
                    d[i][j] = SwiftMath.min(SwiftMath.min(d[i - 1][j - 1] + wordDistance(target[i - 1], heard[j - 1]), d[i - 1][j] + 1), d[i][j - 1] + 1)
                }
            }
        }
        val out = mutableListOf<WordAlignment>()
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && abs(d[i][j] - (d[i - 1][j - 1] + wordDistance(target[i - 1], heard[j - 1]))) < 1e-9) {
                out.add(WordAlignment(target = i - 1, heard = j - 1))
                i -= 1
                j -= 1
            } else if (i > 0 && abs(d[i][j] - (d[i - 1][j] + 1)) < 1e-9) {
                out.add(WordAlignment(target = i - 1, heard = null))
                i -= 1
            } else {
                out.add(WordAlignment(target = null, heard = j - 1))
                j -= 1
            }
        }
        return out.reversed()
    }

    /**
     * Same word with a different ending, or a tiny spelling slip. A changed first sound is never
     * a near miss: "sings" for "things" is exactly the TH error.
     */
    fun nearMiss(target: String, heard: String): Boolean {
        val t = SwiftString.characters(target)
        val h = SwiftString.characters(heard)
        val (shorter, longer) = if (t.size <= h.size) t to h else h to t
        if (shorter.size >= 3 && SwiftString.hasPrefix(longer, shorter) && longer.size - shorter.size <= 3) return true
        return SwiftString.equal(t.take(2).joinToString(""), h.take(2).joinToString("")) && wordDistance(target, heard) <= 0.25
    }

    /**
     * The model writes a lone word as "Sheep." as often as "sheep", so each candidate is scored
     * over its usual spellings. Null when both are the same text (stress pairs need DSP).
     */
    fun pairVariants(target: String, rival: String): PairVariants? {
        val t = SwiftString.trimmingWhitespacesAndNewlines(target)
        val r = SwiftString.trimmingWhitespacesAndNewlines(rival)
        if (t.isEmpty() || r.isEmpty() || SwiftString.equal(words(t), words(r))) return null
        return PairVariants(target = spellings(t), rival = spellings(r))
    }

    private fun spellings(text: String): List<String> {
        val characters = SwiftString.characters(text)
        if (characters.any(SwiftString::isWhitespace)) return listOf(text)
        val low = SwiftString.characters(SwiftString.lowercased(text))
        val capitalized = SwiftString.uppercased(low.take(1).joinToString("")) + low.drop(1).joinToString("")
        return listOf(low.joinToString(""), capitalized, "$capitalized.")
    }

    fun isHesitationSound(normalized: String): Boolean =
        // Swift's regex matches Characters by canonical equivalence; on NFC text plain matching is the same.
        hesitation.matches(SwiftString.nfc(normalized))
}
