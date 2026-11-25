package tech.fonia.features.speaking.coach

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.Field
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.FoniaText
import tech.fonia.design.LocalReduceMotion
import tech.fonia.design.Palette
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.ui
import tech.fonia.features.app.HeaderButton
import tech.fonia.features.speaking.NoUmBlock
import tech.fonia.speaking.coach.AgainstUsual
import tech.fonia.speaking.coach.Area
import tech.fonia.speaking.coach.CoachReport
import tech.fonia.speaking.coach.CoachSpec
import tech.fonia.speaking.coach.FluencyStrip
import tech.fonia.speaking.coach.UsualText
import tech.fonia.speaking.coach.Moment
import tech.fonia.speaking.coach.MomentKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/** The colour of each kind of moment: calm hues, never red. */
internal fun MomentKind.color(): Color = when (this) {
    MomentKind.TooFast, MomentKind.TooSlow -> Palette.tip
    MomentKind.Quiet -> Palette.starTint
    MomentKind.LookedAway -> Palette.cold
    MomentKind.Moving -> Color(0xFF5AA9E6)
    MomentKind.Flat -> Palette.almost
    MomentKind.LongPause -> Palette.lineStrong
    MomentKind.Filler -> Palette.today
}

internal fun Area.color(): Color = when (this) {
    Area.Pace -> Palette.tip
    Area.Voice -> Palette.starTint
    Area.Gaze -> Palette.cold
    Area.Calm -> Color(0xFF5AA9E6)
    Area.Lively -> Palette.accent
}

/**
 * The report: first the ONE thing to work on next (its living picture, a title, a tip of at most 8 words) and
 * „Încă o dată”; then the timeline of the talk with its moments (tap one to hear it); the strip of „ăăă” and long
 * pauses with the pauses counted by length; this talk against his habit; the five scores as living pictures, with the
 * change since last time; „fără ăăă”.
 */
@Composable
internal fun CoachReportView(coach: CoachController, onClose: () -> Unit) {
    val report = coach.report ?: return
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize().background(Palette.paper)) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .verticalScroll(scroll)
                .wrapContentWidth()
                .widthIn(max = 620.dp)
                .padding(horizontal = 18.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                HeaderButton(closes = true, onClick = onClose)
                Spacer(Modifier.size(12.dp))
                FoniaText(coach.prompt.title, Modifier.weight(1f), style = ui(15, UIWeight.semibold, TextRole.subheadline), color = Palette.inkSoft, maxLines = 1, minimumScaleFactor = 0.8f)
                FoniaText(clockText(report.seconds), style = ui(15, UIWeight.semibold, TextRole.subheadline), color = Palette.inkSoft)
            }
            // Praise first: what went best, in one line, before what to work on.
            report.best?.let { area ->
                val what = when (area) {
                    Area.Pace -> "ritmul"
                    Area.Voice -> "vocea"
                    Area.Gaze -> "privirea"
                    Area.Calm -> "ținuta liniștită"
                    Area.Lively -> "vioiciunea"
                }
                FoniaText("Cel mai bine a mers: $what.", style = ui(20, UIWeight.bold, TextRole.title3), color = Palette.accentText)
            }
            FocusCard(report, onAgain = { coach.again() })
            Section("Cum a mers")
            Timeline(report, onMoment = { coach.playMoment(it.start, it.end) })
            Section("Pauze și umpluturi")
            PauseStrip(report, coach.spec, onMoment = { coach.playMoment(it.start, it.end) })
            report.usual?.let { usual ->
                Section("Față de obiceiul tău")
                UsualCard(usual, coach.spec)
            }
            Section("Cele cinci")
            Scores(report)
            report.changeLine?.let {
                FoniaText(it, style = ui(17, UIWeight.semibold, TextRole.body), color = Palette.accentText)
            }
            val said = coach.said?.takeIf { it.reliable && it.words.size >= 5 }
            if (said != null) {
                SaidWords(said, onLine = { coach.playMoment(it.start, it.end) })
            } else if (!report.wordsPerMinute.isNaN()) {
                FoniaText(
                    "Cam ${report.wordsPerMinute.toInt()} de cuvinte pe minut (estimat din silabe).",
                    style = ui(15, UIWeight.medium, TextRole.footnote),
                    color = Palette.inkSoft,
                )
            }
            Trend(if (coach.history.lastOrNull() === report.scores) coach.history else coach.history + listOf(report.scores))
            // Coverage: a number is never shown as if it stood on the whole talk when it does not.
            report.cameraCoverage?.takeIf { it < 0.9 }?.let { share ->
                FoniaText(
                    if (report.eyeContact == null) "Te-am văzut prea puțin ca să măsor privirea." else "Privirea: măsurată în ${(share * 100).toInt()} % din timp.",
                    style = ui(15, UIWeight.medium, TextRole.footnote),
                    color = Palette.inkSoft,
                )
            }
            coach.noUm?.takeIf { it.worthPlaying }?.let { take ->
                Section("Ascultă-te fără „ăăă”")
                NoUmBlock(take, coach.cleanView, coach.playing, onView = { coach.cleanView = it }, onPlay = { coach.togglePlay() })
            }
            StyledButton(
                onClick = { coach.backToSetup() },
                style = FoniButtonStyle(field = Field.paper, size = FoniButtonStyle.Size.regular, kind = FoniButtonStyle.Kind.secondary),
                modifier = Modifier.fillMaxWidth(),
            ) {
                tech.fonia.design.CTALabel("Alt subiect")
            }
        }
    }
}

