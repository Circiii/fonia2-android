package tech.fonia.designsystem.letter

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import tech.fonia.designsystem.motion.Motion
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaLetterTokens
import tech.fonia.designsystem.tokens.FoniaPalette
import tech.fonia.designsystem.tokens.FoniaSticker
import tech.fonia.designsystem.tokens.FoniaTypeScale
import tech.fonia.designsystem.tokens.StickerSet
import kotlin.math.max
import kotlin.math.min

/**
 * A practice word (plan §4): 2–50 letters on fixed slots; the [target] letters are stickers that follow [drive] and
 * play the cues sent to [state] (40 ms apart), the others are plain ink that stays still while the child reads.
 * It shrinks from [fontSize] down to 36 sp to fit its width. Drawn only in the draw phase, like [LiveLetter].
 */
@Composable
fun LiveWord(
    text: String,
    modifier: Modifier = Modifier,
    target: List<IntRange> = emptyList(),
    weightAt: ((index: Int) -> Float)? = null,
    state: LiveLetterState? = null,
    drive: LetterDrive = remember { LetterDrive() },
    sticker: StickerSet = FoniaSticker.Voice,
    fontSize: TextUnit = FoniaTypeScale.practiceWord.sizeSp.sp,
    contentDescription: String = text,
    verdicts: tech.fonia.designsystem.tokens.VerdictStickers = tech.fonia.designsystem.tokens.VerdictStickers.Default,
    /** The set of the plain letters; null = the surface's (ink on Paper). */
    plain: StickerSet? = null,
) {
    val font by rememberAlloOutlines()
    val reduce = FoniaTheme.reduceMotion
    val flat = plain ?: FoniaTheme.flat
    val engine = remember(font) { font?.let { LiveWordEngine(it) } }
    val painter = remember { WordPainter() }
    val frame = remember { mutableLongStateOf(0L) }
    // layout depends on the text: set it during composition (idempotent; allocates only when the text changes)
    engine?.weightAt = weightAt
    engine?.setText(text, target)
    val layoutKey = engine?.text

    SideEffect {
        LetterFrameStats.recompositions++
        engine?.let {
            it.baseSet = sticker
            it.flatSet = flat
            it.verdicts = verdicts
            it.reduceMotion = reduce
            state?.word = it
        }
        painter.flat = flat
    }

    LaunchedEffect(engine) {
        val e = engine ?: return@LaunchedEffect
        var quietSince = 0L
        while (isActive) {
            val t = withFrameNanos { it }
            e.advance(t, drive)
            if (e.idle(drive)) {
                if (quietSince == 0L) quietSince = t
                if (t - quietSince > 300_000_000L) { delay(100); continue }
            } else quietSince = 0L
            frame.longValue = t
        }
    }

    Box(
        modifier
            .semantics { this.contentDescription = contentDescription }
            .layout { measurable, constraints ->
                val e = engine?.takeIf { layoutKey != null }
                val maxPx = fontSize.toPx()
                val minPx = FoniaTypeScale.PRACTICE_WORD_MIN_SP.sp.toPx()
                val unitW = (e?.width ?: 0f) / (e?.font?.upm ?: 2048)
                var em = maxPx
                if (constraints.hasBoundedWidth && unitW > 0f) em = min(maxPx, max(minPx, constraints.maxWidth / unitW))
                val w = (unitW * em).toInt().coerceIn(constraints.minWidth, if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE)
                val h = (em * 1.5f).toInt().coerceAtLeast(constraints.minHeight)
                val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
                layout(w, h) { placeable.place(0, 0) }
            }
            .drawBehind {
                frame.longValue
                val e = engine ?: return@drawBehind
                val t0 = System.nanoTime()
                val unitW = e.width / e.font.upm
                e.emPx = min(fontSize.toPx(), if (unitW > 0f) size.width / unitW else fontSize.toPx())
                e.originX = (size.width - unitW * e.emPx) / 2f
                e.baselineY = size.height * 0.70f
                drawIntoCanvas { painter.draw(it.nativeCanvas, e) }
                LetterFrameStats.record(System.nanoTime() - t0)
            },
    )
}

/** Draws a [LiveWordEngine]: plain letters, then the stickers layer by layer over the whole word (§4). */
internal class WordPainter {
    private var paths = Array(0) { Path() }
    private val sink = ComposePathSink(Path())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val k = StickerColors()
    private val beat = StickerColors()
    private val shadow = FoniaPalette.stickerShadow.color.toArgb()
    private val sunArgb = FoniaPalette.sun.color.toArgb()
    private val sunRim = FoniaPalette.sunDeep.color.toArgb()
    var flat: StickerSet = FoniaSticker.FlatOnPaper

