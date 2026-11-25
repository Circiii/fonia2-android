package tech.fonia.features.speaking.bac

import tech.fonia.features.app.wordsTranscriber
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.fonia.audio.CapturedClip
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.Field
import tech.fonia.design.FieldBackdrop
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaLabel
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.fBody
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fCaption
import tech.fonia.design.fDisplay
import tech.fonia.design.fHeadline
import tech.fonia.design.fLead
import tech.fonia.design.monospacedDigit
import tech.fonia.design.swiftSpring
import tech.fonia.design.ui
import tech.fonia.domain.Language
import tech.fonia.features.app.ActionRole
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.DialogAction
import tech.fonia.features.app.FoniaAlert
import tech.fonia.features.app.SegmentedPicker
import tech.fonia.features.app.SpeechHub
import tech.fonia.features.app.calibration
import tech.fonia.features.app.dspGroup
import tech.fonia.features.app.rememberStoredInt
import tech.fonia.features.app.rememberStoredString
import tech.fonia.features.home.ModelNotice
import tech.fonia.features.session.TakeJudge
import tech.fonia.features.speaking.ListeningPanel
import tech.fonia.features.speaking.SpeakingClock
import tech.fonia.features.speaking.SpeakingHeading
import tech.fonia.features.speaking.SpeakingTopBar
import tech.fonia.features.speaking.TakeRecorder
import tech.fonia.features.speaking.ThinkingPanel
import tech.fonia.features.speaking.WordFlow
import tech.fonia.features.speaking.WordPaint
import tech.fonia.features.speaking.clockText
import tech.fonia.speech.FillerLexicon
import tech.fonia.speech.SpeechResampler
import tech.fonia.store.JSONValue
import tech.fonia.store.Profile
import tech.fonia.store.SpeechRecord

private enum class BacPhase { Intro, Preparation, Part, Analysing, Report, Unavailable }

/** The preparation lengths offered, in minutes; the exam's is about 30 (H §4.2). */
val BAC_PREPARATION_CHOICES = listOf(5, 10, 15, 30)

/** The longest each part may run before it stops by itself, seconds. */
private fun BacPart.maximumSeconds(): Int = when (this) {
    BacPart.Reading, BacPart.Opinion -> 240
    BacPart.Task1, BacPart.Task2 -> 180
}

/**
 * The BAC oral simulation, Proba A (V6): a ticket drawn at random (a text and three tasks), a preparation on a clock
 * the reader sets, the text read aloud, the answers to the first two tasks, the argued opinion, then a report of
 * indicators only. Everything is recorded and analysed on the phone, for minors too; the report says that the grade
 * is the teachers'. [clock] and [draw] are for the UI tests.
 */
