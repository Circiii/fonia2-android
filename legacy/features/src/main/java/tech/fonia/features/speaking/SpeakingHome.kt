package tech.fonia.features.speaking

import tech.fonia.features.app.AppRoute
import tech.fonia.features.app.rememberStoredBoolean
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import tech.fonia.design.ArtTileMark
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.MiniPillStyle
import tech.fonia.design.Palette
import tech.fonia.design.Radius
import tech.fonia.design.PressableStyle
import tech.fonia.design.SceneName
import tech.fonia.design.StyledButton
import tech.fonia.design.UIWeight
import tech.fonia.design.WrapLayout
import tech.fonia.design.fBody
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fHeadline
import tech.fonia.design.fTitle
import tech.fonia.design.ui
import tech.fonia.domain.Language
import tech.fonia.domain.Plural
import tech.fonia.domain.SpeechPrompt
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.FoniaSheet
import tech.fonia.features.app.FullScreenCover
import tech.fonia.features.app.ScenePage
import tech.fonia.features.app.SegmentedPicker
import tech.fonia.features.app.SheetDetent
import tech.fonia.features.app.SystemColors
import tech.fonia.features.app.isChild
import tech.fonia.features.app.rememberStoredInt
import tech.fonia.features.app.rememberStoredString
import tech.fonia.features.home.CardRow
import tech.fonia.features.home.ModelNotice
import tech.fonia.features.home.PathModel
import tech.fonia.features.session.CameraCoachSwitch
import tech.fonia.features.session.Settings
import tech.fonia.store.Profile

/**
 * Vorbit în public. You talk, listen to yourself, then say it again shorter: that loop is what changes how someone
 * speaks. A real talk you're preparing gets its own place, and the short drills sit below.
 */
