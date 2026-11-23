package tech.fonia.live

import tech.fonia.dsp.LiveFault
import tech.fonia.dsp.encodeWAV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LivePipelineTest {
    private val sTarget = LiveTarget.Sibilant(SibilantSide.S, voiced = false)
    private val lead = Signals.quiet(1.3)
    private val leadEnd = lead.size

    private fun meanSNess(centerHz: Double, talker: Talker = Talker.Child): Double {
        val p = LivePipeline(config = LiveConfig(talker = talker))
        var sum = 0.0
        var n = 0
        p.feed(Signals.concat(lead, Signals.hiss(centerHz, 0.8)), sTarget) { s, at ->
            if (at > leadEnd + 4800 && s.fricative) {
                sum += s.sNess
                n++
            }
        }
        assertTrue(n > 50, "the hiss at $centerHz Hz should read as friction, got $n frames")
        return sum / n
    }

    @Test
    fun sNessRisesWithTheHissFrequency() {
        val values = listOf(3500.0, 5500.0, 7000.0, 8500.0).map { meanSNess(it) }
        for (i in 1 until values.size) assertTrue(values[i] > values[i - 1], "sNess must grow with frequency: $values")
    }

    @Test
    fun sLikeAndShLikeHissLandOnTheirSides() {
        assertTrue(meanSNess(3500.0) < 0.2, "Ș-like hiss (3.5 kHz) should sit near 0")
        // A child's S sits higher than an adult's: the child norm puts this band noise past 0.5 only above 7 kHz.
        assertTrue(meanSNess(8500.0) > 0.7, "S-like hiss (8.5 kHz) should sit near 1 for a child")
        assertTrue(meanSNess(7000.0, Talker.Male) > 0.7, "S-like hiss (7 kHz) should sit near 1 for an adult voice")
        assertTrue(meanSNess(3500.0, Talker.Male) < 0.2)
    }

    @Test
    fun holdGrowsOnTargetAndStaysAtZeroOnTheOtherSide() {
        val p = LivePipeline()
        val holds = ArrayList<Float>()
        p.feed(Signals.concat(lead, Signals.hiss(8500.0, 1.0)), sTarget) { s, at -> if (at > leadEnd) holds += s.holdSeconds }
        assertTrue(holds.last() > 0.8f, "an S held for 1 s should give a hold near 1 s, got ${holds.last()}")
        for (i in 1 until holds.size) assertTrue(holds[i] >= holds[i - 1], "hold must not fall while S is held")

        val q = LivePipeline()
        var faults = 0
        q.feed(Signals.concat(lead, Signals.hiss(3500.0, 1.0)), sTarget) { s, at ->
            if (at > leadEnd + 4800) {
                assertEquals(0f, s.holdSeconds, "Ș on an S step must not fill the hold")
                if (s.fault == LiveFaults.OTHER_PLACE) faults++
            }
        }
        assertTrue(faults > 50, "Ș on an S step should report OTHER_PLACE")
    }

    @Test
    fun holdRestartsAfterABreak() {
        val p = LivePipeline()
        var peak = 0f
        p.feed(Signals.concat(lead, Signals.hiss(8500.0, 0.6), Signals.quiet(0.6, seed = 3), Signals.hiss(8500.0, 0.2, seed = 9)), sTarget) { s, _ ->
            peak = maxOf(peak, s.holdSeconds)
        }
        val last = p.board.latest()
        assertTrue(peak > 0.5f)
        assertTrue(last.holdSeconds < 0.25f, "the second hiss starts a new hold, got ${last.holdSeconds}")
        assertTrue(last.bestHoldSeconds >= peak - 1e-6f)
    }

    @Test
    fun voicedToneSpeaksWithPitchThenGoesQuiet() {
        val p = LivePipeline()
        val frames = ArrayList<Pair<Int, LiveSnapshot>>()
        p.feed(Signals.concat(lead, Signals.voiced(220.0, 1.0), Signals.quiet(1.0, seed = 5)), LiveTarget.Voice) { s, at ->
            frames += at to LiveSnapshot().copyFrom(s)
        }
        val inLead = frames.filter { it.first < leadEnd }
        val inTone = frames.filter { it.first > leadEnd + 9600 && it.first < leadEnd + 48000 }
        val after = frames.filter { it.first > leadEnd + 48000 + 24000 }
        assertTrue(inLead.none { it.second.speaking }, "silence is not speech")
        assertTrue(inTone.all { it.second.speaking }, "the tone is speech")
        assertTrue(inTone.all { kotlin.math.abs(it.second.pitchHz - 220f) < 5f }, "pitch should read 220 Hz")
        assertTrue(inTone.all { it.second.voicing > 0.85f })
        assertTrue(inTone.all { it.second.level > 0.5f })
        assertTrue(after.none { it.second.speaking }, "speaking ends after the hang time")
        assertTrue(after.all { it.second.level < 0.1f })
        assertTrue(inTone.last().second.holdSeconds > 0.7f, "a voice target holds while voiced")
    }

    @Test
    fun anUtteranceEndsWithAnEvent() {
        val p = LivePipeline()
        val verdicts = ArrayList<Pair<Boolean, Byte>>()
        p.events = LiveEvents { ok, fault, _, _ -> verdicts += ok to fault }
        p.feed(Signals.concat(lead, Signals.hiss(8500.0, 0.5), Signals.quiet(0.8, seed = 4)), sTarget)
        assertEquals(listOf(true to LiveFaults.NONE), verdicts)

        val q = LivePipeline()
        val wrong = ArrayList<Pair<Boolean, Byte>>()
        q.events = LiveEvents { ok, fault, _, _ -> wrong += ok to fault }
        q.feed(Signals.concat(lead, Signals.hiss(3500.0, 0.5), Signals.quiet(0.8, seed = 4)), sTarget)
        assertEquals(listOf(false to LiveFaults.OTHER_PLACE), wrong)
    }

    @Test
    fun everyHopPublishesInOrder() {
        val p = LivePipeline()
        var last = 0L
        var lastCapture = 0L
        p.feed(Signals.quiet(0.5), LiveTarget.AnySound) { s, _ ->
            assertEquals(last + 1, s.seq)
            assertTrue(s.captureNanos > lastCapture)
            last = s.seq
            lastCapture = s.captureNanos
        }
        assertEquals(50L, last, "0.5 s at 10 ms hops")
        assertEquals(50L, p.board.lastPublishedSeq)
        assertEquals(50L, p.stats.summary().hops)
    }

    @Test
    fun clippingAndNarrowBandAreFlagged() {
        val p = LivePipeline()
        val loud = FloatArray(4800) { if (it % 100 < 50) 1f else -1f }
        p.feed(Signals.concat(lead, loud), LiveTarget.AnySound)
        assertEquals(CaptureQualityCode.CLIPPING, p.board.latest().captureQuality)

        val q = LivePipeline()
        q.narrowBand = true
        q.feed(Signals.quiet(0.1), LiveTarget.AnySound)
        assertEquals(CaptureQualityCode.NARROW_BAND, q.board.latest().captureQuality)
    }

    @Test
    fun mouthFieldsAreTheLastValueHeld() {
        val p = LivePipeline()
        p.postMouth(0.4f, 0.8f, 123L)
        p.feed(Signals.quiet(0.05), LiveTarget.AnySound)
        val s = p.board.latest()
        assertEquals(0.4f, s.mouthOpen)
        assertEquals(0.8f, s.lipRound)
        assertEquals(123L, s.mouthNanos)
        p.feed(Signals.quiet(0.05), LiveTarget.AnySound, start = false)
        assertEquals(0.4f, p.board.latest().mouthOpen, "held between face frames")
    }

    @Test
    fun targetCanChangeMidTake() {
        val p = LivePipeline()
        p.feed(Signals.concat(lead, Signals.hiss(3500.0, 0.5)), sTarget)
        assertEquals(0f, p.board.latest().holdSeconds)
        p.setTarget(LiveTarget.Sibilant(SibilantSide.Sh, voiced = false))
        p.feed(Signals.hiss(3500.0, 0.5, seed = 11), sTarget, start = false)
        assertTrue(p.board.latest().holdSeconds > 0.3f, "Ș now counts")
    }

    @Test
    fun faultCodesFollowTheDspEnum() {
        assertEquals(LiveFault.OTHER_PLACE.ordinal.toByte(), LiveFaults.OTHER_PLACE)
        assertEquals(LiveFault.UNVOICED.ordinal.toByte(), LiveFaults.UNVOICED)
        assertEquals(LiveFault.NO_TRILL.ordinal.toByte(), LiveFaults.NO_TRILL)
        assertEquals(LiveFault.S_LIKE.ordinal.toByte(), LiveFaults.S_LIKE)
        assertEquals(LiveFault.NO_SOUND.ordinal.toByte(), LiveFaults.NO_SOUND)
        for (f in LiveFault.entries) assertEquals(f.rawValue, LiveFaults.rawName(f.ordinal.toByte()))
    }

    @Test
    fun aWavFileRunsThroughTheWholeChain() {
        val audio = Signals.concat(lead, Signals.hiss(8500.0, 0.8), Signals.quiet(0.8, seed = 2))
        val bytes = encodeWAV(audio, 48_000)
        val pipeline = LivePipeline()
        val verdicts = ArrayList<Boolean>()
        pipeline.events = LiveEvents { ok, _, _, _ -> verdicts += ok }
        val clip = LiveCapture.runToEnd(WavPcmSource.fromBytes(bytes), pipeline, sTarget)
        assertEquals(audio.size, clip.samples.size)
        assertEquals(48_000.0, clip.sampleRate)
        assertEquals("fixture", clip.audioSource)
        assertEquals(listOf(true), verdicts)
        val s = pipeline.board.latest()
        assertFalse(s.speaking, "stop() publishes a silent frame")
        assertEquals(LiveCapture.hopSize(48_000.0).let { (audio.size + it - 1) / it }.toLong() + 1, s.seq)
    }

    @Test
    fun theAnalysisThreadDrainsARealTimeSource() {
        val audio = Signals.concat(Signals.quiet(0.3), Signals.hiss(8500.0, 0.3))
        val capture = LiveCapture(LivePipeline())
        capture.start(ArrayPcmSource(audio, Signals.RATE, realTime = true), sTarget)
        Thread.sleep(800)
        val clip = capture.stop()
        assertEquals(audio.size, clip.samples.size)
        assertTrue(clip.startNanos > 0)
        assertFalse(capture.isRunning)
    }

    @Test
    fun theHopIsData() {
        // N3: at 0.01 s every 10 ms hop is analysed; at the old 512/48000 every other one.
        var changes100 = 0
        var changes50 = 0
        val noise = Signals.concat(lead, Signals.hiss(8500.0, 0.5))
        var prev = -1f
        LivePipeline(config = LiveConfig(analysisHopSeconds = 0.01)).feed(noise, sTarget) { s, at ->
            if (at > leadEnd + 4800) {
                if (s.sNess != prev) changes100++
                prev = s.sNess
            }
        }
        prev = -1f
        LivePipeline(config = LiveConfig(analysisHopSeconds = 512.0 / 48000.0)).feed(noise, sTarget) { s, at ->
            if (at > leadEnd + 4800) {
                if (s.sNess != prev) changes50++
                prev = s.sNess
            }
        }
        assertTrue(changes100 > changes50 * 3 / 2, "100 Hz analysis updates more often: $changes100 vs $changes50")
    }
}
