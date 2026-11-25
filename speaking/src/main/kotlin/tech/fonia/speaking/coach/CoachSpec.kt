package tech.fonia.speaking.coach

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Every number the speech coach decides with. The source of truth is `spec/thresholds/speech-coach.json` (bundled in
 * the jar at the same path); the defaults here mirror it so a missing file still works, and `CoachSpecTest` fails when
 * the two drift. Save [version] with every attempt. Where the numbers come from: `speaking/COACH.md`.
 */
@Serializable
data class CoachSpec(
    val version: String = "coach-1",
    val hopSeconds: Double = 0.01,
    val pace: Pace = Pace(),
    val voice: Voice = Voice(),
    val pitch: Pitch = Pitch(),
    val gaze: Gaze = Gaze(),
    val quality: Quality = Quality(),
    val calibration: Calibration = Calibration(),
    val motion: Motion = Motion(),
    val face: Face = Face(),
    val liveliness: Liveliness = Liveliness(),
    val cues: Cues = Cues(),
    val smoothing: Smoothing = Smoothing(),
    val report: Report = Report(),
) {
    /** Syllable nuclei (de Jong & Wempe 2009) on the vowel band, streaming, and the articulation rate over a window. */
    @Serializable
    data class Pace(
        val vowelLowHz: Double = 300.0,
        val vowelHighHz: Double = 2500.0,
        /** A nucleus stands this far above the dips on both sides (de Jong & Wempe: 2 dB; best with 30 dB and 80 ms on the RO clips, COACH.md). */
        val minProminenceDb: Double = 2.0,
        /** ...and no further than this under the loud vowels (the running 95th percentile of the speech). */
        val belowPeakDb: Double = 30.0,
        val minGapSeconds: Double = 0.08,
        /** How long a peak waits for its right-hand dip: the live delay of the rate. */
        val lookAheadSeconds: Double = 0.12,
        val valleyLookSeconds: Double = 0.12,
        /** A nucleus needs a pitch within this reach (voiced frames only). */
        val voicedReachSeconds: Double = 0.03,
        /** Time constant of the forgetting histogram behind the 95th percentile. */
        val peakForgetSeconds: Double = 20.0,
        val windowSeconds: Double = 4.0,
        /** Less speech than this in the window: the rate is unknown (NaN), no cue. */
        val minSpeechInWindowSeconds: Double = 1.5,
        val pauseMinSeconds: Double = 0.25,
        val longPauseSeconds: Double = 2.0,
        /** A silence up to this long is a breath or a full stop (good); longer ones count as hesitations. */
        val goodPauseMaxSeconds: Double = 1.0,
        /**
         * The intensity peaks find about this share of the syllables of real speech (0.37–0.95 on the real-video
         * bench, COACH.md); their rate is divided by it so both sources read in syllables.
         */
        val envelopeRecall: Double = 0.75,
        /** The phone model's vowels older than this are not used: the rate falls back to the intensity peaks. */
        val modelStaleSeconds: Double = 6.0,
        /** Between two vowels, more than this is a pause: the gap counts only up to here. */
        val vowelGapCapSeconds: Double = 0.6,
        /** Fewer gaps than this in the window: the rate is unknown. */
        val minVowelGaps: Int = 6,
        /**
         * The target band, in syllables per second of speaking time (pauses of [pauseMinSeconds] or more left out).
         * The ten bench speakers read 4.3–5.7 over a whole talk (4 s windows: 3.1–7.5).
         */
        val bandLow: Double = 3.3,
        val bandHigh: Double = 6.6,
        val gaugeMin: Double = 2.0,
        val gaugeMax: Double = 8.0,
    )

    /** Voice activity, noise floor, his own speaking level. */
    @Serializable
    data class Voice(
        val initialFloorDb: Double = -60.0,
        val floorRiseDbPerSecond: Double = 3.0,
        val floorFallRate: Double = 0.3,
        val vadMarginDb: Double = 10.0,
        val vadHangSeconds: Double = 0.2,
        val levelWindowSeconds: Double = 1.5,
        /** His reference level is trusted after this much speech. */
        val referenceAfterSpeechSeconds: Double = 4.0,
        val referenceForgetSeconds: Double = 120.0,
        /** His reference is this percentile of his speech levels: a quiet stretch must outlast most of the talk to move it. */
        val referencePercentile: Double = 0.8,
        val quietBelowReferenceDb: Double = 6.0,
        /** Speech this close to the room's floor is too quiet whatever his habit. */
        val minSnrDb: Double = 4.0,
        val gaugeBelowReferenceDb: Double = 15.0,
        val gaugeAboveReferenceDb: Double = 6.0,
    )

    /** Melody: the spread of f0 in semitones (Hincks 2005 measures the same with PVQ = sd/mean of f0). */
    @Serializable
    data class Pitch(
        val windowSeconds: Double = 8.0,
        val minVoicedSeconds: Double = 2.0,
        val referenceHz: Double = 100.0,
        /** Robust spread ((p90 − p10) / 2.56, ≈ one SD) under which the melody counts as flat... */
        val flatSpreadSt: Double = 1.5,
        /** ...and over which as fully lively. */
        val livelySpreadSt: Double = 3.0,
    )

    /**
     * Looking at the lens: where the head points plus where the eyes point inside it. Looking into the camera makes
     * the two cancel, so the sum is near zero for anyone; the countdown baseline only corrects a small personal
     * bias ([baselineClampDeg]). Gains, offset and tolerances fitted on the real-video bench (COACH.md).
     */
    @Serializable
    data class Gaze(
        /** Degrees of eye turn for an iris moved from the middle to a corner of the eye. */
        val irisGainDeg: Double = 60.0,
        /** Degrees of eye turn for eyeLookUp − eyeLookDown = 1. */
        val lookGainDeg: Double = 25.0,
        /** The look-down score reads a little low on every face: added to the vertical sum. */
        val verticalOffsetDeg: Double = 5.0,
        /** The baseline may move the centre by at most this (a baseline taken while reading must not become "the lens"). */
        val baselineClampDeg: Double = 0.0,
        val baselineSeconds: Double = 2.0,
        val toleranceYawDeg: Double = 11.0,
        /** Up is forgiven more than down: notes and the screen sit below the lens (bench: the reader stays caught). */
        val tolerancePitchUpDeg: Double = 10.0,
        val tolerancePitchDownDeg: Double = 7.0,
        /** Without a baseline the default one is less sure: wider tolerance. */
        val fallbackToleranceScale: Double = 1.0,
        val enterBelow: Double = 0.85,
        val leaveAbove: Double = 1.15,
        val smoothingSeconds: Double = 0.25,
        val windowSeconds: Double = 2.5,
        /** "Uită-te la cameră" when he looked away for this share of the window. */
        val awayFractionCue: Double = 0.6,
    )

    /**
     * Is the picture and the sound good enough to measure on? A frame that fails is not measured at all (no reading
     * is better than a wrong one); one quiet hint says what to change. Sizes are shares of the picture's width.
     */
    @Serializable
    data class Quality(
        /** Distance between the eyes ÷ picture width: smaller is too far, larger too close. */
        val minEyeSpan: Double = 0.10,
        val maxEyeSpan: Double = 0.42,
        /** The nose bridge must stay this far from every edge (share of the width / height). */
        val edgeMargin: Double = 0.12,
        /** Mean brightness of the face, 0…255. */
        val minLuma: Double = 45.0,
        /** Room noise louder than this (dBFS): the voice measures are less sure, the loudness and liveliness cues stay off. */
        val maxNoiseDb: Double = -38.0,
        /** A problem must last this long before its hint shows, and the hint then stays at least as long. */
        val hintAfterSeconds: Double = 1.5,
        /** A score is given only when at least this share of the talk could be measured. */
        val minCoverage: Double = 0.5,
    )

    /** The six-second gaze calibration: what makes it valid and how his tolerances come out of it. */
    @Serializable
    data class Calibration(
        val stepSeconds: Double = 2.0,
        val minFrames: Int = 8,
        /** The bottom line must read at least this far under the lens. */
        val minSeparationDeg: Double = 3.0,
        /** Down tolerance = this share of the way from the lens to the bottom line; up likewise to the top line. */
        val downShare: Double = 0.6,
        val upShare: Double = 0.8,
        val minToleranceDeg: Double = 4.0,
        val maxToleranceDeg: Double = 14.0,
        val maxCentreDeg: Double = 10.0,
    )

    /** Swaying and bobbing: spread of the head's position (÷ eye distance) and angles over a window. */
    @Serializable
    data class Motion(
        val windowSeconds: Double = 3.0,
        val positionToleranceEyes: Double = 0.22,
        val angleToleranceDeg: Double = 6.0,
        val cueIndex: Double = 1.6,
        val gaugeMaxIndex: Double = 2.4,
    )

    /** Facial movement: how much brows, smile muscles and nods vary (never what they "mean"). */
    @Serializable
    data class Face(
        val windowSeconds: Double = 5.0,
        val browWeight: Double = 1.0,
        val smileWeight: Double = 0.7,
        /** Per degree of head-pitch spread. */
        val nodWeight: Double = 0.005,
        val activityLow: Double = 0.03,
        val activityHigh: Double = 0.18,
    )

    @Serializable
    data class Liveliness(
        val pitchWeight: Double = 0.65,
        val faceWeight: Double = 0.35,
        val cueBelow: Double = 0.33,
    )

    @Serializable
    data class Cues(
        /** No cue (except the lens) in the first seconds of speech: nothing is known yet. */
        val warmupSeconds: Double = 4.0,
        /** A shown cue stays at least this long, so it never flickers. */
        val minShowSeconds: Double = 2.2,
        /** ...and at most this long in one go, so one habit does not hide the others. */
        val maxShowSeconds: Double = 8.0,
        /** A cue that cleared does not come back before this. */
        val cooldownSeconds: Double = 12.0,
        /** Nothing between two cues for this long (one thing at a time). */
        val gapSeconds: Double = 4.0,
        /** How long a condition must hold before its cue shows. */
        val onSeconds: Map<String, Double> = mapOf(
            "look" to 2.5, "louder" to 3.0, "slower" to 2.0, "faster" to 2.5, "calmer" to 3.0, "livelier" to 5.0,
        ),
        val goodAfterSeconds: Double = 3.0,
        val goodShowSeconds: Double = 2.4,
        val goodCooldownSeconds: Double = 20.0,
        /** A voice cue (pace, loudness) holds through pauses shorter than this. */
        val silenceHoldsCueSeconds: Double = 1.2,
    )

    /** The gauges' envelope follower: fast up, slow down, so they never tremble. */
    @Serializable
    data class Smoothing(
        val attackSeconds: Double = 0.08,
        val releaseSeconds: Double = 0.3,
    )

    @Serializable
    data class Report(
        val tickSeconds: Double = 0.25,
        val minMomentSeconds: Double = 1.5,
        val focusBelow: Int = 80,
        val fillerPenaltyPerMinute: Double = 6.0,
        val maxFillerPenalty: Double = 30.0,
        val changeWorthMentioning: Int = 8,
        /** Syllables in an average Romanian word, for the estimated words per minute (until the transcript gives real words). */
        val syllablesPerWord: Double = 2.4,
        /** The best area is named first when it scored at least this. */
        val praiseFrom: Int = 70,
    )

    fun onSeconds(cue: Cue): Double = cues.onSeconds[cue.key] ?: 2.0

    companion object {
        const val RESOURCE = "/spec/thresholds/speech-coach.json"

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): CoachSpec = json.decodeFromString(serializer(), text)

        /** The bundled `spec/thresholds/speech-coach.json`, or the code defaults when it is missing. */
        val v1: CoachSpec by lazy {
            CoachSpec::class.java.getResourceAsStream(RESOURCE)?.use { parse(it.readBytes().decodeToString()) } ?: CoachSpec()
        }
    }
}
