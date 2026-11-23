package tech.fonia.phones

/**
 * The widened confusion sets of plan 03 §5 (`Alternatives` v2), one per target, for the judge's full head. Each
 * candidate has a [key]: the name of its `lpr.<key>` fusion input (`spec/fusion/<sound>.json`). A candidate may
 * group several ZIPA realisations that name the same error (I as [j] or [i], a back R as [ʁ] or [ʀ]); its likelihood
 * is their sum, as in [RestrictedGop]. "∅" is the omission.
 *
 * Unlike [Alternatives] (the shadow-mode verdicts, kept unchanged), these sets include realisations that are only
 * logged for now (`s̪`, `ɕ` for S; plan 03 §5: "variants with diacritics stay in the log until we measure how often
 * the model emits them"): the fusion gives them no class, only their share of `zipa.gop`.
 */
object ConfusionSets {
    data class Candidate(val key: String, val variants: List<List<String>>) {
        init {
            require(variants.isNotEmpty())
            for (variant in variants) for (token in variant) require(ZipaInventory.contains(token)) { "$key: '$token'" }
        }

        fun toAlternative(): Alternative = Alternative(key, key, variants)
    }

    const val OMISSION = "∅"

    private fun c(key: String, vararg variants: String) =
        Candidate(key, variants.map { v -> if (v.isEmpty()) emptyList() else tokens(v) })

    /** "ts" → [t, s]; "s̪" → [s, ̪] (the combining mark is its own ZIPA token). */
    private fun tokens(written: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < written.length) {
            val cp = written.codePointAt(i)
            out += String(Character.toChars(cp))
            i += Character.charCount(cp)
        }
        return out
    }

    private val omission = c(OMISSION, "")

    /**
     * Realisations counted as the target. Beyond [Alternatives.accepted] (R: trill or tap):
     * - J and Z also as [dʒ] and [dz]. ZIPA hears a stop onset on many good word-initial and intervocalic J and Z (all
     *   four synthetic voices, `EVAL_v1.md`); "J said as GE" is not one of J's error kinds, so the candidate only cost
     *   good takes. Logged through `heard:target`.
     * - At the end of a word, J and Z also voiceless ([ʃ], [s]): Romanian devoices there and plan 03 §3.10 does not
     *   judge it.
     */
    fun accepted(sound: TargetSound, tapAccepted: Boolean = true, wordFinal: Boolean = false): List<List<String>> {
        val base = Alternatives.accepted(sound, tapAccepted)
        return when (sound) {
            TargetSound.J -> base + listOf(listOf("d", "ʒ")) + if (wordFinal) listOf(listOf("ʃ")) else emptyList()
            TargetSound.Z -> base + listOf(listOf("d", "z")) + if (wordFinal) listOf(listOf("s")) else emptyList()
            else -> base
        }
    }

    /**
     * Candidates for [sound], omission last. With [tapAccepted] false (trill games) the tap [ɾ] is a candidate of R. A
     * realisation [accepted] counts as the target is never a candidate.
     */
    fun of(sound: TargetSound, tapAccepted: Boolean = true, wordFinal: Boolean = false): List<Candidate> {
        val taken = accepted(sound, tapAccepted, wordFinal).toSet()
        return all(sound, tapAccepted).filter { c -> c.variants.none { it in taken } }
    }

    private fun all(sound: TargetSound, tapAccepted: Boolean): List<Candidate> = when (sound) {
        TargetSound.S -> listOf(c("ʃ", "ʃ"), c("ts", "ts"), c("z", "z"), c("t", "t"), c("θ", "θ"), c("ɬ", "ɬ"), c("ɕ", "ɕ"), c("s̪", "s̪"))
        TargetSound.Sh -> listOf(c("s", "s"), c("tʃ", "tʃ"), c("ts", "ts"), c("ʒ", "ʒ"), c("t", "t"), c("ɬ", "ɬ"), c("θ", "θ"))
        TargetSound.Z -> listOf(c("s", "s"), c("ʒ", "ʒ"), c("d", "d"), c("ð", "ð"), c("ɮ", "ɮ"), c("dz", "dz"))
        TargetSound.J -> listOf(c("ʃ", "ʃ"), c("z", "z"), c("dʒ", "dʒ"), c("d", "d"), c("ɮ", "ɮ"))
        TargetSound.Ts -> listOf(c("s", "s"), c("tʃ", "tʃ"), c("ʃ", "ʃ"), c("t", "t"), c("tθ", "tθ"))
        TargetSound.Ce -> listOf(c("ts", "ts"), c("ʃ", "ʃ"), c("t", "t"), c("s", "s"))
        TargetSound.Ge -> listOf(c("ʒ", "ʒ"), c("z", "z"), c("dz", "dz"), c("tʃ", "tʃ"), c("d", "d"))
        TargetSound.R -> listOfNotNull(
            c("l", "l"), c("j", "j", "i"), c("ə", "ə", "ɨ"), c("ʁ", "ʁ", "ʀ"), c("ɹ", "ɹ"), c("w", "w"),
            c("ɾ", "ɾ").takeIf { !tapAccepted },
        )
        TargetSound.L -> listOf(c("j", "j", "i"), c("w", "w", "u"), c("r", "r", "ɾ"))
    } + omission

    /**
     * Candidates that name one error kind together, reported as one more `lpr.<a+b>` (log P(target) − log(P(a) + P(b))):
     * L said as I or U is `lToGlide` either way; GE said as [dz] or [z] is `affricatePlace`. A linear head cannot sum
     * the two ratios itself.
     */
    fun groups(sound: TargetSound): Map<String, List<String>> = when (sound) {
        TargetSound.L -> mapOf("j+w" to listOf("j", "w"))
        TargetSound.Ge -> mapOf("dz+z" to listOf("dz", "z"))
        else -> emptyMap()
    }

    /** Every `lpr.<key>` a sound's full head may read (mid-word; at the end of a word J and Z have one fewer). */
    fun keys(sound: TargetSound, tapAccepted: Boolean = true): List<String> = of(sound, tapAccepted).map { it.key } + groups(sound).keys
}
