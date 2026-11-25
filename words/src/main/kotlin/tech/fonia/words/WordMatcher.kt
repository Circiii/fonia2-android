package tech.fonia.words

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Thresholds of the word check and of the speech statistics; the data file is `spec/thresholds/words.json`. */
@Serializable
data class WordsSpec(
    val version: String = "words-1",
    /** A word counts as said when this share of its letters was heard in order (words of up to 3 letters: all). */
    val saidShare: Double = 0.75,
    /** Between this and [saidShare] the word was started but not finished ("aproape"). */
    val closeShare: Double = 0.5,
    /** Below this mean certainty the recogniser says nothing about the take. */
    val minConfidence: Double = 0.70,
    /** With no word and at least this share of empty frames, nothing was heard. */
    val nothingBlankShare: Double = 0.97,
    /** A take whose loudest moment is under this (dBFS) is too quiet to check. */
    val minPeakDb: Double = -45.0,
    /** The words were said: this share of the target words, and none missing among the first [mustHaveFirst]. */
    val allSaidCoverage: Double = 0.9,
    val mostSaidCoverage: Double = 0.6,
    /** Filler words counted in free speech (the sounds "ăăă", "mmm" are found by the sound detector, not here). */
    val fillerWords: List<String> = listOf("deci", "practic", "gen", "adică", "știi", "păi", "efectiv", "cumva", "na"),
    val fillerPhrases: List<String> = listOf("de fapt", "cum ar veni", "să zic", "să zicem", "ca să zic așa"),
    /** A word said twice in a row within this many seconds is a repeat. */
    val repeatWithinSeconds: Double = 1.5,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): WordsSpec = json.decodeFromString(serializer(), text)
    }
}

enum class WordState { Said, Close, Missing }

/** One word of the target text: was it said, what was heard in its place, and when. */
data class WordOutcome(val word: String, val state: WordState, val share: Double, val heardAs: String?, val start: Double?, val end: Double?)

/** Why the check says nothing about a take. */
enum class Abstain { Quiet, Nothing, Unsure }

/**
 * Whether the words of a target text were said. It never says how a sound was pronounced: letters the sound judge
 * looks at (s/ș, z/j, r/l, ț/t/s) and vowels with or without their marks count as the same letter here.
 */
data class WordsCheck(
    val words: List<WordOutcome>,
    /** Words heard that belong to no target word. */
    val extra: List<String>,
    val abstain: Abstain?,
    val confidence: Double,
    val heardText: String,
) {
    val said: Int get() = words.count { it.state == WordState.Said }
    val coverage: Double get() = if (words.isEmpty()) 0.0 else said.toDouble() / words.size
    val missing: List<WordOutcome> get() = words.filter { it.state == WordState.Missing }
    val close: List<WordOutcome> get() = words.filter { it.state == WordState.Close }

    fun allSaid(spec: WordsSpec = WordsSpec()): Boolean = abstain == null && coverage >= spec.allSaidCoverage
    fun mostSaid(spec: WordsSpec = WordsSpec()): Boolean = abstain == null && coverage >= spec.mostSaidCoverage

    /** `leul+ are+ un~ balon-`, for the local log. */
    fun trace(): String = words.joinToString(" ") {
        it.word + when (it.state) {
            WordState.Said -> "+"
            WordState.Close -> "~"
            WordState.Missing -> "-"
        }
    }
}

object WordMatcher {
    /** Lower case, comma-below letters, only letters and digits; hyphens and punctuation split words. */
    fun words(text: String): List<String> =
        text.lowercase()
            .replace('ş', 'ș').replace('ţ', 'ț')
            .split(Regex("[^a-zăâîșț0-9]+"))
            .filter { it.isNotEmpty() }

    /** The letter as the word check sees it: marks dropped, and the pairs the sound judge decides merged. */
    private fun fold(c: Char): Char = when (c) {
        'ă', 'â' -> 'a'
        'î' -> 'i'
        'ș' -> 's'
        'j' -> 'z'
        'ț' -> 't'
        'l' -> 'r'
        else -> c
    }

