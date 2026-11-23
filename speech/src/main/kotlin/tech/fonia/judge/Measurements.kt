package tech.fonia.judge

import tech.fonia.dsp.Biquad
import tech.fonia.dsp.ClipSummary
import tech.fonia.dsp.DSPThresholds
import tech.fonia.dsp.FricativeMatch
import tech.fonia.dsp.SibilantPlace
import tech.fonia.dsp.SoundOutcome
import tech.fonia.dsp.TimingEnvelopes
import tech.fonia.dsp.affricateCues
import tech.fonia.dsp.alignFricatives
import tech.fonia.dsp.bandFlatness
import tech.fonia.dsp.dipBetween
import tech.fonia.dsp.judgeRhotic
import tech.fonia.dsp.judgeSibilant
import tech.fonia.dsp.lateralCues
import tech.fonia.dsp.onsetFricationMs
import tech.fonia.dsp.relativeLevelDb
import tech.fonia.dsp.segmentSpectrum
import tech.fonia.dsp.spectralMoments
import tech.fonia.dsp.uvularCues
import tech.fonia.dsp.voicingCues
import tech.fonia.model.ErrorKind
import tech.fonia.model.SoundId
import java.text.Normalizer
import kotlin.math.log10

/** How each of the nine sounds is measured. */
internal enum class Family { Sibilant, Affricate, Rhotic, Lateral }

internal data class SoundTraits(val family: Family, val graphemes: List<String>, val place: SibilantPlace?, val voiced: Boolean)

internal fun traits(sound: SoundId): SoundTraits = when (sound) {
    SoundId.S -> SoundTraits(Family.Sibilant, listOf("s"), SibilantPlace.S, false)
    SoundId.Sh -> SoundTraits(Family.Sibilant, listOf("ș"), SibilantPlace.SH, false)
    SoundId.Z -> SoundTraits(Family.Sibilant, listOf("z"), SibilantPlace.S, true)
    SoundId.J -> SoundTraits(Family.Sibilant, listOf("j"), SibilantPlace.SH, true)
    SoundId.Ts -> SoundTraits(Family.Affricate, listOf("ț"), SibilantPlace.S, false)
    SoundId.Ce -> SoundTraits(Family.Affricate, listOf("ce", "ci"), SibilantPlace.SH, false)
    SoundId.Ge -> SoundTraits(Family.Affricate, listOf("ge", "gi"), SibilantPlace.SH, true)
    SoundId.R -> SoundTraits(Family.Rhotic, listOf("r"), null, true)
    SoundId.L -> SoundTraits(Family.Lateral, listOf("l"), null, true)
}

/** Feature names (the `inputs` of `spec/fusion/<sound>.json` refer to these). */
object Features {
    const val PRESENT = "present"
    const val TOWARD = "toward"
    const val S_NESS = "sNess"
    const val FRIC_DUR = "fricDurMs"
    const val SEG_DUR = "segDurMs"
    const val GAP = "gapMs"
    const val GAP_RAW = "gapRawMs"
    const val BURST = "burstDb"
    const val RISE = "riseMs"
    const val ONSET_FRIC = "onsetFricMs"
    const val ABOVE_FLOOR = "aboveFloorDb"
    const val REL_LEVEL = "relLevelDb"
    const val FLATNESS = "flatness"
    const val FLAT_HIGH = "flat5to11"
    const val VOICING = "voicing"
    const val VOICED_FRAC = "voicedFrac"
    const val HNR = "hnrDb"
    const val VOICE_BAR = "voiceBarDb"
    const val M1 = "m1Hz"
    const val M2 = "m2Hz"
    const val SKEW = "skew"
    const val KURT = "kurt"
    const val SLOPE = "slopeDbPerKhz"
    const val LAT_LOW = "latLowDb"
    const val LAT_TOP = "latTopDb"
    const val LAT_PEAK = "latPeakDb"
    const val CENTROID = "centroidHz"
    const val PEAK = "peakHz"
    const val BAND_RATIO = "bandRatioDb"
    const val R_TAP = "rTap"
    const val R_EVENTS = "rEvents"
    const val R_OBSERVABLE = "rObservable"
    const val DIP_DB = "dipDb"
    const val DIP_MS = "dipMs"
    const val UV_NOISE = "uvNoiseDb"
    const val UV_PEAK = "uvPeakHz"
    const val L_TAP = "lTap"
    const val BAND_SNR = "cap.bandSnrDb"
    const val BAND_RANGE = "cap.bandRangeDb"
    const val BANDWIDTH = "cap.bandwidthHz"
    const val SPEECH_SECONDS = "cap.speechSeconds"
    const val F0 = "cap.f0MedianHz"
    const val LPR_PREFIX = "lpr."

