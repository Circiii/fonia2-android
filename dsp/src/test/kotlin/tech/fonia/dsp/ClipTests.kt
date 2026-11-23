package tech.fonia.dsp

import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClipAnalysisTests {
    @Test
    fun countsSyllablesAndPauses() {
        val syllableSignal = { shaped(vowel(0.16, f0 = 200f)) { t -> sin(Math.PI * t / 0.16).toFloat() } }
        var parts = silence(0.3)
        repeat(6) { parts += syllableSignal() + silence(0.06) }
        parts += silence(0.6)
        repeat(4) { parts += syllableSignal() + silence(0.06) }
        parts += silence(0.3)
        val summary = analyze(withNoise(parts))
        assertTrue(summary.syllables.size in 9..11, "syllables ${summary.syllables.size}")
        assertEquals(1, summary.pauses.size, "${summary.pauses}")
        val pause = assertNotNull(summary.pauses.firstOrNull())
        assertTrue(pause.duration > 0.4, "$pause")
        assertTrue(summary.articulationRate > 3f, "rate ${summary.articulationRate}")
        assertTrue(summary.speechRate < summary.articulationRate)
    }

    @Test
    fun reportsPitchVariationInSemitones() {
        val summary = analyze(withNoise(silence(0.2) + vowel(1.0, f0 = 200f) + silence(0.2)))
        assertTrue(summary.pitch.medianHz > 190f && summary.pitch.medianHz < 210f, "${summary.pitch}")
        assertTrue(summary.pitch.rangeSt < 1f, "${summary.pitch}")
        assertTrue(summary.pvq < 0.02f, "pvq ${summary.pvq}")
        assertTrue(summary.pitch.voicedShare > 0.6f, "${summary.pitch}")
    }

    @Test
    fun aRisingContourHasAPositiveFinalSlope() {
        val summary = analyze(speech(listOf(SyllableSpec(0.5, 180f, 180f, 0.4f), SyllableSpec(0.5, 180f, 260f, 0.4f))), rate = 16000.0)
        assertTrue(summary.finalSlopeSt > 1.5f, "slope ${summary.finalSlopeSt}")
    }

    @Test
    fun findsAHesitationBeforeAPause() {
        val summary = analyze(withNoise(silence(0.3) + vowel(0.5, f0 = 200f) + silence(0.5) + vowel(0.3, f0 = 200f) + silence(0.3)))
        assertFalse(summary.hesitations.isEmpty())
    }

    @Test
    fun emptyAndShortClipsAreHandled() {
        assertTrue(analyze(FloatArray(0)).quality.isTooShort)
        assertTrue(analyze(silence(0.01)).fricatives.isEmpty())
    }

    @Test
    fun trackRunsAtTheAnalysisHopAndMarksFrication() {
        val summary = analyze(carrier(sNoise(0.16)))
        assertTrue(abs(summary.trackHopSeconds - 0.01) < 1e-9)
        assertTrue(summary.track.size > 50)
        val segment = assertNotNull(summary.fricatives.firstOrNull())
        val middle = (segment.start + segment.end) / 2
        val inside = assertNotNull(summary.track.firstOrNull { abs(it.time - middle) < 0.01 })
        assertEquals(SibilantPlace.S, inside.fricative)
        assertNull(summary.track.firstOrNull()?.fricative)
    }

    @Test
    fun modelEvidenceReplacesTheEnergyDetector() {
        val signal = withNoise(silence(0.3) + vowel(0.4, f0 = 200f) + silence(0.5) + vowel(0.4, f0 = 200f) + silence(0.3))
        val evidence = SpeechEvidence(
            segments = listOf(TimeSpan(start = 0.31, end = 0.69), TimeSpan(start = 1.21, end = 1.59)),
            f0 = SpeechEvidence.F0Track(hopSeconds = 0.016, hz = FloatArray(120) { 180f }, confidence = FloatArray(120) { 0.9f }),
        )
        val summary = analyzeClip(signal, sampleRate = testRate, group = VoiceGroup.FEMALE, calibration = null, evidence = evidence)
        assertTrue(summary.usedSpeechModel)
        assertTrue(summary.usedPitchModel)
        assertTrue(abs(summary.speechStart - 0.31) < 1e-9)
        assertEquals(1, summary.pauses.size)
        assertTrue(abs(summary.pitch.medianHz - 180f) < 1f)
        assertTrue(abs(summary.speechSeconds - 0.76) < 1e-6)
    }

    @Test
    fun aModelThatFoundNoSpeechStillDefersToALoudVoice() {
        val signal = withNoise(silence(0.2) + vowel(0.6, f0 = 200f) + silence(0.2))
        val summary = analyzeClip(
            signal, sampleRate = testRate, group = VoiceGroup.FEMALE, calibration = null, evidence = SpeechEvidence(segments = emptyList()),
        )
        assertTrue(summary.usedSpeechModel)
        assertEquals(0.0, summary.speechSeconds)
        assertTrue(heardSpeech(summary))
    }
}

class CaptureQualityTests {
    @Test
    fun aCleanTakeIsUsable() {
        val quality = analyze(carrier(sNoise(0.16))).quality
        assertTrue(quality.isUsable, "$quality")
        assertTrue(quality.canJudgeSibilants, "$quality")
        assertTrue(quality.bandwidthHz > 15000f, "$quality")
        assertEquals(testRate, quality.sampleRate)
    }