    /**
     * [peakDb] is the take's loudest moment in dBFS, when the caller knows it. With an [Abstain] reason every word is
     * reported Missing and must not be shown as such: the caller asks for another take instead.
     */
    fun check(target: String, heard: Heard, spec: WordsSpec = WordsSpec(), peakDb: Double? = null): WordsCheck {
        val targetWords = words(target)
        val heardWords = heard.words.flatMap { w -> words(w.text).map { HeardWord(it, w.start, w.end, w.confidence) } }
        val abstain = when {
            peakDb != null && peakDb < spec.minPeakDb -> Abstain.Quiet
            heardWords.isEmpty() && heard.blankShare >= spec.nothingBlankShare -> Abstain.Nothing
            heardWords.isEmpty() -> Abstain.Unsure
            heard.confidence < spec.minConfidence -> Abstain.Unsure
            else -> null
        }
        if (targetWords.isEmpty()) return WordsCheck(emptyList(), heardWords.map { it.text }, abstain, heard.confidence, heard.text)

        // Letters of both sides in one line each, remembering which word every letter belongs to.
        val ref = StringBuilder()
        val refWord = ArrayList<Int>()
        targetWords.forEachIndexed { i, w -> for (c in w) { ref.append(fold(c)); refWord += i } }
        val hyp = StringBuilder()
        val hypWord = ArrayList<Int>()
        heardWords.forEachIndexed { i, w -> for (c in w.text) { hyp.append(fold(c)); hypWord += i } }
        val n = ref.length
        val m = hyp.length
        val width = m + 1
        // Two tables: the best score of an alignment ending in a matched pair at (i, j), and the best one ending
        // otherwise. A match right after a match scores more, so letters are matched in runs (whole words) and a word
        // the reader never reached does not lend its letters to an earlier one.
        val low = Int.MIN_VALUE / 2
        val match = IntArray((n + 1) * width) { low }
        val other = IntArray((n + 1) * width)
        for (j in 0..m) other[j] = -j
        for (i in 1..n) {
            other[i * width] = -i
            for (j in 1..m) {
                val diagonal = (i - 1) * width + j - 1
                if (ref[i - 1] == hyp[j - 1]) match[i * width + j] = maxOf(match[diagonal] + 3, other[diagonal] + 2)
                val up = (i - 1) * width + j
                val left = i * width + j - 1
                other[i * width + j] = maxOf(
                    maxOf(match[up], other[up]) - 1,
                    maxOf(match[left], other[left]) - 1,
                    maxOf(match[diagonal], other[diagonal]) - 2,
                )
            }
        }
        val matched = IntArray(targetWords.size)
        val heardFor = Array(targetWords.size) { HashMap<Int, Int>() }
        val used = BooleanArray(heardWords.size)
        var i = n
        var j = m
        var inMatch = match[n * width + m] > other[n * width + m]
        while (i > 0 && j > 0) {
            val diagonal = (i - 1) * width + j - 1
            if (inMatch) {
                matched[refWord[i - 1]]++
                heardFor[refWord[i - 1]].merge(hypWord[j - 1], 1, Int::plus)
                inMatch = match[diagonal] + 3 >= other[diagonal] + 2 && match[diagonal] > low
                i--
                j--
            } else {
                val here = other[i * width + j]
                val up = (i - 1) * width + j
                val left = i * width + j - 1
                when {
                    here == match[up] - 1 -> { i--; inMatch = true }
                    here == other[up] - 1 -> i--
                    here == match[left] - 1 -> { j--; inMatch = true }
                    here == other[left] - 1 -> j--
                    here == match[diagonal] - 2 -> { i--; j--; inMatch = true }
                    else -> { i--; j-- }
                }
            }
        }
        val outcomes = targetWords.mapIndexed { index, word ->
            val share = matched[index].toDouble() / word.length
            val state = when {
                abstain != null -> WordState.Missing
                word.length <= 3 && matched[index] == word.length -> WordState.Said
                word.length > 3 && share >= spec.saidShare -> WordState.Said
                share >= spec.closeShare -> WordState.Close
                else -> WordState.Missing
            }
            val source = heardFor[index].maxByOrNull { it.value }?.key
            if (source != null && state != WordState.Missing) used[source] = true
            val h = source?.let { heardWords[it] }
            WordOutcome(word, state, share, h?.text, h?.start, h?.end)
        }
        // A short word glued to its neighbours ("spune-mi" heard as "spunem") is not held against the speaker.
        val settled = outcomes.mapIndexed { index, o ->
            val neighboursSaid = (index == 0 || outcomes[index - 1].state == WordState.Said) && (index == outcomes.lastIndex || outcomes[index + 1].state == WordState.Said)
            if (o.state == WordState.Close && o.word.length <= 3 && neighboursSaid && outcomes.size > 1) o.copy(state = WordState.Said) else o
        }
        val extra = heardWords.filterIndexed { index, _ -> !used[index] }.map { it.text }
        return WordsCheck(settled, extra, abstain, heard.confidence, heard.text)
    }
}

/**
 * One thing to do next, never a score, in at most eight words. Null when the words were all said (the sound judge's
 * own feedback then stands).
 */
object WordsAdvice {
    fun next(check: WordsCheck, spec: WordsSpec = WordsSpec()): String? = when (check.abstain) {
        Abstain.Quiet -> "Mai tare și mai aproape de telefon."
        Abstain.Nothing -> "Nu te-am auzit. Mai spune o dată."
        Abstain.Unsure -> "Mai spune o dată, mai rar."
        null -> {
            val missing = check.missing
            val close = check.close
            when {
                check.words.isEmpty() -> null
                missing.size == 1 && check.words.size > 1 -> "Ai sărit «${missing[0].word}». Mai spune o dată."
                missing.size > 1 && check.coverage < spec.mostSaidCoverage -> "Spune toată propoziția, rar."
                missing.size > 1 -> "Mai spune o dată, cu toate cuvintele."
                close.isNotEmpty() -> "Spune rar, până la capăt: «${close[0].word}»."
                missing.size == 1 -> "Mai spune o dată: «${missing[0].word}»."
                else -> null
            }
        }
    }
}
