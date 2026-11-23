package tech.fonia.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class DecodedAudio(val samples: FloatArray, val sampleRate: Double)

object AudioDecoder {
    private const val TIMEOUT_US = 10_000L

    /**
     * Reads a WAV or MP3 file to the end and averages its channels to mono, as iOS's `AVAudioFile` does:
     * integer PCM is scaled to -1...1 (16-bit by 1/32768) and channels are summed then scaled by 1/channels.
     * WAV is parsed in Kotlin, so the same code also runs in JVM unit tests; anything else goes through
     * `MediaExtractor` + `MediaCodec`.
     */
    suspend fun decodeMono(url: ClipLocation): DecodedAudio = withContext(Dispatchers.IO) { decodeMonoBlocking(url) }

    internal fun decodeMonoBlocking(url: ClipLocation): DecodedAudio {
        val head = url.open().use { stream ->
            val bytes = ByteArray(12)
            var read = 0
            while (read < bytes.size) {
                val n = stream.read(bytes, read, bytes.size - read)
                if (n < 0) break
                read += n
            }
            bytes.copyOf(read)
        }
        return if (WavReader.isWav(head)) {
            WavReader.decodeMono(url.open().use { it.readBytes() })
        } else {
            decodeWithMediaCodec(url)
        }
    }

    private fun decodeWithMediaCodec(url: ClipLocation): DecodedAudio {
        val extractor = MediaExtractor()
        try {
            when (url) {
                is ClipLocation.File -> extractor.setDataSource(url.file.path)
                is ClipLocation.Asset -> url.assets.openFd(url.path).use { extractor.setDataSource(it) }
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track in ${url.name}")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val isAac = mime == MediaFormat.MIMETYPE_AUDIO_AAC
            if (isAac) {
                // Trimming happens once, below, from the file's edit list; the decoder must not do it as well.
                format.removeKey(KEY_ENCODER_DELAY)
                format.removeKey(KEY_ENCODER_PADDING)
            }
            val codec = MediaCodec.createDecoderByType(mime)
            val decoded = try {
                decode(extractor, codec, format)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            return if (isAac) trimmed(decoded, url) else decoded
        } finally {
            extractor.release()
        }
    }

    private const val KEY_ENCODER_DELAY = "encoder-delay"
    private const val KEY_ENCODER_PADDING = "encoder-padding"

    /**
     * An M4A's priming and padding out, as Apple's reader does, when the file's edit list says how much
     * ([Mp4Gapless]). Android's AAC decoder hands both over as audio.
     */
    private fun trimmed(audio: DecodedAudio, url: ClipLocation): DecodedAudio {
        val edit = Mp4Gapless.read(url.open().use { it.readBytes() }) ?: return audio
        val rate = audio.sampleRate.roundToInt()
        val start = edit.delayFrames(rate)
        val kept = edit.keptFrames(rate)
        if (start < 0 || kept <= 0 || start + kept > audio.samples.size) return audio
        return DecodedAudio(audio.samples.copyOfRange(start, start + kept), audio.sampleRate)
    }

    private fun decode(extractor: MediaExtractor, codec: MediaCodec, format: MediaFormat): DecodedAudio {
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        val out = FloatBuilder()
        codec.configure(format, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val changed = codec.outputFormat
                sampleRate = changed.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                encoding = if (changed.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    changed.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                }
            } else if (index >= 0) {
                if (info.size > 0) {
                    val buffer = codec.getOutputBuffer(index)!!
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    appendMono(buffer.slice().order(ByteOrder.nativeOrder()), channels, encoding, out)
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
        }
        return DecodedAudio(out.toArray(), sampleRate.toDouble())
    }

    private fun appendMono(pcm: ByteBuffer, channels: Int, encoding: Int, out: FloatBuilder) {
        val scale = 1f / channels
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = pcm.asFloatBuffer()
                val frames = floats.remaining() / channels
                for (frame in 0 until frames) {
                    if (channels == 1) {
                        out.add(floats.get(frame))
                    } else {
                        var sum = 0f
                        for (channel in 0 until channels) sum += floats.get(frame * channels + channel)
                        out.add(sum * scale)
                    }
                }
            }
            else -> {
                val shorts = pcm.asShortBuffer()
                val frames = shorts.remaining() / channels
                for (frame in 0 until frames) {
                    if (channels == 1) {
                        out.add(shorts.get(frame) / 32768f)
                    } else {
                        var sum = 0f
                        for (channel in 0 until channels) sum += shorts.get(frame * channels + channel) / 32768f
                        out.add(sum * scale)
                    }
                }
            }
        }
    }
}

/** A growable float array, so decoding does not box every sample. */
internal class FloatBuilder(initialCapacity: Int = 1 shl 16) {
    private var data = FloatArray(max(1, initialCapacity))
    var size = 0
        private set

    fun add(value: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    fun toArray(): FloatArray = data.copyOf(size)
}

/** RIFF/WAVE reader for the PCM layouts `AVAudioFile` reads: 8/16/24/32-bit integers, 32/64-bit floats. */
internal object WavReader {
    private const val FORMAT_PCM = 1
    private const val FORMAT_FLOAT = 3
    private const val FORMAT_EXTENSIBLE = 0xFFFE

