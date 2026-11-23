package tech.fonia.features.reading

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.fonia.design.CTALabel
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.QuietButtonStyle
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.fBody
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fCaption
import tech.fonia.design.monospacedDigit
import tech.fonia.design.ui
import tech.fonia.features.app.ActionRole
import tech.fonia.features.app.DialogAction
import tech.fonia.features.app.FoniaAlert
import tech.fonia.features.app.HeaderButton
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlin.math.max

private val romanian = Locale.forLanguageTag("ro-RO")

/** "3 nov.": a point's date under the curve. */
internal fun shortDay(date: LocalDate): String = DateTimeFormatter.ofPattern("d MMM", romanian).format(date)

/** "3 noiembrie". */
internal fun longDay(date: LocalDate): String = DateTimeFormatter.ofPattern("d MMMM", romanian).format(date)

/**
 * The child's own curve: words correct per minute at each probe of one kind, oldest on the left, spaced by date.
 * Nothing else is drawn on it, the published band included, so the first thing an adult sees is the child against
 * the child (03 §1.3 C1).
 */
@Composable
internal fun ReadingCurve(records: List<ProbeRecord>, modifier: Modifier = Modifier, highlight: UUID? = null, height: Dp = 170.dp) {
    val measurer = rememberTextMeasurer()
    val values = records.map { it.score.wcpmShown }
    val description = when (records.size) {
        0 -> "Încă nicio probă"
        1 -> "O probă, ${wordsPerMinute(values[0])}"
        else -> "De la ${values.first()} la ${values.last()} cuvinte corecte pe minut, în ${records.size} probe"
    }
    val labelStyle = ui(12, UIWeight.medium, TextRole.caption).copy(color = Palette.inkSoft)
    val valueStyle = ui(13, UIWeight.bold, TextRole.caption).monospacedDigit().copy(color = Palette.ink)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        if (records.isEmpty()) return@Canvas
        val ceiling = max(60, ((values.max() + 10 + 19) / 20) * 20)
        val left = 30.dp.toPx()
        val right = size.width - 14.dp.toPx()
        val top = 20.dp.toPx()
        val bottom = size.height - 24.dp.toPx()
        fun y(value: Int) = bottom - (bottom - top) * value / ceiling
        var tick = 0
        while (tick <= ceiling) {
            val ty = y(tick)
            drawLine(Palette.line, Offset(left, ty), Offset(right, ty), strokeWidth = 1.dp.toPx())
            val label = measurer.measure("$tick", labelStyle)
            drawText(label, topLeft = Offset(left - 6.dp.toPx() - label.size.width, ty - label.size.height / 2))
            tick += 20
        }
        val first = records.first().day
        val span = ChronoUnit.DAYS.between(first, records.last().day)
        fun x(record: ProbeRecord): Float =
            if (span == 0L) (left + right) / 2 else left + (right - left) * ChronoUnit.DAYS.between(first, record.day) / span
        // Probes on the same day sit side by side, a little apart, rather than on top of each other.
        val xs = records.mapIndexed { index, record ->
            val sameDayBefore = records.take(index).count { it.day == record.day }
            x(record) + sameDayBefore * 10.dp.toPx()
        }
        if (records.size > 1) {
            val path = Path()
            records.indices.forEach { index -> if (index == 0) path.moveTo(xs[index], y(values[index])) else path.lineTo(xs[index], y(values[index])) }
            drawPath(path, Palette.accent, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        records.forEachIndexed { index, record ->
            val center = Offset(xs[index], y(values[index]))
            val isHighlighted = record.id == highlight || (highlight == null && index == records.lastIndex)
            drawCircle(Palette.accent, radius = (if (isHighlighted) 7 else 5).dp.toPx(), center = center)
            drawCircle(Palette.sheet, radius = (if (isHighlighted) 3 else 2).dp.toPx(), center = center)
            if (records.size <= 8 || index == 0 || isHighlighted) {
                val label = measurer.measure("${values[index]}", valueStyle)
                drawText(label, topLeft = Offset(center.x - label.size.width / 2, center.y - 9.dp.toPx() - label.size.height))
            }
        }
        val firstLabel = measurer.measure(shortDay(first), labelStyle)
        drawText(firstLabel, topLeft = Offset(if (span == 0L) (left + right) / 2 - firstLabel.size.width / 2 else left, size.height - firstLabel.size.height))
        if (span > 0L) {
            val lastLabel = measurer.measure(shortDay(records.last().day), labelStyle)
            drawText(lastLabel, topLeft = Offset(right - lastLabel.size.width, size.height - lastLabel.size.height))
        }
    }
}

/** What the adult reads under the curve: numbers only, never a judgement. */
internal fun trendSentence(records: List<ProbeRecord>): String {
    if (records.isEmpty()) return ""
    val first = records.first()
    val last = records.last()
    if (records.size == 1) {
        return "E prima probă de felul acesta. Repet-o peste câteva săptămâni, cu celălalt set, ca să se vadă curba."
    }
    return "De la ${first.score.wcpmShown} (${longDay(first.day)}) la ${last.score.wcpmShown} (${longDay(last.day)}) " +
        "cuvinte corecte pe minut, în ${records.size} probe."
}

/**
 * After a probe: the child's own curve first, then this probe's numbers, then the published band as a band, with its
 * source in plain sight. Never "risc", never a level.
 */
@Composable
internal fun ProbeResults(record: ProbeRecord, history: List<ProbeRecord>, onDone: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    val score = record.score
    BackHandler(onBack = onDone)
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.paper)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Box(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
            FoniaText("Rezultatul", Modifier.semantics { heading() }, style = ui(17, UIWeight.semibold), color = Palette.ink)
            Row(Modifier.fillMaxWidth()) { HeaderButton(closes = true, onClick = onDone) }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ResultCard {
                FoniaText(record.kind.title, style = ui(16, UIWeight.bold, TextRole.headline), color = Palette.ink)
                FoniaText("Cum citește de la o probă la alta", style = fCaption, color = Palette.inkSoft)
                ReadingCurve(history, Modifier.padding(top = 4.dp), highlight = record.id)
                FoniaText(trendSentence(history), style = fBody, color = Palette.ink)
            }
            ResultCard {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FoniaText("${score.wcpmShown}", style = ui(46, UIWeight.black, TextRole.largeTitle).monospacedDigit(), color = Palette.ink)
                    FoniaText(
                        if (score.wcpmShown == 1) "cuvânt corect pe minut" else "cuvinte corecte pe minut",
                        Modifier.padding(bottom = 9.dp),
                        style = fBodyStrong,
                        color = Palette.ink,
                    )
                }
                val cells = listOf(
                    "Corectitudine" to "${score.accuracyPercent}%",
                    "Silabe pe secundă" to oneDecimal(score.syllablesPerSecond),
                    "Cuvinte citite" to "${score.wordsRead}",
                    "Citite altfel" to "${score.errors}",
                    "Timp" to clockText(record.seconds),
                    "Așezare" to record.preset.title,
                )
                for (pair in cells.chunked(2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for ((label, value) in pair) Fact(label, value, Modifier.weight(1f))
                    }
                }
            }
            ResultCard {
                val position = PeafcNorms.position(record.kind, score.wcpm)
                FoniaText("Reper din studiu, orientativ", style = fCaption, color = Palette.inkSoft)
                FoniaText("${wordsPerMinute(score.wcpmShown)}: ${PeafcNorms.phrase(position)}.", style = fBodyStrong, color = Palette.ink)
                FoniaText("Banda: ${PeafcNorms.range(record.kind)}.", style = fCaption, color = Palette.ink)
                FoniaText(
                    "Sursa: ${PeafcNorms.SOURCE_SHORT}. Listele Fonia sunt altele decât cele din studiu, deci banda e doar un reper. Contează mai mult cum se schimbă de la o lună la alta.",
                    style = fCaption,
                    color = Palette.inkSoft,
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StyledButton(onClick = onDone, style = FoniButtonStyle()) { CTALabel("Gata") }
            StyledButton(onClick = { confirmDelete = true }, style = QuietButtonStyle(Palette.inkSoft)) { FoniaText("Șterge proba") }
        }
    }
    FoniaAlert(
        isPresented = confirmDelete,
        onDismiss = { confirmDelete = false },
        title = "Ștergi proba?",
        message = "Nu mai apare pe curbă și nici în raport.",
        actions = listOf(
            DialogAction("Anulează", ActionRole.Cancel),
            DialogAction("Șterge", ActionRole.Destructive) { onDelete() },
        ),
    )
}

@Composable
private fun ResultCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.sheet, ContinuousRoundedRectangle(22.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun Fact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .heightIn(min = 60.dp)
            .background(Palette.cloud, ContinuousRoundedRectangle(14.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .clearAndSetSemantics { contentDescription = "$label: $value" },
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        FoniaText(value, style = ui(19, UIWeight.black, TextRole.title3).monospacedDigit(), color = Palette.ink, maxLines = 1, minimumScaleFactor = 0.7f)
        FoniaText(label, style = fCaption, color = Palette.inkSoft, maxLines = 1, minimumScaleFactor = 0.8f, textAlign = TextAlign.Start)
    }
}
