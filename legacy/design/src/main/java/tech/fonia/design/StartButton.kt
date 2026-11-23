package tech.fonia.design

import android.graphics.Paint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * The big button in the middle of the bar: a yellow lid on an amber skirt, with a play mark so it says what
 * it does. It's the one loud thing on the page, and it stays exactly where it is: a press darkens it and
 * the phone gives a small knock, nothing moves.
 */
class StartButtonStyle : ButtonStyle {
    @Composable
    override fun MakeBody(configuration: ButtonConfiguration, modifier: Modifier) {
        val haptics = LocalHapticFeedback.current
        // `.sensoryFeedback(.impact(weight: .medium), trigger: isPressed) { _, pressed in pressed }`.
        LaunchedEffect(configuration.isPressed) {
            if (configuration.isPressed) haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
        }
        StartButtonBody(configuration.isPressed, modifier)
    }
}

private const val LID_HEIGHT = 50.7
private const val DEPTH = 13.4

@Composable
private fun StartButtonBody(isPressed: Boolean, modifier: Modifier) {
    // `.brightness(-0.07)` with `.easeOut(duration: 0.09)`: every colour of the drawing moves down by 0.07.
    val brightness by animateFloatAsState(if (isPressed) -0.07f else 0f, easeOut(0.09), label = "start")
    Canvas(modifier.size(82.dp, (LID_HEIGHT + DEPTH).toFloat().dp)) {
        platform { canvas ->
            canvas.save()
            canvas.scale(density, density)
            val rect = CGRect(0.0, 0.0, 82.0, LID_HEIGHT + DEPTH)
            fun shade(color: Color) = color.brightened(brightness)
            canvas.drawPath(startSkirt(rect, LID_HEIGHT), fillPaint(shade(Palette.startSkirt)))
            val lid = CGRect(0.0, 0.0, 82.0, LID_HEIGHT)
            canvas.drawPath(
                CGPath.ellipse(lid),
                gradientPaint(
                    listOf(shade(Palette.startGlint), shade(Palette.startLid)),
                    start = CGPoint(lid.midX, lid.minY),
                    end = CGPoint(lid.minX + lid.width * 0.5, lid.minY + lid.height * 0.62),
                ),
            )
            // A 19 × 21 mark, centred on the lid and moved 1.5 to the right.
            val mark = CGRect((82.0 - 19) / 2 + 1.5, (LID_HEIGHT - 21) / 2, 19.0, 21.0)
            canvas.drawPath(playMark(mark), fillPaint(shade(Palette.ink)))
            canvas.restore()
        }
    }
}

/** `.brightness(amount)`: adds the amount to each channel. */
internal fun Color.brightened(amount: Float): Color =
    if (amount == 0f) this else Color((red + amount).coerceIn(0f, 1f), (green + amount).coerceIn(0f, 1f), (blue + amount).coerceIn(0f, 1f), alpha)

/**
 * Only what shows under the lid: the lower half of the lid's own outline, moved down. It starts at the
 * lid's widest line, so no amber can show around the top.
 */
internal fun startSkirt(rect: CGRect, lidHeight: Double): CGPath {
    val middle = rect.minY + lidHeight / 2
    val lowest = CGRect(rect.minX, rect.maxY - lidHeight, rect.width, lidHeight)
    val path = CGPath()
    path.addRect(CGRect(rect.minX, middle, rect.width, lowest.midY - middle))
    path.addEllipse(lowest)
    return path
}

/** A play triangle with soft corners, in the same round language as everything else. */
internal fun playMark(rect: CGRect): CGPath {
    val radius = rect.width * 0.19
    val top = CGPoint(rect.minX, rect.minY)
    val tip = CGPoint(rect.maxX, rect.midY)
    val bottom = CGPoint(rect.minX, rect.maxY)
    val path = CGPath()
    path.move(CGPoint(rect.minX, rect.midY))
    path.addArc(tangent1End = top, tangent2End = tip, radius = radius)
    path.addArc(tangent1End = tip, tangent2End = bottom, radius = radius)
    path.addArc(tangent1End = bottom, tangent2End = top, radius = radius)
    path.closeSubpath()
    return path
}

/** A medal on the progress page: a sound's letter in a ring that fills. */
@Composable
fun RingMedal(share: Double, caption: String, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(62.dp)
                .drawBehind {
                    platform { canvas ->
                        canvas.save()
                        canvas.scale(density, density)
                        val circle = CGPath.ellipse(CGRect(0, 0, 62, 62))
                        canvas.drawPath(circle, strokePaint(Palette.progressTrack, 6.0))
                        val sweep = min(max(share, 0.0), 1.0)
                        if (sweep > 0) {
                            // `Circle().trim(from: 0, to: share)` turned by −90°: from the top, clockwise.
                            val arc = CGPath()
                            arc.addArc(CGPoint(31, 31), 31.0, -Math.PI / 2, -Math.PI / 2 + sweep * 2 * Math.PI, clockwise = false)
                            canvas.drawPath(arc, strokePaint(Palette.accent, 6.0, cap = Paint.Cap.ROUND))
                        }
                        canvas.restore()
                    }
                },
            contentAlignment = Alignment.Center,
            content = content,
        )
        FoniaText(caption, style = ui(13, UIWeight.bold, TextRole.caption).monospacedDigit(), color = Palette.accentText)
    }
}
