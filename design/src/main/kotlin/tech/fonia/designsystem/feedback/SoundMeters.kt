package tech.fonia.designsystem.feedback

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import tech.fonia.designsystem.letter.LetterDrive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Live meters: what the microphone hears, drawn while the person speaks, so every sound gets an answer on screen
 * at once. They read the same [LetterDrive] the living letter reads (volatile fields, once per frame, in the draw
 * phase only: no recomposition, no allocation per frame).
 *
 *  - [PlaceMeter]: where a hissing sound sits between Ș (round, back) and S (sharp, front), with the target zone.
 *  - [VoiceMeter]: whether the voice is on (Z, J, GE, R, L need it; S, Ș, Ț, CE do not).
 *  - [TrillMeter]: how strongly the tongue tip flutters (R).
 */
data class MeterColors(
    val track: Color,
    val zone: Color,
    val zoneOn: Color,
    val marker: Color,
    val ink: Color,
    /** The small highlight on the bead. */
    val gloss: Color,
)

/** Redraws the caller's Canvas every frame while it is on screen; the value is only read while drawing. */
@Composable
private fun rememberFrameTick(): () -> Long {
    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (isActive) withFrameNanos { tick.longValue = it }
    }
    return { tick.longValue }
}

/**
 * The S ↔ Ș axis. [targetSharp] is where the sound should sit: 1 = S (also Z, Ț), 0 = Ș (also J, CE, GE). The bead
 * appears when a hiss is heard and slides to where the hiss is; the target zone lights when the bead is inside it.
 * [leftLabel] and [rightLabel] are the two letters at the ends ("Ș" and "S" by default).
 */
@Composable
fun PlaceMeter(
    drive: LetterDrive,
    targetSharp: Float,
    colors: MeterColors,
    labelStyle: TextStyle,
    modifier: Modifier = Modifier,
    leftLabel: String = "Ș",
    rightLabel: String = "S",
) {
    val tick = rememberFrameTick()
    val state = remember { PlaceState() }
    Row(
        modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = "Unde e sunetul, între $leftLabel și $rightLabel" },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(leftLabel, style = labelStyle.copy(color = colors.ink))
        Canvas(Modifier.weight(1f).height(34.dp)) {
            state.step(drive, tick())
            val h = 16.dp.toPx()
            val top = (size.height - h) / 2f
            val r = CornerRadius(h / 2f)
            drawRoundRect(colors.track, Offset(0f, top), Size(size.width, h), r)
            // The target zone: the third of the track at the target's end.
            val zoneW = size.width * 0.34f
            val zoneX = if (targetSharp >= 0.5f) size.width - zoneW else 0f
            val inZone = state.shown > 0.2f && abs(state.pos - targetSharp) < 0.34f
            state.glow += ((if (inZone) 1f else 0f) - state.glow) * perFrame(0.18f, state.dt)
            drawRoundRect(lerpColor(colors.zone, colors.zoneOn, state.glow), Offset(zoneX, top), Size(zoneW, h), r)
            if (state.shown > 0.02f) {
                val pad = h * 0.9f
                fun x(p: Float) = pad + (size.width - 2 * pad) * p.coerceIn(0f, 1f)
                // A short tail behind the bead, so the eye sees which way the sound moved.
                for (i in 0 until PlaceState.TRAIL) {
                    val a = state.shown * (1f - (i + 1f) / (PlaceState.TRAIL + 1f)) * 0.22f
                    drawCircle(colors.marker.copy(alpha = a), h * 0.4f, Offset(x(state.trail[(state.head - 1 - i + PlaceState.TRAIL * 2) % PlaceState.TRAIL]), size.height / 2f))
                }
                // A solid knob with a white rim, like a slider's: crisp edges, no haze round it. It comes and goes by
                // size, nearly opaque all the way, so it never turns into a see-through smudge.
                val pulse = 1f + 0.1f * state.glow * sin(state.phase)
                val grow = (0.35f + 0.65f * min(1f, state.shown * 1.5f)) * pulse
                val solid = min(1f, state.shown * 3f)
                val centre = Offset(x(state.pos), size.height / 2f)
                drawCircle(colors.gloss.copy(alpha = solid), h * 0.95f * grow, centre)
                drawCircle(lerpColor(colors.marker, colors.zoneOn, state.glow).copy(alpha = solid), h * 0.72f * grow, centre)
            }
        }
        BasicText(rightLabel, style = labelStyle.copy(color = colors.ink))
    }
}

private class PlaceState {
    var pos = 0.5f
    var shown = 0f
    var glow = 0f
    var phase = 0f
    val trail = FloatArray(TRAIL) { 0.5f }
    var head = 0
    private var last = 0L
    private var sinceTrail = 0f

