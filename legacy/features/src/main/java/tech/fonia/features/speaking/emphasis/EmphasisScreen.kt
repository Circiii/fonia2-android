package tech.fonia.features.speaking.emphasis

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
import tech.fonia.design.PillButtonStyle
import tech.fonia.design.PracticeSheet
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.WrapLayout
import tech.fonia.design.fBody
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fCaption
import tech.fonia.design.fDisplay
import tech.fonia.design.fLead
import tech.fonia.design.fTitle
import tech.fonia.design.swiftSpring
import tech.fonia.design.ui
import tech.fonia.domain.Language
import tech.fonia.dsp.EmphasisCue
import tech.fonia.dsp.EmphasisState
import tech.fonia.dsp.EmphasisTally
import tech.fonia.dsp.WordEmphasis
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.SpeechHub
import tech.fonia.features.app.dspGroup
import tech.fonia.features.app.calibration
import tech.fonia.features.home.ModelNotice
import tech.fonia.features.session.TakeJudge
import tech.fonia.features.speaking.ListeningPanel
import tech.fonia.features.speaking.SpeakingHeading
import tech.fonia.features.speaking.SpeakingTopBar
import tech.fonia.features.speaking.TakeRecorder
import tech.fonia.features.speaking.ThinkingPanel
import tech.fonia.features.speaking.WordFlow
import tech.fonia.features.speaking.WordPaint
import tech.fonia.store.JSONValue
import tech.fonia.store.Profile
import tech.fonia.store.SpeechRecord

/** Sentences in one set: short enough for a few minutes, enough for the 80 % reference to mean something. */
const val EMPHASIS_SET_SIZE = 6

private enum class EmphasisPhase { Intro, Ready, Listening, Thinking, Result, Summary }

/**
 * "Cuvântul important" (V2, H F2): the reader answers a question with a sentence and stresses its marked word.
 * Whisper says where each word is, the clip analysis measures each word's longest vowel, its level and its pitch
 * against its neighbours, and the result names the cue that was measured ("a fost mai lung și mai tare"), never a
 * state of the reader. Everything stays on the phone.
 */
