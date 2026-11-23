package tech.fonia.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/**
 * Gapless AAC in MP4, the way Apple's files carry it: one edit list entry (`moov/trak/edts/elst`) whose media
 * time skips the encoder's priming and whose duration is the real length, so the padding at the end is known too.
 *
 * iOS's `AVAudioFile` writes and honours this by itself. On Android, MediaMuxer writes no edit list and MediaCodec
 * decodes priming and padding as audio, so [RecordingWriter] adds the list after muxing and [AudioDecoder] trims by it.
 */
internal object Mp4Gapless {
    class EditList(val mediaTime: Long, val mediaTimescale: Long, val segmentDuration: Long, val movieTimescale: Long) {
        /** Frames to skip at the start, at [sampleRate]. */
        fun delayFrames(sampleRate: Int): Int = (mediaTime.toDouble() * sampleRate / mediaTimescale).roundToLong().toInt()

        /** Frames to keep after the skipped ones, at [sampleRate]. */
        fun keptFrames(sampleRate: Int): Int = (segmentDuration.toDouble() * sampleRate / movieTimescale).roundToLong().toInt()
    }

    private class Box(val type: String, val offset: Int, val size: Int, val headerSize: Int) {
        val end: Int get() = offset + size
        val bodyOffset: Int get() = offset + headerSize
    }

