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
 * The big button in the middle of the bar: a flat yellow lid on an amber skirt, with a play mark so it says
 * what it does. A press pushes the lid down into the skirt, like a real key, and the phone gives a small knock;
 * letting go springs it back up.
 */
class StartButtonStyle(
    /**
     * `.shadow(color: Palette.ink.opacity(0.18), radius: 6, y: 3)`: a soft shadow round the button's own outline, so
     * it sits over whatever scrolls behind the bar.
     */
    val castsShadow: Boolean = false,
) : ButtonStyle {
    @Composable
    override fun MakeBody(configuration: ButtonConfiguration, modifier: Modifier) {
        val haptics = LocalHapticFeedback.current
        // `.sensoryFeedback(.impact(weight: .medium), trigger: isPressed) { _, pressed in pressed }`.
        LaunchedEffect(configuration.isPressed) {
            if (configuration.isPressed) haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
        }
        StartButtonBody(configuration.isPressed, castsShadow, modifier)
    }
}

private const val LID_HEIGHT = 50.7
private const val DEPTH = 13.4

@Composable
private fun StartButtonBody(isPressed: Boolean, castsShadow: Boolean, modifier: Modifier) {
    // The lid goes down `depth - 3` into the skirt: `.easeOut(duration: 0.07)` down, `.spring(duration: 0.32,
    // bounce: 0.45)` back up.
    val travel by animateFloatAsState(
        if (isPressed) (DEPTH - 3).toFloat() else 0f,
        if (isPressed) easeOut(0.07) else swiftSpring(0.32, 0.45),
        label = "start",
    )
    Canvas(modifier.size(82.dp, (LID_HEIGHT + DEPTH).toFloat().dp)) {
        platform { canvas ->
            canvas.save()
            canvas.scale(density, density)
            val rect = CGRect(0.0, 0.0, 82.0, LID_HEIGHT + DEPTH)
            val skirt = startSkirt(rect, LID_HEIGHT, travel.toDouble())
            if (castsShadow) {
                // One outline for skirt and lid together, so the shadow does not darken where they overlap. A SwiftUI
                // shadow's radius is twice its blur's standard deviation.
                val outline = android.graphics.Path(skirt.android())
                outline.op(CGPath.ellipse(CGRect(0.0, travel.toDouble(), 82.0, LID_HEIGHT)).android(), android.graphics.Path.Op.UNION)
                outline.offset(0f, 3f)
                canvas.drawPath(outline, fillPaint(Palette.ink, 0.18).apply { maskFilter = blurFilter(3.0) })
            }
            // The skirt starts at the lid's widest line wherever the lid is, so no corner shows beside a pressed lid.
            canvas.drawPath(skirt, fillPaint(Palette.startSkirt))
            canvas.translate(0f, travel)
            val lid = CGRect(0.0, 0.0, 82.0, LID_HEIGHT)
            canvas.drawPath(CGPath.ellipse(lid), fillPaint(Palette.startLid))
            // A thin lighter rim on the lid's upper edge, so it reads as a top and not a flat disc: a 2.5 line just
            // inside the outline, only over the upper 45% of the lid.
            canvas.save()
            canvas.clipRect(0f, 0f, 82f, (LID_HEIGHT * 0.45).toFloat())
            canvas.drawPath(CGPath.ellipse(CGRect(1.25, 1.25, 82.0 - 2.5, LID_HEIGHT - 2.5)), strokePaint(Palette.startGlint, 2.5))
            canvas.restore()
            // A 20 × 22 mark, centred on the lid and moved 1.5 to the right.
            val mark = CGRect((82.0 - 20) / 2 + 1.5, (LID_HEIGHT - 22) / 2, 20.0, 22.0)
            canvas.drawPath(playMark(mark), fillPaint(Palette.ink))
            canvas.restore()
        }
    }
}

/**
 * Only what shows under the lid: the lower half of the lid's own outline, moved down. It starts at the
 * lid's widest line, so no amber can show around the top.
 */
internal fun startSkirt(rect: CGRect, lidHeight: Double, travel: Double = 0.0): CGPath {
    val middle = rect.minY + lidHeight / 2 + travel
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

/** A medal on the progress page: a sound's letter in a ring that fills. A null [caption] shows the ring alone. */
@Composable
fun RingMedal(share: Double, caption: String?, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
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
        if (caption != null) FoniaText(caption, style = ui(13, UIWeight.bold, TextRole.caption).monospacedDigit(), color = Palette.accentText)
    }
}
