package tech.fonia.app.onboarding

import android.Manifest
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tech.fonia.app.AppGraph
import tech.fonia.app.data.Household
import tech.fonia.app.judge.Talker
import tech.fonia.app.path.ROAD
import tech.fonia.app.text.T
import tech.fonia.app.ui.FoniMascot
import tech.fonia.app.ui.FoniPose
import tech.fonia.app.ui.mood
import tech.fonia.app.ui.SpeechBubble
import tech.fonia.designsystem.components.ContinueButton
import tech.fonia.designsystem.components.ContinueStyle
import tech.fonia.designsystem.components.MicCapsule
import tech.fonia.designsystem.components.MicCapsuleState
import tech.fonia.designsystem.components.PathNode
import tech.fonia.designsystem.components.PathNodeState
import tech.fonia.designsystem.components.QuietButton
import tech.fonia.designsystem.letter.LetterCue
import tech.fonia.designsystem.letter.LetterDrive
import tech.fonia.designsystem.letter.LiveLetter
import tech.fonia.designsystem.letter.LiveLetterState
import tech.fonia.designsystem.letter.StickerText
import tech.fonia.designsystem.tokens.FoniaSticker
import tech.fonia.designsystem.tokens.StickerSet
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.min
import tech.fonia.designsystem.theme.FoniaStage
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaCorner
import tech.fonia.designsystem.tokens.FoniaSpace
import tech.fonia.model.SoundId

/** The steps of the short onboarding (chapter 01 §4, M1 subset): ON-0, ON-1, the "Doar eu" goal, ON-2, ON-5. */
enum class OnStep { Hello, AdultCard, SelfGoal, Mic, Pick, Hero }

/** Where the onboarding ends: PATH (with the chosen sound) or TEEN-HOME. */
enum class OnExit { Path, TeenHome }

/**
 * The short onboarding: welcome with Foni (who practises), the card for the adult, the microphone pre-prompt, pick a
 * sound (the child chooses; D8), then PATH. Shown once: the state is persisted ([tech.fonia.app.data.AppSettings]).
 * [demo] walks through it by itself for the film.
 */
@Composable
fun OnboardingScreen(graph: AppGraph, demo: Boolean, onDone: (OnExit) -> Unit) {
    var step by remember { mutableStateOf(OnStep.Hello) }
    var household by remember { mutableStateOf(Household.ChildWithAdult) }
    var talker by remember { mutableStateOf(Talker.Child) }
    var speaking by remember { mutableStateOf(false) }
    var sound by remember { mutableStateOf<SoundId?>(null) }
    val context = LocalContext.current
    val perms = graph.permissions
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        perms.onResult(granted, context as? Activity)
        step = OnStep.Pick
    }

    fun finish(exit: OnExit) {
        graph.settings.finishOnboarding(household, talker, sound)
        graph.applyTalkerFlag()
        onDone(exit)
    }

    BackHandler(enabled = step != OnStep.Hello) {
        step = when (step) {
            OnStep.AdultCard, OnStep.SelfGoal -> OnStep.Hello
            OnStep.Mic -> if (household == Household.Self) OnStep.SelfGoal else OnStep.AdultCard
            OnStep.Pick -> OnStep.Mic
            else -> step
        }
    }

    // the film: the adult and the child walk through it hands-free
    if (demo) LaunchedEffect(step) {
        when (step) {
            OnStep.Hello -> { delay(1900); household = Household.ChildWithAdult; step = OnStep.AdultCard }
            OnStep.AdultCard -> { delay(2300); step = OnStep.Mic }
            OnStep.Mic -> { delay(1500); step = OnStep.Pick }
            OnStep.Pick -> { delay(1500); sound = SoundId.S; step = OnStep.Hero }
            else -> Unit
        }
    }

    val c = FoniaTheme.colors
    Box(Modifier.fillMaxSize().background(c.bg)) {
        AnimatedContent(step, transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) }, label = "onboarding") { s ->
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = FoniaSpace.pageKid),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (s) {
                    OnStep.Hello -> Hello(
                        onChild = { household = Household.ChildWithAdult; talker = Talker.Child; step = OnStep.AdultCard },
                        onSelf = { household = Household.Self; if (talker == Talker.Child) talker = Talker.AdultHigh; step = OnStep.SelfGoal },
                    )
                    OnStep.AdultCard -> AdultCard(onStart = { step = OnStep.Mic })
                    OnStep.SelfGoal -> SelfGoal(
                        speaking = speaking, talker = talker,
                        onSpeaking = { speaking = it }, onTalker = { talker = it },
                        onNext = { if (speaking) finish(OnExit.TeenHome) else step = OnStep.Mic },
                    )
                    OnStep.Mic -> Mic(
                        onAllow = {
                            if (graph.fixtureMode || perms.granted) step = OnStep.Pick
                            else launcher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onLater = { perms.declinedForNow = true; step = OnStep.Pick },
                    )
                    OnStep.Pick -> Pick(onPick = { sound = it; step = OnStep.Hero })
                    OnStep.Hero -> Hero(sound ?: SoundId.S, onDone = { finish(OnExit.Path) })
                }
            }
        }
    }
}

