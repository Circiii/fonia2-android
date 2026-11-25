package tech.fonia.judge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import tech.fonia.model.ErrorKind
import tech.fonia.model.SoundId
import java.io.File

// The judge's data files (docs/REGULI.md: thresholds, coefficients and durations are data in spec/, with a version saved
// on each take). Android bundles the same files as assets and passes their text to [JudgeSpec.fromStrings].

@Serializable
data class PopulationThresholds(
    val schema: Int = 1,
    val version: String,
    val source: String = "",
    val capture: CaptureThresholds = CaptureThresholds(),
    val word: WordThresholds = WordThresholds(),
    val measure: MeasureThresholds = MeasureThresholds(),
    /** v2 (2026-11-24): context, praise, bursts, the visual block, personal references. Absent = all off. */
    val judge2: Judge2Thresholds = Judge2Thresholds(),
)

/**
 * The v2 coefficients (`population.json` → `judge2`, fitted with leave-one-voice-out on synthetic voices, see
 * `spec/fusion/EVAL_v2.md`). Every default is "off", so a spec without the section behaves as v1.
 */
@Serializable
data class Judge2Thresholds(
    val version: String = "off",
    /**
     * Vowel context (plan 03 §6): S before i/e is palatalised and reads Ș-ward. Keyed "<place>:<next vowel>" ("s:i",
     * "ș:e"; place s for S/Z/Ț, ș for Ș/J/CE/GE): added to the logit of sNess before the head runs (`toward` follows).
     */
    val contextShift: Map<String, Float> = emptyMap(),
    /**
     * Place trust: with `toward` ≥ this, the phoneme model's place candidate is masked (the spectrum is clearly in the
     * target zone). 2 = off.
     */
    val placeTrust: Float = 2f,
    val praise: PraiseThresholds = PraiseThresholds(),
    val burst: BurstThresholds = BurstThresholds(),
    val visual: VisualThresholds = VisualThresholds(),
    val personal: PersonalThresholds = PersonalThresholds(),
    val affricate: AffricateThresholds = AffricateThresholds(),
    val finalL: FinalLThresholds = FinalLThresholds(),
)

/**
 * Affricate onset gates (`Features.STOP_ONSET`, `docs/JUDECATOR_AFRICATE.md`): ZIPA-small has no [tʃ]/[dʒ] token and
 * hears [s]/[ʃ] in good CE and [ʒ] in good GE (EVAL_v2), while the DSP can hear the closure and release. Off until
 * real takes give before/after numbers: no sounds listed, [trustAt] 2, [vetoAt] −1.
 */
@Serializable
data class AffricateThresholds(
    /**
     * Trust, keyed by sound ("ce", "ge"): these phoneme-model candidates ("ʃ", "s") are set aside, and the GOP recomputed
     * without them, when `stopOnset` ≥ [trustAt]. It raises acceptance, so it ships only if praised errors stay ≤ 5 %.
     */
    val trust: Map<String, List<String>> = emptyMap(),
    val trustAt: Float = 2f,
    /** Veto: on these sounds a take whose `stopOnset` ≤ [vetoAt] (the frication swells like a fricative) is not praised. */
    val veto: List<String> = emptyList(),
    val vetoAt: Float = -1f,
)

/**
 * Word-final L („val”, „cal”): ZIPA has no dark-L token, so a good final L loses mass to [w]/[u] and the full head names
 * lToGlide on it. With [minGlideNats] > 0, at a word-final L lToGlide stands only when `lpr.j+w` ≤ −[minGlideNats];
 * weaker glide evidence is set aside: [mask] false → a named lToGlide becomes Almost (never praise); [mask] true → the
 * glide candidates are masked before the head, which may then praise. 0 = off.
 */
@Serializable
data class FinalLThresholds(val minGlideNats: Float = 0f, val mask: Boolean = false)

/**
 * Praise when both heads agree (owner 2026-11-24: "when he says a sound right the app must say so"): with a reliable
 * full head, Correct when p_full ≥ [full] AND p_fast ≥ [fast] AND the phoneme model's best candidate is the target,
 * even under `tauOk`. 2 = off.
 */
@Serializable
data class PraiseThresholds(val fast: Float = 2f, val full: Float = 2f)

/**
 * Bursts ("ț-ț-ț", "ci-ci-ci", "gi-gi-gi", plan 03 §2.5): the take is judged on the median burst (by p(correct))
 * instead of the worst, when at least [minFound] of the bursts were found. 0 = off (v1: the worst one speaks).
 */
@Serializable
data class BurstThresholds(val minFound: Int = 0)