    private val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "edts")

    fun isMp4(bytes: ByteArray): Boolean = bytes.size >= 8 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp"

    /** The single-entry edit list of the first audio track, or null when there is none. */
    fun read(bytes: ByteArray): EditList? {
        if (!isMp4(bytes)) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val moov = boxes(buffer, 0, bytes.size).firstOrNull { it.type == "moov" } ?: return null
        val movieTimescale = movieTimescale(buffer, moov) ?: return null
        for (trak in boxes(buffer, moov.bodyOffset, moov.end).filter { it.type == "trak" }) {
            val children = boxes(buffer, trak.bodyOffset, trak.end)
            val mdia = children.firstOrNull { it.type == "mdia" } ?: continue
            if (handler(buffer, mdia) != "soun") continue
            val mediaTimescale = mediaTimescale(buffer, mdia) ?: return null
            val edts = children.firstOrNull { it.type == "edts" } ?: return null
            val elst = boxes(buffer, edts.bodyOffset, edts.end).firstOrNull { it.type == "elst" } ?: return null
            val version = bytes[elst.bodyOffset].toInt()
            val count = buffer.getInt(elst.bodyOffset + 4)
            if (count != 1) return null
            val entry = elst.bodyOffset + 8
            val (segment, media) = if (version == 1) {
                buffer.getLong(entry) to buffer.getLong(entry + 8)
            } else {
                (buffer.getInt(entry).toLong() and 0xFFFF_FFFFL) to buffer.getInt(entry + 4).toLong()
            }
            if (media < 0) return null
            return EditList(media, mediaTimescale, segment, movieTimescale)
        }
        return null
    }

    /**
     * [bytes] with an edit list on the audio track that skips [delayFrames] and keeps [keptFrames], and with the
     * movie and track durations set to the kept length. Chunk offsets stay valid: the list takes room from a
     * `free` box that follows `moov`, or the offsets are moved when `moov` comes before the media data.
     */
    fun addEditList(bytes: ByteArray, delayFrames: Int, keptFrames: Int, sampleRate: Int): ByteArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val top = boxes(buffer, 0, bytes.size)
        val moov = top.firstOrNull { it.type == "moov" } ?: return bytes
        val movieTimescale = movieTimescale(buffer, moov) ?: return bytes
        val trak = boxes(buffer, moov.bodyOffset, moov.end).firstOrNull { trak ->
            boxes(buffer, trak.bodyOffset, trak.end).firstOrNull { it.type == "mdia" }?.let { handler(buffer, it) } == "soun"
        } ?: return bytes
        val trakChildren = boxes(buffer, trak.bodyOffset, trak.end)
        if (trakChildren.any { it.type == "edts" }) return bytes
        val tkhd = trakChildren.firstOrNull { it.type == "tkhd" } ?: return bytes
        val mdia = trakChildren.first { it.type == "mdia" }
        val mediaTimescale = mediaTimescale(buffer, mdia) ?: return bytes

        val segment = (keptFrames.toDouble() * movieTimescale / sampleRate).roundToLong()
        val mediaTime = (delayFrames.toDouble() * mediaTimescale / sampleRate).roundToLong()
        val edts = ByteBuffer.allocate(36).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(36).put("edts".toByteArray(Charsets.US_ASCII))
            putInt(28).put("elst".toByteArray(Charsets.US_ASCII))
            putInt(0) // version 0, no flags
            putInt(1) // one entry
            putInt(segment.toInt())
            putInt(mediaTime.toInt())
            putShort(1).putShort(0) // media rate 1.0
        }.array()
        val delta = edts.size

        val out = bytes.copyOf()
        val outBuffer = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        setDuration(outBuffer, moov.bodyOffset.let { boxes(buffer, it, moov.end).first { box -> box.type == "mvhd" } }, segment)
        setDuration(outBuffer, tkhd, segment, isTrackHeader = true)

        val mdat = top.firstOrNull { it.type == "mdat" }
        val following = top.firstOrNull { it.offset == moov.end }
        val shrinkFree = following != null && following.type == "free" && following.headerSize == 8 &&
            (following.size == delta || following.size >= delta + 8)
        val moveOffsets = !shrinkFree && mdat != null && mdat.offset > moov.offset
        if (moveOffsets) shiftChunkOffsets(outBuffer, trak, delta)

        val grown = ByteArray(out.size + delta)
        val insertAt = tkhd.end
        System.arraycopy(out, 0, grown, 0, insertAt)
        System.arraycopy(edts, 0, grown, insertAt, delta)
        System.arraycopy(out, insertAt, grown, insertAt + delta, out.size - insertAt)
        val grownBuffer = ByteBuffer.wrap(grown).order(ByteOrder.BIG_ENDIAN)
        grownBuffer.putInt(moov.offset, moov.size + delta)
        grownBuffer.putInt(trak.offset, trak.size + delta)
        if (!shrinkFree) return grown

        // Give the free box's room to the edit list, so nothing after it moves.
        val free = following
        val freeStart = free.offset + delta
        val remaining = free.size - delta
        val result = ByteArray(grown.size - delta)
        System.arraycopy(grown, 0, result, 0, freeStart)
        if (remaining > 0) {
            ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN).putInt(freeStart, remaining)
            System.arraycopy("free".toByteArray(Charsets.US_ASCII), 0, result, freeStart + 4, 4)
        }
        val tail = freeStart + free.size
        System.arraycopy(grown, tail, result, freeStart + remaining, grown.size - tail)
        return result
    }

    private fun boxes(buffer: ByteBuffer, start: Int, end: Int): List<Box> {
        val result = mutableListOf<Box>()
        var offset = start
        while (offset + 8 <= end) {
            var size = buffer.getInt(offset).toLong() and 0xFFFF_FFFFL
            val type = String(ByteArray(4) { buffer.get(offset + 4 + it) }, Charsets.US_ASCII)
            var header = 8
            if (size == 1L) {
                size = buffer.getLong(offset + 8)
                header = 16
            } else if (size == 0L) {
                size = (end - offset).toLong()
            }
            if (size < header || offset + size > end) break
            result += Box(type, offset, size.toInt(), header)
            offset += size.toInt()
        }
        return result
    }

    private fun movieTimescale(buffer: ByteBuffer, moov: Box): Long? {
        val mvhd = boxes(buffer, moov.bodyOffset, moov.end).firstOrNull { it.type == "mvhd" } ?: return null
        val version = buffer.get(mvhd.bodyOffset).toInt()
        return buffer.getInt(mvhd.bodyOffset + if (version == 1) 20 else 12).toLong() and 0xFFFF_FFFFL
    }

    private fun mediaTimescale(buffer: ByteBuffer, mdia: Box): Long? {
        val mdhd = boxes(buffer, mdia.bodyOffset, mdia.end).firstOrNull { it.type == "mdhd" } ?: return null
        val version = buffer.get(mdhd.bodyOffset).toInt()
        return buffer.getInt(mdhd.bodyOffset + if (version == 1) 20 else 12).toLong() and 0xFFFF_FFFFL
    }

    private fun handler(buffer: ByteBuffer, mdia: Box): String? {
        val hdlr = boxes(buffer, mdia.bodyOffset, mdia.end).firstOrNull { it.type == "hdlr" } ?: return null
        return String(ByteArray(4) { buffer.get(hdlr.bodyOffset + 8 + it) }, Charsets.US_ASCII)
    }

    /** mvhd: duration after timescale. tkhd: after track id and a reserved word. */
    private fun setDuration(buffer: ByteBuffer, box: Box, duration: Long, isTrackHeader: Boolean = false) {
        val version = buffer.get(box.bodyOffset).toInt()
        val at = box.bodyOffset + when {
            isTrackHeader && version == 1 -> 28
            isTrackHeader -> 20
            version == 1 -> 24
            else -> 16
        }
        if (version == 1) buffer.putLong(at, duration) else buffer.putInt(at, duration.toInt())
    }

    private fun shiftChunkOffsets(buffer: ByteBuffer, trak: Box, delta: Int) {
        fun visit(start: Int, end: Int) {
            for (box in boxes(buffer, start, end)) {
                when (box.type) {
                    in containers -> visit(box.bodyOffset, box.end)
                    "stco" -> {
                        val count = buffer.getInt(box.bodyOffset + 4)
                        for (i in 0 until count) {
                            val at = box.bodyOffset + 8 + i * 4
                            buffer.putInt(at, buffer.getInt(at) + delta)
                        }
                    }
                    "co64" -> {
                        val count = buffer.getInt(box.bodyOffset + 4)
                        for (i in 0 until count) {
                            val at = box.bodyOffset + 8 + i * 8
                            buffer.putLong(at, buffer.getLong(at) + delta)
                        }
                    }
                }
            }
        }
        visit(trak.bodyOffset, trak.end)
    }
}
