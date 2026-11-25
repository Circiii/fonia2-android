package tech.fonia.features.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.fonia.design.CTALabel
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.Dock
import tech.fonia.design.Field
import tech.fonia.design.FieldBackdrop
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.Foni
import tech.fonia.design.FoniMood
import tech.fonia.design.FoniTone
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.PracticeWeight
import tech.fonia.design.QuietButtonStyle
import tech.fonia.design.SceneBackdrop
import tech.fonia.design.SceneName
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.ChoiceTileStyle
import tech.fonia.design.DockHint
import tech.fonia.design.TileButtonStyle
import tech.fonia.design.ToolButtonStyle
import tech.fonia.design.UIWeight
import tech.fonia.design.VoiceBars
import tech.fonia.design.VoiceRings
import tech.fonia.design.WordPicture
import tech.fonia.design.easeOut
import tech.fonia.design.fBody
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fCaption
import tech.fonia.design.fDisplay
import tech.fonia.design.fHeadline
import tech.fonia.design.fLead
import tech.fonia.design.fPrompt
import tech.fonia.design.monospacedDigit
import tech.fonia.design.practice
import tech.fonia.design.swiftSpring
import tech.fonia.design.ui
import tech.fonia.domain.FoniaContent
import tech.fonia.dsp.VoiceGroup
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.FoniaSheet
import tech.fonia.features.grownups.LegalInfo
import tech.fonia.features.grownups.PrivacyNote
import tech.fonia.features.grownups.PrivacyText
import tech.fonia.features.app.SoundEffect
import tech.fonia.features.app.field
import tech.fonia.store.Profile

@Suppress("EnumEntryName")
private enum class Step { voice, forAdult, who, child, adult }

/** First run starts with a sound, not with slides: say "aaa" and Foni swells with you. */
@Composable
fun FirstRunFlow(app: AppModel) {
    var step by rememberSaveable { mutableStateOf(Step.voice) }
    fun finish(profile: Profile) {
        app.scope.launch {
            app.audio.shutDown()
            app.save(profile)
            app.select(profile)
            // The first run ends on the quick check's invitation („Hai să vedem ce sunete exersăm”).
            app.offerCheck = profile.track == Profile.Track.Ro
        }
    }
    AnimatedContent(
        targetState = step,
        transitionSpec = {
            // Each step comes in and goes out by its own transition: the voice fades, the others fade and slide 24.
            val spring = swiftSpring<Float>(0.3, 0.0)
            val slide = swiftSpring<androidx.compose.ui.unit.IntOffset>(0.3, 0.0)
            val enter: EnterTransition = if (targetState == Step.voice) fadeIn(spring) else fadeIn(spring) + slideInHorizontally(slide) { (24 * density).toInt() }
            val exit: ExitTransition = if (initialState == Step.voice) fadeOut(spring) else fadeOut(spring) + slideOutHorizontally(slide) { (24 * density).toInt() }
            enter togetherWith exit
        },
        label = "first-run",
    ) { shown ->
        when (shown) {
            Step.voice -> VoiceWelcome(app) { step = Step.who }
            // The note for the adult next to a child comes only on a child's path; an adult learner reads what stays
            // on the phone on their own setup page.
            Step.who -> WhoIsPractising(onChild = { step = Step.forAdult }, onAdult = { step = Step.adult })
            Step.forAdult -> ForTheAdult(withReading = app.arguments.drafts, onBack = { step = Step.who }, onNext = { step = Step.child })
            Step.child -> ChildSetup(app, onBack = { step = Step.forAdult }, onDone = { finish(it) })
            Step.adult -> AdultSetup(onBack = { step = Step.who }, onDone = { finish(it) })
        }
    }
}

/** The screen's density, for offsets given in points. */
private val density: Float get() = android.content.res.Resources.getSystem().displayMetrics.density

