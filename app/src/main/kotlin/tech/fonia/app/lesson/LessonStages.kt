package tech.fonia.app.lesson

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.fonia.app.holdgames.HoldCharacter
import tech.fonia.app.holdgames.HoldStage
import tech.fonia.app.text.T
import tech.fonia.app.ui.Foni
import tech.fonia.app.ui.FoniMood
import tech.fonia.app.ui.SceneSlot
import tech.fonia.app.ui.WordPictureSlot
import tech.fonia.app.ui.pressBounce
import tech.fonia.designsystem.letter.LiveLetter
import tech.fonia.designsystem.letter.LiveWord
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaCorner
import tech.fonia.designsystem.tokens.FoniaSpace
import tech.fonia.lessons.PlanItem
import tech.fonia.lessons.StepKind
import kotlin.math.sin

/** The Stage of the current step: one composable per step kind, all on the blue Stage, all with the living letter. */
@Composable
fun StepStage(c: LessonController, modifier: Modifier) {
    val step = c.step
    val item = c.item
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            step == null -> Unit
            step.kind == StepKind.Opening -> OpeningStage(c)
            step.kind == StepKind.Move -> MoveStage(c)
            step.kind == StepKind.Listen -> ListenStage(c)
            item == null -> OpeningStage(c)
            item.type == "hold" -> HoldStage(c.game, c.sound.letter, c.letter, c.drive, c.tick, T.letterA11y(c.sound.letter), Modifier.fillMaxSize())
            item.type == "warmup" -> HeroLetter(c, 0.6f)
            item.type == "train" -> TrainStage(c, item)
            item.type == "pair" -> PairStage(c, item)
            item.type == "sentence" -> LineStage(c, item.text, null)
            item.type == "twister" -> LineStage(c, item.text, if (item.variant == "step") T.tempoFast else T.tempoSlow)
            item.type == "story" -> StoryStage(c, item)
            item.type == "mission" -> MissionStage(c, item)
            else -> WordStage(c, item)
        }
    }
}

@Composable
private fun HeroLetter(c: LessonController, fraction: Float) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LiveLetter(c.sound.letter, c.letter, drive = c.drive, height = minOf(maxWidth, maxHeight) * fraction, contentDescription = T.letterA11y(c.sound.letter))
    }
}

/** Opening: the sound's character in its scene, the big letter beside it, Foni telling today's chapter. */
@Composable
private fun OpeningStage(c: LessonController) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        SceneSlot("house_${c.sound.name.lowercase()}", Modifier.fillMaxSize())
        val s = minOf(maxWidth, maxHeight)
        Row(Modifier.fillMaxSize().padding(FoniaSpace.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            HoldCharacter(c.game, Modifier.size(s * 0.42f))
            LiveLetter(c.sound.letter, c.letter, drive = c.drive, height = s * 0.5f, contentDescription = T.letterA11y(c.sound.letter))
        }
    }
}

/** „Zboară ca Foni!”: Foni big and cheering; the child moves; „Gata” below. */
@Composable
private fun MoveStage(c: LessonController) {
    Box(Modifier.fillMaxSize().graphicsLayer { translationY = -18f * kotlin.math.abs(sin(c.tick.longValue / 2.5e8f)) * density }, contentAlignment = Alignment.Center) {
        Foni(FoniMood.Cheering, size = 180.dp)
    }
}