/**
 * The onboarding's illustration: three big sticker letters, tilted and overlapping, sized from the height they are
 * given, so a tall screen gets a bigger picture instead of an empty gap (the page is composed around it).
 */
@Composable
private fun ColumnScope.StickerCluster(letters: List<Pair<String, StickerSet>>, foni: FoniPose? = null) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        val unit = min(maxHeight * 0.6f, maxWidth * 0.36f).coerceAtLeast(56.dp)
        val fs = with(LocalDensity.current) { unit.toSp() }
        val spots = listOf(Triple(-0.95f, 0.10f, -10f), Triple(0f, -0.12f, 4f), Triple(0.95f, 0.14f, 12f))
        for ((i, pair) in letters.take(3).withIndex()) {
            val (x, y, tilt) = spots[i]
            StickerText(
                pair.first, pair.second, fontSize = if (i == 1) fs * 1.12f else fs, tilt = tilt, contentDescription = "",
                modifier = Modifier.offset(x = unit * 0.62f * x, y = unit * y),
            )
        }
        if (foni != null) {
            tech.fonia.app.ui.Foni(foni.mood(), Modifier.align(Alignment.BottomStart), size = (unit * 0.7f).coerceIn(72.dp, 130.dp))
        }
    }
}

@Composable
private fun ColumnScope.Hello(onChild: () -> Unit, onSelf: () -> Unit) {
    Spacer(Modifier.height(FoniaSpace.l))
    StickerCluster(listOf("Ș" to FoniaSticker.Voice, "S" to FoniaSticker.Parrot, "R" to FoniaSticker.Sun), foni = FoniPose.Cheers)
    Spacer(Modifier.height(FoniaSpace.m))
    SpeechBubble(T.on0Hello, Modifier.fillMaxWidth())
    Spacer(Modifier.height(FoniaSpace.l))
    ChoiceCard(T.on0Child, people = 2, onClick = onChild)
    Spacer(Modifier.height(FoniaSpace.s))
    ChoiceCard(T.on0Self, people = 1, onClick = onSelf)
    Spacer(Modifier.height(FoniaSpace.l))
}

