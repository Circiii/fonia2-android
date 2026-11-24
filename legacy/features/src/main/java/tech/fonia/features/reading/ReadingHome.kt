package tech.fonia.features.reading

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.fonia.design.CTALabel
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.PracticeWeight
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.fBody
import tech.fonia.design.fCaption
import tech.fonia.design.practice
import tech.fonia.design.ui
import tech.fonia.domain.Rung
import tech.fonia.features.app.ActivityIndicator
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.ButtonRow
import tech.fonia.features.app.DetailRow
import tech.fonia.features.app.FoniaSheet
import tech.fonia.features.app.FullScreenCover
import tech.fonia.features.app.HeaderPage
import tech.fonia.features.app.InsetGroupedList
import tech.fonia.features.app.ListRow
import tech.fonia.features.app.ListSection
import tech.fonia.features.app.SegmentedPicker
import tech.fonia.features.app.SystemColors
import tech.fonia.features.app.SystemNavigationBar
import tech.fonia.features.app.ValueRow
import tech.fonia.features.app.at
import tech.fonia.features.app.dspGroup
import tech.fonia.features.app.ignoringFailure
import tech.fonia.features.app.orderedSounds
import tech.fonia.features.app.shortLetter
import tech.fonia.features.app.title
import tech.fonia.features.grownups.orEmpty
import tech.fonia.store.Profile
import tech.fonia.store.localDay
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The bundled lists and texts, read once per process. */
internal object ReadingLibrary {
    @Volatile
    private var cached: ReadingContent? = null

    fun content(context: Context): ReadingContent? = cached ?: ReadingContent.load(context.assets)?.also { cached = it }
}

/** The probe on screen now, with the setting it was started in. */
private class ActiveProbe(val run: ProbeRun, val preset: ReadingPreset)

/**
 * "Citire", in the adults' area: an adult runs the probe with the child (C1), sees the child's own curve, and makes the
 * report for the teacher and the logoped (C2). It is not a door on the child's Home: a child doesn't start a probe.
 * [clock] and [today] are the real ones; the UI test brings its own.
 */
