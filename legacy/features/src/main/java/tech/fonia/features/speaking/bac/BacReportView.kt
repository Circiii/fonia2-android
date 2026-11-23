package tech.fonia.features.speaking.bac

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.fonia.audio.CapturedClip
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.Field
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaLabel
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.PillButtonStyle
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.WrapLayout
import tech.fonia.design.fBody
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fCaption
import tech.fonia.design.fDisplay
import tech.fonia.design.fLead
import tech.fonia.design.ui
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.SystemColors
import tech.fonia.features.speaking.MeasureBlock
import tech.fonia.features.speaking.ReadStatus
import tech.fonia.features.speaking.SpeakingHeading
import tech.fonia.features.speaking.WordFlow
import tech.fonia.features.speaking.WordPaint

/**
 * The report of a simulation: indicators with their references, the words worth a listen, the answers as heard, the
 * opinion's connectors and the argument count the reader can correct. Never a score or a level.
 */
@Composable
fun BacReportView(
    report: BacReport,
    levels: List<BacLevel>,
    levelsSource: String?,
    /** The reader's own count of arguments, when they changed it. */
    marked: Int?,
    onMark: (Int?) -> Unit,
    app: AppModel,
    /** The reading, for listening to it and to single words. */
    readingClip: CapturedClip?,
    onAnother: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        FoniaText("Raportul de exersare", style = fDisplay, color = Palette.ink)
        FoniaText("Biletul nr. ${report.ticket.number}: ${report.ticket.title}", style = fLead, color = Palette.inkSoft)
        Row(
            Modifier
                .fillMaxWidth()
                .background(Palette.coldSoft, ContinuousRoundedRectangle(16.dp))
                .padding(14.dp)
                .testTag("grade-note"),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FoniaIconView(FoniaIcon.people, size = 22f, tint = Palette.coldDeep)
            FoniaText(BacReportText.GRADE_NOTE, style = fBodyStrong, color = Palette.coldDeep)
        }

        val reading = report.reading
        if (reading != null) {
            SpeakingHeading("Citirea")
            for (indicator in BacReportText.readingIndicators(report)) {
                MeasureBlock(indicator.title, indicator.value, indicator.reference, indicator.note)
                if (indicator.title == "Cuvintele-cheie") KeyWords(report)
            }
            reading.check?.let { check ->
                val toListen = check.toListen
                if (toListen.isNotEmpty()) {
                    FoniaText("Cuvinte de ascultat", style = fBodyStrong, color = Palette.ink)
                    FoniaText("Pe gri, citite altfel sau sărite. Pe galben, cuvinte la care nu am fost sigur. Atinge unul ca să-l asculți.", style = fCaption, color = Palette.inkSoft)
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Palette.sheet, ContinuousRoundedRectangle(16.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (paragraph in report.ticket.text.paragraphs) {
                        WordFlow(
                            paragraph,
                            style = ui(17, UIWeight.regular, TextRole.body),
                            paint = { word ->
                                val read = check.words.getOrNull(word.index)
                                WordPaint(
                                    color = if (word.isKey) Palette.accentText else Palette.ink,
                                    bold = word.isKey,
                                    underline = if (word.isKey) Palette.accent else null,
                                    fill = when (read?.status) {
                                        ReadStatus.DIFFERENT -> Palette.notYetSoft
                                        ReadStatus.UNSURE -> Palette.tipSoft
                                        else -> null
                                    },
                                    state = when (read?.status) {
                                        ReadStatus.DIFFERENT -> read.heard?.let { "citit altfel: am auzit $it" } ?: "nu l-am găsit"
                                        ReadStatus.UNSURE -> "nesigur"
                                        else -> null
                                    },
                                )
                            },
                            tapLabel = "Ascultă cuvântul",
                            onTap = readingClip?.let { clip ->
                                { word ->
                                    check.words.getOrNull(word.index)?.span?.let { span ->
                                        app.scope.launch { app.audio.play(clip, (span.start - 0.15).coerceAtLeast(0.0), span.end + 0.15) }
                                    }
                                    Unit
                                }
                            },
                        )
                    }
                }
            }
            if (readingClip != null) {
                StyledButton(onClick = { app.scope.launch { app.audio.play(readingClip) } }, style = PillButtonStyle()) {
                    FoniaLabel("Ascultă citirea", FoniaIcon.play, iconSize = 16f)
                }
            }
        }

        for (answer in report.answers) {
            SpeakingHeading(if (answer.part == BacPart.Opinion) "Cerința 3: opinia" else answer.part.title)
            FoniaText(report.ticket.tasks[answer.part.task - 1], style = fCaption, color = Palette.inkSoft)
            for (indicator in BacReportText.answerIndicators(answer)) {
                MeasureBlock(indicator.title, indicator.value, indicator.reference, indicator.note)
            }
            if (answer.part == BacPart.Opinion) {
                report.arguments?.let { count -> ArgumentsBlock(count, marked, onMark) }
            }
            val heard = answer.transcript?.text
            if (!heard.isNullOrBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FoniaText("Ce am auzit", style = fBodyStrong, color = Palette.ink)
                    FoniaText(heard, style = fBody, color = Palette.inkSoft)
                }
            }
        }

        SpeakingHeading("Ce urmăresc profesorii")
        FoniaText(
            "Indicatorii de mai sus țin de citirea corectă, cursivă, conștientă și expresivă și de argumentare. Nivelul îl stabilesc profesorii, după grila lor.",
            style = fBody,
            color = Palette.ink,
        )
        for (level in levels) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FoniaText(level.name.replaceFirstChar { it.uppercase() }, style = fBodyStrong, color = Palette.ink)
                FoniaText(level.description.replaceFirstChar { it.uppercase() } + ".", style = fBody, color = Palette.inkSoft)
            }
        }
        levelsSource?.let { FoniaText(it, style = fCaption, color = Palette.inkSoft) }
        FoniaText(BacReportText.REFERENCE_NOTE, style = fCaption, color = Palette.inkSoft)
        StyledButton(onClick = onAnother, style = FoniButtonStyle(field = Field.paper, kind = FoniButtonStyle.Kind.secondary)) { FoniaText("Alt bilet") }
        StyledButton(onClick = onClose, style = FoniButtonStyle(field = Field.paper)) { FoniaText("Gata") }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun KeyWords(report: BacReport) {
    val lines = BacReportText.keyWordLines(report)
    if (lines.isEmpty()) return
    WrapLayout(spacing = 8.dp, lineSpacing = 8.dp) {
        for ((word, state) in lines) {
            val stood = state == "a ieșit în față"
            Row(
                Modifier
                    .background(if (stood) Palette.rightSoft else Palette.cloud, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 7.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "$word: $state" },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (stood) FoniaIconView(FoniaIcon.tick, size = 13f, tint = Palette.right)
                FoniaText(word, style = ui(14, UIWeight.bold, TextRole.footnote), color = if (stood) Palette.rightDeep else Palette.ink)
                FoniaText(state, style = fCaption, color = if (stood) Palette.rightDeep else Palette.inkSoft)
            }
        }
    }
}

