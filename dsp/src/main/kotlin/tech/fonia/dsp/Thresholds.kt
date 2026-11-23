package tech.fonia.dsp

/**
 * Every tunable number in the package. Values are data so the evaluation harness can fit them and
 * a clinician panel can adjust them; `version` says which defaults a stored set started from.
 *
 * Nothing here was fitted on child speech. Values marked "web" were tuned on a few adult clips in
 * the previous implementation, and values marked "proposal" have no published source at all.
 *
 * Swift's structs are values; these are immutable data classes, changed with `copy`, so a shared
 * `v1` can never be altered by a caller.
 */
data class DSPThresholds(
    val version: Int = currentVersion,
    val analysis: Analysis = Analysis(),
    val bands: Bands = Bands(),
    val pitch: Pitch = Pitch(),
    val envelope: Envelope = Envelope(),
    val fricative: Fricative = Fricative(),
    val sibilant: Sibilant = Sibilant(),
    val lateral: Lateral = Lateral(),
    val trill: Trill = Trill(),
    val tap: Tap = Tap(),
    val syllable: Syllable = Syllable(),
    val hesitation: Hesitation = Hesitation(),
    val live: Live = Live(),
    val capture: Capture = Capture(),
    val judge: Judge = Judge(),
    val stress: Stress = Stress(),
    val prosody: Prosody = Prosody(),
    val delivery: Delivery = Delivery(),
) {
    companion object {
        const val currentVersion = 1
        val v1 = DSPThresholds()

        /**
         * Decodes a JSON override. Keys the JSON leaves out keep their `v1` value, so a stored file
         * survives new fields being added.
         */
        fun load(from: ByteArray): DSPThresholds = load(String(from, Charsets.UTF_8))

        fun load(json: String): DSPThresholds {
            val base = Json.parse(Json.writeCompact(ThresholdsCodec.thresholds.encode(v1)))
            val override = Json.parse(json)
            val merged = deepMerge(base, override)
            return ThresholdsCodec.thresholds.decode(merged, "DSPThresholds")
        }

        private fun deepMerge(base: Any?, override: Any?): Any? {
            if (base !is Map<*, *> || override !is Map<*, *>) return override
            val result = LinkedHashMap<Any?, Any?>(base)
            for ((key, value) in override) {
                result[key] = if (result.containsKey(key)) deepMerge(result[key], value) else value
            }
            return result
        }
    }

    fun jsonData(): ByteArray = Json.writePretty(ThresholdsCodec.thresholds.encode(this)).toByteArray(Charsets.UTF_8)

    data class Analysis(
        val hopSeconds: Double = 0.01,
        /** Rounded up to a power of two, which gives 2048 at 44.1 and 48 kHz. */
        val frameSeconds: Double = 0.04,
        /** Clip floor and peak come from the level distribution, so a stray click cannot set them. */
        val floorPercentile: Float = 0.08f,
        val peakPercentile: Float = 0.98f,
        /** Speaking threshold above the floor: 30% of the clip's dynamic range, kept within 9 to 18 dB (web). */
        val speechMarginShare: Float = 0.3f,
        val speechMarginMinDb: Float = 9f,
        val speechMarginMaxDb: Float = 18f,
        /** Quiet tails and breaths after a word are not speech, however silent the room is. */
        val speechMaxBelowPeakDb: Float = 30f,
        /** Bridges stop closures so they are not counted as pauses (web). */
        val speechHangSeconds: Double = 0.15,
        val minPauseSeconds: Double = 0.25,
    )

    data class Bands(
        val levelLowHz: Float = 80f,
        val levelHighHz: Float = 15000f,
        val hfLowHz: Float = 2500f,
        val lfLowHz: Float = 40f,
        val lfHighHz: Float = 500f,
        val vowelLowHz: Float = 300f,
        val vowelHighHz: Float = 2500f,
        /**
         * Holliday et al. 2015 measured 0.3 to 20 kHz; the low edge is raised to 2 kHz so the
         * voicing of Z and J cannot pull the centroid down.
         */
        val centroidLowHz: Float = 2000f,
        val centroidHighHz: Float = 20000f,
        val peakSmoothingHz: Float = 400f,
        val tiltLowHz: Float = 2000f,
        val tiltMidHz: Float = 5500f,
        val ratioLowHz: Float = 5000f,
        val ratioMidHz: Float = 11000f,
        val ratioHighHz: Float = 20000f,
        /** Flatness stops at 11 kHz so a codec's lowpass cannot read as a shaped spectrum. */
        val flatnessLowHz: Float = 2000f,
        val flatnessHighHz: Float = 11000f,
        /** Flatness over 500 Hz blocks: on raw bins the estimator's own variance caps white noise near 0.56. */
        val flatnessBlockHz: Float = 500f,
    )

    data class Pitch(
        val minHz: Float = 60f,
        /** The children's search in the web pipeline goes up to 1000 Hz. */
        val maxHz: Float = 1000f,
        /** Absolute dip threshold; de Cheveigné & Kawahara 2002 find the exact value is not critical near 0.1. */
        val yinThreshold: Float = 0.1f,
        /**
         * A dip at twice the period must be this much deeper to replace the first one, which
         * corrects octave-up errors on vowels whose first formant sits on the second harmonic.
         */
        val octaveCorrectionMargin: Float = 0.04f,
        /**
         * With no dip under the threshold, the earliest dip this close to the deepest one wins, so
         * a chance minimum at twice the period does not read as an octave down.
         */
        val fallbackDipMargin: Float = 0.05f,
        val windowSeconds: Double = 0.025,
        /**
         * YIN runs on a decimated, low-passed signal: speech F0 needs no more, and it keeps
         * fricative hiss out of the voicing decision for Z and J.
         */
        val decimatedRateHz: Double = 12000.0,
        val lowpassHz: Double = 4000.0,
        /** Minimum clarity (1 minus the YIN dip) for the strict track used by R flanks and syllables. */
        val strictClarity: Float = 0.8f,
        /** Melody needs a continuous contour more than a perfect one. */
        val melodyClarity: Float = 0.6f,
        val melodyMinHz: Float = 60f,
        val melodyMaxHz: Float = 700f,
        /** A frame further than this from its neighbours' median is an octave error (web). */
        val octaveOutlierSt: Float = 5f,
        val octaveNeighbourFrames: Int = 5,
        val melodyFoldUpSt: Float = 8f,
        val melodyFoldDownSt: Float = -10f,
        val melodyOutlierSt: Float = 6f,
        val melodyNeighbourFrames: Int = 15,
        /** Below this much voiced track the statistics are noise. */
        val minFramesForStats: Int = 6,
    )

    data class Envelope(
        /** 128 samples at 48 kHz, which leaves 18 to 40 Hz trill dips well resolved. */
        val hopSeconds: Double = 128.0 / 48000.0,
        /** 512 samples at 48 kHz: spans several pitch periods, so voicing stays out of the envelope. */
        val windowSeconds: Double = 512.0 / 48000.0,
    )

    data class Fricative(
        /** Frication above the running high-frequency floor (web). */
        val presentAboveFloorDb: Float = 12f,
        val minHfShareUnvoiced: Float = 0.45f,
        val minHfShareVoiced: Float = 0.22f,
        /**
         * In Z and J the vowel band collapses while high frequencies stay, so high-band energy above
         * the vowel band finds them even when the voice bar keeps the share low. Set on the bundled
         * adult clips, where vowels sit 6 dB or more below and voiced frication reaches well above.
         */
        val minHfOverVowelDb: Float = 0f,
        /**
         * A Z or J inside a phrase can be too weak for that, with its high band still under the voice
         * bar, but its centroid stays near 10 or 5 kHz where a vowel's sits near 3 kHz. Same clips.
         */
        val weakHissCentroidHz: Float = 4500f,
        val weakHissMinHfOverVowelDb: Float = -8f,
        /** Clip analysis only: frication this far under the clip's loudest is a tail, not a sound. */
        val maxBelowHfPeakDb: Float = 25f,
        /** YIN clarity above which a frication frame counts as voiced. */
        val voicedClarity: Float = 0.75f,
        val strengthRangeDb: Float = 25f,
        /**
         * The web used 40 ms, which misses most Z, J and GE/GI of the bundled adult voice: with a
         * 40 ms frame they reach threshold for only 30 to 40 ms.
         */
        val minSegmentSeconds: Double = 0.03,
        /**
         * Share of the frication, centred, used for the verdict. The edges carry formant
         * transitions; Holliday et al. and the research notes both measure mid-frication.
         */
        val coreShare: Double = 0.5,
        val minCoreSeconds: Double = 0.02,
        val maxCoreSeconds: Double = 0.5,
        /** Sine tapers (Riedel & Sidorenko 1995). Holliday et al. also used multitaper spectra. */
        val tapers: Int = 6,
        val diffuseFlatness: Float = 0.7f,
        val diffuseTiltMarginDb: Float = 4f,
        val diffuseMaxAboveFloorDb: Float = 22f,
        /** Shorter segments cannot show voicing: the pitch frame is about as long as they are. */
        val minVoicingSeconds: Double = 0.08,
    )

    data class SibilantPrior(
        val centroidHz: Float,
        val peakHz: Float,
        val bandRatioDb: Float,
        val tiltDb: Float,
        val centroidScaleHz: Float,
        val peakScaleHz: Float,
        val bandRatioScaleDb: Float,
    )

    /** Age band for children, from the dispersions and agreement rates in Holliday et al. 2015. */
    data class ChildAgeBand(
        val maxYears: Int,
        /** Widens the prior scales, as within-category dispersion does at this age. */
        val scaleFactor: Float,
        val uncertainty: Float,
    )

    data class Sibilant(
        /**
         * Centroid and peak boundaries inherited from the web, checked there on adult Romanian (/s/
         * 9.4 to 9.8 kHz, /ʃ/ 5.6 to 6.7 kHz in a 2 to 15 kHz band). The band ratio sits between the
         * medians measured on the bundled adult clips (about 9 and 25 dB), whose 11-20 kHz band stops
         * near 15 kHz; a full-band capture puts more energy up there for S, which only widens the gap.
         * No Romanian child norms exist in print.
         */
        val male: SibilantPrior = SibilantPrior(
            centroidHz = 6200f, peakHz = 5200f, bandRatioDb = 16f, tiltDb = -1f,
            centroidScaleHz = 700f, peakScaleHz = 900f, bandRatioScaleDb = 5f,
        ),
        val female: SibilantPrior = SibilantPrior(
            centroidHz = 7300f, peakHz = 6400f, bandRatioDb = 16f, tiltDb = 1f,
            centroidScaleHz = 700f, peakScaleHz = 900f, bandRatioScaleDb = 5f,
        ),
        /** Scale from the 984 Hz within-category dispersion of 5-year-olds in Holliday et al. */
        val child: SibilantPrior = SibilantPrior(
            centroidHz = 7900f, peakHz = 7000f, bandRatioDb = 16f, tiltDb = 2f,
            centroidScaleHz = 984f, peakScaleHz = 1200f, bandRatioScaleDb = 6f,
        ),
        /**
         * Holliday et al. dispersions of 1367, 1319, 1074 and 984 Hz at ages 2 to 5; a mixed-effects
         * centroid model recovers only 67% of targets at age 2, so age 2 stays almost always unclear.
         */
        val childAgeBands: List<ChildAgeBand> = listOf(
            ChildAgeBand(maxYears = 2, scaleFactor = 1.39f, uncertainty = 0.45f),
            ChildAgeBand(maxYears = 3, scaleFactor = 1.34f, uncertainty = 0.2f),
            ChildAgeBand(maxYears = 4, scaleFactor = 1.09f, uncertainty = 0.16f),
        ),
        val weightCentroid: Float = 0.45f,
        val weightPeak: Float = 0.3f,
        val weightBandRatio: Float = 0.25f,
        /** Steepness of the sNess curve (web). */
        val gain: Float = 2.2f,
        /** Half-width of the `unclear` band around sNess 0.5 while only a prior is known. */
        val priorUncertainty: Float = 0.12f,
        val fittedUncertainty: Float = 0.08f,
        /** Research protocol: 5 validated tokens of each place before trusting a talker's boundary. */
        val minTokensToFit: Int = 10,
        val minTokensPerPlace: Int = 3,
        /**
         * Between-means distance in pooled SDs below which the anchors barely separate the two
         * places, so the wider prior band is kept. Proposal.
         */
        val minSeparation: Float = 1.5f,
        /**
         * Keeps a few consistent anchors from claiming impossible precision. 577 Hz is the adult
         * within-category dispersion in Holliday et al.; the floor is half of that.
         */
        val minCentroidScaleHz: Float = 290f,
        val minPeakScaleHz: Float = 400f,
        val minBandRatioScaleDb: Float = 2.5f,
        /** Oldest anchors drop out beyond this, so the boundary follows a growing child. */
        val maxAnchorsPerPlace: Int = 30,
        /**
         * S/Ș cannot be judged below these: at 16 kHz a 48 kHz boundary gives wrong answers (research
         * probe: 9925 Hz read as 5535 Hz after decimation), and Bluetooth HFP sits there silently.
         */
        val minSampleRateHz: Double = 32000.0,
        val minBandwidthHz: Float = 11000f,
        /** Measured bandwidth is where frication's average spectrum falls this far below its peak. */
        val bandwidthDropDb: Float = 50f,
    )

    /**
     * Valentini-Botinhao et al. 2012: lateral spectra have more energy just below 5 kHz and fall
     * fastest above 11 kHz. The cut-offs are proposals; no published threshold exists for children,
     * and that study reached only about 75% at phone level on real speakers, so this stays a hint.
     * No S on the bundled adult clips meets both, but none of them has a real lateral to compare.
     */
    data class Lateral(
        val minLowBandDb: Float = -12f,
        val minBandRatioDb: Float = 14f,
        /** The fall above 11 kHz is invisible once a codec has already cut the band. */
        val minBandwidthHz: Float = 16000f,
    )

    data class Trill(
        val windowSeconds: Double = 0.24,
        val stepSeconds: Double = 0.02,
        /** Removes the slow contour with a moving average about one trill period wide. */
        val detrendHalfSeconds: Float = 0.028f,
        /** Cross-language trill rates gather near 30 Hz or a little less. */
        val minRateHz: Float = 18f,
        val maxRateHz: Float = 38f,
        val minProminence: Float = 0.55f,
        val maxHalfLagCorrelation: Float = 0.35f,
        /** Real speech without R reached about 0.43 periodicity from consonant-vowel ripple (web). */
        val minPeriodicity: Float = 0.5f,
        val minDepthDb: Float = 2.5f,
        val strengthPeriodicityBase: Float = 0.3f,
        val strengthPeriodicityRange: Float = 0.45f,
        val strengthDepthDb: Float = 3.5f,
        /** Vocal fry also pulses at 20 to 70 Hz; a real trill keeps a normal pitch. */
        val creakyBelowHz: Float = 85f,
        val hissyShare: Float = 0.35f,
        val hissyFraction: Float = 0.2f,
        /**
         * A trill is voiced speech at full level. Quiet tails ripple too, and MP3 frames put a
         * false 38 Hz period in them.
         */
        val minVoicedShare: Float = 0.6f,
        val maxBelowPeakDb: Float = 20f,
        /**
         * A single dip near an onset also gives an autocorrelation peak. A trill repeats: at least
         * three contacts, and the correlation must still stand at twice the period.
         */
        val minContacts: Int = 3,
        val minSecondPeriodCorrelation: Float = 0.25f,
        val contactDepthShare: Float = 0.75f,
        val mergeGapSeconds: Double = 0.03,
        val lookbackFrames: Int = 24,
    )

    /**
     * Savu reports mean Romanian /r/ constrictions of 27.3, 26.9, 24.0 and 28.8 ms for #rV, rC, Cr
     * and VrV, longer than the 20 ms the web assumed.
     */
    data class Tap(
        val closurePriorLowMs: Float = 24f,
        val closurePriorHighMs: Float = 29f,
        /**
         * Half-depth width that still counts. The window smears the dip, and stop closures
         * (typically 50 ms and up) must stay out. Proposal, to be refitted on Romanian adults.
         */
        val minWidthMs: Float = 10f,
        val maxWidthMs: Float = 42f,
        val minScore: Float = 0.2f,
        /**
         * On the bundled adult clips 6 dB kept 23 of 26 taps and 11 of 92 R-free words had a false one;
         * 7 dB kept 21 with 7 false. Missing a correct R costs more than passing a wrong one.
         */
        val minDepthDb: Float = 6f,
        /** Flanks must be near speech level, or a tail of decay reads as a dip. */
        val maxFlankBelowSpeechDb: Float = 14f,
        val flankLookSeconds: Float = 0.06f,
        val smoothHalfSeconds: Float = 0.0025f,
        val minSpacingSeconds: Float = 0.05f,
        /** Savu's tokens have a vocalic element beside the closure, so both sides must be voiced. */
        val voicedFlankInnerFrames: Int = 2,
        val voicedFlankOuterFrames: Int = 8,
        /** A tap releases into a vowel. Hiss on or beside the dip means a stop burst or a fricative. */
        val maxHfShare: Float = 0.25f,
        val hissCheckFrames: Int = 3,
    )

    data class Syllable(
        /**
         * Voiced peaks of vowel-band energy that stand this far above the dips on both sides
         * (de Jong & Wempe 2009 use 2 dB). 3 dB and a 120 ms gap gave the most exact counts on the
         * bundled adult words: 109 of 155, against 93 for the web's 1.5 dB and 90 ms.
         */
        val minProminenceDb: Float = 3f,
        val belowPeakDb: Float = 20f,
        val minGapSeconds: Double = 0.12,
        val valleyLookFrames: Int = 25,
    )

    data class Hesitation(
        val minSeconds: Double = 0.25,
        val maxSeconds: Double = 1.0,
        val maxPitchSdSt: Float = 0.6f,
        val nearPauseSeconds: Double = 0.15,
        val maxSyllables: Int = 1,
    )

    data class Live(
        /** Heavy analysis runs at most this often, so a caller's small hop cannot exceed the budget. */
        val analysisHopSeconds: Double = 512.0 / 48000.0,
        val initialFloorDb: Float = -75f,
        val initialHfFloorDb: Float = -100f,
        val floorRiseDbPerSecond: Float = 1.5f,
        /** For this long after start the floor learns fast, so a noisy room does not look like speech. */
        val floorLearnSeconds: Float = 1.2f,
        val floorLearnRiseDbPerSecond: Float = 30f,
        val floorFallRate: Float = 8f,
        val vadMarginDb: Float = 11f,
        val vadHangSeconds: Double = 0.22,
        val levelRangeDb: Float = 40f,
        val pitchClarity: Float = 0.85f,
        /**
         * Blowing is loud with no pitch: phone microphones high-pass wind rumble, so the old
         * low-band rule rarely fired (web).
         */
        val blowAboveFloorDb: Float = 10f,
        val blowRangeDb: Float = 20f,
        val blowAperiodicLowClarity: Float = 0.5f,
        val blowAperiodicMidClarity: Float = 0.8f,
        val blowAperiodicMid: Float = 0.55f,
        val blowAperiodicHigh: Float = 0.12f,
        val blowFricativeBase: Float = 0.25f,
        /** Target-zone edge shared by the live meters. */
        val meterZone: Float = 0.7f,
        val trillMeterThreshold: Float = 0.45f,
        val utteranceMinGoodSeconds: Double = 0.06,
        val utteranceQuietSeconds: Double = 0.25,
        val utteranceMinSeconds: Double = 0.09,
        val liveThStrength: Float = 0.35f,
    )

    data class Capture(
        /** Near full scale in a run of at least `clipRunSamples`: a flat top, not a loud peak. */
        val clipLevel: Float = 0.985f,
        val clipRunSamples: Int = 3,
        /**
         * Benway & Preston 2023 lost 14% of one participant's utterances to clipping protection;
         * the cut-off is a proposal. A single short run is inaudible, a sustained one is not.
         */
        val maxClippedFraction: Float = 0.001f,
        /** Proposal, not yet checked on a phone. */
        val minSpeechRmsDb: Float = -50f,
        val minDynamicRangeDb: Float = 12f,
        val minSpeechSeconds: Double = 0.12,
    )

    data class Judge(
        /**
         * Alignment costs. F, V and H are weak and often go unheard, so missing one costs little,
         * and matching one to a detection costs a bit so that sibilants claim detections first.
         */
        val alignMissingSibilant: Float = 1f,
        val alignMissingOther: Float = 0.3f,
        val alignExtraDetection: Float = 0.5f,
        val alignWrongPlace: Float = 1f,
        val alignOtherMatch: Float = 0.2f,
        /**
         * Per unit of relative distance between a letter's place in the text and a detection's
         * place in the take. Settles which of several detections a target belongs to.
         */
        val alignPositionCost: Float = 0.5f,
        /** A take shorter than this with no target heard is reported as too short, not wrong. */
        val shortTakeSeconds: Double = 0.35,
        val voicedSeconds: Double = 0.25,
        val voicedMinAboveFloorDb: Float = 15f,
        val voicedMaxBelowPeakDb: Float = 30f,
        val thLeadSeconds: Double = 0.15,
        val scoreGood: Float = 0.9f,
        val scoreRhoticAccepted: Float = 0.85f,
        val scorePartialFloor: Float = 0.6f,
        val scoreUnclear: Float = 0.5f,
        val scoreWrongPlaceCap: Float = 0.45f,
        val scoreMissing: Float = 0.25f,
        val scoreWeak: Float = 0.6f,
        val scoreUnvoiced: Float = 0.65f,
    )

    data class Stress(
        /**
         * Cue scales: 3 dB, 2 semitones and 40 ms of vowel each count as one unit (web, hand-set;
         * the research notes want them refitted on labelled adult takes).
         */
        val dbUnit: Float = 3f,
        val semitoneUnit: Float = 2f,
        val durationUnit: Float = 0.04f,
        val durationWeight: Float = 0.6f,
        /** Words end longer anyway (phrase-final lengthening), so the last vowel's length counts less. */
        val finalLengthening: Float = 0.75f,
        val confidenceMargin: Float = 1.5f,
        val minConfidence: Float = 0.15f,
        val middleTolerance: Float = 0.34f,
        val nucleusLookFrames: Int = 3,
        val vowelDropDb: Float = 6f,
    )

    data class Prosody(
        val minSpeakingSeconds: Double = 0.15,
        val maxDtwCells: Double = 1_000_000.0,
        val dtwBand: Float = 0.35f,
        val spanMarginSeconds: Double = 0.05,
        val medianFrames: Int = 2,
        val minContourFrames: Int = 8,
        val minContourSdSt: Float = 0.3f,
        /** A monotone take has no shape to correlate: a melody of zero, not a missing value. */
        val monotoneSdSt: Float = 0.2f,
        val shapeBase: Float = 0.1f,
        val shapeRange: Float = 0.7f,
        val shapeWeight: Float = 0.75f,
        val rangeWeight: Float = 0.25f,
        val durationRatioSpan: Float = 1.6f,
        val durationWeight: Float = 0.6f,
        val pauseWeight: Float = 0.4f,
        val pauseMatchSeconds: Double = 0.2,
        val intonationWeight: Float = 0.6f,
        val rhythmWeight: Float = 0.4f,
        val good: Float = 0.75f,
        val accepted: Float = 0.6f,
        val flatRangeRatio: Float = 0.6f,
        val poorIntonation: Float = 0.5f,
        val tooSlowRatio: Float = 1.3f,
        val tooFastRatio: Float = 0.75f,
    )

    data class Delivery(
        val minWordsPerMinute: Float = 110f,
        val maxWordsPerMinute: Float = 165f,
        val paceFalloffWpm: Float = 50f,
        /** Words per syllable when no transcript exists, from average word length per language. */
        val romanianWordsPerSyllable: Float = 0.42f,
        val englishWordsPerSyllable: Float = 0.62f,
        /** Below 0.15 sounds monotone (Hincks). */
        val monotonePvq: Float = 0.15f,
        val livelyPvq: Float = 0.22f,
        val livelyRangeSt: Float = 5f,
        val minLoudnessSdDb: Float = 2.5f,
        val maxLoudnessSdDb: Float = 10f,
        val flatLoudnessScore: Float = 0.6f,
        val targetPausesPerMinute: Float = 6f,
        val minPauseScore: Float = 0.2f,
        val longPauseSeconds: Double = 3.0,
        val longPausePenalty: Float = 0.7f,
        /** About two fillers a minute go unnoticed; beyond that listeners start counting. */
        val freeFillersPerMinute: Float = 2f,
        val fillerPenalty: Float = 0.12f,
        val minFluencyScore: Float = 0.2f,
        val weightPace: Float = 0.22f,
        val weightTone: Float = 0.22f,
        val weightVolume: Float = 0.12f,
        val weightPauses: Float = 0.14f,
        val weightFluency: Float = 0.2f,
        val risingSlopeSt: Float = 1.5f,
        val fallingSlopeSt: Float = 0.5f,
    )
}

