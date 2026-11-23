package tech.fonia.app.data

import tech.fonia.live.Clip
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a take as 16-bit PCM mono WAV. The store names recording files `<id>.m4a`; the audio decoder sniffs the RIFF
 * header, so the WAV plays back through the audio director all the same (no encoder on the capture path).
 */
object Wav {
    fun bytes(clip: Clip): ByteArray {
        val n = clip.samples.size
        val rate = clip.sampleRate.toInt().coerceAtLeast(1)
        val b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + n * 2).put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray(Charsets.US_ASCII)).putInt(n * 2)
        for (x in clip.samples) b.putShort((x.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        return b.array()
    }

    fun write(file: File, clip: Clip) {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes(clip))
    }
}