@Composable
fun ReadingHome(
    profileID: UUID,
    app: AppModel,
    clock: ProbeClock = ProbeClock.System,
    today: () -> LocalDate = { LocalDate.now() },
) {
    val profile = app.profiles.firstOrNull { it.id == profileID } ?: return
    val context = LocalContext.current
    val content = remember(context) { ReadingLibrary.content(context) }
    val store = app.reading
    var data by remember { mutableStateOf(ReadingProfileData()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(profileID) {
        data = loadOrEmpty(store, profileID)
        loaded = true
    }
    var chosenSet by rememberSaveable { mutableStateOf<String?>(null) }
    // The other set than last time, so a repeated probe doesn't meet the same words.
    val set = chosenSet ?: data.latest?.set?.let { last -> content?.sets?.firstOrNull { it != last } } ?: content?.sets?.firstOrNull() ?: "A"
    var active by remember { mutableStateOf<ActiveProbe?>(null) }
    var showProbe by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }

    fun save(change: (ReadingProfileData) -> ReadingProfileData) {
        data = change(data)
        if (store != null) app.scope.launch { ignoringFailure { data = store.update(profileID, change) } }
    }

    Box(Modifier.fillMaxSize()) {
        HeaderPage("Citire") {
            if (content == null) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    FoniaText("Listele de citit lipsesc din aplicație.", style = fBody, color = Palette.inkSoft)
                }
                return@HeaderPage
            }
            InsetGroupedList {
                ListSection(footer = content.notice) {
                    row {
                        ListRow {
                            FoniaText(
                                "Copilul citește un minut de pe ecran. Tu atingi cuvintele citite altfel, iar telefonul măsoară timpul. " +
                                    "Vezi câte cuvinte corecte pe minut a citit și cum se schimbă de la o lună la alta.",
                                Modifier.weight(1f),
                                style = fBody,
                                color = Palette.ink,
                            )
                        }
                    }
                }
                ListSection(header = "Ce citește", footer = "Setul B are alte cuvinte, la fel de grele. E pentru proba repetată, ca să nu le știe pe de rost.") {
                    row {
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            SegmentedPicker(content.sets.map { "Setul $it" }, selected = content.sets.indexOf(set).coerceAtLeast(0)) { chosenSet = content.sets[it] }
                        }
                    }
                    for (kind in ProbeKind.entries) {
                        val probe = content.probe(kind, set) ?: continue
                        val last = data.probes(kind).lastOrNull()
                        row {
                            DetailRow(
                                kind.title,
                                if (last == null) "${countLabel(probe)}. Încă nicio probă." else "${countLabel(probe)}. Ultima: ${last.score.wcpmShown} pe minut, ${longDay(last.day)}.",
                                onClick = {
                                    active = ActiveProbe(ProbeRun(probe, clock), data.preset)
                                    showProbe = true
                                },
                            )
                        }
                    }
                }
                ListSection(
                    header = "Cum arată literele",
                    footer = "Unii copii citesc mai ușor cu mai mult loc între litere. Încearcă două așezări în zile diferite, cu seturi diferite, și păstrează-o pe cea cu care citește mai bine.",
                ) {
                    row {
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            SegmentedPicker(ReadingPreset.entries.map { it.title }, selected = data.preset.ordinal) { index ->
                                save { it.copy(preset = ReadingPreset.entries[index]) }
                            }
                        }
                    }
                    row {
                        PresetPreview(data.preset, content.probe(ProbeKind.Mono, set)?.words?.take(10)?.map { it.text } ?: emptyList())
                    }
                }
                val kinds = ProbeKind.entries.filter { kind -> data.probes(kind).isNotEmpty() }
                if (kinds.isNotEmpty()) {
                    ListSection(header = "Cum citește de la o probă la alta", footer = "Cuvinte corecte pe minut, la fiecare probă. Fiecare copil se compară cu el însuși.") {
                        for (kind in kinds) {
                            row {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    FoniaText(kind.title, style = ui(15, UIWeight.semibold), color = Palette.ink)
                                    ReadingCurve(data.probes(kind), height = 130.dp)
                                }
                            }
                        }
                    }
                }
                ListSection(header = "Raport", footer = "PDF-ul se face pe telefon. Pleacă de aici doar dacă îl trimiți tu, cui vrei.") {
                    row {
                        DetailRow(
                            "Raportul pentru învățător și logoped",
                            "Pe părțile Anexei 1 din OMEN 3124/2017, cu curba pe 6 luni.",
                            onClick = { showReport = true },
                        )
                    }
                }
            }
        }
        FullScreenCover(isPresented = showProbe, onDismissed = { active = null }) {
            val current = active
            if (current != null && content != null) {
                ProbeFlow(
                    current,
                    content,
                    clock,
                    voice = remember(app) {
                        AudioHubVoiceStart(app.audio, profile.dspGroup) { AudioHubVoiceStart.mayListen(context, app.audio) }
                    },
                    onSave = { record ->
                        data = data.copy(probes = data.probes + record)
                        if (store != null) app.scope.launch { ignoringFailure { data = store.add(profileID, record) } }
                    },
                    onDelete = { record ->
                        data = data.copy(probes = data.probes.filterNot { it.id == record.id })
                        if (store != null) app.scope.launch { ignoringFailure { data = store.remove(profileID, record.id) } }
                    },
                    history = { kind -> data.probes(kind) },
                    onClose = { showProbe = false },
                )
            }
        }
    }
    if (content != null && loaded) {
        FoniaSheet(isPresented = showReport, onDismiss = { showReport = false }) {
            ReportSheet(profile, app, data, content, today, onDetails = { details -> save { it.copy(report = details) } }, onDone = { showReport = false })
        }
    }
}

private suspend fun loadOrEmpty(store: ReadingStore?, profileID: UUID): ReadingProfileData = try {
    store?.load(profileID) ?: ReadingProfileData()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    ReadingProfileData()
}

/** "60 de cuvinte", "113 cuvinte". */
private fun countLabel(probe: ReadingProbe): String =
    tech.fonia.domain.Plural.format(probe.words.size, tech.fonia.domain.Plural.Noun.Cuvant).replaceFirstChar { it.uppercase() }

