package tech.fonia.judge

import tech.fonia.dsp.SoundOutcome
import tech.fonia.dsp.TalkerCalibration
import tech.fonia.dsp.VoiceGroup
import tech.fonia.model.ErrorKind
import tech.fonia.model.SoundId
import tech.fonia.model.Verdict

/** How much the child says around the sound (plan 03 §8: isolated and syllables have no word gate). */
enum class Level(val raw: String) { Isolated("isolated"), Syllable("syllable"), Word("word"), Sentence("sentence") }

/** Where the target sits in the word. [Any] judges every occurrence (sentences, or when the caller does not know). */
enum class Position(val raw: String) { Initial("initial"), Medial("medial"), Final("final"), Any("any") }

/** Whose voice: picks the group prior of the S/Ș boundary until the child has anchors (plan 03 §6). */
enum class TalkerGroup(val raw: String, val voice: VoiceGroup) {
    Child("child", VoiceGroup.CHILD),
    Woman("woman", VoiceGroup.FEMALE),
    Man("man", VoiceGroup.MALE),
}

/** What was asked of this take. */
data class JudgeRequest(
    val sound: SoundId,
    val level: Level,
    val text: String,
    val position: Position = Position.Any,
    val talker: TalkerGroup = TalkerGroup.Child,
    /** The child's own anchors (adult-confirmed good takes); null = group prior. */
    val calibration: TalkerCalibration? = null,
    /** The child's median F0 from calibration, for the adult-voice gate; null = population default. */
    val childF0Hz: Float? = null,
)

/** Why a take must be said again. A retake is not an attempt: it never counts (plan 03 §8). */
enum class RetakeReason(val raw: String) {
    Clipped("clipped"),
    TooQuiet("tooQuiet"),
    TooShort("tooShort"),
    NoSpeech("noSpeech"),
    Noisy("noisy"),
    Dropped("dropped"),
    AdultVoice("adultVoice"),
    OtherWord("otherWord"),
}

/** Which fusion head decided. */
enum class Head(val raw: String) {
    /** Measurements only, ≤ 100 ms after the take ends (N6). */
    Fast("fast"),

    /** Measurements plus the phoneme model's log-probability ratios (≤ 600 ms). */
    Full("full"),

    /** A gate decided before any head ran. */
    Gate("gate"),
}

/** What the session layer may tell the adult (never the child). */
data class AdultNote(
    val kind: ErrorKind,
    val probability: Float,
    val heardAs: SoundId?,
    /** "în probă": the kind is in trial in `spec/unlock.json`, so the adult sees it with that mention only. */
    val inTrial: Boolean,
)

/** Capture facts kept with every take (no audio). */
data class CaptureReport(
    val durationSeconds: Double,
    val sampleRate: Double,
    val speechSeconds: Double,
    val speechRmsDb: Float,
    val dynamicRangeDb: Float,
    val clippedFraction: Float,
    val bandwidthHz: Float,
    val dropped: Int,
    /** 4–8 kHz energy of the target frication over the clip's 4–8 kHz floor, dB; NaN when there is no frication target. */
    val bandSnrDb: Float,
    /** 95th percentile minus floor of the clip's 4–8 kHz levels; NaN when not measured. */
    val bandRangeDb: Float = Float.NaN,
    val f0MedianHz: Float,
    /** "full" (≥ 16 kHz), "noLateral" (11–16 kHz), "presenceOnly" (below 11 kHz or rate < 32 kHz). */
    val bandwidthTier: String,
)

/** The versions of every data file the decision used (saved in the trace, plan 03 §1). */
data class SpecVersions(
    val thresholds: String,
    val fusion: String,
    val unlock: String,
    val feedback: String,
    val featureVersion: String,
)

/** Everything measured, by name. NaN = not measured (the fusion head masks it). */
data class JudgeEvidence(
    val features: Map<String, Float>,
    /** The parity-locked DSP judge's own answer on the same clip (`judgeSibilant` / `judgeRhotic`), as a safety net. */
    val outcome: SoundOutcome?,
    val capture: CaptureReport?,
    /** Target segments found, seconds (start, end); empty when none. */
    val segments: List<Pair<Double, Double>>,
    /** Error kinds that could not be named on this take because their measurement was not valid (bandwidth, length…). */
    val blockedKinds: Set<ErrorKind>,
    /** Free-form reasons for the adult card and the trace ("rhoticContext", "lowBandwidth", "noTarget"…). */
    val notes: List<String>,
)

