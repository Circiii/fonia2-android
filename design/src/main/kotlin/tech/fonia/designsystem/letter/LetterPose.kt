package tech.fonia.designsystem.letter

/**
 * Everything a frame of the living letter can change, reset to neutral every frame and then written by the drive and
 * by every active cue. Lengths are in em of the letter (y down), angles in degrees. Mutable on purpose: one instance
 * per letter, no allocation per frame.
 */
class LetterPose {
    // body
    /** Inflation override: final inflation = lerp(drive inflation, [inflation], [inflationMix]). */
    var inflation = 0f
    var inflationMix = 0f
    var wghtAdd = 0f
    /** ROND override: final ROND = lerp(live ROND, [rond], [rondMix]). */
    var rond = 0f
    var rondMix = 0f
    var scale = 1f
    /** Squash and stretch about the baseline. */
    var sx = 1f
    var sy = 1f
    var tx = 0f
    var ty = 0f
    var rot = 0f
    var shear = 0f
    var rimMul = 1f
    var alpha = 1f
    /** 0 = normal; 1 = Almost's half-full face (the lower half in the edge colour). */
    var halfFill = 0f
    /** Fill line height as a fraction of the cap height (0 = baseline, 1 = cap top). */
    var fillLine = 0.5f
    /** Slosh of the liquid line (degrees) and its wave phase. */
    var slosh = 0f
    var sloshWave = 0f
    /** Outline noise amount 0–1 (Unsure / trembling) and which of the 4 noise frames. */
    var noise = 0f
    var noiseSeed = 0
    /** Transverse ripple on the leg (em) and its phase (radians). */
    var ripple = 0f
    var ripplePhase = 0f
    /** The shadow stays on the ground while the letter is in the air: extra shadow offset (em), and its spread. */
    var shadowLift = 0f
    var shadowSpread = 0f

    // comma of Ș/Ț (font-relative, em, y down)
    /** 0 = attached as drawn; 1 = separate (falling in / coming loose); -1 = hidden (target drawn without it yet). */
    var commaMode = 0
    var commaDx = 0f
    var commaDy = 0f
    var commaRot = 0f
    var commaScale = 1f
    var commaAlpha = 1f
    /** Draw the comma of [commaFrom] on a letter that has none (comma-lift's loose comma). */
    var phantomComma = false

    // the second glyph: swap-glide ghost or closure-snap's small T
    /** 0 none, 1 flat ghost (heard letter), 2 small sticker (snap). */
    var ghostMode = 0
    var ghostDx = 0f
    var ghostDy = 0f
    var ghostAlpha = 0f
    var ghostScale = 1f
    var ghostSx = 1f
    /** The target sliding in (swap-glide), em, and its alpha. */
    var targetDx = 0f

    // props (time since the prop started, seconds; negative = not shown)
    var ringT = -1f
    var puffT = -1f
    var teethT = -1f
    var waveT = -1f
    var streakT = -1f
    var sunDotT = -1f

    // badge (a glyph from Allo beside the letter: ✓ ? ↻), 0 = none
    var badge = 0
    var badgeScale = 0f
    var badgeKind = 0

    fun reset() {
        inflation = 0f; inflationMix = 0f; wghtAdd = 0f; rond = 0f; rondMix = 0f
        scale = 1f; sx = 1f; sy = 1f; tx = 0f; ty = 0f; rot = 0f; shear = 0f; rimMul = 1f; alpha = 1f
        halfFill = 0f; fillLine = 0.5f; slosh = 0f; sloshWave = 0f; noise = 0f; noiseSeed = 0; ripple = 0f; ripplePhase = 0f
        shadowLift = 0f; shadowSpread = 0f
        commaMode = 0; commaDx = 0f; commaDy = 0f; commaRot = 0f; commaScale = 1f; commaAlpha = 1f; phantomComma = false
        ghostMode = 0; ghostDx = 0f; ghostDy = 0f; ghostAlpha = 0f; ghostScale = 1f; ghostSx = 1f; targetDx = 0f
        ringT = -1f; puffT = -1f; teethT = -1f; waveT = -1f; streakT = -1f; sunDotT = -1f
        badge = 0; badgeScale = 0f; badgeKind = 0
    }

    companion object {
        const val BADGE_CHECK = 0x2713
        const val BADGE_QUESTION = '?'.code
        const val BADGE_RETAKE = 0x21BB
    }
}