    /** Phone-model summary inputs of the full head: `PhoneEvidence.gop` and `fitPerLabel`. */
    const val ZIPA_GOP = "zipa.gop"
    const val ZIPA_FIT = "zipa.fit"

    /** Bumped whenever a feature's definition changes; saved in the trace and in each fusion spec. */
    const val VERSION = "e2.v0"
}

/** One target occurrence's features; NaN = not measured. */
internal class Occurrence(val features: MutableMap<String, Float>, val segment: Pair<Double, Double>?)

/** P0–P3 on one take, before any head runs. */
class Measured internal constructor(
    val request: JudgeRequest,
    val gate: RetakeReason?,
    val capture: CaptureReport?,
    internal val occurrences: List<Occurrence>,
    val global: Map<String, Float>,
    val outcome: SoundOutcome?,
    val blocked: Set<ErrorKind>,
    val notes: List<String>,
    val micros: Map<String, Long>,
    /** The part of [blocked] that waits only for the phoneme model (plan 03 §3.8, E2): a reliable full head lifts it. */
    val pending: Set<ErrorKind> = emptySet(),
)

internal fun romanian(text: String): String =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFC).replace("ş", "ș").replace("ţ", "ț")

private val vowels = setOf('a', 'ă', 'â', 'e', 'i', 'î', 'o', 'u')

/** Phonemes ≈ letters, with "ch" and "gh" counted once (good enough for a 0.5×–2.5× duration gate). */
internal fun phonemeCount(text: String): Int {
    val t = romanian(text).filter { it.isLetter() }
    return t.length - Regex("[cg]h").findAll(t).count()
}

/** For each target letter in a word: how many vowel groups come before it, and whether vowels flank it. */
internal data class LetterSlot(val vowelGroupsBefore: Int, val intervocalic: Boolean, val vowelGroups: Int)

internal fun letterSlots(text: String, letter: Char): List<LetterSlot> {
    val t = romanian(text)
    val groups = Regex("[aăâeiîou]+").findAll(t).count()
    val out = ArrayList<LetterSlot>()
    var before = 0
    var inVowel = false
    for (i in t.indices) {
        val c = t[i]
        if (c in vowels) {
            if (!inVowel) before++
            inVowel = true
        } else {
            inVowel = false
        }
        if (c == letter && (i == 0 || t[i - 1] != letter)) {
            val v1 = i > 0 && t[i - 1] in vowels
            val v2 = i + 1 < t.length && t[i + 1] in vowels
            out.add(LetterSlot(before, v1 && v2, groups))
        }
    }
    return out
}

internal class Measurer(private val population: PopulationThresholds, private val dsp: DSPThresholds) {
    private val cap = population.capture
    private val m = population.measure

    fun captureReport(
        clipSeconds: Double, sampleRate: Double, dropped: Int, summary: ClipSummary?,
    ): CaptureReport {
        val q = summary?.quality
        val bandwidth = q?.bandwidthHz ?: 0f
        val tier = when {
            sampleRate < cap.minSampleRateHz || bandwidth < cap.placeBandwidthHz -> "presenceOnly"
            bandwidth < cap.fullBandwidthHz -> "noLateral"
            else -> "full"
        }
        return CaptureReport(
            durationSeconds = clipSeconds, sampleRate = sampleRate, speechSeconds = q?.speechSeconds ?: 0.0,
            speechRmsDb = q?.speechRmsDb ?: -120f, dynamicRangeDb = q?.dynamicRangeDb ?: 0f,
            clippedFraction = q?.clippedFraction ?: 0f, bandwidthHz = bandwidth, dropped = dropped, bandSnrDb = Float.NaN,
            f0MedianHz = summary?.pitch?.medianHz ?: 0f, bandwidthTier = tier,
        )
    }

