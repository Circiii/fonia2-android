package tech.fonia.dsp

import kotlin.math.abs
import kotlin.math.sqrt

/** The S/Ș boundary for one talker, from a group prior or from that talker's own anchors. */
data class SibilantNorm(
    val centroidHz: Float,
    val peakHz: Float,
    val bandRatioDb: Float,
    val tiltDb: Float,
    val centroidScaleHz: Float,
    val peakScaleHz: Float,
    val bandRatioScaleDb: Float,
    val weightCentroid: Float,
    val weightPeak: Float,
    val weightBandRatio: Float,
    val gain: Float,
    /** Half-width of the band around sNess 0.5 that answers `unclear`. */
    val uncertainty: Float,
    val source: Source,
    val tokenCount: Int,
) {
    enum class Source(val rawValue: String) {
        PRIOR("prior"),
        FITTED("fitted"),
    }

    /** The group prior, used until a talker has enough anchors. */
    constructor(group: VoiceGroup, thresholds: DSPThresholds.Sibilant) : this(
        when (group) {
            VoiceGroup.CHILD -> thresholds.child
            VoiceGroup.FEMALE -> thresholds.female
            VoiceGroup.MALE -> thresholds.male
        },
        thresholds,
    )

    private constructor(prior: DSPThresholds.SibilantPrior, thresholds: DSPThresholds.Sibilant) : this(
        centroidHz = prior.centroidHz, peakHz = prior.peakHz, bandRatioDb = prior.bandRatioDb, tiltDb = prior.tiltDb,
        centroidScaleHz = prior.centroidScaleHz, peakScaleHz = prior.peakScaleHz, bandRatioScaleDb = prior.bandRatioScaleDb,
        weightCentroid = thresholds.weightCentroid, weightPeak = thresholds.weightPeak,
        weightBandRatio = thresholds.weightBandRatio, gain = thresholds.gain,
        uncertainty = thresholds.priorUncertainty, source = Source.PRIOR, tokenCount = 0,
    )

    /** 0 clearly Ș-like, 1 clearly S-like. Several cues, so one noisy feature cannot flip the verdict. */
    fun sNess(centroidHz: Float, peakHz: Float, bandRatioDb: Float): Float {
        val centroidZ = (centroidHz - this.centroidHz) / centroidScaleHz
        val peakZ = (peakHz - this.peakHz) / peakScaleHz
        val ratioZ = (this.bandRatioDb - bandRatioDb) / bandRatioScaleDb
        return sigmoid(gain * (weightCentroid * centroidZ + weightPeak * peakZ + weightBandRatio * ratioZ))
    }

    fun sNess(features: FrameFeatures): Float =
        sNess(centroidHz = features.centroidHz, peakHz = features.peakHz, bandRatioDb = features.bandRatioDb)

    fun isBorderline(sNess: Float): Boolean = abs(sNess - 0.5f) < uncertainty

    /** `null` inside the uncertainty band: abstention is an answer. */
    fun place(sNess: Float): SibilantPlace? {
        if (isBorderline(sNess)) return null
        return if (sNess >= 0.5f) SibilantPlace.S else SibilantPlace.SH
    }
}

/** A token an adult or clinician validated as a correct production of `place`. */
data class SibilantAnchor(
    val place: SibilantPlace,
    val centroidHz: Float,
    val peakHz: Float,
    val bandRatioDb: Float,
    val tiltDb: Float,
)

/**
 * Learns one talker's S/Ș boundary. Holliday et al. 2015 reach 93% token prediction at age 5 with
 * a talker-specific boundary; the fit here keeps that logic (each talker's own midpoint) without a
 * mixed model. Until enough anchors exist the group prior answers, with a wider `unclear` band.
 *
 * `anchors` is an immutable list that `add` replaces, so a copy never shares changes with the
 * original, as a Swift struct would not.
 */
