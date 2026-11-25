package tech.fonia.dsp

// How the frication of a take starts: an affricate (CE, GE, Ț) closes the tongue and then lets the air out, so its
// frication jumps in, often on a short burst, after a closure; a fricative (Ș, J, S) builds up from silence or out of
// the vowel. Word-initially the closure of a voiceless affricate is the silence before the word and cannot be told from
// it, but the jump and the burst still can, and a voiced one is often prevoiced (a voice bar under 1 kHz while the
// > 2 kHz band is still closed). Trace and opt-in judge gates only (`judge2.affricate`); not in the iOS DSP.

/** NaN = not measurable on this take. */
data class AffricateOnset(
    /**
     * Rise of the > 2 kHz envelope over the 10 ms that end where it first comes within 6 dB of the frication plateau,
     * dB, capped at 40: a release jumps (≥ 30), a fricative ramp of 30 ms or more climbs about 10 or less.
     */
    val attackDb: Float,
    /**
     * Strongest > 2 kHz value of the first 10 ms of frication minus the median of the 15–45 ms after the onset, dB: a
     * release starts at (or, with a burst, over) the level of the frication that follows; a ramp starts under it.
     */
    val spikeDb: Float,
    /**
     * The voice bar right before the frication (< 1 kHz voiced, > 2 kHz still 20 dB under the plateau), ms, when
     * silence comes before it (word-initial prevoicing of GE). NaN when a vowel runs into it: the closure after a vowel
     * is `AffricateCues.gapMs`.
     */
    val leadMs: Float,
    /**
     * 0 = fricative onset … 1 = stop onset: the mean of ramps on [attackDb] (10 → 30 dB), [spikeDb] (−2 → +3 dB),
     * the closure `gapMs` when one of 10 ms or more was found (10 → 40 ms, double weight) and, for voiced targets,
     * [leadMs] (10 → 40 ms, double weight). A shorter or zero closure is not counted: on the eval's prompt voice the
     * valid closure was 0 ms in most good CE and GE takes, so a zero says the closure was not found, not that there
     * was none. Untuned: the ramps are where the synthetic cases split, not a fit on takes.
     */
    val stopOnset: Float,
    /** Where the frication first came within 6 dB of its plateau, seconds. */
    val onsetSeconds: Double,
) {
    companion object {
        val NONE = AffricateOnset(Float.NaN, Float.NaN, Float.NaN, Float.NaN, Double.NaN)
    }
}

/**
 * [AffricateOnset] of the frication in [fricStart, fricEnd] (the refined span of [affricateCues]). [gapMs] is the
 * closure `affricateCues` measured, only when it is valid (something audible before it); NaN otherwise.
 */
fun affricateOnset(
    env: TimingEnvelopes,
    fricStart: Double,
    fricEnd: Double,
    voiced: Boolean = false,
    gapMs: Float = Float.NaN,
): AffricateOnset {
    val h = env.high
    val l = env.low
    val n = h.db.size
    val hop = h.hopSeconds
    if (n < 2 || fricStart.isNaN() || fricEnd.isNaN()) return AffricateOnset.NONE
    fun hops(seconds: Double): Int = kotlin.math.max(1, (seconds / hop + 0.5).toInt())
    val s = h.index(fricStart)
    val e = kotlin.math.min(n - 1, h.index(fricEnd))
    if (e - s < hops(0.010)) return AffricateOnset.NONE
    val plateau = h.db.copyOfRange(s, e + 1).also { it.sort() }.let { it[it.size * 3 / 4] }
    // Frication dominates the voice band (voiced: 12 dB under it allowed, as in affricateCues).
    val margin = if (voiced) -12f else 0f
    fun near(i: Int): Boolean = h.db[i] >= plateau - 6f && h.db[i] - l.db[i] > margin
    // Onset: the first value of the frication's run within 6 dB of the plateau, searched from 30 ms before the span.
    var r = -1
    for (i in kotlin.math.max(0, s - hops(0.030))..e) if (near(i)) { r = i; break }
    if (r < 0) return AffricateOnset.NONE
    while (r > 0 && near(r - 1)) r--
    val w = hops(0.010)
    val attack = if (r - w < 0) Float.NaN else kotlin.math.min(40f, h.db[r] - h.db[r - w])
    var first = -Float.MAX_VALUE
    for (i in kotlin.math.max(0, r - hops(0.002))..kotlin.math.min(n - 1, r + w)) first = kotlin.math.max(first, h.db[i])
    val q0 = r + hops(0.015)
    val q1 = kotlin.math.min(e, r + hops(0.045))
    val spike = if (q1 - q0 < hops(0.005)) Float.NaN else {
        val after = h.db.copyOfRange(q0, q1 + 1).also { it.sort() }
        first - after[after.size / 2]
    }
    // Voice lead: back over the rise (within 20 dB of the plateau), then over the voice bar; it counts only when
    // silence bounds it, so a vowel before the target is never read as prevoicing.
    var k = r
    while (k > 0 && h.db[k - 1] >= plateau - 20f) k--
    var j = k
    while (j > 0 && l.db[j - 1] > env.lowFloorDb + 15f && l.db[j - 1] > h.db[j - 1] && h.db[j - 1] < plateau - 20f) j--
    var silentBefore = false
    for (i in kotlin.math.max(0, j - hops(0.020)) until j) if (l.db[i] <= env.lowFloorDb + 10f) silentBefore = true
    val lead = if (silentBefore) ((k - j) * hop * 1000).toFloat() else Float.NaN
    return AffricateOnset(attack, spike, lead, stopOnset(attack, spike, gapMs, if (voiced) lead else Float.NaN), h.time(r))
}

/** The [AffricateOnset.stopOnset] mix; NaN when neither the attack nor the spike was measured. A closure under 10 ms is left out. */
internal fun stopOnset(attackDb: Float, spikeDb: Float, gapMs: Float, leadMs: Float): Float {
    if (attackDb.isNaN() && spikeDb.isNaN()) return Float.NaN
    fun ramp(x: Float, from: Float, to: Float): Float = ((x - from) / (to - from)).coerceIn(0f, 1f)
    var sum = 0f
    var weight = 0f
    fun add(x: Float, from: Float, to: Float, w: Float) {
        if (x.isNaN()) return
        sum += w * ramp(x, from, to)
        weight += w
    }
    add(attackDb, 10f, 30f, 1f)
    add(spikeDb, -2f, 3f, 1f)
    if (gapMs >= 10f) add(gapMs, 10f, 40f, 2f)
    add(leadMs, 10f, 40f, 2f)
    return sum / weight
}