// MARK: JSON
//
// Swift's synthesized Codable: every property is a key with its Swift name, nested structs are
// objects, and decoding needs every key. Each field is listed once, with how to read and replace it.

private class Field<T>(val name: String, val encode: (T) -> Any?, val decode: (T, Any?) -> T)

private class Codec<T>(private val seed: T, private val fields: List<Field<T>>) {
    fun encode(value: T): Map<String, Any?> = LinkedHashMap<String, Any?>().apply {
        for (field in fields) put(field.name, field.encode(value))
    }

    fun decode(json: Any?, key: String): T {
        val members = json.asObject(key)
        var result = seed
        for (field in fields) result = field.decode(result, members.member(field.name))
        return result
    }
}

private fun <T> float(name: String, get: (T) -> Float, set: T.(Float) -> T) =
    Field<T>(name, { Json.number(get(it)) }, { value, json -> value.set(json.asFloat(name)) })

private fun <T> double(name: String, get: (T) -> Double, set: T.(Double) -> T) =
    Field<T>(name, { Json.number(get(it)) }, { value, json -> value.set(json.asDouble(name)) })

private fun <T> int(name: String, get: (T) -> Int, set: T.(Int) -> T) =
    Field<T>(name, { Json.number(get(it)) }, { value, json -> value.set(json.asInt(name)) })

