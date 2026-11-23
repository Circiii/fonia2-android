package tech.fonia.mouth3d

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import tech.fonia.mouth3d.style.Anatomy
import tech.fonia.mouth3d.style.Geo
import tech.fonia.mouth3d.style.P2
import tech.fonia.mouth3d.style.SagittalShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.fonia.model.SoundId
import tech.fonia.mouth3d.render.Mouth3DController
import tech.fonia.mouth3d.render.Mouth3DThread
import kotlin.math.max
import kotlin.math.min

/** Frame timing of the GL renderer, averaged over [frames] frames (CPU time of a frame, swap included). */
data class Mouth3DFrameStats(val averageMs: Double, val maxMs: Double, val frames: Int, val msaaSamples: Int)

/**
 * The stylised 3D mouth (packages H6–H8, docs/v2/04 §7–9): lips, teeth, tongue and palate on a transparent
 * background, showing the position of [sound] (null = rest).
 *
 * @param progress 0 = rest, 1 = the sound's pose; springs smooth every change.
 * @param inside the cut-away side view with the tongue. Shown only when the pose is approved by a speech therapist
 *   (`"insideApproved"` in spec/poses/mouth3d, decision N17) or [allowUnapprovedInside] is set (review/debug builds);
 *   otherwise the front view stays. [Mouth3DData.insideAvailable] tells the screen whether to offer the toggle.
 * @param colors the app's tokens (no dependency on the design system).
 * @param playing runs the pose's short animation (Ț, CE, GE: closure → release; R: flutter; L: release).
 * @param autoRotate a gentle sideways sway (off with [reduceMotion]).
 * @param reduceMotion 150 ms straight transitions, no loops, no flutter, no sway.
 * @param onDoubleTap e.g. toggle [inside]; null = no double-tap.
 * @param groups the parts that have moved toward the pose so far (the guide's step-by-step reveal); all by default.
 * @param highlight the part the current step is about ([PoseStep.articulator]): it glows softly in both views; for
 *   [Articulator.Air] the airflow grows. Null = no glow.
 * @param forceFallback draw the 2D side view; it is also used without GL ES 3, on low-RAM phones, if GL fails, and
 *   (with [autoFallback]) after 30 frames in a row over 12 ms.
 */
@Composable
fun Mouth3D(
    sound: SoundId?,
    progress: Float,
    inside: Boolean,
    colors: Mouth3DColors,
    modifier: Modifier = Modifier,
    playing: Boolean = true,
    autoRotate: Boolean = false,
    reduceMotion: Boolean = rememberReduceMotion(),
    allowUnapprovedInside: Boolean = Mouth3DReview.showUnapprovedInside,
    onDoubleTap: (() -> Unit)? = null,
    onFrameStats: ((Mouth3DFrameStats) -> Unit)? = null,
    forceFallback: Boolean = false,
    autoFallback: Boolean = true,
    groups: Set<PoseGroup> = PoseGroup.ALL,
    highlight: Articulator? = null,
) {
    val context = LocalContext.current
    val data by produceState<Mouth3DData?>(null) { value = withContext(Dispatchers.IO) { Mouth3DAssets.load(context) } }
    val d = data
    if (d == null) {
        Box(modifier)
        return
    }
    val spec = d.spec(sound)
    val showInside = inside && d.insideAvailable(sound, allowUnapprovedInside)
    val inputs = SceneInputs(
        pose = spec,
        rest = d.library.rest,
        progress = progress,
        inside = showInside,
        playing = playing,
        autoRotate = autoRotate,
        reduceMotion = reduceMotion,
        groups = groups,
        highlight = highlight,
        cutAllowed = d.insideAvailable(sound, allowUnapprovedInside),
    )
    var glBroken by remember { mutableStateOf(false) }
    val supported = remember(context) { Mouth3DSupport.canRender3D(context) }
    val use2d = forceFallback || !supported || glBroken
    val description = spec.description
    val doubleTap by rememberUpdatedState(onDoubleTap)

    Box(modifier.semantics { contentDescription = description }) {
        if (use2d) {
            Mouth2DFallback(d, inputs, colors, Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures(onDoubleTap = { doubleTap?.invoke() }) })
        } else {
            Mouth3DSurface(d, inputs, colors, onFrameStats, onTrouble = { slow -> if (!slow || autoFallback) glBroken = true }, { doubleTap }, Modifier.matchParentSize())
        }
    }
}