@Composable
fun EmphasisScreen(
    language: Language,
    profile: Profile,
    app: AppModel,
    onDone: () -> Unit,
    pick: (List<EmphasisItem>) -> List<EmphasisItem> = { it.shuffled().take(EMPHASIS_SET_SIZE) },
) {
    val context = LocalContext.current
    val content = remember { EmphasisContent.load(context.assets) }
    val recorder = remember { TakeRecorder(app, profile) }
    var phase by remember { mutableStateOf(EmphasisPhase.Intro) }
    var set by remember { mutableStateOf<List<EmphasisItem>>(emptyList()) }
    var position by remember { mutableIntStateOf(0) }
    var takes by remember { mutableStateOf<Map<Int, EmphasisTake>>(emptyMap()) }
    var current by remember { mutableStateOf<EmphasisTake?>(null) }
    var clip by remember { mutableStateOf<CapturedClip?>(null) }
    var elapsed by remember { mutableIntStateOf(0) }
    var listening by remember { mutableStateOf<Job?>(null) }
    var failed by remember { mutableStateOf(false) }
    val item = set.getOrNull(position)

    fun tally(): EmphasisTally = takes.values.fold(EmphasisTally.EMPTY) { sum, take -> sum + take.tally }

    fun save() {
        val total = tally()
        if (takes.isEmpty()) return
        val record = SpeechRecord(
            profileId = profile.id,
            lang = if (language == Language.Ro) SpeechRecord.Language.Ro else SpeechRecord.Language.En,
            prompt = "Cuvântul important",
            duration = 0.0,
            metrics = buildMap {
                put("sentences", JSONValue.Number(takes.size.toDouble()))
                put("keyWords", JSONValue.Number(total.measuredKeyWords.toDouble()))
                put("keyWordsStressed", JSONValue.Number(total.stressedKeyWords.toDouble()))
                total.keyShare?.let { put("keyWordShare", JSONValue.Number(it.toDouble())) }
                total.stressedShare?.let { put("stressedShare", JSONValue.Number(it.toDouble())) }
            },
        )
        val repository = app.repository
        app.scope.launch { runCatching { repository.saveSpeech(record) } }
    }

    fun close() {
        if (phase != EmphasisPhase.Summary && takes.isNotEmpty()) save()
        listening?.cancel()
        app.scope.launch {
            recorder.cancel()
            app.audio.shutDown()
            onDone()
        }
    }

    fun begin() {
        val items = content?.items(language).orEmpty()
        if (items.isEmpty()) return
        set = pick(items)
        position = 0
        takes = emptyMap()
        current = null
        clip = null
        phase = EmphasisPhase.Ready
    }

    suspend fun finish() {
        if (phase != EmphasisPhase.Listening) return
        val shown = item ?: return
        phase = EmphasisPhase.Thinking
        val captured = recorder.stop()
        if (captured == null) {
            phase = EmphasisPhase.Ready
            return
        }
        clip = captured
        val take = try {
            val summary = TakeJudge.summarise(captured, profile.dspGroup, profile.calibration, app.audio.analysisThresholds)
            val transcriber = app.speech.transcriber
            if (transcriber == null) EmphasisTake(shown, null, null) else EmphasisTake.judge(shown, captured, summary, transcriber)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
            EmphasisTake(shown, null, null)
        }
        current = take
        takes = takes + (position to take)
        phase = EmphasisPhase.Result
    }

    fun read() {
        val shown = item ?: return
        if (phase != EmphasisPhase.Ready) return
        failed = false
        listening = app.scope.launch {
            if (!recorder.start(fixtureText = shown.text.plain, language = language)) return@launch
            elapsed = 0
            phase = EmphasisPhase.Listening
            val ticker = launch {
                while (true) {
                    delay(1000)
                    elapsed += 1
                }
            }
            try {
                app.audio.waitForTakeEnd(silence = 1.2, startTimeout = 6.0, maximum = 14.0)
            } finally {
                ticker.cancel()
            }
            finish()
        }
    }

    fun stopNow() {
        listening?.cancel()
        app.scope.launch { finish() }
    }

    fun next() {
        if (position < set.size - 1) {
            position += 1
            current = null
            clip = null
            phase = EmphasisPhase.Ready
        } else {
            phase = EmphasisPhase.Summary
            save()
        }
    }

    BackHandler {
        when (phase) {
            EmphasisPhase.Listening -> stopNow()
            EmphasisPhase.Thinking -> Unit
            else -> close()
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
            SpeakingTopBar(onClose = { close() }) {
                if (phase != EmphasisPhase.Intro && phase != EmphasisPhase.Summary && set.isNotEmpty()) {
                    FoniaText("${position + 1} din ${set.size}", style = fBodyStrong, color = Palette.inkSoft)
                }
            }
            Crossfade(phase, Modifier.weight(1f), animationSpec = swiftSpring(0.34, 0.08), label = "emphasis") { shownPhase ->
                when (shownPhase) {
                    EmphasisPhase.Intro -> Intro(content, app.speech, onStart = { begin() })
                    EmphasisPhase.Ready, EmphasisPhase.Listening -> item?.let {
                        Prompt(it, shownPhase == EmphasisPhase.Listening, elapsed, app, recorder.problem, onRead = { read() }, onStop = { stopNow() })
                    }
                    EmphasisPhase.Thinking -> ThinkingPanel("Ascult ce ai citit")
                    EmphasisPhase.Result -> current?.let { take ->
                        Result(
                            take = take,
                            failed = failed,
                            isLast = position == set.size - 1,
                            onListen = clip?.let { c -> { app.scope.launch { app.audio.play(c) } } },
                            onAgain = { phase = EmphasisPhase.Ready },
                            onNext = { next() },
                        )
                    }
                    EmphasisPhase.Summary -> Summary(set, takes, tally(), onAgain = { begin() }, onClose = { close() })
                }
            }
        }
    }
}

