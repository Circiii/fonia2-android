package tech.fonia.speaking.coach

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the microphone says about delivery, live: pace (syllable nuclei on the vowel band, de Jong & Wempe 2009, made
 * streaming), loudness against the room and against his own habit, and the spread of his melody.
 *
 * Feed it every captured sample with [process] on ONE thread (the audio thread), together with the latest pitch of
 * the live analyser (0 = unvoiced). It allocates nothing per hop. Every [PUBLISH_HOPS] hops it publishes its readings
 * to volatile fields that any thread may read ([rate], [quiet], [pitchSpreadSt] …).
 *
 * Nuclei are found [CoachSpec.Pace.lookAheadSeconds] late (the right-hand dip must be seen), so the rate window ends
 * that much in the past.
 */
class AudioCoach(
    val sampleRate: Double,
    private val spec: CoachSpec = CoachSpec.v1,
    /** His speaking level from an earlier attempt on this phone (dBFS), used until his own is known. NaN = none. */
    calibratedReferenceDb: Double = Double.NaN,
) {
    private val hopSec = spec.hopSeconds
    private val hopSamples = max(1, (sampleRate * hopSec).roundToInt())
    private val pace = spec.pace
    private val voice = spec.voice

    // Filters: a 2nd-order high-pass and low-pass (RBJ Butterworth) for the vowel band.
    private val hp = Biquad.highPass(pace.vowelLowHz, sampleRate)
    private val lp = Biquad.lowPass(min(pace.vowelHighHz, sampleRate * 0.45), sampleRate)

    private var accFull = 0.0
    private var accVowel = 0.0
    private var accN = 0
    private var latestPitch = 0f

    /** Hops seen so far (absolute index of the next hop). */
    var hops = 0L
        private set

    // Rings, by absolute hop index modulo their size.
    private val ringSize = max(
        ((pace.windowSeconds + pace.lookAheadSeconds + 0.5) / hopSec).roundToInt(),
        ((spec.pitch.windowSeconds + 0.1) / hopSec).roundToInt(),
    ).coerceAtLeast(64)
    private val rawVowel = FloatArray(ringSize)
    private val smooth = FloatArray(ringSize)
    private val fullDb = FloatArray(ringSize)
    private val speakingAt = BooleanArray(ringSize)
    /** Above the floor by the margin, without the hang: what the pauses are measured on. */
    private val activeAt = BooleanArray(ringSize)
    private val voicedAt = BooleanArray(ringSize)
    private val nucleusAt = BooleanArray(ringSize)
    private val pitchSt = FloatArray(ringSize)

    // The phone model's vowels (one per syllable), when the app has the model: they arrive a few seconds late, in
    // chunks, from another thread. The activity of the hops is kept long enough to measure the speaking time of the
    // window those vowels cover.
    private val longSize = ((pace.windowSeconds + pace.modelStaleSeconds + 4.0) / hopSec).roundToInt()
    private val activeLong = BooleanArray(longSize)
    private val vowelTimes = DoubleArray(1024)
    private var vowelCount = 0L
    private var modelFrom = Double.NaN
    private var lastVowel = Double.NaN
    private var allGaps = 0L
    private var allGapTime = 0.0
    private var modelUntil = Double.NaN
    private val incoming = java.util.concurrent.ConcurrentLinkedQueue<DoubleArray>()
    private val scratch = FloatArray(ringSize)

    private val lookAhead = max(2, (pace.lookAheadSeconds / hopSec).roundToInt())
    private val valleyLook = max(2, (pace.valleyLookSeconds / hopSec).roundToInt())
    private val voicedReach = max(0, (pace.voicedReachSeconds / hopSec).roundToInt())
    private val minGapHops = max(1, (pace.minGapSeconds / hopSec).roundToInt())
    private val windowHops = (pace.windowSeconds / hopSec).roundToInt()
    private val levelHops = (voice.levelWindowSeconds / hopSec).roundToInt()
    private val pitchHops = (spec.pitch.windowSeconds / hopSec).roundToInt()

    private var floor = voice.initialFloorDb
    private var hangUntil = -1L
    private var lastNucleus = Long.MIN_VALUE / 2

    private val peakHistogram = DecayingHistogram(-100.0, 0.0, 1.0, pace.peakForgetSeconds / hopSec)
    private val levelHistogram = DecayingHistogram(-100.0, 0.0, 1.0, voice.referenceForgetSeconds / hopSec)
    private val calibrated = calibratedReferenceDb

    // Whole-attempt totals (audio thread; read after the take or through the published copies).
    private var speechHops = 0L
    private var voicedHops = 0L
    private var nucleiCount = 0L
    private var lastActiveHop = -1L
    private var phonationHops = 0L
    private val pauses = ArrayList<DoubleArray>()
    private var goodPauses = 0
    private var hesitations = 0
    private val allLevels = DecayingHistogram(-100.0, 0.0, 1.0, Double.POSITIVE_INFINITY)
    private val allPitch = DecayingHistogram(-24.0, 48.0, 0.25, Double.POSITIVE_INFINITY)

    // Published readings (any thread).
    /**
     * Articulation rate over the window, syllables per second of speech; NaN when too little speech. From the phone
     * model's vowels when they are fresh ([rateFromModel]), otherwise from the intensity peaks, scaled by the share
     * of syllables those find on real speech ([CoachSpec.Pace.envelopeRecall]).
     */
    @Volatile var rate: Double = Double.NaN
        private set

    /** Share of the window spent in pauses of at least [CoachSpec.Pace.pauseMinSeconds]. */
    @Volatile var pauseRatio: Double = Double.NaN
        private set

    /** Energy mean of the speech hops of the last [CoachSpec.Voice.levelWindowSeconds], dBFS; NaN = no speech. */
    @Volatile var levelDb: Double = Double.NaN
        private set

    @Volatile var floorDb: Double = voice.initialFloorDb
        private set

    /** His reference: a high percentile of his speech so far (or the calibrated value until then). NaN = unknown. */
    @Volatile var referenceDb: Double = calibratedReferenceDb
        private set

    @Volatile var quiet: Boolean = false
        private set

    /** 0…1 for the loudness gauge (his reference sits at about 0.7); NaN = nothing to show. */
    @Volatile var loudness01: Double = Double.NaN
        private set

    @Volatile var speaking: Boolean = false
        private set

    /** Seconds (of audio) of the last speech hop; -inf before any. */
    @Volatile var lastSpeechSeconds: Double = Double.NEGATIVE_INFINITY
        private set

    /** Robust spread of the melody in semitones ((p90 − p10) / 2.56); NaN with too little voice. */
    @Volatile var pitchSpreadSt: Double = Double.NaN
        private set

    @Volatile var speechSeconds: Double = 0.0
        private set

    @Volatile var nucleiTotal: Long = 0
        private set

    /** The room's level under the voice (the running noise floor), dBFS. */
    val roomDb: Double get() = floorDb

    /** True while [rate] comes from the phone model's vowels. */
    @Volatile var rateFromModel: Boolean = false
        private set

    /**
     * The vowels the phone model heard between [fromSeconds] and [untilSeconds] of this attempt's audio ([times] on the
     * same clock). Any thread; chunks in order, without gaps.
     */
    fun addVowels(fromSeconds: Double, untilSeconds: Double, times: DoubleArray) {
        val packet = DoubleArray(times.size + 2)
        packet[0] = fromSeconds
        packet[1] = untilSeconds
        System.arraycopy(times, 0, packet, 2, times.size)
        incoming.add(packet)
    }

    /** Seconds of audio seen. */
    val seconds: Double get() = hops * hopSec

    fun process(samples: FloatArray, offset: Int, count: Int, pitchHz: Float) {
        latestPitch = pitchHz
        var i = offset
        val end = offset + count
        while (i < end) {
            val x = samples[i].toDouble()
            val v = lp.step(hp.step(x))
            accFull += x * x
            accVowel += v * v
            accN += 1
            if (accN >= hopSamples) {
                hopDone(accFull / accN, accVowel / accN)
                accFull = 0.0
                accVowel = 0.0
                accN = 0
            }
            i++
        }
    }

    private fun db(meanSquare: Double): Double = 10.0 * log10(meanSquare + 1e-12)

    private fun hopDone(msFull: Double, msVowel: Double) {
        val h = hops
        val k = idx(h)
        val full = db(msFull)
        val vowel = db(msVowel)
        // The room's floor: falls fast to any quieter hop, rises slowly.
        floor = if (full < floor) floor + (full - floor) * voice.floorFallRate else floor + voice.floorRiseDbPerSecond * hopSec
        val active = full > floor + voice.vadMarginDb
        if (active) hangUntil = h + (voice.vadHangSeconds / hopSec).roundToInt()
        activeAt[k] = active
        activeLong[(hops % longSize).toInt()] = activeAt[k]
        val isSpeaking = h <= hangUntil
        val pitch = latestPitch
        val voiced = isSpeaking && pitch > 0f
        rawVowel[k] = vowel.toFloat()
        fullDb[k] = full.toFloat()
        speakingAt[k] = isSpeaking
        voicedAt[k] = voiced
        nucleusAt[k] = false
        pitchSt[k] = if (voiced) (12.0 * log2(pitch / spec.pitch.referenceHz)).toFloat() else Float.NaN
        if (isSpeaking) {
            speechHops++
            peakHistogram.add(vowel)
            if (!quiet) levelHistogram.add(full) // a quiet stretch does not become his reference
            allLevels.add(full)
            lastSpeechSeconds = h * hopSec
        }
        if (active) {
            if (lastActiveHop >= 0) {
                val gap = h - lastActiveHop - 1
                if (gap * hopSec >= pace.longPauseSeconds) pauses.add(doubleArrayOf((lastActiveHop + 1) * hopSec, h * hopSec))
                // Short silences between phrases are good pauses; longer ones (inside the talk) are hesitations.
                val gapSeconds = gap * hopSec
                if (gapSeconds >= pace.pauseMinSeconds && gapSeconds <= pace.goodPauseMaxSeconds) goodPauses++
                else if (gapSeconds > pace.goodPauseMaxSeconds) hesitations++
                if (gap < (pace.pauseMinSeconds / hopSec).roundToInt()) phonationHops += gap
            }
            phonationHops++
            lastActiveHop = h
        }
        if (voiced) {
            voicedHops++
            allPitch.add(pitchSt[k].toDouble())
        }
        peakHistogram.tick()
        levelHistogram.tick()
        // The hop before this one now has its 3-point smoothed value.
        if (h >= 1) {
            val c = h - 1
            val a = if (c >= 1) rawVowel[idx(c - 1)] else rawVowel[idx(c)]
            smooth[idx(c)] = (a + rawVowel[idx(c)] + rawVowel[k]) / 3f
        }
        // The candidate nucleus: lookAhead hops back, so its right-hand dip is already known.
        val cand = h - 1 - lookAhead
        if (cand >= valleyLook) detect(cand, h - 1)
        hops = h + 1
        if (hops % PUBLISH_HOPS == 0L) publish()
    }

    private fun detect(c: Long, newest: Long) {
        val sc = smooth[idx(c)]
        if (!speakingAt[idx(c)]) return
        if (!(sc >= smooth[idx(c - 1)] && sc > smooth[idx(c + 1)])) return
        val peak = peakHistogram.percentile(0.95)
        if (peak.isNaN() || sc < peak - pace.belowPeakDb) return
        var voiced = false
        for (j in c - voicedReach..min(newest, c + voicedReach)) if (voicedAt[idx(j)]) { voiced = true; break }
        if (!voiced) return
        var leftMin = sc
        var j = c - 1
        while (j >= c - valleyLook && j >= 0) {
            val s = smooth[idx(j)]
            leftMin = min(leftMin, s)
            if (s > sc) break
            j--
        }
        var rightMin = sc
        j = c + 1
        while (j <= min(newest, c + valleyLook)) {
            val s = smooth[idx(j)]
            rightMin = min(rightMin, s)
            if (s > sc) break
            j++
        }
        if (sc - leftMin < pace.minProminenceDb || sc - rightMin < pace.minProminenceDb) return
        if (c - lastNucleus < minGapHops) {
            // Two peaks too close: one syllable, keep the louder.
            if (smooth[idx(lastNucleus)] < sc && h0(lastNucleus)) {
                nucleusAt[idx(lastNucleus)] = false
                nucleusAt[idx(c)] = true
                lastNucleus = c
            }
            return
        }
        nucleusAt[idx(c)] = true
        lastNucleus = c
        nucleiCount++
    }

    /** The hop is still inside the rings. */
    private fun h0(h: Long) = hops - h < ringSize

    private fun idx(h: Long): Int = (h % ringSize).toInt()

    private fun publish() {
        val newest = hops - 1
        // Pace window: ends where nuclei are already decided.
        val wEnd = newest - lookAhead - 1
        val wStart = max(0L, wEnd - windowHops + 1)
        // Articulation rate (de Jong & Wempe): nuclei ÷ phonation time, where phonation = the span from the first to
        // the last active hop minus the silent runs of at least pauseMinSeconds inside it.
        var nuclei = 0
        var first = -1L
        var last = -1L
        var pauseHops = 0L
        var run = 0L
        val pauseMin = (pace.pauseMinSeconds / hopSec).roundToInt()
        var h = wStart
        while (h <= wEnd) {
            val k = idx(h)
            if (nucleusAt[k]) nuclei++
            if (activeAt[k]) {
                if (first < 0) first = h
                if (last >= 0 && run >= pauseMin) pauseHops += run
                last = h
                run = 0
            } else if (first >= 0) {
                run++
            }
            h++
        }
        val phonation = if (first < 0) 0.0 else ((last - first + 1) - pauseHops) * hopSec
        val envelopeRate = if (phonation >= pace.minSpeechInWindowSeconds) nuclei / phonation / pace.envelopeRecall else Double.NaN
        val modelRate = modelRate()
        rateFromModel = !modelRate.isNaN()
        rate = if (rateFromModel) modelRate else envelopeRate
        val span = (wEnd - wStart + 1).coerceAtLeast(1)
        pauseRatio = if (first >= 0) pauseHops.toDouble() / span else Double.NaN

        // Loudness: energy mean of the recent speech hops.
        var sum = 0.0
        var n = 0
        h = max(0L, newest - levelHops + 1)
        while (h <= newest) {
            val k = idx(h)
            if (speakingAt[k]) {
                sum += Math.pow(10.0, fullDb[k] / 10.0)
                n++
            }
            h++
        }
        val level = if (n >= (0.25 / hopSec)) 10.0 * log10(sum / n) else Double.NaN
        levelDb = level
        floorDb = floor
        val own = if (speechHops * hopSec >= voice.referenceAfterSpeechSeconds) levelHistogram.percentile(voice.referencePercentile) else Double.NaN
        val reference = if (!own.isNaN()) own else calibrated
        referenceDb = reference
        quiet = !level.isNaN() && (level < floor + voice.minSnrDb || (!reference.isNaN() && level < reference - voice.quietBelowReferenceDb))
        loudness01 = when {
            level.isNaN() -> Double.NaN
            !reference.isNaN() -> ((level - (reference - voice.gaugeBelowReferenceDb)) / (voice.gaugeBelowReferenceDb + voice.gaugeAboveReferenceDb)).coerceIn(0.0, 1.0)
            else -> ((level - floor) / (voice.minSnrDb + 15.0)).coerceIn(0.0, 1.0)
        }
        speaking = speakingAt[idx(newest)]
        speechSeconds = speechHops * hopSec
        nucleiTotal = nucleiCount

        // Melody spread over the pitch window.
        var m = 0
        h = max(0L, newest - pitchHops + 1)
        while (h <= newest) {
            val v = pitchSt[idx(h)]
            if (!v.isNaN()) scratch[m++] = v
            h++
        }
        pitchSpreadSt = if (m * hopSec >= spec.pitch.minVoicedSeconds) spread(scratch, m) else Double.NaN
    }

    /** The rate over the last window the phone model covered; NaN without fresh vowels or with too little speech. */
    private fun modelRate(): Double {
        while (true) {
            val packet = incoming.poll() ?: break
            if (modelFrom.isNaN()) modelFrom = packet[0]
            modelUntil = packet[1]
            for (i in 2 until packet.size) {
                if (!lastVowel.isNaN()) {
                    allGaps++
                    allGapTime += min(packet[i] - lastVowel, pace.vowelGapCapSeconds)
                }
                lastVowel = packet[i]
                vowelTimes[(vowelCount % vowelTimes.size).toInt()] = packet[i]
                vowelCount++
            }
        }
        if (modelUntil.isNaN() || seconds - modelUntil > pace.modelStaleSeconds) return Double.NaN
        // Syllables per second of speaking, from the gaps between vowels alone: a gap longer than
        // [CoachSpec.Pace.vowelGapCapSeconds] holds a pause and counts only up to the cap. No loudness threshold is
        // involved, so a noisy room does not shorten the speaking time (the bench's outdoor speaker read 7.6 before).
        val from = max(modelFrom, modelUntil - pace.windowSeconds)
        var gaps = 0
        var time = 0.0
        var later = Double.NaN
        var i = vowelCount - 1
        val stop = max(0L, vowelCount - vowelTimes.size)
        while (i >= stop) {
            val t = vowelTimes[(i % vowelTimes.size).toInt()]
            if (t < from) break
            if (!later.isNaN()) {
                gaps++
                time += min(later - t, pace.vowelGapCapSeconds)
            }
            later = t
            i--
        }
        return if (gaps >= pace.minVowelGaps && time > 0) gaps / time else Double.NaN
    }

    /** Totals of the whole attempt; call after the last [process] (same thread, or once audio stopped). */
    fun summary(): AudioSummary {
        publish()
        val speechSec = speechHops * hopSec
        return AudioSummary(
            seconds = seconds,
            speechSeconds = speechSec,
            nuclei = if (vowelCount > 0) vowelCount else nucleiCount,
            articulationRate = when {
                phonationHops * hopSec <= 1.0 -> Double.NaN
                // The model's vowels: the same capped-gap measure over the whole talk.
                vowelCount > 1 && allGaps > 0 && allGapTime > 0 -> allGaps / allGapTime
                else -> nucleiCount / (phonationHops * hopSec) / pace.envelopeRecall
            },
            phonationSeconds = phonationHops * hopSec,
            medianLevelDb = allLevels.percentile(0.5),
            floorDb = floor,
            pitchSpreadSt = if (voicedHops * hopSec >= spec.pitch.minVoicedSeconds) {
                (allPitch.percentile(0.9) - allPitch.percentile(0.1)) / 2.56
            } else {
                Double.NaN
            },
            longPauses = pauses.map { Span(it[0], it[1]) },
            goodPauses = goodPauses,
            hesitations = hesitations,
        )
    }

    companion object {
        /** Readings are published every 5 hops (50 ms). */
        const val PUBLISH_HOPS = 5L

        /** (p90 − p10) / 2.56 of the first [n] values (sorted in place). */
        internal fun spread(values: FloatArray, n: Int): Double {
            if (n < 2) return Double.NaN
            java.util.Arrays.sort(values, 0, n)
            val p10 = values[((n - 1) * 0.1).roundToInt()]
            val p90 = values[((n - 1) * 0.9).roundToInt()]
            return (p90 - p10) / 2.56
        }
    }
}