data class TalkerCalibration(
    var anchors: List<SibilantAnchor> = emptyList(),
    var ageYears: Int? = null,
) {
    fun add(anchor: SibilantAnchor, thresholds: DSPThresholds = DSPThresholds.v1) {
        val updated = ArrayList(anchors)
        updated.add(anchor)
        val limit = thresholds.sibilant.maxAnchorsPerPlace
        val samePlace = updated.count { it.place == anchor.place }
        if (samePlace > limit) {
            val oldest = updated.indexOfFirst { it.place == anchor.place }
            if (oldest >= 0) updated.removeAt(oldest)
        }
        anchors = updated.toList()
    }

    /** Swift `add(_ segment:, as place:)`. */
    fun add(segment: FricativeSegment, place: SibilantPlace, thresholds: DSPThresholds = DSPThresholds.v1) {
        add(
            SibilantAnchor(
                place = place, centroidHz = segment.centroidHz, peakHz = segment.peakHz,
                bandRatioDb = segment.bandRatioDb, tiltDb = segment.tiltDb,
            ),
            thresholds,
        )
    }

    fun norm(group: VoiceGroup, thresholds: DSPThresholds = DSPThresholds.v1): SibilantNorm {
        val prior = priorNorm(group, thresholds.sibilant)
        return fitted(prior, thresholds.sibilant) ?: prior
    }

    private fun priorNorm(group: VoiceGroup, thresholds: DSPThresholds.Sibilant): SibilantNorm {
        val norm = SibilantNorm(group, thresholds).copy(tokenCount = anchors.size)
        val ageYears = ageYears
        if (group != VoiceGroup.CHILD || ageYears == null) return norm
        val band = thresholds.childAgeBands.firstOrNull { ageYears <= it.maxYears } ?: return norm
        return norm.copy(
            centroidScaleHz = norm.centroidScaleHz * band.scaleFactor,
            peakScaleHz = norm.peakScaleHz * band.scaleFactor,
            bandRatioScaleDb = norm.bandRatioScaleDb * band.scaleFactor,
            uncertainty = band.uncertainty,
        )
    }

    private class Stats(val s: Float, val sh: Float, val spread: Float)

    private fun fitted(prior: SibilantNorm, thresholds: DSPThresholds.Sibilant): SibilantNorm? {
        val sTokens = anchors.filter { it.place == SibilantPlace.S }
        val shTokens = anchors.filter { it.place == SibilantPlace.SH }
        if (anchors.size < thresholds.minTokensToFit ||
            sTokens.size < thresholds.minTokensPerPlace ||
            shTokens.size < thresholds.minTokensPerPlace
        ) {
            return null
        }

        fun stats(feature: (SibilantAnchor) -> Float): Stats {
            val sValues = sTokens.map(feature).toFloatArray()
            val shValues = shTokens.map(feature).toFloatArray()
            val sSpread = standardDeviation(sValues)
            val shSpread = standardDeviation(shValues)
            return Stats(median(sValues), median(shValues), sqrt((sSpread * sSpread + shSpread * shSpread) / 2f))
        }
        val centroid = stats { it.centroidHz }
        val peak = stats { it.peakHz }
        val ratio = stats { it.bandRatioDb }
        val tilt = stats { it.tiltDb }
        // Anchors the wrong way round are mislabelled; better to keep the prior than to invert verdicts.
        if (!(centroid.s > centroid.sh)) return null

        val centroidScale = max(centroid.spread, thresholds.minCentroidScaleHz)
        val separation = (centroid.s - centroid.sh) / centroidScale
        return prior.copy(
            centroidHz = (centroid.s + centroid.sh) / 2f,
            peakHz = (peak.s + peak.sh) / 2f,
            bandRatioDb = (ratio.s + ratio.sh) / 2f,
            tiltDb = (tilt.s + tilt.sh) / 2f,
            centroidScaleHz = centroidScale,
            peakScaleHz = max(peak.spread, thresholds.minPeakScaleHz),
            bandRatioScaleDb = max(ratio.spread, thresholds.minBandRatioScaleDb),
            source = SibilantNorm.Source.FITTED,
            uncertainty = if (separation >= thresholds.minSeparation) thresholds.fittedUncertainty else prior.uncertainty,
        )
    }

    /** Swift's synthesized Codable: `{"anchors": [...], "ageYears": n}`, `ageYears` left out when nil. */
    fun toJson(): String {
        val json = LinkedHashMap<String, Any?>()
        json["anchors"] = anchors.map { anchor ->
            linkedMapOf<String, Any?>(
                "place" to anchor.place.rawValue,
                "centroidHz" to Json.number(anchor.centroidHz),
                "peakHz" to Json.number(anchor.peakHz),
                "bandRatioDb" to Json.number(anchor.bandRatioDb),
                "tiltDb" to Json.number(anchor.tiltDb),
            )
        }
        ageYears?.let { json["ageYears"] = Json.number(it) }
        return Json.writeCompact(json)
    }

    companion object {
        fun fromJson(json: String): TalkerCalibration {
            val members = Json.parse(json).asObject("TalkerCalibration")
            val anchors = members.member("anchors").asArray("anchors").map { element ->
                val anchor = element.asObject("anchors")
                val raw = anchor.member("place").asString("place")
                SibilantAnchor(
                    place = SibilantPlace.fromRawValue(raw)
                        ?: throw JsonException("dataCorrupted: cannot initialize SibilantPlace from \"$raw\""),
                    centroidHz = anchor.member("centroidHz").asFloat("centroidHz"),
                    peakHz = anchor.member("peakHz").asFloat("peakHz"),
                    bandRatioDb = anchor.member("bandRatioDb").asFloat("bandRatioDb"),
                    tiltDb = anchor.member("tiltDb").asFloat("tiltDb"),
                )
            }
            val age = members["ageYears"]
            return TalkerCalibration(anchors = anchors, ageYears = if (age == null) null else age.asInt("ageYears"))
        }
    }
}

