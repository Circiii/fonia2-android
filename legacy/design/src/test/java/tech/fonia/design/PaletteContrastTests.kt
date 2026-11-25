package tech.fonia.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Marks that carry a state, progress or a control's edge reach 3:1 against what they are drawn on (WCAG 1.4.11),
 * measured on the colours the app really puts next to each other.
 */
class PaletteContrastTests {
    private class Rgb(val red: Double, val green: Double, val blue: Double) {
        constructor(color: Color) : this(color.red.toDouble(), color.green.toDouble(), color.blue.toDouble())

        /** This colour drawn at [opacity] over [ground], as the screen shows it. */
        fun over(ground: Rgb, opacity: Double): Rgb = Rgb(
            red * opacity + ground.red * (1 - opacity),
            green * opacity + ground.green * (1 - opacity),
            blue * opacity + ground.blue * (1 - opacity),
        )

        /** WCAG relative luminance. */
        val luminance: Double
            get() {
                fun linear(value: Double) = if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
                return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
            }

        /** The hue in degrees: red at 0 and 360, yellow at 60, green at 120. */
        val hue: Double
            get() {
                val top = max(red, max(green, blue))
                val span = top - min(red, min(green, blue))
                if (span == 0.0) return 0.0
                val sixth = when (top) {
                    red -> ((green - blue) / span).mod(6.0)
                    green -> (blue - red) / span + 2
                    else -> (red - green) / span + 4
                }
                return sixth * 60
            }
    }

    private fun ratio(first: Rgb, second: Rgb): Double {
        val light = max(first.luminance, second.luminance)
        val dark = min(first.luminance, second.luminance)
        return (light + 0.05) / (dark + 0.05)
    }

    private fun ratio(first: Color, second: Color): Double = ratio(Rgb(first), Rgb(second))

    /** [mark] drawn at [opacity] on [ground], against [ground]. */
    private fun ratio(mark: Color, opacity: Double, ground: Color): Double {
        val under = Rgb(ground)
        return ratio(Rgb(mark).over(under, opacity), under)
    }

    private fun atLeast3(value: Double, what: String) = assertTrue("$what: $value", value >= 3)

    /** the helper gives the known extremes */
    @Test
    fun helper() {
        assertEquals(21.0, ratio(Color.hex(0x000000), Color.hex(0xFFFFFF)), 0.01)
        assertEquals(1.0, ratio(Palette.sheet, Palette.sheet), 0.0001)
        assertEquals(0.0, Rgb(Color.hex(0xFF0000)).hue, 0.01)
        assertEquals(120.0, Rgb(Color.hex(0x00FF00)).hue, 0.01)
    }

    /** the edge of a white control stands out from the page and from its own face */
    @Test
    fun chipEdge() {
        atLeast3(ratio(Palette.chipEdge, Palette.paper), "chipEdge on paper")
        atLeast3(ratio(Palette.chipEdge, Palette.sheet), "chipEdge on sheet")
    }

    /** what is done stands out from the track it runs along */
    @Test
    fun progress() {
        atLeast3(ratio(Palette.progress, Palette.progressTrack), "progress on progressTrack")
    }

    /** the coach's pace marks read on the card, the gauge and the timeline lanes */
    @Test
    fun tip() {
        atLeast3(ratio(Palette.tip, Palette.sheet), "tip on sheet")
        atLeast3(ratio(Palette.tip, Palette.cloud), "tip on cloud")
        atLeast3(ratio(Palette.tip, Palette.progressTrack), "tip on progressTrack")
        atLeast3(ratio(Palette.tip, 0.9, Palette.cloud), "tip at 0.9 on cloud")
    }

    /** almost reads on the timeline lanes and stays a warm yellow green, far from red */
    @Test
    fun almost() {
        atLeast3(ratio(Palette.almost, Palette.cloud), "almost on cloud")
        atLeast3(ratio(Palette.almost, 0.9, Palette.cloud), "almost at 0.9 on cloud")
        val rgb = Rgb(Palette.almost)
        assertTrue("hue ${rgb.hue}", rgb.hue in 60.0..100.0)
        assertTrue(rgb.green > rgb.red)
    }
}
