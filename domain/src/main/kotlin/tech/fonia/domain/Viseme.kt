package tech.fonia.domain

import kotlinx.serialization.Serializable

/** Where the tongue sits in a mouth pose. */
enum class TonguePos(override val rawValue: String) : StringRawValue {
    /** Lying low, behind the lower teeth. */
    Rest("rest"),

    /** Dome raised to the palate. */
    Up("up"),

    /** Tip touching behind the upper teeth (L, R). */
    TipUp("tip-up"),

    /** Tip between the teeth (English TH). */
    Between("between"),

    /** Stuck out, down over the lower lip. */
    Out("out"),

    /** Stuck out towards the nose. */
    OutUp("out-up"),
    OutLeft("out-left"),
    OutRight("out-right"),
    Hidden("hidden"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): TonguePos? = entries.byRawValue(rawValue)
    }
}

/**
 * Shape of Foni's mouth. Every number is 0...1 except `smile`, which is -1...1. Swift's `var` fields on a value type
 * become `val`s here; change one with `copy()`.
 */
data class MouthParams(
    /** Jaw opening. */
    val open: Double,
    /** Corner-to-corner spread. */
    val width: Double,
    /** Lip rounding and protrusion. */
    val round: Double,
    /** -1 sad to +1 big smile. */
    val smile: Double,
    /** How much of the teeth rows is visible. */
    val teeth: Double,
    val tongue: TonguePos,
    /** Tongue tip trill (R). */
    val vibrate: Boolean,
    /** Upper teeth on lower lip (F, V). */
    val lipBite: Boolean,
    /** Cheeks inflated. */
    val puff: Double,
)

/** Mouth poses Foni can show. Raw values are the pose names used in the JSON resources. */
@Serializable(with = Viseme.RawSerializer::class)
enum class Viseme(override val rawValue: String) : StringRawValue {
    Rest("rest"),
    A("a"),
    E("e"),
    I("i"),
    O("o"),
    U("u"),
    ABreve("ă"),
    ICircumflex("î"),
    S("s"),
    Z("z"),
    Ts("ț"),
    Sh("ș"),
    J("j"),
    Ce("ce"),
    Ge("ge"),
    R("r"),
    L("l"),
    M("m"),
    P("p"),
    B("b"),
    F("f"),
    V("v"),
    T("t"),
    D("d"),
    N("n"),
    K("k"),
    G("g"),
    H("h"),
    Th("th"),
    W("w"),
    SmileBig("smileBig"),
    Kiss("kiss"),
    Puff("puff"),
    Lion("lion"),
    TongueOut("tongueOut"),
    TongueUp("tongueUp"),
    TongueLeft("tongueLeft"),
    TongueRight("tongueRight"),
    TongueInsideUp("tongueInsideUp"),
    Blow("blow"),
    ;