/** Listen: two big cards — the picture said right ("Bine") and Foni mixed up ("Încurcat"). */
@Composable
private fun ListenStage(c: LessonController) {
    val st = c.choice ?: return HeroLetter(c, 0.5f)
    val colors = FoniaTheme.colors
    Column(Modifier.fillMaxSize().padding(FoniaSpace.m), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(FoniaSpace.m), modifier = Modifier.fillMaxWidth()) {
            ChoiceCard(
                label = T.listenRightCard, sub = st.word, chosen = st.answered == true, right = st.saidRight, answered = st.answered != null,
                modifier = Modifier.weight(1f), onTap = { c.chooseAnswer(true) },
            ) { WordPictureSlot(st.pictureKey, st.picture, Modifier.fillMaxSize()) }
            ChoiceCard(
                label = T.listenMixedCard, sub = st.foil, chosen = st.answered == false, right = !st.saidRight, answered = st.answered != null,
                modifier = Modifier.weight(1f), onTap = { c.chooseAnswer(false) },
            ) {
                Box(Modifier.fillMaxSize().background(colors.fill, RoundedCornerShape(FoniaCorner.card)), contentAlignment = Alignment.Center) {
                    Foni(FoniMood.Thinking, size = 88.dp, modifier = Modifier.graphicsLayer { rotationZ = -12f })
                    BasicText("?", style = FoniaTheme.type.display.copy(color = colors.voiceDeep), modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
                }
            }
        }
    }
}

@Composable
private fun ChoiceCard(
    label: String, sub: String, chosen: Boolean, right: Boolean, answered: Boolean,
    modifier: Modifier, onTap: () -> Unit, picture: @Composable () -> Unit,
) {
    val colors = FoniaTheme.colors
    val fade by animateFloatAsState(if (answered && !right) 0.45f else 1f, tween(300), label = "fade")
    val pop by animateFloatAsState(if (answered && right) 1.06f else 1f, spring(dampingRatio = 0.4f), label = "pop")
    Column(
        modifier
            .graphicsLayer { alpha = fade; scaleX = pop; scaleY = pop }
            .background(colors.surface, RoundedCornerShape(FoniaCorner.card))
            .drawBehind {
                if (answered && right) drawRoundRect(colors.parrot, cornerRadius = CornerRadius(FoniaCorner.card.toPx()), style = Stroke(5.dp.toPx()))
                else if (chosen) drawRoundRect(colors.accentDeep, cornerRadius = CornerRadius(FoniaCorner.card.toPx()), style = Stroke(3.dp.toPx()))
            }
            .pressBounce(enabled = !answered, onClick = onTap)
            .semantics(mergeDescendants = true) { contentDescription = "$label: $sub" }
            .padding(FoniaSpace.s),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) { picture() }
        Spacer(Modifier.height(FoniaSpace.xs))
        BasicText(label, style = FoniaTheme.type.title2.copy(color = colors.ink))
        BasicText(sub, style = FoniaTheme.type.body.copy(color = colors.inkSecondary))
        if (answered && right) BasicText("✓", style = FoniaTheme.type.title.copy(color = colors.parrotDeep))
    }
}

/** Words (and the final's sure item): the picture, the word with its target letters as the living stickers. */
@Composable
private fun WordStage(c: LessonController, item: PlanItem) {
    val ci = c.content.item(item.id)
    Column(Modifier.fillMaxSize().padding(FoniaSpace.m), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        WordPictureSlot(ci?.icon ?: item.text, ci?.emoji, Modifier.size(150.dp))
        Spacer(Modifier.height(FoniaSpace.m))
        LiveWord(
            item.text, target = PlanRules.targets(item.text, c.sound), state = c.word, drive = c.drive,
            fontSize = if (item.text.length > 7) 52.sp else 68.sp, contentDescription = item.text,
        )
    }
}

