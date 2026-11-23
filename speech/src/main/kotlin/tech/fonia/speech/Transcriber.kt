package tech.fonia.speech

import kotlin.math.exp

/**
 * The recogniser as features see it, so they and their tests never need the model.
 * Samples are mono 16 kHz; use [SpeechResampler] for anything else. Nothing here leaves the device.
 */
interface Transcriber {
    /** `verbatim` nudges the model to write fillers instead of cleaning them up. */
    suspend fun transcribe(samples16k: FloatArray, language: SpeechLanguage, verbatim: Boolean = false): Transcript

    /** Which of two candidates (word or whole sentence) the take sounds like. */
    suspend fun scorePair(samples16k: FloatArray, target: String, rival: String, language: SpeechLanguage): PairScore

    /** A transcript plus the teacher-forced score of the target sentence. */
    suspend fun scoreSentence(samples16k: FloatArray, target: String, language: SpeechLanguage): SentenceScore
}

/** Canned answers for previews and for screens that run while the model is absent. */
data class StubTranscriber(
    val transcript: Transcript = Transcript(text = "", words = emptyList(), noSpeechProb = 1.0, logp = 0.0),
    /** The target-minus-rival margin every pair gets. */
    val margin: Double = 3.0,
) : Transcriber {
    override suspend fun transcribe(samples16k: FloatArray, language: SpeechLanguage, verbatim: Boolean): Transcript = transcript

    override suspend fun scorePair(samples16k: FloatArray, target: String, rival: String, language: SpeechLanguage): PairScore {
        val variants = SpeechText.pairVariants(target = target, rival = rival)
            ?: return SpeechJudge.combinePair(target, rival, language, transcript, targetScores = emptyList(), rivalScores = emptyList())
        fun score(text: String, logp: Double) =
            TextScore(text = text, logp = logp, meanLogp = logp, eotLogp = 0.0, noSpeechProb = transcript.noSpeechProb, tokens = emptyList(), words = emptyList())
        return SpeechJudge.combinePair(
            target = target,
            rival = rival,
            language = language,
            transcript = transcript,
            targetScores = listOf(score(variants.target[0], 0.0)),
            rivalScores = listOf(score(variants.rival[0], -margin)),
        )
    }

    override suspend fun scoreSentence(samples16k: FloatArray, target: String, language: SpeechLanguage): SentenceScore {
        val words = SpeechText.rawWords(target).map {
            WordScore(word = it, logp = -0.1, prob = exp(-0.1), minLogp = -0.1, top = it, topLogp = -0.1, start = 0.0, end = 0.0, tokens = emptyList())
        }
        val forced = TextScore(
            text = target,
            logp = -0.1 * words.size.toDouble(),
            meanLogp = -0.1,
            eotLogp = -0.1,
            noSpeechProb = transcript.noSpeechProb,
            tokens = emptyList(),
            words = words,
        )
        return SentenceScore(target = target, language = language, transcript = transcript, score = forced)
    }
}