@Composable
fun SpeakingHome(profile: Profile, app: AppModel, path: PathModel) {
    var language by remember { mutableStateOf(Language.Ro) }
    var talk by remember { mutableStateOf<Talk?>(null) }
    var editingMine by remember { mutableStateOf(false) }
    var myTitle by rememberStoredString(app.preferences, "fonia.myTalk.title", "")
    var myMinutes by rememberStoredInt(app.preferences, "fonia.myTalk.minutes", 5)
    val cameraCoach by rememberStoredBoolean(app.preferences, Settings.cameraCoach)
    val push = tech.fonia.features.home.rememberPush()
    var memory by remember(profile.id) { mutableStateOf(SpeakingMemory.load(profile.id, app.preferences)) }
    val prompts = app.content.diction.speechPrompts
    // The step after the last topic finished, starting from the most private one.
    val todaysStep = memory.todaysStep(prompts.size)
    val todaysPrompt = prompts.getOrNull(todaysStep)
    val myTalk = SpeechPrompt(title = myTitle, prompt = "Spune-ți prezentarea așa cum ai spune-o în fața oamenilor.", seconds = myMinutes * 60)
    val myOpening = SpeechPrompt(
        title = myTitle,
        prompt = "Spune doar începutul prezentării, ca în fața publicului. Te opresc după un minut.",
        seconds = OPENING_SECONDS,
    )

    fun finished(done: Talk, fillers: FillerSample) {
        memory = memory.finished(done.step, fillers)
        memory.save(profile.id, app.preferences)
    }

    LaunchedEffect(Unit) { if (profile.track == Profile.Track.En) language = Language.En }
    // Not on iOS: the emphasis exercise and the BAC simulation (SpeakingExtras.kt).
    val extras = rememberSpeakingExtras()

    // The talk covers this page, which already covers the whole screen.
    Box(Modifier.fillMaxSize()) {
        ScenePage(
            scene = SceneName.speaking,
            title = "Vorbit în public",
            sentence = "Spui, te asculți, apoi spui din nou, mai scurt.",
            // Rows are white cards: on the white sheet they lost their edges, so the page sits on paper.
            onPaper = true,
            heroHeight = 250.dp,
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SegmentedPicker(listOf("Română", "Engleză"), if (language == Language.Ro) 0 else 1) {
                    language = if (it == 0) Language.Ro else Language.En
                }

                Heading("Azi")
                if (todaysPrompt != null) {
                    // Today's topic, where it sits on the ladder and how long it takes: one source for the time.
                    // A child sees only the time: a step out of a total reads as a score.
                    TalkRow(
                        ArtTileMark.Art("art-megaphone"),
                        todaysPrompt.title,
                        if (profile.isChild) todaysPrompt.shortDuration else "Pasul ${todaysStep + 1} din ${prompts.size} · ${todaysPrompt.shortDuration}",
                        isNext = true,
                    ) { talk = Talk(todaysPrompt, step = todaysStep) }
                    LadderRow(todaysStep, prompts.size) {
                        memory = memory.stepBack(prompts.size)
                        memory.save(profile.id, app.preferences)
                    }
                }

                Heading("Prezentarea mea")
                if (myTitle.isEmpty()) {
                    TalkRow(ArtTileMark.Art("art-start"), "Ai de ținut o prezentare?", "Scrie-i numele și cât vrei să dureze. Apoi o repeți aici.") {
                        editingMine = true
                    }
                } else {
                    TalkRow(ArtTileMark.Art("art-start"), myTitle, "Țintă: ${Plural.format(myMinutes, Plural.Noun.Minut)}") { talk = Talk(myTalk) }
                    WrapLayout(spacing = 10.dp, lineSpacing = 10.dp) {
                        if (myMinutes > 1) {
                            StyledButton(
                                onClick = { talk = Talk(myOpening, openingOnly = true) },
                                style = MiniPillStyle(),
                                modifier = Modifier.heightIn(min = 48.dp),
                                onClickLabel = "Repeți primul minut al prezentării",
                            ) { FoniaText("Doar începutul") }
                        }
                        StyledButton(onClick = { editingMine = true }, style = MiniPillStyle(), modifier = Modifier.heightIn(min = 48.dp)) {
                            FoniaText("Schimbă prezentarea")
                        }
                    }
                }

                if (CameraCoachSwitch.shows(cameraCoach, profile.isChild)) {
                    Heading("În fața camerei")
                    TalkRow(
                        ArtTileMark.Art("art-mirror"),
                        "Vorbește în fața camerei",
                        "Te vezi pe ecran și primești câte un semn, pe loc. Nimic nu pleacă din telefon.",
                    ) { push(AppRoute.SpeechCoach()) }
                }

                Heading("Exerciții scurte")
                for (drill in drills) {
                    TalkRow(ArtTileMark.Art("art-target"), drill.title, drill.shortDuration) { talk = Talk(drill) }
                }
                for (unit in path.catalog.dictionUnits.filter { it.id != "d-breath" }) {
                    for (lesson in unit.lessons) {
                        TalkRow(ArtTileMark.Art(lesson.art), lesson.title, lesson.detail) { app.start(lesson, unit) }
                    }
                }

                if (app.arguments.drafts) SpeakingExtraRows(extras, language)

                Heading("Alte subiecte")
                prompts.forEachIndexed { step, prompt ->
                    TalkRow(ArtTileMark.Art("art-bubble"), prompt.title, prompt.shortDuration) { talk = Talk(prompt, step = step) }
                }

                ModelNotice(app.speech)
            }
        }

        // `.fullScreenCover(item: $talk)`: the cover keeps its talk while it slides away.
        var shown by remember { mutableStateOf<Talk?>(null) }
        SideEffect { if (talk != null) shown = talk }
        FullScreenCover(isPresented = talk != null) {
            val current = talk ?: shown
            if (current != null) {
                androidx.compose.runtime.key(current) {
                    TalkScreen(
                        current.prompt,
                        language,
                        profile,
                        app,
                        path,
                        openingOnly = current.openingOnly,
                        showsNervesTip = memory.showsNervesTip,
                        fillerHistory = memory.fillerTalks,
                        onFinished = { finished(current, it) },
                    ) { talk = null }
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
 * A talk about to start: the subject, its place on the topic ladder if it has one, and whether only the opening is
 * rehearsed.
 */
private data class Talk(val prompt: SpeechPrompt, val step: Int? = null, val openingOnly: Boolean = false)

/** The way back down a step. Where today's topic sits is said inside its card. */
@Composable
private fun LadderRow(step: Int, @Suppress("UNUSED_PARAMETER") count: Int, onBack: () -> Unit) {
    if (step <= 0) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        run {
            StyledButton(
                onClick = onBack,
                style = MiniPillStyle(),
                modifier = Modifier.heightIn(min = 48.dp),
                onClickLabel = "Alege subiectul de dinainte",
            ) { FoniaText("Un pas înapoi") }
        }
    }
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
private fun TalkRow(tile: ArtTileMark, title: String, detail: String, isNext: Boolean = false, onClick: () -> Unit) {
    StyledButton(onClick = onClick, style = PressableStyle()) {
        CardRow(tile, title, detail) { StartMark(isNext) }
    }
}

/** One filled play mark on the page, on today's topic; every other row gets the quiet outline. */
@Composable
private fun StartMark(isNext: Boolean) {
    Box(
        Modifier
            .size(32.dp)
            .background(if (isNext) Palette.accent else Palette.sheet, CircleShape)
            .then(if (isNext) Modifier else Modifier.border(1.5.dp, Palette.accentText, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        FoniaIconView(FoniaIcon.play, size = 14f, tint = if (isNext) Palette.buttonLabel else Palette.accentText)
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
                .background(Palette.sheet, ContinuousRoundedRectangle(Radius.small))
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
