package tech.fonia.design

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.animation.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

@Suppress("EnumEntryName")
enum class FoniMood {
    /** Standing around, looking about. */
    idle,

    /** Head cocked, the way a bird listens. */
    listening,

    /** Waves once, then stays friendly. */
    hello,

    /** Hops and flaps once, then stays pleased. */
    happy,

    /** Slow breathing, head down. */
    asleep,

    /** Eyes closed, breathing in and out with the warm-up. */
    calm,
}

/** The rings around Foni follow the family of the sound being practised. */
@Suppress("EnumEntryName")
enum class FoniTone {
    cold, warm, tip, neutral;

    val glow: Color
        get() = when (this) {
            cold -> Color.hex(0x4E9DF2)
            warm -> Color.hex(0xF2628C)
            tip -> Color.hex(0xFBAE3C)
            neutral -> Color.hex(0x6FBF8B)
        }

    companion object {
        /** `FoniTone(field)`. */
        operator fun invoke(field: Field): FoniTone = when (field) {
            Field.cold -> cold
            Field.warm -> warm
            Field.tip -> tip
            Field.night, Field.paper -> neutral
        }
    }
}

/** One drawing of the mascot, for places that stay still on purpose. */
@Suppress("EnumEntryName")
enum class MascotPose {
    hello, happy, easy, ready, talk, inhale, exhale, asleep, peek, cloud;

    val rawValue: String get() = name
}

@Composable
fun MascotView(pose: MascotPose, modifier: Modifier = Modifier) {
    FoniaImage("mascot-${pose.rawValue}", modifier, contentScale = ContentScale.Fit)
}

/**
 * Foni alive. It behaves like a small parrot: quick turns of the head with long holds between them, a blink
 * now and then, slow breathing, a flick of the tail. A voice opens its beak as far as the voice is loud, so a
 * child sees their own voice move it. Tapping it makes it hop.
 *
 * Every frame comes from `withFrameNanos`: the pose is a pure function of time, as on iOS, and is kept in a
 * state that only the drawing reads, so the bird moves without recomposing anything.
 */
@Composable
fun Foni(
    modifier: Modifier = Modifier,
    mood: FoniMood = FoniMood.idle,
    /** How loud the voice is right now, 0…1. Read every frame, so it never touches observed state. */
    level: (() -> Float)? = null,
    /** Facing right instead of left. */
    flipped: Boolean = false,
    reactsToTap: Boolean = false,
) {
    val reduceMotion = LocalReduceMotion.current
    val assets = foniaAssets()
    val layers = remember(assets) { ParrotPainter.layers(assets) }
    val born = remember { System.nanoTime() }
    // `.onAppear { moodStart = .now }` and `.onChange(of: mood) { moodStart = .now }`.
    val moodStart = remember(mood) { System.nanoTime() }
    var hopStart by remember { mutableStateOf<Long?>(null) }
    val smoothing = remember { Smoothing() }
    val haptics = LocalHapticFeedback.current
    val currentLevel by rememberUpdatedState(level)
    val currentMood by rememberUpdatedState(mood)
    val currentMoodStart by rememberUpdatedState(moodStart)
    /** Two birds on one screen should not move together. */
    val seed = if (flipped) 37.0 else 11.0

    fun compute(now: Long, dt: Double): ParrotPose {
        val voice = smoothing.next(currentLevel?.invoke() ?: 0f, dt).toDouble()
        val hop = hopStart
        return ParrotMotion.pose(
            time = if (reduceMotion) 0.0 else (now - born) / 1e9,
            mood = currentMood,
            sinceMood = if (reduceMotion) 100.0 else (now - currentMoodStart) / 1e9,
            sinceHop = if (reduceMotion || hop == null) 100.0 else (now - hop) / 1e9,
            voice = if (reduceMotion) 0.0 else voice,
            seed = seed,
        )
    }

    val pose = remember { mutableStateOf(compute(System.nanoTime(), 1.0 / 60), neverEqualPolicy()) }
    LaunchedEffect(reduceMotion, seed) {
        // Reduce Motion: the timeline is paused and the bird holds one pose.
        if (reduceMotion) {
            pose.value = compute(System.nanoTime(), 1.0 / 60)
            return@LaunchedEffect
        }
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 1.0 / 60 else (now - last) / 1e9
                last = now
                pose.value = compute(now, dt)
            }
        }
    }
    val tap = if (reactsToTap) {
        Modifier.pointerInput(Unit) {
            detectTapGestures {
                hopStart = System.nanoTime()
                // `.sensoryFeedback(.impact(weight: .light), trigger: hopStart)`.
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                FoniaSfx.play(SfxCue.FoniChirp)
            }
        }
    } else {
        Modifier
    }
    Canvas(modifier.aspectRatio(1f).clearAndSetSemantics {}.then(tap)) {
        val current = pose.value
        platform { canvas -> ParrotPainter.paint(current, canvas, size.width.toDouble(), size.height.toDouble(), flipped, layers) }
    }
}

