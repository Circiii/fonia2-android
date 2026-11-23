package tech.fonia.face

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import tech.fonia.model.SoundId

/** A measured quantity of [MouthState] that a pose can check. All are relative, so distance does not matter. */
enum class MouthFeature(val key: String) {
    /** Inner lip gap ÷ the rest mouth width W₀. */
    APERTURE("aperture"),

    /** Mouth width ÷ W₀ (1 = as at rest). */
    WIDTH("width"),

    /** Lip rounding 0…1 relative to rest. */
    ROUND("round"),

    /** Lip spreading 0…1 relative to rest. */
    SPREAD("spread"),

    /** Jaw drop (chin to nose base, minus rest) ÷ W₀. */
    JAW("jaw"),
    ;

    companion object {
        fun of(key: String): MouthFeature = entries.firstOrNull { it.key == key } ?: error("unknown mouth feature '$key'")
    }
}

/**
 * What to change, as a key for the screen to word (never UI text here; the child sees at most 8 words, chosen by
 * the screen and unlocked by `spec/unlock.json`).
 */
enum class CameraAdvice(val key: String) {
    /** „rotunjește buzele” */
    ROUND_LIPS("lips.round"),

    /** „întinde buzele, ca la zâmbet” */
    SPREAD_LIPS("lips.spread"),

    /** „nu rotunji buzele” */
    LESS_ROUND("lips.lessRound"),

    /** „nu întinde buzele” */
    LESS_SPREAD("lips.lessSpread"),

    /** „deschide gura mai mult” */
    OPEN_MORE("mouth.openMore"),

    /** „apropie dinții” / „deschide gura mai puțin” */
    OPEN_LESS("mouth.openLess"),

    /** „desparte puțin buzele” */
    PART_LIPS("lips.part"),
    ;

    companion object {
        fun of(key: String): CameraAdvice = entries.firstOrNull { it.key == key } ?: error("unknown camera advice '$key'")
    }
}

/**
 * One visible condition of a pose: [feature] should lie in [[min], [max]] (either may be open). Outside, the score
 * falls linearly to 0 over [soft]; the advice says which way to move.
 */
data class CameraCheck(
    val feature: MouthFeature,
    val min: Float?,
    val max: Float?,
    val soft: Float,
    val belowMin: CameraAdvice?,
    val aboveMax: CameraAdvice?,
) {
    /** 1 inside the band, falling to 0 at [soft] outside it. */
    fun score(value: Float): Float {
        if (value.isNaN()) return 0f
        val below = if (min != null && value < min) min - value else 0f
        val above = if (max != null && value > max) value - max else 0f
        val off = maxOf(below, above)
        return (1f - off / soft).coerceIn(0f, 1f)
    }

    /** The advice for [value], or null inside the band. */
    fun advice(value: Float): CameraAdvice? = when {
        min != null && value < min -> belowMin
        max != null && value > max -> aboveMax
        else -> null
    }
}

/**
 * The target lip shape drawn by `LipTargetOverlay` (docs/v2/04 §4): from the child's rest rings, `u' = u·wide·(1 −
 * 0.30·round)`, `v' = v·(1 + 0.25·round) ± (open·W₀/2)·cos²(πu)`. [klass] names the lip class of the table.
 */
data class LipPose(
    val klass: String,
    val wide: Float,
    val open: Float,
    val round: Float,
    val tolOpen: Float = 0.35f,
    val tolWide: Float = 0.08f,
    val tolRound: Float = 0.25f,
)

/** What part of the articulation the camera can see for a sound; `false` is said in data, not guessed. */
data class Visibility(val lips: Boolean, val jaw: Boolean, val teeth: Boolean, val tongue: Boolean, val note: String?)

/**
 * `spec/poses/<sound>.json`: the lip target and the camera checks for one sound. The GNM blocks of docs/v2/04 §8
 * (`poses`, `timeline`, `asset`) belong to H7 and are ignored here.
 */
data class PoseSpec(
    val sound: SoundId,
    val version: Int,
    val reviewStatus: String,
    val lips: LipPose,
    val checks: List<CameraCheck>,
    val visible: Visibility,
    /** How the camera's evidence is used in the verdict (docs/v2/04 §1): only ever to choose advice or confirm. */
    val role: String,
) {
    companion object {
        /** File name of a sound's pose (ASCII, so it survives every file system and zip). */
        fun fileName(sound: SoundId): String = when (sound) {
            SoundId.S -> "s.json"
            SoundId.Sh -> "sh.json"
            SoundId.Z -> "z.json"
            SoundId.J -> "j.json"
            SoundId.Ts -> "ts.json"
            SoundId.Ce -> "ce.json"
            SoundId.Ge -> "ge.json"
            SoundId.R -> "r.json"
            SoundId.L -> "l.json"
        }

        fun parse(text: String): PoseSpec {
            val o = Json.parseToJsonElement(text).jsonObject
            val raw = o.s("sound") ?: error("pose without sound")
            val sound = SoundId.of(raw) ?: error("unknown sound '$raw'")
            val lipsObj = o.obj("lips") ?: error("$raw: no lips block")
            val tol = lipsObj.obj("tol") ?: JsonObject(emptyMap())
            val lips = LipPose(
                klass = lipsObj.s("class") ?: "neutral",
                wide = lipsObj.f("wide", 1f), open = lipsObj.f("open", 0f), round = lipsObj.f("round", 0f),
                tolOpen = tol.f("open", 0.35f), tolWide = tol.f("wide", 0.08f), tolRound = tol.f("round", 0.25f),
            )
            val checks = o.arr("cameraChecks")?.map { e ->
                val c = e.jsonObject
                CameraCheck(
                    feature = MouthFeature.of(c.s("feature") ?: error("$raw: check without feature")),
                    min = c.fOrNull("min"), max = c.fOrNull("max"), soft = c.f("soft", 0.1f),
                    belowMin = c.s("belowMin")?.let(CameraAdvice::of), aboveMax = c.s("aboveMax")?.let(CameraAdvice::of),
                ).also { require(it.soft > 0f && (it.min != null || it.max != null)) { "$raw: bad check $c" } }
            } ?: emptyList()
            val v = o.obj("visible") ?: JsonObject(emptyMap())
            return PoseSpec(
                sound = sound,
                version = o.int("version", 1),
                reviewStatus = o.obj("review")?.s("status") ?: "draft",
                lips = lips,
                checks = checks,
                visible = Visibility(v.b("lips", true), v.b("jaw", true), v.b("teeth", false), v.b("tongue", false), v.s("note")),
                role = o.s("role") ?: "chooseAdvice",
            )
        }

        private val cache = java.util.concurrent.ConcurrentHashMap<SoundId, PoseSpec>()

        /** The bundled pose of [sound] (`spec/poses/<file>.json`), parsed once. */
        fun bundled(sound: SoundId): PoseSpec = cache.getOrPut(sound) { parse(SpecResources.text(fileName(sound))) }
    }
}