/** A few words in the chosen setting, as the child will see them. */
@Composable
private fun PresetPreview(preset: ReadingPreset, words: List<String>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FoniaText(preset.detail, style = fCaption, color = Palette.inkSoft)
        Box(
            Modifier
                .fillMaxWidth()
                .background(Palette.paper, ContinuousRoundedRectangle(14.dp))
                .padding(12.dp)
                .semantics { contentDescription = "Așa arată: ${preset.title}" },
        ) {
            FoniaText(
                words.chunked(preset.maxWordsPerLine).joinToString("\n") { it.joinToString("  ") },
                style = practice(22, PracticeWeight.regular, TextRole.title).copy(lineHeight = preset.lineHeight.em),
                color = Palette.ink,
                tracking = preset.letterSpacing.em,
            )
        }
    }
}

/** The probe, then its result; saving happens in between, outside this screen's lifetime. */
@Composable
private fun ProbeFlow(
    active: ActiveProbe,
    content: ReadingContent,
    clock: ProbeClock,
    voice: VoiceStart?,
    onSave: (ProbeRecord) -> Unit,
    onDelete: (ProbeRecord) -> Unit,
    history: (ProbeKind) -> List<ProbeRecord>,
    onClose: () -> Unit,
) {
    var saved by remember { mutableStateOf<ProbeRecord?>(null) }
    val record = saved
    if (record == null) {
        ReadingProbeScreen(
            run = active.run,
            preset = active.preset,
            instruction = content.instruction(active.run.probe.kind),
            adultRules = content.adultRules,
            clock = clock,
            voice = voice,
            onClose = onClose,
            onDone = {
                val run = active.run
                val score = run.score() ?: return@ReadingProbeScreen
                if (!score.isUsable) return@ReadingProbeScreen
                val now = Instant.now()
                val made = ProbeRecord(
                    takenAt = now,
                    localDay = localDay(now),
                    probeID = run.probe.id,
                    kind = run.probe.kind,
                    set = run.probe.set,
                    contentVersion = content.version,
                    preset = active.preset,
                    startedBy = run.startedBy ?: StartSource.Tap,
                    seconds = score.seconds,
                    totalWords = run.probe.words.size,
                    wordsRead = score.wordsRead,
                    errorIndices = run.marked.filter { it < score.wordsRead }.sorted(),
                    syllablesRead = score.syllablesRead,
                    syllablesCorrect = score.syllablesCorrect,
                )
                onSave(made)
                saved = made
            },
        )
    } else {
        ProbeResults(
            record,
            history(record.kind).let { list -> if (list.any { it.id == record.id }) list else (list + record).sortedBy { it.takenAt } },
            onDone = onClose,
            onDelete = {
                onDelete(record)
                onClose()
            },
        )
    }
}

