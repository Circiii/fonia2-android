package tech.fonia.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of CaptureRingTests.swift ("Capture ring"). On the JVM `CaptureRing` is the Kotlin twin; on a device it
 * is FoniaAudioRT's native ring, so the same cases check both. `sinkNode` is in the instrumented tests.
 */
class CaptureRingTests {
    private fun write(values: FloatArray, ring: CaptureRing): Int = ring.write(values)

    private fun read(count: Int, ring: CaptureRing): FloatArray {
        val out = FloatArray(count) { Float.NaN }
        val got = ring.read(out)
        return out.copyOf(got)
    }

    /** Samples come out in the order they went in. */
    @Test
    fun preservesOrder() {
        CaptureRing(8).use { ring ->
            assertEquals(3, write(floatArrayOf(1f, 2f, 3f), ring))
            assertEquals(3, ring.available)
            assertArrayEquals(floatArrayOf(1f, 2f), read(2, ring), 0f)
            assertArrayEquals(floatArrayOf(3f), read(8, ring), 0f)
            assertEquals(0, ring.available)
        }
    }

    /** Reading an empty ring returns nothing and drops nothing. */
    @Test
    fun emptyRead() {
        CaptureRing(8).use { ring ->
            assertEquals(0, read(4, ring).size)
            assertEquals(0, ring.dropped)
        }
    }

    /** Writes and reads wrap around the end of the storage. */
    @Test
    fun wraparound() {
        CaptureRing(8).use { ring ->
            assertEquals(6, write(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), ring))
            assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), read(6, ring), 0f)

            // These six straddle the end of the eight-slot storage.
            assertEquals(6, write(floatArrayOf(7f, 8f, 9f, 10f, 11f, 12f), ring))
            assertArrayEquals(floatArrayOf(7f, 8f, 9f, 10f, 11f, 12f), read(6, ring), 0f)
            assertEquals(0, ring.dropped)
        }
    }

    /** Samples past the capacity are refused and counted. */
    @Test
    fun overflowCounts() {
        CaptureRing(4).use { ring ->
            assertEquals(4, write(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), ring))
            assertEquals(2, ring.dropped)
            assertEquals(0, ring.free)

            assertEquals(0, write(floatArrayOf(7f, 8f, 9f), ring))
            assertEquals(5, ring.dropped)

            // Oldest samples survive an overflow, and freed space is usable again.
            assertArrayEquals(floatArrayOf(1f, 2f), read(2, ring), 0f)
            assertEquals(1, write(floatArrayOf(10f), ring))
            assertArrayEquals(floatArrayOf(3f, 4f, 10f), read(4, ring), 0f)
            assertEquals(5, ring.dropped)
        }
    }

    /** Capacity rounds up to a power of two. */
    @Test
    fun capacityRounding() {
        for ((requested, actual) in listOf(1 to 1, 2 to 2, 3 to 4, 1000 to 1024, (1 shl 17) to (1 shl 17))) {
            CaptureRing(requested).use { assertEquals("capacity($requested)", actual, it.capacity) }
        }
    }

    /** A writer thread and a reader thread lose nothing and reorder nothing. */
    @Test(timeout = 60_000)
    fun concurrentWriterAndReader() = runBlocking {
        CaptureRing(1 shl 10).use { ring ->
            val total = 2_000_000

            val written = async(Dispatchers.Default) {
                var next = 0
                val chunk = FloatArray(256)
                while (next < total) {
                    val count = minOf(chunk.size, minOf(total - next, ring.free))
                    if (count == 0) {
                        yield()
                        continue
                    }
                    for (offset in 0 until count) chunk[offset] = ((next + offset) % 8192).toFloat()
                    ring.write(chunk, 0, count)
                    next += count
                }
            }

            val mismatches = async(Dispatchers.Default) {
                var expected = 0
                var wrong = 0
                val chunk = FloatArray(300)
                while (expected < total) {
                    val got = ring.read(chunk)
                    if (got == 0) {
                        yield()
                        continue
                    }
                    for (offset in 0 until got) {
                        if (chunk[offset] != ((expected + offset) % 8192).toFloat()) wrong += 1
                    }
                    expected += got
                }
                wrong
            }

            written.await()
            assertEquals(0, mismatches.await())
            assertEquals(0, ring.dropped)
            assertTrue(ring.available == 0)
        }
    }
}