/** Target zones and floors of the visual block ([TakeVisuals]). */
@Serializable
data class VisualThresholds(
    /** Half-width of the uncertain middle of the S↔Ș axis: S zone is [0.5 + h, 1], Ș zone [0, 0.5 − h]. */
    val placeZoneHalf: Float = 0.1f,
    val voicedFraction: Float = 0.5f,
    val dipFloorDb: Float = 3f,
    val dipFullDb: Float = 12f,
    val trillNeeded: Float = 0.5f,
    val closureGapMs: Float = 15f,
    val closureBurstDb: Float = 6f,
    val releaseMinMs: Float = 25f,
    val wordMinMs: Float = 60f,
    val isolatedMinMs: Float = 250f,
    val relLevelFloorDb: Float = -25f,
    /** A personal floor sits this far under the person's own median level. */
    val personalLevelMarginDb: Float = 8f,
    /** An error class below this probability does not choose the hint (the cues do). */
    val minKindProbability: Float = 0.35f,
)

/** When a take may become part of the person's reference ([PersonalReference.update]). */
@Serializable
data class PersonalThresholds(
    /** p(correct) of the full head, and of the fast head, both needed. */
    val minFull: Float = 0.9f,
    val minFast: Float = 0.8f,
    /** Anchors per place before the person's own S/Ș boundary is used (the DSP's fit also needs it). */
    val minPerPlace: Int = 5,
    /** Takes kept per sound for the medians. */
    val window: Int = 20,
    /** An anchor whose sNess sits in the uncertain middle (|sNess − 0.5| < this) is never taken. */
    val minPlaceMargin: Float = 0.2f,
)

@Serializable
data class CaptureThresholds(
    /** Lost samples allowed in the take (plan 03 §8: > 0 → Retake(Dropped)). */
    val maxDropped: Int = 0,
    /** 4–8 kHz frication over the clip's 4–8 kHz floor; below → Retake(Noisy) at sibilants and affricates. */
    val minBandSnrDb: Float = 15f,
    /** A take with no dynamic range whose RMS is above this is noise, not silence. */
    val noisyRmsDb: Float = -35f,
    val bandSnrLowHz: Float = 4000f,
    val bandSnrHighHz: Float = 8000f,
    /** A child target whose median F0 is under ratio × the child's F0 is an adult speaking. */
    val adultVoiceRatio: Float = 0.7f,
    val childF0Hz: Float = 260f,
    val fullBandwidthHz: Float = 16000f,
    val placeBandwidthHz: Float = 11000f,
    val minSampleRateHz: Float = 32000f,
)

@Serializable
data class WordThresholds(
    val secondsPerPhoneme: Double = 0.09,
    val baseSeconds: Double = 0.15,
    val minRatio: Double = 0.5,
    val maxRatio: Double = 2.5,
    /** Phoneme-model word gate (plan 03 §8). */
    val minFitPerLabel: Float = -3f,
    val minFitFrames: Int = 10,
)

@Serializable
data class MeasureThresholds(
    val lookbackSeconds: Double = 0.15,
    val gapMarginDb: Float = 6f,
    val neighbourSeconds: Double = 0.15,
    /** Place needs ≥ 4 frames of 10 ms (plan 03 §3.10). */
    val minPlaceMs: Float = 40f,
    /**
     * Voicing is judged on frication of at least this length. The plan has 80 ms (today's code) and 60 ms for GE
     * (closure + frication); the prompt voices' Z and J last 40–90 ms, so v0 takes 40 ms (4 frames) [P].
     */
    val minVoicingMs: Float = 40f,
    val onsetSearchSeconds: Double = 0.25,
)

@Serializable
data class FusionInput(
    val name: String,
    val mean: Float = 0f,
    val scale: Float = 1f,
    val clip: List<Float> = listOf(-4f, 4f),
    /** "hasZipa" for phoneme-model inputs; informative only (a NaN input is always masked). */
    val mask: String? = null,
)

/** The four constants of [FullHead.build] (plan 03 §7 full head, v1). */
@Serializable
data class FullHeadParams(
    /** Weight of the fast head's logits (its temperature folded in). */
    val alpha: Float,
    /** Nats → logit: each error class gains −c · lpr(its candidate). */
    val c: Float,
    /** Weight of `zipa.gop` (log posterior of the target among all candidates) on "correct". */
    val g: Float,
    /** Added to the "correct" bias. */
    val correctBias: Float,
    /**
     * v2: the weight of the phone evidence on the PLACE classes (placeSwap, affricatePlace) when it differs from [c]:
     * on S/Ș the phoneme model hears [s] in good Ș (EVAL_v1), so its say on place is fitted per sound. Null = [c].
     */
    val cPlace: Float? = null,
)

@Serializable
data class FusionHeadSpec(
    val uses: String,
    val bias: List<Float>,
    /** One row per class, one column per input. */
    val weights: List<List<Float>>,
    val temperature: Float = 1f,
)

@Serializable
data class Isotonic(val x: List<Float>, val y: List<Float>)

