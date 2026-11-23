package tech.fonia.features.reading

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import tech.fonia.design.CTALabel
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.PracticeWeight
import tech.fonia.design.QuietButtonStyle
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.fBody
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fCaption
import tech.fonia.design.fLead
import tech.fonia.design.monospacedDigit
import tech.fonia.design.practice
import tech.fonia.design.ui
import tech.fonia.features.app.ActionRole
import tech.fonia.features.app.DialogAction
import tech.fonia.features.app.FoniaAlert
import tech.fonia.features.app.HeaderButton
import tech.fonia.features.app.SegmentedPicker
import tech.fonia.features.app.ViewTask
import kotlin.math.max

/**
 * The probe itself (C1). The adult gives the instructions with the words hidden, then shows them; the clock starts at
 * the first word, from the microphone when it may listen or from the adult's tap. During the minute the adult taps the
 * words read differently or skipped (nothing marks them by itself; that is C6, later). At 60 s the clock stops and the
 * adult taps the last word read. Marks are quiet: a grey pill, never red, since the child can see the screen.
 */
@Composable
internal fun ReadingProbeScreen(
    run: ProbeRun,
    preset: ReadingPreset,
    instruction: String,
    adultRules: List<String>,
    clock: ProbeClock,
    voice: VoiceStart?,
    onClose: () -> Unit,
    onDone: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var listening by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }

    // The screen stays on for the minute: a phone that dims while a child reads would end the probe.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    ViewTask(run.phase) {
        when (run.phase) {
            ProbePhase.ready -> {
                val heard = voice?.awaitFirstVoice(clock) { listening = true }
                listening = false
                if (heard != null) run.start(StartSource.Voice, heard)
            }
            ProbePhase.running -> while (isActive && run.phase == ProbePhase.running) {
                if (run.tick()) {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    break
                }
                delay(100)
            }
            ProbePhase.prep, ProbePhase.marking -> Unit
        }
    }

    BackHandler { if (run.phase == ProbePhase.prep) onClose() else confirmLeave = true }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.paper)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        TopBar(run, onClose = { if (run.phase == ProbePhase.prep) onClose() else confirmLeave = true })
        when (run.phase) {
            ProbePhase.prep -> Instructions(run, preset, instruction, adultRules, Modifier.weight(1f))
            else -> {
                Hint(run, listening)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    Words(run, preset)
                }
                Dock(run, onDone)
            }
        }
    }

    FoniaAlert(
        isPresented = confirmLeave,
        onDismiss = { confirmLeave = false },
        title = "Oprești proba?",
        message = "Ce ai atins până acum nu se păstrează.",
        actions = listOf(
            DialogAction("Continuă", ActionRole.Cancel),
            DialogAction("Oprește", ActionRole.Destructive) { onClose() },
        ),
    )
}

