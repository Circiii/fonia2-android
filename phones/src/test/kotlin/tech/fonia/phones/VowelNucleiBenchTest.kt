package tech.fonia.phones

import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Vowels counted by the phone model on the real-video bench (opt-in, `-Pfonia.coach.bench=<folder>` with 16 kHz
 * `tracks/<name>.wav`): writes `out/<name>.vowels.txt`, the time in seconds of every vowel, for the comparison with
 * the transcripts.
 */
class VowelNucleiBenchTest {
    @Test
    fun realVideos() {
        val dir = File(System.getProperty("fonia.coach.bench") ?: return)
        val wavs = File(dir, "tracks").listFiles { f -> f.extension == "wav" }?.sortedBy { it.name } ?: return
        val model = ZipaModel.load(ModelCache.fetch(ZipaModel.INT8).getOrElse { return })
        val out = File(dir, "out").apply { mkdirs() }
        for (wav in wavs) {
            val pcm = read16k(wav)
            val window = 16000 * 4
            val times = ArrayList<Double>()
            val onsets = IntArray(400)
            var at = 0
            val started = System.nanoTime()
            while (at < pcm.size) {
                val n = minOf(window, pcm.size - at)
                if (n < 1600) break
                val p = model.posteriors(pcm.copyOfRange(at, at + n))
                val secondsPerFrame = n / 16000.0 / p.frames
                val found = VowelNuclei.onsets(p, onsets)
                for (i in 0 until found) times += at / 16000.0 + onsets[i] * secondsPerFrame
                at += n
            }
            val ms = (System.nanoTime() - started) / 1e6
            File(out, wav.nameWithoutExtension + ".vowels.txt").writeText(times.joinToString("\n") { "%.3f".format(it) })
            println("${wav.nameWithoutExtension}: vowels=${times.size} in ${"%.0f".format(ms)} ms for ${"%.0f".format(pcm.size / 16000.0)} s")
        }
        model.close()
    }

    private fun read16k(file: File): FloatArray {
        val bytes = file.readBytes()
        var i = 12
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4)
            val size = ByteBuffer.wrap(bytes, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                val b = ByteBuffer.wrap(bytes, i + 8, minOf(size, bytes.size - i - 8)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                return FloatArray(b.remaining()) { b.get(it) / 32768f }
            }
            i += 8 + size + (size and 1)
        }
        return FloatArray(0)
    }
}
