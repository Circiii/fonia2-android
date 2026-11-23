package tech.fonia.feature.speaking

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive
import tech.fonia.designsystem.letter.StickerText
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaTypeScale
import tech.fonia.speaking.FlaggedSpan
import tech.fonia.speaking.ProsodicTranscript
import tech.fonia.speaking.ProsodicWord
import tech.fonia.speaking.TimeMap
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The playback position, polled once per frame while something plays. Words read [frame] inside their
 * `graphicsLayer`/draw blocks, so following playback redraws layers but never recomposes the transcript.
 */
@Stable
class PlaybackClock {
    internal val frame = mutableLongStateOf(0L)
    internal var position: PlaybackPosition? = null
    /** Index of the word under the playhead (original time), -1 when none. */
    internal var current: Int = -1
}

@Composable
fun rememberPlaybackClock(transcript: ProsodicTranscript?, playing: Boolean, position: () -> PlaybackPosition?): PlaybackClock {
    val clock = remember { PlaybackClock() }
    LaunchedEffect(transcript, playing) {
        if (!playing || transcript == null) {
            clock.position = null
            clock.current = -1
            clock.frame.longValue++
            return@LaunchedEffect
        }
        while (isActive) {
            val t = withFrameNanos { it }
            val p = position()
            clock.position = p
            clock.current = if (p == null) -1 else transcript.wordAt(p.oldSeconds).let { i ->
                if (i >= 0 && p.oldSeconds <= transcript.words[i].end + 0.25) i else -1
            }
            clock.frame.longValue = t
        }
    }
    return clock
}

/**
 * The prosodic transcript (plan 01 §10, S nr. 3): size and weight = loudness, baseline = pitch level, a slight tilt =
 * pitch movement, gaps = pauses. Fillers, false starts and repetitions stay in place as thin struck "ghost" words
 * (dotted when the engine is unsure, dotted underline when offered but not cut) — never red. During the cleaned
 * playback the current word is underlaid and each cut word pops out as the playhead passes its join.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProsodicTranscriptView(
    transcript: ProsodicTranscript,
    spans: List<FlaggedSpan>,
    cuts: Set<Int>,
    clock: PlaybackClock,
    modifier: Modifier = Modifier,
    timeMap: TimeMap? = null,
    onToggle: ((spanId: Int) -> Unit)? = null,
) {
    val byId = remember(spans) { spans.associateBy { it.id } }
    val summary = remember(transcript) { tech.fonia.speaking.SpeakingText.spoken(transcript.summary) }
    FlowRow(
        modifier.semantics(mergeDescendants = false) { contentDescription = transcript.plainText + ". " + summary },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        for (w in transcript.words) {
            if (w.pauseBefore >= 0.25 && w.index > 0) PauseGap(w, clock, timeMap)
            val span = w.spanId?.let { byId[it] }
            WordChip(w, span, span != null && span.id in cuts, clock, timeMap, onToggle)
        }
    }
}

/** A pause as an empty stretch with a faint dotted line; a long one visibly closes up when the cleaned take passes it. */
@Composable
private fun PauseGap(w: ProsodicWord, clock: PlaybackClock, timeMap: TimeMap?) {
    val c = FoniaTheme.colors
    val seconds = min(w.pauseBefore, 3.0)
    val width = (6 + seconds * 18).dp
    Box(
        Modifier
            .width(width)
            .height(40.dp)
            .drawBehind {
                clock.frame.longValue
                val p = clock.position
                var share = 1f
                if (p != null && p.version == Version.Clean && timeMap != null && w.pauseBefore > 1.2) {
                    val passed = timeMap.toNew(w.start) <= p.newSeconds
                    if (passed) share = (0.6 / w.pauseBefore).toFloat()
                }
                val len = size.width * 0.8f * share
                val y = size.height * 0.62f
                drawLine(
                    c.separator, Offset((size.width - len) / 2, y), Offset((size.width + len) / 2, y),
                    strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 8.dp.toPx())),
                )
            },
    )
}

