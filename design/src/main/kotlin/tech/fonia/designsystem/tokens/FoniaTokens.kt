package tech.fonia.designsystem.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * THE single source of the Fonia 2 design tokens (plan 05 §2 and §10).
 *
 * Kotlin is the source; `spec/tokens/fonia.tokens.json` (W3C design-token shape, for the iOS port and for review) is
 * generated from this file by `TokensJsonTest` (`./gradlew :core:designsystem:testDebugUnitTest -Pfonia.writeTokens=true`)
 * and the same test fails when the committed JSON no longer matches. This is the ONLY file allowed to spell a colour:
 * `NoRawColorsTest` scans every other Kotlin file of :core:designsystem and :app.
 *
 * Only the light theme exists in v1 (the plan forces it); dark and high-contrast are derived later from these roles.
 */

/** One colour role: the ARGB value, what it is for, and what it must never be used for. */
@Immutable
class ColorToken internal constructor(val name: String, val argb: Long, val use: String, val forbidden: String = "") {
    val color: Color get() = Color(argb)
    val hex: String get() = "#" + (argb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')
}

/** The 14 colour roles of §2.1 (light). There is no red in Fonia. */
object FoniaPalette {
    val bg = ColorToken("bg", 0xFFF4F6FF, "the Paper background")
    val surface = ColorToken("surface", 0xFFFFFFFF, "cards, sheets, text on accent, the button on Stage")
    val fill = ColorToken("fill", 0xFFE6EEFF, "fields, progress rail, pressed state, selection", "accent text on it (4.44:1)")
    val separator = ColorToken("separator", 0xFFDFE4F7, "decorative lines", "the only outline of a control (1.27:1)")
    val inkSecondary = ColorToken("inkSecondary", 0xFF5A638A, "secondary text, field outlines")
    val ink = ColorToken("ink", 0xFF131A3A, "text, icons, plain letters, the Breathe background")
    val accent = ColorToken("accent", 0xFF2F5BFF, "THE main action, selection, progress, the Stage background", "two filled accent things on one screen")
    val accentDeep = ColorToken("accentDeep", 0xFF14228A, "blue text, pressed, rim of the blue sticker")
    val voice = ColorToken("voice", 0xFFFF4F93, "the child's sound: target sticker edge, listening MicCapsule", "text; a lone mark on bg (2.87:1)")
    val voiceDeep = ColorToken("voiceDeep", 0xFF8A1247, "rim of the pink sticker, text on pink faces", "'wrong' or warnings")
    val parrot = ColorToken("parrot", 0xFF17B55E, "Foni, confirmed by the adult, the success sticker", "white text on it (2.69:1); a mark without outline")
    val parrotDeep = ColorToken("parrotDeep", 0xFF0A5A30, "green text, rim of the green sticker")
    val sun = ColorToken("sun", 0xFFFFD23F, "stars, celebration, the golden wagon", "text; an ordinary button background")
    val sunDeep = ColorToken("sunDeep", 0xFF9A6200, "rim of the yellow sticker, text on yellow faces", "small text on sun (3.53:1)")

    val roles: List<ColorToken> = listOf(
        bg, surface, fill, separator, inkSecondary, ink, accent, accentDeep, voice, voiceDeep, parrot, parrotDeep, sun, sunDeep,
    )

    // Sticker-only tints (faces and the Sky edge), not roles: §2.1 "seturile de autocolant".
    internal val voiceFace = ColorToken("sticker.voice.face", 0xFFFFF6E6, "face of the Voice sticker")
    internal val parrotFace = ColorToken("sticker.parrot.face", 0xFFEFFFE0, "face of the Parrot sticker")
    internal val sunFace = ColorToken("sticker.sun.face", 0xFFFFF3B8, "face of the Sun sticker")
    internal val skyEdge = ColorToken("sticker.sky.edge", 0xFF6C8BFF, "edge of the Sky sticker")