@Composable
private fun VoiceWelcome(app: AppModel, onNext: () -> Unit) {
    var isListening by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var silent by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val levels = app.audio.levels
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(heard) { if (heard) haptics.performHapticFeedback(HapticFeedbackType.Confirm) }

    suspend fun listen() {
        if (!app.audio.requestMicrophone()) {
            denied = true
            return
        }
        try {
            app.audio.startListening(group = VoiceGroup.FEMALE, calibration = null, fixtureText = "Bună ziua. Mă numesc Ana și astăzi vă prezint proiectul nostru.")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            silent = true
            return
        }
        isListening = true
        var loud = 0.0
        val started = System.nanoTime()
        while ((System.nanoTime() - started) / 1e9 < 20) {
            delay(50)
            if (levels.level > 0.22f) loud += 0.05
            if (loud >= 0.5) break
        }
        app.audio.cancelListening()
        isListening = false
        heard = loud >= 0.5
        silent = !heard
        if (heard) app.audio.effect(SoundEffect.Star)
    }

    fun leave() {
        listening?.cancel()
        app.scope.launch { app.audio.cancelListening() }
        onNext()
    }

    Box(Modifier.fillMaxSize()) {
        SceneBackdrop(SceneName.lesson)
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 20.dp)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f).heightIn(min = 12.dp))
            Box(Modifier.heightIn(max = 300.dp), contentAlignment = Alignment.Center) {
                VoiceRings(color = FoniTone.cold.glow, isLive = isListening, level = { levels.level }, modifier = Modifier.widthIn(max = 360.dp))
                Foni(
                    Modifier.widthIn(max = 210.dp),
                    mood = if (heard) FoniMood.happy else if (isListening) FoniMood.listening else FoniMood.idle,
                    level = { levels.level },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val title = if (heard) "Te-am auzit" else if (isListening) "Spune „aaa”" else if (silent) "Nu te-am auzit" else "Eu sunt Foni"
                Crossfade(title, animationSpec = easeOut(0.18), label = "title") { shown ->
                    FoniaText(shown, style = ui(32, UIWeight.black, TextRole.largeTitle), color = Palette.ink, textAlign = TextAlign.Center)
                }
                val lead = if (denied) {
                    "Microfonul e oprit. Îl poți porni din Setări, la Fonia."
                } else if (silent) {
                    "Mai încearcă sau mergi mai departe."
                } else {
                    "Te ascult și te ajut să spui sunetele. Tot ce spui rămâne pe telefonul tău."
                }
                Crossfade(lead, animationSpec = easeOut(0.18), label = "lead") { shown ->
                    FoniaText(shown, style = fLead, color = Palette.ink, textAlign = TextAlign.Center)
                }
            }
            VoiceBars(
                isLive = isListening,
                color = Palette.coldDeep,
                level = { levels.level },
                modifier = Modifier
                    .height(40.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp)
                    .alpha(if (isListening) 1f else 0f),
            )
            Spacer(Modifier.weight(1f).heightIn(min = 10.dp))
            // Same height in every state, so Foni doesn't jump when the words change.
            Column(Modifier.height(116.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (heard || denied || silent) {
                    StyledButton(onClick = onNext, style = FoniButtonStyle(field = Field.paper)) { CTALabel("Mai departe") }
                } else if (!isListening) {
                    StyledButton(onClick = { listening = scope.launch { listen() } }, style = FoniButtonStyle(field = Field.paper)) { CTALabel("Pornește microfonul") }
                }
                Spacer(Modifier.weight(1f))
                if (!heard && !denied && !silent) {
                    StyledButton(onClick = { leave() }, style = QuietButtonStyle(Palette.ink)) { FoniaText("Mai târziu") }
                }
            }
        }
    }
}

/** What the adult should know before anything else (iOS `ForTheAdult`): three lines, and the whole text one tap away. */
@Composable
private fun ForTheAdult(withReading: Boolean, onBack: () -> Unit, onNext: () -> Unit) {
    var showsAll by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val icons = listOf(FoniaIcon.microphone, FoniaIcon.mirror, FoniaIcon.lock)
    Column(
        Modifier
            .fillMaxSize()
            .background(Field.paper.background)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            SetupBack(onBack)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FoniaText("Pentru adultul de lângă copil", Modifier.semantics { heading() }, style = fDisplay, color = Palette.ink)
                FoniaText("Fonia nu ține locul logopedului. Te ajută să exersați între ședințe.", style = fBody, color = Palette.inkSoft)
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Palette.sheet, ContinuousRoundedRectangle(22.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PrivacyText.summary.forEachIndexed { index, line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier
                                .size(42.dp)
                                .background(Palette.accentSoft, ContinuousRoundedRectangle(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            FoniaIconView(icons[index % icons.size], size = 22f, tint = Palette.accentText)
                        }
                        FoniaText(line, Modifier.weight(1f), style = fLead, color = Palette.ink)
                    }
                }
            }
            StyledButton(
                onClick = { showsAll = true },
                style = QuietButtonStyle(Palette.accentText),
                modifier = Modifier.semantics { contentDescription = "Citește tot, despre ce păstrăm pe telefon" },
            ) { FoniaText("Citește tot", style = fBodyStrong) }
        }
        Dock {
            StyledButton(onClick = onNext, style = FoniButtonStyle(field = Field.paper)) { CTALabel("Mai departe") }
        }
    }
    FoniaSheet(isPresented = showsAll, onDismiss = { showsAll = false }) {
        // No profile yet, so no gate: the adult is the one setting the app up.
        PrivacyNote(onDone = { showsAll = false }, withReading = withReading, onOpenLink = { LegalInfo.open(context, it) })
    }
}