private fun <T, N> nested(name: String, codec: Codec<N>, get: (T) -> N, set: T.(N) -> T) =
    Field<T>(name, { codec.encode(get(it)) }, { value, json -> value.set(codec.decode(json, name)) })

private fun <T, N> list(name: String, codec: Codec<N>, get: (T) -> List<N>, set: T.(List<N>) -> T) =
    Field<T>(name, { get(it).map(codec::encode) }, { value, json ->
        value.set(json.asArray(name).map { codec.decode(it, name) })
    })

private object ThresholdsCodec {
    private val analysis = Codec(
        DSPThresholds.Analysis(),
        listOf(
            double("hopSeconds", { it.hopSeconds }) { copy(hopSeconds = it) },
            double("frameSeconds", { it.frameSeconds }) { copy(frameSeconds = it) },
            float("floorPercentile", { it.floorPercentile }) { copy(floorPercentile = it) },
            float("peakPercentile", { it.peakPercentile }) { copy(peakPercentile = it) },
            float("speechMarginShare", { it.speechMarginShare }) { copy(speechMarginShare = it) },
            float("speechMarginMinDb", { it.speechMarginMinDb }) { copy(speechMarginMinDb = it) },
            float("speechMarginMaxDb", { it.speechMarginMaxDb }) { copy(speechMarginMaxDb = it) },
            float("speechMaxBelowPeakDb", { it.speechMaxBelowPeakDb }) { copy(speechMaxBelowPeakDb = it) },
            double("speechHangSeconds", { it.speechHangSeconds }) { copy(speechHangSeconds = it) },
            double("minPauseSeconds", { it.minPauseSeconds }) { copy(minPauseSeconds = it) },
        ),
    )

