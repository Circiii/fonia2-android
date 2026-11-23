package tech.fonia.features.speaking

import tech.fonia.speech.SpeechLanguage
import tech.fonia.speech.SpeechText

// A text read aloud in the speaking exercises that are not on iOS (V2 and V6 in fonia-android-plan/03 §1.5): the
// emphasis sentences and the BAC tickets. Key words are marked in the content with asterisks, "Trenul pleacă *azi*.";
// paragraphs are separated by a line break.

/** One word as the reader sees it. */
data class TextWord(
    /** Position among the words of the whole text. */
    val index: Int,
    /** As printed, punctuation and quotes included: „Lasă, */
    val shown: String,
    /** The word marked to be stressed. */
    val isKey: Boolean,
    /** The punctuation right after the word, if any: where a reader breathes. */
    val mark: Char?,
    /** The last word of a paragraph. */
    val endsParagraph: Boolean,
) {
    /** A full stop, a question or an exclamation mark, or the end of a paragraph. */
    val endsSentence: Boolean get() = endsParagraph || (mark != null && mark in SENTENCE_ENDS)

    fun normalized(language: SpeechLanguage): String = SpeechText.normalizedWord(shown, language)

    /** The word without the punctuation around it: „Lasă, → Lasă. */
    val bare: String get() = SpeechText.trimmedToWord(shown)

    companion object {
        /** Marks a reader is expected to pause at. Semicolons never appear: the copy rules keep them out. */
        const val PAUSE_MARKS = ".,!?:…"
        const val SENTENCE_ENDS = ".!?…"
    }
}

/** A run of words that ends with a full stop, a question or an exclamation mark, or a paragraph. */
data class TextSentence(val first: Int, val last: Int, val text: String) {
    val indices: IntRange get() = first..last
}

data class MarkedText(
    /** The text as shown, asterisks removed. */
    val plain: String,
    val words: List<TextWord>,
) {
    val keyIndices: Set<Int> get() = words.filter { it.isKey }.map { it.index }.toSet()

    val paragraphs: List<List<TextWord>>
        get() {
            val out = mutableListOf<List<TextWord>>()
            var current = mutableListOf<TextWord>()
            for (word in words) {
                current.add(word)
                if (word.endsParagraph) {
                    out.add(current)
                    current = mutableListOf()
                }
            }
            if (current.isNotEmpty()) out.add(current)
            return out
        }

    /**
     * The text cut at the ends of sentences. Each one's [TextSentence.text] is its words joined by single spaces,
     * so the recogniser's own split of it (`SpeechText.rawWords`) gives back exactly these words, in this order.
     */
    val sentences: List<TextSentence>
        get() {
            val out = mutableListOf<TextSentence>()
            var start = 0
            for (word in words) {
                if (word.endsSentence || word.index == words.lastIndex) {
                    out.add(TextSentence(start, word.index, words.subList(start, word.index + 1).joinToString(" ") { it.shown }))
                    start = word.index + 1
                }
            }
            return out
        }

    /** The same text with other key words: the reader may mark their own while preparing. */
    fun withKeys(keys: Set<Int>): MarkedText = copy(words = words.map { it.copy(isKey = it.index in keys) })

    companion object {
        private val closers = setOf('”', '"', '»', '’', ')', '\'')

        /** "Trenul pleacă *azi*, nu mâine.": the words, with "azi" as the key word and a comma after it. */
        fun parse(marked: String): MarkedText {
            val paragraphs = marked.replace("\r\n", "\n").split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            val words = mutableListOf<TextWord>()
            for (paragraph in paragraphs) {
                // The recogniser's own split, so word i here is word i of anything it says about the same text.
                val tokens = SpeechText.rawWords(paragraph).filter { SpeechText.hasWordCharacter(it.replace("*", "")) }
                tokens.forEachIndexed { i, token ->
                    val shown = token.replace("*", "")
                    words.add(
                        TextWord(
                            index = words.size,
                            shown = shown,
                            isKey = token.count { it == '*' } >= 2,
                            mark = pauseMark(shown),
                            endsParagraph = i == tokens.lastIndex,
                        ),
                    )
                }
            }
            return MarkedText(plain = paragraphs.joinToString("\n") { it.replace("*", "") }, words = words)
        }

        /** The punctuation that ends a token, past any closing quote: „trece”, → ','. */
        internal fun pauseMark(token: String): Char? {
            var end = token.length
            while (end > 0 && token[end - 1] in closers) end -= 1
            if (end == 0) return null
            val last = token[end - 1]
            return if (last in TextWord.PAUSE_MARKS) last else null
        }
    }
}
