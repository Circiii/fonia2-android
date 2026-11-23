package tech.fonia.features.speaking

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.fonia.audio.CapturedClip
import tech.fonia.design.ArtTileMark
import tech.fonia.design.CTALabel
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.Field
import tech.fonia.design.FieldBackdrop
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.Foni
import tech.fonia.design.FoniMood
import tech.fonia.design.FoniTone
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaLabel
import tech.fonia.design.FoniaText
import tech.fonia.design.MiniPillStyle
import tech.fonia.design.PaceChart
import tech.fonia.design.Palette
import tech.fonia.design.PillButtonStyle
import tech.fonia.design.PracticeSheet
import tech.fonia.design.PressableStyle
import tech.fonia.design.SceneName
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.ToolButtonStyle
import tech.fonia.design.UIWeight
import tech.fonia.design.VoiceBars
import tech.fonia.design.VoiceRings
import tech.fonia.design.WrapLayout
import tech.fonia.design.fBody
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fCaption
import tech.fonia.design.fDisplay
import tech.fonia.design.fHeadline
import tech.fonia.design.fTitle
import tech.fonia.design.monospacedDigit
import tech.fonia.design.swiftSpring
import tech.fonia.design.ui
import tech.fonia.domain.DictionLesson
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.Language
import tech.fonia.domain.Plural
import tech.fonia.domain.SpeechPrompt
import tech.fonia.dsp.TimeSpan
import tech.fonia.dsp.heardSpeech
import tech.fonia.features.app.ActivityIndicator
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.FoniaSheet
import tech.fonia.features.app.FullScreenCover
import tech.fonia.features.app.LocalStillMeters
import tech.fonia.features.app.ScenePage
import tech.fonia.features.app.ScenePanel
import tech.fonia.features.app.SegmentedPicker
import tech.fonia.features.app.SheetDetent
import tech.fonia.features.app.SystemColors
import tech.fonia.features.app.calibration
import tech.fonia.features.app.dspGroup
import tech.fonia.features.app.rememberStoredInt
import tech.fonia.features.app.rememberStoredString
import tech.fonia.features.home.CardRow
import tech.fonia.features.home.ModelNotice
import tech.fonia.features.home.NextStep
import tech.fonia.features.home.PathModel
import tech.fonia.features.session.TakeJudge
import tech.fonia.features.session.romanianDecimal
import tech.fonia.speech.FillerHit
import tech.fonia.speech.FillerLexicon
import tech.fonia.speech.SpeechJudge
import tech.fonia.speech.SpeechLanguage
import tech.fonia.speech.SpeechReport
import tech.fonia.speech.SpeechResampler
import tech.fonia.speech.Transcriber
import tech.fonia.speech.speechReport
import tech.fonia.designsystem.motion.staggeredEntrance
import tech.fonia.store.JSONValue
import tech.fonia.store.Profile
import tech.fonia.store.SpeechRecord
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

/**
 * Vorbit în public. You talk, listen to yourself, then say it again shorter: that loop is what changes how someone
 * speaks. A real talk you're preparing gets its own place, and the short drills sit below.
 */