@Composable
fun BacOralScreen(
    profile: Profile,
    app: AppModel,
    onDone: () -> Unit,
    clock: SpeakingClock = SpeakingClock.System,
    draw: ((BacContent, String?) -> BacTicket)? = null,
) {
    val context = LocalContext.current
    val content = remember { BacContent.load(context.assets) }
    val recorder = remember { TakeRecorder(app, profile) }
    var preparationMinutes by rememberStoredInt(app.preferences, "fonia.bac.preparationMinutes", content?.preparationMinutes ?: 30)
    var lastTicket by rememberStoredString(app.preferences, "fonia.bac.lastTicket", "")
    var phase by remember { mutableStateOf(BacPhase.Intro) }
    var ticket by remember { mutableStateOf<BacTicket?>(null) }
    var keys by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var preparationStart by remember { mutableStateOf(0L) }
    var remaining by remember { mutableIntStateOf(0) }
    var part by remember { mutableStateOf(BacPart.Reading) }
    var elapsed by remember { mutableIntStateOf(0) }
    var listening by remember { mutableStateOf<Job?>(null) }
    var pending by remember { mutableStateOf<List<Deferred<BacRecording>>>(emptyList()) }
    var progress by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<BacReport?>(null) }
    var marked by remember { mutableStateOf<Int?>(null) }
    var readingClip by remember { mutableStateOf<CapturedClip?>(null) }
    var askExit by remember { mutableStateOf(false) }
    var analysis by remember { mutableStateOf<Job?>(null) }

    fun close() {
        listening?.cancel()
        analysis?.cancel()
        app.scope.launch {
            recorder.cancel()
            app.audio.shutDown()
            onDone()
        }
    }

    fun save(made: BacReport) {
        val reading = made.reading
        val record = SpeechRecord(
            profileId = profile.id,
            lang = SpeechRecord.Language.Ro,
            prompt = "Simulare BAC oral, biletul ${made.ticket.number}",
            duration = (reading?.seconds ?: 0.0) + made.answers.sumOf { it.seconds },
            metrics = buildMap {
                put("ticket", JSONValue.String(made.ticket.id))
                reading?.check?.let { put("readingAccuracy", JSONValue.Number(it.accuracy.toDouble())) }
                reading?.pauses?.markShare?.let { put("punctuationPauseShare", JSONValue.Number(it.toDouble())) }
                reading?.pauses?.otherPausesPerMinute?.let { put("otherPausesPerMinute", JSONValue.Number(it)) }
                reading?.takeIf { it.heard }?.let { put("pvq", JSONValue.Number(it.pvq.toDouble())) }
                reading?.emphasis?.tally?.keyShare?.let { put("keyWordShare", JSONValue.Number(it.toDouble())) }
                made.arguments?.let { put("arguments", JSONValue.Number(it.arguments.toDouble())) }
            },
        )
        val repository = app.repository
        app.scope.launch { runCatching { repository.saveSpeech(record) } }
    }

    fun drawTicket() {
        val loaded = content ?: return
        val drawn = draw?.invoke(loaded, lastTicket.ifEmpty { null }) ?: loaded.draw(lastTicket.ifEmpty { null })
        lastTicket = drawn.id
        ticket = drawn
        keys = drawn.text.keyIndices
        preparationStart = clock.nanos()
        remaining = preparationMinutes * 60
        pending = emptyList()
        readingClip = null
        report = null
        marked = null
        part = BacPart.Reading
        elapsed = 0
        phase = BacPhase.Preparation
    }

    fun analyse() {
        val drawn = ticket ?: return
        phase = BacPhase.Analysing
        progress = "Pregătesc raportul"
        analysis = app.scope.launch {
            val recordings = pending.map { it.await() }.associateBy { it.part }
            // The reading at 16 kHz is what the report plays back; the 48 kHz takes are already let go.
            readingClip = recordings[BacPart.Reading]?.let { CapturedClip(it.samples16k, SpeechResampler.WHISPER_RATE) }
            val transcriber = app.wordsTranscriber
            if (transcriber == null) {
                phase = BacPhase.Unavailable
                return@launch
            }
            val fillers = app.content.diction.fillers.ro
            val lexicon = FillerLexicon(
                hesitationLabel = fillers.hesitations.firstOrNull() ?: "ăă",
                fillers = fillers.hesitations + fillers.discourseMarkers,
                ambiguous = fillers.discourseMarkers,
            )
            val guide = app.content.diction.rateGuide.romanian
            val made = try {
                BacReport.make(
                    ticket = drawn.copy(text = drawn.text.withKeys(keys)),
                    recordings = recordings,
                    transcriber = transcriber,
                    lexicon = lexicon,
                    paceBand = guide.syllablesPerSecondMin..guide.syllablesPerSecondMax,
                ) { line -> progress = line }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (made == null) {
                phase = BacPhase.Unavailable
                return@launch
            }
            report = made
            marked = null
            save(made)
            phase = BacPhase.Report
        }
    }

    suspend fun finishPart() {
        if (!recorder.isRecording) return
        val recorded = part
        val captured = recorder.stop()
        if (captured == null) return
        val thresholds = app.audio.analysisThresholds
        // The clip analysis and the 16 kHz copy run while the next part starts; the 48 kHz clip is let go after.
        pending = pending + app.scope.async {
            val summary = TakeJudge.summarise(captured, profile.dspGroup, profile.calibration, thresholds)
            val samples = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                SpeechResampler.resample(captured.samples, captured.sampleRate)
            }
            BacRecording(recorded, samples, summary, captured.duration)
        }
        val next = BacPart.entries.getOrNull(recorded.ordinal + 1)
        if (next == null) {
            analyse()
        } else {
            part = next
            elapsed = 0
        }
    }

    fun startPart() {
        val drawn = ticket ?: return
        if (recorder.isRecording) return
        listening = app.scope.launch {
            val fixture = if (part == BacPart.Reading) drawn.text.plain else null
            if (!recorder.start(fixtureText = fixture, language = Language.Ro)) return@launch
            elapsed = 0
            val limit = part.maximumSeconds()
            while (recorder.isRecording && elapsed < limit) {
                delay(1000)
                if (recorder.isRecording) elapsed += 1
            }
            finishPart()
        }
    }

    fun stopPart() {
        listening?.cancel()
        app.scope.launch { finishPart() }
    }

    // The preparation clock: the reader's own minutes, counted on [clock], so a test can move it.
    LaunchedEffect(phase, preparationStart) {
        if (phase != BacPhase.Preparation) return@LaunchedEffect
        while (true) {
            val passed = ((clock.nanos() - preparationStart) / 1_000_000_000L).toInt()
            remaining = (preparationMinutes * 60 - passed).coerceAtLeast(0)
            delay(250)
        }
    }

    BackHandler {
        when (phase) {
            BacPhase.Intro, BacPhase.Report, BacPhase.Unavailable -> close()
            BacPhase.Analysing -> Unit
            BacPhase.Preparation -> askExit = true
            BacPhase.Part -> if (recorder.isRecording) stopPart() else askExit = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        FieldBackdrop(Field.paper)
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            SpeakingTopBar(onClose = { if (phase == BacPhase.Preparation || phase == BacPhase.Part) askExit = true else close() }) {
                ticket?.takeIf { phase != BacPhase.Intro }?.let { FoniaText("Biletul nr. ${it.number}", style = fBodyStrong, color = Palette.inkSoft) }
            }
            Crossfade(phase, Modifier.weight(1f), animationSpec = swiftSpring(0.34, 0.08), label = "bac") { shown ->
                when (shown) {
                    BacPhase.Intro -> Intro(
                        content = content,
                        speech = app.speech,
                        minutes = preparationMinutes,
                        onMinutes = { preparationMinutes = it },
                        onDraw = { drawTicket() },
                    )
                    BacPhase.Preparation -> ticket?.let { drawn ->
                        Preparation(
                            ticket = drawn,
                            keys = keys,
                            remaining = remaining,
                            onToggle = { index -> keys = if (index in keys) keys - index else keys + index },
                            onReady = {
                                part = BacPart.Reading
                                elapsed = 0
                                phase = BacPhase.Part
                            },
                        )
                    }
                    BacPhase.Part -> ticket?.let { drawn ->
                        PartPage(
                            ticket = drawn,
                            keys = keys,
                            part = part,
                            isRecording = recorder.isRecording,
                            elapsed = elapsed,
                            app = app,
                            problem = recorder.problem,
                            onStart = { startPart() },
                            onStop = { stopPart() },
                        )
                    }
                    BacPhase.Analysing -> ThinkingPanel("Pregătesc raportul", progress)
                    BacPhase.Report -> report?.let { made ->
                        BacReportView(
                            report = made,
                            levels = content?.levels.orEmpty(),
                            levelsSource = content?.levelsSource,
                            marked = marked,
                            onMark = { marked = it },
                            app = app,
                            readingClip = readingClip,
                            onAnother = {
                                report = null
                                readingClip = null
                                phase = BacPhase.Intro
                            },
                            onClose = { close() },
                        )
                    }
                    BacPhase.Unavailable -> Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    ) {
                        FoniaText("Nu am putut face raportul.", style = fHeadline, color = Palette.ink)
                        FoniaText("Pentru raport, telefonul are nevoie de pachetul de vorbire. Descarcă-l și mai încearcă.", style = fBody, color = Palette.inkSoft)
                        ModelNotice(app.speech)
                        StyledClose { close() }
                    }
                }
            }
        }
    }

    FoniaAlert(
        isPresented = askExit,
        onDismiss = { askExit = false },
        title = "Ieși din simulare?",
        message = "Biletul și ce ai înregistrat până acum nu se păstrează.",
        actions = listOf(
            DialogAction("Rămân", ActionRole.Cancel),
            DialogAction("Ies", ActionRole.Default) { close() },
        ),
    )
}

