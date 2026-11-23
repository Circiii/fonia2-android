package tech.fonia.design.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tech.fonia.designsystem.art.SceneBackdrop
import tech.fonia.designsystem.art.WordPicture
import tech.fonia.designsystem.components.ContinueButton
import tech.fonia.designsystem.components.PathNode
import tech.fonia.designsystem.components.PathNodeState
import tech.fonia.designsystem.components.QuietButton
import tech.fonia.designsystem.foni.FoniMascot
import tech.fonia.designsystem.foni.FoniMood
import tech.fonia.designsystem.letter.LetterCue
import tech.fonia.designsystem.letter.LetterDrive
import tech.fonia.designsystem.letter.LiveLetter
import tech.fonia.designsystem.letter.LiveLetterState
import tech.fonia.designsystem.motion.BurstStyle
import tech.fonia.designsystem.motion.CelebrationBurst
import tech.fonia.designsystem.motion.FoniaAnimatedScreen
import tech.fonia.designsystem.motion.FoniaNav
import tech.fonia.designsystem.motion.FoniaOverlayHost
import tech.fonia.designsystem.motion.LocalFoniaOverlay
import tech.fonia.designsystem.motion.rememberFoniaHaptics
import tech.fonia.designsystem.motion.staggeredEntrance
import tech.fonia.designsystem.theme.FoniaTheme
import kotlin.math.PI
import kotlin.math.sin

/**
 * The design system gallery. Extras: `page` (foni | pictures | scenes | motion), `demo` (scripted tour), `mood` (the
 * hero's mood on the Foni page), `reduce` (force reduced motion; default off, so an emulator with animations off
 * still shows the motion).
 */
class DemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = intent.getStringExtra("page")?.let { p -> Page.entries.firstOrNull { it.key == p } } ?: Page.Foni
        val tour = intent.getBooleanExtra("demo", false)
        val mood = intent.getStringExtra("mood")?.let { m -> FoniMood.entries.firstOrNull { it.name.equals(m, true) } }
        val reduce = intent.getBooleanExtra("reduce", false)
        val cue = intent.getStringExtra("cue")
        setContent {
            FoniaTheme(reduceMotion = reduce) {
                FoniaOverlayHost(Modifier.fillMaxSize().background(FoniaTheme.colors.bg)) {
                    Gallery(page, tour, mood, cue)
                }
            }
        }
    }
}

enum class Page(val key: String, val title: String) {
    Foni("foni", "Foni"), Pictures("pictures", "Poze"), Scenes("scenes", "Scene"), Motion("motion", "Mișcare")
}

/** The tour's shared state: what each page shows, driven by the script or by taps. */
class TourState {
    var page by mutableStateOf(Page.Foni)
    var heroMood by mutableStateOf(FoniMood.Waving)
    var cue by mutableIntStateOf(0)
    var cueKind by mutableStateOf<LetterCue>(LetterCue.Rest)
    var sheet by mutableStateOf(false)
    var burst by mutableIntStateOf(0)
}

