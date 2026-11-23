package tech.fonia.facecamera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.fonia.face.EvidenceStatus
import tech.fonia.face.FaceLevels
import tech.fonia.face.FaceQuality
import tech.fonia.face.FaceSource
import tech.fonia.face.MirrorSnapshot
import tech.fonia.face.MouthLevels
import tech.fonia.face.QualityIssue
import tech.fonia.model.SoundId
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * The mirror's colours, passed in by the screen (the design system owns the palette). Never red: [matched] is the
 * parrot green, [target] the blue of the guide, [neutral] the grey used while the camera is unsure.
 */
data class MirrorColors(
    val target: Color,
    val matched: Color,
    val childLips: Color,
    val neutral: Color,
    val hintBackground: Color,
    val hintText: Color,
)

/** Default Romanian hint for a quality issue (docs/v2/04 §3); null = say nothing. Screens may pass their own. */
fun defaultMirrorHint(issue: QualityIssue): String? = when (issue) {
    QualityIssue.NoFace -> "Unde ești? Vino în oglindă"
    QualityIssue.PartialFace -> "Arată-mi toată gura"
    QualityIssue.TooFar -> "Vino mai aproape"
    QualityIssue.TooClose -> "Puțin mai departe"
    QualityIssue.TurnToCamera -> "Uită-te la Foni"
    QualityIssue.TooDark -> "Mai multă lumină pe față"
    QualityIssue.TooBright -> "Prea multă lumină"
    QualityIssue.Unsteady, QualityIssue.None -> null
}

/**
 * `MIRROR` (docs/v2/04 §4): the mirrored front camera, edge to edge, with the lip guide on top. It owns the camera's
 * lifetime through [FaceCameraView] (permission, start/stop with the composition, closed in the background) and
 * attaches [mouth] while on screen. [sound] chooses the target ring; null shows only the child's lips.
 *
 * Edge-to-edge: the preview fills the whole box (draw behind the system bars); only the hint keeps clear of them.
 */
@Composable
fun MirrorView(
    source: FaceSource,
    faceLevels: FaceLevels,
    mouth: MouthTracker,
    sound: SoundId?,
    colors: MirrorColors,
    modifier: Modifier = Modifier,
    showChildLips: Boolean = true,
    reducedMotion: Boolean = false,
    hintText: (QualityIssue) -> String? = ::defaultMirrorHint,
    onFailure: ((Throwable) -> Unit)? = null,
) {
    SideEffect { mouth.sound = sound }
    DisposableEffect(mouth) {
        mouth.attach()
        onDispose { mouth.detach() }
    }
    Box(modifier.testTag(MIRROR_TAG)) {
        FaceCameraView(source, faceLevels, Modifier.fillMaxSize(), onFailure)
        LipTargetOverlay(mouth.levels, colors, Modifier.fillMaxSize(), showChildLips, reducedMotion, hintText)
    }
}

const val MIRROR_TAG = "face.mirror"
const val LIP_OVERLAY_TAG = "face.lipOverlay"

/**
 * The lip guide (docs/v2/04 §4), drawn in the draw phase from the latest [levels] snapshot: a frame loop bumps a
 * draw-only state, so a camera frame never recomposes anything. Over a `PreviewView` in FILL_CENTER, mirrored.
 *
 * - the child's own outer lip contour, a soft thin line;
 * - the target ring: dashed blue with a soft halo; the dashes lengthen as the match nears; matched = solid green,
 *   5 dp, one pulse (≥ 300 ms) and a small tick by the mouth corner; never red;
 * - camera unsure: thin grey ring and a short hint (too far, too dark, turn to the camera), never flickering.
 */
