package tech.fonia.designsystem.motion

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaMotion
import kotlin.math.min

// ------------------------------------------------------------------------------------------------ press and bounce

/**
 * The sticker press: the thing sinks to [pressedScale] quickly while pressed and springs back past 1 on release (the
 * `bounce` token), so a tap feels like pressing a sticker. Read in the layer phase only. Reduced motion: no scale, a
 * slight dim while pressed. Pass the same [interactionSource] as the clickable.
 */
fun Modifier.pressBounce(interactionSource: InteractionSource, pressedScale: Float = 0.94f, enabled: Boolean = true): Modifier = composed {
    val reduce = FoniaTheme.reduceMotion
    val scale = remember { Animatable(1f) }
    val dim = remember { Animatable(1f) }
    LaunchedEffect(interactionSource, enabled, reduce) {
        interactionSource.interactions.collect { i ->
            if (!enabled) return@collect
            val down = i is PressInteraction.Press
            val up = i is PressInteraction.Release || i is PressInteraction.Cancel
            if (!down && !up) return@collect
            launch {
                if (reduce) dim.animateTo(if (down) 0.85f else 1f, tween(FoniaMotion.QUICK_MS))
                else if (down) scale.animateTo(pressedScale, spring(1f, 1400f))
                else scale.animateTo(1f, spring(FoniaMotion.bounce.dampingRatio * 0.75f, FoniaMotion.bounce.stiffness))
            }
        }
    }
    graphicsLayer {
        scaleX = scale.value; scaleY = scale.value
        alpha = dim.value
    }
}

/**
 * Staggered entrance for the [index]-th item of a list or grid: it rises 20 dp, grows from 0.92 and fades in on the
 * `bounce` spring, [FoniaMotion.STAGGER_MS] after the previous one (a whole run never waits more than 320 ms). Replays
 * when [key] changes. Reduced motion: a 150 ms fade, no movement.
 */
fun Modifier.staggeredEntrance(index: Int, key: Any? = Unit): Modifier = composed {
    val reduce = FoniaTheme.reduceMotion
    val rise = with(LocalDensity.current) { 20.dp.toPx() }
    val p = remember(key) { Animatable(0f) }
    LaunchedEffect(key, reduce) {
        delay(min(index * FoniaMotion.STAGGER_MS, FoniaMotion.STAGGER_MAX_MS).toLong())
        if (reduce) p.animateTo(1f, tween(FoniaMotion.REDUCED_FADE_MS))
        else p.animateTo(1f, spring(FoniaMotion.bounce.dampingRatio, FoniaMotion.bounce.stiffness * 0.6f))
    }
    graphicsLayer {
        val v = p.value
        alpha = (v * 1.5f).coerceIn(0f, 1f)
        if (!reduce) {
            translationY = (1f - v) * rise
            val s = 0.92f + 0.08f * v
            scaleX = s; scaleY = s
        }
    }
}

// ------------------------------------------------------------------------------------------------ haptics

/** False turns every [FoniaHaptics] call into nothing (the app's "vibrații" setting). */
val LocalFoniaHapticsEnabled = staticCompositionLocalOf { true }

/**
 * Fonia's haptic vocabulary, one call per meaning. Light by design (children, cheap phones): taps are the system key
 * click, a success is a soft double tick, a POP a single firm click. Never a "wrong" buzz — Fonia has no failure.
 * Uses `View.performHapticFeedback` (no permission) and, for the composed effects on API 30+, the vibrator
 * (`VIBRATE`, declared by this library).
 */
@Stable
class FoniaHaptics internal constructor(private val view: View, private val enabled: Boolean) {
    private val vibrator: Vibrator? by lazy { vibratorOf(view.context) }

    /** A button or a node pressed. */
    fun tap() { if (enabled) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }

    /** A choice moved (a slider step, a syllable passing). */
    fun tick() { if (enabled) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }

    /** A correct answer, a level done: two soft ticks. */
    fun success() {
        if (!enabled) return
        if (!compose(VibrationEffect.Composition.PRIMITIVE_TICK, VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f, 0.8f, 70)) {
            view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    /** The letter POPs, a celebration bursts. */
    fun pop() {
        if (!enabled) return
        if (!compose(VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.9f, 0.4f, 0)) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun compose(a: Int, b: Int, sa: Float, sb: Float, delayMs: Int): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val v = vibrator ?: return false
        return runCatching {
            if (!v.areAllPrimitivesSupported(a, b)) return false
            v.vibrate(VibrationEffect.startComposition().addPrimitive(a, sa).addPrimitive(b, sb, delayMs).compose())
            true
        }.getOrDefault(false)
    }

    private fun vibratorOf(context: Context): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
    }.getOrNull()?.takeIf { it.hasVibrator() }
}

/** The haptics of this screen (respects [LocalFoniaHapticsEnabled]). */
@Composable
fun rememberFoniaHaptics(): FoniaHaptics {
    val view = LocalView.current
    val enabled = LocalFoniaHapticsEnabled.current
    return remember(view, enabled) { FoniaHaptics(view, enabled) }
}