/**
 * What the recogniser heard: words per minute, the filler words by name, repeated words, and the talk as text, line
 * by line (tap a line to hear it). Counts only; nothing here says a word was said badly.
 */
@Composable
private fun SaidWords(said: tech.fonia.words.SpeechText, onLine: (tech.fonia.words.Marked) -> Unit) {
    Section("Ce ai spus")
    FoniaText(
        "${said.wordsPerMinute.toInt()} cuvinte pe minut",
        style = ui(22, UIWeight.bold, TextRole.title3),
        color = Palette.ink,
    )
    val fillers = said.fillers.groupingBy { it.text.lowercase() }.eachCount().entries.sortedByDescending { it.value }
    if (fillers.isNotEmpty()) {
        FoniaText(
            "Cuvinte de umplutură: " + fillers.take(5).joinToString(", ") { "„${it.key}” ×${it.value}" },
            style = ui(16, UIWeight.semibold, TextRole.body),
            color = Palette.ink,
        )
    } else {
        FoniaText("Fără cuvinte de umplutură.", style = ui(16, UIWeight.semibold, TextRole.body), color = Palette.accentText)
    }
    if (said.repeats.isNotEmpty()) {
        FoniaText(
            "Repetate: " + said.repeats.take(4).joinToString(", ") { "„${it.text}”" },
            style = ui(15, UIWeight.medium, TextRole.footnote),
            color = Palette.inkSoft,
        )
    }
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val lines = androidx.compose.runtime.remember(said) { said.lines() }
    StyledButton(onClick = { open = !open }, style = tech.fonia.design.PressableStyle()) {
        FoniaText(if (open) "Ascunde textul" else "Vezi textul", style = ui(16, UIWeight.bold, TextRole.body), color = Palette.accentText)
    }
    if (open) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (line in lines) {
                StyledButton(onClick = { onLine(line) }, style = tech.fonia.design.PressableStyle()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FoniaText(clockText(line.start), style = ui(14, UIWeight.medium, TextRole.footnote), color = Palette.inkSoft)
                        FoniaText(line.text, style = ui(16, UIWeight.regular, TextRole.body), color = Palette.ink)
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    FoniaText(title, Modifier.semantics { heading() }.padding(top = 6.dp), style = ui(20, UIWeight.bold, TextRole.title3), color = Palette.ink)
}

private fun clockText(seconds: Double): String {
    val s = seconds.toInt().coerceAtLeast(0)
    return "${s / 60}:${"%02d".format(s % 60)}"
}

/** The one thing to work on: the area's living picture, the title and the tip, and „Încă o dată” right there. */
@Composable
private fun FocusCard(report: CoachReport, onAgain: () -> Unit) {
    val focus = report.focus
    val area = focus.area
    val shape = remember { ContinuousRoundedRectangle(30.dp) }
    val score = area?.let { report.scores[it] } ?: 100
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.sheet, shape)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LivingPicture(area, score / 100.0, Modifier.size(132.dp))
        FoniaText(
            if (area == null) "Data viitoare" else "De lucrat data viitoare",
            style = ui(14, UIWeight.semibold, TextRole.footnote),
            color = Palette.inkSoft,
        )
        FoniaText(focus.title, style = ui(28, UIWeight.black, TextRole.title), color = Palette.ink, textAlign = TextAlign.Center)
        FoniaText(focus.tip, style = ui(21, UIWeight.medium, TextRole.title3), color = Palette.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        StyledButton(onClick = onAgain, style = FoniButtonStyle(field = Field.paper), modifier = Modifier.fillMaxWidth()) {
            tech.fonia.design.CTALabel("Încă o dată")
        }
    }
}

/**
 * The talk from start to end: three lanes (pace; the voice; the camera) with the moments as soft bars. A tap plays
 * that moment of his take. The legend below names what was found, with counts. The „ăăă” and the long pauses have
 * their own strip ([PauseStrip]).
 */
@Composable
private fun Timeline(report: CoachReport, onMoment: (Moment) -> Unit) {
    val measurer = rememberTextMeasurer()
    val small = remember { ui(12, UIWeight.semibold, TextRole.caption2).copy(color = Palette.inkSoft) }
    val shape = remember { ContinuousRoundedRectangle(24.dp) }
    val total = max(report.seconds, 1.0)
    val moments = remember(report) { report.moments.filter { it.kind !in FluencyStrip.KINDS } }
    Column(
        Modifier.fillMaxWidth().background(Palette.sheet, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(122.dp)
                .pointerInput(moments) {
                    detectTapGestures { p ->
                        val left = 52.dp.toPx()
                        val t = (p.x - left) / (size.width - left) * total
                        moments.minByOrNull { m -> if (t in m.start..m.end) 0.0 else minOf(abs(t - m.start), abs(t - m.end)) }
                            ?.takeIf { m -> t in (m.start - 2)..(m.end + 2) }
                            ?.let(onMoment)
                    }
                }
                .semantics { contentDescription = "Linia vorbirii, cu momentele găsite. Atinge un moment ca să-l asculți." },
        ) {
            val left = 52.dp.toPx()
            val width = size.width - left
            fun x(t: Double) = left + (t / total).toFloat().coerceIn(0f, 1f) * width
            val laneH = 18.dp.toPx()
            val lanes = listOf("ritm", "voce", "cameră")
            fun laneY(i: Int) = 6.dp.toPx() + i * (laneH + 10.dp.toPx())
            val axisY = laneY(3) + 4.dp.toPx()
            lanes.forEachIndexed { i, name ->
                val l = measurer.measure(name, small)
                drawText(l, topLeft = Offset(0f, laneY(i) + laneH / 2 - l.size.height / 2f))
                drawRoundRect(Palette.cloud, Offset(left, laneY(i)), Size(width, laneH), CornerRadius(laneH / 2))
            }
            for (m in moments) {
                val color = m.kind.color()
                val lane = when (m.kind) {
                    MomentKind.TooFast, MomentKind.TooSlow, MomentKind.LongPause, MomentKind.Filler -> 0
                    MomentKind.Quiet, MomentKind.Flat -> 1
                    MomentKind.LookedAway, MomentKind.Moving -> 2
                }
                val y = laneY(lane)
                drawRoundRect(color.copy(alpha = 0.92f), Offset(x(m.start), y), Size(max(x(m.end) - x(m.start), 6.dp.toPx()), laneH), CornerRadius(laneH / 2))
            }
            // Time axis.
            drawLine(Palette.line, Offset(left, axisY), Offset(size.width, axisY), 2.dp.toPx(), StrokeCap.Round)
            val step = if (total > 90) 30.0 else if (total > 40) 15.0 else 10.0
            var t = 0.0
            while (t <= total + 0.01) {
                val label = measurer.measure(clockText(t), small)
                val lx = (x(t) - label.size.width / 2f).coerceIn(left, size.width - label.size.width)
                drawLine(Palette.line, Offset(x(t), axisY - 4.dp.toPx()), Offset(x(t), axisY + 4.dp.toPx()), 2.dp.toPx())
                drawText(label, topLeft = Offset(lx, axisY + 6.dp.toPx()))
                t += step
            }
        }
        val kinds = moments.groupBy { it.kind }
        if (kinds.isEmpty()) {
            FoniaText("Nimic de semnalat. Așa!", style = ui(17, UIWeight.semibold, TextRole.body), color = Palette.accentText)
        } else {
            tech.fonia.design.WrapLayout(spacing = 8.dp, lineSpacing = 8.dp) {
                for ((kind, list) in kinds.toSortedMap(compareBy { it.ordinal })) {
                    Row(
                        Modifier
                            .background(Palette.cloud, RoundedCornerShape(50))
                            .clickable(role = Role.Button) { onMoment(list.first()) }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                            .clearAndSetSemantics { contentDescription = "${kind.label}, de ${list.size} ori. Atinge ca să asculți." },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Box(Modifier.size(10.dp).background(kind.color(), CircleShape))
                        FoniaText(if (list.size > 1) "${kind.label} ×${list.size}" else kind.label, style = ui(14, UIWeight.medium, TextRole.footnote), color = Palette.ink)
                    }
                }
            }
        }
    }
}

/** The colour of a mark on the strip: the „ăăă” the sound showed, a filler word, a long pause. */
private fun Moment.stripColor(): Color = when {
    kind == MomentKind.LongPause -> Palette.lineStrong
    word != null -> Palette.cold
    else -> Palette.today
}

/**
 * One strip the length of the talk: the long pauses as soft bars on it, the „ăăă” (and the filler words, when the
 * words were written down) as dots above, where they happened. A tap plays that moment. Below: the legend, the pauses
 * counted by length, and one line on them. TalkBack reads the strip as a sentence ([FluencyStrip.describe]).
 */
@Composable
private fun PauseStrip(report: CoachReport, spec: CoachSpec, onMoment: (Moment) -> Unit) {
    val measurer = rememberTextMeasurer()
    val small = remember { ui(12, UIWeight.semibold, TextRole.caption2).copy(color = Palette.inkSoft) }
    val shape = remember { ContinuousRoundedRectangle(24.dp) }
    val total = max(report.seconds, 1.0)
    val marks = remember(report) { FluencyStrip.marks(report) }
    val groups = remember(report) { FluencyStrip.groups(report) }
    val spoken = remember(report) { FluencyStrip.describe(report) }
    Column(
        Modifier.fillMaxWidth().background(Palette.sheet, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(62.dp)
                .pointerInput(marks) {
                    detectTapGestures { p ->
                        val t = p.x / size.width * total
                        marks.minByOrNull { m -> if (t in m.start..m.end) 0.0 else minOf(abs(t - m.start), abs(t - m.end)) }
                            ?.takeIf { m -> t in (m.start - 2)..(m.end + 2) }
                            ?.let(onMoment)
                    }
                }
                .semantics { contentDescription = spoken },
        ) {
            fun x(t: Double) = (t / total).toFloat().coerceIn(0f, 1f) * size.width
            val dotY = 7.dp.toPx()
            val trackY = 22.dp.toPx()
            val trackH = 14.dp.toPx()
            drawRoundRect(Palette.cloud, Offset(0f, trackY), Size(size.width, trackH), CornerRadius(trackH / 2))
            for (m in marks.filter { it.kind == MomentKind.LongPause }) {
                drawRoundRect(
                    m.stripColor().copy(alpha = 0.55f),
                    Offset(x(m.start), trackY),
                    Size(max(x(m.end) - x(m.start), 6.dp.toPx()), trackH),
                    CornerRadius(trackH / 2),
                )
            }
            for (m in marks.filter { it.kind == MomentKind.Filler }) {
                val color = m.stripColor()
                val cx = x((m.start + m.end) / 2)
                drawLine(color.copy(alpha = 0.45f), Offset(cx, dotY), Offset(cx, trackY + trackH / 2), 2.dp.toPx(), StrokeCap.Round)
                drawCircle(color, 5.dp.toPx(), Offset(cx, dotY))
            }
            val start = measurer.measure(clockText(0.0), small)
            val end = measurer.measure(clockText(report.seconds), small)
            drawText(start, topLeft = Offset(0f, trackY + trackH + 6.dp.toPx()))
            drawText(end, topLeft = Offset(size.width - end.size.width, trackY + trackH + 6.dp.toPx()))
        }
        if (groups.isEmpty()) {
            FoniaText(spoken, style = ui(17, UIWeight.semibold, TextRole.body), color = Palette.accentText)
        } else {
            tech.fonia.design.WrapLayout(spacing = 8.dp, lineSpacing = 8.dp) {
                for ((label, list) in groups) {
                    Row(
                        Modifier
                            .background(Palette.cloud, RoundedCornerShape(50))
                            .clickable(role = Role.Button) { onMoment(list.first()) }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                            .clearAndSetSemantics { contentDescription = "$label, ${UsualText.times(list.size)}. Atinge ca să asculți." },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Box(Modifier.size(10.dp).background(list.first().stripColor(), CircleShape))
                        FoniaText(if (list.size > 1) "$label ×${list.size}" else label, style = ui(14, UIWeight.medium, TextRole.footnote), color = Palette.ink)
                    }
                }
            }
        }
        report.pauses?.let { p ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val counts = listOf(p.short, p.medium, p.long)
                UsualText.pauseKinds(spec).forEachIndexed { i, (name, limits) ->
                    Column(
                        Modifier
                            .weight(1f)
                            .background(Palette.cloud, RoundedCornerShape(16.dp))
                            .padding(vertical = 8.dp)
                            .clearAndSetSemantics { contentDescription = "Pauze $name, $limits: ${counts[i]}" },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        FoniaText("${counts[i]}", style = ui(22, UIWeight.black, TextRole.title2), color = Palette.ink)
                        FoniaText(name, style = ui(14, UIWeight.semibold, TextRole.footnote), color = Palette.ink)
                        FoniaText(limits, style = ui(12, UIWeight.medium, TextRole.caption2), color = Palette.inkSoft, maxLines = 1, minimumScaleFactor = 0.8f)
                    }
                }
            }
            UsualText.pauses(p)?.let { FoniaText(it, style = ui(16, UIWeight.semibold, TextRole.body), color = Palette.ink) }
        }
    }
}

