package tech.fonia.features.guide

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.hex
import tech.fonia.design.swiftSpring
import tech.fonia.design.ui
import tech.fonia.face.EvidenceStatus
import tech.fonia.face.FaceLevels
import tech.fonia.face.FaceSource
import tech.fonia.face.MirrorSnapshot
import tech.fonia.facecamera.CameraFaceSource
import tech.fonia.facecamera.FaceCameraView
import tech.fonia.facecamera.LipTargetOverlay
import tech.fonia.facecamera.MirrorColors
import tech.fonia.facecamera.MouthTracker
import tech.fonia.features.app.FaceCamera
import tech.fonia.model.SoundId
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The lip guide in the coach's colours: Foni's wing blue for the target, the parrot green when it matches; never red. */
internal val CoachMirrorColors = MirrorColors(
    target = Color.hex(0x4DA3E8),
    matched = Palette.progress,
    childLips = Color.White,
    neutral = Palette.lineStrong,
    hintBackground = Palette.sheet.copy(alpha = 0.92f),
    hintText = Palette.ink,
)

/** The corrections' colour (Foni's wing blue, deeper than the ring) and the white that outlines them on any face. */
private val CorrectionBlue = Color.hex(0x2F8FE6)
private val CorrectionEdge = Color.White

/**
 * Attaches [mouth] for as long as the coach is on screen (once, so the rest is taken once), keeps its target [sound],
 * and runs the debug [fixture]'s portrait script.
 */
@Composable
internal fun CoachMouthLifetime(mouth: MouthTracker?, sound: SoundId?, fixture: MirrorFixture?) {
    SideEffect { mouth?.sound = sound }
    DisposableEffect(mouth) {
        mouth?.attach()
        onDispose { mouth?.detach() }
    }
    LaunchedEffect(fixture) { fixture?.run() }
}

/**
 * Everything the self-view works out per display frame from the tracker's lock-free snapshot, outside the snapshot
 * system: the framing ([follower]), the lips in pane pixels, the hold ([hold]) and the correction with hysteresis.
 * Only [correction] and [successes] are Compose state, and they change rarely; [tick] is read in layer/draw blocks
 * only, so a camera frame never recomposes anything. No allocation per frame.
 */
@Stable
internal class SelfViewState {
    val snap = MirrorSnapshot()
    val follower = CropFollower()
    val picker = CorrectionPicker()
    val hold = HoldTimer()
    val lips = FloatArray(40)
    val shownLips = FloatArray(40)
    var hasLips = false
    var paneW = 0f
    var paneH = 0f
    var restProgress = 0f
    var matchedNow = false
    var mouthCx = 0f
    var mouthCy = 0f
    var mouthW = 0f
    var mouthH = 0f
    private var lastNanos = 0L
    private var hadFrame = false

    /** Debug builds: a correction to show whatever the camera says (`foniaCoachHint`), for screenshots. */
    var forced: Correction? = null

    /** False on steps the camera cannot judge (the tongue) or while the coach is finished: no hold, no praise. */
    var judging = true

    val tick = mutableLongStateOf(0L)
    var correction by mutableStateOf(Correction.None)
        private set
    var successes by mutableIntStateOf(0)
        private set

