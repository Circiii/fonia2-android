package tech.fonia.designsystem.foni

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import tech.fonia.designsystem.art.FoniArt
import tech.fonia.designsystem.art.FoniaArtAssets
import tech.fonia.designsystem.theme.FoniaTheme
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Foni, the real parrot of the old app (the same 13 layers), alive: he breathes, blinks, looks around, flicks his tail,
 * and every [mood] has its own body language (see [FoniMood]); moods blend through a spring, so he never snaps.
 *
 * - [voiceLevel] (0–1) is read in the draw phase every frame: no recomposition per frame. Talking (and any other mood
 *   except Listening / Sleepy) opens the beak with it; Listening leans in and widens the eyes instead.
 * - [lookAt] is a point in **root** coordinates (`positionInRoot()` / `boundsInRoot()` space): the eyes follow it, the
 *   head tilts towards it and he turns around (a squish and a hop) when it is clearly on his other side. Null = he faces
 *   left, as drawn, and looks about on his own.
 * - Reduced motion: one still pose per mood, the beak follows the voice in 100 ms steps.
 * - TalkBack reads [FoniMood.description].
 *
 * The art decodes off the main thread the first time; he pops in when it is ready.
 */
@Composable
fun FoniMascot(
    mood: FoniMood,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    voiceLevel: () -> Float = { 0f },
    lookAt: Offset? = null,
) {
    val context = LocalContext.current
    val reduce = FoniaTheme.reduceMotion
    val px = with(LocalDensity.current) { size.roundToPx() }
    val art by produceState<FoniArt?>(FoniaArtAssets.foniCached(px), px) {
        if (value == null) value = FoniaArtAssets.foni(context, px)
    }
    val rig = remember { FoniRig() }
    val painter = remember { FoniPainter() }
    val frame = remember { mutableLongStateOf(0L) }
    val holder = remember { Holder() }
    val voice by rememberUpdatedState(voiceLevel)

    SideEffect {
        rig.setMood(mood)
        rig.reduceMotion = reduce
        holder.lookAt = lookAt
    }

    LaunchedEffect(reduce) {
        while (isActive) {
            if (reduce) {
                delay(100)
                frame.longValue = System.nanoTime()
            } else {
                withFrameNanos { frame.longValue = it }
            }
        }
    }

    Box(
        modifier
            .size(size)
            .semantics { contentDescription = mood.description }
            .onGloballyPositioned { holder.coords = it }
            .drawBehind {
                val now = frame.longValue // the only per-frame state read: a new frame redraws, nothing recomposes
                val a = art ?: return@drawBehind
                if (!holder.appeared) { holder.appeared = true; rig.appear() }
                val dt = if (holder.last == 0L) 0f else ((now - holder.last) / 1e9f)
                holder.last = now

                // where to look, in the bird's terms
                var lx = 0f; var ly = 0f; var side = Float.NaN
                val target = holder.lookAt
                val coords = holder.coords
                if (target != null && coords != null && coords.isAttached) {
                    val origin = coords.positionInRoot()
                    val local = target - origin
                    val s = min(this.size.width, this.size.height)
                    val u = s / 35f
                    val facingLeft = rig.pose.facing >= 0f
                    val eyeX = (this.size.width - s) / 2f + (if (facingLeft) 13.4f * u else s - 13.4f * u)
                    val eyeY = (this.size.height - s) / 2f + 11f * u
                    val dx = local.x - eyeX
                    val dy = local.y - eyeY
                    val d = sqrt(dx * dx + dy * dy)
                    if (d > 1f) {
                        val reach = (d / (s * 0.6f)).coerceIn(0.25f, 1f)
                        lx = dx / d * reach; ly = dy / d * reach
                    }
                    side = (local.x - this.size.width / 2f) / s
                }
                rig.update(dt, voice(), lx, ly, side)
                drawIntoCanvas { painter.draw(it.nativeCanvas, rig.pose, a, this.size.width, this.size.height) }
            },
    )
}

private class Holder {
    var coords: LayoutCoordinates? = null
    var lookAt: Offset? = null
    var last = 0L
    var appeared = false
}
