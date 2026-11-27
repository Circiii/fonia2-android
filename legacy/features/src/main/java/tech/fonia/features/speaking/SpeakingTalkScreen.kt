package tech.fonia.features.speaking

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.fonia.audio.CapturedClip
import tech.fonia.design.CTALabel
import tech.fonia.design.Field
import tech.fonia.design.FieldBackdrop
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.Foni
import tech.fonia.design.FoniMood
import tech.fonia.design.FoniTone
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.MiniPillStyle
import tech.fonia.design.Palette
import tech.fonia.design.QuietButtonStyle
import tech.fonia.design.PracticeSheet
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.ToolButtonStyle
import tech.fonia.design.UIWeight
import tech.fonia.design.VoiceBars
import tech.fonia.design.VoiceRings
import tech.fonia.design.fBody
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fDisplay
import tech.fonia.design.monospacedDigit
import tech.fonia.design.swiftSpring
import tech.fonia.design.ui
import tech.fonia.domain.DictionLesson
import tech.fonia.domain.Language
import tech.fonia.domain.Plural
import tech.fonia.domain.SpeechPrompt
import tech.fonia.features.app.ActivityIndicator
import tech.fonia.features.app.ActionRole
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.DialogAction
import tech.fonia.features.app.FoniaConfirmationDialog
import tech.fonia.features.app.LocalStillMeters
import tech.fonia.features.app.calibration
import tech.fonia.features.app.dspGroup
import tech.fonia.features.home.NextStep
import tech.fonia.features.home.PathModel
import tech.fonia.store.JSONValue
import tech.fonia.store.Profile
import tech.fonia.store.SpeechRecord
import java.util.Locale
import java.util.UUID
import kotlin.math.max

private enum class TalkPhase { Ready, Talking, Thinking, Feeling, Report }

/**
 * What is kept of a take's numbers. The rate of hesitations only when there was enough speech for it to mean anything;
 * the count and the seconds always, so a later version can compare however it likes.
 */
internal fun metrics(report: TalkReport): Map<String, JSONValue> = buildMap {
    put("syllablesPerSecond", JSONValue.Number(report.rate))
    put("pitchSpread", JSONValue.Number(report.pitchSpread))
    put("hesitationCount", JSONValue.Number(report.fillers.count.toDouble()))
    put("speechSeconds", JSONValue.Number(report.fillers.seconds))
    report.fillers.perMinute?.let { put("hesitationsPerMinute", JSONValue.Number(it)) }
}

/** The opening of the person's own talk: where nerves are highest, and short enough to say again and again. */
internal const val OPENING_SECONDS = 60