/**
 * The judge's answer for one take. No text: the session layer turns [verdict] into an animation and an instruction
 * through `spec/feedback.json` ([Feedback]).
 */
data class TakeResult(
    val verdict: Verdict,
    /** Probability of what was decided: p(correct) for Correct and Almost, p(kind) for NamedError, the largest class
     *  probability for Unsure, 1 for a gate's Retake. */
    val confidence: Float,
    val retakeReason: RetakeReason? = null,
    val head: Head,
    /** Class → probability ("correct" and the [ErrorKind.raw] of this sound's kinds); empty for a gate. */
    val probabilities: Map<String, Float> = emptyMap(),
    /** The most likely error kind and its probability, whatever was decided (trace / adult view). */
    val topError: ErrorKind? = null,
    val topErrorProbability: Float = 0f,
    /** The kind the detector would have named had `spec/unlock.json` allowed it (null when it was named or none). */
    val withheldKind: ErrorKind? = null,
    val adultNote: AdultNote? = null,
    val evidence: JudgeEvidence,
    val versions: SpecVersions,
    /** True when a fast-head verdict may be shown at once (p(correct) ≥ tauOkFast); otherwise wait for the full head. */
    val fastFinal: Boolean = false,
    val micros: Map<String, Long> = emptyMap(),
)

/** Optional phoneme-model evidence (ZIPA + GOP, `:engine:phones`). Absent → the fast head alone, with wider abstention. */
data class PhoneEvidence(
    /** `lpr(x)` = log P(target) − log P(x), keyed by candidate IPA label ("ʃ", "s", "t", "l", "j", "ʁ", "∅"…). */
    val lpr: Map<String, Float>,
    /** Word fit per label; < −3 with ≥ 10 frames means another word (plan 03 §8; word and sentence levels only). */
    val fitPerLabel: Float? = null,
    val frames: Int = 0,
    val model: String = "",
    /** Log posterior of the accepted realisation among all candidates (≤ 0); the fusion input `zipa.gop`. */
    val gop: Float? = null,
    /**
     * False when the alignment cannot be trusted (too few frames, poor fit, the winning candidate got no frame): the
     * judge then decides with the fast head and notes `phonesUnreliable`.
     */
    val reliable: Boolean = true,
    /** Where the target sits on the best CTC path, seconds of the clip (null for an omission or no alignment). */
    val targetSpan: Pair<Double, Double>? = null,
    /** Free-form, for the trace ("heard:ʃ", "poorFit", "noAlignment", "tooShort"…). */
    val notes: List<String> = emptyList(),
    /** Wall clock of the phone model on this take, microseconds (0 when not measured). */
    val micros: Long = 0,
)

/** Optional sentence/pair evidence from Whisper. It never proves the sound (it repairs a lateral S). */
data class WordEvidence(val otherWord: Boolean, val model: String = "")

/** Port for the phoneme model; `:engine:phones` implements it on the 16 kHz clip. */
fun interface PhoneScorerPort {
    fun score(samples16k: FloatArray, request: JudgeRequest): PhoneEvidence?
}

/** Port for the sentence checker (Whisper `judgeSentence` / `judgePair`). */
fun interface WordCheckerPort {
    fun check(samples16k: FloatArray, request: JudgeRequest): WordEvidence?
}

/** What the session knows at decision time (unlock `runtime` conditions, adult feedback offsets, plan 03 §7). */
data class RuntimeContext(
    val adultPresent: Boolean = false,
    /** 0 = no anchors, 1 = calibrated (≥ 10 anchors), plan 03 §6. */
    val calibrationTier: Int = 0,
    val camera: Boolean = false,
    /** Raised by 0.05 after 3 adult disagreements in 20 on a sound (plan 03 §7). */
    val childOkOffset: Float = 0f,
    val childErrOffset: Map<ErrorKind, Float> = emptyMap(),
)
