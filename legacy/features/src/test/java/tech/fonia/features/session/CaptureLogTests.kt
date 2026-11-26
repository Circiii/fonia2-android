package tech.fonia.features.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.dsp.DSPThresholds
import tech.fonia.features.app.AudioHub
import tech.fonia.features.grownups.PracticeReport
import tech.fonia.store.JSONValue

/**
 * What is kept about how a take was heard, next to the attempt (iOS `CaptureLogTests`): the microphone, the rate, the
 * DSP settings and the model, so later a shift in the phone's answers can be told apart from a shift in the speech.
 */
class CaptureLogTests {
    /** a take keeps its microphone, rate and DSP settings; a narrow-band microphone is named as such */
    @Test
    fun entry() {
        val phone = CaptureLog.entry(microphone = "phone", sampleRate = 48_000.0, thresholds = DSPThresholds.v1, model = null, app = null)
        assertEquals(
            JSONValue.Object(mapOf("mic" to JSONValue.String("phone"), "rateHz" to JSONValue.Number(48_000.0), "dsp" to JSONValue.String("v1"))),
            phone,
        )

        val bluetooth = CaptureLog.entry(microphone = "bluetooth", sampleRate = 16_000.0, thresholds = AudioHub.withoutSibilants, model = "zipa@abc", app = "1.4 (212)")
        assertEquals(
            JSONValue.Object(
                mapOf(
                    "mic" to JSONValue.String("bluetooth"), "rateHz" to JSONValue.Number(16_000.0), "dsp" to JSONValue.String("v1-noSibilants"),
                    "model" to JSONValue.String("zipa@abc"), "app" to JSONValue.String("1.4 (212)"),
                ),
            ),
            bluetooth,
        )
    }

    /** the capture goes next to the take's measures and reads back; older attempts have none */
    @Test
    fun detail() {
        val capture = CaptureLog.entry(microphone = "wired", sampleRate = 44_100.0, thresholds = DSPThresholds.v1, model = null, app = null)
        val detail = CaptureLog.adding(capture, to = mapOf("peakHz" to JSONValue.Number(6_200.0)))
        assertEquals(JSONValue.Number(6_200.0), detail["peakHz"])
        assertEquals(capture, detail[CaptureLog.KEY])
        assertEquals("wired", CaptureLog.microphone(detail))
        assertEquals("wired", CaptureLog.microphone(CaptureLog.adding(capture, to = null)))
        assertNull(CaptureLog.microphone(mapOf("peakHz" to JSONValue.Number(6_200.0))))
        assertNull(CaptureLog.microphone(null))
    }

    /** every microphone name has words in the report */
    @Test
    fun names() {
        for (microphone in listOf("phone", "wired", "bluetooth", "usb", "other", "none", "fixture")) {
            assertTrue(PracticeReport.microphoneName(microphone).isNotEmpty())
        }
        assertEquals("al telefonului", PracticeReport.microphoneName("phone"))
    }
}