@Composable
internal fun TalkScreen(
    prompt: SpeechPrompt,
    language: Language,
    profile: Profile,
    app: AppModel,
    path: PathModel,
    /** Only the first minute of the person's own talk: it stops on time and says nothing about length. */
    openingOnly: Boolean = false,
    /** The tip for nerves in full, for the first talks. Later it waits behind "Ai emoții?". */
    showsNervesTip: Boolean = true,
    /** The hesitations of the person's earlier talks, oldest first: what a take's hesitations are compared with. */
    fillerHistory: List<FillerSample> = emptyList(),
    /** Called once per take that was heard all the way to the report, with that take's hesitations. */
    onFinished: (FillerSample) -> Unit = {},
    onDone: () -> Unit,
) {
    var phase by remember { mutableStateOf(TalkPhase.Ready) }
    var seconds by remember { mutableIntStateOf(prompt.seconds) }
    var previous by remember { mutableStateOf<TalkReport?>(null) }
    var takes by remember { mutableIntStateOf(0) }
    var elapsed by remember { mutableIntStateOf(0) }
    var clip by remember { mutableStateOf<CapturedClip?>(null) }
    var report by remember { mutableStateOf<TalkReport?>(null) }
    var feeling by remember { mutableStateOf<Int?>(null) }
    var ignoredFillers by remember { mutableStateOf(setOf<Int>()) }
    var micOff by remember { mutableStateOf(false) }
    var startFailed by remember { mutableStateOf(false) }
    var isStarting by remember { mutableStateOf(false) }
    var recordID by remember { mutableStateOf(UUID.randomUUID()) }
    // „Tu, fără ăăă”: the same take with its fillers cut out, the A/B view and what is playing.
    var noUm by remember { mutableStateOf<NoUmTake?>(null) }
    var cleanView by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val speakingAudio = remember(profile) { HubSpeakingAudio(app, profile) }

    /** Saved when the report opens and again on the way out, with the fillers the speaker corrected. */
    fun save() {
        val current = report ?: return
        val fillers = current.speech?.fillers ?: emptyList()
        val corrected: List<JSONValue> = ignoredFillers.sorted().mapNotNull { index -> fillers.getOrNull(index)?.let { JSONValue.String(it.word) } }
        val record = SpeechRecord(
            id = recordID,
            profileId = profile.id,
            lang = if (language == Language.Ro) SpeechRecord.Language.Ro else SpeechRecord.Language.En,
            prompt = prompt.title,
            duration = current.seconds,
            transcript = current.transcript,
            metrics = metrics(current),
            selfRating = feeling,
            correctedFillers = corrected,
        )
        val repository = app.repository
        app.scope.launch { runCatching { repository.saveSpeech(record) } }
    }

    // Asked before a take under way is thrown away.
    var confirmsLeaving by remember { mutableStateOf(false) }

    fun leave() {
        speakingAudio.stopPlayback()
        if (phase == TalkPhase.Report || phase == TalkPhase.Feeling) save()
        // Ends the timer loop of a take under way, so nothing stops or saves it after the page has gone.
        if (phase == TalkPhase.Talking) phase = TalkPhase.Ready
        app.scope.launch {
            app.audio.shutDown()
            onDone()
        }
    }

    /** Leaves the talk. A take still being recorded is asked about first; one already heard is kept as it is. */
    fun close() {
        if (phase == TalkPhase.Talking) confirmsLeaving = true else leave()
    }

    suspend fun stop() {
        if (phase != TalkPhase.Talking) return
        val heard = app.audio.stopListening() ?: return
        // Debug builds with `foniaFixtures`: a recorded take full of fillers stands in for the microphone.
        val captured = (if (app.audio.usesFixtures) SpeakingFixture.take(context) else null) ?: heard
        if (!(captured.sampleRate > 0) || captured.samples.isEmpty()) {
            startFailed = true
            phase = TalkPhase.Ready
            return
        }
        phase = TalkPhase.Thinking
        clip = captured
        val made = TalkReport.make(captured, language, profile, app.content, app.wordsTranscriber, if (openingOnly) 0 else seconds, fillerHistory)
        report = made
        if (made.focus != TalkFocus.NotHeard) onFinished(made.fillers)
        noUm = if (language == Language.Ro) NoUmTake.make(captured, report?.rawTranscript) else null
        cleanView = true
        phase = TalkPhase.Feeling
    }

    suspend fun start() {
        if (phase != TalkPhase.Ready || isStarting) return
        isStarting = true
        try {
            if (!app.audio.requestMicrophone()) {
                micOff = true
                return
            }
            try {
                app.audio.startListening(
                    group = profile.dspGroup,
                    calibration = profile.calibration,
                    fixtureText = "Bună ziua. Mă numesc Ana și astăzi vă prezint proiectul nostru.",
                    fixtureLanguage = Language.Ro,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                startFailed = true
                return
            }
            elapsed = 0
            phase = TalkPhase.Talking
            val limit = if (openingOnly) seconds else seconds + 30
            while (phase == TalkPhase.Talking && elapsed < limit) {
                delay(1000)
                if (phase == TalkPhase.Talking) elapsed += 1
            }
            if (phase == TalkPhase.Talking) stop()
        } finally {
            isStarting = false
        }
    }

    fun againShorter() {
        save()
        previous = report
        takes += 1
        // The opening keeps its minute: it is said again, not cut.
        if (!openingOnly) seconds = max(30, seconds * 2 / 3)
        report = null
        clip = null
        noUm = null
        speakingAudio.stopPlayback()
        feeling = null
        ignoredFillers = emptySet()
        recordID = UUID.randomUUID()
        phase = TalkPhase.Ready
    }

    fun drill(focus: TalkFocus): NextStep? {
        val name = when (focus) {
            TalkFocus.Hesitations, TalkFocus.TooFast, TalkFocus.PhrasesLong, TalkFocus.PhrasesShort -> "d-${DictionLesson.Pauses.rawValue}"
            TalkFocus.TooSlow -> "d-${DictionLesson.Pace.rawValue}"
            TalkFocus.FlatPitch -> "d-${DictionLesson.Glide.rawValue}"
            TalkFocus.NotHeard, TalkFocus.Time, TalkFocus.None -> null
        } ?: return null
        for (unit in path.catalog.dictionUnits) {
            unit.lessons.firstOrNull { it.id == name }?.let { return NextStep(unit, it) }
        }
        return null
    }

    // The Android back gesture: the X where there is one, "Am terminat" while talking, nothing while it listens back.
    BackHandler {
        when (phase) {
            TalkPhase.Ready, TalkPhase.Report -> close()
            TalkPhase.Talking -> app.scope.launch { stop() }
            TalkPhase.Thinking, TalkPhase.Feeling -> Unit
        }
    }

    FoniaConfirmationDialog(
        isPresented = confirmsLeaving,
        onDismiss = { confirmsLeaving = false },
        title = "Te oprești aici?",
        message = "Ce ai spus până acum nu se păstrează.",
        actions = listOf(
            DialogAction("Mă opresc", ActionRole.Destructive) { leave() },
            DialogAction("Mai rămân", ActionRole.Cancel),
        ),
    )
    Box(Modifier.fillMaxSize()) {
        FieldBackdrop(Field.paper)
        Crossfade(phase, Modifier.windowInsetsPadding(WindowInsets.systemBars), animationSpec = swiftSpring(0.34, 0.08), label = "talk") { shownPhase ->
            when (shownPhase) {
                TalkPhase.Ready -> Ready(prompt, seconds, openingOnly, showsNervesTip, micOff, startFailed, onClose = { close() }, onStart = { app.scope.launch { start() } })
                TalkPhase.Talking -> Talking(prompt, elapsed, app, onClose = { close() }) { app.scope.launch { stop() } }
                TalkPhase.Thinking -> Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    CloseBar { close() }
                    Spacer(Modifier.weight(1f))
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        ActivityIndicator()
                        FoniaText("Ascult ce ai spus", style = fBodyStrong, color = Palette.inkSoft)
                    }
                    Spacer(Modifier.weight(1f))
                }
                TalkPhase.Feeling -> FeelingStep(
                    onClose = { close() },
                    onSkip = {
                        feeling = null
                        phase = TalkPhase.Report
                        save()
                    },
                ) { value ->
                    feeling = value
                    phase = TalkPhase.Report
                    save()
                }
                TalkPhase.Report -> ReportView(
                    report = report,
                    previous = previous,
                    feeling = feeling,
                    takes = takes,
                    openingOnly = openingOnly,
                    clip = clip,
                    app = app,
                    noUm = noUm,
                    cleanView = cleanView,
                    playing = playing,
                    onView = { cleanView = it },
                    onPlay = {
                        val take = noUm
                        if (playing) {
                            speakingAudio.stopPlayback()
                        } else if (take != null) {
                            val (samples, rate) = if (cleanView) take.clean.samples to take.clean.sampleRate else (clip?.samples ?: take.clean.samples) to (clip?.sampleRate ?: take.clean.sampleRate)
                            app.scope.launch {
                                playing = true
                                try {
                                    speakingAudio.play(samples, rate)
                                } finally {
                                    playing = false
                                }
                            }
                        }
                    },
                    ignoredFillers = ignoredFillers,
                    drill = report?.let { drill(it.focus) },
                    onClose = { close() },
                    onAgain = { againShorter() },
                    onToggleFiller = { index -> ignoredFillers = if (index in ignoredFillers) ignoredFillers - index else ignoredFillers + index },
                    onDrill = { step ->
                        app.scope.launch {
                            app.audio.shutDown()
                            onDone()
                            app.start(step.lesson, step.unit)
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun CloseBar(onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        StyledButton(onClick = onClose, style = ToolButtonStyle(field = Field.paper), modifier = Modifier.semantics { contentDescription = "Închide" }) {
            FoniaIconView(FoniaIcon.close, size = 18f)
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun Ready(
    prompt: SpeechPrompt,
    seconds: Int,
    openingOnly: Boolean,
    showsNervesTip: Boolean,
    micOff: Boolean,
    startFailed: Boolean,
    onClose: () -> Unit,
    onStart: () -> Unit,
) {
    val durationText = if (seconds % 60 == 0) {
        Plural.format(seconds / 60, Plural.Noun.Minut)
    } else if (seconds % 100 >= 20) {
        "$seconds de secunde"
    } else {
        "$seconds secunde"
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CloseBar(onClose)
        Spacer(Modifier.weight(1f))
        FoniaText(prompt.title, style = fDisplay, color = Palette.ink, textAlign = TextAlign.Center)
        PracticeSheet {
            FoniaText(prompt.prompt, Modifier.padding(vertical = 12.dp), style = ui(22, UIWeight.medium, TextRole.title3), color = Palette.ink, textAlign = TextAlign.Center)
        }
        FoniaText(
            if (openingOnly) "Ai un minut pentru început." else "Ai $durationText. Oprește-te când ai terminat.",
            style = fBody,
            color = Palette.inkSoft,
            textAlign = TextAlign.Center,
        )
        NervesTip(showsNervesTip)
        if (micOff || startFailed) {
            FoniaText(
                if (micOff) "Microfonul e oprit. Pornește-l din Setări, la Fonia." else "Nu pot porni microfonul acum. Închide alte aplicații care îl folosesc și mai încearcă.",
                style = fBodyStrong,
                color = Palette.warmDeep,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.weight(1f))
        StyledButton(onClick = onStart, style = FoniButtonStyle(field = Field.paper)) { CTALabel("Începem") }
    }
}

/**
 * Saying "Abia aștept!" to yourself helps some people and not others, so after the first talks it is one tap away
 * instead of on every start.
 */
@Composable
private fun NervesTip(showsInFull: Boolean) {
    var open by remember { mutableStateOf(false) }
    when {
        showsInFull -> FoniaText("Ai emoții? Spune-ți în gând „Abia aștept!”", style = fBody, color = Palette.inkSoft, textAlign = TextAlign.Center)
        open -> FoniaText(
            "Spune-ți în gând „Abia aștept!”",
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = fBody,
            color = Palette.inkSoft,
            textAlign = TextAlign.Center,
        )
        else -> StyledButton(
            onClick = { open = true },
            style = MiniPillStyle(),
            modifier = Modifier.heightIn(min = 48.dp),
            onClickLabel = "Arată un sfat",
        ) { FoniaText("Ai emoții?") }
    }
}

@Composable
private fun Talking(prompt: SpeechPrompt, elapsed: Int, app: AppModel, onClose: () -> Unit, onStop: () -> Unit) {
    val levels = app.audio.levels
    val still = LocalStillMeters.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CloseBar(onClose)
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
            VoiceRings(color = FoniTone.neutral.glow, isLive = !still, level = { levels.level }, modifier = Modifier.requiredSize(320.dp))
            Foni(Modifier.size(180.dp), mood = FoniMood.listening, level = { levels.level })
        }
        FoniaText(
            String.format(Locale.ROOT, "%d:%02d", elapsed / 60, elapsed % 60),
            Modifier
                .heightIn(min = 46.dp)
                .background(Palette.sheet, RoundedCornerShape(50))
                .padding(horizontal = 22.dp, vertical = 10.dp),
            style = ui(22, UIWeight.bold, TextRole.title2).monospacedDigit(),
            color = Palette.ink,
        )
        VoiceBars(isLive = !still, color = Palette.ink, level = { levels.level }, modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp))
        FoniaText(prompt.prompt, style = fBody, color = Palette.inkSoft, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))
        StyledButton(onClick = onStop, style = FoniButtonStyle(field = Field.paper)) { FoniaText("Am terminat") }
    }
}

@Composable
private fun FeelingStep(onClose: () -> Unit, onSkip: () -> Unit, onPick: (Int) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CloseBar(onClose)
        Spacer(Modifier.weight(1f))
        FoniaText("Cum ți s-a părut?", style = fDisplay, color = Palette.ink)
        FoniaText("Întâi spui tu, apoi îți spun și eu.", style = fBody, color = Palette.inkSoft)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((value, title) in listOf(3 to "A mers bine", 2 to "A fost în regulă", 1 to "M-am încurcat")) {
                StyledButton(
                    onClick = { onPick(value) },
                    style = FoniButtonStyle(field = Field.paper, size = FoniButtonStyle.Size.regular, kind = FoniButtonStyle.Kind.secondary),
                ) { FoniaText(title) }
            }
        }
        StyledButton(onClick = onSkip, style = QuietButtonStyle(Palette.ink)) { FoniaText("Sar peste") }
        Spacer(Modifier.weight(1f))
    }
}