    val mouth: MouthParams
        get() = when (this) {
            Rest -> MouthParams(open = 0.1, width = 0.55, round = 0.0, smile = 0.6, teeth = 0.25, tongue = TonguePos.Rest, vibrate = false, lipBite = false, puff = 0.0)
            A -> MouthParams(open = 0.85, width = 0.62, round = 0.0, smile = 0.15, teeth = 0.25, tongue = TonguePos.Rest, vibrate = false, lipBite = false, puff = 0.0)
            E -> MouthParams(open = 0.5, width = 0.8, round = 0.0, smile = 0.3, teeth = 0.5, tongue = TonguePos.Rest, vibrate = false, lipBite = false, puff = 0.0)
            I -> MouthParams(open = 0.28, width = 0.95, round = 0.0, smile = 0.5, teeth = 0.75, tongue = TonguePos.Rest, vibrate = false, lipBite = false, puff = 0.0)
            O -> MouthParams(open = 0.6, width = 0.38, round = 0.7, smile = 0.15, teeth = 0.15, tongue = TonguePos.Rest, vibrate = false, lipBite = false, puff = 0.0)
            U -> MouthParams(open = 0.25, width = 0.22, round = 1.0, smile = 0.15, teeth = 0.0, tongue = TonguePos.Hidden, vibrate = false, lipBite = false, puff = 0.0)
            ABreve -> MouthParams(open = 0.4, width = 0.5, round = 0.0, smile = 0.05, teeth = 0.3, tongue = TonguePos.Rest, vibrate = false, lipBite = false, puff = 0.0)
            ICircumflex -> MouthParams(open = 0.3, width = 0.62, round = 0.0, smile = 0.1, teeth = 0.55, tongue = TonguePos.Rest, vibrate = false, lipBite = false, puff = 0.0)
            S -> MouthParams(open = 0.13, width = 0.86, round = 0.0, smile = 0.45, teeth = 1.0, tongue = TonguePos.Hidden, vibrate = false, lipBite = false, puff = 0.0)
            Z -> MouthParams(open = 0.13, width = 0.86, round = 0.0, smile = 0.45, teeth = 1.0, tongue = TonguePos.Hidden, vibrate = false, lipBite = false, puff = 0.0)
            Ts -> MouthParams(open = 0.14, width = 0.82, round = 0.0, smile = 0.4, teeth = 1.0, tongue = TonguePos.Hidden, vibrate = false, lipBite = false, puff = 0.0)
            Sh -> MouthParams(open = 0.3, width = 0.3, round = 0.9, smile = 0.15, teeth = 0.85, tongue = TonguePos.Hidden, vibrate = false, lipBite = false, puff = 0.0)
            J -> MouthParams(open = 0.3, width = 0.3, round = 0.9, smile = 0.15, teeth = 0.85, tongue = TonguePos.Hidden, vibrate = false, lipBite = false, puff = 0.0)
            Ce -> MouthParams(open = 0.3, width = 0.34, round = 0.8, smile = 0.15, teeth = 0.8, tongue = TonguePos.Hidden, vibrate = false, lipBite = false, puff = 0.0)
            Ge -> MouthParams(open = 0.3, width = 0.34, round = 0.8, smile = 0.15, teeth = 0.8, tongue = TonguePos.Hidden, vibrate = false, lipBite = false, puff = 0.0)
            R -> MouthParams(open = 0.42, width = 0.55, round = 0.0, smile = 0.1, teeth = 0.3, tongue = TonguePos.TipUp, vibrate = true, lipBite = false, puff = 0.0)
            L -> MouthParams(open = 0.5, width = 0.6, round = 0.0, smile = 0.2, teeth = 0.4, tongue = TonguePos.TipUp, vibrate = false, lipBite = false, puff = 0.0)
            M -> MouthParams(open = 0.0, width = 0.5, round = 0.0, smile = 0.1, teeth = 0.0, tongue = TonguePos.Hidden, vibrate = false, lipBite = false, puff = 0.0)
            P -> MouthParams(open = 0.0, width = 0.48, round = 0.0, smile = 0.0, teeth = 0.0, tongue = TonguePos.Hidden, vibrate = false, lipBite = false, puff = 0.0)
            B -> MouthParams(open = 0.0, width = 0.48, round = 0.0, smile = 0.0, teeth = 0.0, tongue = TonguePos.Hidden, vibrate = false, lipBite = false, puff = 0.0)
            F -> MouthParams(open = 0.14, width = 0.6, round = 0.0, smile = 0.1, teeth = 0.0, tongue = TonguePos.Hidden, vibrate = false, lipBite = true, puff = 0.0)
            V -> MouthParams(open = 0.14, width = 0.6, round = 0.0, smile = 0.1, teeth = 0.0, tongue = TonguePos.Hidden, vibrate = false, lipBite = true, puff = 0.0)
            T -> MouthParams(open = 0.22, width = 0.62, round = 0.0, smile = 0.15, teeth = 0.7, tongue = TonguePos.TipUp, vibrate = false, lipBite = false, puff = 0.0)
            D -> MouthParams(open = 0.22, width = 0.62, round = 0.0, smile = 0.15, teeth = 0.7, tongue = TonguePos.TipUp, vibrate = false, lipBite = false, puff = 0.0)
            N -> MouthParams(open = 0.22, width = 0.6, round = 0.0, smile = 0.15, teeth = 0.6, tongue = TonguePos.TipUp, vibrate = false, lipBite = false, puff = 0.0)
            K -> MouthParams(open = 0.38, width = 0.58, round = 0.0, smile = 0.15, teeth = 0.4, tongue = TonguePos.Up, vibrate = false, lipBite = false, puff = 0.0)
            G -> MouthParams(open = 0.38, width = 0.58, round = 0.0, smile = 0.15, teeth = 0.4, tongue = TonguePos.Up, vibrate = false, lipBite = false, puff = 0.0)
            H -> MouthParams(open = 0.5, width = 0.6, round = 0.0, smile = 0.15, teeth = 0.3, tongue = TonguePos.Rest, vibrate = false, lipBite = false, puff = 0.0)
            Th -> MouthParams(open = 0.2, width = 0.62, round = 0.0, smile = 0.15, teeth = 0.8, tongue = TonguePos.Between, vibrate = false, lipBite = false, puff = 0.0)
            W -> MouthParams(open = 0.14, width = 0.16, round = 1.0, smile = 0.15, teeth = 0.0, tongue = TonguePos.Hidden, vibrate = false, lipBite = false, puff = 0.0)
            SmileBig -> MouthParams(open = 0.12, width = 1.0, round = 0.0, smile = 1.0, teeth = 0.9, tongue = TonguePos.Hidden, vibrate = false, lipBite = false, puff = 0.0)
            Kiss -> MouthParams(open = 0.05, width = 0.12, round = 1.0, smile = 0.0, teeth = 0.0, tongue = TonguePos.Hidden, vibrate = false, lipBite = false, puff = 0.0)
            Puff -> MouthParams(open = 0.0, width = 0.42, round = 0.0, smile = 0.0, teeth = 0.0, tongue = TonguePos.Hidden, vibrate = false, lipBite = false, puff = 1.0)
            Lion -> MouthParams(open = 1.0, width = 0.7, round = 0.0, smile = 0.0, teeth = 0.6, tongue = TonguePos.Rest, vibrate = false, lipBite = false, puff = 0.0)
            TongueOut -> MouthParams(open = 0.55, width = 0.6, round = 0.0, smile = 0.1, teeth = 0.0, tongue = TonguePos.Out, vibrate = false, lipBite = false, puff = 0.0)
            TongueUp -> MouthParams(open = 0.55, width = 0.6, round = 0.0, smile = 0.1, teeth = 0.0, tongue = TonguePos.OutUp, vibrate = false, lipBite = false, puff = 0.0)
            TongueLeft -> MouthParams(open = 0.5, width = 0.62, round = 0.0, smile = 0.1, teeth = 0.0, tongue = TonguePos.OutLeft, vibrate = false, lipBite = false, puff = 0.0)
            TongueRight -> MouthParams(open = 0.5, width = 0.62, round = 0.0, smile = 0.1, teeth = 0.0, tongue = TonguePos.OutRight, vibrate = false, lipBite = false, puff = 0.0)
            TongueInsideUp -> MouthParams(open = 0.75, width = 0.62, round = 0.0, smile = 0.0, teeth = 0.3, tongue = TonguePos.TipUp, vibrate = false, lipBite = false, puff = 0.0)
            Blow -> MouthParams(open = 0.1, width = 0.18, round = 1.0, smile = 0.15, teeth = 0.0, tongue = TonguePos.Hidden, vibrate = false, lipBite = false, puff = 0.35)
        }

    companion object {
        fun fromRawValue(rawValue: String): Viseme? = entries.byRawValue(rawValue)

        /** Pose for a single letter or grapheme: Romanian first, plus English TH and W. */
        fun forGrapheme(grapheme: String): Viseme {
            val key = swiftLowercased(grapheme)
            fromRawValue(key)?.let { return it }
            return when {
                canonicallyEqual(key, "â") -> ICircumflex
                canonicallyEqual(key, "c") || canonicallyEqual(key, "x") -> K
                canonicallyEqual(key, "y") -> I
                else -> Rest
            }
        }
    }

    internal object RawSerializer : StringRawValueSerializer<Viseme>("Viseme", { entries })
}