/** A big card with a drawn pair (or one) of round heads, ≥ 72 dp, for ON-0. */
@Composable
private fun ChoiceCard(text: String, people: Int, onClick: () -> Unit) {
    val c = FoniaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .background(c.surface, RoundedCornerShape(FoniaCorner.card))
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = text }
            .padding(horizontal = FoniaSpace.m, vertical = FoniaSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // the people, drawn: a head and shoulders each (with an adult: a tall one and a small one)
        Box(
            Modifier.size(width = 72.dp, height = 56.dp).drawBehind {
                fun person(cx: Float, bottom: Float, h: Float, color: androidx.compose.ui.graphics.Color) {
                    val head = h * 0.22f
                    drawCircle(color, head, androidx.compose.ui.geometry.Offset(cx, bottom - h + head))
                    drawRoundRect(
                        color, androidx.compose.ui.geometry.Offset(cx - h * 0.3f, bottom - h * 0.5f),
                        androidx.compose.ui.geometry.Size(h * 0.6f, h * 0.5f), androidx.compose.ui.geometry.CornerRadius(h * 0.3f, h * 0.3f),
                    )
                }
                if (people == 2) {
                    person(size.width * 0.34f, size.height, size.height, c.accent)
                    person(size.width * 0.74f, size.height, size.height * 0.68f, c.voice)
                } else {
                    person(size.width * 0.5f, size.height, size.height, c.accent)
                }
            },
        )
        Spacer(Modifier.width(FoniaSpace.s))
        BasicText(text, style = FoniaTheme.type.title2.copy(color = c.ink), modifier = Modifier.weight(1f))
        BasicText("→", style = FoniaTheme.type.title2.copy(color = c.accent))
    }
}

@Composable
private fun ColumnScope.AdultCard(onStart: () -> Unit) {
    val c = FoniaTheme.colors
    Spacer(Modifier.height(FoniaSpace.l))
    Row(verticalAlignment = Alignment.CenterVertically) {
        tech.fonia.app.ui.Foni(tech.fonia.app.ui.FoniMood.Idle, size = 64.dp)
        Spacer(Modifier.width(FoniaSpace.xs))
        SpeechBubble(T.on1Wait, Modifier.weight(1f))
    }
    StickerCluster(listOf("Z" to FoniaSticker.Sky, "L" to FoniaSticker.Voice, "J" to FoniaSticker.Parrot))
    Column(
        Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(FoniaCorner.card)).padding(FoniaSpace.l),
        verticalArrangement = Arrangement.spacedBy(FoniaSpace.s),
    ) {
        BasicText(T.on1Title, style = FoniaTheme.type.title.copy(color = c.ink), modifier = Modifier.semantics { heading() })
        for (line in T.on1Card.split(". ").map { it.trim().removeSuffix(".") + "." }) {
            Row {
                BasicText("•  ", style = FoniaTheme.type.body.copy(color = c.accent))
                BasicText(line, style = FoniaTheme.type.body.copy(color = c.ink))
            }
        }
    }
    Spacer(Modifier.height(FoniaSpace.l))
    ContinueButton(T.on1Start, onClick = onStart)
    Spacer(Modifier.height(FoniaSpace.l))
}

@Composable
private fun ColumnScope.SelfGoal(speaking: Boolean, talker: Talker, onSpeaking: (Boolean) -> Unit, onTalker: (Talker) -> Unit, onNext: () -> Unit) {
    val c = FoniaTheme.colors
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(FoniaSpace.s)) {
        Spacer(Modifier.height(FoniaSpace.l))
        BasicText(T.on1SelfTitle, style = FoniaTheme.type.title.copy(color = c.ink), modifier = Modifier.semantics { heading() })
        QuietButton((if (!speaking) "● " else "○ ") + T.on1SelfSound, onClick = { onSpeaking(false) }, emphasised = !speaking, modifier = Modifier.fillMaxWidth())
        QuietButton((if (speaking) "● " else "○ ") + T.on1SelfSpeaking, onClick = { onSpeaking(true) }, emphasised = speaking, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(FoniaSpace.s))
        BasicText(T.on1SelfVoice, style = FoniaTheme.type.title2.copy(color = c.ink))
        BasicText(T.setVoiceHelp, style = FoniaTheme.type.body.copy(color = c.inkSecondary))
        for ((t, label) in listOf(Talker.AdultHigh to T.voiceAdultHigh, Talker.AdultLow to T.voiceAdultLow)) {
            QuietButton((if (talker == t) "● " else "○ ") + label, onClick = { onTalker(t) }, emphasised = talker == t, modifier = Modifier.fillMaxWidth())
        }
    }
    ContinueButton(T.on1Start, onClick = onNext)
    Spacer(Modifier.height(FoniaSpace.l))
}

