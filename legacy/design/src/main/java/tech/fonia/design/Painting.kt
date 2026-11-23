package tech.fonia.design

import android.graphics.BlurMaskFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.roundToInt

// Small helpers for the drawings that are ported from SwiftUI's `GraphicsContext`. They draw on the
// platform canvas, whose matrix, clip and paint model match CoreGraphics closely: transforms compose the
// same way, the default fill rule is non-zero, and strokes use the CoreGraphics miter limit of 10.

internal inline fun DrawScope.platform(block: (android.graphics.Canvas) -> Unit) {
    drawIntoCanvas { block(it.nativeCanvas) }
}

/** `context.fill(_, with: .color(color))`, with the context's opacity. */
internal fun fillPaint(color: Color, opacity: Double = 1.0): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
    this.color = color.toArgb()
    alpha = alphaOf(color, opacity)
}

/** `context.stroke(_, with: .color(color), style: StrokeStyle(...))`. */
internal fun strokePaint(
    color: Color,
    width: Double,
    cap: Paint.Cap = Paint.Cap.BUTT,
    join: Paint.Join = Paint.Join.MITER,
    opacity: Double = 1.0,
    dash: FloatArray? = null,
    dashPhase: Double = 0.0,
): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    this.color = color.toArgb()
    alpha = alphaOf(color, opacity)
    strokeWidth = width.toFloat()
    strokeCap = cap
    strokeJoin = join
    strokeMiter = 10f
    if (dash != null) pathEffect = DashPathEffect(dash, dashPhase.toFloat())
}

/** `.linearGradient(Gradient(colors:), startPoint:, endPoint:)`, clamped past both ends like CoreGraphics. */
internal fun gradientPaint(colors: List<Color>, start: CGPoint, end: CGPoint, opacity: Double = 1.0): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = LinearGradient(
            start.x.toFloat(), start.y.toFloat(), end.x.toFloat(), end.y.toFloat(),
            colors.map { it.toArgb() }.toIntArray(), null, Shader.TileMode.CLAMP,
        )
        alpha = (opacity.coerceIn(0.0, 1.0) * 255).roundToInt()
    }

private fun alphaOf(color: Color, opacity: Double) = (color.alpha * opacity.coerceIn(0.0, 1.0) * 255).roundToInt().coerceIn(0, 255)

/**
 * A Gaussian blur of standard deviation [sigma] (what SwiftUI's blur radius is), in the units the canvas is
 * drawing in: Skia scales a mask filter's blur with the canvas matrix, as `GraphicsContext` scales its filters.
 * Android's blur takes a "radius" it turns into sigma = 0.57735·radius + 0.5, so this inverts that.
 */
internal fun blurFilter(sigma: Double): BlurMaskFilter? {
    if (sigma <= 0.5) return null
    return BlurMaskFilter(((sigma - 0.5) / 0.57735).toFloat(), BlurMaskFilter.Blur.NORMAL)
}

internal fun android.graphics.Canvas.drawPath(path: CGPath, paint: Paint) = drawPath(path.android(), paint)
