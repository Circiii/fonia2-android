package tech.fonia.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * The least area a finger gets. A child needs more room than an adult, so a child's screens ask for 56 and
 * everything else keeps the platform's 48. As on iOS (`TouchFloor`).
 */
object TouchFloor {
    val child: Dp = 56.dp
    val adult: Dp = 48.dp
}

/** The touch floor of this screen. Set once, where the profile is known, with [ProvideTouchFloor]. */
val LocalTouchFloor = staticCompositionLocalOf { TouchFloor.adult }

/**
 * Gives [content] a touch floor. Anything that takes a tap and is drawn smaller takes touches out to the floor,
 * without moving anything: Compose widens a small target's touch bounds to the view configuration's least touch
 * size. A dialog has a view configuration of its own, so a sheet passes the floor on again.
 */
@Composable
fun ProvideTouchFloor(floor: Dp, content: @Composable () -> Unit) {
    val base = LocalViewConfiguration.current
    val configuration = remember(base, floor) { FloorViewConfiguration(base, floor) }
    CompositionLocalProvider(
        LocalTouchFloor provides floor,
        LocalViewConfiguration provides configuration,
        content = content,
    )
}

/**
 * The least size a button is drawn at here: the floor on a child's screen. An adult's buttons keep their own sizes;
 * Compose already takes their touches out to 48.
 */
internal val drawnFloor: Dp
    @Composable get() = LocalTouchFloor.current.let { if (it > TouchFloor.adult) it else 0.dp }

private class FloorViewConfiguration(base: ViewConfiguration, floor: Dp) : ViewConfiguration by base {
    override val minimumTouchTargetSize: DpSize = DpSize(floor, floor)
}