/**
 * One frame's frication reading. Shared by the live path and by segment detection. Mutable so the
 * live path can reuse one instance; see `FrameFeatures`.
 */
internal data class FricationFrame(
    var present: Boolean = false,
    var voiced: Boolean = false,
    var sNess: Float = 0.5f,
    var strength: Float = 0f,
    var diffuse: Boolean = false,
) {
    fun setNone(): FricationFrame {
        present = false
        voiced = false
        sNess = 0.5f
        strength = 0f
        diffuse = false
        return this
    }

    companion object {
        val none: FricationFrame get() = FricationFrame()
    }
}

internal fun classifyFrication(
    features: FrameFeatures, hfFloorDb: Float, clarity: Float, norm: SibilantNorm,
    thresholds: DSPThresholds.Fricative,
): FricationFrame = classifyFrication(features, hfFloorDb, clarity, norm, thresholds, FricationFrame())

/** Fills `into` and returns it; allocates nothing. */
internal fun classifyFrication(
    features: FrameFeatures, hfFloorDb: Float, clarity: Float, norm: SibilantNorm,
    thresholds: DSPThresholds.Fricative, into: FricationFrame,
): FricationFrame {
    val above = features.hfDb - hfFloorDb
    val voiced = clarity > thresholds.voicedClarity
    val share = if (voiced) thresholds.minHfShareVoiced else thresholds.minHfShareUnvoiced
    val overVowels = features.hfDb - features.vowelDb
    val weakHiss = features.centroidHz > thresholds.weakHissCentroidHz && overVowels > thresholds.weakHissMinHfOverVowelDb
    if (!(above > thresholds.presentAboveFloorDb &&
            (features.hfShare > share || overVowels > thresholds.minHfOverVowelDb || weakHiss))
    ) {
        return into.setNone()
    }
    into.present = true
    into.voiced = voiced
    into.sNess = norm.sNess(features)
    into.strength = clamp01((above - thresholds.presentAboveFloorDb) / thresholds.strengthRangeDb)
    into.diffuse = isDiffuse(features, above, norm, thresholds)
    return into
}

/** Weak and shapeless: TH-like or a soft S. Sits between the two places, so tilt says nothing either. */
internal fun isDiffuse(
    features: FrameFeatures, aboveFloorDb: Float, norm: SibilantNorm, thresholds: DSPThresholds.Fricative,
): Boolean =
    features.flatness > thresholds.diffuseFlatness &&
        abs(features.tiltDb - norm.tiltDb) < thresholds.diffuseTiltMarginDb &&
        aboveFloorDb < thresholds.diffuseMaxAboveFloorDb

/** Which place a Romanian sound expects. */
fun expectedPlace(forSound: String): SibilantPlace? = when (forSound.lowercase()) {
    "s", "z", "ț" -> SibilantPlace.S
    "ș", "j", "ce", "ci", "ge", "gi" -> SibilantPlace.SH
    else -> null
}

fun isVoicedSibilant(sound: String): Boolean = sound.lowercase() in listOf("z", "j", "ge", "gi")
