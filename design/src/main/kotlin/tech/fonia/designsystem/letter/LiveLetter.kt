package tech.fonia.designsystem.letter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.runtime.withFrameNanos
import tech.fonia.designsystem.motion.FoniaOverlay
import tech.fonia.designsystem.motion.LocalFoniaOverlay
import tech.fonia.designsystem.motion.OverlayDrawer
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaSticker
import tech.fonia.designsystem.tokens.StickerSet

/**
 * The event side of a [LiveLetter] / [LiveWord]: cues go in, [busy] comes out. Hold one per screen
 * (`remember { LiveLetterState() }`); cues sent before the letter is composed wait for its first frame.
 */
@Stable
class LiveLetterState {
    internal var engine: LetterEngine? = null
        set(value) {
            field = value
            if (value != null) while (early.isNotEmpty()) value.cue(early.removeFirst())
        }
    internal var word: LiveWordEngine? = null
        set(value) {
            field = value
            if (value != null) while (early.isNotEmpty()) value.cue(early.removeFirst())
        }
    private val early = ArrayDeque<LetterCue>()

    /** Starts [c] on the next frame (see [LetterCue] for the vocabulary). */
    fun cue(c: LetterCue) {
        val e = engine
        val w = word
        when {
            e != null -> e.cue(c)
            w != null -> w.cue(c)
            else -> early.addLast(c)
        }
    }

    /** True while an animation runs (held final frames of verdicts / instructions do not count). */
    val busy: Boolean get() = engine?.busy ?: word?.busy ?: false

    /** Names of the running and held cues (for the lab and logs). */
    val activeCues: String get() = engine?.activeCueNames ?: word?.activeCueNames ?: ""
}

/** Frame statistics of the letters on screen (debug readout; cheap enough to leave on). */
object LetterFrameStats {
    /** Duration of the last LiveLetter / LiveWord draw, ns. */
    @Volatile var lastDrawNanos = 0L
    /** Exponential average of the draw, ns. */
    @Volatile var avgDrawNanos = 0.0
    /** Times LiveLetter / LiveWord recomposed since start: must not grow while a letter animates. */
    @Volatile var recompositions = 0L

    internal fun record(ns: Long) {
        lastDrawNanos = ns
        avgDrawNanos = if (avgDrawNanos == 0.0) ns.toDouble() else avgDrawNanos * 0.95 + ns * 0.05
    }
}

/**
 * The hero letter (plan §4): a sticker made of the live Allo outline (shadow, rim, edge, face, gloss) that follows
 * [drive] every frame and plays the cues sent to [state].
 *
 * Drawing happens only in the draw phase: the frame loop advances the engine and bumps a frame counter that only the
 * draw lambda reads, so a running letter causes no recomposition and no layout. The frame loop sleeps (10 Hz polling of
 * [drive]) when nothing moves.
 *
 * @param grapheme 1–3 graphemes: "Ș", "ce", "sa".
 * @param sticker the resting set (null = plain letter, the grown-up shell); verdicts change it for a while.
 * @param height the box height; the letter at full inflation fills ~60 % of it, POP and props overflow it on purpose.
 * @param overlay where the POP / Correct particles are drawn: by default the nearest
 *   [tech.fonia.designsystem.motion.FoniaOverlayHost], above every sibling and never clipped by the scene box; null
 *   (no host) draws them in the letter's own box, as before.
 */
@Composable
fun LiveLetter(
    grapheme: String,
    state: LiveLetterState,
    modifier: Modifier = Modifier,
    drive: LetterDrive = remember { LetterDrive() },
    sticker: StickerSet? = FoniaSticker.Voice,
    height: Dp = 200.dp,
    heard: String? = null,
    onPop: (() -> Unit)? = null,
    contentDescription: String = grapheme,
    overlay: FoniaOverlay? = LocalFoniaOverlay.current,
    verdicts: tech.fonia.designsystem.tokens.VerdictStickers = tech.fonia.designsystem.tokens.VerdictStickers.Default,
) {
    val font by rememberAlloOutlines()
    val reduce = FoniaTheme.reduceMotion
    val flat = FoniaTheme.flat
    val engine = remember(font) { font?.let { LetterEngine(it) } }
    val painter = remember { StickerPainter() }
    val frame = remember { mutableLongStateOf(0L) }
    val popCallback by rememberUpdatedState(onPop)
    val link = remember { OverlayLink() }

    DisposableEffect(overlay, engine) {
        val e = engine
        if (overlay == null || e == null) return@DisposableEffect onDispose { }
        val drawer = OverlayDrawer { canvas, host ->
            val c = link.coords
            if (c != null && c.isAttached && e.particles.alive) {
                val o = host.localPositionOf(c, Offset.Zero)
                canvas.save()
                canvas.translate(o.x, o.y)
                painter.drawParticlesOnly(canvas, e)
                canvas.restore()
            }
        }
        overlay.add(drawer)
        onDispose { overlay.remove(drawer) }
    }

    SideEffect {
        LetterFrameStats.recompositions++
        engine?.let {
            it.setText(grapheme)
            it.baseSet = sticker ?: flat
            it.flatSet = flat
            it.verdicts = verdicts
            it.reduceMotion = reduce
            it.onPop = { popCallback?.invoke() }
            state.engine = it
        }
        painter.flatSet = flat
        painter.heard = heard
        painter.gloss = sticker != null
        painter.inlineParticles = overlay == null
        link.overlay = overlay
    }

    LaunchedEffect(engine) {
        val e = engine ?: return@LaunchedEffect
        var quietSince = 0L
        while (isActive) {
            val t = withFrameNanos { it }
            e.advance(t, drive)
            link.overlay?.let { o ->
                val alive = e.particles.alive
                if (alive || link.wasAlive) o.invalidate()
                link.wasAlive = alive
            }
            if (e.idle(drive)) {
                if (quietSince == 0L) quietSince = t
                if (t - quietSince > 300_000_000L) {
                    delay(100) // asleep: nothing changed, nothing to redraw; poll the drive at 10 Hz
                    continue
                }
            } else quietSince = 0L
            frame.longValue = t
        }
    }

    Box(
        modifier
            .sizeIn(minWidth = height * 1.2f, minHeight = height)
            .height(height)
            .semantics { this.contentDescription = contentDescription }
            .onGloballyPositioned { link.coords = it }
            .drawBehind {
                frame.longValue // the only state read: a new frame redraws, nothing recomposes
                val e = engine ?: return@drawBehind
                val t0 = System.nanoTime()
                e.emPx = size.height * HERO_EM
                e.anchorX = size.width / 2f
                e.anchorY = size.height * HERO_CAP_CENTER
                drawIntoCanvas { painter.draw(it.nativeCanvas, e) }
                LetterFrameStats.record(System.nanoTime() - t0)
            },
    )
}

private class OverlayLink {
    var coords: LayoutCoordinates? = null
    var overlay: FoniaOverlay? = null
    var wasAlive = false
}

/** One em of the hero letter as a fraction of its box height. */
internal const val HERO_EM = 0.54f
/** Where the cap-height centre of the hero letter sits, as a fraction of the box height. */
internal const val HERO_CAP_CENTER = 0.47f
