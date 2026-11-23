package tech.fonia.speech

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.math.exp

// FoniaSpeechTests/Support.swift.

/** One `(word, start, end, prob)` of Swift's transcript spec; a null prob means 0.9. */
data class Said(val word: String, val start: Double, val end: Double, val prob: Double? = null)

/** Synthetic recogniser outputs: the judges only see numbers, so most tests need no model. */
fun transcript(spec: List<Said>, logp: Double = -1.0): Transcript {
    val words = spec.map { TranscriptWord(word = it.word, start = it.start, end = it.end, prob = it.prob ?: 0.9) }
    return Transcript(text = words.joinToString(" ") { it.word }, words = words, noSpeechProb = 0.1, logp = logp)
}

fun scored(text: String, logp: Double, perWord: List<Double> = emptyList()): TextScore {
    val words = text.split(" ").filter { it.isNotEmpty() }.mapIndexed { i, word ->
        val value = if (i < perWord.size) perWord[i] else -0.1
        WordScore(word = word, logp = value, prob = exp(value), minLogp = value, top = word, topLogp = -0.1, start = 0.5 + i.toDouble() * 0.3, end = 0.75 + i.toDouble() * 0.3, tokens = emptyList())
    }
    return TextScore(text = text, logp = logp, meanLogp = logp / (words.size + 1).toDouble(), eotLogp = -0.1, noSpeechProb = 0.1, tokens = emptyList(), words = words)
}

/** About 2.5 words per second; "||" stands for a long pause. */
fun talk(spec: String, start: Double = 0.5, step: Double = 0.4): Transcript {
    var t = start
    val words = mutableListOf<Said>()
    for (word in spec.split(" ").filter { it.isNotEmpty() }) {
        if (word == "||") {
            t += 1.5
            continue
        }
        words.add(Said(word, t, t + step * 0.8))
        t += step
    }
    return transcript(words)
}

/** The lists the web app passes today. */
object TestLexicon {
    val EN = FillerLexicon(
        hesitationLabel = "um",
        fillers = listOf("um", "uh", "er", "erm", "ah", "hmm", "you know", "i mean", "kind of", "sort of", "like", "basically", "actually", "literally", "so", "well", "okay"),
        ambiguous = listOf("like", "so", "well", "okay", "ok", "actually", "i mean", "kind of", "sort of", "you know", "right"),
    )
    val RO = FillerLexicon(
        hesitationLabel = "ăă",
        fillers = listOf("ăă", "ăăă", "îî", "îîî", "mmm", "deci", "practic", "adică", "gen", "de genul", "nu știu", "cum să zic", "cum să spun", "păi", "na", "mă rog", "în fine", "efectiv", "fix", "oarecum", "sincer", "știi", "înțelegi", "like", "basically", "literally"),
        ambiguous = listOf("deci", "practic", "adică", "gen", "nu știu", "fix", "na", "sincer", "efectiv", "oarecum", "știi", "înțelegi"),
    )
}

object SpeechPaths {
    /** The counterpart of iOS tools/eval_speech, passed in by speech/build.gradle.kts. */
    val evalFolder: File
        get() = File(System.getProperty("fonia.speech.eval") ?: "eval_speech")

    fun temporaryFolder(): File = Files.createTempDirectory("fonia-speech-").toFile()
}

/** Same near-silent noise as the web check. */
fun quietNoise(count: Int = 32_000): FloatArray {
    var state = 7u
    return FloatArray(count) {
        state = state * 1_664_525u + 1_013_904_223u
        (state.toFloat() / 4_294_967_296f - 0.5f) * 0.002f
    }
}

/**
 * Runs a suspending call to the end on this thread's terms: Swift tests simply `await`. The code
 * under test never needs a dispatcher, so `:speech` stays free of a coroutine library.
 */
fun <T> runSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    val done = CountDownLatch(1)
    block.startCoroutine(
        Continuation(EmptyCoroutineContext) {
            outcome = it
            done.countDown()
        },
    )
    check(done.await(60, TimeUnit.SECONDS)) { "timed out" }
    return outcome!!.getOrThrow()
}
