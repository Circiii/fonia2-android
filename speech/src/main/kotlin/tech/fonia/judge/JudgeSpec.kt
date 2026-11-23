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
