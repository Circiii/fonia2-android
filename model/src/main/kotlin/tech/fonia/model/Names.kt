package tech.fonia.model

/**
 * The shared names of Fonia 2 (docs/v2/00_PLAN_MASTER.md §3). Every module uses these exactly; the raw
 * values are the keys of `content/json/sounds.json` and of the `spec/` files, so they are a contract with iOS.
 */
enum class SoundId(val raw: String, val letter: String) {
    S("s", "S"),
    Sh("ș", "Ș"),
    Z("z", "Z"),
    J("j", "J"),
    Ts("ț", "Ț"),
    Ce("ce", "CE"),
    Ge("ge", "GE"),
    R("r", "R"),
    L("l", "L"),
    ;

    companion object {
        fun of(raw: String): SoundId? = entries.firstOrNull { it.raw == raw }
    }
}

/** Why a take was not right. A kind is *named* to the child only once `spec/unlock.json` unlocks it (plan §5). */
enum class ErrorKind(val raw: String) {
    Omission("omission"),
    Stopping("stopping"),
    Devoicing("devoicing"),
    PlaceSwap("placeSwap"),
    Lateral("lateral"),
    Interdental("interdental"),
    Deaffrication("deaffrication"),
    AffricatePlace("affricatePlace"),
    RtoL("rToL"),
    RtoGlide("rToGlide"),
    RtoVowel("rToVowel"),
    RUvular("rUvular"),
    LtoGlide("lToGlide"),
    LtoR("lToR"),
    WeakOrShort("weakOrShort"),
    ;

    companion object {
        fun of(raw: String): ErrorKind? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * What the judge says about one take. The child never sees "wrong": `NamedError` becomes an instruction of at
 * most eight words, `Unsure` asks the adult, `Retake` asks for the take again (noise, clipping, too short).
 */
sealed interface Verdict {
    data object Correct : Verdict

    data object Almost : Verdict

    /** [heardAs] is the sound the engine heard instead, when it knows (N10). */
    data class NamedError(val kind: ErrorKind, val heardAs: SoundId? = null) : Verdict

    data object Unsure : Verdict

    data object Retake : Verdict

    val key: String
        get() = when (this) {
            Correct -> "correct"
            Almost -> "almost"
            is NamedError -> "namedError"
            Unsure -> "unsure"
            Retake -> "retake"
        }
}

/** The fourteen rungs of a sound's ladder. No climbing past [Isolated] until an adult confirms. */
enum class Rung(val raw: String) {
    Listen("listen"),
    Warmup("warmup"),
    Isolated("isolated"),
    Syllables("syllables"),
    Back("back"),
    WordsInitial("wordsInitial"),
    WordsMedial("wordsMedial"),
    WordsFinal("wordsFinal"),
    Clusters("clusters"),
    Pairs("pairs"),
    Sentences("sentences"),
    Twister("twister"),
    Retell("retell"),
    Everyday("everyday"),
    ;

    val needsAdultToPass: Boolean get() = this == Isolated

    companion object {
        fun of(raw: String): Rung? = entries.firstOrNull { it.raw == raw }
    }
}