/** This talk against his own habit: one line per measure, with his numbers and the general reference. */
@Composable
private fun UsualCard(usual: AgainstUsual, spec: CoachSpec) {
    val shape = remember { ContinuousRoundedRectangle(24.dp) }
    Column(
        Modifier.fillMaxWidth().background(Palette.sheet, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        usual.pace?.let { v ->
            UsualLine(Area.Pace.color(), UsualText.pace(v), UsualText.paceDetail(usual.nowPace, usual.usualPace, spec))
        }
        usual.pitchRange?.let { v ->
            UsualLine(Area.Lively.color(), UsualText.pitchRange(v), UsualText.pitchRangeDetail(usual.nowPitchRangeSt, usual.usualPitchRangeSt))
        }
    }
}

@Composable
private fun UsualLine(color: Color, line: String, detail: String) {
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "$line $detail" },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.padding(top = 7.dp).size(10.dp).background(color, CircleShape))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FoniaText(line, style = ui(17, UIWeight.semibold, TextRole.body), color = Palette.ink)
            FoniaText(detail, style = ui(14, UIWeight.medium, TextRole.footnote), color = Palette.inkSoft)
        }
    }
}

/** The five scores, each a small living picture with its number and the change since last time. */
@Composable
private fun Scores(report: CoachReport) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (area in Area.entries) {
            val score = report.scores[area]
            val delta = report.changes[area]
            Column(
                Modifier
                    .weight(1f)
                    .background(Palette.sheet, RoundedCornerShape(20.dp))
                    .padding(vertical = 10.dp)
                    .clearAndSetSemantics {
                        contentDescription = "${area.title}: ${score ?: "nemăsurat"}" + (delta?.let { ", ${if (it >= 0) "+" else ""}$it față de data trecută" } ?: "")
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                LivingPicture(area, (score ?: 0) / 100.0, Modifier.size(54.dp), dimmed = score == null)
                FoniaText(score?.toString() ?: "–", style = ui(22, UIWeight.black, TextRole.title2), color = Palette.ink)
                FoniaText(area.title, style = ui(13, UIWeight.semibold, TextRole.caption), color = Palette.inkSoft, maxLines = 1, minimumScaleFactor = 0.8f)
                val d = delta
                FoniaText(
                    when {
                        d == null -> " "
                        d > 0 -> "+$d"
                        d < 0 -> "−${-d}"
                        else -> "="
                    },
                    style = ui(13, UIWeight.bold, TextRole.caption),
                    color = if (d != null && d > 0) Palette.accentText else Palette.inkSoft,
                )
            }
        }
    }
}