    /** The sticker's drop shadow: the same outline, offset (0.03, 0.09) em, black at alpha 0.20 (§2.1). */
    val stickerShadow = ColorToken("sticker.shadow", 0x33000000, "the sticker's own shadow (part of the drawing, not depth)")
    /** Depth 1 (the rising sheet only): ink at alpha 0.12 (§2.5). */
    val sheetShadow = ColorToken("depth.sheet", 0x1F131A3A, "shadow of the rising sheet")
    /** The thin gloss on a sticker's face (white, alpha 0.55): a highlight, never a gradient. */
    val stickerGloss = ColorToken("sticker.gloss", 0x8CFFFFFF, "gloss crescent on the sticker face")
}

/** The three layers of a sticker (plan §4): face fill, edge stroke, rim stroke. */
@Immutable
data class StickerSet(val name: String, val face: Color, val edge: Color, val rim: Color, val flat: Boolean = false)

/** The sticker sets of §2.1; one meaning each. */
object FoniaSticker {
    /** The child's target sound. */
    val Voice = StickerSet("Voice", FoniaPalette.voiceFace.color, FoniaPalette.voice.color, FoniaPalette.voiceDeep.color)
    /** Success, confirmed. */
    val Parrot = StickerSet("Parrot", FoniaPalette.parrotFace.color, FoniaPalette.parrot.color, FoniaPalette.parrotDeep.color)
    /** Star, reward, combo; also the props of the instruction animations. */
    val Sun = StickerSet("Sun", FoniaPalette.sunFace.color, FoniaPalette.sun.color, FoniaPalette.sunDeep.color)
    /** "Almost", neutral play; never on Stage. */
    val Sky = StickerSet("Sky", FoniaPalette.fill.color, FoniaPalette.skyEdge.color, FoniaPalette.accentDeep.color)
    /** Letters that are not the target, on Paper (ink) ... */
    val FlatOnPaper = StickerSet("Flat", FoniaPalette.ink.color, Color.Transparent, Color.Transparent, flat = true)
    /** ... and on Stage (white). */
    val FlatOnStage = StickerSet("FlatStage", FoniaPalette.surface.color, Color.Transparent, Color.Transparent, flat = true)
    /** The teeth prop of tongue-behind-teeth: surface with an ink outline (§5.2). */
    val Teeth = StickerSet("Teeth", FoniaPalette.surface.color, FoniaPalette.surface.color, FoniaPalette.ink.color)

    val all: List<StickerSet> = listOf(Voice, Parrot, Sun, Sky, FlatOnPaper, FlatOnStage, Teeth)

    internal val tokens: List<Pair<String, List<ColorToken>>> = listOf(
        "Voice" to listOf(FoniaPalette.voiceFace, FoniaPalette.voice, FoniaPalette.voiceDeep),
        "Parrot" to listOf(FoniaPalette.parrotFace, FoniaPalette.parrot, FoniaPalette.parrotDeep),
        "Sun" to listOf(FoniaPalette.sunFace, FoniaPalette.sun, FoniaPalette.sunDeep),
        "Sky" to listOf(FoniaPalette.fill, FoniaPalette.skyEdge, FoniaPalette.accentDeep),
    )
}

/**
 * The sticker sets in the colours of the iOS app (Ștefan Dulgheru's `Palette`, `tech.fonia.design`), for the living
 * letter inside his screens: one set per sound family (cold = S Z Ț, warm = Ș J CE GE, tip = R L), his green for a good
 * take, his olive "almost", his gold for stars. Not design-token roles (not in the JSON): a bridge for the legacy app.
 */
object FoniaClassicSticker {
    private fun set(name: String, face: Long, edge: Long, rim: Long) = StickerSet(name, Color(face), Color(edge), Color(rim))

    val Cold = set("ClassicCold", 0xFFE0F1FE, 0xFF085F96, 0xFF00406B)
    val Warm = set("ClassicWarm", 0xFFFFE8E9, 0xFFB2354A, 0xFF73202E)
    val Tip = set("ClassicTip", 0xFFFCF2CD, 0xFFF0C543, 0xFF744C00)
    val Good = set("ClassicGood", 0xFFDCF7E1, 0xFF008240, 0xFF005A2A)
    val Almost = set("ClassicAlmost", 0xFFEAF8D4, 0xFF8CAC4B, 0xFF495E16)
    val Star = set("ClassicStar", 0xFFFFE38D, 0xFFFDC436, 0xFFB17000)
    /** His ink, for plain letters next to a sticker. */
    val Ink = StickerSet("ClassicInk", Color(0xFF102E52), Color.Transparent, Color.Transparent, flat = true)

    val all: List<StickerSet> = listOf(Cold, Warm, Tip, Good, Almost, Star, Ink)
}

/** Which sets a verdict cue switches the letter to (default: the Fonia 2 Parrot and Sky). */
@Immutable
data class VerdictStickers(val correct: StickerSet = FoniaSticker.Parrot, val almost: StickerSet = FoniaSticker.Sky) {
    companion object {
        val Default = VerdictStickers()
        val Classic = VerdictStickers(FoniaClassicSticker.Good, FoniaClassicSticker.Almost)
    }
}

/** One step of the type scale (§2.4). All text is Allo; `rond` is the kid shell's value. */
@Immutable
data class TypeToken(val name: String, val sizeSp: Float, val wght: Float, val lineHeightSp: Float, val rond: Float, val use: String)

object FoniaTypeScale {
    val display = TypeToken("display", 40f, 800f, 44f, 100f, "screen titles for the child, END")
    val title = TypeToken("title", 28f, 750f, 34f, 100f, "titles")
    val title2 = TypeToken("title2", 22f, 700f, 28f, 100f, "card titles")
    val headline = TypeToken("headline", 18f, 650f, 24f, 100f, "buttons, rows")
    val body = TypeToken("body", 17f, 500f, 24f, 85f, "text")
    val label = TypeToken("label", 15f, 600f, 20f, 85f, "labels, chips")
    val caption = TypeToken("caption", 13f, 500f, 18f, 85f, "notes; nothing below 13 sp")
    val practiceWord = TypeToken("practiceWord", 56f, 900f, 64f, 100f, "LiveWord (shrinks to 36 sp); plain letters 300")
    val practiceLine = TypeToken("practiceLine", 28f, 400f, 38f, 85f, "sentences, tongue twisters")
    val counter = TypeToken("counter", 28f, 800f, 32f, 100f, "counters, with tnum")