    fun frame(mouth: MouthTracker?, now: Long, reduceMotion: Boolean) {
        val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastNanos = now
        val read = mouth != null && mouth.read(snap)
        if (read) hadFrame = true
        hasLips = hadFrame && lipsToPane(snap, paneW, paneH, lips)
        if (hasLips) {
            val k = if (reduceMotion || mouthW == 0f) 1f else 1f - exp(-dt / 0.04f)
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (i in 0 until 20) {
                shownLips[i * 2] += (lips[i * 2] - shownLips[i * 2]) * k
                shownLips[i * 2 + 1] += (lips[i * 2 + 1] - shownLips[i * 2 + 1]) * k
                minX = min(minX, shownLips[i * 2]); maxX = max(maxX, shownLips[i * 2])
                minY = min(minY, shownLips[i * 2 + 1]); maxY = max(maxY, shownLips[i * 2 + 1])
            }
            mouthCx = (minX + maxX) / 2f
            mouthCy = (minY + maxY) / 2f
            mouthW = maxX - minX
            mouthH = maxY - minY
        }
        follower.update(hasLips, mouthCx, mouthCy, mouthW, paneW, paneH, now, reduceMotion)
        restProgress = snap.restProgress
        matchedNow = hasLips && snap.evidence.status == EvidenceStatus.Matches
        // A single unsteady frame (common with stills, and when a child moves a little) changes nothing: the shown
        // correction and the hold wait for the next steady frame instead of flickering.
        val unsteady = forced == null && hadFrame && snap.hasFace && snap.state.issue == tech.fonia.face.QualityIssue.Unsteady
        if (!unsteady) {
            val raw = forced ?: if (hadFrame) correctionFor(snap) else Correction.None
            val shown = picker.update(if (judging || raw != Correction.Match) raw else Correction.None, now)
            if (shown != correction) correction = shown
            if (hold.update(judging && (matchedNow || forced == Correction.Match), now)) successes += 1
        }
        tick.longValue = now
        if (tech.fonia.features.IS_DEBUG_BUILD && now - lastLog > 1_000_000_000L) {
            lastLog = now
            val st = snap.state
            val e = snap.evidence
            android.util.Log.i(
                "FoniaCoach",
                "self face=${snap.hasFace} target=${snap.hasTarget} q=${st.quality} issue=${st.issue} ${e.status} m=%.2f adv=${e.advice?.key} abst=${e.abstain} rest=%.2f z=%.2f shown=$correction hold=%.2f"
                    .format(e.match, snap.restProgress, follower.z, hold.fraction),
            )
        }
    }

    private var lastLog = 0L

    fun resetHold() = hold.reset()
}

/**
 * The bottom pane of the coach's video call: the child, large and edge to edge, mirrored and framed on the mouth
 * (a damped auto-zoom that follows the lips), with the lip guide (the child's contour and the target ring, blue → green)
 * and one big correction drawn over the child's own mouth. Frames stay in memory. In a debug build `foniaFace=fixture`
 * feeds the same pipeline from a still portrait ([fixture]); a source without a camera keeps the original view.
 */
@Composable
internal fun SelfView(
    state: SelfViewState,
    source: FaceSource?,
    fixture: MirrorFixture?,
    mouth: MouthTracker?,
    levels: FaceLevels,
    reduceMotion: Boolean,
    /** The lip guide's drawing (Setări → Experimental): off, the mirror still follows the mouth, without the guide. */
    guides: Boolean,
    modifier: Modifier = Modifier,
    onFailure: (Throwable) -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
) {
    val currentMouth by rememberUpdatedState(mouth)
    val currentReduce by rememberUpdatedState(reduceMotion)
    LaunchedEffect(state) {
        while (true) withFrameNanos { now -> state.frame(currentMouth, now, currentReduce) }
    }
    Box(
        modifier
            .clipToBounds()
            .background(Color.Black)
            .onSizeChanged { state.paneW = it.width.toFloat(); state.paneH = it.height.toFloat() }
            .semantics { contentDescription = "Camera din față" },
    ) {
        // The picture and the lip guide each in their own layer with the same framing transform: the guide redraws every
        // frame, the picture layer only moves (no re-recording of the camera or the still).
        val framing: Modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                state.tick.longValue // layer-phase read: only the layer's transform changes
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = state.follower.z
                scaleY = state.follower.z
                translationX = state.follower.tx
                translationY = state.follower.ty
            }
        Box(framing) {
            when {
                fixture != null -> FixtureMirror(fixture)
                source is CameraFaceSource && mouth != null -> FaceCameraView(source, levels, Modifier.fillMaxSize(), onFailure)
                source != null -> FaceCamera.View(source, levels, Modifier.fillMaxSize(), onFailure)
                else -> Unit
            }
        }
        if (mouth != null && guides) {
            Box(framing) { LipTargetOverlay(mouth.levels, CoachMirrorColors, Modifier.fillMaxSize(), reducedMotion = reduceMotion, hintText = { null }) }
        }
        val corrections = remember { CorrectionPainter() }
        Canvas(Modifier.fillMaxSize()) {
            val now = state.tick.longValue
            corrections.draw(this, state, state.correction, now, reduceMotion)
        }
        overlay()
    }
}