@Composable
fun LipTargetOverlay(
    levels: MouthLevels,
    colors: MirrorColors,
    modifier: Modifier = Modifier,
    showChildLips: Boolean = true,
    reducedMotion: Boolean = false,
    hintText: (QualityIssue) -> String? = ::defaultMirrorHint,
) {
    val holder = remember(levels) { OverlayHolder() }
    val frameTick = remember { mutableLongStateOf(0L) }
    var hint by remember { mutableStateOf<QualityIssue?>(null) }
    val currentReduced by rememberUpdatedState(reducedMotion)

    LaunchedEffect(levels) {
        while (true) {
            withFrameNanos { now ->
                holder.advance(levels, now, currentReduced)
                frameTick.longValue = now
                val stable = holder.stableIssue(now)
                if (stable != hint) hint = stable
            }
        }
    }

    Box(modifier) {
        Canvas(Modifier.fillMaxSize().testTag(LIP_OVERLAY_TAG)) {
            val now = frameTick.longValue // draw-phase read: only the drawing is invalidated
            holder.draw(this, colors, showChildLips, reducedMotion, now)
        }
        val text = hint?.let(hintText)
        if (text != null) {
            BasicText(
                text = text,
                style = TextStyle(color = colors.hintText, fontSize = 18.sp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 12.dp)
                    .background(colors.hintBackground, RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag(MIRROR_HINT_TAG),
            )
        }
    }
}

const val MIRROR_HINT_TAG = "face.mirrorHint"

/** Per-overlay mutable drawing state, outside the snapshot system on purpose (draw-phase only). */
internal class OverlayHolder {
    val snap = MirrorSnapshot()
    private var lastVersion = -1L
    private var hasData = false

    // Displayed (interpolated) rings, normalised image coordinates.
    private val child = FloatArray(40)
    private val target = FloatArray(40)
    private var primed = false
    private var lastNanos = 0L

    private val childPath = Path()
    private val targetPath = Path()
    private val tickPath = Path()
    private val screen = FloatArray(40)

    private var matchedSince = -1L
    private var wasMatched = false
    private var issue: QualityIssue = QualityIssue.None
    private var issueSince = 0L

    fun advance(levels: MouthLevels, now: Long, reducedMotion: Boolean) {
        val version = levels.version
        if (version != lastVersion) {
            lastVersion = version
            hasData = levels.read(snap)
        }
        if (!hasData || !snap.hasFace) {
            primed = false
        } else {
            // Ease toward the newest analysis frame at display rate (≈ 30 ms time constant); direct with reduced motion.
            val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
            val k = if (!primed || reducedMotion) 1f else 1f - kotlin.math.exp(-dt / 0.03f)
            for (i in 0 until 40) {
                child[i] += (snap.lipsOuter[i] - child[i]) * k
                if (snap.hasTarget) target[i] += (snap.targetOuter[i] - target[i]) * k
            }
            if (!primed && snap.hasTarget) snap.targetOuter.copyInto(target)
            primed = true
        }
        lastNanos = now
        val matched = hasData && snap.evidence.status == EvidenceStatus.Matches
        if (matched && !wasMatched) matchedSince = now
        wasMatched = matched
        val current = if (!hasData) QualityIssue.NoFace else snap.state.issue
        if (current != issue) {
            issue = current
            issueSince = now
        }
    }

    /** The issue to show once it has lasted 600 ms (no flicker); null when the camera is fine. */
    fun stableIssue(now: Long): QualityIssue? {
        if (!hasData) return null
        if (snap.state.quality == FaceQuality.Good) return null
        return if (now - issueSince >= 600_000_000L) issue else null
    }

    fun draw(scope: DrawScope, colors: MirrorColors, showChildLips: Boolean, reducedMotion: Boolean, now: Long) = with(scope) {
        if (!hasData || !snap.hasFace || !primed || snap.imageW <= 0) return@with
        val iw = snap.imageW.toFloat()
        val ih = snap.imageH.toFloat()
        val scale = max(size.width / iw, size.height / ih) // FILL_CENTER
        val dx = (size.width - iw * scale) / 2f
        val dy = (size.height - ih * scale) / 2f
        fun toScreen(src: FloatArray) {
            for (k in 0 until 20) {
                screen[k * 2] = (1f - src[k * 2]) * iw * scale + dx // mirrored once, here
                screen[k * 2 + 1] = src[k * 2 + 1] * ih * scale + dy
            }
        }
        val good = snap.state.quality == FaceQuality.Good
        if (showChildLips) {
            toScreen(child)
            smoothClosed(screen, childPath)
            drawPath(childPath, colors.childLips.copy(alpha = 0.55f), style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
        }
        if (!snap.hasTarget) return@with
        toScreen(target)
        smoothClosed(screen, targetPath)
        if (!good) {
            drawPath(targetPath, colors.neutral.copy(alpha = 0.7f), style = Stroke(1.5.dp.toPx()))
            return@with
        }
        val ev = snap.evidence
        if (ev.status == EvidenceStatus.Matches) {
            val t = (now - matchedSince) / 1e9f
            val pulse = if (reducedMotion || t > PULSE_S) 0f else sin(PI.toFloat() * t / PULSE_S)
            drawPath(targetPath, colors.matched.copy(alpha = 0.18f + 0.22f * pulse), style = Stroke((10f + 8f * pulse).dp.toPx()))
            drawPath(targetPath, colors.matched, style = Stroke(5.dp.toPx(), join = StrokeJoin.Round))
            // A small tick by the corner that is on the screen's right.
            val cx = max(screen[0], screen[20])
            val cy = if (screen[0] >= screen[20]) screen[1] else screen[21]
            val u = 6.dp.toPx()
            tickPath.reset()
            tickPath.moveTo(cx + u, cy - u * 0.2f)
            tickPath.lineTo(cx + u * 1.8f, cy + u * 0.7f)
            tickPath.lineTo(cx + u * 3.4f, cy - u * 1.3f)
            drawPath(tickPath, colors.matched, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            // Dashes lengthen with the match: 6 dp far, 20 dp when nearly there.
            val near = ((ev.match - 0.3f) / 0.45f).coerceIn(0f, 1f)
            val on = (6f + 14f * near).dp.toPx()
            drawPath(targetPath, colors.target.copy(alpha = 0.2f), style = Stroke(9.dp.toPx()))
            drawPath(
                targetPath, colors.target,
                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(on, 6.dp.toPx()))),
            )
        }
    }

    companion object {
        private const val PULSE_S = 0.45f

        /** Closed Catmull–Rom spline through 20 points (x, y), as cubic Béziers. */
        fun smoothClosed(p: FloatArray, path: Path) {
            path.reset()
            val n = p.size / 2
            path.moveTo(p[0], p[1])
            for (i in 0 until n) {
                val i0 = (i - 1 + n) % n
                val i2 = (i + 1) % n
                val i3 = (i + 2) % n
                val c1x = p[i * 2] + (p[i2 * 2] - p[i0 * 2]) / 6f
                val c1y = p[i * 2 + 1] + (p[i2 * 2 + 1] - p[i0 * 2 + 1]) / 6f
                val c2x = p[i2 * 2] - (p[i3 * 2] - p[i * 2]) / 6f
                val c2y = p[i2 * 2 + 1] - (p[i3 * 2 + 1] - p[i * 2 + 1]) / 6f
                path.cubicTo(c1x, c1y, c2x, c2y, p[i2 * 2], p[i2 * 2 + 1])
            }
            path.close()
        }
    }
}
