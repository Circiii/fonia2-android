package tech.fonia.words

/** A filler word or phrase, or a word said twice, with when it was said. */
data class Marked(val text: String, val start: Double, val end: Double)

/**
 * What free speech looked like in words: how many, how fast, which filler words, which repeats, and the words with
 * their times for a transcript. [wordsPerMinute] counts the time from the first word to the last; in Romanian a good
 * pace for a talk is about 90–140 words a minute (3–5 syllables a second at about 2.1 syllables a word).
 */
data class SpeechText(
    val words: List<HeardWord>,
    val wordsPerMinute: Double,
    val speakingSeconds: Double,
    val fillers: List<Marked>,
    val repeats: List<Marked>,
    /** False when the recogniser was too unsure of the recording for these numbers to be shown. */
    val reliable: Boolean,
) {
    val text: String get() = words.joinToString(" ") { it.text }
    val fillersPerMinute: Double get() = if (speakingSeconds > 0) fillers.size * 60 / speakingSeconds else 0.0

    /** Lines of at most [maxWords] words, each starting at its first word's time, for the report. */
    fun lines(maxWords: Int = 8, gapSeconds: Double = 0.7): List<Marked> {
        val out = ArrayList<Marked>()
        var line = ArrayList<HeardWord>()
        fun flush() {
            if (line.isNotEmpty()) out += Marked(line.joinToString(" ") { it.text }, line.first().start, line.last().end)
            line = ArrayList()
        }
        for (w in words) {
            if (line.isNotEmpty() && (line.size >= maxWords || w.start - line.last().end > gapSeconds)) flush()
            line += w
        }
        flush()
        return out
    }

    companion object {
        fun of(heard: Heard, spec: WordsSpec = WordsSpec()): SpeechText {
            val words = heard.words
            if (words.isEmpty()) return SpeechText(emptyList(), 0.0, 0.0, emptyList(), emptyList(), reliable = false)
            val span = (words.last().end - words.first().start).coerceAtLeast(1.0)
            val plain = words.map { WordMatcher.words(it.text).joinToString("") }
            val fillerWords = spec.fillerWords.toSet()
            val fillers = ArrayList<Marked>()
            var i = 0
            while (i < words.size) {
                val phrase = spec.fillerPhrases.firstOrNull { p ->
                    val parts = p.split(' ')
                    i + parts.size <= words.size && parts.indices.all { plain[i + it] == parts[it] }
                }
                if (phrase != null) {
                    val length = phrase.split(' ').size
                    fillers += Marked(phrase, words[i].start, words[i + length - 1].end)
                    i += length
                    continue
                }
                if (plain[i] in fillerWords) fillers += Marked(plain[i], words[i].start, words[i].end)
                i++
            }
            val repeats = ArrayList<Marked>()
            for (k in 1 until words.size) {
                if (plain[k].length >= 2 && plain[k] == plain[k - 1] && words[k].start - words[k - 1].end <= spec.repeatWithinSeconds) {
                    repeats += Marked(plain[k], words[k - 1].start, words[k].end)
                }
            }
            return SpeechText(
                words = words,
                wordsPerMinute = words.size * 60 / span,
                speakingSeconds = span,
                fillers = fillers,
                repeats = repeats,
                reliable = heard.confidence >= spec.minConfidence,
            )
        }
    }
}

/**
 * Reading a known text aloud: words read per minute, and where the reader stumbled (words left out or not finished,
 * in the order of the text).
 */
data class ReadingCheck(
    val check: WordsCheck,
    val wordsReadPerMinute: Double,
    val stumbles: List<WordOutcome>,
    /** The last word of the text that was read: where the reader got to. */
    val reachedIndex: Int,
) {
    companion object {
        fun of(target: String, heard: Heard, spec: WordsSpec = WordsSpec(), peakDb: Double? = null): ReadingCheck {
            val check = WordMatcher.check(target, heard, spec, peakDb)
            val reached = check.words.indexOfLast { it.state == WordState.Said }
            val times = check.words.filter { it.state == WordState.Said && it.start != null }
            val span = if (times.size >= 2) (times.maxOf { it.end!! } - times.minOf { it.start!! }).coerceAtLeast(1.0) else heard.seconds.coerceAtLeast(1.0)
            // Only what lies before the place he reached is a stumble; the rest was simply not read yet.
            val stumbles = check.words.take(reached + 1).filter { it.state != WordState.Said }
            return ReadingCheck(check, check.said * 60 / span, stumbles, reached)
        }
    }
}
