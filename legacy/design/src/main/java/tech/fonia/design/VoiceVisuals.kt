package tech.fonia.design

import android.graphics.Paint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A frame clock like `TimelineView(.animation(minimumInterval:paused:))`: while [running], the returned state
 * takes the frame time (nanoseconds) at most every [minimumInterval] seconds. Read it while drawing, so a
 * frame redraws without recomposing.
 */
@Composable
internal fun rememberFrameClock(running: Boolean, minimumInterval: Double = 0.0): State<Long> {
    val clock = remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(running, minimumInterval) {
        if (!running) return@LaunchedEffect
        val step = (minimumInterval * 1e9).toLong()
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                // A small tolerance, so a 1/30 s clock on a 60 Hz display takes every other frame.
                if (step == 0L || now - last >= step - 2_000_000L) {
                    last = now
                    clock.longValue = now
                }
            }
        }
    }
    return clock
}

/** "I hear you": bars that rise with the voice and sit low in silence. Says nothing about quality, on purpose. */
@Composable
fun VoiceBars(isLive: Boolean, color: Color, level: () -> Float, modifier: Modifier = Modifier) {
    val clock = rememberFrameClock(isLive, 1.0 / 30)
    val history = remember { LevelHistory() }
    val read by rememberUpdatedState(level)
    Canvas(
        modifier.semantics {
            contentDescription = "Nivelul vocii"
            stateDescription = if (isLive) "Ascult" else "Oprit"
        },
    ) {
        clock.value // one push per clock tick, as the canvas redraws once per timeline update on iOS
        val bar = 3 * density
        val gap = 3 * density
        val count = max(1, ((size.width + gap) / (bar + gap)).toInt())
        history.push(read(), count)
        val values = history.values.takeLast(count)
        val lead = size.width - count * (bar + gap) + gap
        for (index in 0 until count) {
            val source = index - (count - values.size)
            val value = if (source >= 0) min(1f, values[source]) else 0f
            val height = bar + (size.height - bar) * value
            drawRoundRect(
                color,
                topLeft = Offset(lead / 2 + index * (bar + gap), (size.height - height) / 2),
                size = Size(bar, height),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
    }
}

private class LevelHistory {
    val values = ArrayList<Float>()

    fun push(value: Float, capacity: Int) {
        values.add(value)
        if (values.size > capacity * 2) values.subList(0, values.size - capacity).clear()
    }
}

/** A bar that fills while a sound lasts, with a mark for each second to reach. */
@Composable
fun HoldMeter(seconds: Double, isLive: Boolean, color: Color, track: Color, progress: () -> Double, modifier: Modifier = Modifier) {
    val clock = rememberFrameClock(isLive, 1.0 / 60)
    val read by rememberUpdatedState(progress)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(30.dp)
            .semantics { contentDescription = "Cât a ținut sunetul" },
    ) {
        clock.value
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        val value = min(1.0, max(0.0, read())).toFloat()
        if (value > 0.005f) {
            drawRoundRect(color, size = Size(max(size.height, size.width * value), size.height), cornerRadius = radius)
        }
        val ticks = max(1, seconds.roundToInt())
        if (ticks > 1) {
            val dot = 4 * density
            for (tick in 1 until ticks) {
                val x = size.width * tick / ticks
                drawCircle(
                    if (x < size.width * value) track else color.opacity(0.45),
                    radius = dot / 2,
                    center = Offset(x, size.height / 2),
                )
            }
        }
    }
}

/** A candle whose flame leans away from the breath and goes out when the breath is strong enough. */
@Composable
fun BlowFlame(isLive: Boolean, isOut: Boolean, strength: () -> Float, modifier: Modifier = Modifier) {
    val clock = rememberFrameClock(isLive && !isOut, 1.0 / 60)
    val read by rememberUpdatedState(strength)
    // `.transition(.opacity)` under `.animation(.easeOut(duration: 0.2), value: isOut)`.
    val flameOpacity by animateFloatAsState(if (isOut) 0f else 1f, easeOut(0.2), label = "flame")
    BoxWithConstraints(modifier.clearAndSetSemantics {}, contentAlignment = Alignment.BottomCenter) {
        val w = maxWidth
        val h = maxHeight
        FoniaImage("candle", Modifier.height(h * 0.62f))
        if (flameOpacity > 0f) {
            FoniaImage(
                "flame",
                Modifier
                    .width(w * 0.42f)
                    .graphicsLayer {
                        val t = clock.value / 1e9
                        val breath = if (isOut) 0f else read()
                        val flicker = (sin(t * 11) * 0.03 + sin(t * 17) * 0.02).toFloat()
                        val scale = max(0.45f, 1 - breath * 0.55f + flicker)
                        scaleX = scale
                        scaleY = scale
                        rotationZ = breath * 42 + flicker * 120
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        translationX = breath * w.toPx() * 0.08f
                        translationY = -h.toPx() * 0.58f
                        alpha = flameOpacity
                    },
            )
        }
    }
}

/** One line in a target zone. The same drawing runs live and in the report, so each teaches the other. */
@Composable
fun PaceChart(
    samples: List<Double>,
    zone: ClosedFloatingPointRange<Double>,
    modifier: Modifier = Modifier,
    maximum: Double = 240.0,
    line: Color = Palette.ink,
    zoneColor: Color = Palette.cold.opacity(0.35),
) {
    Canvas(modifier) {
        platform { canvas ->
            canvas.save()
            canvas.scale(density, density)
            val w = size.width / density.toDouble()
            val h = size.height / density.toDouble()
            fun y(value: Double) = h * (1 - min(1.0, value / maximum))
            val band = CGRect(0.0, y(zone.endInclusive), w, y(zone.start) - y(zone.endInclusive))
            canvas.drawPath(CGPath.continuous(band, 8.0), fillPaint(zoneColor))
            if (samples.size > 1) {
                val path = CGPath()
                samples.forEachIndexed { i, value ->
                    val point = CGPoint(w * i / (samples.size - 1), y(value))
                    if (i == 0) path.move(point) else path.addLine(point)
                }
                canvas.drawPath(path, strokePaint(line, 3.0, Paint.Cap.ROUND, Paint.Join.ROUND))
            }
            canvas.restore()
        }
    }
}

/** The learner's melody over the model's, in semitones and aligned in time. Gaps are unvoiced stretches. */
@Composable
fun MelodyOverlay(
    model: List<Double?>,
    take: List<Double?>,
    modelColor: Color,
    takeColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        platform { canvas ->
            canvas.save()
            canvas.scale(density, density)
            val w = size.width / density.toDouble()
            val h = size.height / density.toDouble()
            val all = (model + take).filterNotNull()
            val low = (all.minOrNull() ?: -6.0) - 1
            val high = (all.maxOrNull() ?: 6.0) + 1
            fun path(values: List<Double?>): CGPath {
                val path = CGPath()
                var pen = false
                val count = max(values.size - 1, 1)
                values.forEachIndexed { i, value ->
                    if (value == null) {
                        pen = false
                        return@forEachIndexed
                    }
                    val point = CGPoint(w * i / count, h * (1 - (value - low) / max(high - low, 1.0)))
                    if (pen) path.addLine(point) else {
                        path.move(point)
                        pen = true
                    }
                }
                return path
            }
            canvas.drawPath(path(model), strokePaint(modelColor, 9.0, Paint.Cap.ROUND, Paint.Join.ROUND))
            canvas.drawPath(path(take), strokePaint(takeColor, 3.5, Paint.Cap.ROUND, Paint.Join.ROUND))
            canvas.restore()
        }
    }
}
