package tech.fonia.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Port-only: the edit list RecordingWriter adds, on the three layouts a muxer can produce. */
class Mp4GaplessTests {
    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
        return ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray()).put(body).array()
    }

    private fun ints(vararg values: Int): ByteArray = ByteBuffer.allocate(values.size * 4).apply { values.forEach { putInt(it) } }.array()

    private val payload = ByteArray(64) { it.toByte() }

    /** mvhd at 1000/s, an audio track at 44 100/s whose one chunk sits at [chunkOffset]. */
    private fun moov(chunkOffset: Int): ByteArray = box(
        "moov",
        box("mvhd", ints(0, 0, 0, 1000, 1068), ByteArray(80)),
        box(
            "trak",
            box("tkhd", ints(7, 0, 0, 1, 0, 1068), ByteArray(60)),
            box(
                "mdia",
                box("mdhd", ints(0, 0, 0, 44_100, 47_104), ByteArray(4)),
                box("hdlr", ints(0, 0), "soun".toByteArray(), ByteArray(12)),
                box("minf", box("stbl", box("stco", ints(0, 1, chunkOffset)))),
            ),
        ),
    )

    private fun chunkOffset(file: ByteArray): Int {
        val at = String(file, Charsets.US_ASCII).indexOf("stco") + 4 + 8
        return ByteBuffer.wrap(file).getInt(at)
    }

    private fun payloadAt(file: ByteArray): ByteArray = file.copyOfRange(chunkOffset(file), chunkOffset(file) + payload.size)

    private fun check(original: ByteArray) {
        assertNull(Mp4Gapless.read(original))
        val fixed = Mp4Gapless.addEditList(original, delayFrames = 2048, keptFrames = 44_100, sampleRate = 44_100)
        val edit = Mp4Gapless.read(fixed)
        assertNotNull(edit)
        edit!!
        assertEquals(2048, edit.delayFrames(44_100))
        assertEquals(44_100, edit.keptFrames(44_100))
        assertEquals(1000L, edit.segmentDuration)
        // The media data is still where the chunk table says it is.
        assertArrayEquals(payload, payloadAt(fixed))
        // Adding it twice changes nothing.
        assertArrayEquals(fixed, Mp4Gapless.addEditList(fixed, 2048, 44_100, 44_100))
    }

    @Test
    fun moovAfterMediaData() {
        val ftyp = box("ftyp", "M4A ".toByteArray(), ints(0))
        val mdat = box("mdat", payload)
        check(ftyp + mdat + moov(chunkOffset = ftyp.size + 8))
    }

    @Test
    fun moovBeforeMediaDataWithRoomInAFreeBox() {
        val ftyp = box("ftyp", "M4A ".toByteArray(), ints(0))
        val moovSize = moov(0).size
        val free = box("free", ByteArray(200))
        val mdatStart = ftyp.size + moovSize + free.size
        val file = ftyp + moov(chunkOffset = mdatStart + 8) + free + box("mdat", payload)
        check(file)
        val fixed = Mp4Gapless.addEditList(file, 2048, 44_100, 44_100)
        assertEquals(file.size, fixed.size)
    }

    @Test
    fun moovBeforeMediaDataWithNoRoom() {
        val ftyp = box("ftyp", "M4A ".toByteArray(), ints(0))
        val moovSize = moov(0).size
        val file = ftyp + moov(chunkOffset = ftyp.size + moovSize + 8) + box("mdat", payload)
        check(file)
    }
}
