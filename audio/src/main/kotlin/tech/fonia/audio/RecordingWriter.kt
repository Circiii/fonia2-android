package tech.fonia.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

object RecordingWriter {
    sealed class WriteError(message: String) : Exception(message) {
        class EmptyClip : WriteError("An empty take cannot be written")
    }

    private const val CHUNK_FRAMES = 8192
    private const val TIMEOUT_US = 10_000L

    /** Android's own AAC-LC encoder (FDK), on every phone, so every phone writes the same bits. */
    private val SOFTWARE_ENCODERS = listOf("c2.android.aac.encoder", "OMX.google.aac.encoder")

    /** Priming the software encoder puts before the audio: 2048 frames, measured at 16, 44.1 and 48 kHz. */
    internal const val ENCODER_DELAY_FRAMES = 2048

    /**
     * Encodes a take as AAC in an M4A file, creating the parent folder if needed (MediaCodec + MediaMuxer).
     * iOS asks Apple's encoder for a quality level; MediaCodec needs a bit rate, see [bitRate]. Like Apple's
     * files, the result says where the audio starts and ends (an edit list, [Mp4Gapless]), so a reader drops the
     * encoder's priming and padding and gets the take back at its length.
     */
    suspend fun writeM4A(clip: CapturedClip, file: File) {
        if (clip.samples.isEmpty() || !(clip.sampleRate > 0)) throw WriteError.EmptyClip()
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            encode(clip, file)
            val rate = clip.sampleRate.roundToInt()
            file.writeBytes(Mp4Gapless.addEditList(file.readBytes(), ENCODER_DELAY_FRAMES, clip.samples.size, rate))
        }
    }

    private fun makeEncoder(): MediaCodec {
        val available = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && MediaFormat.MIMETYPE_AUDIO_AAC in it.supportedTypes }
            .map { it.name }
        val name = SOFTWARE_ENCODERS.firstOrNull { it in available }
        return if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    }

    /**
     * About 2.67 bits a sample, at most 128 kbps: 128 kbps at 48 kHz, 118 at 44.1 kHz, 43 at 16 kHz.
     * `AVAudioQuality.high` lands in the same range, and every value is valid for mono AAC-LC at its rate.
     */
    internal fun bitRate(sampleRate: Int): Int = min(128_000, sampleRate * 8 / 3)

    private fun encode(clip: CapturedClip, file: File) {
        val rate = clip.sampleRate.roundToInt()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate(rate))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_FRAMES * 2)
        }
        val codec = makeEncoder()
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var written = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!.order(ByteOrder.nativeOrder())
                        buffer.clear()
                        val frames = min(min(CHUNK_FRAMES, clip.samples.size - written), buffer.capacity() / 2)
                        val time = written * 1_000_000L / rate
                        if (frames <= 0) {
                            codec.queueInputBuffer(index, 0, 0, time, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            for (i in 0 until frames) {
                                buffer.putShort((clip.samples[written + i] * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
                            }
                            codec.queueInputBuffer(index, 0, frames * 2, time, 0)
                            written += frames
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                } else if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxing) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxing) runCatching { muxer.stop() }
            muxer.release()
        }
    }
}
