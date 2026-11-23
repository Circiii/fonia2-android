package tech.fonia.phones

/**
 * The 127 output symbols of ZIPA-small CR-CTC (`tokens.txt` of `anyspeech/zipa-small-crctc-500k`, SHA-256
 * f8e042a0…b2b8fe, identical to `ipa_simplified/tokens.txt` in `lingjzhu/zipa`). Index = row of the model's
 * `log_probs` output.
 *
 * Things that matter for scoring:
 * - `<blk>` (0) is the CTC blank.
 * - `▁` (3) is the word-start mark the training transcripts carried. The model emits it at the start of every
 *   utterance (and of every word), so a canonical sequence must begin with it: without it the first frames have no
 *   label to go to and the forward pass pushes probability onto whatever phone sits nearest, which made word-initial
 *   fricatives look like affricates in the first experiments.
 * - One token per IPA character: there are no affricate symbols and no tie bar, so Ț, CE/CI and GE/GI are the
 *   two-token sequences `t s`, `t ʃ`, `d ʒ`. Diacritics (`ʲ`, `ː`, `̃`…) are separate tokens.
 */
object ZipaInventory {
    const val BLANK = 0
    const val WORD_START = 3

    val tokens: List<String> = listOf(
        "<blk>", "<sos/eos>", "<unk>", "▁", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o",
        "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z", "æ", "ç", "ð", "ø", "ħ", "ŋ", "œ", "ǀ", "ǁ", "ǂ", "ǃ",
        "ɐ", "ɑ", "ɒ", "ɓ", "ɔ", "ɕ", "ɖ", "ɗ", "ɘ", "ə", "ɛ", "ɜ", "ɞ", "ɟ", "ɠ", "ɢ", "ɣ", "ɤ", "ɥ", "ɦ", "ɧ", "ɨ",
        "ɪ", "ɬ", "ɭ", "ɮ", "ɯ", "ɰ", "ɱ", "ɲ", "ɳ", "ɴ", "ɵ", "ɶ", "ɸ", "ɹ", "ɺ", "ɻ", "ɽ", "ɾ", "ʀ", "ʁ", "ʂ", "ʃ",
        "ʄ", "ʈ", "ʉ", "ʊ", "ʋ", "ʌ", "ʍ", "ʎ", "ʏ", "ʐ", "ʑ", "ʒ", "ʔ", "ʕ", "ʘ", "ʙ", "ʛ", "ʜ", "ʝ", "ʟ", "ʡ", "ʢ",
        "ʰ", "ʲ", "ʷ", "ʼ", "ː", "˞", "ˠ", "ˤ", "̃", "̚", "̥", "̩", "̪", "̴", "̺",
        "β", "θ", "χ", "ᶑ", "ⱱ",
    )

    const val SIZE = 127

    private val ids: Map<String, Int> = tokens.withIndex().associate { (index, token) -> token to index }

    init {
        check(tokens.size == SIZE) { "ZIPA has $SIZE tokens, the table holds ${tokens.size}" }
    }

    /** Index of [token]; throws for a symbol the model cannot emit, so a G2P typo fails loudly. */
    fun id(token: String): Int = ids[token] ?: throw IllegalArgumentException("'$token' is not a ZIPA token")

    fun contains(token: String): Boolean = token in ids

    /**
     * Parses `tokens.txt` ("symbol index" per line), for checking a downloaded file against this table. The
     * combining marks are lines that start with a space-less mark, so the line is split on its last space.
     */
    fun parseTokensFile(text: String): List<String> =
        text.lineSequence().filter { it.isNotBlank() }.map { line ->
            val cut = line.trimEnd().lastIndexOf(' ')
            require(cut > 0) { "bad tokens.txt line: '$line'" }
            line.substring(0, cut) to line.substring(cut + 1).trim().toInt()
        }.sortedBy { it.second }.map { it.first }.toList()
}
