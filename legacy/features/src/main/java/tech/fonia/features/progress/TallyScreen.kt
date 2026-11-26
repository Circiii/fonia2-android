package tech.fonia.features.progress

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.LocalReduceMotion
import tech.fonia.design.Palette
import tech.fonia.design.PressableStyle
import tech.fonia.design.ProgressPill
import tech.fonia.design.Radius
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.fBody
import tech.fonia.design.fCaption
import tech.fonia.design.fLead
import tech.fonia.design.monospacedDigit
import tech.fonia.design.softShadow
import tech.fonia.design.swiftSpring
import tech.fonia.design.ui
import tech.fonia.domain.Plural
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.HeaderPage
import tech.fonia.features.app.TallyFocus
import tech.fonia.features.app.capitalizedFirstLetter
import tech.fonia.features.grownups.SettingsIcon
import tech.fonia.features.grownups.SettingsIcons
import tech.fonia.features.home.PathModel
import tech.fonia.store.localDay
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** The marks the end of a lesson celebrates (iOS `TallyScreen.milestones` and `nextMilestone`). */
object Tally {
    private val fixed = listOf(1, 5, 10, 25, 50, 75, 100)

    /** The marks reached with [upTo] stars: the fixed ones, then every 50 past a hundred. */
    fun milestones(upTo: Int): List<Int> {
        val beyond = if (upTo > 100) (150..upTo step 50).toList() else emptyList()
        return (fixed + beyond).filter { it <= upTo }
    }

    /** The next mark after [after] stars. */
    fun nextMilestone(after: Int): Int = fixed.firstOrNull { it > after } ?: ((after / 50 + 1) * 50)
}

private val romanian = Locale("ro", "RO")
private val weekdays = listOf("L", "M", "M", "J", "V", "S", "D")
private val cardShape = ContinuousRoundedRectangle(Radius.card)
private val capsule = RoundedCornerShape(percent = 50)

/**
 * The two counts on Home, opened up: the days that had practice and the stars from finished lessons. Both only grow.
 * There is no current streak here to keep alive, only a record that a day off never lowers.
 */
@Composable
fun TallyScreen(path: PathModel, @Suppress("UNUSED_PARAMETER") app: AppModel, focus: TallyFocus) {
    var shown by rememberSaveable { mutableStateOf(focus) }
    var hasArrived by remember { mutableStateOf(false) }
    val reduceMotion = LocalReduceMotion.current
    LaunchedEffect(Unit) { hasArrived = true }
    HeaderPage(if (shown == TallyFocus.Days) "Zilele tale" else "Stelele tale") {
        Switcher(shown, Modifier.padding(horizontal = 20.dp).padding(top = 14.dp)) { shown = it }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Crossfade(shown, Modifier.widthIn(max = 620.dp), animationSpec = if (reduceMotion) snap() else swiftSpring(0.35, 0.2), label = "tallyPage") { page ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    when (page) {
                        TallyFocus.Days -> DaysPage(path, hasArrived)
                        TallyFocus.Stars -> StarsPage(path, hasArrived)
                    }
                }
            }
        }
    }
}

// MARK: Switcher

/** "Zile | Stele", with the white capsule sliding to the one shown. */
@Composable
private fun Switcher(focus: TallyFocus, modifier: Modifier, onChange: (TallyFocus) -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val position by animateFloatAsState(
        if (focus == TallyFocus.Days) 0f else 1f,
        if (reduceMotion) snap() else swiftSpring(0.35, 0.2),
        label = "tallySegment",
    )
    BoxWithConstraints(modifier.fillMaxWidth().background(Palette.cloud, capsule).padding(4.dp)) {
        val segment = (maxWidth - 4.dp) / 2
        Box {
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier
                        .offset { IntOffset(((segment + 4.dp).toPx() * position).roundToInt(), 0) }
                        .width(segment)
                        .fillMaxHeight()
                        .softShadow(capsule)
                        .background(Palette.sheet, capsule),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Segment(TallyFocus.Days, FoniaIcon.check, "Zile", focus == TallyFocus.Days, Modifier.weight(1f)) { onChange(TallyFocus.Days) }
                Segment(TallyFocus.Stars, FoniaIcon.star, "Stele", focus == TallyFocus.Stars, Modifier.weight(1f)) { onChange(TallyFocus.Stars) }
            }
        }
    }
}

