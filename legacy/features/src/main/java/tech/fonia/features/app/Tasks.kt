package tech.fonia.features.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.fonia.design.LocalReduceMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * SwiftUI's `.task` and `.task(id:)`: work that starts when the view appears (or its [keys] change) and is cancelled
 * when it goes. It runs on the main thread's dispatcher, as a `@MainActor` task does. In the app that is where a
 * `LaunchedEffect` runs anyway; under a Compose test clock it keeps the pauses of a flow in real time, as the audio
 * they wait for is.
 */
@Composable
fun ViewTask(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
    LaunchedEffect(*keys) {
        withContext(Dispatchers.Main.immediate) { block() }
    }
}

/**
 * Android only, for the UI tests (`foniaReduceMotion`): the voice meters stand still. A Compose test waits for the
 * screen to be idle, and a meter that follows the microphone never is. Reduce Motion itself leaves the meters alone,
 * as on iOS, where a level that follows the voice is information, not decoration.
 */
val LocalStillMeters = compositionLocalOf { false }

/** `ProgressView()`: the system's spinning wheel, eight grey spokes. */
@Composable
fun ActivityIndicator(modifier: Modifier = Modifier, size: Dp = 20.dp, color: Color = SystemColors.systemGray) {
    val reduceMotion = LocalReduceMotion.current
    val turn = rememberInfiniteTransition(label = "spinner")
    val phase by turn.animateFloat(0f, 8f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart), label = "spokes")
    val strokeWidth = with(LocalDensity.current) { (size * 0.095f).toPx() }
    Canvas(modifier.size(size).semantics { contentDescription = "În curs" }) {
        // The wheel steps from spoke to spoke, the head spoke darkest, as UIActivityIndicatorView does.
        val head = if (reduceMotion) 0 else floor(phase).toInt()
        val radius = this.size.minDimension / 2
        for (spoke in 0 until 8) {
            val age = ((head - spoke) % 8 + 8) % 8
            val alpha = 1f - age * 0.11f
            val angle = spoke * PI / 4 - PI / 2
            val inner = radius * 0.5f
            val outer = radius - strokeWidth / 2
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            drawLine(
                color.copy(alpha = color.alpha * alpha),
                start = Offset(center.x + dx * inner, center.y + dy * inner),
                end = Offset(center.x + dx * outer, center.y + dy * outer),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}
