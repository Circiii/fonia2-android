package tech.fonia.speaking

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Every number the speaking engine decides with, plus the Romanian filler lexicon. The source of truth is
 * `spec/thresholds/speaking.json` (bundled in the jar at the same path); the defaults here mirror it so a missing
 * file still works, and `SpeakingThresholdsTest` fails when the two drift. Save [version] with every take.
 */
@Serializable
data class SpeakingThresholds(
    val version: String = "speaking-1",
    val analysis: Analysis = Analysis(),
    val hesitation: Hesitation = Hesitation(),
    val pause: Pause = Pause(),
    val cut: Cut = Cut(),
    val prosody: Prosody = Prosody(),
    val quality: Quality = Quality(),
    val lexicon: Lexicon = Lexicon(),
) {
    @Serializable
    data class Analysis(
        /** Analysis runs on a 16 kHz copy; the cut itself always works on the original samples. */
        val analysisRate: Double = 16000.0,
        val hopSeconds: Double = 0.01,
        val windowSeconds: Double = 0.032,
        /** Mel bands, and the cepstral coefficients (c1…cN) whose change measures spectral steadiness. */
        val bands: Int = 26,
        val cepstra: Int = 12,
        val bandLowHz: Double = 100.0,
        val bandHighHz: Double = 4000.0,
        /** The room-tone floor is this percentile of frame levels. */
        val floorPercentile: Double = 0.05,
        /** A frame is speech when it is this far above the floor... */
        val speechMarginDb: Double = 10.0,
        /** ...and no more than this below the loud frames (95th percentile). */
        val speechRangeDb: Double = 50.0,
        /** A steady (hesitation) frame must be at least this far above the floor. */
        val minSpeechDbAboveFloor: Double = 6.0,
        val speechHangSeconds: Double = 0.03,
        val minSpeechRunSeconds: Double = 0.05,
    )

    /** The acoustic detector of sustained hesitation sounds ("ăăă", "îîî", "mmm"). */
    @Serializable
    data class Hesitation(
        val minSeconds: Double = 0.22,
        val maxSeconds: Double = 2.5,
        /** Change of the spectral envelope (mel cepstrum, 20 ms apart), as an RMS over the mel bands, dB. */
        val maxSpectralChangeDb: Double = 2.6,
        val maxMeanSpectralChangeDb: Double = 1.8,
        /** Pitch spread around its own linear trend, semitones. */
        val maxPitchSdSt: Double = 1.2,
        val maxPitchSlopeStPerSecond: Double = 45.0,
        val maxLevelSdDb: Double = 6.0,
        val minVoicedShare: Double = 0.6,
        /** A side is "bounded" when this much non-speech lies within [boundaryReachSeconds] of the steady run. */
        val boundaryPauseSeconds: Double = 0.04,
        val boundaryReachSeconds: Double = 0.15,
        /** A steady run this long, bounded on one side only, still counts as a hesitation (with less confidence). */
        val longAloneSeconds: Double = 0.5,
        /** A lengthened word ending ("șiii") keeps this much of the vowel when the rest is offered for cutting. */
        val keepLengthenedSeconds: Double = 0.14,
        /** Energy above 1 kHz relative to below, dB: under this the sound is a hum ("mmm"). Cosmetic label only. */
        val nasalHighBandDb: Double = -22.0,
        /** Under this first-formant estimate the vowel is labelled "îîî", else "ăăă". Cosmetic label only. */
        val closeVowelF1Hz: Double = 440.0,
        /** Frames of tolerance inside a steady run. */
        val gapFrames: Int = 2,
        /** Share of steady frames a run needs (a run chained through many unsteady frames is speech). */
        val minSteadyShare: Double = 0.8,
        /** A side with no pause reaches to the deepest level dip this close, if it is this far below the run. */
        val valleySearchSeconds: Double = 0.15,
        val valleyDepthDb: Double = 20.0,
        /** The dip search stops at a frame with this much high-band energy (a fricative: the next word began). */
        val valleyMaxHighLowDb: Double = 0.0,
        /**
         * Second cue, "sonorant island": a stretch between two pauses (or deep dips), at least [islandMinSeconds] long,
         * voiced, with no frication or burst anywhere ([islandMaxHighLowDb]) and a mostly steady envelope. Catches
         * hesitations said with intonation ("ăă-ăă"), which the steady-run cue misses. Less sure: [islandConfidence].
         */
        val islandMinSeconds: Double = 0.3,
        val islandMaxHighLowDb: Double = -12.0,
        val islandMedianChangeDb: Double = 2.6,
        val islandP90ChangeDb: Double = 5.0,
        val islandMaxPitchSdSt: Double = 2.5,
        val islandMinVoicedShare: Double = 0.7,
        val islandDipDb: Double = 25.0,
        val islandEdgeSilenceSeconds: Double = 0.03,
        val islandConfidence: Double = 0.62,
    )

    @Serializable
    data class Pause(
        val minPauseSeconds: Double = 0.25,
        /** Pauses longer than this are brought down to [shortenToSeconds] (plan 01 §10, NO-UM step 4). */
        val longPauseSeconds: Double = 1.2,
        val shortenToSeconds: Double = 0.6,
        /** When a cut joins two silences, what is left is capped to about [joinedPauseTargetSeconds]. */
        val joinedPauseMaxSeconds: Double = 0.55,
        val joinedPauseTargetSeconds: Double = 0.4,
    )

    @Serializable
    data class Cut(
        /** Equal-power crossfade at a join between sounds; [crossfadeSilenceMs] when both sides are room tone. */
        val crossfadeMs: Double = 12.0,
        val crossfadeSilenceMs: Double = 20.0,
        /** Each cut edge moves at most this far to reach the quietest point... */
        val snapSearchMs: Double = 25.0,
        val energyWindowMs: Double = 2.5,
        /** ...and then to the nearest zero crossing. */
        val zeroCrossSearchMs: Double = 1.5,
        /** Kept words are protected by this margin, [guardUncertainMs] when their timing is doubtful. */
        val guardMs: Double = 25.0,
        val guardUncertainMs: Double = 60.0,
        /** A word edge moved onto a measured level dip next to a cut word is exact enough for this smaller guard. */
        val guardRefinedMs: Double = 10.0,
        /** How far a recognised word boundary next to a cut word may move to reach the level dip between them. */
        val edgeSearchMs: Double = 80.0,
        /** ...but only this far into the cut word itself (a closure inside it would leave a fragment). */
        val edgeInwardMs: Double = 20.0,
        /** The dip must be this far below the softer word's peak, or the boundary stays where the recogniser put it. */
        val edgeDipDb: Double = 15.0,
        /** A recognised word under this probability has doubtful timing. */
        val uncertainProbability: Double = 0.5,
        val minCutMs: Double = 60.0,
        val mergeGapMs: Double = 40.0,
        /** A span is cut by default from this confidence on; below [sureConfidence] it is drawn dotted ("unsure"). */
        val cutConfidence: Double = 0.6,
        val sureConfidence: Double = 0.8,
        /** A recognised word is never trimmed shorter than this when the recogniser's end swallowed a hesitation. */
        val wordTrimMinSeconds: Double = 0.08,
        /**
         * A recognised word "swallowed" a hesitation (its end or start was pulled over it) only when it is longer than
         * `letters × secondsPerLetter` by at least [swallowShare] of the overlap; otherwise the steady sound is the
         * word's own held vowel ("copiii") and is left alone.
         */
        val secondsPerLetter: Double = 0.07,
        val swallowShare: Double = 0.7,
    )

    @Serializable
    data class Prosody(
        /** Pitch change across a word beyond which it counts as a rise or a fall, semitones. */
        val movementSt: Double = 1.5,
        /** Local rate looks this far either side of the word. */
        val rateWindowSeconds: Double = 1.5,
        val loudLowPercentile: Double = 0.1,
        val loudHighPercentile: Double = 0.95,
        /** Without a transcript, speech is cut into chunks at pauses this long... */
        val chunkSplitPauseSeconds: Double = 0.15,
        /** ...and at energy valleys so no chunk is longer than this. */
        val chunkMaxSeconds: Double = 0.9,
    )

    /** The objective join checks of [CutQuality]. */
    @Serializable
    data class Quality(
        /** Largest sample step across a join over the largest step near the two original edges. */
        val maxJumpRatio: Double = 2.0,
        /** Energy of the first difference (a click is broadband) across a join over the same near the edges. */
        val maxClickRatio: Double = 2.5,
        /** A cut edge is "quiet" when it is at least this far below the speech median level. */
        val edgeQuietDb: Double = 12.0,
        val clickWindowMs: Double = 5.0,
    )

    @Serializable
    data class Marker(
        val text: String,
        val confidence: Double,
        /** `setApart`: pause or punctuation on both sides; `clauseStart`: at a clause start; `aloneAtStart`: both, and first in its clause. */
        val rule: String = "setApart",
    )

    /** Romanian fillers (content-like data, kept with the thresholds so one file versions both). */
    @Serializable
    data class Lexicon(
        val hesitationPattern: String = "ă+m*|â+m*|î+m*|ə+|e{2,}m*|a{2,}h*|o{2,}|u+h*m+|h+m+|m{2,}|ăh+|eh+",
        val hesitationConfidence: Double = 0.9,
        /**
         * A recognised "ăăă" with no steady sound near it: the recogniser's timing of fillers is often off by a word,
         * so it is offered (dotted) but not cut by default.
         */
        val unconfirmedHesitationConfidence: Double = 0.55,
        val markers: List<Marker> = emptyList(),
        /** A marker followed by one of these words is meaningful ("știi că…"): never a filler. */
        val keepAfter: Map<String, List<String>> = emptyMap(),
        /** A marker preceded by one of these words is meaningful ("e bine", "un exercițiu practic"). */
        val keepBefore: Map<String, List<String>> = emptyMap(),
        val setApartGapSeconds: Double = 0.15,
        val repetitionConfidence: Double = 0.75,
        val falseStartConfidence: Double = 0.7,
        /** Doubled for emphasis ("foarte foarte"), not a repetition. */
        val keepDoubles: List<String> = emptyList(),
        val falseStartMinPrefix: Int = 1,
        /** Whole words that are never read as the broken-off start of the next one. */
        val falseStartNeverWords: List<String> = emptyList(),
    )

    companion object {
        const val RESOURCE = "/spec/thresholds/speaking.json"

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): SpeakingThresholds = json.decodeFromString(serializer(), text)

        /** The bundled `spec/thresholds/speaking.json`, or the code defaults when it is missing. */
        val v1: SpeakingThresholds by lazy {
            SpeakingThresholds::class.java.getResourceAsStream(RESOURCE)?.use { parse(it.readBytes().decodeToString()) }
                ?: SpeakingThresholds()
        }
    }
}