@Composable
private fun Segment(value: TallyFocus, icon: FoniaIcon, title: String, isOn: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .heightIn(min = 44.dp)
            .clip(capsule)
            .selectable(selected = isOn, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FoniaIconView(icon, size = 18f, tint = if (value == TallyFocus.Days) Palette.accentText else Palette.starTint)
        FoniaText(title, style = ui(17, UIWeight.bold, TextRole.headline), color = Palette.ink)
    }
}

// MARK: Days

@Composable
private fun ColumnScope.DaysPage(path: PathModel, hasArrived: Boolean) {
    val practised = path.practisedDays
    val prefix = localDay(Instant.now()).take(7)
    val thisMonth = practised.count { it.startsWith(prefix) }
    Hero(FoniaIcon.check, Palette.accent, Palette.accentSoft, path.totalDays, Plural.word(path.totalDays, Plural.Noun.Zi) + " în care ai exersat", hasArrived)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).tallyArrival(1, hasArrived), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Chip(SettingsIcons.CHART, Palette.cold, "Cel mai lung șir", Plural.format(path.longestRun, Plural.Noun.Zi), Modifier.weight(1f))
        Chip(FoniaIcon.book.rawValue, Palette.today, "Luna asta", Plural.format(thisMonth, Plural.Noun.Zi), Modifier.weight(1f))
    }
    MonthCard(practised, hasArrived, Modifier.tallyArrival(2, hasArrived))
    Note("O zi liberă nu șterge nimic. Zilele doar se adună.", Modifier.tallyArrival(3, hasArrived))
}

/** The month on a calendar, Monday first: a green check on each day with practice, a ring on today. */
@Composable
private fun MonthCard(practised: Set<String>, hasArrived: Boolean, modifier: Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val current = YearMonth.now()
    var month by rememberSaveable { mutableStateOf(current) }
    Column(
        modifier.fillMaxWidth().softShadow(cardShape).background(Palette.sheet, cardShape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonthStep(FoniaIcon.back, "Luna trecută", enabled = true) { month = month.minusMonths(1) }
            FoniaText(
                month.format(DateTimeFormatter.ofPattern("LLLL yyyy", romanian)).capitalizedFirstLetter,
                Modifier.weight(1f),
                style = ui(19, UIWeight.bold, TextRole.title3),
                color = Palette.ink,
                textAlign = TextAlign.Center,
            )
            MonthStep(FoniaIcon.forward, "Luna următoare", enabled = month < current) { month = month.plusMonths(1) }
        }
        Crossfade(month, animationSpec = if (reduceMotion) snap() else tween(200), label = "tallyMonth") { shown ->
            MonthGrid(shown, practised, hasArrived)
        }
    }
}

@Composable
private fun MonthGrid(month: YearMonth, practised: Set<String>, hasArrived: Boolean) {
    val first = month.atDay(1)
    val blanks = first.dayOfWeek.value - 1
    val cells: List<LocalDate?> = List(blanks) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    val today = LocalDate.now()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.clearAndSetSemantics {}) {
            for (letter in weekdays) {
                FoniaText(letter, Modifier.weight(1f), style = ui(13, UIWeight.semibold, TextRole.caption), color = Palette.inkSoft, textAlign = TextAlign.Center)
            }
        }
        for ((row, week) in cells.chunked(7).withIndex()) {
            Row {
                for (column in 0 until 7) {
                    val date = week.getOrNull(column)
                    Box(Modifier.weight(1f).heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
                        if (date != null) DayDot(date, today, practised.contains(date.toString()), hasArrived, order = row * 7 + column)
                    }
                }
            }
        }
    }
}

private val dayLabel = DateTimeFormatter.ofPattern("d MMMM", romanian)