@Composable
private fun StyledClose(onClose: () -> Unit) {
    tech.fonia.design.StyledButton(onClick = onClose, style = FoniButtonStyle(field = Field.paper)) { FoniaText("Închide") }
}

@Composable
private fun Intro(content: BacContent?, speech: SpeechHub, minutes: Int, onMinutes: (Int) -> Unit, onDraw: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        FoniaText("Simulare BAC oral", style = fDisplay, color = Palette.ink)
        FoniaText("Proba A, limba română", style = fLead, color = Palette.inkSoft)
        val steps = listOf(
            "Tragi un bilet: un text și trei cerințe.",
            "Te pregătești. Poți marca în text cuvintele pe care vrei să apeși.",
            "Citești textul cu voce tare.",
            "Răspunzi la primele două cerințe.",
            "Îți spui opinia, cu argumente.",
        )
        steps.forEachIndexed { index, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                FoniaText(
                    "${index + 1}",
                    Modifier
                        .background(Palette.accentSoft, RoundedCornerShape(50))
                        .padding(horizontal = 9.dp, vertical = 2.dp),
                    style = ui(15, UIWeight.bold, TextRole.body),
                    color = Palette.accentText,
                )
                FoniaText(step, style = fBody, color = Palette.ink)
            }
        }
        FoniaText(
            "La final vezi ce a măsurat telefonul: cum ai citit, pauzele, tonul, cuvintele-cheie și argumentele. Nota o dau profesorii.",
            style = fBody,
            color = Palette.inkSoft,
        )
        SpeakingHeading("Timp de pregătire")
        SegmentedPicker(
            BAC_PREPARATION_CHOICES.map { "$it min" },
            BAC_PREPARATION_CHOICES.indexOf(minutes).coerceAtLeast(0),
        ) { onMinutes(BAC_PREPARATION_CHOICES[it]) }
        FoniaText("La examen sunt în jur de 30 de minute.", style = fCaption, color = Palette.inkSoft)
        if (content != null) {
            SpeakingHeading("Ce urmăresc profesorii")
            for (level in content.levels) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    FoniaText(level.name.replaceFirstChar { it.uppercase() }, style = fBodyStrong, color = Palette.ink)
                    FoniaText(level.description.replaceFirstChar { it.uppercase() } + ".", style = fBody, color = Palette.inkSoft)
                }
            }
            FoniaText(content.levelsSource, style = fCaption, color = Palette.inkSoft)
            if (content.isDraft) FoniaText(content.notice, style = fCaption, color = Palette.inkSoft)
        } else {
            FoniaText("Nu găsesc biletele.", style = fBodyStrong, color = Palette.warmDeep)
        }
        ModelNotice(speech)
        tech.fonia.design.StyledButton(
            onClick = onDraw,
            style = FoniButtonStyle(field = Field.paper),
            enabled = content != null && speech.state == SpeechHub.State.Ready,
        ) { FoniaText("Trage un bilet") }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TicketHeader(ticket: BacTicket) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FoniaText(ticket.title, style = ui(22, UIWeight.bold, TextRole.title2), color = Palette.ink)
        FoniaText(ticket.kind.replaceFirstChar { it.uppercase() }, style = fCaption, color = Palette.inkSoft)
    }
}