/** Pairs: both pictures; the one Foni is saying lifts, then the one to say gets the pink edge. */
@Composable
private fun PairStage(c: LessonController, item: PlanItem) {
    val ci = c.content.item(item.id)
    val colors = FoniaTheme.colors
    val first = if (item.variant == "ba") 1 else 0
    val words = listOf(ci?.a ?: "", ci?.b ?: "")
    val pics = listOf(ci?.emojiA, ci?.emojiB)
    Column(Modifier.fillMaxSize().padding(FoniaSpace.m), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(FoniaSpace.m), modifier = Modifier.fillMaxWidth()) {
            for (k in 0..1) {
                val spokenOrder = if (k == first) 0 else 1
                val speaking = c.pairSpoken == spokenOrder
                val target = c.pairTarget == words[k]
                val lift by animateFloatAsState(if (speaking || target) 1.07f else 1f, spring(dampingRatio = 0.45f), label = "lift")
                Column(
                    Modifier.weight(1f)
                        .graphicsLayer { scaleX = lift; scaleY = lift; alpha = if (c.pairTarget != null && !target) 0.6f else 1f }
                        .background(colors.surface, RoundedCornerShape(FoniaCorner.card))
                        .drawBehind { if (target) drawRoundRect(colors.voice, cornerRadius = CornerRadius(FoniaCorner.card.toPx()), style = Stroke(5.dp.toPx())) }
                        .padding(FoniaSpace.s),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    WordPictureSlot(words[k], pics[k], Modifier.fillMaxWidth().aspectRatio(1f))
                    Spacer(Modifier.height(FoniaSpace.xs))
                    BasicText(words[k], style = FoniaTheme.type.title.copy(color = colors.ink))
                }
            }
        }
        Spacer(Modifier.height(FoniaSpace.m))
        val t = c.pairTarget
        if (t != null) LiveWord(t, target = PlanRules.targets(t, c.sound), state = c.word, drive = c.drive, fontSize = 52.sp, contentDescription = t)
    }
}

