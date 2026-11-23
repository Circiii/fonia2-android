package tech.fonia.designsystem.letter

import androidx.compose.runtime.Stable

/**
 * The continuous input of a [LiveLetter] / [LiveWord]: plain volatile fields, written by anyone (the audio thread, a
 * slider, the demo script) and read ONCE per frame inside the draw phase. Writing never recomposes anything and never
 * allocates.
 *
 * Two ways to drive the inflation:
 * - set [inflation] yourself (0–1, the "story number" of plan §4), or
 * - set [inflateWithVoice] and let the letter integrate it: it grows while [speaking] is true and the sound is good
 *   ([holdGoalSeconds] to full), deflates 1.6 times faster in pauses, and POPs at 1 when [autoPop] is on.
 *
 * Mapping from the engine's live snapshot (chapter 03), to be done by the screen once per audio frame:
 * ```
 * drive.feed(level = s.level, speaking = s.speaking, sNess = s.sNess, fricative = s.fricative,
 *            voicing = s.pitchClarity, trill = s.trill, blow = s.blow, holdSeconds = s.holdSeconds)
 * ```
 */
@Stable // never observed by Compose: the frame loop reads it, so writes cannot (and must not) recompose
class LetterDrive {
    /** 0–1 target inflation (used when [inflateWithVoice] is false). */
    @Volatile var inflation: Float = 0.55f

    /**
     * 0 = round (Ș, ROND 100) … 1 = sharp (S, ROND 10). NaN = ROND follows the inflation (plan §4 table).
     * Feed the live sNess here to morph S↔Ș with the voice.
     */
    @Volatile var sharpness: Float = Float.NaN

    /** 0–1 voice loudness: a ±1.2 % buzz of the scale while the child speaks. */
    @Volatile var level: Float = 0f

    /** True while voice is detected. */
    @Volatile var speaking: Boolean = false

    /** Microphone open: the letter leans toward the child (Listen, §5.1) and stays leaning. */
    @Volatile var listening: Boolean = false

    /** 0–1 live uncertainty: the outline "boils" at 8 frames per second while > 0 (Unsure without a verdict). */
    @Volatile var trembling: Float = 0f

    /** 0–1 fricative strength: a little extra sharpness and lift on hissing sounds. */
    @Volatile var fricative: Float = 0f

    /** 0–1 voicing / pitch clarity: voiced sounds make the letter a touch heavier. */
    @Volatile var voicing: Float = 0f

    /** 0–1 trill strength: R's leg ripples like a plucked string while it is > 0. */
    @Volatile var trill: Float = 0f

    /** 0–1 blowing: the letter leans away as if blown. */
    @Volatile var blow: Float = 0f

    /** Seconds the current sound has been held (informative; the integrator uses [speaking]). */
    @Volatile var holdSeconds: Float = 0f

    /** Let the letter integrate [inflation] from [speaking] (the inflate-and-POP game). */
    @Volatile var inflateWithVoice: Boolean = false

    /** Seconds of good sound from empty to full when [inflateWithVoice]. */
    @Volatile var holdGoalSeconds: Float = 3f

    /** POP automatically when the integrated inflation reaches 1 (only with [inflateWithVoice]). */
    @Volatile var autoPop: Boolean = true

    /** One call per audio frame with the live snapshot's values. */
    fun feed(
        level: Float,
        speaking: Boolean,
        sNess: Float = Float.NaN,
        fricative: Float = 0f,
        voicing: Float = 0f,
        trill: Float = 0f,
        blow: Float = 0f,
        holdSeconds: Float = 0f,
    ) {
        this.level = level
        this.speaking = speaking
        this.sharpness = sNess
        this.fricative = fricative
        this.voicing = voicing
        this.trill = trill
        this.blow = blow
        this.holdSeconds = holdSeconds
    }

    /** Back to a calm letter (no voice, nothing trembling). Keeps [inflation] and the mode. */
    fun quiet() {
        level = 0f; speaking = false; trembling = 0f; fricative = 0f; voicing = 0f; trill = 0f; blow = 0f; holdSeconds = 0f
    }
}