@Composable
private fun WhoIsPractising(onChild: () -> Unit, onAdult: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        FieldBackdrop(Field.warm)
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 20.dp)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f).heightIn(min = 12.dp))
            FoniaText("Cine exersează?", Modifier.padding(bottom = 14.dp), style = fDisplay, color = Palette.ink)
            Choice("Un copil", "R, S, Ș și celelalte, pas cu pas", onChild) { Foni(Modifier.fillMaxSize()) }
            Choice("Eu", "Pronunție în engleză, dicție, vorbit în public", onAdult) { WordPicture("choice-adult", Modifier.fillMaxSize().padding(6.dp)) }
            Spacer(Modifier.weight(1f).heightIn(min = 12.dp))
            FoniaText(
                "Fonia nu ține locul logopedului. Te ajută să exersezi între ședințe.",
                style = fCaption,
                color = Palette.inkSoft,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Choice(title: String, detail: String, action: () -> Unit, art: @Composable () -> Unit) {
    StyledButton(
        onClick = action,
        style = TileButtonStyle(radius = 26.dp),
        modifier = Modifier.semantics { contentDescription = "$title, $detail" },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 104.dp)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(68.dp)) { art() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                FoniaText(title, style = fHeadline, color = Palette.ink)
                FoniaText(detail, style = fCaption, color = Palette.inkSoft, textAlign = TextAlign.Start)
            }
            FoniaIconView(FoniaIcon.forward, size = 13f, tint = Palette.lineStrong)
        }
    }
}

/** The back button of a setup screen, on its own line. */
@Composable
private fun SetupBack(action: () -> Unit) {
    StyledButton(onClick = action, style = ToolButtonStyle(field = Field.paper), modifier = Modifier.semantics { contentDescription = "Înapoi" }) {
        FoniaIconView(FoniaIcon.back, size = 18f)
    }
}

/** Hides the keyboard as soon as the page is dragged, as `.scrollDismissesKeyboard(.interactively)` does. */
@Composable
private fun Modifier.dismissesKeyboardOnScroll(): Modifier {
    val keyboard = LocalSoftwareKeyboardController.current
    val connection = remember(keyboard) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) keyboard?.hide()
                return Offset.Zero
            }
        }
    }
    return nestedScroll(connection)
}

/**
 * A setup page: the form scrolls, and the "Gata" button stays at the bottom over the system's navigation area and
 * over the keyboard, as `safeAreaBar(edge: .bottom)` keeps it.
 */
@Composable
private fun SetupPage(onDone: () -> Unit, waitsFor: String? = null, form: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Field.paper.background)
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .dismissesKeyboardOnScroll()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) { form() }
        Dock {
            // A button that can't be pressed yet says above it what it waits for.
            if (waitsFor != null) DockHint(waitsFor)
            StyledButton(onClick = onDone, style = FoniButtonStyle(field = Field.paper), enabled = waitsFor == null) { CTALabel("Gata") }
        }
    }
}

@Composable
fun ChildSetup(app: AppModel, onBack: (() -> Unit)?, onDone: (Profile) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var age by rememberSaveable { mutableStateOf<Profile.AgeBand?>(null) }
    var picked by rememberSaveable { mutableStateOf(listOf<String>()) }
    SetupPage(
        waitsFor = if (age == null) "Alege vârsta" else null,
        onDone = {
            val band = age ?: return@SetupPage
            val trimmed = name.trim()
            onDone(
                Profile(
                    name = trimmed.ifEmpty { null },
                    kind = Profile.Kind.Child,
                    ageBand = band,
                    voiceGroup = Profile.VoiceGroup.Child,
                    color = "cold",
                    targetSounds = picked,
                    track = Profile.Track.Ro,
                ),
            )
        },
    ) {
        if (onBack != null) SetupBack(onBack)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FoniaText("Cum îl cheamă pe copil?", style = fPrompt, color = Palette.ink)
            NameField(name, onChange = { name = it })
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FoniaText("Câți ani are?", style = fPrompt, color = Palette.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (band in listOf(Profile.AgeBand.FourToFive, Profile.AgeBand.SixToSeven, Profile.AgeBand.EightToTen)) {
                    val selected = age == band
                    StyledButton(
                        onClick = { age = band },
                        style = ChoiceTileStyle(isOn = selected),
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = "${band.rawValue} ani" },
                    ) {
                        Box(Modifier.fillMaxWidth().heightIn(min = 62.dp), contentAlignment = Alignment.Center) {
                            FoniaText(band.rawValue, style = ui(22, UIWeight.black, TextRole.title2).monospacedDigit(), color = Palette.ink)
                        }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FoniaText("Ce sunete îi vin greu?", style = fPrompt, color = Palette.ink)
            FoniaText("Alege câte vrei, le punem primele. Nu știi încă? Le aflăm cu o verificare rapidă.", style = fBody, color = Palette.inkSoft)
            SoundPicker(app.content, picked, onChange = { picked = it })
        }
    }
}

/** `TextField("Numele (nu e obligatoriu)")`: capitals at each word, no autocorrect, on a white rounded field. */
@Composable
private fun NameField(value: String, onChange: (String) -> Unit) {
    val style = ui(19, UIWeight.medium, TextRole.title3).copy(color = Palette.ink)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(Palette.accentText),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { field ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 58.dp)
                    .background(Palette.sheet, ContinuousRoundedRectangle(20.dp))
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) FoniaText("Numele (nu e obligatoriu)", style = style, color = Palette.lineStrong.copy(alpha = 0.75f))
                field()
            }
        },
    )
}

