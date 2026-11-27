package tech.fonia.features.session

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.fonia.design.Field
import tech.fonia.design.LocalReduceMotion
import tech.fonia.design.Palette
import tech.fonia.design.Rating
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.ui
import tech.fonia.designsystem.letter.StickerText
import tech.fonia.designsystem.tokens.FoniaClassicSticker

/*
 * The session's motion on top of his screens: titles whose letters drop
 * in one after another, the sound's letter in a title as a sticker, praise that pops, Foni hopping on a good take.
 * Reduce motion: everything appears in place.
 */

/**
 * A screen title in his place and size, in Baloo, whose letters fall in one after another with a little bounce (a wave
 * by letter). The token equal to [letter] (e.g. "S" in "Ține S cât poți") is drawn as a sticker in the family colour.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WaveTitle(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, letter: String? = null, field: Field? = null) {
    val reduce = LocalReduceMotion.current
    val words = text.split(' ').filter { it.isNotEmpty() }
    val styled = style.copy(color = color)
    var index = 0
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { (style.fontSize.value * 0.28f).sp.toDp() }
    FlowRow(
        modifier.clearAndSetSemantics {
            contentDescription = text
            heading()
        },
        horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.Center,
    ) {
        for (word in words) {
            val bare = word.trimEnd('?', '!', '.', ',')
            val tail = word.substring(bare.length)
            if (letter != null && field != null && bare.equals(letter, ignoreCase = false)) {
                val i = index++
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StickerText(
                        bare,
                        stickerFor(field),
                        Modifier.trimSides(0.22f * style.fontSize.value).letterDrop(i, reduce, big = true),
                        fontSize = (style.fontSize.value * 1.05f).sp,
                        weight = 900f,
                    )
                    for (c in tail) BasicText(c.toString(), Modifier.letterDrop(index++, reduce), style = styled)
                }
            } else {
                Row {
                    for (c in word) BasicText(c.toString(), Modifier.letterDrop(index++, reduce), style = styled)
                }
            }
        }
    }
}

/** One letter of a [WaveTitle]: falls from above, stretched, and lands with a squash (35 ms after the previous one). */
private fun Modifier.letterDrop(index: Int, reduce: Boolean, big: Boolean = false): Modifier = composed {
    val p = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduce) return@LaunchedEffect
        delay(60L + 35L * index)
        p.animateTo(1f, spring(dampingRatio = if (big) 0.38f else 0.5f, stiffness = if (big) 260f else 420f))
    }
    graphicsLayer {
        val v = p.value
        alpha = (v * 2.2f).coerceIn(0f, 1f)
        translationY = (1f - v) * -size.height * 0.9f
        // Stretched while falling, a squash when it overshoots the landing.
        val stretch = 1f + (1f - v).coerceIn(0f, 1f) * 0.35f
        scaleY = if (v > 1f) 1f - (v - 1f) * 0.8f else stretch
        scaleX = if (v > 1f) 1f + (v - 1f) * 0.6f else 1f / stretch.coerceAtLeast(0.01f)
        transformOrigin = TransformOrigin(0.5f, 1f)
    }
}

/**
 * The verdict line of his result panel. A good take's praise ("Bravo!", "Da!") pops in as a green sticker in his
 * green; anything else is his calm line, sliding in.
 */
@Composable
fun PraiseTitle(title: String, popping: Boolean) {
    val reduce = LocalReduceMotion.current
    val p = remember(title) { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(title) {
        if (!reduce) p.animateTo(1f, if (popping) spring(0.35f, 300f) else spring(Spring.DampingRatioNoBouncy, 500f))
    }
    val m = Modifier.graphicsLayer {
        val v = p.value
        alpha = v.coerceIn(0f, 1f)
        if (popping) {
            scaleX = 0.3f + 0.7f * v
            scaleY = 0.3f + 0.7f * v
            rotationZ = (1f - v) * -12f
            transformOrigin = TransformOrigin(0f, 0.5f)
        } else {
            translationX = (1f - v) * 24f
        }
    }
    if (popping && title.length <= 14) {
        StickerText(title, FoniaClassicSticker.Good, m, fontSize = 28.sp, weight = 900f)
    } else {
        BasicText(title, m, style = ui(22, UIWeight.bold, TextRole.title2).copy(color = Palette.ink))
    }
}

/** Foni answers the verdict: a hop on a good take, a curious tilt on "almost", nothing otherwise. */
fun Modifier.foniReaction(rating: Rating?): Modifier = composed {
    val reduce = LocalReduceMotion.current
    val hop = remember { Animatable(0f) }
    val tilt = remember { Animatable(0f) }
    LaunchedEffect(rating) {
        if (reduce || rating == null) return@LaunchedEffect
        if (rating == Rating.yes) {
            repeat(2) {
                hop.animateTo(1f, tween(140))
                hop.animateTo(0f, spring(0.45f, 600f))
            }
        } else {
            launch { tilt.animateTo(1f, spring(0.3f, 220f)) }
        }
    }
    graphicsLayer {
        translationY = -hop.value * size.height * 0.22f
        rotationZ = -10f * tilt.value
        val squash = hop.value
        scaleX = 1f - 0.06f * squash
        scaleY = 1f + 0.08f * squash
        transformOrigin = TransformOrigin(0.5f, 1f)
    }
}

/** Takes [sp] of the sticker's own side padding out of the line, so it sits like a letter between words. */
private fun Modifier.trimSides(sp: Float): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val p = measurable.measure(constraints.copy(minWidth = 0))
        val trim = (sp.sp.toPx()).toInt().coerceAtMost(p.width / 3)
        layout(p.width - 2 * trim, p.height) { p.place(-trim, 0) }
    },
)
