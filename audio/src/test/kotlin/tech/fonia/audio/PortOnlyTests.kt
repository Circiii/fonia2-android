package tech.fonia.audio

import android.media.AudioDeviceInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Checks the Android port adds on top of the iOS suites. They are not counted among the 70 ported tests.

/** The end-of-take rule from iOS `AudioHub.waitForTakeEnd` (no iOS test exists for it), on a fake clock. */
class SpeechGateTests {
    private var now = 0L
    private val gate = SpeechGate { now }

    private fun seconds(value: Double) {
        now = Math.round(value * 1e9)
    }

    private fun over(elapsed: Double) = TakeEnd.isOver(
        elapsed, gate, TakeEnd.DEFAULT_SILENCE, TakeEnd.DEFAULT_START_TIMEOUT, TakeEnd.DEFAULT_MAXIMUM,
    )

    @Test
    fun defaultsAreTheIosOnes() {
        assertEquals(0.7, TakeEnd.DEFAULT_SILENCE, 0.0)
        assertEquals(6.0, TakeEnd.DEFAULT_START_TIMEOUT, 0.0)
        assertEquals(8.0, TakeEnd.DEFAULT_MAXIMUM, 0.0)
        assertEquals(50L, TakeEnd.POLL_INTERVAL_MS)
    }

    @Test
    fun withNoVoiceTheTakeEndsAtTheStartTimeout() {
        assertTrue(gate.secondsSinceSpeech.isInfinite())
        assertFalse(over(5.99))
        assertTrue(over(6.0))
    }

    @Test
    fun afterAVoiceTheTakeEndsAfterTheSilence() {
        seconds(1.0)
        gate.update(isSpeaking = true)
        seconds(1.69)
        gate.update(isSpeaking = false)
        assertFalse(over(1.69))
        seconds(1.7)
        assertTrue(over(1.7))
        // Silence counts from the last voiced hop, so a voice past the start timeout keeps the take going.
        seconds(6.5)
        gate.update(isSpeaking = true)
        assertFalse(over(6.5))
    }

    @Test
    fun maximumAndStopEndTheTake() {
        seconds(7.9)
        gate.update(isSpeaking = true)
        assertTrue(over(8.0))
        gate.reset()
        assertFalse(gate.hasSpoken)
        gate.requestStop()
        assertTrue(over(0.1))
        gate.reset()
        assertFalse(gate.isStopRequested)
    }

    @Test
    fun waitReturnsAtTheStartTimeoutOnARealClock() = runBlocking {
        val gate = SpeechGate()
        val began = System.nanoTime()
        gate.waitForTakeEnd(silence = 0.7, startTimeout = 0.2, maximum = 8.0)
        val elapsed = (System.nanoTime() - began) / 1e9
        assertTrue("elapsed $elapsed", elapsed >= 0.2 && elapsed < 1.0)
    }
}

class CaptureChoicesTests {
    @Test
    fun presetIsUnprocessedOnlyWhereThePhoneSaysSo() {
        assertEquals(InputPreset.Unprocessed, InputPreset.preferred("true"))
        assertEquals(InputPreset.VoiceRecognition, InputPreset.preferred(null))
        assertEquals(InputPreset.VoiceRecognition, InputPreset.preferred("false"))
        assertEquals(9, InputPreset.Unprocessed.audioSource)
        assertEquals(6, InputPreset.VoiceRecognition.audioSource)
    }

    @Test
    fun narrowBandMeansBluetoothOrUnder32kHz() {
        fun info(rate: Double, device: Int?) =
            CaptureInfo(rate, InputPreset.Unprocessed, InputPreset.Unprocessed, device, 1, true, 96, emptyList())
        assertFalse(info(48_000.0, AudioDeviceInfo.TYPE_BUILTIN_MIC).isNarrowBand)
        assertFalse(info(32_000.0, null).isNarrowBand)
        assertTrue(info(16_000.0, AudioDeviceInfo.TYPE_BUILTIN_MIC).isNarrowBand)
        assertTrue(info(48_000.0, AudioDeviceInfo.TYPE_BLUETOOTH_SCO).isNarrowBand)
        assertTrue(info(48_000.0, AudioDeviceInfo.TYPE_BLE_HEADSET).isNarrowBand)
    }