    /** 4–8 kHz levels of 20 ms frames (10 ms hop) over the clip: the floor, the range and the SNR of a span. */
    class BandLevels(private val band: FloatArray, private val rate: Double, levels: List<Float>) {
        val floorDb: Float = if (levels.isEmpty()) Float.NaN else levels.sorted()[(levels.size - 1) / 10]
        /** 95th percentile minus floor: how far anything rises above the noise in this band. */
        val rangeDb: Float = if (levels.isEmpty()) Float.NaN else levels.sorted()[(levels.size - 1) * 95 / 100] - floorDb

        fun level(a: Int, b: Int): Float {
            var e = 0.0
            for (i in a until b) e += band[i].toDouble() * band[i]
            return if (b > a) (10 * log10(e / (b - a) + 1e-14)).toFloat() else -140f
        }

        /** Level of [start, end) seconds over the floor. */
        fun snr(start: Double, end: Double): Float {
            val a = (start * rate).toInt().coerceIn(0, band.size)
            val b = (end * rate).toInt().coerceIn(a, band.size)
            return level(a, b) - floorDb
        }
    }

    fun bandLevels(pcm: FloatArray, rate: Double): BandLevels {
        if (rate / 2 <= cap.bandSnrHighHz) return BandLevels(FloatArray(0), rate, emptyList())
        val band = Biquad.lowpass(cap.bandSnrHighHz.toDouble(), rate).run(Biquad.highpass(cap.bandSnrLowHz.toDouble(), rate).run(pcm))
        val frame = (0.02 * rate).toInt()
        val hop = (0.01 * rate).toInt()
        val tmp = BandLevels(band, rate, emptyList())
        val levels = ArrayList<Float>()
        var i = 0
        while (i + frame <= band.size) { levels.add(tmp.level(i, i + frame)); i += hop }
        return BandLevels(band, rate, levels)
    }