/**
 * A small animated picture of an area at [value] (0…1): Ritm = beats that fall in step (or stumble), Voce = sound
 * rings that reach far (or not), Privire = an eye whose pupil finds the lens, Calm = a plumb line that hangs still (or
 * swings), Viu = a wave that moves a lot (or a little). [area] null = all good: a sun of the five colours.
 */
@Composable
internal fun LivingPicture(area: Area?, value: Double, modifier: Modifier, dimmed: Boolean = false) {
    val reduce = LocalReduceMotion.current
    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(reduce) {
        if (reduce) return@LaunchedEffect
        while (isActive) androidx.compose.runtime.withFrameNanos { tick.longValue = it }
    }
    val v = value.coerceIn(0.0, 1.0).toFloat()
    Canvas(modifier) {
        val t = if (reduce) 0.4f else (tick.longValue / 1e9 % 1000).toFloat()
        val color = (area?.color() ?: Palette.accent).copy(alpha = if (dimmed) 0.3f else 1f)
        val soft = color.copy(alpha = 0.16f)
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(soft, r, c)
        when (area) {
            Area.Pace -> beats(c, r, v, t, color)
            Area.Voice -> rings(c, r, v, t, color)
            Area.Gaze -> eye(c, r, v, t, color)
            Area.Calm -> plumb(c, r, v, t, color)
            Area.Lively -> wave(c, r, v, t, color)
            null -> {
                Area.entries.forEachIndexed { i, a ->
                    val ang = (i / 5f) * 2 * PI.toFloat() + t * 0.4f
                    drawCircle(a.color(), r * 0.17f * (1 + 0.12f * sin(t * 3 + i)), Offset(c.x + r * 0.55f * kotlin.math.cos(ang), c.y + r * 0.55f * sin(ang)))
                }
                drawCircle(Palette.accent, r * 0.22f, c)
            }
        }
    }
}