@Serializable
data class DecisionSpec(
    val tauOk: Float,
    val tauOkFast: Float,
    val tauAlmost: Float,
    val delta: Float,
    val coarseMax: Float,
    val tauErr: Map<String, Float>,
    /** Added to tauErr when only the fast head decides (plan 03 §7: "its stricter thresholds"). */
    val tauErrFastExtra: Float = 0.05f,
)

@Serializable
data class FusionSpec(
    val schema: Int = 1,
    val sound: String,
    val version: String,
    val source: String = "",
    val featureVersion: String,
    val trainedOn: JsonElement? = null,
    val classes: List<String>,
    /** 1 presence, 2 manner, 3 voicing, 4 place, 5 distortion (coarse to fine, plan 03 §7). */
    val levels: Map<String, Int>,
    /** The sound heard instead, by class (N10), as `SoundId.raw`. */
    val heardAs: Map<String, String> = emptyMap(),
    val inputs: List<FusionInput>,
    val heads: Map<String, FusionHeadSpec>,
    val isotonic: Map<String, Isotonic> = emptyMap(),
    /** Class → the `lpr.<key>` input (a ZIPA candidate, `ConfusionSets` in `:engine:phones`) that names that error. */
    val phoneMap: Map<String, String> = emptyMap(),
    /** How the full head was made from the fast one ([FullHead.build]); null for a hand-written or trained full head. */
    val fullFrom: FullHeadParams? = null,
    val decision: DecisionSpec,
    val metrics: JsonElement? = null,
) {
    val soundId: SoundId get() = requireNotNull(SoundId.of(sound)) { "unknown sound '$sound'" }

    val kinds: List<ErrorKind> get() = classes.drop(1).map { kindOf(it) }

    fun level(kind: ErrorKind): Int = levels[kind.raw] ?: 5

    fun heardAs(kind: ErrorKind): SoundId? = heardAs[kind.raw]?.let { SoundId.of(it) }

    fun tauErr(kind: ErrorKind): Float = decision.tauErr[kind.raw] ?: 0.9f

    fun validate() {
        require(classes.firstOrNull() == "correct") { "$sound: the first class must be 'correct'" }
        classes.drop(1).forEach { kindOf(it) }
        for ((name, head) in heads) {
            require(head.bias.size == classes.size) { "$sound/$name: ${head.bias.size} biases for ${classes.size} classes" }
            require(head.weights.size == classes.size) { "$sound/$name: ${head.weights.size} weight rows for ${classes.size} classes" }
            head.weights.forEachIndexed { i, row ->
                require(row.size == inputs.size) { "$sound/$name: row $i has ${row.size} weights for ${inputs.size} inputs" }
            }
            require(head.temperature > 0f) { "$sound/$name: temperature must be > 0" }
        }
        require("fast" in heads) { "$sound: a 'fast' head is required" }
    }

    companion object {
        fun kindOf(raw: String): ErrorKind =
            ErrorKind.of(raw) ?: ErrorKind.entries.firstOrNull { it.name == raw } ?: error("unknown error kind '$raw'")
    }
}

@Serializable
data class UnlockRuntime(val calibrationTier: Int = 1, val adultPresent: Boolean = true, val tauErrExtra: Float = 0.07f)

@Serializable
data class UnlockRule(
    val minTokensPerClass: Int = 0,
    val minSpeakers: Int = 0,
    val minChildren: Int = 0,
    val maxFalseAlarm: Float? = null,
    val minPrecision: Float? = null,
    val runtime: UnlockRuntime? = null,
    /**
     * The `self` rule (v2): a teen or adult practising alone. A kind is named, and the visual hint shown, when its
     * probability reaches this (chosen from the held-out false-alarm rate, EVAL_v2.md). Null = the rule is off.
     */
    val minConfidence: Float? = null,
    /** The `self` rule: the kinds it may name (null = every kind of the sound). */
    val kinds: List<String>? = null,
    /** The `self` rule: who it applies to (talker groups "man", "woman"; profile kinds "adult", "teen"). Informative. */
    val appliesTo: List<String> = emptyList(),
    val note: String = "",
)

@Serializable
data class UnlockRequires(val minBandwidthHz: Float? = null, val camera: Boolean = false)

@Serializable
data class UnlockEntry(
    val sound: String,
    val kind: String,
    val wave: Int,
    /** "locked", "trial" ("în probă"), "unlocked". */
    val state: String,
    /** With "unlocked": "imitations" (runtime conditions of the pilot rule) or "children". */
    val evidenceLevel: String? = null,
    val requires: UnlockRequires = UnlockRequires(),
    val evidence: JsonElement? = null,
)

