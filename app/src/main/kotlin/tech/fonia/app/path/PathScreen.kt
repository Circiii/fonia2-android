package tech.fonia.app.path

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import tech.fonia.app.data.SoundStats
import tech.fonia.app.text.T
import tech.fonia.app.ui.FoniMascot
import tech.fonia.app.ui.FoniPose
import tech.fonia.app.ui.MouthButton
import tech.fonia.app.ui.SpeechBubble
import tech.fonia.designsystem.components.ContinueButton
import tech.fonia.designsystem.components.PathNode
import tech.fonia.designsystem.components.PathNodeState
import tech.fonia.designsystem.components.QuietButton
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaSpace
import tech.fonia.model.SoundId
import kotlin.math.roundToInt
import kotlin.math.sin

/** What PATH shows, loaded from the store each time PATH appears. */
data class PathModel(
    val today: Int = 0,
    val stats: Map<SoundId, SoundStats> = emptyMap(),
    val current: SoundId = SoundId.S,
    val loaded: Boolean = false,
    /** Days practised in total (only grows) and the current row (shown only while whole, Q7). */
    val totalDays: Int = 0,
    val streak: Int = 0,
    /** How far each sound's ladder is, 0..3 for the node's dots. */
    val ladder: Map<SoundId, Int> = emptyMap(),
)

/** The order of the road (D8: the child may pick any). */
val ROAD: List<SoundId> = listOf(SoundId.S, SoundId.Sh, SoundId.Z, SoundId.J, SoundId.Ts, SoundId.Ce, SoundId.Ge, SoundId.R, SoundId.L)