@Composable
private fun DayDot(date: LocalDate, today: LocalDate, hasPractice: Boolean, hasArrived: Boolean, order: Int) {
    val isToday = date == today
    val ring = Palette.today
    Box(
        Modifier
            .size(38.dp)
            .drawBehind { if (isToday) drawCircle(ring, radius = size.minDimension / 2 - 1.25.dp.toPx(), style = Stroke(2.5.dp.toPx())) }
            .clearAndSetSemantics {
                contentDescription = date.format(dayLabel)
                stateDescription = listOfNotNull(if (isToday) "Azi" else null, if (hasPractice) "Ai exersat" else null).joinToString(", ")
            },
        contentAlignment = Alignment.Center,
    ) {
        if (hasPractice) {
            PopIn(hasArrived, delayMillis = 200 + order * 12, burst = Palette.accentSoftEdge, bounce = 0.45, modifier = Modifier.padding(if (isToday) 4.dp else 0.dp)) {
                Box(Modifier.size(if (isToday) 30.dp else 38.dp).background(Palette.accent, CircleShape), contentAlignment = Alignment.Center) {
                    FoniaIconView(FoniaIcon.check, size = 16f, tint = Color.White)
                }
            }
        } else {
            FoniaText(
                "${date.dayOfMonth}",
                style = ui(15, UIWeight.semibold, TextRole.subheadline).monospacedDigit(),
                color = if (date.isAfter(today)) Palette.lineStrong else Palette.ink,
            )
        }
    }
}

@Composable
private fun MonthStep(icon: FoniaIcon, label: String, enabled: Boolean, onClick: () -> Unit) {
    StyledButton(
        onClick = onClick,
        style = PressableStyle(),
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Box(Modifier.size(44.dp).background(Palette.cloud, CircleShape), contentAlignment = Alignment.Center) {
            FoniaIconView(icon, size = 14f, tint = Palette.ink)
        }
    }
}

// MARK: Stars

@Composable
private fun ColumnScope.StarsPage(path: PathModel, hasArrived: Boolean) {
    val stars = path.totalLessons
    Hero(FoniaIcon.star, Palette.starTint, Palette.tipSoft, stars, Plural.word(stars, Plural.Noun.Stea) + " de la lecțiile terminate", hasArrived)
    NextCard(stars, Modifier.tallyArrival(1, hasArrived))
    Badges(stars, Modifier.tallyArrival(2, hasArrived))
    if (stars > 0) StarWall(stars, hasArrived, Modifier.tallyArrival(3, hasArrived))
    Note("Fiecare lecție terminată îți aduce o stea. Atinge steaua mare!", Modifier.tallyArrival(4, hasArrived))
}

