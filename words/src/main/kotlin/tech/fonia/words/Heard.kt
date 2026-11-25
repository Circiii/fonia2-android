package tech.fonia.words

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.exp

/** One word the recogniser wrote, with when it was said and how sure the model was of its weakest piece (0…1). */
data class HeardWord(val text: String, val start: Double, val end: Double, val confidence: Double)

/**
 * What the recogniser heard in one clip. [blankShare] is the share of frames with no speech sound at all;
 * [confidence] the mean certainty over the frames that carry a piece (0 when there is none).
 */
data class Heard(val words: List<HeardWord>, val blankShare: Double, val confidence: Double, val seconds: Double) {
    val text: String get() = words.joinToString(" ") { it.text }

    companion object {
        val nothing = Heard(emptyList(), 1.0, 0.0, 0.0)
    }
}

/** The model's pieces (SentencePiece, 1024 of them; `▁` starts a word) and its blank. */
class RoAsrVocabulary(val tokens: List<String>, val blank: Int, val frameSeconds: Double) {
    val size: Int get() = blank + 1

    companion object {
        const val RESOURCE = "/fonia/asr/tokens.json"

        fun parse(text: String): RoAsrVocabulary {
            val root = Json.parseToJsonElement(text).jsonObject
            return RoAsrVocabulary(
                tokens = root.getValue("tokens").jsonArray.map { it.jsonPrimitive.content },
                blank = root.getValue("blank").jsonPrimitive.int,
                frameSeconds = root.getValue("frameSeconds").jsonPrimitive.content.toDouble(),
            )
        }

        /** The list packed with this module. */
        fun bundled(): RoAsrVocabulary =
            parse(RoAsrVocabulary::class.java.getResourceAsStream(RESOURCE)!!.use { it.readBytes().toString(Charsets.UTF_8) })
    }
}

/** The network's output for one clip: `frames × vocabulary` log-probabilities, row after row. */
class CtcOutput(val frames: Int, val width: Int, val values: FloatArray)

/** Greedy CTC with times: the best piece per frame, repeats and blanks collapsed, pieces joined into words. */
object CtcWords {
    private const val WORD_START = '▁'

    fun decode(output: CtcOutput, vocabulary: RoAsrVocabulary): Heard {
        val frames = output.frames
        if (frames == 0) return Heard.nothing
        val width = output.width
        val blank = vocabulary.blank
        class Piece(val token: String, val first: Int, var last: Int, val certainty: Double)
        val pieces = ArrayList<Piece>()
        var previous = blank
        var blanks = 0
        var certaintySum = 0.0
        var certaintyCount = 0
        for (t in 0 until frames) {
            var best = 0
            var bestValue = Float.NEGATIVE_INFINITY
            val row = t * width
            for (v in 0 until width) {
                val value = output.values[row + v]
                if (value > bestValue) {
                    bestValue = value
                    best = v
                }
            }
            if (best == blank) {
                blanks++
            } else {
                val certainty = exp(bestValue.toDouble())
                certaintySum += certainty
                certaintyCount++
                if (best != previous) pieces += Piece(vocabulary.tokens[best], t, t, certainty) else pieces.last().last = t
            }
            previous = best
        }
        val words = ArrayList<HeardWord>()
        val text = StringBuilder()
        var first = 0
        var last = 0
        var weakest = 1.0
        fun flush() {
            if (text.isNotEmpty()) {
                words += HeardWord(text.toString(), first * vocabulary.frameSeconds, (last + 1) * vocabulary.frameSeconds, weakest)
            }
            text.setLength(0)
            weakest = 1.0
        }
        for (piece in pieces) {
            val startsWord = piece.token.startsWith(WORD_START)
            if (startsWord || text.isEmpty()) {
                if (startsWord) flush()
                if (text.isEmpty()) first = piece.first
            }
            text.append(piece.token.trimStart(WORD_START))
            last = piece.last
            if (piece.certainty < weakest) weakest = piece.certainty
        }
        flush()
        return Heard(
            words = words,
            blankShare = blanks.toDouble() / frames,
            confidence = if (certaintyCount == 0) 0.0 else certaintySum / certaintyCount,
            seconds = frames * vocabulary.frameSeconds,
        )
    }
}
