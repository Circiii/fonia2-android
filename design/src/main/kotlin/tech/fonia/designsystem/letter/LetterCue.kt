package tech.fonia.designsystem.letter

import tech.fonia.model.ErrorKind

/**
 * The animation vocabulary of the living letter (plan §5). A cue is an EVENT: it starts an animation with its own
 * duration; verdicts and instructions then hold their last frame (it carries the information) until [Rest] or the next
 * verdict. Continuous behaviour (follow the voice, inflate, lean while listening, live trembling) comes from
 * [LetterDrive], not from cues.
 */
sealed interface LetterCue {
    /** Stable name, used by the lab, logs and tests. */
    val name: String

    // ---- base vocabulary (§5.1) ----
    /** Two slow breaths (wght ±30, scale ±1.5 %, 3.5 s each), then still. Starts by itself after 1 s of calm. */
    data object Breathe : LetterCue { override val name = "breathe" }
    /** Mic opened: lean 4° toward the child, scale 0.96, thinner rim; stays until [Rest] / mic closed. */
    data object Listen : LetterCue { override val name = "listen" }
    /** Back to neutral: drops held verdict / instruction frames and the listening lean. */
    data object Rest : LetterCue { override val name = "rest" }
    /** Anticipation → 1.70 → 1.0 on the reward spring, 80 particles from the outline, a shock ring. */
    data object Pop : LetterCue { override val name = "pop" }
    /** Jump with squash and stretch, the sticker turns Parrot and sticks (3° → 0), 24 little stars. */
    data object Correct : LetterCue { override val name = "correct" }
    /** Sheared 9°, the face half full (a little liquid line), one swing ±3°; set Sky. */
    data object Almost : LetterCue { override val name = "almost" }
    /** The outline boils (4 noise frames at 8 fps, 1.2 s), then freezes tilted 4°, flat, with a "?". */
    data object Unsure : LetterCue { override val name = "unsure" }
    /** Shrinks to 0.9 and back with an 8° turn there and back; ↻ for 600 ms. */
    data object Retake : LetterCue { override val name = "retake" }
    /** Syllable beat: a 0.06 em hop and wght +150 for 140 ms. */
    data class Beat(val strength: Float = 1f) : LetterCue { override val name = "beat" }
    /** Entrance: falls in from above, stretched, lands with a squash and settles. */
    data object DropIn : LetterCue { override val name = "drop-in" }
    /** Gets heavier (wght +200) with a soft swell, and stays (the end of voicing-wave). */
    data object Thicken : LetterCue { override val name = "thicken" }
    /** Stretches like a ribbon (scaleX 1 → 1.5) with air streaks and springs back (the "flow" of closure). */
    data object Stretch : LetterCue { override val name = "stretch" }

    // ---- instruction animations (§5.2), each runs once (≤ 1.6 s) and holds its last frame ----
    /** Target Ș/Ț, heard S/T: the comma falls from above, bounces once and sticks; the letter rounds. */
    data object CommaDrop : LetterCue { override val name = "comma-drop" }
    /** Target S/T, heard Ș/Ț: the comma comes loose and falls out of frame; the letter sharpens. */
    data object CommaLift : LetterCue { override val name = "comma-lift" }
    /** The heard letter, flat ghost, glides out to the left; the target glides in as a sticker. */
    data class SwapGlide(val heard: String) : LetterCue { override val name = "swap-glide" }
    /** One short wave runs down R's leg like a plucked string; [uvular]: a Sun dot rises from behind to the top. */
    data class TrillRipple(val uvular: Boolean = false) : LetterCue { override val name = if (uvular) "trill-ripple-uvular" else "trill-ripple" }
    /** A line under the letter turns into a wave, runs into it, and the letter thickens. */
    data object VoicingWave : LetterCue { override val name = "voicing-wave" }
    /** Two puffs leave the sides, join and fly out through the middle, toward the child. */
    data object AirToCentre : LetterCue { override val name = "air-to-centre" }
    /** Teeth under the letter; the tongue pokes out between them, then slides behind them. */
    data object TongueBehindTeeth : LetterCue { override val name = "tongue-behind-teeth" }
    /** A small T (D for G/J) comes from the left and snaps onto the letter with a squash. */
    data object ClosureSnap : LetterCue { override val name = "closure-snap" }
    /** The letter sags (wght 250, 0.9, tilted 5°), then refills to full. */
    data object SagAndRefill : LetterCue { override val name = "sag-and-refill" }
    /** LiveWord only: the empty slot pulses Sun once and the missing letter drops into it. On a hero: [DropIn]. */
    data class SlotDrop(val index: Int) : LetterCue { override val name = "slot-drop" }