    @Test
    fun flatTopsAreClipping() {
        val loud = (silence(0.2) + vowel(0.5, f0 = 200f, amp = 3f) + silence(0.2)).let { s -> FloatArray(s.size) { max(-1f, min(1f, s[it])) } }
        val quality = analyze(withNoise(loud, amp = 0.0005f)).quality
        assertTrue(quality.isClipped, "$quality")
        assertTrue(quality.clippedFraction > DSPThresholds.v1.capture.maxClippedFraction)
    }

    @Test
    fun aLoudButUnclippedTakeIsNotFlagged() {
        assertFalse(analyze(withNoise(silence(0.2) + vowel(0.5, f0 = 200f, amp = 0.95f) + silence(0.2), amp = 0.0005f)).quality.isClipped)
    }

    @Test
    fun aFaintTakeIsTooQuiet() {
        val faint = silence(0.2) + vowel(0.5, f0 = 200f, amp = 0.001f) + silence(0.2)
        assertTrue(analyze(withNoise(faint, amp = 0.0001f)).quality.isTooQuiet)
    }

    @Test
    fun aSilentTakeIsTooQuiet() {
        assertTrue(analyze(withNoise(silence(1.0), amp = 0.0005f)).quality.isTooQuiet)
    }

    @Test
    fun aClickIsTooShort() {
        val quality = analyze(withNoise(silence(0.5) + vowel(0.05, f0 = 200f) + silence(0.5), amp = 0.0005f)).quality
        assertTrue(quality.isTooShort, "$quality")
    }

    @Test
    fun clippedRunsNeedSeveralSamples() {
        val settings = DSPThresholds.v1.capture
        val lone = floatArrayOf(0f, 1f, 0f, -1f, 0f, 1f, 0f)
        assertEquals(0, clippedSampleCount(lone, settings))
        val run = floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f)
        assertEquals(4, clippedSampleCount(run, settings))
    }

    @Test
    fun a16kCaptureCannotJudgeSibilants() {
        val rate = 16000.0
        val signal = withNoise(
            silence(0.2, rate) + vowel(0.2, rate = rate) + bandNoise(0.16, 2000f, 7000f, amp = 0.1f, rate = rate) +
                vowel(0.2, rate = rate) + silence(0.2, rate),
        )
        assertFalse(analyze(signal, rate = rate).quality.canJudgeSibilants)
    }

    @Test
    fun aBluetoothRouteResampledTo48kIsCaught() {
        // The sample rate says 48 kHz, but everything above 8 kHz is gone.
        val signal = silence(0.2) + vowel(0.2) + bandNoise(0.16, 2000f, 7600f, amp = 0.12f) + vowel(0.2) + silence(0.2)
        val quality = analyze(signal).quality
        assertEquals(testRate, quality.sampleRate)
        assertTrue(quality.bandwidthHz < 11000f, "$quality")
        assertFalse(quality.canJudgeSibilants)
    }
}

class WAVTests {
    @Test
    fun roundTrips() {
        val signal = vowel(0.1)
        val decoded = decodeWAV(encodeWAV(signal, sampleRate = 48000))
        assertEquals(48000.0, decoded.sampleRate)
        assertEquals(signal.size, decoded.pcm.size)
        assertTrue(abs(decoded.pcm[100] - signal[100]) < 1e-3f)
    }

    @Test
    fun rejectsNonsense() {
        assertFailsWith(WAVError.NotWAV::class) { decodeWAV(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun resampleKeepsDurationAndTone() {
        val signal = vowel(0.1)
        val lower = resample(signal, from = testRate, to = 16000.0)
        assertEquals(signal.size / 3, lower.size)
        val tracker = YINPitchTracker(16000.0, DSPThresholds.v1.pitch)
        val estimate = tracker.estimate(lower, 0)
        assertTrue(abs(estimate.hz - 220f) < 4f, "read ${estimate.hz}")
    }

    @Test
    fun trimsSilenceAroundSpeech() {
        val signal = silence(1.0) + vowel(0.3) + silence(1.0)
        val trimmed = trimSilence(signal, sampleRate = testRate)
        assertTrue(trimmed.size < signal.size)
        assertTrue(trimmed.size >= sampleCount(0.3))
        assertTrue(trimmed.size <= sampleCount(0.6))
    }
}

class ThresholdTests {
    @Test
    fun defaultsSurviveJSON() {
        val decoded = DSPThresholds.load(DSPThresholds.v1.jsonData())
        assertEquals(DSPThresholds.v1, decoded)
        assertEquals(DSPThresholds.currentVersion, decoded.version)
    }

    @Test
    fun aPartialFileOverridesOnlyWhatItNames() {
        val json = """{"version": 7, "tap": {"maxWidthMs": 50}, "sibilant": {"female": {"centroidHz": 6000}}}"""
        val loaded = DSPThresholds.load(json.toByteArray(Charsets.UTF_8))
        assertEquals(7, loaded.version)
        assertEquals(50f, loaded.tap.maxWidthMs)
        assertEquals(DSPThresholds.v1.tap.minWidthMs, loaded.tap.minWidthMs)
        assertEquals(6000f, loaded.sibilant.female.centroidHz)
        assertEquals(DSPThresholds.v1.sibilant.female.peakHz, loaded.sibilant.female.peakHz)
    }

    @Test
    fun malformedJSONThrows() {
        assertFails { DSPThresholds.load("nope".toByteArray(Charsets.UTF_8)) }
    }

    @Test
    fun thresholdsReallyDriveTheAnalysis() {
        val strict = DSPThresholds.v1.copy(fricative = DSPThresholds.v1.fricative.copy(presentAboveFloorDb = 500f))
        assertTrue(analyze(carrier(sNoise(0.16)), thresholds = strict).fricatives.isEmpty())
        assertFalse(analyze(carrier(sNoise(0.16))).fricatives.isEmpty())
    }
}
