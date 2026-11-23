package tech.fonia.features.reading

import java.io.File
import java.util.Locale
import java.util.regex.Pattern

/**
 * An independent count of Romanian syllables, to check the hand-made splits in `reading.json`. It counts vowel groups
 * and knows the cases a first-grade list meets: a diphthong or triphthong is one nucleus (stea, ceai, miei); an i or u
 * between two vowels starts a new syllable (ba-ie, zi-ua, fe-me-ie); some vowel pairs are always two (a-le-e, po-e-zii);
 * a final i after a consonant is not a syllable (pomi, ochi, arici); and a final -ie or -ia after a consonant is two
 * (cu-ti-e). It can't tell verbs in -i (ci-ti) or hiatuses like gă-i-nă from the rest, so the lists avoid them.
 */
internal object RomanianSyllables {
    private const val VOWELS = "aăâeiîou"
    private val hiatus = listOf("aa", "ae", "ao", "ee", "oe", "oo", "uu")
    private val vowelRun = Regex("[aăâeiîou]+")

    /** [atWordEnd] false counts a piece from inside a word, where the rules for a word's last letters don't apply. */
    fun count(word: String, atWordEnd: Boolean = true): Int {
        val w = word.lowercase(Locale.ROOT).filter { it.isLetter() }
        val runs = vowelRun.findAll(w).toList()
        var total = 0
        runs.forEachIndexed { index, match ->
            val run = match.value
            val start = match.range.first
            val end = match.range.last + 1
            var nuclei = 1
            for (j in 1 until run.length - 1) if (run[j] == 'i' || run[j] == 'u') nuclei += 1
            for (pair in hiatus) nuclei += run.split(pair).size - 1
            if (atWordEnd && index == runs.lastIndex && end == w.length) {
                if (run == "i" && runs.size > 1 && start > 0) {
                    nuclei = 0
                } else if ((run == "ie" || run == "ia") && start > 0 && w[start - 1] !in VOWELS) {
                    nuclei = 2
                }
            }
            total += nuclei
        }
        return total
    }

    /** A piece of a split word is one syllable by the same rules: "car", "prie", "școli"; never "casa" glued together. */
    fun isOneSyllable(chunk: String, lastOfWord: Boolean): Boolean = count(chunk, atWordEnd = lastOfWord) == 1
}

/** The repository, found from the content submodule the JVM tests already get (`fonia.content`). */
internal val repositoryRoot: File
    get() = File(System.getProperty("fonia.content") ?: error("fonia.content is not set")).absoluteFile.parentFile
        ?: error("The content folder has no parent")

internal fun readingJSON(): String = File(repositoryRoot, "legacy/features/src/main/assets/${ReadingContent.ASSET}").readText(Charsets.UTF_8)

internal val readingContent: ReadingContent by lazy { ReadingContent.parse(readingJSON()) }

/**
 * The copy rules of `tools/lint_copy.py`, read from the script itself so the two never drift: its banned words with
 * the same word boundaries, plus the letters it refuses. `reading.json` sits outside `content/json`, which is all the
 * script reads, so this applies the script's JSON check to it.
 */
internal object CopyRules {
    const val CEDILLAS = "ŞşŢţ"
    const val EM_DASH = '—'

    /** Words the product rules keep out of anything a parent reads (docs/REGULI.md, 03 §2.1, §2.6). */
    val productWords = listOf("risc", "riscul", "severitate", "tulburare", "tulburări", "dislexie", "dislexic", "greșit", "greșeală")

    val banned: Pattern by lazy {
        val script = File(repositoryRoot, "tools/lint_copy.py").readText(Charsets.UTF_8)
        val list = script.substringAfter("BANNED_PATTERNS = [").substringBefore("\n]")
        val patterns = Regex("""r"((?:[^"\\]|\\.)*)"""").findAll(list).map { it.groupValues[1] }.toList()
        check(patterns.size > 50) { "Could not read BANNED_PATTERNS from tools/lint_copy.py" }
        Pattern.compile(
            "\\b(?:" + patterns.joinToString("|") + ")\\b",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE or Pattern.UNICODE_CHARACTER_CLASS,
        )
    }

    /** Every problem the script would report for [text], plus the product words. */
    fun problems(text: String): List<String> {
        val found = mutableListOf<String>()
        text.filter { it in CEDILLAS }.forEach { found += "cedilla $it" }
        if (EM_DASH in text) found += "em dash"
        val matcher = banned.matcher(text)
        while (matcher.find()) found += "banned \"${matcher.group()}\""
        val words = Regex("\\p{L}+").findAll(text.lowercase(Locale.ROOT)).map { it.value }.toSet()
        productWords.filter { it in words }.forEach { found += "product rule \"$it\"" }
        return found
    }
}