/** The one correction as a huge icon and at most two words, in the pane where it does not cover the mouth. */
@Composable
internal fun CorrectionChip(correction: Correction, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = correction,
        modifier = modifier,
        transitionSpec = { (fadeIn(swiftSpring(0.25, 0.1)) + scaleIn(swiftSpring(0.4, 0.4), initialScale = 0.8f)) togetherWith fadeOut(swiftSpring(0.2, 0.0)) },
        label = "correction",
    ) { shown ->
        if (shown != Correction.None) {
            val match = shown == Correction.Match
            Row(
                Modifier
                    .heightIn(min = 64.dp)
                    .background(if (match) Palette.progress else Palette.sheet.copy(alpha = 0.94f), RoundedCornerShape(50))
                    .padding(start = 8.dp, end = 22.dp, top = 8.dp, bottom = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(52.dp).background(if (match) Color.White.copy(alpha = 0.25f) else Palette.coldSoft, CircleShape), contentAlignment = Alignment.Center) {
                    HintGlyph(shown.glyph, if (match) Color.White else CorrectionBlue, Modifier.size(38.dp))
                }
                FoniaText(shown.words, style = ui(26, UIWeight.bold, TextRole.title2), color = if (match) Color.White else Palette.ink, maxLines = 1)
            }
        }
    }
}

/** The still portrait, mirrored and cropped like the preview (FILL_CENTER). */
@Composable
private fun FixtureMirror(fixture: MirrorFixture) {
    val picture = fixture.picture.value
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (picture != null) {
            Image(picture, null, Modifier.fillMaxSize().graphicsLayer { scaleX = -1f }, contentScale = ContentScale.Crop)
        }
    }
}

/** The big icons of the corrections, drawn (no font, no bitmap): one shape a five-year-old reads without words. */
@Composable
internal fun HintGlyph(name: String, tint: Color, modifier: Modifier) {
    Canvas(modifier) { drawHintGlyph(name, tint) }
}