    private val bands = Codec(
        DSPThresholds.Bands(),
        listOf(
            float("levelLowHz", { it.levelLowHz }) { copy(levelLowHz = it) },
            float("levelHighHz", { it.levelHighHz }) { copy(levelHighHz = it) },
            float("hfLowHz", { it.hfLowHz }) { copy(hfLowHz = it) },
            float("lfLowHz", { it.lfLowHz }) { copy(lfLowHz = it) },
            float("lfHighHz", { it.lfHighHz }) { copy(lfHighHz = it) },
            float("vowelLowHz", { it.vowelLowHz }) { copy(vowelLowHz = it) },
            float("vowelHighHz", { it.vowelHighHz }) { copy(vowelHighHz = it) },
            float("centroidLowHz", { it.centroidLowHz }) { copy(centroidLowHz = it) },
            float("centroidHighHz", { it.centroidHighHz }) { copy(centroidHighHz = it) },
            float("peakSmoothingHz", { it.peakSmoothingHz }) { copy(peakSmoothingHz = it) },
            float("tiltLowHz", { it.tiltLowHz }) { copy(tiltLowHz = it) },
            float("tiltMidHz", { it.tiltMidHz }) { copy(tiltMidHz = it) },
            float("ratioLowHz", { it.ratioLowHz }) { copy(ratioLowHz = it) },
            float("ratioMidHz", { it.ratioMidHz }) { copy(ratioMidHz = it) },
            float("ratioHighHz", { it.ratioHighHz }) { copy(ratioHighHz = it) },
            float("flatnessLowHz", { it.flatnessLowHz }) { copy(flatnessLowHz = it) },
            float("flatnessHighHz", { it.flatnessHighHz }) { copy(flatnessHighHz = it) },
            float("flatnessBlockHz", { it.flatnessBlockHz }) { copy(flatnessBlockHz = it) },
        ),
    )