@Composable
private fun ColumnScope.Mic(onAllow: () -> Unit, onLater: () -> Unit) {
    val c = FoniaTheme.colors
    Spacer(Modifier.height(FoniaSpace.l))
    Row(verticalAlignment = Alignment.CenterVertically) {
        tech.fonia.app.ui.Foni(tech.fonia.app.ui.FoniMood.Listening, size = 64.dp)
        Spacer(Modifier.width(FoniaSpace.xs))
        SpeechBubble(T.on2Ask, Modifier.weight(1f))
    }
    StickerCluster(listOf("s" to FoniaSticker.Sky, "Ș" to FoniaSticker.Voice, "s" to FoniaSticker.Sky))
    MicCapsule(MicCapsuleState.Listen, T.micListen, level = { 0f }, nudge = { false })
    Spacer(Modifier.height(FoniaSpace.l))
    Column(
        Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(FoniaCorner.card)).padding(FoniaSpace.m),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BasicText(T.micTitle, style = FoniaTheme.type.label.copy(color = c.inkSecondary))
        BasicText(T.on2Body, style = FoniaTheme.type.body.copy(color = c.ink))
    }
    Spacer(Modifier.height(FoniaSpace.m))
    ContinueButton(T.on2Allow, onClick = onAllow, arrow = false)
    Spacer(Modifier.height(FoniaSpace.s))
    ContinueButton(T.on2Later, onClick = onLater, style = ContinueStyle.Outlined, arrow = false)
    Spacer(Modifier.height(FoniaSpace.l))
}

@Composable
private fun ColumnScope.Pick(onPick: (SoundId) -> Unit) {
    Spacer(Modifier.height(FoniaSpace.l))
    Row(verticalAlignment = Alignment.CenterVertically) {
        tech.fonia.app.ui.Foni(tech.fonia.app.ui.FoniMood.Pointing, size = 64.dp)
        Spacer(Modifier.width(FoniaSpace.xs))
        SpeechBubble(T.on5Pick, Modifier.weight(1f))
    }
    Spacer(Modifier.weight(1f))
    for (row in ROAD.chunked(3)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (s in row) {
                PathNode(s.letter, PathNodeState.Available, progress = 0, onClick = { onPick(s) }, contentDescription = T.letterA11y(T.soundName(s)))
            }
        }
        Spacer(Modifier.height(FoniaSpace.m))
    }
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.Hero(sound: SoundId, onDone: () -> Unit) {
    val c = FoniaTheme.colors
    val state = remember { LiveLetterState() }
    val drive = remember { LetterDrive().apply { inflation = 0.3f } }
    LaunchedEffect(sound) {
        // the letter fills up and pops: it becomes the hero
        val steps = 24
        for (i in 1..steps) { drive.inflation = 0.3f + 0.7f * i / steps; delay(40) }
        state.cue(LetterCue.Pop)
        delay(900)
        state.cue(LetterCue.Correct)
        delay(1300)
        onDone()
    }
    Spacer(Modifier.height(FoniaSpace.l))
    Row(verticalAlignment = Alignment.CenterVertically) {
        tech.fonia.app.ui.Foni(tech.fonia.app.ui.FoniMood.Cheering, size = 64.dp)
        Spacer(Modifier.width(FoniaSpace.xs))
        SpeechBubble(T.on5Hero, Modifier.weight(1f))
    }
    Spacer(Modifier.height(FoniaSpace.l))
    FoniaStage {
        Box(
            Modifier.weight(1f).fillMaxWidth().background(c.accent, RoundedCornerShape(FoniaCorner.sheet)),
            contentAlignment = Alignment.Center,
        ) {
            LiveLetter(sound.letter, state, drive = drive, height = 300.dp, contentDescription = T.letterA11y(sound.letter))
        }
    }
    Spacer(Modifier.height(FoniaSpace.xl))
}
