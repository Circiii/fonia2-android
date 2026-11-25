package tech.fonia.features.session

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.Palette

/**
 * The white sheet at the bottom of a spoken task. While listening it holds the live meters and whose turn it is; the
 * result card is the same sheet with the outcome. The meters sit right above the bottom row in both, so the Ș–S line
 * keeps its place and its look from one to the other.
 */
@Composable
fun DockSheet(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Palette.sheet, ContinuousRoundedRectangle(28.dp))
            .padding(DockMetrics.INSET),
        verticalArrangement = Arrangement.spacedBy(DockMetrics.GAP),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

object DockMetrics {
    val INSET: Dp = 16.dp
    val GAP: Dp = 12.dp

    /** The bottom row: the turn line while listening, the main action on the result. One height for both. */
    fun rowHeight(isChild: Boolean): Dp = if (isChild) 64.dp else 56.dp

    /** Anything a child taps; the least an adult-only control may be. */
    fun tapHeight(isChild: Boolean): Dp = if (isChild) 56.dp else 44.dp
}

/**
 * How things come and go at the bottom of a lesson. What leaves goes first and quickly, what arrives comes after it,
 * so the two never share pixels while either can be read. With Reduce Motion, plain fades and no travel.
 */
object DockMotion {
    /** `.timingCurve(0.23, 1, 0.32, 1)`: quick out of the gate, a long soft landing, no bounce. */
    private val settle = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

    private fun <T> inSpec(after: Int, duration: Int) = tween<T>(durationMillis = duration, delayMillis = after, easing = settle)

    /** One label for another in the same place: the turn line, its icon. */
    fun swap(reduceMotion: Boolean, density: Density): ContentTransform {
        val rise = with(density) { 6.dp.roundToPx() }
        val drop = with(density) { 4.dp.roundToPx() }
        val enter: EnterTransition = fadeIn(inSpec(90, 180)) +
            (if (reduceMotion) EnterTransition.None else slideInVertically(inSpec(90, 180)) { rise })
        val exit: ExitTransition = fadeOut(tween(90, easing = LinearOutSlowInEasing)) +
            (if (reduceMotion) ExitTransition.None else slideOutVertically(tween(90, easing = LinearOutSlowInEasing)) { -drop })
        return ContentTransform(enter, exit, sizeTransform = SizeTransform(clip = false))
    }

    /**
     * The result card coming in over the turn line as one piece, after the line has gone; and the line coming back
     * after the card has gone.
     */
    fun card(toCard: Boolean, reduceMotion: Boolean, density: Density): ContentTransform {
        val out = fadeOut(tween(120, easing = FastOutLinearInEasing))
        if (!toCard) return ContentTransform(fadeIn(inSpec(120, 220)), out, sizeTransform = SizeTransform(clip = false))
        val lift = with(density) { 18.dp.roundToPx() }
        val enter = fadeIn(inSpec(120, 300)) + (if (reduceMotion) EnterTransition.None else slideInVertically(inSpec(120, 300)) { lift })
        return ContentTransform(enter, out, sizeTransform = SizeTransform(clip = false))
    }

    /**
     * The page that leaves fades out quickly; the next one comes in after it, with a short slide. The two never stand
     * on top of each other while either can be read. With Reduce Motion on, plain fades.
     */
    fun page(reduceMotion: Boolean, density: Density): ContentTransform {
        val shift = with(density) { 22.dp.roundToPx() }
        val enter = fadeIn(inSpec(140, 280)) + (if (reduceMotion) EnterTransition.None else slideInHorizontally(inSpec(140, 280)) { shift })
        return ContentTransform(enter, fadeOut(tween(140, easing = FastOutLinearInEasing)), sizeTransform = SizeTransform(clip = false))
    }
}