    /** LiveWord only: the syllable [range] (letter indices) hops in turn (40 ms apart), heavier, in the Voice set. */
    data class Syllable(val range: IntRange, val stressed: Boolean = false) : LetterCue { override val name = "syllable" }

    /** A named error: resolved to the matching instruction animation for [target] (see [resolve]). */
    data class Instruct(val kind: ErrorKind, val heard: String? = null) : LetterCue { override val name = "instruct:${kind.raw}" }

    companion object {
        /** Every cue the lab offers, in vocabulary order (SwapGlide / SlotDrop with a sample argument). */
        val vocabulary: List<LetterCue> = listOf(
            Breathe, Listen, Rest, Pop, Correct, Almost, Unsure, Retake, Beat(), DropIn, Thicken, Stretch,
            CommaDrop, CommaLift, SwapGlide("J"), TrillRipple(false), TrillRipple(true), VoicingWave, AirToCentre,
            TongueBehindTeeth, ClosureSnap, SagAndRefill,
        )

        private val COMMA_OF = mapOf('S' to 'Ș', 's' to 'ș', 'T' to 'Ț', 't' to 'ț')
        private val BASE_OF = COMMA_OF.entries.associate { (k, v) -> v to k } + mapOf('Ş' to 'S', 'ş' to 's', 'Ţ' to 'T', 'ţ' to 't')

        internal fun hasComma(g: String) = g.any { it in BASE_OF }
        internal fun commaVersionOf(g: String) = String(g.map { COMMA_OF[it] ?: it }.toCharArray())
        internal fun baseVersionOf(g: String) = String(g.map { BASE_OF[it] ?: it }.toCharArray())

        /**
         * The instruction animation for [kind] on [target] (plan §5.2 table). [heard] is what the phone heard, when known.
         */
        fun resolve(kind: ErrorKind, target: String, heard: String?): LetterCue {
            val t = target.uppercase()
            return when (kind) {
                ErrorKind.PlaceSwap, ErrorKind.AffricatePlace -> when {
                    hasComma(target) && (heard == null || !hasComma(heard)) &&
                        (heard == null || baseVersionOf(target).equals(heard, ignoreCase = true)) -> CommaDrop
                    !hasComma(target) && t.firstOrNull() in setOf('S', 'T') &&
                        (heard == null || hasComma(heard)) -> CommaLift
                    else -> SwapGlide(heard ?: defaultHeard(kind, t))
                }
                ErrorKind.RtoL, ErrorKind.RtoGlide, ErrorKind.LtoGlide, ErrorKind.LtoR -> SwapGlide(heard ?: defaultHeard(kind, t))
                ErrorKind.RtoVowel -> TrillRipple(false)
                ErrorKind.RUvular -> TrillRipple(true)
                ErrorKind.Devoicing -> VoicingWave
                ErrorKind.Lateral -> AirToCentre
                ErrorKind.Interdental -> TongueBehindTeeth
                ErrorKind.Deaffrication -> ClosureSnap
                ErrorKind.Stopping -> Stretch
                ErrorKind.WeakOrShort -> SagAndRefill
                ErrorKind.Omission -> DropIn
            }
        }

        private fun defaultHeard(kind: ErrorKind, t: String): String = when (kind) {
            ErrorKind.RtoL -> "L"
            ErrorKind.LtoR -> "R"
            ErrorKind.RtoGlide, ErrorKind.LtoGlide -> "I"
            else -> when (t.firstOrNull()) {
                'Z' -> "J"; 'J' -> "Z"; 'Ț' -> "C"; 'C' -> "Ț"; 'S' -> "Ș"; 'Ș' -> "S"; else -> "?"
            }
        }
    }
}
