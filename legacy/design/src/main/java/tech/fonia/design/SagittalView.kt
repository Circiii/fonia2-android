package tech.fonia.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay

internal object SagittalInk {
    val lipUpper = Color.hex(0xD9566F)
    val lipLower = Color.hex(0xF28C9A)
    val inside = Color.hex(0x571C2E)
    val tongue = Color.hex(0xFA9CA8)
    val tongueLine = Color.hex(0xEE7C8D)
    val tooth = Color.hex(0xFFFFFF)
    val toothLine = Color.hex(0xE8E3D6)
    val tissue = Color.hex(0xFCDDC4)
    val tissueLine = Color.hex(0xF2C4A2)
    val bone = Color.hex(0xFFF4E4)
    val boneLine = Color.hex(0xF2DFC4)
}

@Suppress("EnumEntryName")
enum class SagittalAir { cold, warm }

/**
 * One sound seen from the side, in the drawing's own coordinates: facing right, y growing downward.
 * [jaw] runs 0…1, [round] runs −1 (spread) … 1 (forward), [lips] is the gap in drawing units, [velum] is 1
 * raised and 0 lowered for breathing through the nose.
 */
data class SagittalPose(
    val jaw: Double = 0.1,
    val round: Double = 0.0,
    val lips: Double = 3.0,
    val velum: Double = 1.0,
    val tongue: Tongue = Tongue.relaxed,
    val air: SagittalAir? = null,
    val voiced: Boolean = false,
    val vibrate: Boolean = false,
    val lipBite: Boolean = false,
    /** The tongue closes the channel, so the air waits behind it. */
    val stop: Boolean = false,
    /** Name of the pose this one glides into and back from, as in Ț into S. */
    val release: String? = null,
    /** Where the air leaves the teeth, when not between the incisal edges. */
    val exit: CGPoint? = null,
    /** The air goes round the sides of the tongue, not over the middle. */
    val lateral: Boolean = false,
) {
    /** The tongue's profile, from the tip back to the root, plus the underside just behind the tip. */
    data class Tongue(
        val tip: CGPoint,
        val blade: CGPoint,
        val front: CGPoint,
        val dorsum: CGPoint,
        val back: CGPoint,
        val root: CGPoint,
        val under: CGPoint,
    ) {
        companion object {
            /** Low in the mouth, tip behind the lower incisors, clear air above. */
            val relaxed = Tongue(CGPoint(198.5, 125.5), CGPoint(187, 114), CGPoint(166, 105), CGPoint(134, 99), CGPoint(103, 104), CGPoint(80, 124), CGPoint(190, 134))

            // Tip down behind the lower incisors; only the blade comes close to the ridge, leaving a narrow groove.
            internal val groove = Tongue(CGPoint(200.5, 127.5), CGPoint(190, 97.5), CGPoint(172, 90), CGPoint(140, 88.5), CGPoint(105, 96), CGPoint(79, 123), CGPoint(189, 137))

            // Blade raised further back than for S, with a wider channel and a hollow under the tip.
            internal val cup = Tongue(CGPoint(195, 107), CGPoint(182, 93.5), CGPoint(163, 89), CGPoint(134, 89), CGPoint(103, 97), CGPoint(78, 123), CGPoint(186, 122))

            // Tip and blade pressed from the ridge back along the post-alveolar slope.
            internal val press = Tongue(CGPoint(196.5, 100.8), CGPoint(184, 87), CGPoint(164, 87), CGPoint(134, 89), CGPoint(103, 97), CGPoint(78, 123), CGPoint(189, 116))
        }
    }

    internal val released: SagittalPose get() = release?.let(::named) ?: this

    companion object {
        val rest = SagittalPose(jaw = 0.04, lips = 0.0, velum = 0.0)
        val s = SagittalPose(jaw = 0.08, round = -0.4, lips = 5.0, tongue = Tongue.groove, air = SagittalAir.cold)
        val z = SagittalPose(jaw = 0.08, round = -0.4, lips = 5.0, tongue = Tongue.groove, air = SagittalAir.cold, voiced = true)
        val ts = SagittalPose(
            jaw = 0.08,
            round = -0.4,
            lips = 5.0,
            tongue = Tongue(CGPoint(198.6, 103.4), CGPoint(190, 99), CGPoint(171, 92), CGPoint(140, 89), CGPoint(105, 96), CGPoint(79, 123), CGPoint(192, 116)),
            air = SagittalAir.cold,
            stop = true,
            release = "s",
        )
        val sh = SagittalPose(jaw = 0.14, round = 1.0, lips = 7.0, tongue = Tongue.cup, air = SagittalAir.warm)
        val zh = SagittalPose(jaw = 0.14, round = 1.0, lips = 7.0, tongue = Tongue.cup, air = SagittalAir.warm, voiced = true)
        val ce = SagittalPose(jaw = 0.12, round = 0.7, lips = 6.0, tongue = Tongue.press, air = SagittalAir.warm, stop = true, release = "ș")
        val ge = SagittalPose(jaw = 0.12, round = 0.7, lips = 6.0, tongue = Tongue.press, air = SagittalAir.warm, voiced = true, stop = true, release = "j")
        val r = SagittalPose(
            jaw = 0.3,
            lips = 9.0,
            tongue = Tongue(CGPoint(195, 100.8), CGPoint(183, 111), CGPoint(161, 112), CGPoint(130, 104), CGPoint(101, 100), CGPoint(79, 125), CGPoint(187, 120)),
            air = SagittalAir.cold,
            voiced = true,
            vibrate = true,
            exit = CGPoint(204, 118),
        )
        val l = SagittalPose(
            jaw = 0.3,
            lips = 9.0,
            tongue = Tongue(CGPoint(198, 102.6), CGPoint(187, 102), CGPoint(166, 106.5), CGPoint(134, 102), CGPoint(103, 103), CGPoint(79, 125), CGPoint(191, 117.5)),
            voiced = true,
            exit = CGPoint(205, 121),
            lateral = true,
        )

        // Tip sealed on the ridge with the body held higher than for L, so the air has nowhere to go until it lets go.
        val t = SagittalPose(
            jaw = 0.14,
            lips = 6.0,
            tongue = Tongue(CGPoint(198.4, 103.2), CGPoint(189, 99.5), CGPoint(170, 95), CGPoint(140, 92), CGPoint(105, 98), CGPoint(79, 124), CGPoint(191, 116.5)),
            air = SagittalAir.cold,
            stop = true,
        )

        // Contact only where the back of the tongue meets the soft palate; the front stays low.
        val k = SagittalPose(
            jaw = 0.24,
            lips = 8.0,
            tongue = Tongue(CGPoint(198, 136), CGPoint(185, 123), CGPoint(158, 110), CGPoint(124, 93), CGPoint(92, 80.5), CGPoint(74, 117), CGPoint(190, 143)),
        )
        val g = SagittalPose(jaw = 0.24, lips = 8.0, tongue = k.tongue, voiced = true)
        val th = SagittalPose(
            jaw = 0.12,
            lips = 8.0,
            tongue = Tongue(CGPoint(216, 125.5), CGPoint(203, 120), CGPoint(178, 109), CGPoint(142, 99), CGPoint(105, 101), CGPoint(79, 125), CGPoint(205, 131)),
            air = SagittalAir.cold,
            exit = CGPoint(214, 122),
        )
        val f = SagittalPose(jaw = 0.08, lips = 0.0, air = SagittalAir.cold, lipBite = true, exit = CGPoint(214, 125.5))
        val v = SagittalPose(jaw = 0.08, lips = 0.0, air = SagittalAir.cold, voiced = true, lipBite = true, exit = CGPoint(214, 125.5))

        /** The pose for a viseme name such as "ș" or "ce"; anything without a side view falls back to [rest]. */
        fun named(name: String): SagittalPose = when (name.lowercase()) {
            "s" -> s
            "z" -> z
            "ț" -> ts
            "ș" -> sh
            "j" -> zh
            "ce" -> ce
            "ge" -> ge
            "r" -> r
            "l" -> l
            "t" -> t
            "k", "c" -> k
            "g" -> g
            "th" -> th
            "f" -> f
            "v" -> v
            else -> rest
        }
    }
}

