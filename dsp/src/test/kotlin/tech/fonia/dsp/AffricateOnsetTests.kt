package tech.fonia.dsp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [affricateOnset] on synthetic stop-then-frication and ramped-frication onsets, as the judge measures them. */
class AffricateOnsetTests {
    private fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }

    private fun mix(a: FloatArray, b: FloatArray): FloatArray = FloatArray(maxOf(a.size, b.size)) {
        (if (it < a.size) a[it] else 0f) + (if (it < b.size) b[it] else 0f)
    }

    private fun pad(seconds: Double = 0.3) = withNoise(silence(seconds), amp = 0.0003f)

    /** Ș-like frication, [seconds] long, whose amplitude climbs linearly over the first [rampMs] (0 = abrupt). */
    private fun frication(seconds: Double, rampMs: Double = 0.0, amp: Float = 0.12f): FloatArray {
        val noise = bandNoise(seconds, 2500f, 9000f, amp = amp, seed = 9)
        val n = sampleCount(rampMs / 1000)
        return FloatArray(noise.size) { noise[it] * if (it < n) it.toFloat() / n else 1f }
    }

    /** A stop release: 4 ms of broadband noise over the frication's level. */
    private fun burst() = bandNoise(0.004, 500f, 12000f, amp = 0.3f, seed = 21)

    /** A voice bar: the closure of a voiced stop, only the first two harmonics of 120 Hz. */
    private fun voiceBar(seconds: Double): FloatArray {
        val out = FloatArray(sampleCount(seconds))
        addSine(out, 120f, 0f, 0.05f, testRate)
        addSine(out, 240f, 0f, 0.02f, testRate)
        return out
    }

    /** Voiced frication: the hiss over a low voice. */
    private fun voicedFrication(seconds: Double, rampMs: Double = 0.0): FloatArray =
        mix(frication(seconds, rampMs, amp = 0.08f), voiceBar(seconds).let { v -> FloatArray(v.size) { v[it] * 2f } })

    /**
     * What the judge does: refine the span with [affricateCues], then read the onset over the union of the refined and
     * the detector's span (a burst over voiced frication cuts the refined one short), with the valid closure.
     */
    private fun onset(clip: FloatArray, from: Double, to: Double, voiced: Boolean = false): AffricateOnset {
        val env = TimingEnvelopes.of(clip, testRate)
        val c = affricateCues(env, from - 0.02, to + 0.02, voiced)
        return affricateOnset(env, minOf(c.startSeconds, from), maxOf(c.endSeconds, to), voiced, if (c.gapValid) c.gapMs else Float.NaN)
    }

    @Test
    fun wordInitialReleaseJumpsAndARampDoesNot() {
        // "ce" from silence: the release (burst then frication) against "șe": the hiss swelling over 50 ms.
        val stop = onset(concat(pad(), burst(), frication(0.09), vowel(0.25), pad()), 0.3, 0.394)
        val ramp = onset(concat(pad(), frication(0.15, rampMs = 50.0), vowel(0.25), pad()), 0.3, 0.45)
        assertTrue(stop.attackDb >= 30f, "stop $stop")
        assertTrue(ramp.attackDb <= 10f, "ramp $ramp")
        assertTrue(stop.spikeDb > ramp.spikeDb + 2f, "spike stop $stop ramp $ramp")
        assertTrue(stop.stopOnset >= 0.8f, "stop $stop")
        assertTrue(ramp.stopOnset <= 0.2f, "ramp $ramp")
    }

    @Test
    fun theAttackFallsAsTheRampGrows() {
        var last = Float.MAX_VALUE
        for (ms in listOf(0.0, 20.0, 40.0, 80.0)) {
            val o = onset(concat(pad(), frication(0.16, rampMs = ms), vowel(0.25), pad()), 0.3, 0.46)
            assertTrue(o.attackDb <= last + 1f, "ramp $ms ms: $o after $last")
            last = o.attackDb
        }
        assertTrue(last < 6f, "80 ms ramp attack $last")
    }

    @Test
    fun theOnsetIsWhereTheFricationArrives() {
        val o = onset(concat(pad(), burst(), frication(0.09), vowel(0.25), pad()), 0.3, 0.394)
        assertEquals(0.3, o.onsetSeconds, 0.006)
    }

    @Test
    fun prevoicingBeforeAVoicedReleaseIsTheLead() {
        // "ge" with a Romanian voice lead: 80 ms of voice bar, the release, voiced frication.
        val dzh = onset(concat(pad(), voiceBar(0.08), burst(), voicedFrication(0.09), vowel(0.25), pad()), 0.38, 0.474, voiced = true)
        assertEquals(80.0, dzh.leadMs.toDouble(), 10.0, "lead $dzh")
        assertTrue(dzh.stopOnset >= 0.8f, "dʒ $dzh")
        // "je": voice and hiss start together, the hiss swelling over 50 ms.
        val zh = onset(concat(pad(), voicedFrication(0.15, rampMs = 50.0), vowel(0.25), pad()), 0.3, 0.45, voiced = true)
        assertTrue(zh.leadMs < 10f, "lead $zh")
        assertTrue(zh.stopOnset <= 0.2f, "ʒ $zh")
    }

    @Test
    fun aVowelBeforeTheTargetIsNotALead() {
        val o = onset(concat(pad(), vowel(0.2), voicedFrication(0.12, rampMs = 30.0), vowel(0.2), pad()), 0.5, 0.62, voiced = true)
        assertTrue(o.leadMs.isNaN(), "lead $o")
    }

    @Test
    fun aClosureAfterAVowelCountsAndNoClosureDoesNot() {
        // "dulce" against "așa": vowel, 40 ms closure, release; vowel straight into a 40 ms swell of hiss.
        val closed = onset(concat(pad(), vowel(0.2), pad(0.04), burst(), frication(0.09), vowel(0.2), pad()), 0.54, 0.634)
        val open = onset(concat(pad(), vowel(0.2), frication(0.15, rampMs = 40.0), vowel(0.2), pad()), 0.5, 0.65)
        assertTrue(closed.stopOnset >= 0.8f, "closed $closed")
        assertTrue(open.stopOnset <= 0.25f, "open $open")
    }

    @Test
    fun nothingToMeasureIsNaN() {
        val env = TimingEnvelopes.of(concat(pad(), vowel(0.2), pad()), testRate)
        val tooShort = affricateOnset(env, 0.35, 0.352)
        assertTrue(tooShort.stopOnset.isNaN() && tooShort.attackDb.isNaN(), "$tooShort")
        assertTrue(affricateOnset(env, Double.NaN, 0.4).stopOnset.isNaN())
        assertTrue(stopOnset(Float.NaN, Float.NaN, 30f, 30f).isNaN())
        // The closure and the lead weigh double; each part is a clamped ramp.
        assertEquals(1f, stopOnset(40f, 3f, Float.NaN, Float.NaN), 1e-6f)
        assertEquals(0.6f, stopOnset(40f, Float.NaN, 40f, 0f), 1e-6f)
        assertEquals(0.5f, stopOnset(20f, Float.NaN, Float.NaN, Float.NaN), 1e-6f)
    }
}