@Composable
fun SpeakingHome(profile: Profile, app: AppModel, path: PathModel) {
    var language by remember { mutableStateOf(Language.Ro) }
    var talk by remember { mutableStateOf<SpeechPrompt?>(null) }
    var editingMine by remember { mutableStateOf(false) }
    var myTitle by rememberStoredString(app.preferences, "fonia.myTalk.title", "")
    var myMinutes by rememberStoredInt(app.preferences, "fonia.myTalk.minutes", 5)
    val prompts = app.content.diction.speechPrompts
    val todaysPrompt = prompts[todaysIndex(prompts.size)]
    val myTalk = SpeechPrompt(title = myTitle, prompt = "Spune-ți prezentarea așa cum ai spune-o în fața oamenilor.", seconds = myMinutes * 60)

    LaunchedEffect(Unit) { if (profile.track == Profile.Track.En) language = Language.En }
    // Not on iOS: the emphasis exercise and the BAC simulation (SpeakingExtras.kt).
    val extras = rememberSpeakingExtras()

    // The talk covers this page, which already covers the whole screen.
    Box(Modifier.fillMaxSize()) {
        ScenePage(
            scene = SceneName.speaking,
            title = "Vorbit în public",
            sentence = "Spui, te asculți, apoi spui din nou, mai scurt.",
            panel = ScenePanel.attached,
            heroHeight = 250.dp,
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SegmentedPicker(listOf("Română", "Engleză"), if (language == Language.Ro) 0 else 1) {
                    language = if (it == 0) Language.Ro else Language.En
                }

                Heading("Azi")
                TalkRow(ArtTileMark.Art("art-megaphone"), "Un minut pe zi", todaysPrompt.prompt) { talk = todaysPrompt }

                Heading("Prezentarea mea")
                if (myTitle.isEmpty()) {
                    TalkRow(ArtTileMark.Art("art-start"), "Ai de ținut o prezentare?", "Scrie-i numele și cât vrei să dureze. Apoi o repeți aici.") {
                        editingMine = true
                    }
                } else {
                    TalkRow(ArtTileMark.Art("art-start"), myTitle, "Țintă: ${Plural.format(myMinutes, Plural.Noun.Minut)}") { talk = myTalk }
                    StyledButton(onClick = { editingMine = true }, style = MiniPillStyle()) { FoniaText("Schimbă prezentarea") }
                }

                Heading("Exerciții scurte")
                for (drill in drills) {
                    TalkRow(ArtTileMark.Art("art-target"), drill.title, drill.shortDuration) { talk = drill }
                }
                for (unit in path.catalog.dictionUnits.filter { it.id != "d-breath" }) {
                    for (lesson in unit.lessons) {
                        TalkRow(ArtTileMark.Art(lesson.art), lesson.title, lesson.detail) { app.start(lesson, unit) }
                    }
                }

                SpeakingExtraRows(extras, language)

                Heading("Alte subiecte")
                for (prompt in prompts) {
                    TalkRow(ArtTileMark.Art("art-bubble"), prompt.title, prompt.shortDuration) { talk = prompt }
                }

                ModelNotice(app.speech)
            }
        }

        // `.fullScreenCover(item: $talk)`: the cover keeps its talk while it slides away.
        var shown by remember { mutableStateOf<SpeechPrompt?>(null) }
        SideEffect { if (talk != null) shown = talk }
        FullScreenCover(isPresented = talk != null) {
            val prompt = talk ?: shown
            if (prompt != null) {
                androidx.compose.runtime.key(prompt) {
                    TalkScreen(prompt, language, profile, app, path) { talk = null }
                }
            }
        }
        SpeakingExtraCovers(extras, language, profile, app)
    }

    FoniaSheet(isPresented = editingMine, onDismiss = { editingMine = false }, detents = listOf(SheetDetent.medium), showsDragIndicator = true) {
        MyTalkEditor(myTitle, myMinutes, onMinutes = { myMinutes = it }) { title ->
            myTitle = title
            editingMine = false
        }
    }
}

/**
 * A new subject each day, the same all day: `Calendar.current.ordinality(of: .day, in: .era, for: .now)`. Foundation
 * counts the days of the era from 1 January of year 1 in the proleptic Gregorian calendar (on 5 November 2026 it
 * gives 739 925, and iOS showed "Prezintă-te", the subject at that index modulo 5), so: the epoch day plus 719 163.
 */
internal fun todaysIndex(count: Int, today: LocalDate = LocalDate.now()): Int {
    val day = today.toEpochDay() + 719_163
    return (day % max(count, 1)).toInt()
}

private val drills = listOf(
    SpeechPrompt(
        title = "Pauză în loc de „ăă”",
        prompt = "Povestește ce ai făcut ieri. Când simți că vine un „ăă”, închide gura și oprește-te o clipă.",
        seconds = 60,
    ),
    SpeechPrompt(
        title = "Spune clar",
        prompt = "Citește cu voce tare primele rânduri dintr-o carte, apoi citește-le din nou deschizând bine gura, ca pentru cineva aflat departe.",
        seconds = 60,
    ),
)

