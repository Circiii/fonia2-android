package tech.fonia.app.holdgames

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.isActive
import tech.fonia.app.ui.SceneSlot
import tech.fonia.designsystem.letter.LetterDrive
import tech.fonia.designsystem.letter.LiveLetter
import tech.fonia.designsystem.letter.LiveLetterState
import tech.fonia.designsystem.theme.FoniaTheme

/**
 * The `Hold` stage: the sound's backdrop, the game's scene and the living letter riding in it. The scene is drawn in the
 * draw phase and the letter is placed in the layer phase, both from [tick] (the frame time the lesson's frame loop
 * writes): nothing recomposes per frame.
 */
@Composable
fun HoldStage(
    game: HoldGame,
    letter: String,
    state: LiveLetterState,
    drive: LetterDrive,
    tick: MutableLongState,
    description: String,
    modifier: Modifier = Modifier,
) {
    val palette = GamePalette(FoniaTheme.colors)
    BoxWithConstraints(modifier.semantics { contentDescription = description }) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val base = minOf(maxWidth, maxHeight) * 0.62f
        val basePx = with(density) { base.toPx() }
        SceneSlot("hold_${game.sound.name.lowercase()}", Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().drawBehind { tick.longValue; with(game) { scene(palette) } })
        Box(
            Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    tick.longValue
                    val pose = game.letter
                    translationX = pose.x * wPx - wPx / 2f
                    translationY = pose.y * hPx - hPx / 2f
                    scaleX = pose.scale; scaleY = pose.scale
                    rotationZ = pose.rotation
                    // keep the letter's own sticker size readable: the base letter is 62 % of the stage
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                    if (basePx <= 0f) alpha = 0f
                },
        ) {
            LiveLetter(letter, state, drive = drive, height = base, contentDescription = letter)
        }
    }
}

/** The character alone, gently moving: the house's header and the `Hold` card's icon. Self-animating (draw phase only). */
@Composable
fun HoldCharacter(game: HoldGame, modifier: Modifier = Modifier) {
    val palette = GamePalette(FoniaTheme.colors)
    val reduce = FoniaTheme.reduceMotion
    val t = remember { mutableLongStateOf(0L) }
    if (!reduce) LaunchedEffect(game) {
        val start = withFrameNanos { it }
        while (isActive) withFrameNanos { t.longValue = it - start }
    }
    Box(modifier.drawBehind { val s = t.longValue / 1e9f; with(game) { icon(palette, s) } })
}