    fun sibilants(
        request: JudgeRequest, pcm: FloatArray, rate: Double, summary: ClipSummary, env: TimingEnvelopes,
        notes: MutableList<String>, blocked: MutableSet<ErrorKind>,
    ): Pair<List<Occurrence>, SoundOutcome> {
        val t = traits(request.sound)
        val outcome = judgeSibilant(request.text, t.graphemes, summary, dsp)
        val wanted = t.graphemes.map { romanian(it) }.toSet()
        val all = alignFricatives(request.text, summary, dsp).filter { it.expected.grapheme in wanted }
        if (all.isEmpty()) {
            notes.add("noTarget")
            return emptyList<Occurrence>() to outcome
        }
        val chosen: List<FricativeMatch> = when (request.position) {
            Position.Initial -> listOf(all.first())
            Position.Final -> listOf(all.last())
            Position.Medial -> all.filter { it.expected.position > 0f && !it.expected.isWordFinal }.ifEmpty { all }
            Position.Any -> all
        }
        val canPlace = summary.quality.canJudgeSibilants
        if (!canPlace) {
            notes.add("lowBandwidth")
            blocked += setOf(ErrorKind.PlaceSwap, ErrorKind.AffricatePlace, ErrorKind.Lateral, ErrorKind.Interdental)
        } else if (summary.quality.bandwidthHz < cap.fullBandwidthHz) {
            blocked += ErrorKind.Lateral
        }
        val result = chosen.map { match ->
            val f = HashMap<String, Float>()
            val initial = match.expected.position == 0f && match === all.first() && request.level != Level.Sentence
            val d = match.detected
            if (d == null) {
                f[Features.PRESENT] = 0f
                if (initial) {
                    val lead = onsetFricationMs(env, summary.speechStart - 0.1, summary.speechStart + m.onsetSearchSeconds)
                    f[Features.ONSET_FRIC] = lead
                    f[Features.FRIC_DUR] = if (lead.isNaN()) 0f else lead
                    val c = affricateCues(env, summary.speechStart - 0.03, summary.speechStart + 0.03, t.voiced, m.lookbackSeconds, m.gapMarginDb)
                    f[Features.BURST] = c.burstDb
                } else {
                    f[Features.FRIC_DUR] = 0f
                }
                Occurrence(f, null)
            } else {
                f[Features.PRESENT] = 1f
                val c = affricateCues(env, d.start - 0.02, d.end + 0.02, t.voiced, m.lookbackSeconds, m.gapMarginDb)
                val start = if (c.startSeconds.isNaN()) d.start else c.startSeconds
                val end = if (c.endSeconds.isNaN()) d.end else c.endSeconds
                // The longer of the refined and the detector's span: the refined one is strict on purpose and can lose a
                // frication whose peak sits near a vowel edge.
                f[Features.FRIC_DUR] = maxOf(c.fricDurMs, ((d.end - d.start) * 1000).toFloat())
                f[Features.SEG_DUR] = ((d.end - d.start) * 1000).toFloat()
                f[Features.GAP_RAW] = c.gapMs
                f[Features.GAP] = if (c.gapValid) c.gapMs else Float.NaN
                f[Features.BURST] = c.burstDb
                f[Features.RISE] = c.riseMs
                f[Features.VOICE_BAR] = c.voiceBarDb
                if (initial) f[Features.ONSET_FRIC] = onsetFricationMs(env, summary.speechStart - 0.1, end + 0.05)
                f[Features.S_NESS] = d.sNess
                val toward = if (t.place == SibilantPlace.S) d.sNess else 1f - d.sNess
                // Validity on the longer of the refined and the detector's own span: the refined one is strict on purpose.
                val span = maxOf(c.fricDurMs, ((d.end - d.start) * 1000).toFloat())
                f[Features.TOWARD] = if (canPlace && span >= m.minPlaceMs) toward else Float.NaN
                f[Features.ABOVE_FLOOR] = d.aboveFloorDb
                f[Features.FLATNESS] = d.flatness
                f[Features.CENTROID] = d.centroidHz
                f[Features.PEAK] = d.peakHz
                f[Features.BAND_RATIO] = d.bandRatioDb
                val coreA = (d.coreStart * rate).toInt()
                val coreB = (d.coreEnd * rate).toInt()
                val spectrum = segmentSpectrum(pcm, rate, coreA, coreB - coreA)
                val moments = spectralMoments(spectrum)
                f[Features.M1] = moments.m1Hz
                f[Features.M2] = moments.m2Hz
                f[Features.SKEW] = moments.skewness
                f[Features.KURT] = moments.kurtosis
                f[Features.SLOPE] = moments.slopeDbPerKhz
                f[Features.FLAT_HIGH] = bandFlatness(spectrum)
                val lat = lateralCues(spectrum)
                f[Features.LAT_LOW] = lat.latLowDb
                f[Features.LAT_TOP] = lat.latTopDb
                f[Features.LAT_PEAK] = lat.latPeakDb
                val ref = referenceSpan(env, start, end, request.level == Level.Isolated)
                if (ref != null) {
                    val rs = segmentSpectrum(pcm, rate, (ref.first * rate).toInt(), ((ref.second - ref.first) * rate).toInt())
                    f[Features.REL_LEVEL] = relativeLevelDb(spectrum, rs)
                }
                // Voicing on the detector's core (the middle of the frication): the edges carry the vowels' voice.
                val v = voicingCues(pcm, rate, d.coreStart, d.coreEnd, pitch = dsp.pitch)
                f[Features.VOICED_FRAC] = v.voicedFrac
                f[Features.HNR] = v.hnrDb
                val voicingJudged = t.voiced && !match.expected.isWordFinal && span >= m.minVoicingMs
                f[Features.VOICING] = if (voicingJudged) v.voicedFrac else Float.NaN
                Occurrence(f, start to end)
            }
        }
        if (t.voiced && result.all { it.features[Features.VOICING]?.isNaN() != false }) blocked += ErrorKind.Devoicing
        // The DSP detector misses weak voiced frication, so for Z, J and GE "not found" is not evidence of an omission or
        // a stop: those wait for the phoneme model.
        if (t.voiced && result.any { it.features[Features.PRESENT] == 0f }) blocked += setOf(ErrorKind.Omission, ErrorKind.Stopping)
        if (result.all { it.features[Features.TOWARD]?.isNaN() != false }) {
            blocked += setOf(ErrorKind.PlaceSwap, ErrorKind.AffricatePlace)
        }
        return result to outcome
    }