/** The report: what goes on its first lines, then the PDF, its pages to look at, and the share sheet. */
@Composable
private fun ReportSheet(
    profile: Profile,
    app: AppModel,
    data: ReadingProfileData,
    content: ReadingContent,
    today: () -> LocalDate,
    onDetails: (ReportDetails) -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val guard = tech.fonia.features.app.rememberAdultGate(app)
    var pseudonym by rememberSaveable { mutableStateOf(data.report.pseudonym.ifBlank { "Elevul A" }) }
    var age by rememberSaveable { mutableStateOf(data.report.age) }
    var grade by rememberSaveable { mutableStateOf(data.report.grade?.rawValue) }
    var making by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var made by remember { mutableStateOf<Pair<File, List<Bitmap>>?>(null) }
    val ageBand = profile.ageBand.rawValue.takeIf { profile.ageBand != Profile.AgeBand.Adult }?.replace("-", "–")
    val preview = ReadingReport.build(data, ageBand, today(), emptyList(), content.version, content.isDraft)

    Column(Modifier.fillMaxSize().background(Palette.paper)) {
        SystemNavigationBar("Raport de citire", onDone = onDone)
        InsetGroupedList(Modifier.weight(1f)) {
            ListSection(header = "Pe prima pagină", footer = "Numele copilului nu apare pe raport. Scrie un pseudonim, ca „Elevul A”.") {
                row { TextFieldRow("Pseudonim", pseudonym, "Elevul A") { pseudonym = it; made = null } }
                row { TextFieldRow("Vârsta", age, ageBand?.let { "$it ani" } ?: "de exemplu, 7 ani", KeyboardType.Text) { age = it; made = null } }
                row {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FoniaText("Clasa", style = fCaption, color = SystemColors.secondaryLabel)
                        SegmentedPicker(SchoolGrade.entries.map { it.short }, selected = SchoolGrade.entries.indexOfFirst { it.rawValue == grade }) { index ->
                            grade = SchoolGrade.entries[index].rawValue
                            made = null
                        }
                    }
                }
            }
            ListSection(footer = "Raportul ia probele din ultimele 6 luni: criteriul II din Anexa 1 întreabă ce s-a schimbat în 6 luni.") {
                row { ValueRow("Perioada", preview.period) }
                row { ValueRow("Probe de citire", "${preview.probes.size}") }
            }
            ListSection(footer = if (failed) "PDF-ul nu s-a putut face. Mai încearcă." else null) {
                row {
                    if (making) {
                        ListRow {
                            ActivityIndicator()
                            FoniaText("Se face PDF-ul…", color = SystemColors.label)
                        }
                    } else {
                        ButtonRow(if (made == null) "Fă PDF-ul" else "Fă-l din nou", onClick = {
                            val details = ReportDetails(pseudonym.trim(), age.trim(), SchoolGrade.fromRawValue(grade))
                            onDetails(details)
                            making = true
                            failed = false
                            app.scope.launch {
                                try {
                                    val speech = speechLines(profile, app)
                                    val report = ReadingReport.build(data.copy(report = details), ageBand, today(), speech, content.version, content.isDraft)
                                    made = withContext(Dispatchers.IO) {
                                        val (file, _) = ReadingReportPdf.write(context, report)
                                        file to ReadingReportPdf.render(file, 900)
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    failed = true
                                } finally {
                                    making = false
                                }
                            }
                        })
                    }
                }
            }
            val ready = made
            if (ready != null) {
                ListSection(header = "Cum arată", clear = true) {
                    row {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            StyledButton(onClick = { guard { ReadingReportPdf.share(context, ready.first) } }, style = FoniButtonStyle()) { CTALabel("Trimite PDF-ul") }
                            ready.second.forEachIndexed { index, page ->
                                Image(
                                    page.asImageBitmap(),
                                    contentDescription = "Pagina ${index + 1}",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(page.width.toFloat() / page.height)
                                        .border(1.dp, Palette.line)
                                        .testTag("report-page-$index"),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextFieldRow(label: String, value: String, placeholder: String, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        FoniaText(label, Modifier.weight(0.4f), color = SystemColors.label)
        Box(Modifier.weight(0.6f)) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = fBody.copy(color = SystemColors.label),
                cursorBrush = SolidColor(Palette.accentText),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, keyboardType = keyboard),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                decorationBox = { field ->
                    if (value.isEmpty()) FoniaText(placeholder, style = fBody, color = SystemColors.tertiaryLabel)
                    field()
                },
            )
        }
    }
}

/** The sounds practised, for part 6 of the report: the step reached and the phone's answers so far. */
private suspend fun speechLines(profile: Profile, app: AppModel): List<SpeechLine> {
    val progress = orEmpty { app.repository.soundProgress(profile.id) }
    val outcomes = orEmpty { app.repository.soundOutcomesStream(profile.id).first() }
    return profile.orderedSounds(app.content)
        .filter { sound ->
            val id = sound.id.rawValue
            id in profile.targetSounds || progress.any { it.sound == id } || outcomes.any { it.sound == id }
        }
        .map { sound ->
            val id = sound.id.rawValue
            val rung = Rung.at(progress.firstOrNull { it.sound == id }?.rung ?: 0)
            val counts = outcomes.firstOrNull { it.sound == id }
            SpeechLine(sound.shortLetter, rung.title(sound.shortLetter), counts?.good ?: 0, counts?.almost ?: 0, counts?.notYet ?: 0)
        }
}