/** Where every part of the bird is at one moment. */
data class ParrotPose(
    var head: Double = 0.0,
    var wing: Double = 0.0,
    var wingLift: Double = 0.0,
    var tail: Double = 0.0,
    var lean: Double = 0.0,
    var rise: Double = 0.0,
    var squash: Double = 0.0,
    var breath: Double = 0.0,
    var beak: Double = 0.0,
    var blink: Double = 0.0,
    var eyes: Eyes = Eyes.open,
    var sleepy: Double = 0.0,
) {
    @Suppress("EnumEntryName")
    enum class Eyes { open, calm, smile }
}

/** All motion is a pure function of time, so nothing needs storing and nothing can drift. */
object ParrotMotion {
    fun pose(time: Double, mood: FoniMood, sinceMood: Double, sinceHop: Double, voice: Double, seed: Double): ParrotPose {
        val pose = ParrotPose()
        val t = time + seed * 3.1

        // Breathing, and a slow shift of weight.
        val breathSpeed = if (mood == FoniMood.asleep) 5.2 else (if (mood == FoniMood.calm) 6.0 else 3.4)
        pose.breath = sin(t * 2 * PI / breathSpeed)
        pose.lean = sin(t * 2 * PI / 8.3) * 0.7

        when (mood) {
            FoniMood.asleep -> {
                pose.eyes = ParrotPose.Eyes.calm
                pose.head = 11 + sin(t * 2 * PI / 5.2) * 0.8
                pose.lean += 2
                pose.sleepy = 1.0
            }
            FoniMood.calm -> {
                pose.eyes = ParrotPose.Eyes.calm
                pose.head = -3 - pose.breath * 5
                pose.wing = -max(0.0, pose.breath) * 12
            }
            FoniMood.idle -> {
                pose.head = headLook(t, seed, centre = -1.0, spread = 7.0)
                pose.blink = blink(t, seed)
            }
            FoniMood.listening -> {
                // A bird turns one eye towards what it hears.
                pose.head = headLook(t, seed, centre = -9.0, spread = 3.5)
                pose.blink = blink(t, seed)
            }
            FoniMood.hello -> {
                pose.head = headLook(t, seed, centre = -3.0, spread = 5.0)
                pose.blink = blink(t, seed)
                val wave = progress(sinceMood, 1.5)
                if (wave < 1) {
                    val envelope = bump(wave)
                    pose.wing = -108 * envelope + sin(sinceMood * 14) * 12 * envelope
                    pose.wingLift = envelope
                    pose.beak = 16 * envelope
                }
            }
            FoniMood.happy -> {
                pose.eyes = if (sinceMood < 2.6) ParrotPose.Eyes.smile else ParrotPose.Eyes.open
                pose.head = if (sinceMood < 2.6) -8.0 else headLook(t, seed, centre = -3.0, spread = 5.0)
                pose.blink = if (sinceMood < 2.6) 0.0 else blink(t, seed)
                val joy = progress(sinceMood, 1.5)
                if (joy < 1) {
                    val envelope = bump(joy)
                    pose.wing = -92 * envelope + sin(sinceMood * 20) * 16 * envelope
                    pose.wingLift = envelope
                    pose.beak = 22 * envelope
                    addHop(pose, since = sinceMood.mod(0.5), height = 0.075)
                }
            }
        }

        // Small things that happen on their own.
        if (mood != FoniMood.asleep && mood != FoniMood.calm) {
            pose.tail += tailFlick(t, seed)
            pose.wing += wingSettle(t, seed)
        }

        // A tap: one hop, one flap, a chirp of the beak.
        if (sinceHop < 0.55) {
            val phase = sinceHop / 0.55
            addHop(pose, since = sinceHop, height = 0.1)
            pose.wing -= 60 * bump(phase)
            pose.wingLift = max(pose.wingLift, bump(phase))
            pose.beak = max(pose.beak, 22 * bump(phase))
            pose.eyes = ParrotPose.Eyes.smile
        }

        // The voice wins over everything the beak was doing.
        if (voice > 0.02) {
            pose.beak = max(pose.beak, min(1.0, voice * 1.5) * 34)
            pose.head -= voice * 4
            pose.rise += voice * 0.006
            pose.blink = 0.0
        }
        return pose
    }

