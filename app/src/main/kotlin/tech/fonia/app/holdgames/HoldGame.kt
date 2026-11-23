package tech.fonia.app.holdgames

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import tech.fonia.designsystem.theme.FoniaColors
import tech.fonia.model.SoundId
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What a hold game reads each frame: the controller copies these from `LiveBoard.latest()` (the only reader) into one
 * preallocated instance; games never touch the board, never allocate, never cause a recomposition.
 */
class HoldInput {
    @JvmField var dt = 0f
    /** The mic is open and it is the child's turn. */
    @JvmField var listening = false
    @JvmField var level = 0f
    @JvmField var speaking = false
    @JvmField var fricative = false
    @JvmField var sibilance = 0f
    @JvmField var sNess = 0.5f
    @JvmField var voicing = 0f
    @JvmField var trill = 0f
    @JvmField var trillDetected = false
    @JvmField var onTargetNow = false
    @JvmField var holdSeconds = 0f
}

/** Where the living letter sits in the scene: centre as fractions of the scene, scale of the base letter, rotation. */
class LetterPose {
    @JvmField var x = 0.5f
    @JvmField var y = 0.3f
    @JvmField var scale = 0.6f
    @JvmField var rotation = 0f
}

/** The tokens a game paints with (no raw colours; no red; nothing that reads as failure). */
class GamePalette(c: FoniaColors) {
    val bg = c.bg
    val surface = c.surface
    val fill = c.fill
    val separator = c.separator
    val ink = c.ink
    val inkSecondary = c.inkSecondary
    val accent = c.accent
    val accentDeep = c.accentDeep
    val voice = c.voice
    val voiceDeep = c.voiceDeep
    val parrot = c.parrot
    val parrotDeep = c.parrotDeep
    val sun = c.sun
    val sunDeep = c.sunDeep
    val shadow = c.stickerShadow
    val gloss = c.gloss
}

/** Short voiced or unvoiced bursts ("ț", "ci", "ge", "la"): hysteresis on the level, at least 120 ms apart. */
class Onsets(private val on: Float = 0.2f, private val off: Float = 0.08f) {
    private var active = false
    private var quiet = 0f
    private var since = 1f

    fun reset() { active = false; quiet = 0f; since = 1f }

    /** True on the frame a new burst starts. */
    fun feed(level: Float, dt: Float): Boolean {
        since += dt
        if (!active) {
            if (level > on && since > 0.12f) { active = true; quiet = 0f; since = 0f; return true }
        } else if (level < off) {
            quiet += dt
            if (quiet > 0.05f) active = false
        } else {
            quiet = 0f
        }
        return false
    }
}

/**
 * One sound's `Hold` mini-game (docs/v2/05 §6 "Umflă litera", one character per sound, chapter 06 §4). All nine are
 * driven by the same live band; each draws its scene in the draw phase and says where the living letter sits.
 *
 * Sustain games fill with seconds of good sound ([good]); burst games count [Onsets]. Progress never goes down inside
 * a take (nothing is lost). [shown] is the springy, smoothed progress the drawing uses.
 */
abstract class HoldGame(val sound: SoundId) {
    abstract val bursts: Boolean

    /** Seconds (sustain) or bursts (burst) to fill the game; set by the step (adaptation). */
    var goal: Float = 2f

    var time = 0f; private set
    var held = 0f; protected set
    var count = 0; protected set
    var shown = 0f; private set
    private var velocity = 0f
    private val onsets = Onsets()
    val letter = LetterPose()

    open val progress: Float get() = if (bursts) (count / max(1f, goal)).coerceIn(0f, 1f) else (held / max(0.3f, goal)).coerceIn(0f, 1f)
    val reached: Boolean get() = progress >= 1f

    /** A new take: the scene goes back to its start (gently: the spring carries it). */
    fun reset() {
        held = 0f
        count = 0
        onsets.reset()
        onReset()
    }

    fun update(i: HoldInput) {
        val dt = min(i.dt, 0.05f)
        time += dt
        if (i.listening) {
            if (bursts) {
                if (onsets.feed(burstLevel(i), dt)) { count++; onBurst() }
            } else if (good(i)) {
                held += dt
            }
        }
        // a soft spring towards the progress (stiffness 60, damping 0.7): springy, never jumpy
        val k = 60f
        val d = 2f * sqrt(k) * 0.7f
        velocity += ((progress - shown) * k - velocity * d) * dt
        shown += velocity * dt
        step(i, dt)
    }

    /** Sustain: is this frame good sound for this game? (the band's own hold, or the game's looser cue). */
    protected open fun good(i: HoldInput): Boolean = i.onTargetNow

    /** Burst games: the level the onset detector watches. */
    protected open fun burstLevel(i: HoldInput): Float = i.level

    protected open fun onBurst() {}
    protected open fun onReset() {}
    protected open fun step(i: HoldInput, dt: Float) {}

    abstract fun DrawScope.scene(p: GamePalette)