private fun DrawScope.drawHintGlyph(name: String, tint: Color) {
    val w = size.width
    val h = size.height
    val s = Stroke(w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun arrow(x0: Float, y0: Float, x1: Float, y1: Float) {
        drawLine(tint, Offset(x0 * w, y0 * h), Offset(x1 * w, y1 * h), w * 0.09f, StrokeCap.Round)
        val dx = x1 - x0
        val dy = y1 - y0
        val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
        val ux = dx / len
        val uy = dy / len
        val hl = 0.16f
        drawLine(tint, Offset(x1 * w, y1 * h), Offset((x1 - ux * hl - uy * hl) * w, (y1 - uy * hl + ux * hl) * h), w * 0.09f, StrokeCap.Round)
        drawLine(tint, Offset(x1 * w, y1 * h), Offset((x1 - ux * hl + uy * hl) * w, (y1 - uy * hl - ux * hl) * h), w * 0.09f, StrokeCap.Round)
    }
    fun lips(cx: Float, cy: Float, rw: Float, rh: Float) {
        val p = Path().apply {
            moveTo((cx - rw) * w, cy * h)
            cubicTo((cx - rw * 0.5f) * w, (cy - rh) * h, (cx - rw * 0.1f) * w, (cy - rh) * h, cx * w, (cy - rh * 0.6f) * h)
            cubicTo((cx + rw * 0.1f) * w, (cy - rh) * h, (cx + rw * 0.5f) * w, (cy - rh) * h, (cx + rw) * w, cy * h)
            cubicTo((cx + rw * 0.5f) * w, (cy + rh * 1.2f) * h, (cx - rw * 0.5f) * w, (cy + rh * 1.2f) * h, (cx - rw) * w, cy * h)
        }
        drawPath(p, tint, style = s)
    }
    when (name) {
        "smile" -> { lips(0.5f, 0.5f, 0.2f, 0.12f); arrow(0.3f, 0.5f, 0.06f, 0.5f); arrow(0.7f, 0.5f, 0.94f, 0.5f) }
        "lessSmile" -> { lips(0.5f, 0.5f, 0.18f, 0.12f); arrow(0.02f, 0.5f, 0.26f, 0.5f); arrow(0.98f, 0.5f, 0.74f, 0.5f) }
        "round" -> { drawCircle(tint, w * 0.18f, Offset(w * 0.5f, h * 0.5f), style = s); drawCircle(tint, w * 0.4f, Offset(w * 0.5f, h * 0.5f), style = Stroke(w * 0.05f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 0.08f, w * 0.07f)))) }
        "open" -> { lips(0.5f, 0.5f, 0.2f, 0.12f); arrow(0.5f, 0.3f, 0.5f, 0.04f); arrow(0.5f, 0.7f, 0.5f, 0.96f) }
        "close" -> { lips(0.5f, 0.5f, 0.2f, 0.12f); arrow(0.5f, 0.02f, 0.5f, 0.28f); arrow(0.5f, 0.98f, 0.5f, 0.72f) }
        "closer" -> {
            drawRoundRect(tint, Offset(w * 0.3f, h * 0.3f), Size(w * 0.4f, h * 0.4f), CornerRadius(w * 0.06f), style = s)
            arrow(0.04f, 0.04f, 0.24f, 0.24f); arrow(0.96f, 0.96f, 0.76f, 0.76f)
        }
        "farther" -> {
            drawRoundRect(tint, Offset(w * 0.3f, h * 0.3f), Size(w * 0.4f, h * 0.4f), CornerRadius(w * 0.06f), style = s)
            arrow(0.26f, 0.26f, 0.04f, 0.04f); arrow(0.74f, 0.74f, 0.96f, 0.96f)
        }
        "sun" -> {
            drawCircle(tint, w * 0.18f, Offset(w * 0.5f, h * 0.5f))
            for (i in 0 until 8) rotate(i * 45f) { drawLine(tint, Offset(w * 0.5f, h * 0.12f), Offset(w * 0.5f, h * 0.24f), w * 0.08f, StrokeCap.Round) }
        }
        "face", "calm" -> {
            drawOval(tint, Offset(w * 0.18f, h * 0.08f), Size(w * 0.64f, h * 0.84f), style = s)
            drawCircle(tint, w * 0.05f, Offset(w * 0.38f, h * 0.4f))
            drawCircle(tint, w * 0.05f, Offset(w * 0.62f, h * 0.4f))
            if (name == "calm") drawLine(tint, Offset(w * 0.38f, h * 0.66f), Offset(w * 0.62f, h * 0.66f), w * 0.08f, StrokeCap.Round)
            else drawArc(tint, 20f, 140f, false, Offset(w * 0.36f, h * 0.5f), Size(w * 0.28f, h * 0.2f), style = s)
        }
        "star" -> {
            val p = Path()
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 0.46f else 0.2f
                val a = -PI / 2 + i * PI / 5
                val x = (0.5f + r * kotlin.math.cos(a).toFloat()) * w
                val y = (0.52f + r * kotlin.math.sin(a).toFloat()) * h
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            drawPath(p, tint)
        }
        "tongueUp" -> {
            val p = Path().apply {
                moveTo(0.28f * w, 0.42f * h); lineTo(0.28f * w, 0.66f * h)
                cubicTo(0.28f * w, 0.98f * h, 0.72f * w, 0.98f * h, 0.72f * w, 0.66f * h); lineTo(0.72f * w, 0.42f * h)
            }
            drawPath(p, tint, style = s)
            arrow(0.5f, 0.34f, 0.5f, 0.02f)
        }
        else -> Unit
    }
}

/**
 * Draws the shown correction over the child's mouth from the latest lips (pane pixels → screen through the framing):
 * arrows on the mouth corners, a pulsing ring to grow into, arrows at the chin, a frame that shrinks or grows, a sun, a
 * face outline; on a match a soft green glow and a tick; and the hold arc. Reused paths, no allocation per frame.
 */
private class CorrectionPainter {
    private val path = Path()
    private val head = Path()

