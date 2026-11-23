package tech.fonia.app.house

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tech.fonia.app.AppGraph
import tech.fonia.app.data.Ladder
import tech.fonia.app.data.PracticeState
import tech.fonia.app.holdgames.HoldCharacter
import tech.fonia.app.holdgames.HoldGame
import tech.fonia.app.text.T
import tech.fonia.app.ui.Foni
import tech.fonia.app.ui.FoniMood
import tech.fonia.app.ui.FoniaSheet
import tech.fonia.app.ui.RoundGlyphButton
import tech.fonia.app.ui.SceneSlot
import tech.fonia.app.ui.SheetBody
import tech.fonia.app.ui.SheetTitle
import tech.fonia.app.ui.pressBounce
import tech.fonia.designsystem.components.ContinueButton
import tech.fonia.designsystem.components.ContinueStyle
import tech.fonia.designsystem.components.QuietButton
import tech.fonia.designsystem.letter.LetterCue
import tech.fonia.designsystem.letter.LetterDrive
import tech.fonia.designsystem.letter.LiveLetter
import tech.fonia.designsystem.letter.LiveLetterState
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaCorner
import tech.fonia.designsystem.tokens.FoniaSpace
import tech.fonia.model.SoundId

private class HouseData(val name: String, val who: String, val cards: List<ExerciseCard>, val needsAdult: Boolean)

/**
 * „Casa sunetului” (M3): the sound's character in its colour, the big living letter, „Hai să exersăm” (the guided
 * 4-minute session) and the exercise cards the child chooses from freely. Closed cards stay visible with a small lock;
 * a tap explains („Cheamă un adult” for the lock after `isolated`).
 */
@Composable
fun SoundHouseScreen(
    sound: SoundId,
    graph: AppGraph,
    onBack: () -> Unit,
    onGuided: () -> Unit,
    onExercise: (Exercise) -> Unit,
    onAdult: () -> Unit,
) {
    val colors = FoniaTheme.colors
    val data by produceState<HouseData?>(null, sound) {
        val loaded = graph.content.get()
        val st: PracticeState = graph.practice.state()
        val p = st.of(sound)
        val summary = ContentSummary.of(loaded.content, sound)
        val hero = loaded.content.sound(sound).hero
        value = HouseData(hero.name, hero.who, ExerciseCatalog.forSound(sound, summary, p), Ladder.needsAdult(p))
    }
    var sheet by remember { mutableStateOf<CardLock?>(null) }
    val (tone, toneDeep) = soundColor(sound, colors)

    Box(Modifier.fillMaxSize().background(colors.bg).semantics { contentDescription = T.houseA11y(sound) }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = FoniaSpace.pageKid, vertical = FoniaSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundGlyphButton("←", T.houseBackA11y, onClick = onBack)
                Spacer(Modifier.weight(1f))
                QuietButton(T.pathAdult, onClick = onAdult)
            }
            // ---- the header: the character's world in the sound's colour, the hero, the big living letter
            HouseHeader(sound, data?.name.orEmpty(), data?.who.orEmpty(), tone, toneDeep)
            Spacer(Modifier.height(FoniaSpace.m))
            ContinueButton(
                T.houseGo, onClick = onGuided, contentDescription = "${T.houseGo}, ${T.houseGoCaption}",
                modifier = Modifier.padding(horizontal = FoniaSpace.pageKid),
            )
            BasicText(
                T.houseGoCaption,
                style = FoniaTheme.type.label.copy(color = colors.inkSecondary),
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
            )
            if (data?.needsAdult == true) {
                Spacer(Modifier.height(FoniaSpace.s))
                Row(
                    Modifier.padding(horizontal = FoniaSpace.pageKid).fillMaxWidth()
                        .background(colors.surface, RoundedCornerShape(FoniaCorner.card))
                        .drawBehind { drawRoundRect(colors.sun, cornerRadius = CornerRadius(FoniaCorner.card.toPx()), style = Stroke(3.dp.toPx())) }
                        .pressBounce(onClick = { sheet = CardLock.NeedsAdult })
                        .padding(FoniaSpace.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Foni(FoniMood.Waving, size = 48.dp)
                    Spacer(Modifier.width(FoniaSpace.s))
                    BasicText(T.lockAdultTitle, style = FoniaTheme.type.headline.copy(color = colors.ink))
                }
            }
            Spacer(Modifier.height(FoniaSpace.l))
            BasicText(
                T.houseChoose,
                style = FoniaTheme.type.title2.copy(color = colors.ink),
                modifier = Modifier.padding(horizontal = FoniaSpace.pageKid).semantics { heading() },
            )
            Spacer(Modifier.height(FoniaSpace.s))
            val cards = data?.cards.orEmpty()
            Column(Modifier.padding(horizontal = FoniaSpace.pageKid), verticalArrangement = Arrangement.spacedBy(FoniaSpace.s)) {
                cards.chunked(2).forEachIndexed { row, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(FoniaSpace.s)) {
                        pair.forEachIndexed { k, card ->
                            CardTile(card, sound, index = row * 2 + k, modifier = Modifier.weight(1f)) {
                                if (card.lock == CardLock.Open) onExercise(card.exercise) else sheet = card.lock
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(FoniaSpace.xl))
            Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        FoniaSheet(sheet != null, onDismiss = { sheet = null }) {
            if (sheet == CardLock.NeedsAdult) {
                SheetTitle(T.lockAdultTitle)
                SheetBody(T.lockAdultBody)
                ContinueButton(T.lockAdultButton, onClick = { sheet = null; onAdult() }, arrow = false)
                ContinueButton(T.lockOk, onClick = { sheet = null }, style = ContinueStyle.Outlined, arrow = false)
            } else {
                SheetTitle(T.lockNotYetTitle)
                SheetBody(T.lockNotYetBody)
                ContinueButton(T.lockOk, onClick = { sheet = null }, arrow = false)
            }
        }
    }
}

@Composable
private fun HouseHeader(sound: SoundId, name: String, who: String, tone: androidx.compose.ui.graphics.Color, toneDeep: androidx.compose.ui.graphics.Color) {
    val colors = FoniaTheme.colors
    val game = remember(sound) { HoldGame.of(sound) }
    val state = remember(sound) { LiveLetterState() }
    val drive = remember(sound) { LetterDrive().apply { inflation = 0.6f } }
    val reduce = FoniaTheme.reduceMotion
    LaunchedEffect(sound) {
        state.cue(LetterCue.DropIn)
        if (!reduce) while (true) { delay(2600); state.cue(LetterCue.Beat(0.7f)) }
    }
    Box(
        Modifier
            .padding(horizontal = FoniaSpace.pageKid)
            .fillMaxWidth()
            .height(236.dp)
            .clip(RoundedCornerShape(FoniaCorner.sheet)),
    ) {
        SceneSlot("house_${sound.name.lowercase()}", Modifier.fillMaxSize())
        // the sound's colour: a hill under the character and a halo behind the letter
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawCircle(tone, size.width * 0.42f, Offset(size.width * 0.28f, size.height * 1.18f))
                drawCircle(toneDeep.copy(alpha = 0.35f), size.width * 0.42f, Offset(size.width * 0.28f, size.height * 1.18f), style = Stroke(6.dp.toPx()))
                drawCircle(colors.surface.copy(alpha = 0.14f), size.height * 0.4f, Offset(size.width * 0.72f, size.height * 0.46f))
            },
        )
        HoldCharacter(game, Modifier.size(150.dp).align(Alignment.BottomStart).padding(start = 8.dp, bottom = 28.dp))
        Column(Modifier.align(Alignment.TopStart).padding(FoniaSpace.m)) {
            BasicText(name, style = FoniaTheme.type.title.copy(color = colors.surface), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(who, style = FoniaTheme.type.label.copy(color = colors.surface.copy(alpha = 0.85f)))
        }
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 18.dp)) {
            LiveLetter(sound.letter, state, drive = drive, height = 150.dp, contentDescription = T.letterA11y(sound.letter))
        }
    }
}

