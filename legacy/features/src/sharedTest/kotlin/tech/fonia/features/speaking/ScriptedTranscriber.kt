package tech.fonia.features.speaking

import tech.fonia.speech.InferenceCost
import tech.fonia.speech.PairScore
import tech.fonia.speech.SentenceScore
import tech.fonia.speech.SpeechLanguage
import tech.fonia.speech.SpeechResampler
import tech.fonia.speech.SpeechText
import tech.fonia.speech.TextScore
import tech.fonia.speech.Transcriber
import tech.fonia.speech.Transcript
import tech.fonia.speech.TranscriptWord
import tech.fonia.speech.WordScore
import kotlin.math.exp

/**
 * A recogniser that knows its takes: each one is registered with the words in it and their times (from
 * [SyntheticSpeech], or a real Whisper transcript of a recorded clip). It finds a clip, or a slice of one, by its
 * samples, so the code under test hands it exactly what it would hand Whisper.
 *
 * Teacher forcing is scripted per word: a word of the target text gets a log-probability of −0.05, or −3 when it is in
 * the take's [Take.readWrong]; its times come from the take's word lined up with it. Without a registered take, or for
 * a take registered with [Take.forcedScores] off, scoring throws, the way the real engine does when it can't run.
 */
class ScriptedTranscriber : Transcriber {
    class Take(
        val samples16k: FloatArray,
        val words: List<TranscriptWord>,
        /** Normalized words of the text that were really read differently. */
        val readWrong: Set<String> = emptySet(),
        val forcedScores: Boolean = true,
    )

    private val takes = mutableListOf<Take>()

    /** Every call, for the tests to look at: "transcribe" or "score: <target>". */
    val calls = mutableListOf<String>()

    fun add(spoken: SyntheticSpeech.Spoken, readWrong: Set<String> = emptySet()): Take =
        add(SpeechResampler.resample(spoken.samples, spoken.rate), spoken.words, readWrong)

    fun add(samples16k: FloatArray, words: List<TranscriptWord>, readWrong: Set<String> = emptySet(), forcedScores: Boolean = true): Take =
        Take(samples16k, words, readWrong, forcedScores).also { takes.add(it) }

    /** The take [samples] come from and where they start in it, seconds. */
    private fun locate(samples: FloatArray): Pair<Take, Double>? {
        val probe = minOf(48, samples.size)
        for (take in takes) {
            val whole = take.samples16k
            var offset = 0
            while (offset + samples.size <= whole.size) {
                var same = true
                for (k in 0 until probe) {
                    if (whole[offset + k] != samples[k]) {
                        same = false
                        break
                    }
                }
                if (same) return take to offset / SpeechResampler.WHISPER_RATE
                offset += 1
            }
        }
        return null
    }

    private fun transcript(words: List<TranscriptWord>): Transcript = Transcript(
        text = words.joinToString(" ") { it.word },
        words = words,
        noSpeechProb = if (words.isEmpty()) 0.95 else 0.02,
        logp = -0.2 * words.size,
        cost = InferenceCost.ZERO,
    )

    override suspend fun transcribe(samples16k: FloatArray, language: SpeechLanguage, verbatim: Boolean): Transcript {
        calls.add("transcribe")
        val (take, offset) = locate(samples16k) ?: return transcript(emptyList())
        val end = offset + samples16k.size / SpeechResampler.WHISPER_RATE
        return transcript(
            take.words.filter { it.start >= offset - 0.02 && it.end <= end + 0.02 }
                .map { it.copy(start = it.start - offset, end = it.end - offset) },
        )
    }

    override suspend fun scoreSentence(samples16k: FloatArray, target: String, language: SpeechLanguage): SentenceScore {
        calls.add("score: $target")
        val (take, offset) = locate(samples16k) ?: error("Not a registered take")
        check(take.forcedScores) { "No teacher forcing for this take" }
        val heard = transcribe(samples16k, language, verbatim = false)
        calls.removeAt(calls.lastIndex)
        val targetWords = SpeechText.rawWords(target).filter { SpeechText.normalizedWord(it, language).isNotEmpty() }
        val normalizedTarget = targetWords.map { SpeechText.normalizedWord(it, language) }
        val normalizedHeard = heard.words.map { SpeechText.normalizedWord(it.word, language) }
        val lined = arrayOfNulls<TranscriptWord>(targetWords.size)
        for (pair in SpeechText.alignWords(normalizedTarget, normalizedHeard)) {
            val t = pair.target ?: continue
            val h = pair.heard ?: continue
            lined[t] = heard.words[h]
        }
        val scores = targetWords.mapIndexed { k, word ->
            val logp = if (normalizedTarget[k] in take.readWrong) -3.0 else -0.05
            WordScore(
                word = word, logp = logp, prob = exp(logp), minLogp = logp, top = word, topLogp = logp,
                start = lined[k]?.start ?: 0.0, end = lined[k]?.end ?: 0.0, tokens = emptyList(),
            )
        }
        val total = scores.sumOf { it.logp }
        return SentenceScore(
            target = target,
            language = language,
            transcript = heard,
            score = TextScore(
                text = target, logp = total, meanLogp = total / scores.size.coerceAtLeast(1), eotLogp = -0.05,
                noSpeechProb = heard.noSpeechProb, tokens = emptyList(), words = scores,
            ),
        )
    }

    override suspend fun scorePair(samples16k: FloatArray, target: String, rival: String, language: SpeechLanguage): PairScore =
        throw UnsupportedOperationException("The speaking exercises never score pairs")
}
