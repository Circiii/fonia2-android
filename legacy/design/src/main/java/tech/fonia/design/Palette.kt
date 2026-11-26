package tech.fonia.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** `Color(hex:)`: an opaque sRGB colour from 0xRRGGBB. */
fun Color.Companion.hex(hex: Long): Color = Color(0xFF000000L or (hex and 0xFFFFFFL))

/** `Color.opacity(_:)`: the same colour with its alpha multiplied. */
fun Color.opacity(value: Double): Color = copy(alpha = (alpha * value).toFloat().coerceIn(0f, 1f))

/**
 * Taken from the parrot: green for what you do, yellow for the one big button, deep blue for words. The
 * pages stay pale so the scenes carry the colour. Worked out in OKLCH and measured: every text pairing
 * reaches 4.5:1, including white on the action green. The app only has a light look, because the scenes
 * are painted for daylight.
 */
object Palette {
    val paper = Color.hex(0xF4F8F3)
    val sheet = Color.hex(0xFFFFFF)
    val cloud = Color.hex(0xEBF2EB)
    val line = Color.hex(0xDDE3DC)
    val lineStrong = Color.hex(0x7D8792)
    val ink = Color.hex(0x102E52)
    val inkSoft = Color.hex(0x546577)

    /** Actions and the icons on them. */
    val accent = Color.hex(0x008240)
    val accentEdge = Color.hex(0x005A2A)

    /** The same green, dark enough to read as small text. */
    val accentText = Color.hex(0x036A34)
    val accentSoft = Color.hex(0xD1F2D7)
    /** The ring that closes round a choice's tick as it pops in. */
    val accentSoftEdge = Color.hex(0xA3D9AF)

    /** Deleting: the red text and edge, and the soft tile behind its icon. */
    val danger = Color.hex(0xB3261E)
    val dangerSoft = Color.hex(0xFBE4E1)

    /**
     * What is done on a bar, a bead or a step, on [progressTrack]: 3:1 against it, so the share reads without a
     * number.
     */
    val progress = Color.hex(0x039A48)
    val progressTrack = Color.hex(0xDFECE1)
    val today = Color.hex(0xDF6C32)
    /** Deep enough for a star icon on white or paper (3.4:1). */
    val starTint = Color.hex(0xBA8000)

    val startLid = Color.hex(0xFDC436)
    /** The soft ring that breathes out from under the yellow button. */
    val startHalo = Color.hex(0xE5A000)
    val startGlint = Color.hex(0xFFE38D)
    val startSkirt = Color.hex(0xB17000)
    /**
     * The edge under a white control. A white face on the pale page is almost invisible (1.1:1), so this edge is what
     * shows the control is there: 3:1 against [paper].
     */
    val chipEdge = Color.hex(0x8A918A)
    val pillShadow = Color.hex(0x102E52).opacity(0.1)

    val sage = Color.hex(0xD3EDD7)
    val peach = Color.hex(0xFDDBAE)

    val night = Color.hex(0x1A1A3F)
    val nightText = Color.hex(0xFFFFFF)
    val nightSoft = Color.hex(0xCDCFE5)
    val nightDeep = Color.hex(0x2E2F5D)
    val nightPage = Color.hex(0x20204A)

    val cold = Color.hex(0x085F96)
    val coldDeep = Color.hex(0x00406B)
    val coldSoft = Color.hex(0xE0F1FE)

    val warm = Color.hex(0xB2354A)
    val warmDeep = Color.hex(0x73202E)
    val warmSoft = Color.hex(0xFFE8E9)

    /**
     * The tip family's solid colour. The speaking coach draws the pace with it: a dot, a bar and marks on the timeline,
     * so it keeps 3:1 on [progressTrack], and on [cloud] when a timeline mark is drawn at 0.9.
     */
    val tip = Color.hex(0x9C7C00)
    val tipDeep = Color.hex(0x744C00)
    val tipSoft = Color.hex(0xFCF2CD)

    val right = Color.hex(0x00672C)
    val rightDeep = Color.hex(0x005121)
    val rightSoft = Color.hex(0xDCF7E1)

    /** A warm olive green, never red. On the coach's timeline it marks a flat voice: 3:1 on [cloud] at 0.9. */
    val almost = Color.hex(0x6C8A26)
    val almostDeep = Color.hex(0x495E16)
    val almostSoft = Color.hex(0xEAF8D4)

    val notYet = Color.hex(0x4A4C5B)
    val notYetDeep = Color.hex(0x454754)
    val notYetSoft = Color.hex(0xEBECF2)

    val buttonFill = accent
    val buttonLabel = Color.hex(0xFFFFFF)
}

/** The family a sound belongs to. The colour says what the air or the tongue does for it. */
@Suppress("EnumEntryName")
enum class Field {
    cold, warm, tip, night, paper;

    val background: Color get() = if (this == night) Palette.night else Palette.paper

    val foreground: Color get() = if (this == night) Palette.nightText else Palette.ink

    /** Text that is not the main element. */
    val secondary: Color get() = if (this == night) Palette.nightSoft else Palette.ink

    /** The family's dark tone: big letters, target sounds and progress. */
    val deep: Color
        get() = when (this) {
            cold -> Palette.coldDeep
            warm -> Palette.warmDeep
            tip -> Palette.tipDeep
            night -> Palette.nightSoft
            paper -> Palette.accent
        }

    val soft: Color
        get() = when (this) {
            cold -> Palette.coldSoft
            warm -> Palette.warmSoft
            tip -> Palette.tipSoft
            night -> Palette.nightDeep
            paper -> Palette.cloud
        }

    /** The family's solid colour, for marks on a white card. */
    val solid: Color
        get() = when (this) {
            cold -> Palette.cold
            warm -> Palette.warm
            tip -> Palette.tip
            night -> Palette.nightSoft
            paper -> Palette.accent
        }

    /** What a bar or a meter runs along. */
    val track: Color get() = if (this == night) Palette.nightDeep else Palette.ink.opacity(0.09)

    val buttonFill: Color get() = Palette.buttonFill

    val buttonLabel: Color get() = Palette.buttonLabel
}

/**
 * The page behind a practice screen: one flat colour. iOS lets it run under the safe areas
 * (`ignoresSafeArea`); here it fills whatever box it's given, and the screen gives it the whole window.
 */
@Composable
fun FieldBackdrop(field: Field, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(field.background))
}

/** The page behind everything else. */
@Composable
fun PageBackdrop(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Palette.paper))
}
