package tech.fonia.designsystem.art

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import tech.fonia.designsystem.letter.StickerText
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaMotion
import tech.fonia.designsystem.tokens.FoniaSticker
import kotlin.math.min

/**
 * The picture of a word (from `images/pictures`, the Fluent-emoji set of the old app), square, fitted in the space it
 * is given. [key] is a Romanian word (`"măr"`, looked up in `pictures/words.json`, built from the content), an emoji
 * (`"🍎"`) or a code (`"1f34e"`). It pops in with the bounce spring when decoded. With no picture it shows a sticker
 * tile with the word's first letter, so a missing picture never leaves a hole.
 */
@Composable
fun WordPicture(key: String, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val context = LocalContext.current
    val reduce = FoniaTheme.reduceMotion
    var bitmap by remember(key) { mutableStateOf<Bitmap?>(null) }
    var missing by remember(key) { mutableStateOf(false) }
    val appear = remember(key) { Animatable(0f) }

    BoxWithConstraints(
        modifier.aspectRatio(1f).semantics { this.contentDescription = contentDescription ?: key },
        contentAlignment = Alignment.Center,
    ) {
        val px = with(LocalDensity.current) { min(maxWidth.toPx(), maxHeight.toPx()).toInt().coerceAtLeast(64) }
        LaunchedEffect(key, px) {
            val code = FoniaArtAssets.pictureCode(context, key)
            val b = code?.let { FoniaArtAssets.picture(context, it, px) }
            if (b == null) missing = true else bitmap = b
            if (reduce) appear.animateTo(1f, tween(FoniaMotion.REDUCED_FADE_MS))
            else appear.animateTo(1f, spring(FoniaMotion.bounce.dampingRatio, FoniaMotion.bounce.stiffness))
        }
        val b = bitmap
        when {
            b != null -> {
                val image = remember(b) { b.asImageBitmap() }
                Box(
                    Modifier.fillMaxSize()
                        .graphicsLayer {
                            val v = appear.value
                            alpha = v.coerceIn(0f, 1f)
                            val s = if (reduce) 1f else 0.82f + 0.18f * v
                            scaleX = s; scaleY = s
                        }
                        .drawBehind {
                            val side = min(size.width, size.height)
                            drawImage(
                                image,
                                srcOffset = IntOffset.Zero,
                                srcSize = IntSize(image.width, image.height),
                                dstOffset = IntOffset(((size.width - side) / 2).toInt(), ((size.height - side) / 2).toInt()),
                                dstSize = IntSize(side.toInt(), side.toInt()),
                            )
                        },
                )
            }
            missing -> LetterTile(key, { appear.value }, reduce)
            else -> Unit
        }
    }
}

/** The fallback: a Sun sticker tile with the first letter as a Voice sticker. */
@Composable
private fun LetterTile(word: String, progress: () -> Float, reduce: Boolean) {
    val c = FoniaTheme.colors
    val letter = word.trim().firstOrNull { it.isLetter() }?.uppercase() ?: "?"
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .graphicsLayer {
                val v = progress()
                alpha = v.coerceIn(0f, 1f)
                val s = if (reduce) 1f else 0.82f + 0.18f * v
                scaleX = s; scaleY = s
            }
            .drawBehind {
                val inset = size.minDimension * 0.06f
                val r = CornerRadius(size.minDimension * 0.22f)
                val tile = Size(size.width - inset * 2, size.height - inset * 2)
                drawRoundRect(c.stickerShadow, Offset(inset, inset + size.minDimension * 0.03f), tile, r)
                drawRoundRect(FoniaSticker.Sun.face, Offset(inset, inset), tile, r)
                val w = size.minDimension * 0.035f
                drawRoundRect(FoniaSticker.Sun.edge, Offset(inset + w / 2, inset + w / 2), Size(tile.width - w, tile.height - w), CornerRadius(r.x - w / 2), style = Stroke(w))
            },
        contentAlignment = Alignment.Center,
    ) {
        val sp = with(LocalDensity.current) { (min(maxWidth.toPx(), maxHeight.toPx()) * 0.42f).toSp() }
        StickerText(letter, FoniaSticker.Voice, fontSize = if (sp.value > 8f) sp else 20.sp, contentDescription = word)
    }
}