/** "45 s", "2 min": all a row has room for. */
val SpeechPrompt.shortDuration: String
    get() = if (seconds % 60 == 0) "${seconds / 60} min" else "$seconds s"

@Composable
private fun Heading(title: String) {
    FoniaText(title, Modifier.padding(start = 4.dp, top = 4.dp).semantics { heading() }, style = fHeadline, color = Palette.ink)
}

@Composable
private fun TalkRow(tile: ArtTileMark, title: String, detail: String, onClick: () -> Unit) {
    StyledButton(onClick = onClick, style = PressableStyle()) {
        CardRow(tile, title, detail) { StartMark() }
    }
}

@Composable
private fun StartMark() {
    Box(Modifier.size(32.dp).background(Palette.accent, CircleShape), contentAlignment = Alignment.Center) {
        FoniaIconView(FoniaIcon.play, size = 14f, tint = Palette.buttonLabel)
    }
}

/** A real talk the person is preparing: its name and how long it should last. */
@Composable
private fun MyTalkEditor(title: String, minutes: Int, onMinutes: (Int) -> Unit, onDone: (String) -> Unit) {
    var draft by remember(title) { mutableStateOf(title) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.paper)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FoniaText("Prezentarea mea", style = fTitle, color = Palette.ink)
        Box(
            Modifier
                .fillMaxWidth()
                .background(Palette.sheet, ContinuousRoundedRectangle(14.dp))
                .padding(14.dp),
        ) {
            BasicTextField(
                draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = fBody.copy(color = Palette.ink),
                singleLine = true,
                cursorBrush = SolidColor(Palette.accentText),
                decorationBox = { field ->
                    if (draft.isEmpty()) FoniaText("Cum se numește?", style = fBody, color = SystemColors.tertiaryLabel)
                    field()
                },
            )
        }
        Stepper("Durează ${Plural.format(minutes, Plural.Noun.Minut)}", minutes, 1..45, onMinutes)
        Spacer(Modifier.weight(1f))
        StyledButton(onClick = { onDone(draft.trim()) }, style = FoniButtonStyle()) { FoniaText("Gata") }
    }
}