@Serializable
data class UnlockSpec(
    val schema: Int = 1,
    val version: String,
    val rules: Map<String, UnlockRule> = emptyMap(),
    val entries: List<UnlockEntry>,
    val signedBy: List<JsonElement> = emptyList(),
) {
    fun entry(sound: SoundId, kind: ErrorKind): UnlockEntry? =
        entries.firstOrNull { it.sound == sound.raw && FusionSpec.kindOf(it.kind) == kind }

    /** The CI rule of plan 03 §7: an unlocked entry without evidence is an error. */
    fun problems(): List<String> = entries.mapNotNull { e ->
        when {
            e.state !in setOf("locked", "trial", "unlocked") -> "${e.sound}/${e.kind}: unknown state '${e.state}'"
            e.state == "unlocked" && e.evidence == null -> "${e.sound}/${e.kind}: unlocked without evidence"
            e.state == "unlocked" && e.evidenceLevel !in setOf("imitations", "children") ->
                "${e.sound}/${e.kind}: unlocked needs evidenceLevel imitations or children"
            SoundId.of(e.sound) == null -> "${e.sound}: unknown sound"
            ErrorKind.of(e.kind) == null && ErrorKind.entries.none { it.name == e.kind } -> "${e.kind}: unknown kind"
            else -> null
        }
    }
}

@Serializable
data class FeedbackKind(
    val sound: String,
    val kind: String,
    /** Animation identifier from chapter 05 §5.2 (N2). */
    val animation: String,
    val variant: String? = null,
    /** Key of Foni's line (chapter 06 owns the final wording). */
    val instructionKey: String,
    /** The child's instruction, Romanian, at most 8 words. */
    val child: String,
    val adultKey: String,
    val adult: String,
)

@Serializable
data class FeedbackLine(val animation: String, val instructionKey: String, val child: String, val adultKey: String? = null, val adult: String? = null)

@Serializable
data class FeedbackSpec(
    val schema: Int = 1,
    val version: String,
    val kinds: List<FeedbackKind>,
    /** "correct", "almost", "unsure". */
    val verdicts: Map<String, FeedbackLine>,
    /** By [RetakeReason.raw]. */
    val retake: Map<String, FeedbackLine>,
)

/** Looks up what to show for a result; the engine itself never produces text. */
class Feedback(val spec: FeedbackSpec) {
    fun forKind(sound: SoundId, kind: ErrorKind): FeedbackKind? =
        spec.kinds.firstOrNull { it.sound == sound.raw && FusionSpec.kindOf(it.kind) == kind }

    fun forRetake(reason: RetakeReason): FeedbackLine? = spec.retake[reason.raw]

    fun forVerdict(key: String): FeedbackLine? = spec.verdicts[key]
}

/** All data files, parsed and checked. */
class JudgeSpec(
    val population: PopulationThresholds,
    val fusion: Map<SoundId, FusionSpec>,
    val unlock: UnlockSpec,
    val feedback: FeedbackSpec,
) {
    init {
        fusion.values.forEach { it.validate() }
    }

    fun fusionFor(sound: SoundId): FusionSpec = fusion[sound] ?: error("no spec/fusion/${sound.raw}.json")

    fun versions(sound: SoundId): SpecVersions {
        val f = fusion[sound]
        return SpecVersions(
            thresholds = population.version, fusion = f?.version ?: "", unlock = unlock.version,
            feedback = feedback.version, featureVersion = f?.featureVersion ?: "",
        )
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = false }

        /**
         * File names of the fusion specs, ASCII so asset paths survive every file system: `spec/fusion/<SoundId.name
         * lowercased>.json` (`s`, `sh`, `z`, `j`, `ts`, `ce`, `ge`, `r`, `l`). The `sound` field inside is `SoundId.raw`.
         */
        fun fusionFileName(sound: SoundId): String = "${sound.name.lowercase()}.json"

        fun fromStrings(population: String, fusion: Collection<String>, unlock: String, feedback: String): JudgeSpec {
            val parsed = fusion.map { json.decodeFromString(FusionSpec.serializer(), it) }
            return JudgeSpec(
                population = json.decodeFromString(PopulationThresholds.serializer(), population),
                fusion = parsed.associateBy { it.soundId },
                unlock = json.decodeFromString(UnlockSpec.serializer(), unlock),
                feedback = json.decodeFromString(FeedbackSpec.serializer(), feedback),
            )
        }

        /** [dir] is the `spec/` folder (or the assets copy of it). */
        fun load(dir: File): JudgeSpec {
            val fusionDir = File(dir, "fusion")
            val fusion = SoundId.entries.mapNotNull { s -> File(fusionDir, fusionFileName(s)).takeIf { it.isFile }?.readText() }
            return fromStrings(
                population = File(dir, "thresholds/population.json").readText(),
                fusion = fusion,
                unlock = File(dir, "unlock.json").readText(),
                feedback = File(dir, "feedback.json").readText(),
            )
        }
    }
}