/**
 * The mouth in profile, the classic speech-therapy drawing in flat cut paper: nose and nasal cavity, hard
 * palate with the alveolar ridge, soft palate and uvula, pharynx and larynx, incisors, lips, jaw and one
 * smooth tongue. Each sound poses the tongue, jaw, lips and soft palate, and shows the air and the voice.
 * Labels belong to the caller.
 *
 * A change of pose moves the whole 25-channel vector with one spring, `spring(duration: 0.34, bounce: 0.12)`
 * (`easeOut(0.15)` with Reduce Motion). Ț, CE and GE alternate held (700 ms) and released (1100 ms) while live.
 */
@Composable
fun SagittalView(pose: SagittalPose, modifier: Modifier = Modifier, isLive: Boolean = true) {
    val reduceMotion = LocalReduceMotion.current
    var releasedFrom by remember { mutableStateOf<SagittalPose?>(null) }
    val shown = if (releasedFrom == pose) pose.released else pose
    val target = remember(shown) { SagittalVector.of(shown) }
    val vector = remember { AnimatedVector(target) }
    var lastPose by remember { mutableStateOf(pose) }
    LaunchedEffect(target) {
        // `.animation(…, value: pose)` follows Reduce Motion; `.animation(spring, value: releasedFrom)` doesn't.
        val byPose = lastPose != pose
        lastPose = pose
        val motion = if (byPose && reduceMotion) VectorMotion.Tween(0.15, EaseOutCurve) else VectorMotion.Spring(0.34, 0.12)
        vector.animateTo(target, motion)
    }
    // `.task(id: pose.release != nil && isLive && !reduceMotion ? pose : nil)`.
    val loop = if (pose.release != null && isLive && !reduceMotion) pose else null
    LaunchedEffect(loop) {
        releasedFrom = null
        if (loop == null) return@LaunchedEffect
        val held = loop
        var open = false
        while (true) {
            delay(if (open) 1100L else 700L)
            open = !open
            releasedFrom = if (open) held else null
        }
    }
    val targetMoves = SagittalVector(target).movesOnItsOwn
    val clockOn = isLive && !reduceMotion && (targetMoves || vector.isRunning)
    val clock = rememberFrameClock(clockOn, 1.0 / 60)
    val stop = shown.stop
    val lateral = shown.lateral
    Canvas(modifier.aspectRatio((SagittalPainter.frame.width / SagittalPainter.frame.height).toFloat()).clearAndSetSemantics {}) {
        val current = SagittalVector(vector.value)
        val ticking = clockOn && current.movesOnItsOwn
        val painter = SagittalPainter(
            pose = current,
            stop = stop,
            lateral = lateral,
            // `Palette.cold.mix(with: Palette.warm, by:)` mixes perceptually; Compose's `lerp` mixes in Oklab.
            airColor = lerp(Palette.cold, Palette.warm, max(0.0, min(1.0, current.warmth)).toFloat()),
            voiceColor = Palette.warm,
            casing = Palette.sheet,
            clock = if (ticking) clock.value / 1e9 else null,
        )
        platform { canvas -> painter.draw(canvas, size.width.toDouble(), size.height.toDouble()) }
    }
}