/**
 * PATH (chapter 01 §5): the road of the nine sounds as sticker nodes on a winding road, Foni beside the current one,
 * one `ContinueButton`. MIRROR („Trezim gura”, the first stop) and „Pentru adult” are visible but quiet.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PathScreen(
    model: PathModel,
    onPick: (SoundId) -> Unit,
    onHouse: (SoundId) -> Unit,
    onMirror: () -> Unit,
    onAdult: () -> Unit,
    onLab: (() -> Unit)?,
) {
    val c = FoniaTheme.colors
    Column(Modifier.fillMaxSize().background(c.bg)) {
        // ---- the bar: logo (long-press: the lab, debug), the day's count, the adult corner
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = FoniaSpace.pageKid, end = FoniaSpace.pageKid, top = FoniaSpace.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                T.appName,
                style = FoniaTheme.type.title.copy(color = c.accentDeep),
                modifier = Modifier
                    .semantics { contentDescription = T.pathLogoA11y; heading() }
                    .combinedClickable(remember { MutableInteractionSource() }, indication = null, onClick = {}, onLongClick = onLab)
                    .padding(vertical = FoniaSpace.xs),
            )
            Spacer(Modifier.weight(1f))
            QuietButton(T.pathAdult, onClick = onAdult)
        }
        Row(Modifier.padding(start = FoniaSpace.pageKid, top = FoniaSpace.xs), horizontalArrangement = Arrangement.spacedBy(FoniaSpace.xs)) {
            TodayChip(model.today)
            if (model.totalDays > 0) DayChip("☀", T.pathDays(model.totalDays))
            if (model.streak >= 2) DayChip("✦", T.pathStreak(model.streak))
        }

        // ---- the road
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val scroll = rememberScrollState()
            val density = LocalDensity.current
            val width = maxWidth
            val step = 118.dp
            val top = 64.dp
            val stops = ROAD.size + 1 // + the mirror stop
            val totalHeight = top + step * (stops - 1) + 120.dp
            val amp = (width - 120.dp) * 0.36f
            fun xOf(i: Int) = width / 2 + amp * sin(i * 1.05f + 0.35f)
            fun yOf(i: Int) = top + step * i
            val currentIndex = ROAD.indexOf(model.current) + 1
            LaunchedEffect(model.loaded) {
                if (model.loaded) with(density) {
                    val target = (yOf(currentIndex) - 230.dp).toPx().roundToInt().coerceAtLeast(0)
                    scroll.animateScrollTo(target.coerceAtMost(scroll.maxValue))
                }
            }
            Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(totalHeight)
                        .drawBehind {
                            // the road: a soft band with a dashed centre line, through every stop
                            val pts = (0 until stops).map { Offset(xOf(it).toPx(), yOf(it).toPx()) }
                            val path = Path().apply {
                                moveTo(pts[0].x, pts[0].y - 60.dp.toPx())
                                lineTo(pts[0].x, pts[0].y)
                                for (k in 1 until pts.size) {
                                    val a = pts[k - 1]
                                    val b = pts[k]
                                    val my = (a.y + b.y) / 2
                                    cubicTo(a.x, my, b.x, my, b.x, b.y)
                                }
                                lineTo(pts.last().x, pts.last().y + 80.dp.toPx())
                            }
                            drawPath(path, c.separator, style = Stroke(44.dp.toPx(), cap = StrokeCap.Round))
                            drawPath(path, c.fill, style = Stroke(38.dp.toPx(), cap = StrokeCap.Round))
                            drawPath(
                                path, c.surface,
                                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 12.dp.toPx()))),
                            )
                        },
                ) {
                    // the first stop: MIRROR („Trezim gura”), quiet
                    Column(
                        Modifier.offset { with(density) { IntOffset((xOf(0) - 44.dp).roundToPx(), (yOf(0) - 32.dp).roundToPx()) } }
                            .width(88.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        MouthButton(T.pathMirror, onClick = onMirror, modifier = Modifier.size(64.dp))
                        BasicText(T.pathMirror, style = FoniaTheme.type.caption.copy(color = c.inkSecondary))
                    }
                    ROAD.forEachIndexed { k, sound ->
                        val i = k + 1
                        val stats = model.stats[sound] ?: SoundStats(0, false)
                        val state = when {
                            sound == model.current -> PathNodeState.Current
                            stats.practisedToday -> PathNodeState.Done
                            else -> PathNodeState.Available
                        }
                        PathNode(
                            letter = sound.letter,
                            state = state,
                            progress = model.ladder[sound] ?: stats.sessions.coerceAtMost(3),
                            onClick = { onHouse(sound) },
                            contentDescription = T.pathNodeA11y(sound, stats.sessions, stats.practisedToday),
                            modifier = Modifier.offset { with(density) { IntOffset((xOf(i) - 44.dp).roundToPx(), (yOf(i) - 40.dp).roundToPx()) } },
                        )
                    }
                    // Foni stands beside the current stop, on the side away from the road's bend
                    val fx = xOf(currentIndex)
                    val right = fx < width / 2
                    val foniX = if (right) fx + 56.dp else fx - 56.dp - 64.dp
                    Box(Modifier.offset { with(density) { IntOffset(foniX.roundToPx(), (yOf(currentIndex) - 44.dp).roundToPx()) } }) {
                        tech.fonia.app.ui.Foni(tech.fonia.app.ui.FoniMood.Waving, size = 64.dp, modifier = Modifier.graphicsLayer { scaleX = if (right) 1f else -1f })
                    }
                    // "you are here": a small sun pill under Foni, always (the bubble below adds the ask on day one)
                    Box(
                        Modifier.offset { with(density) { IntOffset((foniX - 8.dp).roundToPx(), (yOf(currentIndex) + 24.dp).roundToPx()) } }
                            .background(c.sun, RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .semantics { contentDescription = T.pathHereA11y(model.current) },
                    ) { BasicText(T.pathHere, style = FoniaTheme.type.caption.copy(color = c.ink)) }
                    if (model.today == 0) {
                        // under Foni, its tail pointing up at him (x: the tail sits 40 dp in from the bubble's left)
                        val bubbleX = (foniX + 32.dp - 40.dp).coerceIn(8.dp, width - 196.dp)
                        Box(
                            Modifier.offset { with(density) { IntOffset(bubbleX.roundToPx(), (yOf(currentIndex) + 24.dp).roundToPx()) } }
                                .padding(top = 30.dp)
                                .widthIn(max = 188.dp),
                        ) { SpeechBubble(T.pathPick, tailUp = true) }
                    }
                }
            }
            // soft fades at both ends, so the road slips under the bar and the button instead of being cut
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(20.dp)
                    .background(Brush.verticalGradient(listOf(c.bg, c.bg.copy(alpha = 0f)))),
            )
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp)
                    .background(Brush.verticalGradient(listOf(c.bg.copy(alpha = 0f), c.bg))),
            )
        }

        // ---- the one button, at thumb height, not glued to the edge
        ContinueButton(
            T.pathContinue,
            onClick = { onPick(model.current) },
            contentDescription = "${T.pathContinueA11y}: ${T.soundName(model.current)}",
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = FoniaSpace.pageKid, end = FoniaSpace.pageKid, top = FoniaSpace.xs, bottom = FoniaSpace.l),
        )
    }
}

/** „Azi: N încercări” — the saved attempts of the day, for the adult; bumps when it grows. */
@Composable
private fun TodayChip(count: Int, modifier: Modifier = Modifier) {
    val c = FoniaTheme.colors
    val bump = remember { Animatable(1f) }
    var shown by remember { mutableIntStateOf(count) }
    val reduce = FoniaTheme.reduceMotion
    LaunchedEffect(count) {
        if (count > shown && !reduce) {
            bump.snapTo(1.18f)
            bump.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
        }
        shown = count
    }
    Row(
        modifier
            .graphicsLayer { scaleX = bump.value; scaleY = bump.value; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
            .background(c.surface, RoundedCornerShape(50))
            .padding(horizontal = FoniaSpace.s, vertical = 6.dp)
            .semantics(mergeDescendants = true) { role = Role.Image },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText("★", style = FoniaTheme.type.label.copy(color = c.sunDeep))
        BasicText(if (count == 0) T.pathTodayNone else T.pathToday(count), style = FoniaTheme.type.label.copy(color = c.ink))
    }
}

/** A small day chip next to today's count: the total days (☀) and, while whole, the row (✦). */
@Composable
private fun DayChip(glyph: String, text: String) {
    val c = FoniaTheme.colors
    Row(
        Modifier
            .background(c.surface, RoundedCornerShape(50))
            .padding(horizontal = FoniaSpace.s, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(glyph, style = FoniaTheme.type.label.copy(color = c.sunDeep))
        BasicText(text, style = FoniaTheme.type.label.copy(color = c.ink))
    }
}
