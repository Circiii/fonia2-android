package tech.fonia.dsp

data class TrillSegment(
    val start: Double,
    val end: Double,
    val rate: Float,
    val periodicity: Float,
)

data class TapEvent(
    val time: Double,
    /** Half-depth width of the dip. */
    val closureMs: Float,
    val depthDb: Float,
    /** Fit to the 24-29 ms closure prior, 0...1. */
    val score: Float,
)

data class PitchStatistics(
    val medianHz: Float,
    val sdSt: Float,
    val rangeSt: Float,
    val voicedShare: Float,
)

data class LoudnessStatistics(
    val meanDb: Float,
    val sdDb: Float,
)

/** One analysis hop of a clip, ready for drawing and for the stress and prosody comparisons. */
data class TrackPoint(
    val time: Double,
    val db: Float,
    val vowelDb: Float,
    /** Strict F0, 0 when unvoiced. */
    val hz: Float,
    /** Looser, octave-folded F0 for melody, 0 outside speech. */
    val melodyHz: Float,
    val hfShare: Float,
    /** `null` outside frication. */
    val fricative: SibilantPlace?,
    val speaking: Boolean,
)

/**
 * Speech and pitch evidence from models that ran after the take. When present it replaces the
 * energy detector and the classical pitch track.
 */
class SpeechEvidence(
    val segments: List<TimeSpan>? = null,
    val f0: F0Track? = null,
) {
    class F0Track(
        val hopSeconds: Double,
        val hz: FloatArray,
        val confidence: FloatArray,
    )
}

data class ClipSummary(
    val durationSeconds: Double,
    val sampleRate: Double,
    val floorDb: Float,
    val peakDb: Float,
    val speechStart: Double,
    val speechEnd: Double,
    val fricatives: List<FricativeSegment>,
    val trills: List<TrillSegment>,
    val taps: List<TapEvent>,
    /** Times of syllable nuclei. */
    val syllables: List<Double>,
    val pauses: List<TimeSpan>,
    /** Steady voiced stretches next to a pause. */
    val hesitations: List<TimeSpan>,
    val pitch: PitchStatistics,
    val loudness: LoudnessStatistics,
    /** Syllables per second while speaking, pauses excluded. */
    val articulationRate: Float,
    /** Syllables per second over the whole speaking span. */
    val speechRate: Float,
    /** Pitch variation quotient: SD over mean of F0. */
    val pvq: Float,
    /** F0 change over the last third of voiced speech, in semitones. Questions rise. */
    val finalSlopeSt: Float,
    val speechSeconds: Double,
    val track: List<TrackPoint>,
    val trackHopSeconds: Double,
    val quality: CaptureQuality,
    val norm: SibilantNorm,
    /** A voice-activity model decided where speech is. */
    val usedSpeechModel: Boolean,
    val usedPitchModel: Boolean,
)
