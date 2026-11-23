package tech.fonia.features.speaking

import kotlinx.coroutines.CancellationException
import tech.fonia.dsp.TimeSpan
import tech.fonia.speech.SpeechLanguage
import tech.fonia.speech.SpeechResampler
import tech.fonia.speech.SpeechText
import tech.fonia.speech.SpeechThresholds
import tech.fonia.speech.Transcriber
import tech.fonia.speech.Transcript
import tech.fonia.speech.TranscriptWord
import tech.fonia.speech.WordScore

// A known text read aloud, checked word by word against what the recogniser heard (V6 reading, H F7: "acuratețea pe
// cuvinte, Whisper teacher forcing pe textul biletului"). The free transcript says where each sentence is; a sentence
// whose words all came back as written needs nothing more; any other is cut out of the take and scored against its own
// words with teacher forcing (`scoreSentence`). A word the recogniser wrote differently but whose forced score clears
// the iOS rescue bar (`SentenceThresholds.rescueLogp`) is a recogniser's slip ("Astați" for "Astăzi"): read, worth a
// listen. Below the bar it was read differently, even when only its ending changed, since at the exam a changed ending
// is a reading slip ("elevii" for "elevilor"). That is where this check parts from `judgeSentence`, which calls a near
// miss "weak". Everything runs on the phone.

/** What became of one word of the text. */
enum class ReadStatus {
    /** Heard as written. */
    READ,

    /** Heard, but the recogniser was unsure: worth listening to. */
    UNSURE,

    /** Read differently, or not found. */
    DIFFERENT,
}

data class WordRead(
    val index: Int,
    val status: ReadStatus,
    /** What the recogniser wrote in its place, when that differs. */
    val heard: String?,
    /** Where it is in the take; null when it was not found. */
    val span: TimeSpan?,
)

/** A word of the text and the transcript word lined up with it. */
data class HeardWord(val word: TranscriptWord, val exact: Boolean, val nearMiss: Boolean) {
    val span: TimeSpan get() = TimeSpan(word.start, word.end)
}

data class ReadAloudCheck(
    val words: List<WordRead>,
    val transcript: Transcript,
    /** Sentences that needed scoring against their own words, of all of them. */
    val scoredSentences: Int,
    val sentences: Int,
) {
    val readAsWritten: Int get() = words.count { it.status != ReadStatus.DIFFERENT }

    /** Words read as written over all words of the text. */
    val accuracy: Float get() = if (words.isEmpty()) 0f else readAsWritten.toFloat() / words.size.toFloat()

    /** Where each word read as written is, for the emphasis: a word read differently is not the word that was marked. */
    val spans: List<TimeSpan?> get() = words.map { if (it.status == ReadStatus.DIFFERENT) null else it.span }

    /**
     * Where the reader said something in each word's place, for the pauses: a word read differently still has its
     * place, a word not found has none.
     */
    val placed: List<TimeSpan?> get() = words.map { it.span }

    val toListen: List<WordRead> get() = words.filter { it.status != ReadStatus.READ }
}

object ReadAloud {
    /** The longest stretch one teacher-forced pass can score: Whisper's window, less a margin. */
    const val MAX_SLICE_SECONDS = 29.0

    /** Context kept around a sentence's stretch, seconds. */
    const val SLICE_MARGIN_SECONDS = 0.05

    /** Each word of the text and the transcript word aligned with it (the iOS `alignWords`, by spelling distance). */
    fun align(words: List<TextWord>, transcript: Transcript, language: SpeechLanguage): List<HeardWord?> {
        val target = words.map { it.normalized(language) }
        val heardWords = transcript.words.filter { SpeechText.normalizedWord(it.word, language).isNotEmpty() }
        val heard = heardWords.map { SpeechText.normalizedWord(it.word, language) }
        val out = arrayOfNulls<HeardWord>(words.size)
        for (pair in SpeechText.alignWords(target = target, heard = heard)) {
            val t = pair.target ?: continue
            val h = pair.heard ?: continue
            val exact = SpeechText.wordDistance(target[t], heard[h]) == 0.0
            out[t] = HeardWord(heardWords[h], exact = exact, nearMiss = !exact && SpeechText.nearMiss(target = target[t], heard = heard[h]))
        }
        return out.toList()
    }