    fun isWav(head: ByteArray): Boolean = head.size >= 12 &&
        String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WAVE"

    fun decodeMono(bytes: ByteArray): DecodedAudio {
        if (!isWav(bytes)) throw IOException("Not a WAV file")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var format = -1
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var blockAlign = 0
        var dataOffset = -1
        var dataSize = 0L

        var position = 12
        while (position + 8 <= bytes.size) {
            val id = String(bytes, position, 4, Charsets.US_ASCII)
            val size = buffer.getInt(position + 4).toLong() and 0xFFFF_FFFFL
            val body = position + 8
            when (id) {
                "fmt " -> {
                    format = buffer.getShort(body).toInt() and 0xFFFF
                    channels = buffer.getShort(body + 2).toInt() and 0xFFFF
                    sampleRate = buffer.getInt(body + 4)
                    blockAlign = buffer.getShort(body + 12).toInt() and 0xFFFF
                    bits = buffer.getShort(body + 14).toInt() and 0xFFFF
                    if (format == FORMAT_EXTENSIBLE && size >= 40) format = buffer.getShort(body + 24).toInt() and 0xFFFF
                }
                "data" -> {
                    dataOffset = body
                    dataSize = min(size, (bytes.size - body).toLong())
                }
            }
            if (dataOffset >= 0 && format >= 0) break
            position = (body + size + (size and 1)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        if (format < 0 || dataOffset < 0 || channels <= 0 || sampleRate <= 0 || blockAlign <= 0) {
            throw IOException("Malformed WAV file")
        }
        val bytesPerSample = bits / 8
        val read: (Int) -> Float = when {
            format == FORMAT_PCM && bits == 8 -> { offset -> ((bytes[offset].toInt() and 0xFF) - 128) / 128f }
            format == FORMAT_PCM && bits == 16 -> { offset -> buffer.getShort(offset) / 32768f }
            format == FORMAT_PCM && bits == 24 -> { offset ->
                val value = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 2].toInt() shl 16)
                value / 8388608f
            }
            format == FORMAT_PCM && bits == 32 -> { offset -> (buffer.getInt(offset) / 2147483648.0).toFloat() }
            format == FORMAT_FLOAT && bits == 32 -> { offset -> buffer.getFloat(offset) }
            format == FORMAT_FLOAT && bits == 64 -> { offset -> buffer.getDouble(offset).toFloat() }
            else -> throw IOException("Unsupported WAV format $format with $bits bits")
        }

        val frames = (dataSize / blockAlign).toInt()
        val samples = FloatArray(frames)
        val scale = 1f / channels
        for (frame in 0 until frames) {
            val base = dataOffset + frame * blockAlign
            if (channels == 1) {
                samples[frame] = read(base)
            } else {
                var sum = 0f
                for (channel in 0 until channels) sum += read(base + channel * bytesPerSample)
                samples[frame] = sum * scale
            }
        }
        return DecodedAudio(samples, sampleRate.toDouble())
    }
}

/**
 * Mono 16-bit PCM WAV in memory, the form [PlaybackEngine] hands to Media3 (iOS: `AVAudioPCMBuffer.mono`).
 * Samples are scaled by 32768, the inverse of decoding, so a clip read from 16-bit PCM plays back bit for bit.
 */
internal object PcmWav {
    const val HEADER_SIZE = 44

    /** A WAV of `samples[from until to]`, or null when the rate or the length cannot make one. */
    fun mono(samples: FloatArray, from: Int, to: Int, sampleRate: Double): ByteArray? {
        if (!(sampleRate > 0) || from >= to) return null
        val frames = to - from
        val out = ByteBuffer.allocate(HEADER_SIZE + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        putHeader(out, frames, sampleRate.roundToInt())
        for (i in from until to) {
            out.putShort((samples[i] * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
        }
        return out.array()
    }

    fun frames(wav: ByteArray): Int = (wav.size - HEADER_SIZE) / 2

    fun sampleRate(wav: ByteArray): Int = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(24)

    /** The part of [wav] from [fromFrame] on, or null when nothing is left. */
    fun tail(wav: ByteArray, fromFrame: Int): ByteArray? {
        val frames = frames(wav)
        if (fromFrame >= frames) return null
        val start = max(0, fromFrame)
        val remaining = frames - start
        val out = ByteBuffer.allocate(HEADER_SIZE + remaining * 2).order(ByteOrder.LITTLE_ENDIAN)
        putHeader(out, remaining, sampleRate(wav))
        out.put(wav, HEADER_SIZE + start * 2, remaining * 2)
        return out.array()
    }

    private fun putHeader(out: ByteBuffer, frames: Int, rate: Int) {
        out.put("RIFF".toByteArray(Charsets.US_ASCII))
        out.putInt(36 + frames * 2)
        out.put("WAVE".toByteArray(Charsets.US_ASCII))
        out.put("fmt ".toByteArray(Charsets.US_ASCII))
        out.putInt(16)
        out.putShort(1)
        out.putShort(1)
        out.putInt(rate)
        out.putInt(rate * 2)
        out.putShort(2)
        out.putShort(16)
        out.put("data".toByteArray(Charsets.US_ASCII))
        out.putInt(frames * 2)
    }
}
