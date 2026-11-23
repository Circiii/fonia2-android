package tech.fonia.audio

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/** Port of OutputTests.swift, suite "Recording writer": the cases that run the AAC encoder. */
@RunWith(AndroidJUnit4::class)
class RecordingWriterTests {
    /** A take round-trips through M4A at each rate. */
    @Test(timeout = 120_000)
    fun roundTrip() = runBlocking {
        for (rate in listOf(48_000.0, 44_100.0, 16_000.0)) {
            withTemporaryFile("m4a") { file ->
                val clip = CapturedClip(sine(frequency = 440.0, amplitude = 0.5f, seconds = 1.0, sampleRate = rate), rate)
                RecordingWriter.writeM4A(clip, file)

                val extractor = MediaExtractor()
                extractor.setDataSource(file.path)
                val format = extractor.getTrackFormat(0)
                extractor.release()
                assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, format.getString(MediaFormat.KEY_MIME))
                assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                assertEquals(rate.toInt(), format.getInteger(MediaFormat.KEY_SAMPLE_RATE))

                val decoded = AudioDecoder.decodeMono(ClipLocation.File(file))
                val seconds = decoded.samples.size / decoded.sampleRate
                assertTrue("$rate Hz: decoded $seconds s", abs(seconds - 1) < 0.05)

                // AAC is lossy, so the check is on loudness. A sine of amplitude 0.5 has an RMS of about 0.354.
                val middle = decoded.samples.copyOfRange(decoded.samples.size / 4, decoded.samples.size * 3 / 4)
                val rms = sqrt(middle.fold(0f) { sum, x -> sum + x * x } / middle.size)
                assertTrue("$rate Hz: rms $rms", abs(rms - 0.354f) < 0.03f)
            }
        }
    }

    /** Missing folders are created. */
    @Test(timeout = 60_000)
    fun createsFolders() = runBlocking {
        val folder = File(System.getProperty("java.io.tmpdir"), "FoniaAudioTests-${UUID.randomUUID()}")
        try {
            val file = File(folder, "nested/take.m4a")
            RecordingWriter.writeM4A(CapturedClip(FloatArray(4_800) { 0.1f }, 48_000.0), file)
            assertTrue(file.exists())
        } finally {
            folder.deleteRecursively()
        }
    }
}