    /** The character alone, as a sticker (the house's header and the `Hold` card). [t] seconds for idle motion. */
    abstract fun DrawScope.icon(p: GamePalette, t: Float)

    companion object {
        fun of(sound: SoundId): HoldGame = when (sound) {
            SoundId.S -> SnakeGame()
            SoundId.Sh -> TrainGame()
            SoundId.Z -> BeeGame()
            SoundId.J -> WindGame()
            SoundId.Ts -> MouseGame()
            SoundId.Ce -> CanGame()
            SoundId.Ge -> MotorGame()
            SoundId.R -> TigerGame()
            SoundId.L -> LionGame()
        }
    }
}

// ---- sticker drawing helpers (the design language: shadow, dark rim, white edge, face, gloss)

/** A round sticker. */
fun DrawScope.stickerCircle(p: GamePalette, c: Offset, r: Float, face: Color, rim: Color, gloss: Boolean = true) {
    val e = max(1.5f, r * 0.12f)
    drawCircle(p.shadow, r + e * 1.6f, c + Offset(r * 0.06f, r * 0.14f))
    drawCircle(rim, r + e * 1.6f, c)
    drawCircle(p.surface, r + e * 0.8f, c)
    drawCircle(face, r, c)
    if (gloss) drawArc(p.gloss, 200f, 70f, false, c - Offset(r * 0.72f, r * 0.72f), Size(r * 1.44f, r * 1.44f), style = Stroke(r * 0.14f, cap = StrokeCap.Round))
}

/** An oval sticker inside [tl]/[size]. */
fun DrawScope.stickerOval(p: GamePalette, tl: Offset, size: Size, face: Color, rim: Color) {
    val e = max(1.5f, min(size.width, size.height) * 0.08f)
    drawOval(p.shadow, tl + Offset(e * 0.6f, e * 1.4f) - Offset(e * 1.6f, e * 1.6f), Size(size.width + e * 3.2f, size.height + e * 3.2f))
    drawOval(rim, tl - Offset(e * 1.6f, e * 1.6f), Size(size.width + e * 3.2f, size.height + e * 3.2f))
    drawOval(p.surface, tl - Offset(e * 0.8f, e * 0.8f), Size(size.width + e * 1.6f, size.height + e * 1.6f))
    drawOval(face, tl, size)
}

/** A filled path as a sticker ([edge] = width of the rim, px). */
fun DrawScope.stickerPath(p: GamePalette, path: Path, face: Color, rim: Color, edge: Float) {
    translate(edge * 0.4f, edge * 1.1f) { drawPath(path, p.shadow, style = Stroke(edge * 2.4f, join = StrokeJoin.Round)) }
    drawPath(path, rim, style = Stroke(edge * 2.4f, join = StrokeJoin.Round))
    drawPath(path, p.surface, style = Stroke(edge * 1.2f, join = StrokeJoin.Round))
    drawPath(path, face)
}

/** A thick stroked line as a sticker (snake bodies, stems, rails). */
fun DrawScope.stickerStroke(p: GamePalette, path: Path, face: Color, rim: Color, width: Float) {
    translate(width * 0.08f, width * 0.2f) { drawPath(path, p.shadow, style = Stroke(width * 1.45f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
    drawPath(path, rim, style = Stroke(width * 1.45f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, p.surface, style = Stroke(width * 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, face, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Two friendly eyes looking at [look] (−1…1 horizontally). */
fun DrawScope.eyes(p: GamePalette, a: Offset, b: Offset, r: Float, look: Float = 0f) {
    eye(p, a, r, look)
    eye(p, b, r, look)
}

fun DrawScope.eye(p: GamePalette, c: Offset, r: Float, look: Float = 0f) {
    drawCircle(p.surface, r, c)
    drawCircle(p.ink, r * 0.58f, c + Offset(look * r * 0.3f, r * 0.08f))
    drawCircle(p.surface, r * 0.2f, c + Offset(look * r * 0.3f + r * 0.18f, -r * 0.16f))
}

/** A five-point star (rewards, goals). */
fun DrawScope.star(p: GamePalette, c: Offset, r: Float, path: Path) {
    path.reset()
    for (k in 0 until 10) {
        val a = Math.toRadians((-90 + k * 36).toDouble())
        val rr = if (k % 2 == 0) r else r * 0.45f
        val x = c.x + (rr * kotlin.math.cos(a)).toFloat()
        val y = c.y + (rr * kotlin.math.sin(a)).toFloat()
        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    stickerPath(p, path, p.sun, p.sunDeep, r * 0.12f)
}

/** The ground band every scene stands on. */
fun DrawScope.ground(p: GamePalette, top: Float, face: Color, line: Color) {
    drawRect(face, Offset(0f, top), Size(size.width, size.height - top))
    drawLine(line, Offset(0f, top), Offset(size.width, top), size.minDimension * 0.012f)
}

/** Lerp. */
fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