    private val pitch = Codec(
        DSPThresholds.Pitch(),
        listOf(
            float("minHz", { it.minHz }) { copy(minHz = it) },
            float("maxHz", { it.maxHz }) { copy(maxHz = it) },
            float("yinThreshold", { it.yinThreshold }) { copy(yinThreshold = it) },
            float("octaveCorrectionMargin", { it.octaveCorrectionMargin }) { copy(octaveCorrectionMargin = it) },
            float("fallbackDipMargin", { it.fallbackDipMargin }) { copy(fallbackDipMargin = it) },
            double("windowSeconds", { it.windowSeconds }) { copy(windowSeconds = it) },
            double("decimatedRateHz", { it.decimatedRateHz }) { copy(decimatedRateHz = it) },
            double("lowpassHz", { it.lowpassHz }) { copy(lowpassHz = it) },
            float("strictClarity", { it.strictClarity }) { copy(strictClarity = it) },
            float("melodyClarity", { it.melodyClarity }) { copy(melodyClarity = it) },
            float("melodyMinHz", { it.melodyMinHz }) { copy(melodyMinHz = it) },
            float("melodyMaxHz", { it.melodyMaxHz }) { copy(melodyMaxHz = it) },
            float("octaveOutlierSt", { it.octaveOutlierSt }) { copy(octaveOutlierSt = it) },
            int("octaveNeighbourFrames", { it.octaveNeighbourFrames }) { copy(octaveNeighbourFrames = it) },
            float("melodyFoldUpSt", { it.melodyFoldUpSt }) { copy(melodyFoldUpSt = it) },
            float("melodyFoldDownSt", { it.melodyFoldDownSt }) { copy(melodyFoldDownSt = it) },
            float("melodyOutlierSt", { it.melodyOutlierSt }) { copy(melodyOutlierSt = it) },
            int("melodyNeighbourFrames", { it.melodyNeighbourFrames }) { copy(melodyNeighbourFrames = it) },
            int("minFramesForStats", { it.minFramesForStats }) { copy(minFramesForStats = it) },
        ),
    )

    private val envelope = Codec(
        DSPThresholds.Envelope(),
        listOf(
            double("hopSeconds", { it.hopSeconds }) { copy(hopSeconds = it) },
            double("windowSeconds", { it.windowSeconds }) { copy(windowSeconds = it) },
        ),
    )

    private val fricative = Codec(
        DSPThresholds.Fricative(),
        listOf(
            float("presentAboveFloorDb", { it.presentAboveFloorDb }) { copy(presentAboveFloorDb = it) },
            float("minHfShareUnvoiced", { it.minHfShareUnvoiced }) { copy(minHfShareUnvoiced = it) },
            float("minHfShareVoiced", { it.minHfShareVoiced }) { copy(minHfShareVoiced = it) },
            float("minHfOverVowelDb", { it.minHfOverVowelDb }) { copy(minHfOverVowelDb = it) },
            float("weakHissCentroidHz", { it.weakHissCentroidHz }) { copy(weakHissCentroidHz = it) },
            float("weakHissMinHfOverVowelDb", { it.weakHissMinHfOverVowelDb }) { copy(weakHissMinHfOverVowelDb = it) },
            float("maxBelowHfPeakDb", { it.maxBelowHfPeakDb }) { copy(maxBelowHfPeakDb = it) },
            float("voicedClarity", { it.voicedClarity }) { copy(voicedClarity = it) },
            float("strengthRangeDb", { it.strengthRangeDb }) { copy(strengthRangeDb = it) },
            double("minSegmentSeconds", { it.minSegmentSeconds }) { copy(minSegmentSeconds = it) },
            double("coreShare", { it.coreShare }) { copy(coreShare = it) },
            double("minCoreSeconds", { it.minCoreSeconds }) { copy(minCoreSeconds = it) },
            double("maxCoreSeconds", { it.maxCoreSeconds }) { copy(maxCoreSeconds = it) },
            int("tapers", { it.tapers }) { copy(tapers = it) },
            float("diffuseFlatness", { it.diffuseFlatness }) { copy(diffuseFlatness = it) },
            float("diffuseTiltMarginDb", { it.diffuseTiltMarginDb }) { copy(diffuseTiltMarginDb = it) },
            float("diffuseMaxAboveFloorDb", { it.diffuseMaxAboveFloorDb }) { copy(diffuseMaxAboveFloorDb = it) },
            double("minVoicingSeconds", { it.minVoicingSeconds }) { copy(minVoicingSeconds = it) },
        ),
    )

    private val sibilantPrior = Codec(
        DSPThresholds.SibilantPrior(0f, 0f, 0f, 0f, 0f, 0f, 0f),
        listOf(
            float("centroidHz", { it.centroidHz }) { copy(centroidHz = it) },
            float("peakHz", { it.peakHz }) { copy(peakHz = it) },
            float("bandRatioDb", { it.bandRatioDb }) { copy(bandRatioDb = it) },
            float("tiltDb", { it.tiltDb }) { copy(tiltDb = it) },
            float("centroidScaleHz", { it.centroidScaleHz }) { copy(centroidScaleHz = it) },
            float("peakScaleHz", { it.peakScaleHz }) { copy(peakScaleHz = it) },
            float("bandRatioScaleDb", { it.bandRatioScaleDb }) { copy(bandRatioScaleDb = it) },
        ),
    )