@Composable
private fun Intro(content: EmphasisContent?, speech: SpeechHub, onStart: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.heightIn(min = 12.dp))
        FoniaText("Cuvântul important", style = fDisplay, color = Palette.ink)
        FoniaText(
            "Cineva îți pune o întrebare, iar tu răspunzi citind propoziția. Apasă pe cuvântul subliniat: spune-l puțin mai lung și mai tare decât restul.",
            style = fLead,
            color = Palette.ink,
        )
        FoniaText(
            "Telefonul măsoară cât de lung, cât de tare și cât de sus a fost acel cuvânt față de cuvintele din jur. Iese în față când trec cel puțin două din trei.",
            style = fBody,
            color = Palette.inkSoft,
        )
        if (content == null) {
            FoniaText("Nu găsesc propozițiile exercițiului.", style = fBodyStrong, color = Palette.warmDeep)
        } else if (content.isDraft) {
            FoniaText(content.notice, style = fCaption, color = Palette.inkSoft)
        }
        ModelNotice(speech)
        Spacer(Modifier.weight(1f))
        StyledButton(
            onClick = onStart,
            style = FoniButtonStyle(field = Field.paper),
            enabled = content != null && speech.state == SpeechHub.State.Ready,
        ) { FoniaText("Începem") }
    }
}

@Composable
private fun Prompt(
    item: EmphasisItem,
    isListening: Boolean,
    elapsed: Int,
    app: AppModel,
    problem: String?,
    onRead: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        item.context?.let { question ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FoniaText("Cineva te întreabă:", style = fCaption, color = Palette.inkSoft)
                FoniaText(
                    EmphasisWords.quoted(question),
                    Modifier
                        .background(Palette.cloud, RoundedCornerShape(18.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    style = fLead,
                    color = Palette.ink,
                )
            }
        }
        PracticeSheet {
            WordFlow(
                item.text.words,
                Modifier.padding(vertical = 14.dp),
                style = ui(28, UIWeight.medium, TextRole.title),
                centred = true,
                paint = { word ->
                    if (word.isKey) WordPaint(color = Palette.accentText, bold = true, underline = Palette.accent, state = "cuvântul de apăsat") else WordPaint()
                },
            )
        }
        FoniaText(
            if (isListening) "Citește propoziția. Te ascult." else "Apasă pe cuvântul subliniat.",
            style = fBody,
            color = Palette.inkSoft,
            textAlign = TextAlign.Center,
        )
        if (problem != null && !isListening) FoniaText(problem, style = fBodyStrong, color = Palette.warmDeep, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))
        if (isListening) {
            ListeningPanel(app, elapsed, "Gata", onStop)
        } else {
            StyledButton(onClick = onRead, style = FoniButtonStyle(field = Field.paper)) {
                FoniaLabel("Citește", FoniaIcon.microphone)
            }
        }
    }
}

private fun tone(verdict: EmphasisVerdict): androidx.compose.ui.graphics.Color = when (verdict) {
    EmphasisVerdict.Stressed -> Palette.right
    EmphasisVerdict.Almost -> Palette.almostDeep
    EmphasisVerdict.NotYet -> Palette.notYet
    EmphasisVerdict.NotHeard -> Palette.inkSoft
}

@Composable
private fun Result(
    take: EmphasisTake,
    failed: Boolean,
    isLast: Boolean,
    onListen: (() -> Unit)?,
    onAgain: () -> Unit,
    onNext: () -> Unit,
) {
    val analysis = take.analysis
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.heightIn(min = 8.dp))
        FoniaText(EmphasisWords.headline(take), style = fTitle, color = tone(take.verdict))
        if (failed) FoniaText("Nu am putut asculta acum. Mai încearcă o dată.", style = fBody, color = Palette.inkSoft)
        PracticeSheet {
            WordFlow(
                take.item.text.words,
                Modifier.padding(vertical = 12.dp),
                style = ui(24, UIWeight.medium, TextRole.title2),
                centred = true,
                paint = { word ->
                    val measured = analysis?.words?.getOrNull(word.index)
                    val stood = measured?.isStressed == true
                    WordPaint(
                        color = if (word.isKey) Palette.accentText else Palette.ink,
                        bold = word.isKey,
                        underline = if (word.isKey) Palette.accent else null,
                        fill = if (stood) Palette.tipSoft else null,
                        state = if (stood) "a ieșit în față" else null,
                    )
                },
            )
        }
        if (analysis != null && analysis.words.any { it.isStressed }) {
            FoniaText("Pe fond galben: cuvintele care au ieșit în față.", style = fCaption, color = Palette.inkSoft)
        }
        for (key in take.keys) {
            KeyWordCard(key, take.item.text.words[key.index].bare)
        }
        EmphasisWords.extraLine(take)?.let { FoniaText(it, style = fBody, color = Palette.ink) }
        EmphasisWords.tip(take)?.let { FoniaText(it, style = fBodyStrong, color = Palette.ink) }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onListen != null) {
                StyledButton(onClick = onListen, style = PillButtonStyle(), modifier = Modifier.weight(1f)) {
                    FoniaLabel("Ascultă-te", FoniaIcon.play, iconSize = 16f)
                }
            }
            StyledButton(onClick = onAgain, style = PillButtonStyle(), modifier = Modifier.weight(1f)) {
                FoniaLabel("Încă o dată", FoniaIcon.retry, iconSize = 16f)
            }
        }
        StyledButton(onClick = onNext, style = FoniButtonStyle(field = Field.paper)) {
            FoniaText(if (isLast) "Vezi cum a mers" else "Mai departe")
        }
    }
}