    /**
     * Checks a reading of [text]. [samples16k] is the take at 16 kHz; [progress] gets (sentence, sentences) while the
     * sentences that need it are scored.
     */
    suspend fun check(
        text: MarkedText,
        samples16k: FloatArray,
        transcriber: Transcriber,
        language: SpeechLanguage,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): ReadAloudCheck {
        val transcript = transcriber.transcribe(samples16k, language, verbatim = false)
        val heard = align(text.words, transcript, language)
        val duration = samples16k.size.toDouble() / SpeechResampler.WHISPER_RATE
        val result = arrayOfNulls<WordRead>(text.words.size)
        val sentences = text.sentences
        var scored = 0
        for ((s, sentence) in sentences.withIndex()) {
            progress(s, sentences.size)
            if (sentence.indices.all { heard[it]?.exact == true }) {
                for (i in sentence.indices) result[i] = WordRead(i, ReadStatus.READ, null, heard[i]!!.span)
                continue
            }
            // The sentence lies between the last word found before it and the first one found after it.
            val before = (sentence.first - 1 downTo 0).firstOrNull { heard[it] != null }?.let { heard[it]!!.word.end } ?: 0.0
            val after = (sentence.last + 1 until text.words.size).firstOrNull { heard[it] != null }?.let { heard[it]!!.word.start } ?: duration
            val from = (before - SLICE_MARGIN_SECONDS).coerceAtLeast(0.0)
            val to = (after + SLICE_MARGIN_SECONDS).coerceAtMost(duration)
            val score = if (to - from in 0.3..MAX_SLICE_SECONDS && sentence.indices.any { heard[it] != null }) {
                val first = (from * SpeechResampler.WHISPER_RATE).toInt()
                val last = (to * SpeechResampler.WHISPER_RATE).toInt().coerceAtMost(samples16k.size)
                try {
                    transcriber.scoreSentence(samples16k.copyOfRange(first, last), sentence.text, language).also { scored += 1 }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A sentence too long for the decoder (`TextTooLong`) or a failed pass: the transcript alone decides.
                    null
                }
            } else {
                null
            }
            if (score == null) {
                for (i in sentence.indices) result[i] = fromTranscript(i, heard[i])
                continue
            }
            val words = text.words.subList(sentence.first, sentence.last + 1)
            val again = align(words, score.transcript, language)
            val forced = score.score.words
            for ((k, word) in words.withIndex()) {
                val forcedWord = if (forced.size == words.size) {
                    forced[k]
                } else {
                    forced.firstOrNull { SpeechText.normalizedWord(it.word, language) == word.normalized(language) }
                }
                result[word.index] = fromScore(word.index, again[k], forcedWord, from, heard[word.index])
            }
        }
        return ReadAloudCheck(
            words = result.mapIndexed { i, word -> word ?: WordRead(i, ReadStatus.DIFFERENT, null, null) },
            transcript = transcript,
            scoredSentences = scored,
            sentences = sentences.size,
        )
    }

    /** `SentenceThresholds.rescueLogp`: written differently, but clearly what the model expects. */
    private val RESCUE_LOGP = SpeechThresholds.STANDARD.sentence.rescueLogp