    /** Seconds since the previous frame (0 on the first). */
    var dt = 0f
        private set

    /** One frame at [nanos]: every rate is per second of real time, so a 120 Hz phone moves as calmly as a 60 Hz one. */
    fun step(drive: LetterDrive, nanos: Long) {
        dt = if (last == 0L || nanos <= last) 0f else ((nanos - last) / 1e9f).coerceAtMost(0.1f)
        last = nanos
        val s = drive.sharpness
        val hearing = drive.listening && !s.isNaN() && drive.fricative > 0.12f
        shown += ((if (hearing) 1f else 0f) - shown) * perFrame(if (hearing) 0.3f else 0.06f, dt)
        if (hearing) pos += (s.coerceIn(0f, 1f) - pos) * perFrame(0.16f, dt)
        phase += 6f * dt
        sinceTrail += dt
        if (sinceTrail >= 0.05f) {
            sinceTrail = 0f
            trail[head] = pos
            head = (head + 1) % TRAIL
        }
    }

    companion object {
        const val TRAIL = 5
    }
}

/**
 * The voice line: flat while there is no voice, a living wave while the vocal folds hum. With [wantVoice] the wave is
 * the good colour when the voice is on; without it (S, Ș, Ț, CE) a hum is shown in the quiet track colour only.
 */
@Composable
fun VoiceMeter(drive: LetterDrive, wantVoice: Boolean, colors: MeterColors, modifier: Modifier = Modifier) {
    val tick = rememberFrameTick()
    val state = remember { WaveState() }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(34.dp)
            .clearAndSetSemantics { contentDescription = if (wantVoice) "Vocea: pornită sau nu" else "Fără voce" },
    ) {
        state.tick(tick())
        val voiced = drive.listening && drive.voicing > 0.45f && drive.speaking
        state.amp += ((if (voiced) 0.75f else 0f) - state.amp) * perFrame(0.12f, state.dt)
        state.phase += 7f * state.dt
        val mid = size.height / 2f
        val color = if (wantVoice) lerpColor(colors.track, colors.zoneOn, (state.amp * 1.6f).coerceIn(0f, 1f)) else lerpColor(colors.track, colors.marker, (state.amp * 1.2f).coerceIn(0f, 1f))
        val path = state.path
        path.rewind()
        val steps = 48
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            // Pinned at both ends, fullest in the middle.
            val envelope = sin(t * PI.toFloat())
            val y = mid + sin(t * 6f * PI.toFloat() + state.phase) * envelope * state.amp * size.height * 0.42f
            if (i == 0) path.moveTo(0f, y) else path.lineTo(t * size.width, y)
        }
        drawPath(path, color, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
    }
}

private class WaveState {
    var amp = 0f
    var phase = 0f
    val path = Path()
    private var last = 0L
    var dt = 0f
        private set

    fun tick(nanos: Long) {
        dt = if (last == 0L || nanos <= last) 0f else ((nanos - last) / 1e9f).coerceAtMost(0.1f)
        last = nanos
    }
}

/** A per-frame easing factor tuned at 60 Hz, scaled to [dt] seconds (the same motion on a 60 or 120 Hz screen). */
private fun perFrame(at60: Float, dt: Float): Float = if (dt <= 0f) 0f else 1f - (1f - at60).pow(dt * 60f)

/** The flutter of the tongue tip for R: five beads that light from the left as the trill gets stronger, and shake. */
@Composable
fun TrillMeter(drive: LetterDrive, colors: MeterColors, modifier: Modifier = Modifier) {
    val tick = rememberFrameTick()
    val state = remember { WaveState() }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(34.dp)
            .clearAndSetSemantics { contentDescription = "Cât de tare vibrează limba" },
    ) {
        state.tick(tick())
        val strength = if (drive.listening) drive.trill.coerceIn(0f, 1f) else 0f
        state.amp += (strength - state.amp) * perFrame(0.15f, state.dt)
        state.phase += 14f * state.dt
        val n = 5
        val d = size.height * 0.62f
        val gap = (size.width - n * d) / (n + 1)
        for (i in 0 until n) {
            val on = ((state.amp * n) - i).coerceIn(0f, 1f)
            val shake = sin(state.phase + i * 1.3f) * on * d * 0.08f
            val c = Offset(gap + d / 2f + i * (d + gap), size.height / 2f + shake)
            drawCircle(colors.track, d / 2f, c)
            if (on > 0f) drawCircle(colors.zoneOn.copy(alpha = on), d / 2f * (0.55f + 0.45f * on), c)
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = androidx.compose.ui.graphics.lerp(a, b, t.coerceIn(0f, 1f))