    val all = listOf(display, title, title2, headline, body, label, caption, practiceWord, practiceLine, counter)

    /** ROND for the grown-up shell (adults, teens): one value for everything. */
    const val ROND_GROWN = 30f
    /** The hero letter is sized in dp, not sp: 200 dp (160–240 by screen). */
    val heroLetter: Dp = 200.dp
    /** Practice words never shrink below this before breaking at a syllable. */
    const val PRACTICE_WORD_MIN_SP = 36f
}

/** Spacing, corners, depth and touch targets (§2.5). */
object FoniaSpace {
    val xxs = 4.dp
    val xs = 8.dp
    val s = 12.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
    val scale = listOf(xxs, xs, s, m, l, xl, xxl)
    /** Page margin: kid shell 20 dp, grown shell 16 dp. */
    val pageKid = 20.dp
    val pageGrown = 16.dp
}

object FoniaCorner {
    val field = 8.dp
    val chip = 12.dp
    val card = 20.dp
    val sheet = 28.dp
    // buttons are capsules (50 %)
}

object FoniaDepth {
    /** Everything is flat (0) except the rising sheet: ink alpha 0.12, blur 24 dp, y 8 dp. */
    val sheetBlur = 24.dp
    val sheetY = 8.dp
}

object FoniaTarget {
    val kidPrimary = 64.dp
    val continueButtonHeight = 72.dp
    val minimum = 48.dp
    val gap = 12.dp
}

/** A spring in the units of Compose's `spring()`: damping ratio and stiffness (mass 1). */
@Immutable
data class SpringToken(val name: String, val dampingRatio: Float, val stiffness: Float, val use: String)

/** The motion system (§10). */
object FoniaMotion {
    /** The signature spring: buttons, syllables, the comma, the sticker sticking. */
    val bounce = SpringToken("bounce", 0.55f, 500f, "buttons, syllables, the comma, sticker sticking")
    /** Everything that follows the voice (no wobble). */
    val follow = SpringToken("follow", 0.95f, 400f, "everything that follows the voice")
    /** POP, Correct: once per screen. */
    val reward = SpringToken("reward", 0.45f, 400f, "POP, Correct; once per screen")
    /** The comma of Ș/Ț landing (§5.2: 0.5 / 400). */
    val comma = SpringToken("comma", 0.5f, 400f, "the falling comma of Ș/Ț and slot-drop")

    const val QUICK_MS = 140
    const val STANDARD_MS = 260
    const val SLOW_MS = 480
    /** `out` easing: CubicBezier(0.2, 0.8, 0.3, 1). Particles, flights on an arc. */
    val outEasing = floatArrayOf(0.2f, 0.8f, 0.3f, 1f)
    /** Stagger between letters; a whole run never exceeds 320 ms. */
    const val STAGGER_MS = 40
    const val STAGGER_MAX_MS = 320
    /** Reduced motion: transitions become a 150 ms fade, axes move in 100 ms steps. */
    const val REDUCED_FADE_MS = 150
    const val REDUCED_STEP_MS = 100
    /** Particle budget per screen (60 on the camera screen); a celebration lasts at most 1.2 s. */
    const val PARTICLES_MAX = 150
    const val PARTICLES_MAX_CAMERA = 60
    const val CELEBRATION_MAX_MS = 1200
    /** Unsure trembles only the outline, at 8 frames per second, for 1.2 s. */
    const val TREMBLE_FPS = 8

    val springs = listOf(bounce, follow, reward, comma)
}

/** The living letter's story number (§4): what `inflation` 0 → 1 does. */
object FoniaLetterTokens {
    const val WGHT_MIN = 180f
    const val WGHT_MAX = 900f
    const val SCALE_MIN = 0.80f
    const val SCALE_MAX = 1.30f
    const val SCALE_POP = 1.70f
    const val RIM_MIN_EM = 0.06f
    const val RIM_MAX_EM = 0.24f
    const val EDGE_MIN_EM = 0.03f
    const val EDGE_MAX_EM = 0.12f
    const val SHADOW_DX_EM = 0.03f
    const val SHADOW_DY_EM = 0.09f
    /** Deflating is 1.6 times faster than inflating. */
    const val DEFLATE_RATE = 1.6f
    /** ROND when the letter is sharpened (target S) and rounded (target Ș). */
    const val ROND_SHARP = 10f
    const val ROND_ROUND = 100f
    /** Voice energy gives ±1.2 % of scale. */
    const val ENERGY_SCALE = 0.012f
}