@Composable
private fun TaskList(ticket: BacTicket, highlight: Int? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ticket.tasks.forEachIndexed { index, task ->
            val on = highlight == index + 1
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (on) Palette.accentSoft else Palette.sheet, ContinuousRoundedRectangle(14.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FoniaText("${index + 1}.", style = fBodyStrong, color = Palette.accentText)
                FoniaText(task, style = fBody, color = Palette.ink)
            }
        }
    }
}

private fun keyPaint(keys: Set<Int>): (tech.fonia.features.speaking.TextWord) -> WordPaint = { word ->
    if (word.index in keys) WordPaint(color = Palette.accentText, bold = true, underline = Palette.accent, state = "cuvânt-cheie") else WordPaint()
}

@Composable
private fun Preparation(ticket: BacTicket, keys: Set<Int>, remaining: Int, onToggle: (Int) -> Unit, onReady: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (remaining == 0) Palette.tipSoft else Palette.sheet, ContinuousRoundedRectangle(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FoniaIconView(FoniaIcon.hourglass, size = 22f, tint = Palette.ink)
            if (remaining > 0) {
                FoniaText("Pregătire", Modifier.weight(1f), style = fBodyStrong, color = Palette.ink)
                FoniaText(clockText(remaining), style = ui(20, UIWeight.bold, TextRole.title3).monospacedDigit(), color = Palette.ink)
            } else {
                FoniaText("Timpul de pregătire s-a terminat.", Modifier.weight(1f), style = fBodyStrong, color = Palette.tipDeep)
            }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TicketHeader(ticket)
            FoniaText("Atinge un cuvânt ca să-l marchezi: pe cuvintele marcate vei apăsa când citești.", style = fCaption, color = Palette.inkSoft)
            for (paragraph in ticketParagraphs(ticket)) {
                WordFlow(
                    paragraph,
                    style = ui(19, UIWeight.regular, TextRole.body),
                    paint = keyPaint(keys),
                    tapLabel = "Marchează sau scoate cuvântul-cheie",
                    onTap = { onToggle(it.index) },
                )
            }
            SpeakingHeading("Cerințele")
            TaskList(ticket)
        }
        tech.fonia.design.StyledButton(onClick = onReady, style = FoniButtonStyle(field = Field.paper)) {
            FoniaText(if (remaining > 0) "Sunt gata" else "Mai departe")
        }
    }
}