/** Syllables: the train — one wagon per syllable; each burst the child says makes the next wagon hop. */
@Composable
private fun TrainStage(c: LessonController, item: PlanItem) {
    val colors = FoniaTheme.colors
    val parts = item.syllables.ifEmpty { item.text.split('-') }
    Column(Modifier.fillMaxSize().padding(FoniaSpace.s), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // the locomotive carries the living letter
            Box(
                Modifier.size(width = 78.dp, height = 92.dp).background(colors.voice, RoundedCornerShape(topStart = 18.dp, topEnd = 30.dp, bottomStart = 10.dp, bottomEnd = 10.dp)),
                contentAlignment = Alignment.Center,
            ) { LiveLetter(c.sound.letter, c.letter, drive = c.drive, height = 60.dp, contentDescription = T.letterA11y(c.sound.letter)) }
            parts.forEachIndexed { k, syl ->
                Box(
                    Modifier
                        .graphicsLayer {
                            c.tick.longValue
                            val hopped = c.beats > k
                            translationY = if (hopped) -10f * density * (0.5f + 0.5f * sin(c.tick.longValue / 9e7f + k)) else 0f
                        }
                        .size(width = 64.dp, height = 64.dp)
                        .background(if (k % 3 == 2) colors.sun else colors.surface, RoundedCornerShape(12.dp))
                        .drawBehind { drawRoundRect(colors.accentDeep, cornerRadius = CornerRadius(12.dp.toPx()), style = Stroke(2.5.dp.toPx())) },
                    contentAlignment = Alignment.Center,
                ) { BasicText(syl, style = FoniaTheme.type.title.copy(color = colors.ink, fontSize = 26.sp)) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth(0.94f).height(5.dp).background(colors.surface.copy(alpha = 0.6f), RoundedCornerShape(3.dp)))
    }
}

/** Sentence and twister: the line in practice type, the sound's letters marked pink; the twister's tempo badge on top. */
@Composable
private fun LineStage(c: LessonController, text: String, tempo: String?) {
    val colors = FoniaTheme.colors
    val targets = PlanRules.targets(text, c.sound)
    val styled = buildAnnotatedString {
        append(text)
        for (r in targets) addStyle(SpanStyle(color = colors.voiceDeep, fontWeight = FontWeight.Black), r.first, r.last + 1)
    }
    Column(Modifier.fillMaxSize().padding(FoniaSpace.m), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (tempo != null) {
            Row(
                Modifier.background(colors.sun, RoundedCornerShape(50)).padding(horizontal = FoniaSpace.m, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { BasicText(if (tempo == T.tempoSlow) "🐌  $tempo" else "🐇  $tempo", style = FoniaTheme.type.headline.copy(color = colors.ink)) }
            Spacer(Modifier.height(FoniaSpace.m))
        }
        LiveLetter(c.sound.letter, c.letter, drive = c.drive, height = 90.dp, contentDescription = T.letterA11y(c.sound.letter))
        Spacer(Modifier.height(FoniaSpace.s))
        Box(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(FoniaCorner.card)).padding(FoniaSpace.m)) {
            BasicText(styled, style = FoniaTheme.type.practiceLine.copy(color = colors.ink, textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Retell: three pictures; while Foni tells the story the letter walks from picture to picture. */
@Composable
private fun StoryStage(c: LessonController, item: PlanItem) {
    val colors = FoniaTheme.colors
    val ci = c.content.item(item.id)
    val pics = ci?.pictures.orEmpty()
    val at = c.storyLine
    val walk by animateFloatAsState(if (at < 0) 1f else at.toFloat(), spring(dampingRatio = 0.6f, stiffness = 120f), label = "walk")
    BoxWithConstraints(Modifier.fillMaxSize().padding(FoniaSpace.m)) {
        val cell = (maxWidth - FoniaSpace.s * 2) / 3
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Box(Modifier.fillMaxWidth().height(70.dp)) {
                Box(Modifier.graphicsLayer { translationX = (cell.toPx() + FoniaSpace.s.toPx()) * walk + cell.toPx() / 2 - 35.dp.toPx() }) {
                    LiveLetter(c.sound.letter, c.letter, drive = c.drive, height = 70.dp, contentDescription = T.letterA11y(c.sound.letter))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(FoniaSpace.s)) {
                pics.take(3).forEachIndexed { k, p ->
                    val lit = at == k
                    Box(
                        Modifier.width(cell).aspectRatio(0.85f)
                            .graphicsLayer { val s = if (lit) 1.06f else 1f; scaleX = s; scaleY = s }
                            .drawBehind { if (lit) drawRoundRect(colors.sun, cornerRadius = CornerRadius(FoniaCorner.card.toPx()), style = Stroke(5.dp.toPx())) },
                    ) { WordPictureSlot("${item.id}.$k", p, Modifier.fillMaxSize()) }
                }
            }
            Spacer(Modifier.height(FoniaSpace.s))
            BasicText(
                if (at >= 0) ci?.lines?.getOrNull(at).orEmpty() else "",
                style = FoniaTheme.type.title2.copy(color = colors.surface, textAlign = TextAlign.Center),
                modifier = Modifier.fillMaxWidth().height(64.dp),
            )
        }
    }
}

/** Mission: today's card for home; once said, it flies up to the adult. */
@Composable
private fun MissionStage(c: LessonController, item: PlanItem) {
    val colors = FoniaTheme.colors
    val fly by animateFloatAsState(if (c.missionSent) 1f else 0f, tween(700), label = "fly")
    Column(Modifier.fillMaxSize().padding(FoniaSpace.l), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier
                .graphicsLayer { translationY = -fly * 260.dp.toPx(); rotationZ = -4f + 10f * fly; alpha = 1f - fly * 0.7f; val s = 1f - 0.4f * fly; scaleX = s; scaleY = s }
                .background(colors.surface, RoundedCornerShape(FoniaCorner.card))
                .drawBehind { drawRoundRect(colors.sun, cornerRadius = CornerRadius(FoniaCorner.card.toPx()), style = Stroke(6.dp.toPx())) }
                .padding(FoniaSpace.l),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText("★ ${T.missionCard}", style = FoniaTheme.type.headline.copy(color = colors.sunDeep))
            Spacer(Modifier.height(FoniaSpace.s))
            BasicText(item.text, style = FoniaTheme.type.title.copy(color = colors.ink, textAlign = TextAlign.Center))
        }
        if (c.missionSent) {
            Spacer(Modifier.height(FoniaSpace.l))
            BasicText(T.missionSent, style = FoniaTheme.type.title2.copy(color = colors.surface))
        }
    }
}
