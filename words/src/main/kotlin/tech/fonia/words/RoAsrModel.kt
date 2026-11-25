package tech.fonia.words

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Anything that turns 16 kHz samples into words: the real model, or a stand-in in tests. */
fun interface WordRecognizer {
    /** Null when the model cannot run. */
    fun hear(samples16k: FloatArray): Heard?
}

/**
 * The Romanian word model (SpeD Parakeet-Ro 110M, CTC branch, int8) on ONNX Runtime. The Java API is the same on the
 * desktop JVM and on Android, so this class runs in the JVM tests and in the app. Not thread-safe: one host thread.
 *
 * Long recordings are cut at their quietest moment into pieces of at most [maxPieceSeconds], because the network's
 * attention grows with the square of the length.
 */
class RoAsrModel private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    val vocabulary: RoAsrVocabulary,
    private val maxPieceSeconds: Double,
) : WordRecognizer, AutoCloseable {
    private var features = FloatArray(0)

    var lastFeatureNanos = 0L
        private set
    var lastModelNanos = 0L
        private set

    override fun hear(samples16k: FloatArray): Heard {
        lastFeatureNanos = 0
        lastModelNanos = 0
        val pieces = Pieces.cut(samples16k, RoAsrFeatures.RATE, maxPieceSeconds)
        if (pieces.size == 1) return hearPiece(samples16k)
        val words = ArrayList<HeardWord>()
        var blank = 0.0
        var certainty = 0.0
        var certaintyWeight = 0.0
        var seconds = 0.0
        for (range in pieces) {
            val offset = range.first.toDouble() / RoAsrFeatures.RATE
            val heard = hearPiece(samples16k.copyOfRange(range.first, range.last + 1))
            for (w in heard.words) words += w.copy(start = w.start + offset, end = w.end + offset)
            blank += heard.blankShare * heard.seconds
            val voiced = (1 - heard.blankShare) * heard.seconds
            certainty += heard.confidence * voiced
            certaintyWeight += voiced
            seconds += heard.seconds
        }
        return Heard(words, if (seconds > 0) blank / seconds else 1.0, if (certaintyWeight > 0) certainty / certaintyWeight else 0.0, seconds)
    }

    private fun hearPiece(samples16k: FloatArray): Heard {
        val start = System.nanoTime()
        val frames = RoAsrFeatures.frameCount(samples16k.size)
        if (frames < MIN_FRAMES) return Heard.nothing
        val needed = frames * RoAsrFeatures.BANDS
        if (features.size < needed) features = FloatArray(needed)
        RoAsrFeatures.compute(samples16k, features)
        val afterFeatures = System.nanoTime()
        val output = run(features, frames)
        lastFeatureNanos += afterFeatures - start
        lastModelNanos += System.nanoTime() - afterFeatures
        return CtcWords.decode(output, vocabulary)
    }

    /** Runs the network on [frames] columns of features laid out band after band. */
    fun run(features: FloatArray, frames: Int): CtcOutput {
        val x = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(features, 0, frames * RoAsrFeatures.BANDS),
            longArrayOf(1, RoAsrFeatures.BANDS.toLong(), frames.toLong()),
        )
        try {
            val length = OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(frames.toLong())), longArrayOf(1))
            try {
                session.run(mapOf("audio_signal" to x, "length" to length)).use { result ->
                    val tensor = result.get(0) as OnnxTensor
                    val shape = tensor.info.shape
                    check(shape.size == 3 && shape[0] == 1L && shape[2] == vocabulary.size.toLong()) { "unexpected output shape ${shape.toList()}" }
                    val rows = shape[1].toInt()
                    val values = FloatArray(rows * vocabulary.size)
                    tensor.floatBuffer.get(values)
                    return CtcOutput(rows, vocabulary.size, values)
                }
            } finally {
                length.close()
            }
        } finally {
            x.close()
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        /** The subsampling needs a few columns; shorter clips hold no word anyway (0.25 s). */
        const val MIN_FRAMES = 25

        private fun options(threads: Int) = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }

        fun load(path: String, threads: Int, vocabulary: RoAsrVocabulary = RoAsrVocabulary.bundled(), maxPieceSeconds: Double = 20.0): RoAsrModel {
            val environment = OrtEnvironment.getEnvironment()
            return RoAsrModel(environment, environment.createSession(path, options(threads)), vocabulary, maxPieceSeconds)
        }

        /** From a mapped file (the APK's asset), without a copy. */
        fun load(model: ByteBuffer, threads: Int, vocabulary: RoAsrVocabulary = RoAsrVocabulary.bundled(), maxPieceSeconds: Double = 20.0): RoAsrModel {
            val environment = OrtEnvironment.getEnvironment()
            return RoAsrModel(environment, environment.createSession(model, options(threads)), vocabulary, maxPieceSeconds)
        }
    }
}

/** Cuts a long recording where it is quietest, so no word is split. */
internal object Pieces {
    fun cut(samples: FloatArray, rate: Int, maxSeconds: Double): List<IntRange> {
        val max = (maxSeconds * rate).toInt()
        if (samples.size <= max) return listOf(0 until samples.size)
        val out = ArrayList<IntRange>()
        val window = rate * 3 / 10
        var start = 0
        while (samples.size - start > max) {
            // The quietest 0.3 s in the last 40 % of the allowed length.
            val from = start + (max * 0.6).toInt()
            val to = start + max - window
            var best = from
            var bestEnergy = Double.MAX_VALUE
            var i = from
            while (i <= to) {
                var energy = 0.0
                for (k in i until i + window) energy += samples[k] * samples[k]
                if (energy < bestEnergy) {
                    bestEnergy = energy
                    best = i
                }
                i += window / 3
            }
            val cutAt = best + window / 2
            out += start until cutAt
            start = cutAt
        }
        out += start until samples.size
        return out
    }
}