/** `Stepper(_:value:in:)`: the label, and minus and plus in one grey capsule. */
@Composable
private fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FoniaText(label, Modifier.weight(1f), style = fBodyStrong, color = Palette.ink)
        Row(
            Modifier
                .size(width = 94.dp, height = 32.dp)
                .background(SystemColors.fill.copy(alpha = 0.5f), RoundedCornerShape(50)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepperHalf("−", "Mai puțin", enabled = value > range.first) { onChange(value - 1) }
            Box(Modifier.width(1.dp).height(18.dp).background(SystemColors.separator))
            StepperHalf("+", "Mai mult", enabled = value < range.last) { onChange(value + 1) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.StepperHalf(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .height(32.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        FoniaText(symbol, Modifier.alpha(if (enabled) 1f else 0.3f), style = ui(20, UIWeight.medium), color = SystemColors.label)
    }
}

private enum class TalkPhase { Ready, Talking, Thinking, Feeling, Report }

@Composable
private fun TalkScreen(prompt: SpeechPrompt, language: Language, profile: Profile, app: AppModel, path: PathModel, onDone: () -> Unit) {
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
            metrics = mapOf(
                "syllablesPerSecond" to JSONValue.Number(current.rate),
                "hesitationsPerMinute" to JSONValue.Number(current.hesitationsPerMinute),
                "pitchSpread" to JSONValue.Number(current.pitchSpread),
            ),
            selfRating = feeling,
            correctedFillers = corrected,
        )
        val repository = app.repository
        app.scope.launch { runCatching { repository.saveSpeech(record) } }
    }

    fun close() {
        speakingAudio.stopPlayback()
        if (phase == TalkPhase.Report) save()
        app.scope.launch {
            app.audio.shutDown()
            onDone()
        }
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
        report = TalkReport.make(captured, language, profile, app.content, app.speech.transcriber, seconds)
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
            val limit = seconds + 30
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
        seconds = max(30, seconds * 2 / 3)
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

    Box(Modifier.fillMaxSize()) {
        FieldBackdrop(Field.paper)
        Crossfade(phase, Modifier.windowInsetsPadding(WindowInsets.systemBars), animationSpec = swiftSpring(0.34, 0.08), label = "talk") { shownPhase ->
            when (shownPhase) {
                TalkPhase.Ready -> Ready(prompt, seconds, micOff, startFailed, onClose = { close() }, onStart = { app.scope.launch { start() } })
                TalkPhase.Talking -> Talking(prompt, elapsed, app) { app.scope.launch { stop() } }
                TalkPhase.Thinking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        ActivityIndicator()
                        FoniaText("Ascult ce ai spus", style = fBodyStrong, color = Palette.inkSoft)
                    }
                }
                TalkPhase.Feeling -> FeelingStep { value ->
                    feeling = value
                    phase = TalkPhase.Report
                    save()
                }
                TalkPhase.Report -> ReportView(
                    report = report,
                    previous = previous,
                    feeling = feeling,
                    takes = takes,
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
private fun CloseBar(onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        StyledButton(onClick = onClose, style = ToolButtonStyle(field = Field.paper), modifier = Modifier.semantics { contentDescription = "Închide" }) {
            FoniaIconView(FoniaIcon.close, size = 18f)
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun Ready(prompt: SpeechPrompt, seconds: Int, micOff: Boolean, startFailed: Boolean, onClose: () -> Unit, onStart: () -> Unit) {
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
        FoniaText("Ai $durationText. Oprește-te când ai terminat.", style = fBody, color = Palette.inkSoft, textAlign = TextAlign.Center)
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

@Composable
private fun Talking(prompt: SpeechPrompt, elapsed: Int, app: AppModel, onStop: () -> Unit) {
    val levels = app.audio.levels
    val still = LocalStillMeters.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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
private fun FeelingStep(onPick: (Int) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ReportView(
    report: TalkReport?,
    previous: TalkReport?,
    feeling: Int?,
    takes: Int,
    clip: CapturedClip?,
    app: AppModel,
    noUm: NoUmTake?,
    cleanView: Boolean,
    playing: Boolean,
    onView: (Boolean) -> Unit,
    onPlay: () -> Unit,
    ignoredFillers: Set<Int>,
    drill: NextStep?,
    onClose: () -> Unit,
    onAgain: () -> Unit,
    onToggleFiller: (Int) -> Unit,
    onDrill: (NextStep) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        CloseBar(onClose)
        if (report == null) return@Column
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Foni(Modifier.size(84.dp), mood = if (feeling == 1) FoniMood.idle else FoniMood.happy, reactsToTap = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FoniaText(report.headline, style = ui(22, UIWeight.bold, TextRole.title2), color = Palette.ink)
                val change = previous?.let { report.change(it) }
                if (change != null) FoniaText(change, style = fBodyStrong, color = Palette.accentText)
            }
        }

        // One main thing to do next: the same talk again, shorter. The rest stays quiet.
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StyledButton(onClick = onAgain, style = FoniButtonStyle(field = Field.paper, size = FoniButtonStyle.Size.regular)) {
                FoniaText(if (takes == 0) "Încă o dată, mai scurt" else "Mai încerc o dată")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (clip != null && noUm?.worthPlaying != true) {
                    StyledButton(onClick = { app.scope.launch { app.audio.play(clip) } }, style = PillButtonStyle(), modifier = Modifier.weight(1f)) {
                        FoniaLabel("Ascultă-te", FoniaIcon.play, iconSize = 16f)
                    }
                }
                if (drill != null) {
                    StyledButton(onClick = { onDrill(drill) }, style = PillButtonStyle(), modifier = Modifier.weight(1f)) {
                        FoniaLabel("Exercițiul potrivit", FoniaIcon.arrow, iconSize = 16f)
                    }
                }
            }
        }

        if (noUm != null && noUm.worthPlaying) {
            NoUmBlock(noUm, cleanView, playing, onView, onPlay, Modifier.staggeredEntrance(1))
        }

        Column(Modifier.staggeredEntrance(2), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FoniaText("Ritmul", style = fHeadline, color = Palette.ink)
            PaceChart(
                samples = report.paceTimeline,
                zone = report.paceZone,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .semantics { contentDescription = report.paceSentence },
                maximum = report.paceMax,
                line = Palette.ink,
                zoneColor = Palette.accentSoft,
            )
            FoniaText(report.paceSentence, style = fBody, color = Palette.inkSoft)
        }

        val fillers = report.speech?.fillers ?: emptyList()
        if (fillers.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FoniaText("Ce am auzit în plus", style = fHeadline, color = Palette.ink)
                FoniaText("Atinge ce trebuia să rămână.", style = fCaption, color = Palette.inkSoft)
                WrapLayout(spacing = 8.dp, lineSpacing = 8.dp) {
                    fillers.forEachIndexed { index, hit -> FillerChip(hit, index in ignoredFillers) { onToggleFiller(index) } }
                }
                FoniaText(app.content.diction.fillerNote, style = fCaption, color = Palette.inkSoft)
            }
        }

        for (detail in report.details) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FoniaText(detail.title, style = fHeadline, color = Palette.ink)
                FoniaText(detail.sentence, style = fBody, color = Palette.inkSoft)
            }
        }

        val transcript = report.transcript
        val marked = noUm?.markedTranscript(cleanView)
        if (marked != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FoniaText("Ce ai spus", style = fHeadline, color = Palette.ink)
                NoUmTranscript(marked)
            }
        } else if (!transcript.isNullOrEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FoniaText("Ce ai spus", style = fHeadline, color = Palette.ink)
                FoniaText(transcript, style = fBody, color = Palette.ink)
            }
        } else if (app.speech.state != tech.fonia.features.app.SpeechHub.State.Ready) {
            ModelNotice(app.speech)
        }
    }
}

@Composable
private fun FillerChip(hit: FillerHit, ignored: Boolean, onToggle: () -> Unit) {
    StyledButton(onClick = onToggle, style = PressableStyle(), onClickLabel = if (ignored) "Îl pune la loc" else "Îl scoate din numărătoare") {
        Box(
            Modifier
                .heightIn(min = 44.dp)
                .background(Palette.sheet, RoundedCornerShape(50))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            FoniaText(
                hit.word,
                style = fBodyStrong.copy(textDecoration = if (ignored) TextDecoration.LineThrough else null),
                color = if (ignored) Palette.inkSoft else Palette.ink,
            )
        }
    }
}

/** What a take most needs next, in the order it should be said. Only ever one. */
enum class TalkFocus { NotHeard, Time, Hesitations, TooFast, TooSlow, PhrasesShort, PhrasesLong, FlatPitch, None }

/**
 * One take as a report. One sentence, one thing to try, and no score: scores have nothing behind them, and a number in
 * the middle of a scale only confuses. The ranges are guides, not verdicts.
 */
data class TalkReport(
    val seconds: Double,
    /** Syllables per second over the whole time spent speaking, pauses included. */
    val rate: Double,
    val focus: TalkFocus,
    val headline: String,
    val paceTimeline: List<Double>,
    val paceZone: ClosedFloatingPointRange<Double>,
    val paceMax: Double,
    val paceSentence: String,
    val details: List<Detail>,
    val hesitationsPerMinute: Double,
    val medianPhrase: Int?,
    val pitchSpread: Double,
    val transcript: String?,
    val speech: SpeechReport?,
    /** Whisper's verbatim words, kept for „Ascultă-te fără ăăă” so the take is not transcribed twice. */
    val rawTranscript: tech.fonia.speech.Transcript? = null,
) {
    data class Detail(val title: String, val sentence: String)

    /** Only changes bigger than the noise between two takes get mentioned. */
    fun change(earlier: TalkReport): String? {
        if (earlier.hesitationsPerMinute - hesitationsPerMinute >= 2) return "Mai puține „ăă” decât data trecută."
        if (pitchSpread - earlier.pitchSpread >= 0.5) return "Vocea a urcat și a coborât mai mult decât data trecută."
        if (earlier.rate > 0 && abs(rate - earlier.rate) / earlier.rate >= 0.1) {
            return if (rate < earlier.rate) "Ai vorbit mai rar decât data trecută." else "Ai vorbit mai repede decât data trecută."
        }
        if (seconds < earlier.seconds * 0.9) return "Ai spus-o mai scurt decât data trecută."
        return null
    }

    companion object {
        /** Syllables a speech needs before its pace means anything, about fifteen seconds' worth. */
        private const val MINIMUM_SYLLABLES = 60

        suspend fun make(
            clip: CapturedClip,
            language: Language,
            profile: Profile,
            content: FoniaContent,
            transcriber: Transcriber?,
            target: Int,
        ): TalkReport {
            val summary = TakeJudge.summarise(clip, profile.dspGroup, profile.calibration)
            var speechReport: SpeechReport? = null
            var heardText: String? = null
            var raw: tech.fonia.speech.Transcript? = null
            if (transcriber != null) {
                try {
                    withContext(Dispatchers.Default) {
                        val samples = SpeechResampler.resample(clip.samples, clip.sampleRate)
                        val lang = if (language == Language.Ro) SpeechLanguage.RO else SpeechLanguage.EN
                        val transcript = transcriber.transcribe(samples, lang, verbatim = true)
                        heardText = transcript.text
                        raw = transcript
                        val list = if (language == Language.Ro) content.diction.fillers.ro else content.diction.fillers.en
                        val lexicon = FillerLexicon(
                            hesitationLabel = list.hesitations.firstOrNull() ?: "ăă",
                            fillers = list.hesitations + list.discourseMarkers,
                            ambiguous = list.discourseMarkers,
                        )
                        speechReport = SpeechJudge.speechReport(
                            transcript,
                            language = lang,
                            lexicon = lexicon,
                            hesitations = summary.hesitations.map { tech.fonia.speech.TimeSpan(it.start, it.end) },
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // `try?`: no transcript, the rest of the report still stands.
                }
            }

            val zone = if (language == Language.Ro) 3.2..5.0 else 2.75..4.25
            val rate = summary.speechRate.toDouble()
            val span = max(summary.speechEnd - summary.speechStart, 0.1)
            val spokenMinutes = span / 60
            val hesitationCount = summary.hesitations.size
            val hesitationsPerMinute = if (spokenMinutes > 0) hesitationCount.toDouble() / spokenMinutes else 0.0
            val runs = phraseLengths(summary.syllables, summary.pauses)
            val median = if (runs.size >= 8) runs.sorted()[runs.size / 2] else null
            val pitchSpread = summary.pvq.toDouble() * 17.3
            val longPauses = summary.pauses.count { it.duration >= 3 }
            val enoughForPace = summary.syllables.size >= MINIMUM_SYLLABLES
            val shown = romanianDecimal(rate)

            val focus: TalkFocus
            val headline: String
            if (!heardSpeech(summary)) {
                focus = TalkFocus.NotHeard
                headline = "Nu te-am auzit. Ține telefonul mai aproape și mai încearcă."
            } else if (target >= 60 && abs(span - target.toDouble()) > target.toDouble() * 0.1) {
                focus = TalkFocus.Time
                headline = "Ai ținut ${clock(span.toInt())} din ${clock(target)}. Vezi ce poți lăsa deoparte sau ce poți adăuga."
            } else if (spokenMinutes >= 1 && hesitationCount >= 3 && hesitationsPerMinute >= 12) {
                focus = TalkFocus.Hesitations
                headline = "Ai multe „ăă”. Când vine unul, închide gura și oprește-te o clipă."
            } else if (enoughForPace && rate > zone.endInclusive) {
                focus = TalkFocus.TooFast
                headline = "Ai vorbit repede. Încearcă o pauză după fiecare idee."
            } else if (enoughForPace && rate < zone.start) {
                focus = TalkFocus.TooSlow
                headline = "Ai vorbit rar. Leagă cuvintele din aceeași idee."
            } else if (median != null && median < 6) {
                focus = TalkFocus.PhrasesShort
                headline = "Frazele au ieșit tăiate. Spune o idee dintr-o suflare."
            } else if (median != null && median > 20) {
                focus = TalkFocus.PhrasesLong
                headline = "Frazele au fost lungi. Oprește-te după fiecare idee."
            } else if (summary.speechSeconds >= 30 && pitchSpread < 2) {
                focus = TalkFocus.FlatPitch
                headline = "Vocea a stat mai mult pe loc. Încearcă să apeși pe cuvântul important."
            } else {
                focus = TalkFocus.None
                headline = "A curs bine. Încearcă să spui același lucru în mai puțin timp."
            }

            val paceSentence = if (!enoughForPace) {
                "E prea puțin ca să spun ceva despre ritm."
            } else if (rate in zone) {
                "Un ritm obișnuit, $shown silabe pe secundă. Zona verde e doar un reper."
            } else {
                "$shown silabe pe secundă. Zona verde e doar un reper."
            }

            val details = mutableListOf<Detail>()
            if (longPauses > 0) {
                details += Detail("Pauze lungi", if (longPauses == 1) "O pauză de peste 3 secunde." else "$longPauses pauze de peste 3 secunde.")
            }
            if (spokenMinutes >= 1 && hesitationCount >= 3) {
                details += Detail("„Ăă”", "Cam ${roundedHalfAway(hesitationsPerMinute)} pe minut. Până la 5 aproape că nu se observă.")
            }
            if (summary.speechSeconds >= 30 && pitchSpread >= 2.8) {
                details += Detail("Vocea", "A urcat și a coborât frumos.")
            }

            return TalkReport(
                seconds = clip.duration,
                rate = rate,
                focus = focus,
                headline = headline,
                paceTimeline = paceLine(summary.syllables, summary.speechStart, summary.speechEnd),
                paceZone = zone,
                paceMax = zone.endInclusive * 1.6,
                paceSentence = paceSentence,
                details = details,
                hesitationsPerMinute = hesitationsPerMinute,
                medianPhrase = median,
                pitchSpread = pitchSpread,
                transcript = heardText,
                rawTranscript = raw,
                speech = speechReport,
            )
        }

        /** `String(format: "%d:%02d", ...)`. */
        private fun clock(seconds: Int): String = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)

        /** Swift's `rounded()`: halves away from zero. */
        private fun roundedHalfAway(value: Double): Int = (if (value < 0) -Math.floor(-value + 0.5) else Math.floor(value + 0.5)).toInt()

        internal fun phraseLengths(syllables: List<Double>, pauses: List<TimeSpan>): List<Int> {
            val breaks = pauses.filter { it.duration >= 0.25 }.map { it.start }.sorted()
            val counts = IntArray(breaks.size + 1)
            for (time in syllables) {
                val found = breaks.indexOfFirst { time < it }
                counts[if (found < 0) breaks.size else found] += 1
            }
            return counts.filter { it > 0 }
        }

        /** Pace in a fifteen second window moving five seconds at a time, so the line doesn't jump about. */
        internal fun paceLine(syllables: List<Double>, start: Double, end: Double): List<Double> {
            val window = 15.0
            val span = end - start
            if (!(span > 0)) return listOf(0.0, 0.0)
            if (!(span > window)) return listOf(syllables.size / span, syllables.size / span)
            // `stride(from:through:by:)` on Double: each value is start + n × 5, through the last one not past the end.
            val result = mutableListOf<Double>()
            var n = 0
            while (true) {
                val from = start + n * 5.0
                if (from > end - window) break
                result += syllables.count { it >= from && it < from + window }.toDouble() / window
                n += 1
            }
            return result
        }
    }
}