@Composable
private fun WordChip(
    w: ProsodicWord,
    span: FlaggedSpan?,
    cut: Boolean,
    clock: PlaybackClock,
    timeMap: TimeMap?,
    onToggle: ((Int) -> Unit)?,
) {
    val c = FoniaTheme.colors
    val reduce = FoniaTheme.reduceMotion
    val flat = FoniaTheme.flat
    val flagged = span != null
    // Size and weight from loudness; flagged words are thin ghosts whatever their level.
    val sizeSp = if (flagged) 20f else 18f + 14f * w.loudness
    val weight = when {
        flagged -> 200f
        else -> (260f + 560f * w.loudness + if (w.emphasis) 120f else 0f).coerceAtMost(900f)
    }
    val tilt = if (flagged) 0f else (-w.movementSt.coerceIn(-6f, 6f) * 1.3f)
    val lift = if (flagged) 0f else (w.pitchSt ?: 0f).coerceIn(-6f, 6f) * 1.6f // dp, up = higher pitch
    val ghost = when {
        !flagged -> 1f
        cut -> 0.42f
        else -> 0.8f
    }
    val state = when {
        !flagged -> null
        cut -> SpeakingStrings.CUT
        !span.cut -> SpeakingStrings.MAYBE
        else -> SpeakingStrings.KEPT
    }
    Box(
        Modifier
            .then(
                if (span != null && onToggle != null) {
                    Modifier
                        .semantics { stateDescription = state ?: "" }
                        .clickable(role = Role.Button, onClickLabel = if (cut) SpeakingStrings.KEPT else SpeakingStrings.CUT) { onToggle(span.id) }
                } else Modifier,
            )
            // StickerText pads 0.3 em each side; give most of it back so words sit like text.
            .layout { m, constraints ->
                val p = m.measure(constraints)
                val trim = (sizeSp.sp.toPx() * 0.42f).toInt()
                val wOut = max(1, p.width - trim)
                layout(wOut, p.height) { p.place(-trim / 2, 0) }
            }
            .graphicsLayer {
                clock.frame.longValue
                translationY = -lift.dp.toPx()
                alpha = ghost
                if (span != null && cut && timeMap != null) {
                    val p = clock.position
                    if (p != null && p.version == Version.Clean) {
                        // Pop out: the playhead reached this cut's join in the cleaned take.
                        val join = timeMap.toNew(span.start)
                        val k = ((p.newSeconds - join) / 0.45).toFloat()
                        when {
                            k < 0f -> {}
                            k >= 1f || reduce -> alpha = 0.12f
                            else -> {
                                val s = 1f + 0.28f * sin(PI.toFloat() * k)
                                scaleX = s
                                scaleY = s
                                translationY = -lift.dp.toPx() - 14.dp.toPx() * k
                                alpha = ghost + (0.12f - ghost) * k
                            }
                        }
                    }
                }
            }
            .drawBehind {
                clock.frame.longValue
                if (clock.current == w.index) {
                    drawRoundRect(c.fill, cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()))
                    val y = size.height - 4.dp.toPx()
                    drawLine(c.accentDeep, Offset(size.width * 0.15f, y), Offset(size.width * 0.85f, y), 3.dp.toPx(), StrokeCap.Round)
                }
            }
            .drawWithContent {
                drawContent()
                if (span == null) return@drawWithContent
                val dotted = span.unsure || !cut
                val effect = if (dotted) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())) else null
                val y = if (cut) size.height * 0.52f else size.height * 0.82f
                drawLine(
                    c.inkSecondary, Offset(size.width * 0.12f, y), Offset(size.width * 0.88f, y),
                    strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = effect,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        StickerText(
            text = w.text,
            sticker = flat,
            fontSize = sizeSp.sp,
            weight = weight,
            roundness = FoniaTypeScale.ROND_GROWN,
            tilt = tilt,
            contentDescription = w.text,
        )
    }
}