@Composable
private fun Mouth3DSurface(
    data: Mouth3DData,
    inputs: SceneInputs,
    colors: Mouth3DColors,
    onFrameStats: ((Mouth3DFrameStats) -> Unit)?,
    onTrouble: (slow: Boolean) -> Unit,
    onDoubleTap: () -> (() -> Unit)?,
    modifier: Modifier,
) {
    val controller = remember { Mouth3DController() }
    val trouble by rememberUpdatedState(onTrouble)
    SideEffect {
        controller.inputs = inputs
        controller.colors = colors
        controller.onStats = onFrameStats
        controller.onTooSlow = { trouble(true) }
        controller.onFailed = { trouble(false) }
        controller.changed()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> { controller.active = true; controller.changed() }
                Lifecycle.Event.ON_PAUSE -> controller.active = false
                else -> Unit
            }
        }
        controller.active = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val thread = remember { Mouth3DThread(data, controller).also { it.startThread() } }
    DisposableEffect(thread) { onDispose { thread.shutdown() } }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = false
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { thread.attach(st, w, h) }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { thread.resize(w, h) }
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { thread.detach(); return true }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
        )
        // Gestures on a layer above the TextureView (it would not pass them on reliably).
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { controller.release() },
                        onDragCancel = { controller.release() },
                    ) { change, drag ->
                        change.consume()
                        controller.drag(drag.x / density * DEG_PER_DP, drag.y / density * DEG_PER_DP)
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onDoubleTap()?.invoke() }) },
        )
    }
}

private const val DEG_PER_DP = 0.4f

/**
 * The 2D side view used when 3D is not available: the same side diagram as the 3D inside view ([SagittalShape]), flat,
 * with the same springs and poses.
 */
@Composable
private fun Mouth2DFallback(data: Mouth3DData, inputs: SceneInputs, colors: Mouth3DColors, modifier: Modifier) {
    val scene = remember { MouthScene() }
    val latest by rememberUpdatedState(inputs)
    var shape by remember { mutableStateOf<SagittalShape?>(null) }
    LaunchedEffect(Unit) {
        var last = 0L
        var moving = true
        var lastInputs: SceneInputs? = null
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else (now - last) / 1e9f
                last = now
                val inp = latest
                if (moving || inp != lastInputs) {
                    val f = scene.step(dt, inp.copy(inside = true, autoRotate = false))
                    shape = SagittalShape.of(f.pose)
                    moving = f.animating && !f.ambient
                    lastInputs = inp
                }
            }
        }
    }
    val sh = shape ?: return
    val air = inputs.pose.air
    Canvas(modifier) {
        val zMin = Anatomy.FRAME_MIN_X; val zMax = Anatomy.FRAME_MAX_X; val yMin = Anatomy.FRAME_MIN_Y; val yMax = Anatomy.FRAME_MAX_Y
        val scale = min(size.width / (zMax - zMin), size.height / (yMax - yMin))
        val ox = (size.width - (zMax - zMin) * scale) / 2
        val oy = (size.height - (yMax - yMin) * scale) / 2
        fun pt(p: P2) = Offset(ox + (p.x - zMin) * scale, oy + (p.y - yMin) * scale)
        fun path(pts: List<P2>, close: Boolean = true) = Path().apply {
            pts.forEachIndexed { i, p -> val o = pt(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
            if (close) close()
        }
        val line = Stroke(width = max(1.5f, 0.9f * scale), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun piece(pts: List<P2>, c: Color, outline: Color? = null) {
            val p = path(pts); drawPath(p, c); if (outline != null) drawPath(p, outline, style = line)
        }
        val tissueLine = colors.section.copy(red = colors.section.red * 0.8f, green = colors.section.green * 0.8f, blue = colors.section.blue * 0.8f)
        clipRect {
            piece(sh.silhouette, colors.section)
            piece(sh.airSpace, colors.lining)
            piece(sh.tongue, colors.tongue, colors.tongue.copy(red = colors.tongue.red * 0.72f, green = colors.tongue.green * 0.72f, blue = colors.tongue.blue * 0.72f))
            piece(Anatomy.palateBone, Color.White.copy(alpha = 0.6f).compositeOver(colors.section), tissueLine)
            piece(Anatomy.palateLining, colors.gums, tissueLine)
            piece(Geo.catmull(sh.lowerCrown, true, 4), colors.teeth, tissueLine)
            piece(Geo.catmull(sh.upperLip, true, 4), colors.section)
            piece(Geo.catmull(sh.upperLip.subList(3, 12), false, 4), colors.lips)
            piece(Anatomy.upperCrown, colors.teeth, tissueLine)
            piece(Geo.catmull(sh.lowerLip, true, 4), colors.section)
            piece(Geo.catmull(sh.lowerLip.subList(17, 20) + sh.lowerLip.subList(0, 5), false, 4), colors.lips)
            if (air != null) drawPath(path(sh.airPath(air.stop, air.stream == AirStream.Lateral).first, close = false), if (air.warm) colors.airWarm else colors.airCold, style = Stroke(width = max(2f, 1.4f * scale), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * scale, 5f * scale))))
        }
    }
}