    /** Long holds and quick moves, the way a bird looks around. The move overshoots a little. */
    private fun headLook(t: Double, seed: Double, centre: Double, spread: Double): Double {
        val period = 2.3
        val step = floor(t / period)
        val from = centre + (noise(step - 1 + seed) * 2 - 1) * spread
        val to = centre + (noise(step + seed) * 2 - 1) * spread
        val moved = min(1.0, (t - step * period) / 0.2)
        val overshoot = 1.0 + 0.18 * sin(moved * PI)
        return from + (to - from) * smooth(moved) * overshoot
    }

    /** 0 open, 1 closed. Now and then a double blink. */
    private fun blink(t: Double, seed: Double): Double {
        val period = 3.9
        val step = floor(t / period)
        val at = 0.4 + noise(step * 1.7 + seed) * (period - 1)
        var closed = bump(progress(t - step * period - at, 0.17))
        if (noise(step * 3.1 + seed) < 0.22) {
            closed = max(closed, bump(progress(t - step * period - at - 0.27, 0.17)))
        }
        return closed
    }

    private fun tailFlick(t: Double, seed: Double): Double {
        val period = 6.7
        val step = floor(t / period)
        val at = noise(step * 2.3 + seed) * (period - 1)
        return -7 * bump(progress(t - step * period - at, 0.34))
    }

    private fun wingSettle(t: Double, seed: Double): Double {
        val period = 9.1
        val step = floor(t / period)
        val at = noise(step * 1.3 + seed) * (period - 1)
        return -6 * bump(progress(t - step * period - at, 0.5))
    }

    private fun addHop(pose: ParrotPose, since: Double, height: Double) {
        val air = 0.34
        if (since < air) {
            val phase = since / air
            pose.rise += height * (4 * phase * (1 - phase))
            pose.squash -= 0.03 * sin(phase * PI)
        } else if (since < air + 0.16) {
            pose.squash += 0.06 * bump((since - air) / 0.16)
        }
    }

    /** 0 at the ends, 1 in the middle. */
    private fun bump(phase: Double): Double = if (phase > 0 && phase < 1) sin(phase * PI) else 0.0

    private fun progress(time: Double, duration: Double): Double = if (time < 0) 0.0 else min(1.0, time / duration)

    private fun smooth(x: Double): Double = x * x * (3 - 2 * x)

    private fun noise(x: Double): Double {
        val value = sin(x * 12.9898 + 4.1414) * 43758.5453
        return value - floor(value)
    }
}

/** Stacks the bird's layers. Every layer is drawn in the same square, so a pivot only has to be named once. */
object ParrotPainter {
    // Positions in the 35 × 35 grid the layers are drawn in. They match tools/art/parrot.py.
    private val neck = CGPoint(17.7, 14.4)
    private val shoulder = CGPoint(17.7, 15.6)
    private val tailRoot = CGPoint(22.5, 26.0)
    private val hinge = CGPoint(11.4, 11.7)
    private val feet = CGPoint(18.0, 33.0)