@Composable
private fun TopBar(run: ProbeRun, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 4.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(horizontal = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                FoniaText(
                    run.probe.kind.title,
                    Modifier.semantics { heading() },
                    style = ui(17, UIWeight.semibold),
                    color = Palette.ink,
                    maxLines = 1,
                    minimumScaleFactor = 0.8f,
                )
                FoniaText("Setul ${run.probe.set}", style = fCaption, color = Palette.inkSoft)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HeaderButton(closes = true, onClick = onClose)
                Spacer(Modifier.weight(1f))
                if (run.phase != ProbePhase.prep) TimerPill(run.shownSeconds)
            }
        }
        if (run.phase != ProbePhase.prep) {
            MinuteBar(run.shownSeconds, Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun TimerPill(seconds: Double) {
    Box(
        Modifier
            .background(Palette.sheet, CircleShape)
            .heightIn(min = 40.dp)
            .padding(horizontal = 14.dp)
            .semantics { contentDescription = "Timp: ${seconds.toInt()} secunde din 60" },
        contentAlignment = Alignment.Center,
    ) {
        FoniaText(clockText(seconds), style = ui(17, UIWeight.bold).monospacedDigit(), color = Palette.ink)
    }
}

/** The minute as a bar that fills: calmer than a number counting down. */
@Composable
private fun MinuteBar(seconds: Double, modifier: Modifier = Modifier) {
    val share = (seconds / PROBE_SECONDS).toFloat().coerceIn(0f, 1f)
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(Palette.progressTrack, cornerRadius = radius)
        if (share > 0f) drawRoundRect(Palette.accent, size = Size(max(size.height, size.width * share), size.height), cornerRadius = radius)
    }
}

/** Before the words appear: what to say to the child, and how to mark. */
@Composable
private fun Instructions(run: ProbeRun, preset: ReadingPreset, instruction: String, adultRules: List<String>, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card {
                FoniaText("Spune-i copilului", style = fCaption, color = Palette.inkSoft)
                FoniaText(instruction, style = fLead, color = Palette.ink)
            }
            Card {
                FoniaText("Cum atingi", style = fCaption, color = Palette.inkSoft)
                for (rule in adultRules) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.padding(top = 8.dp).size(6.dp).background(Palette.accent, CircleShape))
                        FoniaText(rule, Modifier.weight(1f), style = fBody, color = Palette.ink)
                    }
                }
            }
            Card {
                FoniaText("Ceasul", style = fCaption, color = Palette.inkSoft)
                FoniaText(
                    "Pornește la primul cuvânt și se oprește singur după un minut. Dacă telefonul are voie la microfon, pornește când aude prima voce, așa că după ce arăți cuvintele lasă copilul să vorbească primul. Dacă termină mai devreme, apasă „Oprește ceasul”.",
                    style = fBody,
                    color = Palette.ink,
                )
                FoniaText("Așezarea literelor: ${preset.title}.", style = fCaption, color = Palette.inkSoft)
            }
        }
        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp)) {
            StyledButton(onClick = { run.showWords() }, style = FoniButtonStyle()) {
                CTALabel(if (run.probe.kind == ProbeKind.Text) "Arată textul" else "Arată cuvintele")
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.sheet, ContinuousRoundedRectangle(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** One line for the adult above the words: what the next tap does. */
@Composable
private fun Hint(run: ProbeRun, listening: Boolean) {
    val (title, detail) = when (run.phase) {
        ProbePhase.ready -> "Ceasul pornește la primul cuvânt." to
            if (listening) "Ascult. Pornește singur când copilul începe, sau atinge „Pornește”." else "Atinge „Pornește” când copilul citește primul cuvânt."
        ProbePhase.running -> "Atinge cuvintele citite altfel sau sărite." to
            "Încă o atingere îl lasă citit bine. Atinse: ${run.marked.size}."
        ProbePhase.marking -> (if (run.timeUp) "A trecut un minut." else "Ceasul s-a oprit la ${clockText(run.seconds)}.") to
            if (run.markMode == MarkMode.lastWord) "Atinge ultimul cuvânt citit." else "Atinge cuvintele citite altfel sau sărite."
        ProbePhase.prep -> "" to ""
    }
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .background(Palette.sheet, ContinuousRoundedRectangle(18.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (run.phase == ProbePhase.ready && listening) FoniaIconView(FoniaIcon.microphone, size = 18f, tint = Palette.accent)
            FoniaText(title, style = fBodyStrong, color = Palette.ink)
        }
        FoniaText(detail, style = fCaption, color = Palette.inkSoft)
        if (run.phase == ProbePhase.marking) {
            SegmentedPicker(
                listOf("Ultimul cuvânt", "Cuvinte atinse"),
                selected = run.markMode.ordinal,
                modifier = Modifier.padding(top = 8.dp),
            ) { run.markMode = MarkMode.entries[it] }
        }
    }
}

/** The words in the chosen setting, each one a tap target for the adult. */
@Composable
private fun Words(run: ProbeRun, preset: ReadingPreset) {
    val isText = run.probe.kind == ProbeKind.Text
    val size = if (isText) 26 else 30
    val style = practice(size, PracticeWeight.regular, TextRole.title)
    val sizeDp = with(LocalDensity.current) { style.fontSize.toDp() }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (isText) {
            FoniaText(run.probe.title, style = practice(22, PracticeWeight.bold, TextRole.title2), color = Palette.ink, tracking = preset.letterSpacing.em)
        }
        WordFlow(
            maxPerLine = preset.maxWordsPerLine,
            wordGap = sizeDp * preset.wordGap,
            lineGap = sizeDp * max(0.15f, preset.lineHeight - 1.3f),
        ) {
            run.probe.words.forEachIndexed { index, word ->
                val last = run.lastRead
                WordChip(
                    text = word.text,
                    index = index,
                    style = style,
                    letterSpacing = preset.letterSpacing,
                    isMarked = index in run.marked,
                    isLast = run.phase == ProbePhase.marking && last == index,
                    isPastLast = run.phase == ProbePhase.marking && last != null && index > last,
                    enabled = run.phase == ProbePhase.running || run.phase == ProbePhase.marking,
                    onTap = { run.tap(index) },
                )
            }
        }
    }
}

@Composable
private fun WordChip(
    text: String,
    index: Int,
    style: TextStyle,
    letterSpacing: Float,
    isMarked: Boolean,
    isLast: Boolean,
    isPastLast: Boolean,
    enabled: Boolean,
    onTap: () -> Unit,
) {
    val shape = remember { ContinuousRoundedRectangle(10.dp) }
    Box(
        Modifier
            .testTag("word-$index")
            .alpha(if (isPastLast) 0.35f else 1f)
            .drawBehind {
                if (isMarked) {
                    drawOutline(shape.createOutline(size, layoutDirection, this), Palette.notYetSoft)
                    val h = 3.dp.toPx()
                    drawRoundRect(
                        Palette.notYetDeep,
                        topLeft = Offset(6.dp.toPx(), size.height - h - 3.dp.toPx()),
                        size = Size(size.width - 12.dp.toPx(), h),
                        cornerRadius = CornerRadius(h / 2),
                    )
                }
                if (isLast) {
                    // The end of the reading, like the bracket an examiner draws after the last word.
                    val w = 3.dp.toPx()
                    drawRoundRect(Palette.ink, topLeft = Offset(size.width - w, 2.dp.toPx()), size = Size(w, size.height - 4.dp.toPx()), cornerRadius = CornerRadius(w / 2))
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onTap,
            )
            .semantics {
                contentDescription = text
                stateDescription = when {
                    isLast -> "ultimul citit"
                    isMarked -> "atins"
                    else -> ""
                }
            }
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        FoniaText(text, style = style, color = Palette.ink, tracking = letterSpacing.em, maxLines = 1)
    }
}

/** Lines of words, left to right, never more than [maxPerLine] on a line. */
@Composable
private fun WordFlow(maxPerLine: Int, wordGap: Dp, lineGap: Dp, content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxWidth()) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
        val gap = wordGap.roundToPx()
        val between = lineGap.roundToPx()
        val width = constraints.maxWidth
        val xs = IntArray(placeables.size)
        val ys = IntArray(placeables.size)
        var x = 0
        var y = 0
        var lineHeight = 0
        var onLine = 0
        placeables.forEachIndexed { index, placeable ->
            if (onLine > 0 && (onLine >= maxPerLine || x + placeable.width > width)) {
                x = 0
                y += lineHeight + between
                lineHeight = 0
                onLine = 0
            }
            xs[index] = x
            ys[index] = y
            x += placeable.width + gap
            lineHeight = max(lineHeight, placeable.height)
            onLine += 1
        }
        layout(width, if (placeables.isEmpty()) 0 else y + lineHeight) {
            placeables.forEachIndexed { index, placeable -> placeable.place(xs[index], ys[index]) }
        }
    }
}

/** The one or two actions under the words. */
@Composable
private fun Dock(run: ProbeRun, onDone: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (run.phase) {
            ProbePhase.ready -> StyledButton(onClick = { run.start(StartSource.Tap) }, style = FoniButtonStyle()) { CTALabel("Pornește") }
            ProbePhase.running -> StyledButton(
                onClick = { run.stop() },
                style = FoniButtonStyle(kind = FoniButtonStyle.Kind.secondary),
            ) { CTALabel("Oprește ceasul") }
            ProbePhase.marking -> {
                val score = run.score()
                if (score != null && !score.isUsable) {
                    FoniaText("Prea puțin ca să se poată măsura. Închide și mai încearcă.", style = fCaption, color = Palette.inkSoft, textAlign = TextAlign.Center)
                }
                StyledButton(
                    onClick = onDone,
                    style = FoniButtonStyle(),
                    enabled = score?.isUsable == true,
                ) { CTALabel("Vezi rezultatul") }
                if (run.lastRead != run.probe.words.lastIndex) {
                    StyledButton(onClick = { run.readEverything() }, style = QuietButtonStyle(Palette.accentText)) {
                        FoniaText("A citit tot")
                    }
                }
            }
            ProbePhase.prep -> Unit
        }
    }
}