    /**
     * Word [index] from its sentence's second look: [slice] is the word in the slice's own transcript and [forced] its
     * teacher-forced score, both timed from the slice start at [offset] seconds; [whole] is the word in the first
     * transcript of the whole take.
     */
    private fun fromScore(index: Int, slice: HeardWord?, forced: WordScore?, offset: Double, whole: HeardWord?): WordRead {
        val status = when {
            slice?.exact == true || whole?.exact == true -> ReadStatus.READ
            forced != null && forced.logp >= RESCUE_LOGP -> ReadStatus.UNSURE
            else -> ReadStatus.DIFFERENT
        }
        val heardSpan = slice?.let { TimeSpan(offset + it.word.start, offset + it.word.end) } ?: whole?.span
        // Teacher forcing places every word of the text, said or not: its times count only for a word that was read.
        val span = if (status != ReadStatus.DIFFERENT && forced != null && forced.end > forced.start) {
            TimeSpan(offset + forced.start, offset + forced.end)
        } else {
            heardSpan
        }
        val heard = (slice ?: whole)?.takeIf { !it.exact }?.word?.word?.let(SpeechText::trimmedToWord)
        return WordRead(index, status, heard = if (status == ReadStatus.READ) null else heard, span = span)
    }

    /** Without a teacher-forced score (a stretch longer than Whisper's window): the transcript alone. */
    private fun fromTranscript(index: Int, heard: HeardWord?): WordRead = when {
        heard == null -> WordRead(index, ReadStatus.DIFFERENT, null, null)
        heard.exact -> WordRead(index, ReadStatus.READ, null, heard.span)
        else -> WordRead(index, ReadStatus.DIFFERENT, SpeechText.trimmedToWord(heard.word.word), heard.span)
    }
}

/** A boundary between two words that were both found: how long the reader stopped there. */
data class BoundaryPause(
    /** The word before the boundary. */
    val after: Int,
    val mark: Char?,
    val seconds: Double,
)

/**
 * Pauses where the text has punctuation, and pauses where it has none (V1 and V6, H F1: pauses of at least 250 ms;
 * at least 80 % of punctuation marks paused at; at most 2 pauses a minute elsewhere). Both are [S] starting values.
 */
data class PauseCheck(
    val boundaries: List<BoundaryPause>,
    val minPauseSeconds: Double,
    /** From the first word read to the last, minutes. */
    val minutes: Double,
) {
    private val marked: List<BoundaryPause> get() = boundaries.filter { it.mark != null }

    /** Punctuation marks with both neighbours found. */
    val marks: Int get() = marked.size
    val pausedMarks: Int get() = marked.count { it.seconds >= minPauseSeconds }
    val markShare: Float? get() = if (marks > 0) pausedMarks.toFloat() / marks.toFloat() else null

    /** Pauses between two words with no punctuation between them. */
    val otherPauses: Int get() = boundaries.count { it.mark == null && it.seconds >= minPauseSeconds }
    val otherPausesPerMinute: Double? get() = if (minutes > 0.1) otherPauses / minutes else null

    companion object {
        const val MIN_PAUSE_SECONDS = 0.25

        /** How far a DSP pause may reach past the two words it separates. */
        private const val EDGE_SECONDS = 0.15

        /**
         * [spans] are the words' places in the take (null when not found); [pauses] are the clip analysis's silences.
         * A boundary's pause is the longer of the gap between the two words and the longest silence that sits between them.
         */
        fun measure(words: List<TextWord>, spans: List<TimeSpan?>, pauses: List<TimeSpan>, minPauseSeconds: Double = MIN_PAUSE_SECONDS): PauseCheck {
            val boundaries = mutableListOf<BoundaryPause>()
            for (i in 0 until words.size - 1) {
                val a = spans[i] ?: continue
                val b = spans[i + 1] ?: continue
                val gap = (b.start - a.end).coerceAtLeast(0.0)
                val silence = pauses
                    .filter { it.start >= a.end - EDGE_SECONDS && it.end <= b.start + EDGE_SECONDS }
                    .maxOfOrNull { it.duration } ?: 0.0
                boundaries.add(BoundaryPause(i, words[i].mark, maxOf(gap, silence)))
            }
            val found = spans.filterNotNull()
            val minutes = if (found.isEmpty()) 0.0 else (found.last().end - found.first().start).coerceAtLeast(0.0) / 60
            return PauseCheck(boundaries, minPauseSeconds, minutes)
        }
    }
}