/** One exercise card: illustration, title, the sound's own subtitle, dots; closed ones are dimmed with a small lock. */
@Composable
private fun CardTile(card: ExerciseCard, sound: SoundId, index: Int, modifier: Modifier, onTap: () -> Unit) {
    val colors = FoniaTheme.colors
    val locked = card.lock != CardLock.Open
    val reduce = FoniaTheme.reduceMotion
    val enter = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduce) { delay(40L * index); enter.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 300f)) } }
    Column(
        modifier
            .graphicsLayer { alpha = enter.value.coerceIn(0f, 1f) * (if (locked) 0.62f else 1f); translationY = (1f - enter.value) * 40f * density }
            .background(colors.surface, RoundedCornerShape(FoniaCorner.card))
            .drawBehind { drawRoundRect(colors.separator, cornerRadius = CornerRadius(FoniaCorner.card.toPx()), style = Stroke(1.5.dp.toPx())) }
            .pressBounce(onClick = onTap)
            .semantics(mergeDescendants = true) { contentDescription = T.cardA11y(card.title, card.subtitle, locked, card.dots) }
            .heightIn(min = 168.dp)
            .padding(FoniaSpace.s),
    ) {
        Box(Modifier.fillMaxWidth()) {
            ExerciseIcon(card.exercise, sound, Modifier.size(64.dp))
            if (locked) Padlock(Modifier.align(Alignment.TopEnd).size(26.dp))
        }
        Spacer(Modifier.height(FoniaSpace.xs))
        BasicText(card.title, style = FoniaTheme.type.headline.copy(color = colors.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(card.subtitle, style = FoniaTheme.type.caption.copy(color = colors.inkSecondary), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f, fill = false))
        Spacer(Modifier.height(FoniaSpace.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(card.dotsTotal) { i ->
                Box(Modifier.size(10.dp).drawBehind {
                    if (i < card.dots) drawCircle(colors.sun) else drawCircle(colors.separator, style = Stroke(2.dp.toPx()))
                })
            }
        }
    }
}

/** A small drawn padlock (no emoji), soft ink. */
@Composable
private fun Padlock(modifier: Modifier) {
    val colors = FoniaTheme.colors
    Box(modifier.drawBehind {
        val u = size.minDimension / 24f
        drawArc(colors.inkSecondary, 180f, 180f, false, Offset(6 * u, 2 * u), Size(12 * u, 14 * u), style = Stroke(2.4f * u))
        drawRoundRect(colors.inkSecondary, Offset(4 * u, 10 * u), Size(16 * u, 12 * u), CornerRadius(3 * u))
        drawCircle(colors.surface, 1.8f * u, Offset(12 * u, 15.5f * u))
    })
}