private fun DrawScope.beats(c: Offset, r: Float, v: Float, t: Float, color: Color) {
    val n = 5
    for (i in 0 until n) {
        // In step: the same phase gap; out of step: a jitter that grows as the score falls.
        val jitter = (1 - v) * 0.45f * sin(i * 2.7f + t * 0.9f)
        val ph = t * 2.6f - i * 0.55f + jitter * 3
        val h = (0.5f + 0.5f * sin(ph)).let { it * it }
        val x = c.x - r * 0.56f + i * r * 0.28f
        val y = c.y + r * 0.28f - h * r * 0.5f
        drawCircle(color, r * 0.1f, Offset(x, y))
    }
    drawLine(color.copy(alpha = 0.4f), Offset(c.x - r * 0.65f, c.y + r * 0.42f), Offset(c.x + r * 0.65f, c.y + r * 0.42f), r * 0.05f, StrokeCap.Round)
}

private fun DrawScope.rings(c: Offset, r: Float, v: Float, t: Float, color: Color) {
    val src = Offset(c.x - r * 0.45f, c.y)
    drawCircle(color, r * 0.14f, src)
    val reach = 0.35f + 0.65f * v
    for (k in 0 until 3) {
        val p = ((t * 0.7f + k / 3f) % 1f)
        val rr = r * (0.25f + p * 1.05f * reach)
        val a = (1 - p) * (0.35f + 0.65f * v)
        drawArc(color.copy(alpha = a), -50f, 100f, false, Offset(src.x - rr, src.y - rr), Size(rr * 2, rr * 2), style = Stroke(r * 0.08f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.eye(c: Offset, r: Float, v: Float, t: Float, color: Color) {
    val w = r * 0.78f
    val h = r * 0.46f
    val lid = Path().apply {
        moveTo(c.x - w, c.y)
        quadraticTo(c.x, c.y - h * 1.6f, c.x + w, c.y)
        quadraticTo(c.x, c.y + h * 1.6f, c.x - w, c.y)
        close()
    }
    drawPath(lid, Color.White)
    drawPath(lid, color, style = Stroke(r * 0.07f))
    // The pupil wanders off the lens as the score falls.
    val off = (1 - v) * r * 0.32f
    val p = Offset(c.x + off * sin(t * 0.8f), c.y + off * 0.6f + off * 0.2f * sin(t * 1.3f))
    drawCircle(color, r * 0.22f, p)
    drawCircle(Color.White.copy(alpha = 0.9f), r * 0.07f, Offset(p.x - r * 0.07f, p.y - r * 0.07f))
}

private fun DrawScope.plumb(c: Offset, r: Float, v: Float, t: Float, color: Color) {
    val angle = (1 - v) * 28f * sin(t * 2.2f)
    val pivot = Offset(c.x, c.y - r * 0.62f)
    rotate(angle, pivot) {
        drawLine(color, pivot, Offset(c.x, c.y + r * 0.35f), r * 0.06f, StrokeCap.Round)
        drawCircle(color, r * 0.18f, Offset(c.x, c.y + r * 0.42f))
    }
    drawCircle(color, r * 0.06f, pivot)
}

private fun DrawScope.wave(c: Offset, r: Float, v: Float, t: Float, color: Color) {
    val path = Path()
    val amp = r * (0.06f + 0.36f * v)
    var x = c.x - r * 0.7f
    var first = true
    while (x <= c.x + r * 0.7f) {
        val u = (x - (c.x - r * 0.7f)) / (r * 1.4f)
        val y = c.y + amp * sin(u * 3 * PI.toFloat() * 2 + t * 3f) * sin(u * PI.toFloat())
        if (first) path.moveTo(x, y) else path.lineTo(x, y)
        first = false
        x += r * 0.04f
    }
    drawPath(path, color, style = Stroke(r * 0.09f, cap = StrokeCap.Round))
}


/** The last talks, per area, as small bars growing left to right (the newest is the last): the trend at a glance. */
@Composable
private fun Trend(talks: List<tech.fonia.speaking.coach.CoachScores>) {
    val shown = talks.takeLast(5)
    if (shown.size < 2) {
        // The first talk has nothing to stand next to yet: one quiet line says when the trend shows up.
        FoniaText("După încă o încercare vezi aici cum se schimbă.", style = ui(15, UIWeight.medium, TextRole.footnote), color = Palette.inkSoft)
        return
    }
    Section("Ultimele ${shown.size}")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (area in Area.entries) {
            val values = shown.map { it[area] }
            if (values.count { it != null } < 2) continue
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FoniaText(area.title, Modifier.widthIn(min = 74.dp), style = ui(15, UIWeight.semibold, TextRole.footnote), color = Palette.inkSoft)
                Row(Modifier.height(30.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    values.forEachIndexed { i, v ->
                        val last = i == values.lastIndex
                        Box(
                            Modifier
                                .size(width = 16.dp, height = (4 + 26 * ((v ?: 0) / 100f)).dp)
                                .background(if (v == null) Palette.cloud else if (last) Palette.progress else Palette.progress.copy(alpha = 0.4f), RoundedCornerShape(5.dp)),
                        )
                    }
                }
                val first = values.firstOrNull { it != null }
                val now = values.last()
                if (first != null && now != null && now != first) {
                    FoniaText((if (now > first) "+" else "") + (now - first), style = ui(15, UIWeight.bold, TextRole.footnote), color = if (now > first) Palette.accentText else Palette.inkSoft)
                }
            }
        }
    }
}
