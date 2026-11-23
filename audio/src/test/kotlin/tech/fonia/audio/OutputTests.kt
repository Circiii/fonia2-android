package tech.fonia.audio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Port of OutputTests.swift, suite "Captured clip". */
class CapturedClipTests {
    /** Duration follows samples and rate. */
    @Test
    fun duration() {
        assertEquals(0.5, CapturedClip(FloatArray(24_000), 48_000.0).duration, 0.0)
        assertEquals(0.0, CapturedClip(FloatArray(0), 0.0).duration, 0.0)
    }
}

/**
 * Port of OutputTests.swift, suite "Recording writer": the case that needs no encoder. The round trip and the
 * folder creation run MediaCodec, so they are instrumented (RecordingWriterTests in androidTest).
 */
class RecordingWriterJvmTests {
    /** An empty take is refused. */
    @Test
    fun emptyClip() = runBlocking {
        try {
            RecordingWriter.writeM4A(CapturedClip(FloatArray(0), 48_000.0), File("/tmp/unused.m4a"))
            fail("Expected WriteError.EmptyClip")
        } catch (e: RecordingWriter.WriteError.EmptyClip) {
            // Expected.
        }
    }
}

/** Port of OutputTests.swift, suite "Sound effects". */
class SoundEffectsTests {
    /** Audible only when neither muted nor capturing. */
    @Test
    fun gating() {
        val cases = listOf(
            Triple(false, false, true),
            Triple(true, false, false),
            Triple(false, true, false),
            Triple(true, true, false),
        )
        for ((isMuted, isCapturing, expected) in cases) {
            assertEquals("muted $isMuted, capturing $isCapturing", expected, SoundEffects.isAudible(isMuted, isCapturing))
        }
    }

    /** Mute setting reads back. */
    @Test
    fun muteSetting() {
        val effects = SoundEffects(ClipLocation.File(File(checkNotNull(System.getProperty("java.io.tmpdir")))))
        assertFalse(effects.isMuted)
        effects.isMuted = true
        assertTrue(effects.isMuted)
    }

    /** Each effect names a file the app bundles (fonia-content `voice/sfx`, copied into the app's assets). */
    @Test
    fun fileExists() {
        val content = File(checkNotNull(System.getProperty("fonia.content")) { "fonia.content is not set" })
        for (effect in SoundEffects.Effect.entries) {
            val bundled = File(content, "voice/sfx/${effect.fileName}")
            assertTrue("${effect.name}: $bundled", bundled.isFile)
        }
    }
}