@Composable
private fun NextCard(stars: Int, modifier: Modifier) {
    val next = Tally.nextMilestone(stars)
    val previous = Tally.milestones(stars).lastOrNull() ?: 0
    Column(
        modifier.fillMaxWidth().softShadow(cardShape).background(Palette.sheet, cardShape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FoniaText("Încă ${Plural.format(next - stars, Plural.Noun.Stea)} până la $next", style = ui(17, UIWeight.bold, TextRole.headline), color = Palette.ink)
        ProgressPill(stars - previous, next - previous)
    }
}

/** Every mark up to a hundred (or the next one past it), lit once it is reached. */
@Composable
private fun Badges(stars: Int, modifier: Modifier) {
    val shown = Tally.milestones(max(100, Tally.nextMilestone(stars)))
    Column(
        modifier.fillMaxWidth().softShadow(cardShape).background(Palette.sheet, cardShape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FoniaText("Praguri", style = ui(15, UIWeight.bold, TextRole.subheadline), color = Palette.inkSoft)
        // `GridItem(.adaptive(minimum: 62), spacing: 10)`.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = max(1, ((maxWidth + 10.dp) / (62.dp + 10.dp)).toInt())
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (row in shown.chunked(columns)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (column in 0 until columns) {
                            val mark = row.getOrNull(column)
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { if (mark != null) Badge(mark, stars >= mark) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Badge(mark: Int, reached: Boolean) {
    Column(
        Modifier.clearAndSetSemantics {
            contentDescription = Plural.format(mark, Plural.Noun.Stea)
            stateDescription = if (reached) "Atins" else "Încă nu"
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(50.dp).background(if (reached) Palette.startLid else Palette.cloud, CircleShape), contentAlignment = Alignment.Center) {
            FoniaIconView(FoniaIcon.star, size = 22f, tint = if (reached) Palette.startSkirt else Palette.lineStrong)
        }
        FoniaText("$mark", style = ui(15, UIWeight.bold, TextRole.subheadline).monospacedDigit(), color = if (reached) Palette.ink else Palette.inkSoft)
    }
}

/** One small star for each, up to a hundred, so a wall fills up over the weeks. */
@Composable
private fun StarWall(stars: Int, hasArrived: Boolean, modifier: Modifier) {
    val shown = min(stars, 100)
    Column(
        modifier
            .fillMaxWidth()
            .softShadow(cardShape)
            .background(Palette.sheet, cardShape)
            .padding(16.dp)
            .clearAndSetSemantics { contentDescription = Plural.format(stars, Plural.Noun.Stea) },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FoniaText("Toate stelele", style = ui(15, UIWeight.bold, TextRole.subheadline), color = Palette.inkSoft)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((row, ten) in (0 until shown).chunked(10).withIndex()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (column in 0 until 10) {
                        Box(Modifier.weight(1f).height(24.dp), contentAlignment = Alignment.Center) {
                            if (column < ten.size) {
                                PopIn(hasArrived, delayMillis = 250 + (row * 10 + column) * 20, burst = Palette.startGlint, bounce = 0.5) {
                                    FoniaIconView(FoniaIcon.star, size = 20f, tint = Palette.starTint)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (stars > 100) FoniaText("și încă ${stars - 100}", style = fCaption, color = Palette.inkSoft)
    }
}

// MARK: Shared

/** The big count: its icon on a soft circle (a tap sprays small ones; it shines on the way in), the number, a line. */
@Composable
private fun Hero(icon: FoniaIcon, tint: Color, soft: Color, value: Int, line: String, hasArrived: Boolean) {
    var taps by remember { mutableIntStateOf(0) }
    Column(
        Modifier
            .fillMaxWidth()
            .softShadow(cardShape)
            .background(Palette.sheet, cardShape)
            .padding(vertical = 22.dp)
            .semantics(mergeDescendants = true) {}
            .tallyArrival(0, hasArrived),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The tap is only for fun, so the reader skips it (StyledButton's own tick is the light haptic).
        Box(Modifier.clearAndSetSemantics {}) {
            StyledButton(onClick = { taps += 1 }, style = PressableStyle()) {
                Box(Modifier.size(104.dp).clip(CircleShape).background(soft).shine(hasArrived), contentAlignment = Alignment.Center) {
                    FoniaIconView(icon, size = 52f, tint = tint)
                }
            }
            Spray(taps, icon, tint, Modifier.matchParentSize())
        }
        FoniaText("$value", style = ui(56, UIWeight.black, TextRole.largeTitle).monospacedDigit(), color = Palette.ink)
        FoniaText(line, Modifier.padding(horizontal = 16.dp), style = fLead, color = Palette.inkSoft, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Chip(icon: String, tint: Color, title: String, value: String, modifier: Modifier) {
    Column(
        modifier
            .fillMaxHeight()
            .softShadow(cardShape)
            .background(Palette.sheet, cardShape)
            .padding(14.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingsIcon(icon, 16f, tint)
            FoniaText(title, style = ui(14, UIWeight.semibold, TextRole.subheadline), color = Palette.inkSoft, maxLines = 1, minimumScaleFactor = 0.8f)
        }
        FoniaText(value, style = ui(20, UIWeight.bold, TextRole.title3).monospacedDigit(), color = Palette.ink)
    }
}

@Composable
private fun Note(text: String, modifier: Modifier) {
    FoniaText(
        text,
        modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp),
        style = fBody,
        color = Palette.inkSoft,
        textAlign = TextAlign.Center,
    )
}

/**
 * Each card settles into place a beat after the one above it, the first time the page opens: it fades in and comes
 * up 14 dp, 50 ms after the card above. Switching between days and stars later shows the cards as they are.
 */
@Composable
private fun Modifier.tallyArrival(order: Int, hasArrived: Boolean): Modifier {
    val reduceMotion = LocalReduceMotion.current
    val progress = remember { Animatable(if (hasArrived || reduceMotion) 1f else 0f) }
    LaunchedEffect(hasArrived) {
        if (!hasArrived || progress.value >= 1f) return@LaunchedEffect
        delay(order * 50L)
        progress.animateTo(1f, swiftSpring(0.4, 0.15))
    }
    return graphicsLayer {
        alpha = progress.value.coerceIn(0f, 1f)
        translationY = (1f - progress.value) * 14.dp.toPx()
    }
}

// MARK: Effects (Pow's pop, spray and shine, drawn here)

/**
 * Pow's `.movingParts.pop(color)`: the first time [arrived] turns true, the content springs up from nothing after
 * [delayMillis], with a ring and a few dots of [burst] bursting out behind it. Shown at once when it was already
 * there (another month), and with only a fade under Reduce Motion.
 */
@Composable
private fun PopIn(arrived: Boolean, delayMillis: Int, burst: Color, bounce: Double, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val startsHidden = remember { !arrived }
    val scale = remember { Animatable(if (startsHidden && !reduceMotion) 0f else 1f) }
    val alpha = remember { Animatable(if (startsHidden) 0f else 1f) }
    val ring = remember { Animatable(1f) }
    LaunchedEffect(arrived) {
        if (!arrived || !startsHidden || alpha.value >= 1f) return@LaunchedEffect
        if (reduceMotion) {
            alpha.animateTo(1f, tween(200))
            return@LaunchedEffect
        }
        delay(delayMillis.toLong())
        alpha.snapTo(1f)
        ring.snapTo(0f)
        launch { ring.animateTo(1f, tween(550, easing = LinearOutSlowInEasing)) }
        scale.animateTo(1f, swiftSpring(0.4, bounce))
    }
    Box(
        modifier
            .drawBehind {
                val t = ring.value
                if (t <= 0f || t >= 1f) return@drawBehind
                val base = size.minDimension / 2
                val fade = 1f - t
                drawCircle(burst.copy(alpha = fade), radius = base * (0.6f + 0.7f * t), style = Stroke((3.dp.toPx()) * fade + 0.5f))
                for (i in 0 until 6) {
                    val angle = (i / 6f) * 2 * Math.PI.toFloat() + 0.5f
                    val reach = base * (0.9f + 0.8f * t)
                    drawCircle(burst.copy(alpha = fade), radius = 2.dp.toPx() * fade + 0.5f, center = center + Offset(cos(angle) * reach, sin(angle) * reach))
                }
            }
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

private class Spark(val angle: Float, val distance: Float, val spin: Float, val scale: Float)

/**
 * Pow's `.spray`: each new [trigger] throws a handful of small [icon]s out of the top of the circle; they fly, fall a
 * little, turn and fade. Nothing under Reduce Motion.
 */
@Composable
private fun Spray(trigger: Int, icon: FoniaIcon, tint: Color, modifier: Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val progress = remember { Animatable(1f) }
    var sparks by remember { mutableStateOf(emptyList<Spark>()) }
    LaunchedEffect(trigger) {
        if (trigger == 0 || reduceMotion) return@LaunchedEffect
        sparks = List(9) {
            Spark(
                angle = Math.toRadians(Random.nextDouble(-165.0, -15.0)).toFloat(),
                distance = Random.nextDouble(60.0, 115.0).toFloat(),
                spin = Random.nextDouble(-200.0, 200.0).toFloat(),
                scale = Random.nextDouble(0.7, 1.1).toFloat(),
            )
        }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(900, easing = LinearOutSlowInEasing))
    }
    val t = progress.value
    if (t >= 1f) return
    // `UnitPoint(x: 0.5, y: 0.3)`.
    Box(modifier, contentAlignment = BiasAlignment(0f, -0.4f)) {
        for (spark in sparks) {
            FoniaIconView(
                icon,
                Modifier.graphicsLayer {
                    translationX = cos(spark.angle) * spark.distance.dp.toPx() * t
                    translationY = sin(spark.angle) * spark.distance.dp.toPx() * t + 40.dp.toPx() * t * t
                    rotationZ = spark.spin * t
                    scaleX = spark.scale * (1f - 0.3f * t)
                    scaleY = spark.scale * (1f - 0.3f * t)
                    alpha = 1f - t
                },
                size = 20f,
                tint = tint,
            )
        }
    }
}

/** Pow's `.shine(duration: 0.9)`: once [trigger] turns true, a soft band of light sweeps across. */
@Composable
private fun Modifier.shine(trigger: Boolean): Modifier {
    val reduceMotion = LocalReduceMotion.current
    val sweep = remember { Animatable(if (trigger) 1f else 0f) }
    LaunchedEffect(trigger) {
        if (!trigger || reduceMotion || sweep.value >= 1f) return@LaunchedEffect
        sweep.animateTo(1f, tween(900))
    }
    return drawWithContent {
        drawContent()
        val t = sweep.value
        if (t <= 0f || t >= 1f) return@drawWithContent
        val band = size.width * 0.5f
        val x = -band + (size.width + 2 * band) * t
        drawRect(
            Brush.linearGradient(
                listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0f)),
                start = Offset(x - band / 2, 0f),
                end = Offset(x + band / 2, size.height),
            ),
        )
    }
}