    private val names = listOf(
        "leg-far", "tail", "leg-near", "body", "wing-shadow", "wing", "head",
        "eye-open", "eye-calm", "eye-smile", "mouth", "beak-low", "beak-up",
    )

    /** Looked up and decoded once; the asset cache keeps them. */
    fun layers(assets: FoniaAssets): Map<String, Bitmap> {
        val loaded = HashMap<String, Bitmap>()
        for (name in names) assets.bitmap("parrot-$name")?.let { loaded[name] = it.asAndroidBitmap() }
        assets.bitmap("icon-zzz")?.let { loaded["zzz"] = it.asAndroidBitmap() }
        return loaded
    }

    fun paint(pose: ParrotPose, canvas: android.graphics.Canvas, width: Double, height: Double, flipped: Boolean, layers: Map<String, Bitmap>) {
        val unit = min(width, height) / 35
        val side = unit * 35
        val square = RectF(0f, 0f, side.toFloat(), side.toFloat())
        fun point(grid: CGPoint) = CGPoint(grid.x * unit, grid.y * unit)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val world = Matrix()
        world.preTranslate(((width - side) / 2).toFloat(), ((height - side) / 2).toFloat())
        if (flipped) {
            world.preTranslate(side.toFloat(), 0f)
            world.preScale(-1f, 1f)
        }

        fun draw(name: String, context: Matrix, opacity: Double = 1.0, box: RectF = square) {
            val bitmap = layers[name] ?: return
            paint.alpha = (opacity.coerceIn(0.0, 1.0) * 255).toInt()
            canvas.save()
            canvas.concat(context)
            canvas.drawBitmap(bitmap, null, box, paint)
            canvas.restore()
        }

        fun turned(base: Matrix, degrees: Double, pivot: CGPoint): Matrix {
            val next = Matrix(base)
            val at = point(pivot)
            next.preTranslate(at.x.toFloat(), at.y.toFloat())
            next.preRotate(degrees.toFloat())
            next.preTranslate(-at.x.toFloat(), -at.y.toFloat())
            return next
        }

        // Legs stay on the ground unless it hops.
        val ground = Matrix(world)
        ground.preTranslate(0f, (-pose.rise * side).toFloat())
        draw("leg-far", ground)

        val body = turned(ground, pose.lean, feet)
        val foot = point(feet)
        body.preTranslate(foot.x.toFloat(), foot.y.toFloat())
        body.preScale(
            (1 + pose.squash * 0.7 - pose.breath * 0.004).toFloat(),
            (1 - pose.squash + pose.breath * 0.009).toFloat(),
        )
        body.preTranslate(-foot.x.toFloat(), -foot.y.toFloat())

        draw("tail", turned(body, pose.tail, tailRoot))
        draw("leg-near", ground)
        draw("body", body)

        // The head, with its eye and beak.
        val head = turned(body, pose.head, neck)
        head.preTranslate(0f, (-pose.breath * 0.09 * unit).toFloat())
        draw("head", head)

        when (pose.eyes) {
            ParrotPose.Eyes.open -> {
                draw("eye-open", head, opacity = 1 - pose.blink)
                if (pose.blink > 0) draw("eye-calm", head, opacity = pose.blink)
            }
            ParrotPose.Eyes.calm -> draw("eye-calm", head)
            ParrotPose.Eyes.smile -> draw("eye-smile", head)
        }

        val opening = pose.beak
        if (opening > 0.5) {
            val inside = Matrix(head)
            inside.preTranslate(0f, (opening * 0.018 * unit).toFloat())
            draw("mouth", inside, opacity = min(1.0, opening / 8))
        }
        val lower = turned(head, -opening, hinge)
        lower.preTranslate(0f, (opening * 0.03 * unit).toFloat())
        draw("beak-low", lower)
        draw("beak-up", turned(head, opening * 0.4, hinge))

        // The wing lies over the neck. Its soft shadow fades as it lifts away from the body.
        val lifted = turned(body, pose.wing, shoulder)
        val wing = Matrix(lifted)
        wing.preTranslate((pose.wingLift * 1.6 * unit).toFloat(), (pose.wingLift * 1.4 * unit).toFloat())
        val shadow = max(0.0, 1 - abs(pose.wing) / 14)
        if (shadow > 0) draw("wing-shadow", lifted, opacity = shadow)
        draw("wing", wing)

        if (pose.sleepy > 0) {
            val drift = (pose.breath + 1) * 0.5
            val box = RectF((2 * unit).toFloat(), ((1 - drift) * unit).toFloat(), (9 * unit).toFloat(), ((1 - drift) * unit + 7 * unit).toFloat())
            draw("zzz", world, opacity = 0.55 + 0.45 * drift, box = box)
        }
    }
}

