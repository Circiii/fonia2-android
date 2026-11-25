package tech.fonia.phones

/**
 * Syllable nuclei from the phone recogniser: every vowel the model hears is one syllable. This is what the speech
 * coach counts for the speaking rate: it follows fast and reduced speech, where an intensity-peak counter merges
 * neighbouring syllables. Greedy CTC over the frames (repeats merged, blanks dropped), the times in model frames.
 */
object VowelNuclei {
    /** First letters of the vowel tokens of the inventory (IPA). Length marks, nasal and tone marks follow them. */
    private const val VOWELS = "aeiouyæøœɐɑɒɔəɘɛɜɞɤɨɪɯɵɶʉʊʌʏ"

    private val isVowel: BooleanArray = BooleanArray(ZipaInventory.tokens.size) { id ->
        val token = ZipaInventory.tokens[id]
        token.isNotEmpty() && token[0] in VOWELS
    }

    fun isVowel(token: Int): Boolean = token in isVowel.indices && isVowel[token]

    /**
     * The frames (0 until [Posteriors.frames]) where a vowel begins, appended to [out]; returns how many. A vowel
     * that is still the best token when the next window starts is counted once by the caller (overlap handling).
     */
    fun onsets(posteriors: Posteriors, out: IntArray): Int {
        var n = 0
        var previous = -1
        for (t in 0 until posteriors.frames) {
            val best = posteriors.argmax(t)
            if (best != previous && best != ZipaInventory.BLANK && isVowel(best) && n < out.size) out[n++] = t
            previous = best
        }
        return n
    }

    fun count(posteriors: Posteriors): Int = onsets(posteriors, IntArray(posteriors.frames.coerceAtLeast(1)))
}
