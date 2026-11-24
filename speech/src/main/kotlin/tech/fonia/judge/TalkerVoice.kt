package tech.fonia.judge

import tech.fonia.live.Clip
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Who is speaking, heard from the voice itself rather than taken from the profile (owner, 2026-11-23: a teenager with
 * a man's voice picks "Un copil" and must still be judged on HIS voice). The S/Ș boundary depends on the group, so the
 * group decides which priors the judge and the live letter use. Nothing is ever rejected for being an adult.
 *
 * The rule is on the median F0 of a take's voiced frames: under [MAN_BELOW_HZ] a man, under [WOMAN_BELOW_HZ] a woman or
 * a teenager, otherwise a child. A take with no voice ("sss") says nothing and keeps the last group.
 */
object TalkerVoice {
    const val MAN_BELOW_HZ = 165f
    const val WOMAN_BELOW_HZ = 255f

    /** Below this or above [MAX_HZ] a median is not a speaking voice (a hum, a whistle, a tracker octave error). */
    const val MIN_HZ = 60f
    const val MAX_HZ = 600f

    /** The group of one F0, or null when there is no usable voice in it. */
    fun groupOf(f0Hz: Float): TalkerGroup? = when {
        f0Hz.isNaN() || f0Hz < MIN_HZ || f0Hz > MAX_HZ -> null
        f0Hz < MAN_BELOW_HZ -> TalkerGroup.Man
        f0Hz < WOMAN_BELOW_HZ -> TalkerGroup.Woman
        else -> TalkerGroup.Child
    }

    /** A typical F0 of each group, used as the starting point when only the profile is known. */
    fun typicalHz(group: TalkerGroup): Float = when (group) {
        TalkerGroup.Man -> 120f
        TalkerGroup.Woman -> 210f
        TalkerGroup.Child -> 280f
    }

    /** Median of the voiced values (> 0) of a list of per-frame pitches; NaN when none is voiced. */
    fun medianVoiced(pitches: FloatArray, count: Int = pitches.size, minVoiced: Int = 1): Float {
        val voiced = FloatArray(count)
        var n = 0
        for (i in 0 until count) {
            val p = pitches[i]
            if (p > 0f && !p.isNaN()) voiced[n++] = p
        }
        if (n == 0 || n < minVoiced) return Float.NaN
        val sorted = voiced.copyOf(n).also { it.sort() }
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
    }
}

/**
 * The talker group of one person over a session, smoothed: the median of the last [WINDOW] voiced takes (log F0), with
 * a little hysteresis so a single high "Șoc!" does not flip a man into a woman. The first voiced take replaces the
 * profile's prior outright (the prior is only a guess). Unvoiced takes change nothing.
 *
 * Not thread-safe; the session calls it from one coroutine. [encode] / [decode] keep it per profile between sessions.
 */
class TalkerTracker(prior: TalkerGroup) {
    var group: TalkerGroup = prior
        private set

    /** True once at least one take had a voice: the group then comes from the voice, not the profile. */
    val heard: Boolean get() = recent.isNotEmpty()

    /** The smoothed F0 in Hz, or NaN before any voiced take. */
    val smoothedHz: Float get() = if (recent.isEmpty()) Float.NaN else exp(medianOf(recent)).toFloat()

    private val recent = ArrayDeque<Double>()

    /** One take's median F0 (0 or NaN for an unvoiced take). Returns the group to use from now on. */
    fun observe(f0Hz: Float): TalkerGroup {
        if (TalkerVoice.groupOf(f0Hz) == null) return group
        recent.addLast(ln(f0Hz.toDouble()))
        while (recent.size > WINDOW) recent.removeFirst()
        val hz = smoothedHz
        val raw = TalkerVoice.groupOf(hz) ?: return group
        if (recent.size == 1 || raw == group) {
            group = raw
            return group
        }
        // Switch only when the smoothed voice is clearly past the boundary it crosses.
        val boundary = when (setOf(raw, group)) {
            setOf(TalkerGroup.Man, TalkerGroup.Woman) -> TalkerVoice.MAN_BELOW_HZ
            setOf(TalkerGroup.Woman, TalkerGroup.Child) -> TalkerVoice.WOMAN_BELOW_HZ
            else -> Float.NaN // man ↔ child: two boundaries apart, always clear
        }
        if (boundary.isNaN() || abs(ln(hz / boundary)) >= HYSTERESIS) group = raw
        return group
    }

    /** `"<group>;<f0>,<f0>…"`, for a preference string. */
    fun encode(): String = group.raw + ";" + recent.joinToString(",") { "%.1f".format(java.util.Locale.ROOT, exp(it)) }

    companion object {
        const val WINDOW = 5

        /** ±4 % in F0 (log units) around a boundary before the group changes. */
        const val HYSTERESIS = 0.04

        fun decode(text: String?, prior: TalkerGroup): TalkerTracker {
            val t = TalkerTracker(prior)
            if (text.isNullOrBlank()) return t
            val (g, list) = text.split(';', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            TalkerGroup.entries.firstOrNull { it.raw == g }?.let { t.group = it }
            list.split(',').mapNotNull { it.toFloatOrNull() }.filter { TalkerVoice.groupOf(it) != null }
                .takeLast(WINDOW).forEach { t.recent.addLast(ln(it.toDouble())) }
            return t
        }

        private fun medianOf(values: Collection<Double>): Double {
            val s = values.sorted()
            val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
        }
    }
}

/**
 * [TakeJudge.measure] with the talker heard from this take: measures with the tracker's group, feeds the take's F0
 * ([TalkerEstimator], octave-safe) to [tracker], and measures again with the new group if the voice moved it (≈ 20 ms more, only when it changes).
 * The returned [Measured.request] carries the group that was used.
 */
fun TakeJudge.measureForVoice(request: JudgeRequest, clip: Clip, tracker: TalkerTracker): Measured {
    val first = measure(request.copy(talker = tracker.group), clip)
    // Only a take that passed the capture and word gates says who spoke: a beep, a cough or a wrong word does not.
    if (first.gate != null) return first
    // v2: the octave-safe estimate (YIN + the DSP tracker), not the DSP median alone, which halves some women's F0.
    val f0 = TalkerEstimator.estimate(clip, first.global[Features.F0] ?: Float.NaN).f0Hz
    val before = first.request.talker
    val after = tracker.observe(f0)
    return if (after == before) first else measure(request.copy(talker = after), clip)
}