    /** The loudest 40 ms of voice within ±[MeasureThresholds.neighbourSeconds] outside the frication; at the isolated level the quietest 40 ms of the take. */
    private fun referenceSpan(env: TimingEnvelopes, start: Double, end: Double, isolated: Boolean): Pair<Double, Double>? {
        val low = env.low
        val w = (0.04 / low.hopSeconds).toInt()
        if (low.db.size <= w) return null
        if (isolated) {
            var best = 0
            var bestDb = Float.MAX_VALUE
            for (i in 0 until low.db.size - w step 5) {
                val mean = (i until i + w).sumOf { env.mid.db[it].toDouble() } / w
                if (mean < bestDb) { bestDb = mean.toFloat(); best = i }
            }
            return low.time(best) to low.time(best + w)
        }
        val candidates = ArrayList<Int>()
        val n = (m.neighbourSeconds / low.hopSeconds).toInt()
        val a = low.index(start)
        val b = low.index(end)
        for (i in kotlin.math.max(0, a - n) until a - w) candidates.add(i)
        for (i in b until kotlin.math.min(low.db.size - w, b + n)) candidates.add(i)
        if (candidates.isEmpty()) return null
        val best = candidates.maxBy { i -> (i until i + w).sumOf { low.db[it].toDouble() } }
        return low.time(best) to low.time(best + w)
    }

    fun rhotic(
        request: JudgeRequest, pcm: FloatArray, rate: Double, summary: ClipSummary, env: TimingEnvelopes, notes: MutableList<String>,
        blocked: MutableSet<ErrorKind>,
    ): Pair<List<Occurrence>, SoundOutcome?> {
        val isR = request.sound == SoundId.R
        val letter = if (isR) 'r' else 'l'
        val slots = letterSlots(request.text, letter)
        val outcome = if (isR) judgeRhotic(request.text, summary, dsp) else null
        if (slots.isEmpty()) {
            notes.add("noTarget")
            return emptyList<Occurrence>() to outcome
        }
        val f = HashMap<String, Float>()
        val events = summary.taps.size + summary.trills.size
        val observable = slots.count { it.intervocalic }
        f[Features.R_EVENTS] = events.toFloat()
        f[Features.R_OBSERVABLE] = observable.toFloat()
        val hasR = romanian(request.text).contains('r')
        if (isR) {
            f[Features.R_TAP] = when {
                events > 0 -> 1f
                observable > 0 -> 0f
                else -> Float.NaN
            }
            if (events == 0 && observable == 0) notes.add("rhoticContext")
        } else {
            // A tap in a word without R is an L said as R; with an R in the word it proves nothing.
            f[Features.L_TAP] = if (hasR) Float.NaN else if (events > 0) 1f else 0f
        }
        // The amplitude dip at the first intervocalic slot, when the syllable nuclei line up with the vowel groups.
        val slot = slots.firstOrNull { it.intervocalic }
        val nuclei = summary.syllables
        var segment: Pair<Double, Double>? = null
        if (slot != null && nuclei.size == slot.vowelGroups && slot.vowelGroupsBefore in 1 until nuclei.size) {
            val a = nuclei[slot.vowelGroupsBefore - 1]
            val b = nuclei[slot.vowelGroupsBefore]
            val dip = dipBetween(env, a, b)
            f[Features.DIP_DB] = dip.depthDb
            f[Features.DIP_MS] = dip.widthMs
            val mid = (a + b) / 2
            val spectrum = segmentSpectrum(pcm, rate, ((mid - 0.03) * rate).toInt(), (0.06 * rate).toInt())
            val uv = uvularCues(spectrum)
            f[Features.UV_NOISE] = uv.uvNoiseDb
            f[Features.UV_PEAK] = uv.uvPeakHz
            segment = a to b
        } else if (slot != null) {
            notes.add("nucleiMismatch")
        }
        // Without the dip, "no tap" is only absence of evidence: R's error kinds wait for the phoneme model.
        if (isR && f[Features.DIP_DB]?.isNaN() != false) {
            blocked += setOf(ErrorKind.RtoL, ErrorKind.RtoGlide, ErrorKind.RtoVowel, ErrorKind.Omission)
        }
        return listOf(Occurrence(f, segment)) to outcome
    }
}