    private val childAgeBand = Codec(
        DSPThresholds.ChildAgeBand(0, 0f, 0f),
        listOf(
            int("maxYears", { it.maxYears }) { copy(maxYears = it) },
            float("scaleFactor", { it.scaleFactor }) { copy(scaleFactor = it) },
            float("uncertainty", { it.uncertainty }) { copy(uncertainty = it) },
        ),
    )

    private val sibilant = Codec(
        DSPThresholds.Sibilant(),
        listOf(
            nested("male", sibilantPrior, { it.male }) { copy(male = it) },
            nested("female", sibilantPrior, { it.female }) { copy(female = it) },
            nested("child", sibilantPrior, { it.child }) { copy(child = it) },
            list("childAgeBands", childAgeBand, { it.childAgeBands }) { copy(childAgeBands = it) },
            float("weightCentroid", { it.weightCentroid }) { copy(weightCentroid = it) },
            float("weightPeak", { it.weightPeak }) { copy(weightPeak = it) },
            float("weightBandRatio", { it.weightBandRatio }) { copy(weightBandRatio = it) },
            float("gain", { it.gain }) { copy(gain = it) },
            float("priorUncertainty", { it.priorUncertainty }) { copy(priorUncertainty = it) },
            float("fittedUncertainty", { it.fittedUncertainty }) { copy(fittedUncertainty = it) },
            int("minTokensToFit", { it.minTokensToFit }) { copy(minTokensToFit = it) },
            int("minTokensPerPlace", { it.minTokensPerPlace }) { copy(minTokensPerPlace = it) },
            float("minSeparation", { it.minSeparation }) { copy(minSeparation = it) },
            float("minCentroidScaleHz", { it.minCentroidScaleHz }) { copy(minCentroidScaleHz = it) },
            float("minPeakScaleHz", { it.minPeakScaleHz }) { copy(minPeakScaleHz = it) },
            float("minBandRatioScaleDb", { it.minBandRatioScaleDb }) { copy(minBandRatioScaleDb = it) },
            int("maxAnchorsPerPlace", { it.maxAnchorsPerPlace }) { copy(maxAnchorsPerPlace = it) },
            double("minSampleRateHz", { it.minSampleRateHz }) { copy(minSampleRateHz = it) },
            float("minBandwidthHz", { it.minBandwidthHz }) { copy(minBandwidthHz = it) },
            float("bandwidthDropDb", { it.bandwidthDropDb }) { copy(bandwidthDropDb = it) },
        ),
    )

    private val lateral = Codec(
        DSPThresholds.Lateral(),
        listOf(
            float("minLowBandDb", { it.minLowBandDb }) { copy(minLowBandDb = it) },
            float("minBandRatioDb", { it.minBandRatioDb }) { copy(minBandRatioDb = it) },
            float("minBandwidthHz", { it.minBandwidthHz }) { copy(minBandwidthHz = it) },
        ),
    )

    private val trill = Codec(
        DSPThresholds.Trill(),
        listOf(
            double("windowSeconds", { it.windowSeconds }) { copy(windowSeconds = it) },
            double("stepSeconds", { it.stepSeconds }) { copy(stepSeconds = it) },
            float("detrendHalfSeconds", { it.detrendHalfSeconds }) { copy(detrendHalfSeconds = it) },
            float("minRateHz", { it.minRateHz }) { copy(minRateHz = it) },
            float("maxRateHz", { it.maxRateHz }) { copy(maxRateHz = it) },
            float("minProminence", { it.minProminence }) { copy(minProminence = it) },
            float("maxHalfLagCorrelation", { it.maxHalfLagCorrelation }) { copy(maxHalfLagCorrelation = it) },
            float("minPeriodicity", { it.minPeriodicity }) { copy(minPeriodicity = it) },
            float("minDepthDb", { it.minDepthDb }) { copy(minDepthDb = it) },
            float("strengthPeriodicityBase", { it.strengthPeriodicityBase }) { copy(strengthPeriodicityBase = it) },
            float("strengthPeriodicityRange", { it.strengthPeriodicityRange }) { copy(strengthPeriodicityRange = it) },
            float("strengthDepthDb", { it.strengthDepthDb }) { copy(strengthDepthDb = it) },
            float("creakyBelowHz", { it.creakyBelowHz }) { copy(creakyBelowHz = it) },
            float("hissyShare", { it.hissyShare }) { copy(hissyShare = it) },
            float("hissyFraction", { it.hissyFraction }) { copy(hissyFraction = it) },
            float("minVoicedShare", { it.minVoicedShare }) { copy(minVoicedShare = it) },
            float("maxBelowPeakDb", { it.maxBelowPeakDb }) { copy(maxBelowPeakDb = it) },
            int("minContacts", { it.minContacts }) { copy(minContacts = it) },
            float("minSecondPeriodCorrelation", { it.minSecondPeriodCorrelation }) { copy(minSecondPeriodCorrelation = it) },
            float("contactDepthShare", { it.contactDepthShare }) { copy(contactDepthShare = it) },
            double("mergeGapSeconds", { it.mergeGapSeconds }) { copy(mergeGapSeconds = it) },
            int("lookbackFrames", { it.lookbackFrames }) { copy(lookbackFrames = it) },
        ),
    )

    private val tap = Codec(
        DSPThresholds.Tap(),
        listOf(
            float("closurePriorLowMs", { it.closurePriorLowMs }) { copy(closurePriorLowMs = it) },
            float("closurePriorHighMs", { it.closurePriorHighMs }) { copy(closurePriorHighMs = it) },
            float("minWidthMs", { it.minWidthMs }) { copy(minWidthMs = it) },
            float("maxWidthMs", { it.maxWidthMs }) { copy(maxWidthMs = it) },
            float("minScore", { it.minScore }) { copy(minScore = it) },
            float("minDepthDb", { it.minDepthDb }) { copy(minDepthDb = it) },
            float("maxFlankBelowSpeechDb", { it.maxFlankBelowSpeechDb }) { copy(maxFlankBelowSpeechDb = it) },
            float("flankLookSeconds", { it.flankLookSeconds }) { copy(flankLookSeconds = it) },
            float("smoothHalfSeconds", { it.smoothHalfSeconds }) { copy(smoothHalfSeconds = it) },
            float("minSpacingSeconds", { it.minSpacingSeconds }) { copy(minSpacingSeconds = it) },
            int("voicedFlankInnerFrames", { it.voicedFlankInnerFrames }) { copy(voicedFlankInnerFrames = it) },
            int("voicedFlankOuterFrames", { it.voicedFlankOuterFrames }) { copy(voicedFlankOuterFrames = it) },
            float("maxHfShare", { it.maxHfShare }) { copy(maxHfShare = it) },
            int("hissCheckFrames", { it.hissCheckFrames }) { copy(hissCheckFrames = it) },
        ),
    )