/** One marked word: what was measured, then a chip per cue with its value against the neighbours. */
@Composable
private fun KeyWordCard(word: WordEmphasis, shown: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.sheet, ContinuousRoundedRectangle(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FoniaText(EmphasisWords.keyLine(word, shown), style = fBody, color = Palette.ink)
        if (word.state != EmphasisState.NOT_MEASURED) {
            WrapLayout(spacing = 8.dp, lineSpacing = 8.dp) {
                for (cue in EmphasisCue.entries) CueChip(cue, word)
            }
        }
    }
}

@Composable
private fun CueChip(cue: EmphasisCue, word: WordEmphasis) {
    val passed = cue in word.passed
    val value = EmphasisWords.cueValue(cue, word) ?: "nemăsurat"
    val title = EmphasisWords.cueTitle(cue)
    Row(
        Modifier
            .background(if (passed) Palette.rightSoft else Palette.cloud, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title: $value, ${EmphasisWords.cueBar(cue)}" + if (passed) ", trecut" else ""
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (passed) FoniaIconView(FoniaIcon.tick, size = 13f, tint = Palette.right)
        FoniaText(title, style = fCaption, color = if (passed) Palette.rightDeep else Palette.inkSoft)
        FoniaText(value, style = ui(14, UIWeight.bold, TextRole.footnote), color = if (passed) Palette.rightDeep else Palette.ink)
    }
}

@Composable
private fun Summary(
    set: List<EmphasisItem>,
    takes: Map<Int, EmphasisTake>,
    tally: EmphasisTally,
    onAgain: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.heightIn(min = 8.dp))
        FoniaText("Cum a mers", style = fDisplay, color = Palette.ink)
        val lines = EmphasisWords.summary(tally)
        FoniaText(lines.first(), style = ui(20, UIWeight.bold, TextRole.title3), color = Palette.ink)
        for (line in lines.drop(1)) FoniaText(line, style = fBody, color = Palette.inkSoft)
        SpeakingHeading("Propozițiile")
        set.forEachIndexed { index, item ->
            val take = takes[index]
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Palette.sheet, ContinuousRoundedRectangle(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                WordFlow(item.text.words, style = ui(17, UIWeight.medium, TextRole.body), paint = { word ->
                    val stood = take?.analysis?.words?.getOrNull(word.index)?.isStressed == true
                    WordPaint(
                        color = if (word.isKey) Palette.accentText else Palette.ink,
                        bold = word.isKey,
                        underline = if (word.isKey) Palette.accent else null,
                        fill = if (stood) Palette.tipSoft else null,
                    )
                })
                FoniaText(take?.let { EmphasisWords.headline(it) } ?: "Necitită.", style = fCaption, color = take?.let { tone(it.verdict) } ?: Palette.inkSoft)
            }
        }
        Spacer(Modifier.weight(1f))
        StyledButton(onClick = onAgain, style = FoniButtonStyle(field = Field.paper, kind = FoniButtonStyle.Kind.secondary)) { FoniaText("Încă un set") }
        StyledButton(onClick = onClose, style = FoniButtonStyle(field = Field.paper)) { FoniaText("Gata") }
    }
}
