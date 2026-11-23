package tech.fonia.features.speaking

import android.content.Context
import tech.fonia.audio.CapturedClip
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug builds with `foniaFixtures`: the talk's take becomes `assets/speaking/fillers.wav`, a 15 s synthetic Romanian
 * voice full of „ăăă” and „deci” (ElevenLabs „Sarah”, HSIC/tmp/test_audio), so „Ascultă-te fără ăăă” can be checked
 * on an emulator. It proves the chain runs, not that it is accurate on real speakers.
 */
internal object SpeakingFixture {
    fun take(context: Context): CapturedClip? = runCatching {
        val bytes = context.assets.open("speaking/fillers.wav").use { it.readBytes() }
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
                val n = minOf(size, bytes.size - pos - 8) / (2 * channels)
                val samples = FloatArray(n) { i -> b.getShort(pos + 8 + i * 2 * channels) / 32768f }
                return@runCatching CapturedClip(samples, rate.toDouble())
            }
            pos += 8 + size + (size and 1)
        }
        null
    }.getOrNull()
}
