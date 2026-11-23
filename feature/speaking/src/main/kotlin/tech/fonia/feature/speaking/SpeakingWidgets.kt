package tech.fonia.feature.speaking

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaSpace
import kotlin.math.min

/** The one filled accent action of a screen (plan 05 §2.3 rule 1). */
@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = FoniaTheme.colors
    Box(
        modifier
            .defaultMinSize(minHeight = 56.dp)
            .background(if (enabled) c.accent else c.fill, RoundedCornerShape(50))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = FoniaSpace.l, vertical = FoniaSpace.s),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = FoniaTheme.type.headline.copy(color = if (enabled) c.surface else c.inkSecondary, textAlign = TextAlign.Center))
    }
}

/** A secondary action: outlined, never filled. */
@Composable
internal fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = FoniaTheme.colors
    Box(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .border(1.5.dp, c.inkSecondary, RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = FoniaSpace.m, vertical = FoniaSpace.xs),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = FoniaTheme.type.headline.copy(color = c.accentDeep, textAlign = TextAlign.Center))
    }
}

@Composable
internal fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.defaultMinSize(minHeight = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = FoniaSpace.s),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = FoniaTheme.type.label.copy(color = FoniaTheme.colors.accentDeep))
    }
}

/** The A/B switch „cum a fost” / „fără ăăă”. Selection shows by fill, border and weight, not by colour alone. */
@Composable
internal fun VersionSwitch(selected: Version, onSelect: (Version) -> Unit, modifier: Modifier = Modifier) {
    val c = FoniaTheme.colors
    Row(
        modifier.border(1.5.dp, c.inkSecondary, RoundedCornerShape(50)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((v, label) in listOf(Version.Original to SpeakingStrings.AS_IT_WAS, Version.Clean to SpeakingStrings.WITHOUT)) {
            val on = v == selected
            Box(
                Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .background(if (on) c.fill else c.surface, RoundedCornerShape(50))
                    .then(if (on) Modifier.border(1.5.dp, c.accentDeep, RoundedCornerShape(50)) else Modifier)
                    .semantics { this.selected = on }
                    .clickable(role = Role.Tab) { onSelect(v) },
                contentAlignment = Alignment.Center,
            ) {
                val style = if (on) FoniaTheme.type.headline else FoniaTheme.type.label
                BasicText((if (on) "● " else "○ ") + label, style = style.copy(color = if (on) c.accentDeep else c.inkSecondary))
            }
        }
    }
}

/**
 * Plan 01 §10 step 2: the voice as a wave and a ring for the time; no counter. Levels and time are read in the draw
 * phase from [levels], so 50 level updates a second cause no recomposition.
 */
@Composable
internal fun RecordingRing(levels: LevelHistory, maxSeconds: Double, modifier: Modifier = Modifier) {
    val c = FoniaTheme.colors
    val reduce = FoniaTheme.reduceMotion
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(levels) {
        while (isActive) {
            val t = withFrameNanos { it }
            frame.longValue = if (reduce) t / 100_000_000L else t
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .semantics { contentDescription = "Te ascult" }
            .drawBehind {
                frame.longValue
                val stroke = 10.dp.toPx()
                val d = min(size.width, size.height) - stroke
                val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2)
                drawArc(c.fill, 0f, 360f, false, topLeft, Size(d, d), style = Stroke(stroke))
                val sweep = (levels.elapsedSeconds() / maxSeconds).coerceIn(0.0, 1.0).toFloat() * 360f
                drawArc(c.accent, -90f, sweep, false, topLeft, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
                // The wave: the last levels as bars, newest on the right.
                val n = 32
                val inner = d * 0.62f
                val left = (size.width - inner) / 2
                val step = inner / n
                val bar = step * 0.55f
                val mid = size.height / 2
                for (i in 0 until n) {
                    val lv = levels.level(n - 1 - i).coerceIn(0f, 1f)
                    val h = (0.06f + 0.94f * lv) * inner * 0.45f
                    drawRoundRect(
                        c.voice, Offset(left + i * step + (step - bar) / 2, mid - h), Size(bar, 2 * h),
                        cornerRadius = CornerRadius(bar / 2, bar / 2),
                    )
                }
            },
    )
}

/** „Mă gândesc” with three breathing dots (static under reduced motion). */
@Composable
internal fun ThinkingLine(modifier: Modifier = Modifier) {
    val c = FoniaTheme.colors
    val reduce = FoniaTheme.reduceMotion
    val phase = rememberInfiniteTransition(label = "thinking")
        .animateFloat(0f, 3f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "dots")
    Row(modifier.height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FoniaSpace.xs)) {
        BasicText(SpeakingStrings.THINKING, style = FoniaTheme.type.title2.copy(color = c.ink))
        for (i in 0 until 3) {
            Box(
                Modifier.padding(top = 8.dp).drawBehind {
                    val on = if (reduce) 1f else (1f - kotlin.math.abs(phase.value - i - 0.5f).coerceAtMost(1f))
                    drawCircle(c.accentDeep.copy(alpha = 0.25f + 0.75f * on), radius = 4.dp.toPx())
                }.padding(6.dp),
            )
        }
    }
}