/**
 * Circles around Foni that grow with the voice. Their colour can follow what the microphone hears, so a
 * child sees an S come out blue and an Ș come out pink without reading anything.
 */
@Composable
fun VoiceRings(
    color: Color,
    isLive: Boolean,
    level: () -> Float,
    modifier: Modifier = Modifier,
    /** The colour of what is heard right now. Null keeps the rings in [color]. */
    heard: (() -> Color?)? = null,
) {
    val reduceMotion = LocalReduceMotion.current
    val smoothing = remember { Smoothing() }
    var voice by remember { mutableFloatStateOf(0f) }
    var time by remember { mutableDoubleStateOf(System.nanoTime() / 1e9) }
    val tint = remember { Animatable(heard?.invoke() ?: color) }
    var tintTarget by remember { mutableStateOf(tint.value) }
    val scope = rememberCoroutineScope()
    val currentLevel by rememberUpdatedState(level)
    val currentHeard by rememberUpdatedState(heard)
    val currentColor by rememberUpdatedState(color)

    fun retint() {
        val next = currentHeard?.invoke() ?: currentColor
        if (next != tintTarget) {
            tintTarget = next
            // `.animation(.easeOut(duration: 0.2), value: tint)`.
            scope.launch { tint.animateTo(next, easeOut(0.2)) }
        }
    }

    LaunchedEffect(isLive) {
        if (!isLive) {
            voice = smoothing.next(0f, 1.0 / 60)
            retint()
            return@LaunchedEffect
        }
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 1.0 / 60 else (now - last) / 1e9
                last = now
                time = now / 1e9
                voice = smoothing.next(currentLevel(), dt)
                retint()
            }
        }
    }
    Canvas(modifier.aspectRatio(1f).clearAndSetSemantics {}) {
        val d = min(size.width, size.height)
        val centre = Offset(size.width / 2, size.height / 2)
        val v = voice
        val shown = tint.value
        for (ring in 0 until 3) {
            val index = ring.toFloat()
            val drift = if (reduceMotion) 0f else (sin(time * 1.2 - ring * 0.7) * 0.012).toFloat()
            val scale = 0.6f + index * 0.17f + v * (0.1f + index * 0.1f) + drift
            drawCircle(shown.opacity(0.2 - 0.05 * ring), radius = d / 2 * scale, center = centre)
        }
    }
}

/**
 * Eases the voice level in quickly and out slowly, so the mascot swells with speech and settles softly.
 * iOS steps it once per frame at 60 Hz (0.35 up, 0.08 down); here each step is scaled to the time that
 * passed, so a 90 or 120 Hz screen moves it exactly as fast as an iPhone does.
 */
internal class Smoothing {
    private var value = 0f

    fun next(target: Float, dt: Double = 1.0 / 60): Float {
        val clipped = min(1f, max(0f, target))
        val perFrame = if (clipped > value) 0.35 else 0.08
        val k = 1 - (1 - perFrame).pow(dt * 60)
        value += ((clipped - value) * k).toFloat()
        return value
    }
}