    fun draw(c: Canvas, e: LiveWordEngine) {
        val n = e.count
        if (paths.size < n) paths = Array(n) { Path().apply { fillType = PathFillType.NonZero } }
        for (i in 0 until n) { paths[i].rewind(); sink.path = paths[i]; e.emitLetter(i, sink) }
        val em = e.emPx

        // slot-drop: the empty slot pulses Sun once
        if (e.pulseIndex in 0 until n && e.pulse > 0.01f) {
            val cx = e.slotCenterPx(e.pulseIndex)
            val r = e.slotWidthPx(e.pulseIndex) * 0.36f * (0.7f + 0.5f * e.pulse)
            val cy = e.baselineY - em * 0.3f
            paint.style = Paint.Style.FILL
            paint.color = fade(sunRim, e.pulse)
            c.drawRoundRect(cx - r - em * 0.03f, cy - r * 1.2f - em * 0.03f, cx + r + em * 0.03f, cy + r * 1.2f + em * 0.03f, r, r, paint)
            paint.color = fade(sunArgb, e.pulse)
            c.drawRoundRect(cx - r, cy - r * 1.2f, cx + r, cy + r * 1.2f, r, r, paint)
        }

        // plain letters
        val flatArgb = flat.face.toArgb()
        for (i in 0 until n) {
            if (e.isTarget(i)) continue
            val p = e.pose(i)
            paint.style = Paint.Style.FILL
            if (p.badgeKind == 9) {
                // syllable beat: the active syllable takes the Voice set for the beat
                beat.set(FoniaSticker.Voice, p.alpha)
                layers(c, paths[i], beat, em * 0.16f, em * 0.08f, em, p)
            } else {
                paint.color = fade(flatArgb, p.alpha)
                c.drawPath(paths[i].asAndroidPath(), paint)
            }
        }

        // targets, layer by layer over the whole word
        k.blend(e.prevSet, e.curSet, e.setMix, 1f)
        val solid = 1f - k.flatness
        val rimW = e.rimEm * em * e.targetScale * solid
        val edgeW = e.edgeEm * em * e.targetScale * solid
        if (solid > 0.01f) {
            for (i in 0 until n) if (e.isTarget(i)) {
                val p = e.pose(i)
                c.save(); c.translate(FoniaLetterTokens.SHADOW_DX_EM * em, FoniaLetterTokens.SHADOW_DY_EM * em + p.shadowLift * em)
                sf(c, paths[i], fade(shadow, p.alpha * solid * (1f - 0.5f * Motion.clamp01(p.shadowLift / 0.4f))), rimW)
                c.restore()
            }
            for (i in 0 until n) if (e.isTarget(i)) sf(c, paths[i], fade(k.rim, e.pose(i).alpha), rimW * e.pose(i).rimMul)
            for (i in 0 until n) if (e.isTarget(i)) sf(c, paths[i], fade(k.edge, e.pose(i).alpha), edgeW * e.pose(i).rimMul)
        }
        for (i in 0 until n) if (e.isTarget(i)) {
            val p = e.pose(i)
            paint.style = Paint.Style.FILL
            paint.color = fade(k.face, p.alpha)
            c.drawPath(paths[i].asAndroidPath(), paint)
            if (p.halfFill > 0f) {
                c.save()
                val y = e.baselineY - p.fillLine * em * 0.55f
                c.clipRect(0f, y, c.width.toFloat(), e.baselineY + em)
                paint.color = fade(k.edge, p.alpha)
                c.drawPath(paths[i].asAndroidPath(), paint)
                c.restore()
            }
        }
    }

    private fun layers(c: Canvas, path: Path, s: StickerColors, rim: Float, edge: Float, em: Float, p: LetterPose) {
        c.save(); c.translate(0.03f * em, 0.09f * em)
        sf(c, path, fade(shadow, p.alpha), rim)
        c.restore()
        sf(c, path, s.rim, rim)
        sf(c, path, s.edge, edge)
        paint.style = Paint.Style.FILL
        paint.color = s.face
        c.drawPath(path.asAndroidPath(), paint)
    }

    private fun sf(c: Canvas, path: Path, argb: Int, w: Float) {
        if ((argb ushr 24) == 0) return
        paint.style = if (w > 0.05f) Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
        paint.strokeWidth = w
        paint.color = argb
        c.drawPath(path.asAndroidPath(), paint)
    }
}