/** The arguments: the count, which the reader can correct, then the connectors heard, by role. */
@Composable
private fun ArgumentsBlock(count: ArgumentCount, marked: Int?, onMark: (Int?) -> Unit) {
    val indicator = BacReportText.argumentIndicator(count, marked)
    MeasureBlock(indicator.title, indicator.value, indicator.reference)
    val shown = marked ?: count.arguments
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FoniaText("Le-ai numărat altfel?", Modifier.weight(1f), style = fBodyStrong, color = Palette.ink)
        Row(
            Modifier
                .width(110.dp)
                .height(36.dp)
                .background(SystemColors.fill.copy(alpha = 0.5f), RoundedCornerShape(50)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepHalf("−", "Mai puține argumente", enabled = shown > 0) { onMark(shown - 1) }
            FoniaText("$shown", Modifier.width(30.dp), style = fBodyStrong, color = Palette.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            StepHalf("+", "Mai multe argumente", enabled = shown < 9) { onMark(shown + 1) }
        }
    }
    for (line in BacReportText.structureLines(count)) FoniaText(line, style = fBody, color = Palette.ink)
    val roles = ConnectorRole.entries.filter { count.phrases(it).isNotEmpty() }
    if (roles.isNotEmpty()) {
        FoniaText("Cuvinte de legătură auzite", style = fBodyStrong, color = Palette.ink)
        for (role in roles) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FoniaText(BacReportText.roleTitle(role), style = fCaption, color = Palette.inkSoft)
                WrapLayout(spacing = 8.dp, lineSpacing = 8.dp) {
                    for (phrase in count.phrases(role)) {
                        FoniaText(
                            "„$phrase”",
                            Modifier
                                .background(Palette.cloud, RoundedCornerShape(50))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            style = fCaption,
                            color = Palette.ink,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.StepHalf(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .height(36.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        FoniaText(symbol, Modifier.alpha(if (enabled) 1f else 0.3f), style = ui(20, UIWeight.medium), color = SystemColors.label)
    }
}