/** What the whole attempt sounded like. */
data class AudioSummary(
    val seconds: Double,
    val speechSeconds: Double,
    val nuclei: Long,
    /** Syllables per second of phonation (pauses ≥ pauseMinSeconds excluded) over the whole attempt. */
    val articulationRate: Double,
    val phonationSeconds: Double,
    val medianLevelDb: Double,
    val floorDb: Double,
    val pitchSpreadSt: Double,
    val longPauses: List<Span>,
    /** Silences of a breath or a full stop (pauseMinSeconds … goodPauseMaxSeconds). */
    val goodPauses: Int = 0,
    /** Silences longer than goodPauseMaxSeconds inside the talk. */
    val hesitations: Int = 0,
)

/** A stretch of the attempt, seconds from its start. */
data class Span(val start: Double, val end: Double) {
    val seconds: Double get() = end - start
}

/** RBJ cookbook biquad, transposed direct form II. */
internal class Biquad private constructor(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double,
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun step(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    companion object {
        private const val Q = 0.7071067811865476

        fun lowPass(hz: Double, rate: Double): Biquad {
            val w = 2 * PI * hz / rate
            val alpha = sin(w) / (2 * Q)
            val c = cos(w)
            val a0 = 1 + alpha
            return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }

        fun highPass(hz: Double, rate: Double): Biquad {
            val w = 2 * PI * hz / rate
            val alpha = sin(w) / (2 * Q)
            val c = cos(w)
            val a0 = 1 + alpha
            return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }
    }
}

/**
 * A histogram that forgets: every [tick] multiplies the weights by exp(−1/[forgetTicks]). Percentiles are read from
 * the bins (bin centres), so a query costs one pass over ~100 bins and nothing is allocated.
 */
internal class DecayingHistogram(private val low: Double, high: Double, private val step: Double, forgetTicks: Double) {
    private val bins = DoubleArray(((high - low) / step).roundToInt() + 1)
    private val decay = if (forgetTicks.isInfinite()) 1.0 else exp(-1.0 / forgetTicks)
    private var total = 0.0
    private var ticks = 0

    fun add(value: Double) {
        if (value.isNaN()) return
        val i = ((value - low) / step).roundToInt().coerceIn(0, bins.size - 1)
        bins[i] += 1.0
        total += 1.0
    }

    fun tick() {
        if (decay == 1.0) return
        // Decay in batches of 10 ticks: the same result, a tenth of the work.
        if (++ticks < 10) return
        ticks = 0
        val d = Math.pow(decay, 10.0)
        for (i in bins.indices) bins[i] *= d
        total *= d
    }

    fun percentile(p: Double): Double {
        if (total < 1e-9) return Double.NaN
        val target = total * p
        var acc = 0.0
        for (i in bins.indices) {
            acc += bins[i]
            if (acc >= target) return low + i * step
        }
        return low + (bins.size - 1) * step
    }

    val weight: Double get() = total
}

/** sqrt that tolerates tiny negatives from rounding. */
internal fun safeSqrt(x: Double): Double = sqrt(max(0.0, x))
