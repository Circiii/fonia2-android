package tech.fonia.app

import tech.fonia.live.Clip
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads the fixture WAVs of `spec/fixtures/audio` (16-bit PCM mono, written by make_fixtures.py) as clips. */
object WavFixtures {
    private val root = File(System.getProperty("fonia.root") ?: "..")

    fun clip(name: String): Clip {
        val bytes = File(root, "spec/fixtures/audio/$name.wav").readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var rate = 0
        var channels = 1
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = b.getInt(pos + 4)
            if (id == "fmt ") {
                channels = b.getShort(pos + 10).toInt()
                rate = b.getInt(pos + 12)
            } else if (id == "data") {
                val n = size / 2 / channels
                val out = FloatArray(n) { i -> b.getShort(pos + 8 + i * 2 * channels) / 32768f }
                return Clip(out, rate.toDouble(), audioSource = "fixture")
            }
            pos += 8 + size + (size and 1)
        }
        error("no data chunk in $name")
    }
}