    @Test
    fun fixtureModePicksTheClipThenSoareThenTheMicrophone() {
        val microphone = FixtureSource(ClipLocation.File(File("mic")))
        val clip = ClipLocation.File(Fixtures.soareWav)
        assertTrue(FixtureMode.captureSource(true, clip, null) { microphone } is FixtureSource)
        assertTrue(FixtureMode.captureSource(true, null, clip) { microphone } is FixtureSource)
        assertSame(microphone, FixtureMode.captureSource(true, null, null) { microphone })
        assertSame(microphone, FixtureMode.captureSource(false, clip, clip) { microphone })
        assertEquals("foniaFixtures", FixtureMode.LAUNCH_ARGUMENT)
    }

    @Test
    fun halvesRoundAwayFromZeroAsInSwift() {
        assertEquals(221.0, 220.5.roundedAwayFromZero(), 0.0)
        assertEquals(-221.0, (-220.5).roundedAwayFromZero(), 0.0)
        assertEquals(0.0, 0.49999999999999994.roundedAwayFromZero(), 0.0)
        assertEquals(480.0, 480.0.roundedAwayFromZero(), 0.0)
    }
}

class DecodingTests {
    @Test
    fun soareIs16BitPcmScaledBy32768() = runBlocking {
        val audio = AudioDecoder.decodeMono(ClipLocation.File(Fixtures.soareWav))
        assertEquals(Fixtures.SOARE_FRAMES, audio.samples.size)
        assertEquals(Fixtures.SOARE_RATE, audio.sampleRate, 0.0)
        assertTrue(audio.samples.all { it * 32768f == Math.rint((it * 32768f).toDouble()).toFloat() })
    }

    @Test
    fun floatWavRoundTripsExactly() = runBlocking {
        withTemporaryFile("wav") { file ->
            val samples = sine(frequency = 440.0, amplitude = 0.5f, seconds = 0.1, sampleRate = 16_000.0)
            writeWav(samples, 16_000.0, file)
            val audio = AudioDecoder.decodeMono(ClipLocation.File(file))
            assertEquals(16_000.0, audio.sampleRate, 0.0)
            assertArrayEquals(samples, audio.samples, 0f)
        }
    }

    @Test
    fun pcmWavPlaysDecodedPcmBitForBitAndCutsTails() = runBlocking {
        val audio = AudioDecoder.decodeMono(ClipLocation.File(Fixtures.soareWav))
        val wav = PcmWav.mono(audio.samples, 0, audio.samples.size, audio.sampleRate)!!
        assertArrayEquals(audio.samples, WavReader.decodeMono(wav).samples, 0f)
        val tail = PcmWav.tail(wav, 100_000)!!
        assertEquals(Fixtures.SOARE_FRAMES - 100_000, PcmWav.frames(tail))
        assertEquals(48_000, PcmWav.sampleRate(tail))
        assertNull(PcmWav.tail(wav, Fixtures.SOARE_FRAMES))
        assertNull(PcmWav.mono(audio.samples, 5, 5, 48_000.0))
        assertNull(PcmWav.mono(audio.samples, 0, 5, 0.0))
    }

    @Test
    fun aacBitRatesStayValid() {
        assertEquals(128_000, RecordingWriter.bitRate(48_000))
        assertEquals(117_600, RecordingWriter.bitRate(44_100))
        assertEquals(42_666, RecordingWriter.bitRate(16_000))
    }

    /** input types map to the microphone names the attempt log keeps (iOS `AudioSessionMappingTests.microphones`) */
    @Test
    fun microphones() {
        assertEquals("phone", CaptureInfo.microphone(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertEquals("wired", CaptureInfo.microphone(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals("bluetooth", CaptureInfo.microphone(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals("bluetooth", CaptureInfo.microphone(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals("usb", CaptureInfo.microphone(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals("other", CaptureInfo.microphone(AudioDeviceInfo.TYPE_TELEPHONY))
        assertEquals("other", CaptureInfo.microphone(null))
    }
}