@Composable
private fun Gallery(start: Page, tour: Boolean, mood: FoniMood?, cue: String?) {
    val s = remember { TourState().apply { page = start; if (mood != null) heroMood = mood } }
    LaunchedEffect(cue) {
        val c = when (cue) {
            "tongue" -> LetterCue.TongueBehindTeeth; "correct" -> LetterCue.Correct; "unsure" -> LetterCue.Unsure
            "pop" -> LetterCue.Pop; else -> null
        } ?: return@LaunchedEffect
        delay(700)
        s.cueKind = c; s.cue++
    }
    LaunchedEffect(tour) {
        if (!tour) return@LaunchedEffect
        s.page = Page.Foni
        for (m in listOf(FoniMood.Waving, FoniMood.Talking, FoniMood.Listening, FoniMood.Cheering)) { s.heroMood = m; delay(2000) }
        s.page = Page.Pictures; delay(3000)
        s.page = Page.Scenes; delay(3500)
        s.page = Page.Motion; delay(900)
        s.cueKind = LetterCue.TongueBehindTeeth; s.cue++; delay(2600)
        s.cueKind = LetterCue.Correct; s.cue++; delay(2600)
        s.burst++; delay(1800)
        s.sheet = true; delay(1600)
        s.sheet = false; delay(1200)
        s.page = Page.Foni
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in Page.entries) Chip(p.title, selected = s.page == p, Modifier.weight(1f)) { s.page = p }
        }
        FoniaAnimatedScreen(
            targetState = s.page,
            modifier = Modifier.fillMaxSize(),
            nav = { a, b -> if (b.ordinal > a.ordinal) FoniaNav.Forward else FoniaNav.Back },
        ) { p ->
            when (p) {
                Page.Foni -> FoniPage(s)
                Page.Pictures -> PicturesPage()
                Page.Scenes -> ScenesPage()
                Page.Motion -> MotionPage(s)
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = FoniaTheme.colors
    Box(
        modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(if (selected) c.accent else c.fill).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { BasicText(text, style = FoniaTheme.type.label.copy(color = if (selected) c.surface else c.ink)) }
}

/** A speech-like level: syllables at ~4.5 Hz in phrases with pauses. */
private fun fakeVoice(): Float {
    val t = System.nanoTime() / 1e9
    val phrase = sin(t * 2 * PI / 3.1)
    if (phrase < -0.3) return 0f
    val syll = 0.5 + 0.5 * sin(t * 2 * PI * 4.5)
    return (syll * (0.55 + 0.45 * sin(t * 1.7))).toFloat().coerceIn(0f, 1f)
}

// ---------------------------------------------------------------------------------------------------- Foni

@Composable
private fun FoniPage(s: TourState) {
    val c = FoniaTheme.colors
    var look by remember { mutableStateOf<Offset?>(null) }
    var voiceOn by remember { mutableStateOf(true) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Column(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(Unit) { detectTapGestures { look = origin + it } }
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(190.dp).pointerInput(Unit) {
                detectTapGestures { s.heroMood = FoniMood.entries[(s.heroMood.ordinal + 1) % FoniMood.entries.size] }
            }) {
                FoniMascot(s.heroMood, size = 190.dp, voiceLevel = { if (voiceOn) fakeVoice() else 0f }, lookAt = look)
            }
            Column(Modifier.width(150.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(s.heroMood.name, style = FoniaTheme.type.title2.copy(color = c.ink))
                BasicText(s.heroMood.description, style = FoniaTheme.type.caption.copy(color = c.inkSecondary))
                QuietButton(if (voiceOn) "Voce: da" else "Voce: nu", { voiceOn = !voiceOn })
            }
        }
        Spacer(Modifier.height(4.dp))
        val moods = FoniMood.entries
        for (row in moods.chunked(4)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                for (m in row) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FoniMascot(m, size = 84.dp, voiceLevel = { if (m == FoniMood.Talking && voiceOn) fakeVoice() else 0f }, lookAt = look)
                    BasicText(m.name, style = FoniaTheme.type.caption.copy(color = c.inkSecondary))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------- pictures

private val WORDS = listOf(
    "măr", "sanie", "șarpe", "casă", "lup", "pește", "zebră", "ceas", "balon", "rață", "floare", "țânțar",
    "urs", "ac", "pisică", "ciocolată", "lămâie", "🦔", "soare", "minge",
)

@Composable
private fun PicturesPage() {
    val c = FoniaTheme.colors
    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        itemsIndexed(WORDS) { i, w ->
            Column(Modifier.padding(6.dp).staggeredEntrance(i), horizontalAlignment = Alignment.CenterHorizontally) {
                WordPicture(w, Modifier.fillMaxWidth())
                BasicText(w, style = FoniaTheme.type.label.copy(color = c.ink, textAlign = TextAlign.Center))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------- scenes

@Composable
private fun ScenesPage() {
    val c = FoniaTheme.colors
    var drag by remember { mutableFloatStateOf(0f) }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SceneBackdrop(
            "lesson",
            Modifier.fillMaxWidth().aspectRatio(1.05f).clip(RoundedCornerShape(28.dp))
                .pointerInput(Unit) { detectDragGestures { _, d -> drag = (drag + d.x / 600f).coerceIn(-1f, 1f) } },
            parallax = { Offset(drag, 0f) },
        ) {
            FoniMascot(FoniMood.Pointing, Modifier.align(Alignment.BottomStart).padding(12.dp), size = 130.dp)
            WordPicture("soare", Modifier.align(Alignment.TopEnd).padding(20.dp).size(96.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((i, sc) in listOf("cold", "warm").withIndex()) {
                SceneBackdrop(sc, Modifier.weight(1f).aspectRatio(1.9f).clip(RoundedCornerShape(20.dp)).staggeredEntrance(i))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((i, sc) in listOf("tip", "night").withIndex()) {
                SceneBackdrop(sc, Modifier.weight(1f).aspectRatio(1.9f).clip(RoundedCornerShape(20.dp)).staggeredEntrance(i + 2))
            }
        }
        BasicText("Trage pe scenă: paralaxă", style = FoniaTheme.type.caption.copy(color = c.inkSecondary))
    }
}

// ---------------------------------------------------------------------------------------------------- motion

@Composable
private fun MotionPage(s: TourState) {
    val c = FoniaTheme.colors
    val letter = remember { LiveLetterState() }
    val drive = remember { LetterDrive() }
    val haptics = rememberFoniaHaptics()
    var foni by remember { mutableStateOf(FoniMood.Idle) }
    var letterCentre by remember { mutableStateOf<Offset?>(null) }
    val overlay = LocalFoniaOverlay.current

    fun play(cue: LetterCue) {
        letter.cue(LetterCue.Rest)
        letter.cue(cue)
        foni = when (cue) {
            LetterCue.Correct, LetterCue.Pop -> FoniMood.Cheering
            LetterCue.TongueBehindTeeth -> FoniMood.Pointing
            LetterCue.Unsure -> FoniMood.Unsure
            else -> FoniMood.Listening
        }
        if (cue == LetterCue.Correct || cue == LetterCue.Pop) {
            haptics.success()
            letterCentre?.let { overlay?.burst(it, BurstStyle.Stars) }
        }
    }
    LaunchedEffect(s.cue) { if (s.cue > 0) play(s.cueKind) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // the scene box clips (as the app's does); the POP burst still flies over it through the overlay host
            SceneBackdrop("warm", Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(28.dp))) {
                FoniMascot(foni, Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 6.dp), size = 120.dp, lookAt = letterCentre)
                LiveLetter(
                    "Ș", letter, Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)
                        .onGloballyPositioned { letterCentre = it.boundsInRoot().center },
                    drive = drive, height = 200.dp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietButton("Corect", { play(LetterCue.Correct) })
                QuietButton("Limba", { play(LetterCue.TongueBehindTeeth) })
                QuietButton("Hmm", { play(LetterCue.Unsure) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietButton("POP", { drive.inflation = 1f; play(LetterCue.Pop) })
                QuietButton("Foaie", { s.sheet = true })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                val states = listOf(PathNodeState.Done, PathNodeState.Done, PathNodeState.Current, PathNodeState.Available, PathNodeState.Locked)
                for ((i, st) in states.withIndex()) {
                    PathNode(listOf("S", "Z", "Ș", "J", "R")[i], st, progress = if (st == PathNodeState.Done) 3 else 0, onClick = {}, size = 56.dp, modifier = Modifier.staggeredEntrance(i))
                }
            }
            Box {
                ContinueButton("Bravo, mai departe", { s.burst++ })
                CelebrationBurst(s.burst, Modifier.matchParentSize(), style = BurstStyle.Confetti, origin = Offset(0.5f, 0.3f))
            }
        }
        FoniaAnimatedScreen(s.sheet, Modifier.fillMaxSize(), nav = { _, open -> if (open) FoniaNav.ModalOpen else FoniaNav.ModalClose }) { open ->
            if (open) Box(Modifier.fillMaxSize().background(c.ink.copy(alpha = 0.25f)).clickable(MutableInteractionSource(), null) { s.sheet = false }, contentAlignment = Alignment.BottomCenter) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.surface).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FoniMascot(FoniMood.Encouraging, size = 120.dp)
                    BasicText("Hai, încă o dată!", style = FoniaTheme.type.title2.copy(color = c.ink))
                    Spacer(Modifier.height(12.dp))
                    ContinueButton("Gata", { s.sheet = false })
                }
            } else Spacer(Modifier.fillMaxSize())
        }
    }
}