/** Letters, coloured by what the air or tongue does. The order they're tapped in is the order practised. */
@Composable
fun SoundPicker(content: FoniaContent, picked: List<String>, onChange: (List<String>) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (row in content.sounds.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (sound in row) {
                    val isOn = sound.id.rawValue in picked
                    val field = sound.family.field
                    StyledButton(
                        onClick = {
                            onChange(if (isOn) picked.filter { it != sound.id.rawValue } else picked + sound.id.rawValue)
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        },
                        style = ChoiceTileStyle(isOn = isOn),
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = "Sunetul ${sound.letter}" },
                    ) {
                        Box(Modifier.fillMaxWidth().heightIn(min = 78.dp), contentAlignment = Alignment.Center) {
                            FoniaText(sound.letter, style = practice(if (sound.letter.length > 2) 24 else 36, PracticeWeight.bold, TextRole.title), color = field.deep)
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AdultSetup(onBack: () -> Unit, onDone: (Profile) -> Unit) {
    var voice by rememberSaveable { mutableStateOf(Profile.VoiceGroup.Female) }
    var track by rememberSaveable { mutableStateOf(Profile.Track.En) }
    SetupPage(
        onDone = {
            onDone(
                Profile(
                    kind = Profile.Kind.Adult,
                    ageBand = Profile.AgeBand.Adult,
                    voiceGroup = voice,
                    color = "cold",
                    requireConfirmation = false,
                    openPath = true,
                    track = track,
                ),
            )
        },
    ) {
        SetupBack(onBack)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FoniaText("Ce vrei să exersezi?", style = fPrompt, color = Palette.ink)
            Option("Engleză", "Să te înțeleagă toată lumea", "art-headphones", track == Profile.Track.En) { track = Profile.Track.En }
            Option("Vorbit în public", "Ritm, pauze, „ăă”, voce", "art-megaphone", track == Profile.Track.Dictie) { track = Profile.Track.Dictie }
            Option("Sunetele românei", "R, S, Ș și celelalte", "art-lips", track == Profile.Track.Ro) { track = Profile.Track.Ro }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FoniaText("Vocea ta e mai degrabă", style = fPrompt, color = Palette.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                VoiceButton("Subțire", voice == Profile.VoiceGroup.Female, Modifier.weight(1f)) { voice = Profile.VoiceGroup.Female }
                VoiceButton("Groasă", voice == Profile.VoiceGroup.Male, Modifier.weight(1f)) { voice = Profile.VoiceGroup.Male }
            }
            FoniaText("Așa ascultăm mai bine S și Ș.", style = fCaption, color = Palette.inkSoft)
        }
        // An adult learner skips the note for the adult beside a child; what stays on the phone is said here.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FoniaIconView(FoniaIcon.lock, size = 18f, tint = Palette.accentText)
            FoniaText(PrivacyText.summary[0], style = fBody, color = Palette.ink)
        }
    }
}

@Composable
private fun Option(title: String, detail: String, art: String, isOn: Boolean, action: () -> Unit) {
    StyledButton(
        onClick = action,
        style = ChoiceTileStyle(isOn = isOn),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 70.dp)
                .padding(start = 14.dp, end = 34.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WordPicture(art, Modifier.size(42.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                FoniaText(title, style = fBodyStrong, color = Palette.ink)
                FoniaText(detail, style = fCaption, color = Palette.inkSoft)
            }
        }
    }
}

@Composable
private fun VoiceButton(title: String, isOn: Boolean, modifier: Modifier, action: () -> Unit) {
    StyledButton(
        onClick = action,
        style = ChoiceTileStyle(isOn = isOn),
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 58.dp), contentAlignment = Alignment.Center) {
            FoniaText(title, style = fBodyStrong, color = Palette.ink)
        }
    }
}
