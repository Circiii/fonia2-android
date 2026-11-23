package tech.fonia.phones

/**
 * The nine Romanian sounds Fonia practises, with the raw values of `sounds.json` (`SoundID` in `:domain`, which this
 * module does not depend on at run time; a test keeps the two in step).
 *
 * [canonical] is the ZIPA token sequence of the sound. ZIPA has no affricate symbols, so Ț, CE/CI and GE/GI are two
 * tokens each.
 */
enum class TargetSound(val rawValue: String, val letter: String, val canonical: List<String>) {
    S("s", "S", listOf("s")),
    Sh("ș", "Ș", listOf("ʃ")),
    Z("z", "Z", listOf("z")),
    J("j", "J", listOf("ʒ")),
    Ts("ț", "Ț", listOf("t", "s")),
    Ce("ce", "CE/CI", listOf("t", "ʃ")),
    Ge("ge", "GE/GI", listOf("d", "ʒ")),
    R("r", "R", listOf("r")),
    L("l", "L", listOf("l")),
    ;

    companion object {
        fun fromRawValue(rawValue: String): TargetSound? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/**
 * One way the target can come out instead: a category the child or the logoped would name ("sună ca Ș"), realised
 * as one or more ZIPA token sequences. Its likelihood is the sum over [variants] (the label sequences are disjoint
 * events under CTC). An empty variant is an omission.
 *
 * [soundsLike] is what `sounds.json` calls the category in its `fixes` ("sună ca Ș", "sună ca I sau U",
 * "se aude din gât", "lipsește"), so the app can point to the matching tip; [sound] is set when the category is one
 * of the nine practice sounds.
 */
data class Alternative(
    val id: String,
    val soundsLike: String,
    val variants: List<List<String>>,
    val sound: TargetSound? = null,
) {
    val isOmission: Boolean get() = variants == listOf(emptyList<String>())

    init {
        require(variants.isNotEmpty())
        for (variant in variants) for (token in variant) require(ZipaInventory.contains(token)) { "$id: '$token'" }
    }

    companion object {
        val omission = Alternative("omission", "lipsește", listOf(emptyList()))

        fun of(sound: TargetSound) = Alternative(sound.canonical.joinToString(""), sound.letter, listOf(sound.canonical), sound)
    }
}

/**
 * The plausible alternatives of each target, from three project sources and nothing fitted to data:
 * - the product decision in `docs/PLAN.md` §7.1 (P4): S↔Ș↔Ț, Z↔J, CE/CI↔Ț, GE/GI↔J/Z, R↔[ɾ]/[l]/[j]/omission, L↔R;
 * - the typical errors the content names for each sound (`sounds.json`, `fixes[].when`): "sună ca I sau U" for L,
 *   "se aude din gât" for R, "sună ca CI" for GE/GI, "sună ca CE" for Ț, "sună ca Ș" for CE/CI…;
 * - the minimal pairs the content teaches (`contrasts`): S↔Z (sar/zar) and Ș↔J (șoc/joc).
 *
 * Omission is a candidate for every target, as in GOP-SF-SD (Cao et al. 2026). Distortions that are not another phone
 * (lateral or interdental sibilants, apart from a plain [θ]) are left to the full-band DSP at 48 kHz: the model sees
 * 16 kHz audio and its filterbank stops at 7.6 kHz.
 */
object Alternatives {
    private val tap = Alternative("ɾ", "R bătut", listOf(listOf("ɾ")), TargetSound.R)
    private val soundsLikeI = Alternative("j", "I", listOf(listOf("j"), listOf("i")))
    private val soundsLikeU = Alternative("w", "U", listOf(listOf("w"), listOf("u")))
    private val guttural = Alternative("ʁ", "din gât", listOf(listOf("ʁ"), listOf("ʀ")))
    private val interdental = Alternative("θ", "între dinți", listOf(listOf("θ")))
    private val stop = Alternative("t", "T", listOf(listOf("t")))
    private val rForL = Alternative("r", "R", listOf(listOf("r"), listOf("ɾ")), TargetSound.R)

    /**
     * Realisations counted as correct. R accepts the tap [ɾ] as well as the trill (86 % of Romanian R is tapped,
     * PLAN §7.1 P3) unless [tapAccepted] is false, as in the trill games, where the tap becomes an alternative.
     */
    fun accepted(sound: TargetSound, tapAccepted: Boolean = true): List<List<String>> =
        if (sound == TargetSound.R && tapAccepted) listOf(listOf("r"), listOf("ɾ")) else listOf(sound.canonical)

    fun of(sound: TargetSound, tapAccepted: Boolean = true): List<Alternative> = when (sound) {
        TargetSound.S -> listOf(of(TargetSound.Sh), of(TargetSound.Ts), of(TargetSound.Z), interdental)
        TargetSound.Sh -> listOf(of(TargetSound.S), of(TargetSound.Ts), of(TargetSound.Ce), of(TargetSound.J))
        TargetSound.Z -> listOf(of(TargetSound.S), of(TargetSound.J))
        TargetSound.J -> listOf(of(TargetSound.Sh), of(TargetSound.Z))
        TargetSound.Ts -> listOf(of(TargetSound.S), of(TargetSound.Ce), of(TargetSound.Sh), stop)
        TargetSound.Ce -> listOf(of(TargetSound.Ts), of(TargetSound.Sh), stop)
        TargetSound.Ge -> listOf(of(TargetSound.J), of(TargetSound.Z), of(TargetSound.Ce))
        TargetSound.R -> listOfNotNull(of(TargetSound.L), soundsLikeI, guttural, tap.takeIf { !tapAccepted })
        TargetSound.L -> listOf(rForL, soundsLikeI, soundsLikeU)
    } + Alternative.omission

    private fun of(sound: TargetSound) = Alternative.of(sound)
}