    fun draw(scope: DrawScope, s: SelfViewState, correction: Correction, now: Long, reduceMotion: Boolean) = with(scope) {
        val t = now / 1e9f
        val pulse = if (reduceMotion) 0.5f else 0.5f + 0.5f * sin(2f * PI.toFloat() * 1.1f * t)
        val f = s.follower
        val cx = f.toScreenX(s.mouthCx)
        val cy = f.toScreenY(s.mouthCy)
        val mw = (s.mouthW * f.z).coerceAtLeast(40.dp.toPx())
        val mh = (s.mouthH * f.z).coerceAtLeast(14.dp.toPx())
        val unit = 1.dp.toPx()
        val hasMouth = s.hasLips
        when (correction) {
            Correction.Smile, Correction.Relax -> if (hasMouth) {
                val gap = mw * (0.08f + 0.12f * pulse)
                bigArrow(cx - mw / 2 - gap, cy, cx - mw / 2 - gap - mw * 0.32f, cy, unit)
                bigArrow(cx + mw / 2 + gap, cy, cx + mw / 2 + gap + mw * 0.32f, cy, unit)
            }
            Correction.LessSmile -> if (hasMouth) {
                val gap = mw * (0.42f - 0.1f * pulse)
                bigArrow(cx - mw / 2 - gap, cy, cx - mw / 2 - mw * 0.06f, cy, unit)
                bigArrow(cx + mw / 2 + gap, cy, cx + mw / 2 + mw * 0.06f, cy, unit)
            }
            Correction.Round -> if (hasMouth) {
                val r = mw * (0.36f + 0.08f * pulse)
                drawCircle(CorrectionEdge.copy(alpha = 0.7f), r, Offset(cx, cy), style = Stroke(9f * unit))
                drawCircle(CorrectionBlue, r, Offset(cx, cy), style = Stroke(5f * unit))
                // Four chevrons around it pushing the lips together into the ring.
                val out = mw * 0.62f
                for (k in 0 until 4) {
                    val a = k * PI.toFloat() / 2f
                    val ox = kotlin.math.cos(a)
                    val oy = kotlin.math.sin(a)
                    val d = out + mw * 0.1f * (1f - pulse)
                    bigArrow(cx + ox * (d + mw * 0.2f), cy + oy * (d + mw * 0.2f), cx + ox * d, cy + oy * d, unit, small = true)
                }
            }
            Correction.Open, Correction.Part -> if (hasMouth) {
                val reach = if (correction == Correction.Open) 0.42f else 0.26f
                val gap = mh * 0.3f + mw * 0.08f * pulse
                bigArrow(cx, cy - mh / 2 - gap, cx, cy - mh / 2 - gap - mw * reach * 0.7f, unit)
                bigArrow(cx, cy + mh / 2 + gap, cx, cy + mh / 2 + gap + mw * reach, unit)
            }
            Correction.Close -> if (hasMouth) {
                val gap = mh * 0.3f + mw * (0.42f - 0.1f * pulse)
                bigArrow(cx, cy - mh / 2 - gap, cx, cy - mh / 2 - mh * 0.2f, unit)
                bigArrow(cx, cy + mh / 2 + gap, cx, cy + mh / 2 + mh * 0.2f, unit)
            }
            Correction.Closer, Correction.Farther -> {
                // A frame that shrinks toward the face ("come closer") or grows to the edges ("further back").
                val phase = if (reduceMotion) 0.5f else (t * 0.7f) % 1f
                val k = if (correction == Correction.Closer) 1f - 0.35f * phase else 0.65f + 0.35f * phase
                val fw = size.width * 0.86f * k
                val fh = size.height * 0.8f * k
                val alpha = if (correction == Correction.Closer) 1f - phase * 0.6f else 0.4f + phase * 0.6f
                drawRoundRect(CorrectionEdge.copy(alpha = alpha), Offset((size.width - fw) / 2, (size.height - fh) / 2), Size(fw, fh), CornerRadius(28f * unit), style = Stroke(8f * unit))
                drawRoundRect(CorrectionBlue.copy(alpha = alpha), Offset((size.width - fw) / 2, (size.height - fh) / 2), Size(fw, fh), CornerRadius(28f * unit), style = Stroke(4f * unit))
            }
            Correction.Light, Correction.LessLight -> {
                val r = 34f * unit
                val c = Offset(size.width - r * 2.2f, size.height * 0.42f)
                drawCircle(Color.hex(0xFFD54A), r * (0.9f + 0.1f * pulse), c)
                rotate(if (reduceMotion) 0f else t * 30f, c) {
                    for (i in 0 until 8) rotate(i * 45f, c) { drawLine(Color.hex(0xFFD54A), Offset(c.x, c.y - r * 1.35f), Offset(c.x, c.y - r * 1.85f), 7f * unit, StrokeCap.Round) }
                }
            }
            Correction.NoFace, Correction.WholeMouth, Correction.LookHere -> {
                val ow = size.width * 0.5f
                val oh = ow * 1.3f
                drawOval(CorrectionEdge.copy(alpha = 0.5f + 0.4f * pulse), Offset((size.width - ow) / 2, (size.height - oh) / 2), Size(ow, oh), style = Stroke(6f * unit, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f * unit, 12f * unit))))
            }
            Correction.Calibrate -> if (hasMouth) {
                val r = mw * 0.75f
                drawCircle(CorrectionEdge.copy(alpha = 0.35f), r, Offset(cx, cy), style = Stroke(4f * unit))
                drawArc(CorrectionEdge, -90f, 360f * s.restProgress, false, Offset(cx - r, cy - r), Size(r * 2, r * 2), style = Stroke(5f * unit, cap = StrokeCap.Round))
            }
            Correction.Match -> if (hasMouth) {
                // A soft green glow around the child's own lips and a tick by the corner.
                closedLips(s, path)
                drawPath(path, Palette.progress.copy(alpha = 0.25f), style = Stroke(22f * unit, join = StrokeJoin.Round))
                drawPath(path, Palette.progress.copy(alpha = 0.45f), style = Stroke(10f * unit, join = StrokeJoin.Round))
                val tx = cx + mw * 0.62f
                val ty = cy - mh * 0.2f
                head.reset()
                head.moveTo(tx, ty)
                head.lineTo(tx + 10f * unit, ty + 10f * unit)
                head.lineTo(tx + 30f * unit, ty - 14f * unit)
                drawPath(head, Color.White, style = Stroke(10f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(head, Palette.progress, style = Stroke(6f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Correction.None -> Unit
        }
        // How long the pose has been held: a slim arc around the mouth.
        val held = s.hold.fraction
        if (hasMouth && held > 0.01f) {
            val r = mw * 0.72f
            drawArc(Palette.progress, -90f, 360f * held, false, Offset(cx - r, cy - r), Size(r * 2, r * 2), style = Stroke(5f * unit, cap = StrokeCap.Round))
        }
    }

    private fun closedLips(s: SelfViewState, out: Path) {
        val f = s.follower
        out.reset()
        for (i in 0 until 20) {
            val x = f.toScreenX(s.shownLips[i * 2])
            val y = f.toScreenY(s.shownLips[i * 2 + 1])
            if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
        }
        out.close()
    }

    /** A fat arrow from (x0, y0) to (x1, y1), blue with a white edge so it reads on any skin and any light. */
    private fun DrawScope.bigArrow(x0: Float, y0: Float, x1: Float, y1: Float, unit: Float, small: Boolean = false) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val ux = dx / len
        val uy = dy / len
        val hl = (if (small) 14f else 22f) * unit
        val width = (if (small) 7f else 11f) * unit
        head.reset()
        head.moveTo(x1 - ux * hl - uy * hl * 0.8f, y1 - uy * hl + ux * hl * 0.8f)
        head.lineTo(x1, y1)
        head.lineTo(x1 - ux * hl + uy * hl * 0.8f, y1 - uy * hl - ux * hl * 0.8f)
        if (!small) {
            drawLine(CorrectionEdge, Offset(x0, y0), Offset(x1 - ux * 4f * unit, y1 - uy * 4f * unit), width + 6f * unit, StrokeCap.Round)
        }
        drawPath(head, CorrectionEdge, style = Stroke(width + 6f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (!small) drawLine(CorrectionBlue, Offset(x0, y0), Offset(x1 - ux * 4f * unit, y1 - uy * 4f * unit), width, StrokeCap.Round)
        drawPath(head, CorrectionBlue, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
