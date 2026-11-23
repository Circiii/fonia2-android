package tech.fonia.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// Port of FoniaAudioTests/Support.swift. Shared by the JVM tests and the instrumented tests.

object Fixtures {
    /** `Tests/FoniaAudioTests/Fixtures`, copied to `src/androidTest/assets/fixtures`. */
    val directory: File get() = FixturesLocator.directory

    val soareWav: File get() = File(directory, "soare.wav")
    val popMp3: File get() = File(directory, "pop.mp3")

    /** Facts of `soare.wav`, read with `afinfo` on iOS. */
    const val SOARE_FRAMES = 113_328
    const val SOARE_RATE = 48_000.0
}

/** A file path nobody else uses, removed when [body] returns. */
inline fun <T> withTemporaryFile(extension: String, body: (File) -> T): T {
    val file = File(System.getProperty("java.io.tmpdir"), "FoniaAudioTests-${UUID.randomUUID()}.$extension")
    try {
        return body(file)
    } finally {
        file.delete()
    }
}

/** A mono Float32 WAV, as iOS's `writeWav`. */
fun writeWav(samples: FloatArray, sampleRate: Double, file: File) {
    val rate = sampleRate.toInt()
    val buffer = ByteBuffer.allocate(44 + samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + samples.size * 4).put("WAVE".toByteArray(Charsets.US_ASCII))
    buffer.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(3).putShort(1).putInt(rate).putInt(rate * 4)
        .putShort(4).putShort(32)
    buffer.put("data".toByteArray(Charsets.US_ASCII)).putInt(samples.size * 4)
    for (sample in samples) buffer.putFloat(sample)
    RandomAccessFile(file, "rw").use { it.write(buffer.array()) }
}

fun sine(frequency: Double, amplitude: Float, seconds: Double, sampleRate: Double): FloatArray =
    FloatArray((seconds * sampleRate).toInt()) { index -> amplitude * sin(2 * PI * frequency * index.toDouble() / sampleRate).toFloat() }

/** Counts what it is given and publishes RMS, standing in for the real analyser. */
class RMSProcessor(private val tally: HopTally) : HopProcessor {
    override fun process(samples: FloatArray, offset: Int, count: Int, levels: LiveLevels) {
        var sum = 0f
        for (i in offset until offset + count) sum += samples[i] * samples[i]
        val rms = sqrt(sum / count)
        levels.publish(rms = rms)
        tally.record(hopSize = count, rms = rms)
    }
}

/** What the processors of a take saw, readable once the take has ended. */
class HopTally {
    private val lock = Any()
    private val rateList = mutableListOf<Double>()
    private val hopList = mutableListOf<Int>()
    private var loudest = 0f

    fun processorMade(rate: Double) = synchronized(lock) { rateList += rate }

    fun record(hopSize: Int, rms: Float) = synchronized(lock) {
        hopList += hopSize
        loudest = max(loudest, rms)
    }

    val rates: List<Double> get() = synchronized(lock) { rateList.toList() }
    val hopSizes: List<Int> get() = synchronized(lock) { hopList.toList() }
    val totalSamples: Int get() = synchronized(lock) { hopList.sum() }
    val loudestRMS: Float get() = synchronized(lock) { loudest }
}