    private val syllable = Codec(
        DSPThresholds.Syllable(),
        listOf(
            float("minProminenceDb", { it.minProminenceDb }) { copy(minProminenceDb = it) },
            float("belowPeakDb", { it.belowPeakDb }) { copy(belowPeakDb = it) },
            double("minGapSeconds", { it.minGapSeconds }) { copy(minGapSeconds = it) },
            int("valleyLookFrames", { it.valleyLookFrames }) { copy(valleyLookFrames = it) },
        ),
    )

    private val hesitation = Codec(
        DSPThresholds.Hesitation(),
        listOf(
            double("minSeconds", { it.minSeconds }) { copy(minSeconds = it) },
            double("maxSeconds", { it.maxSeconds }) { copy(maxSeconds = it) },
            float("maxPitchSdSt", { it.maxPitchSdSt }) { copy(maxPitchSdSt = it) },
            double("nearPauseSeconds", { it.nearPauseSeconds }) { copy(nearPauseSeconds = it) },
            int("maxSyllables", { it.maxSyllables }) { copy(maxSyllables = it) },
        ),
    )

    private val live = Codec(
        DSPThresholds.Live(),
        listOf(
            double("analysisHopSeconds", { it.analysisHopSeconds }) { copy(analysisHopSeconds = it) },
            float("initialFloorDb", { it.initialFloorDb }) { copy(initialFloorDb = it) },
            float("initialHfFloorDb", { it.initialHfFloorDb }) { copy(initialHfFloorDb = it) },
            float("floorRiseDbPerSecond", { it.floorRiseDbPerSecond }) { copy(floorRiseDbPerSecond = it) },
            float("floorLearnSeconds", { it.floorLearnSeconds }) { copy(floorLearnSeconds = it) },
            float("floorLearnRiseDbPerSecond", { it.floorLearnRiseDbPerSecond }) { copy(floorLearnRiseDbPerSecond = it) },
            float("floorFallRate", { it.floorFallRate }) { copy(floorFallRate = it) },
            float("vadMarginDb", { it.vadMarginDb }) { copy(vadMarginDb = it) },
            double("vadHangSeconds", { it.vadHangSeconds }) { copy(vadHangSeconds = it) },
            float("levelRangeDb", { it.levelRangeDb }) { copy(levelRangeDb = it) },
            float("pitchClarity", { it.pitchClarity }) { copy(pitchClarity = it) },
            float("blowAboveFloorDb", { it.blowAboveFloorDb }) { copy(blowAboveFloorDb = it) },
            float("blowRangeDb", { it.blowRangeDb }) { copy(blowRangeDb = it) },
            float("blowAperiodicLowClarity", { it.blowAperiodicLowClarity }) { copy(blowAperiodicLowClarity = it) },
            float("blowAperiodicMidClarity", { it.blowAperiodicMidClarity }) { copy(blowAperiodicMidClarity = it) },
            float("blowAperiodicMid", { it.blowAperiodicMid }) { copy(blowAperiodicMid = it) },
            float("blowAperiodicHigh", { it.blowAperiodicHigh }) { copy(blowAperiodicHigh = it) },
            float("blowFricativeBase", { it.blowFricativeBase }) { copy(blowFricativeBase = it) },
            float("meterZone", { it.meterZone }) { copy(meterZone = it) },
            float("trillMeterThreshold", { it.trillMeterThreshold }) { copy(trillMeterThreshold = it) },
            double("utteranceMinGoodSeconds", { it.utteranceMinGoodSeconds }) { copy(utteranceMinGoodSeconds = it) },
            double("utteranceQuietSeconds", { it.utteranceQuietSeconds }) { copy(utteranceQuietSeconds = it) },
            double("utteranceMinSeconds", { it.utteranceMinSeconds }) { copy(utteranceMinSeconds = it) },
            float("liveThStrength", { it.liveThStrength }) { copy(liveThStrength = it) },
        ),
    )

    private val capture = Codec(
        DSPThresholds.Capture(),
        listOf(
            float("clipLevel", { it.clipLevel }) { copy(clipLevel = it) },
            int("clipRunSamples", { it.clipRunSamples }) { copy(clipRunSamples = it) },
            float("maxClippedFraction", { it.maxClippedFraction }) { copy(maxClippedFraction = it) },
            float("minSpeechRmsDb", { it.minSpeechRmsDb }) { copy(minSpeechRmsDb = it) },
            float("minDynamicRangeDb", { it.minDynamicRangeDb }) { copy(minDynamicRangeDb = it) },
            double("minSpeechSeconds", { it.minSpeechSeconds }) { copy(minSpeechSeconds = it) },
        ),
    )

    private val judge = Codec(
        DSPThresholds.Judge(),
        listOf(
            float("alignMissingSibilant", { it.alignMissingSibilant }) { copy(alignMissingSibilant = it) },
            float("alignMissingOther", { it.alignMissingOther }) { copy(alignMissingOther = it) },
            float("alignExtraDetection", { it.alignExtraDetection }) { copy(alignExtraDetection = it) },
            float("alignWrongPlace", { it.alignWrongPlace }) { copy(alignWrongPlace = it) },
            float("alignOtherMatch", { it.alignOtherMatch }) { copy(alignOtherMatch = it) },
            float("alignPositionCost", { it.alignPositionCost }) { copy(alignPositionCost = it) },
            double("shortTakeSeconds", { it.shortTakeSeconds }) { copy(shortTakeSeconds = it) },
            double("voicedSeconds", { it.voicedSeconds }) { copy(voicedSeconds = it) },
            float("voicedMinAboveFloorDb", { it.voicedMinAboveFloorDb }) { copy(voicedMinAboveFloorDb = it) },
            float("voicedMaxBelowPeakDb", { it.voicedMaxBelowPeakDb }) { copy(voicedMaxBelowPeakDb = it) },
            double("thLeadSeconds", { it.thLeadSeconds }) { copy(thLeadSeconds = it) },
            float("scoreGood", { it.scoreGood }) { copy(scoreGood = it) },
            float("scoreRhoticAccepted", { it.scoreRhoticAccepted }) { copy(scoreRhoticAccepted = it) },
            float("scorePartialFloor", { it.scorePartialFloor }) { copy(scorePartialFloor = it) },
            float("scoreUnclear", { it.scoreUnclear }) { copy(scoreUnclear = it) },
            float("scoreWrongPlaceCap", { it.scoreWrongPlaceCap }) { copy(scoreWrongPlaceCap = it) },
            float("scoreMissing", { it.scoreMissing }) { copy(scoreMissing = it) },
            float("scoreWeak", { it.scoreWeak }) { copy(scoreWeak = it) },
            float("scoreUnvoiced", { it.scoreUnvoiced }) { copy(scoreUnvoiced = it) },
        ),
    )