private fun ticketParagraphs(ticket: BacTicket) = ticket.text.paragraphs

@Composable
private fun PartPage(
    ticket: BacTicket,
    keys: Set<Int>,
    part: BacPart,
    isRecording: Boolean,
    elapsed: Int,
    app: AppModel,
    problem: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FoniaText(
            when (part) {
                BacPart.Reading -> "Citește textul cu voce tare"
                BacPart.Task1 -> "Cerința 1"
                BacPart.Task2 -> "Cerința 2"
                BacPart.Opinion -> "Cerința 3: opinia ta"
            },
            Modifier.semantics { contentDescription = "Pasul ${part.ordinal + 1} din 4" },
            style = ui(24, UIWeight.bold, TextRole.title2),
            color = Palette.ink,
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (part == BacPart.Reading) {
                TicketHeader(ticket)
                for (paragraph in ticketParagraphs(ticket)) {
                    WordFlow(paragraph, style = ui(19, UIWeight.regular, TextRole.body), paint = keyPaint(keys))
                }
            } else {
                FoniaText(
                    ticket.tasks[part.task - 1],
                    Modifier
                        .fillMaxWidth()
                        .background(Palette.sheet, ContinuousRoundedRectangle(18.dp))
                        .padding(16.dp),
                    style = fLead,
                    color = Palette.ink,
                )
                FoniaText(
                    if (part == BacPart.Opinion) {
                        "Spune ce crezi, adu cel puțin două argumente, apoi încheie. Vorbește ca în fața comisiei."
                    } else {
                        "Răspunde ca în fața comisiei. Te poți uita la text."
                    },
                    style = fBody,
                    color = Palette.inkSoft,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (paragraph in ticketParagraphs(ticket)) {
                        WordFlow(paragraph, style = ui(15, UIWeight.regular, TextRole.footnote), paint = { WordPaint(color = Palette.inkSoft) })
                    }
                }
            }
        }
        if (problem != null && !isRecording) FoniaText(problem, style = fBodyStrong, color = Palette.warmDeep, textAlign = TextAlign.Center)
        if (isRecording) {
            ListeningPanel(app, elapsed, "Am terminat", onStop)
        } else {
            tech.fonia.design.StyledButton(onClick = onStart, style = FoniButtonStyle(field = Field.paper)) {
                FoniaLabel(if (part == BacPart.Reading) "Încep să citesc" else "Încep să răspund", FoniaIcon.microphone)
            }
        }
    }
}