    private val stress = Codec(
        DSPThresholds.Stress(),
        listOf(
            float("dbUnit", { it.dbUnit }) { copy(dbUnit = it) },
            float("semitoneUnit", { it.semitoneUnit }) { copy(semitoneUnit = it) },
            float("durationUnit", { it.durationUnit }) { copy(durationUnit = it) },
            float("durationWeight", { it.durationWeight }) { copy(durationWeight = it) },
            float("finalLengthening", { it.finalLengthening }) { copy(finalLengthening = it) },
            float("confidenceMargin", { it.confidenceMargin }) { copy(confidenceMargin = it) },
            float("minConfidence", { it.minConfidence }) { copy(minConfidence = it) },
            float("middleTolerance", { it.middleTolerance }) { copy(middleTolerance = it) },
            int("nucleusLookFrames", { it.nucleusLookFrames }) { copy(nucleusLookFrames = it) },
            float("vowelDropDb", { it.vowelDropDb }) { copy(vowelDropDb = it) },
        ),
    )

    private val prosody = Codec(
        DSPThresholds.Prosody(),
        listOf(
            double("minSpeakingSeconds", { it.minSpeakingSeconds }) { copy(minSpeakingSeconds = it) },
            double("maxDtwCells", { it.maxDtwCells }) { copy(maxDtwCells = it) },
            float("dtwBand", { it.dtwBand }) { copy(dtwBand = it) },
            double("spanMarginSeconds", { it.spanMarginSeconds }) { copy(spanMarginSeconds = it) },
            int("medianFrames", { it.medianFrames }) { copy(medianFrames = it) },
            int("minContourFrames", { it.minContourFrames }) { copy(minContourFrames = it) },
            float("minContourSdSt", { it.minContourSdSt }) { copy(minContourSdSt = it) },
            float("monotoneSdSt", { it.monotoneSdSt }) { copy(monotoneSdSt = it) },
            float("shapeBase", { it.shapeBase }) { copy(shapeBase = it) },
            float("shapeRange", { it.shapeRange }) { copy(shapeRange = it) },
            float("shapeWeight", { it.shapeWeight }) { copy(shapeWeight = it) },
            float("rangeWeight", { it.rangeWeight }) { copy(rangeWeight = it) },
            float("durationRatioSpan", { it.durationRatioSpan }) { copy(durationRatioSpan = it) },
            float("durationWeight", { it.durationWeight }) { copy(durationWeight = it) },
            float("pauseWeight", { it.pauseWeight }) { copy(pauseWeight = it) },
            double("pauseMatchSeconds", { it.pauseMatchSeconds }) { copy(pauseMatchSeconds = it) },
            float("intonationWeight", { it.intonationWeight }) { copy(intonationWeight = it) },
            float("rhythmWeight", { it.rhythmWeight }) { copy(rhythmWeight = it) },
            float("good", { it.good }) { copy(good = it) },
            float("accepted", { it.accepted }) { copy(accepted = it) },
            float("flatRangeRatio", { it.flatRangeRatio }) { copy(flatRangeRatio = it) },
            float("poorIntonation", { it.poorIntonation }) { copy(poorIntonation = it) },
            float("tooSlowRatio", { it.tooSlowRatio }) { copy(tooSlowRatio = it) },
            float("tooFastRatio", { it.tooFastRatio }) { copy(tooFastRatio = it) },
        ),
    )

    private val delivery = Codec(
        DSPThresholds.Delivery(),
        listOf(
            float("minWordsPerMinute", { it.minWordsPerMinute }) { copy(minWordsPerMinute = it) },
            float("maxWordsPerMinute", { it.maxWordsPerMinute }) { copy(maxWordsPerMinute = it) },
            float("paceFalloffWpm", { it.paceFalloffWpm }) { copy(paceFalloffWpm = it) },
            float("romanianWordsPerSyllable", { it.romanianWordsPerSyllable }) { copy(romanianWordsPerSyllable = it) },
            float("englishWordsPerSyllable", { it.englishWordsPerSyllable }) { copy(englishWordsPerSyllable = it) },
            float("monotonePvq", { it.monotonePvq }) { copy(monotonePvq = it) },
            float("livelyPvq", { it.livelyPvq }) { copy(livelyPvq = it) },
            float("livelyRangeSt", { it.livelyRangeSt }) { copy(livelyRangeSt = it) },
            float("minLoudnessSdDb", { it.minLoudnessSdDb }) { copy(minLoudnessSdDb = it) },
            float("maxLoudnessSdDb", { it.maxLoudnessSdDb }) { copy(maxLoudnessSdDb = it) },
            float("flatLoudnessScore", { it.flatLoudnessScore }) { copy(flatLoudnessScore = it) },
            float("targetPausesPerMinute", { it.targetPausesPerMinute }) { copy(targetPausesPerMinute = it) },
            float("minPauseScore", { it.minPauseScore }) { copy(minPauseScore = it) },
            double("longPauseSeconds", { it.longPauseSeconds }) { copy(longPauseSeconds = it) },
            float("longPausePenalty", { it.longPausePenalty }) { copy(longPausePenalty = it) },
            float("freeFillersPerMinute", { it.freeFillersPerMinute }) { copy(freeFillersPerMinute = it) },
            float("fillerPenalty", { it.fillerPenalty }) { copy(fillerPenalty = it) },
            float("minFluencyScore", { it.minFluencyScore }) { copy(minFluencyScore = it) },
            float("weightPace", { it.weightPace }) { copy(weightPace = it) },
            float("weightTone", { it.weightTone }) { copy(weightTone = it) },
            float("weightVolume", { it.weightVolume }) { copy(weightVolume = it) },
            float("weightPauses", { it.weightPauses }) { copy(weightPauses = it) },
            float("weightFluency", { it.weightFluency }) { copy(weightFluency = it) },
            float("risingSlopeSt", { it.risingSlopeSt }) { copy(risingSlopeSt = it) },
            float("fallingSlopeSt", { it.fallingSlopeSt }) { copy(fallingSlopeSt = it) },
        ),
    )

    val thresholds = Codec(
        DSPThresholds(),
        listOf(
            int("version", { it.version }) { copy(version = it) },
            nested("analysis", analysis, { it.analysis }) { copy(analysis = it) },
            nested("bands", bands, { it.bands }) { copy(bands = it) },
            nested("pitch", pitch, { it.pitch }) { copy(pitch = it) },
            nested("envelope", envelope, { it.envelope }) { copy(envelope = it) },
            nested("fricative", fricative, { it.fricative }) { copy(fricative = it) },
            nested("sibilant", sibilant, { it.sibilant }) { copy(sibilant = it) },
            nested("lateral", lateral, { it.lateral }) { copy(lateral = it) },
            nested("trill", trill, { it.trill }) { copy(trill = it) },
            nested("tap", tap, { it.tap }) { copy(tap = it) },
            nested("syllable", syllable, { it.syllable }) { copy(syllable = it) },
            nested("hesitation", hesitation, { it.hesitation }) { copy(hesitation = it) },
            nested("live", live, { it.live }) { copy(live = it) },
            nested("capture", capture, { it.capture }) { copy(capture = it) },
            nested("judge", judge, { it.judge }) { copy(judge = it) },
            nested("stress", stress, { it.stress }) { copy(stress = it) },
            nested("prosody", prosody, { it.prosody }) { copy(prosody = it) },
            nested("delivery", delivery, { it.delivery }) { copy(delivery = it) },
        ),
    )
}
