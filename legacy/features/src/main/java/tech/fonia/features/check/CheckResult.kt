package tech.fonia.features.check

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.fonia.design.CTALabel
import tech.fonia.design.Dock
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.Foni
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.FoniMood
import tech.fonia.design.FoniaText
import tech.fonia.design.LocalReduceMotion
import tech.fonia.design.Palette
import tech.fonia.design.PressableStyle
import tech.fonia.design.SceneBackdrop
import tech.fonia.design.SceneName
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.fCaption
import tech.fonia.design.softShadow
import tech.fonia.design.ui
import tech.fonia.designsystem.letter.StickerText
import tech.fonia.designsystem.tokens.FoniaClassicSticker
import tech.fonia.designsystem.tokens.StickerSet
import tech.fonia.domain.SoundID
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.ignoringFailure
import tech.fonia.features.app.shortLetter
import tech.fonia.features.session.SessionRun
import java.time.Instant

/** The calm grey of „Mai ascultăm o dată”: nothing is wrong, the phone just wants to hear it again. */
private val QuietSticker = StickerSet("CheckQuiet", Color(0xFFEFF2F6), Color(0xFF8C97A8), Color(0xFF4B5567))

/** The groups as he reads them. */
internal fun CheckVerdict.title(): String = when (this) {
    CheckVerdict.Good -> "Ies bine"
    CheckVerdict.Practice -> "De exersat"
    CheckVerdict.Unsure -> "Mai ascultăm o dată"
}

private fun CheckVerdict.sticker(): StickerSet = when (this) {
    CheckVerdict.Good -> FoniaClassicSticker.Good
    CheckVerdict.Practice -> FoniaClassicSticker.Tip
    CheckVerdict.Unsure -> QuietSticker
}

/**
 * The end of the quick check: the nine sounds as sticker letters in three calm groups, the change since the last check,
 * and the plan it sets (the sounds to practise become his sounds, one at a time). A tap on a sound to practise makes it
 * the first one.
 */
@Composable
fun CheckResultScreen(run: SessionRun, app: AppModel, onDone: () -> Unit) {
    val sounds = remember { run.session.trials.mapNotNull { SessionRun.soundOf(it.exercise) }.distinct() }
    val record = remember { CheckRecord(Instant.now(), QuickCheckRules.group(run.takes.values.toList(), sounds)) }
    var before by remember { mutableStateOf<CheckRecord?>(null) }
    var chosen by remember { mutableStateOf<SoundID?>(null) }
    val profileID = run.profile.id

    fun applyPlan() {
        val current = app.profiles.firstOrNull { it.id == profileID } ?: return
        val currentSounds = current.targetSounds.mapNotNull { SoundID.fromRawValue(it) }
        val plan = QuickCheckRules.plan(record, chosen, currentSounds)
        if (plan == currentSounds) return
        app.scope.launch {
            app.save(current.copy(targetSounds = plan.map { it.rawValue }))
            app.checkSaved()
        }
    }

    LaunchedEffect(Unit) {
        // What came before, for „Atunci și acum”; then this check joins the history and sets the plan.
        before = runCatching { app.checks.history(profileID).lastOrNull() }.getOrNull()
        ignoringFailure { app.checks.add(profileID, record) }
        applyPlan()
        app.checkSaved()
    }

    val practice = record.sounds(CheckVerdict.Practice)
    val first = QuickCheckRules.plan(record, chosen, emptyList()).firstOrNull()
    val moved = QuickCheckRules.movedToGood(before, record)
    Box(Modifier.fillMaxSize()) {
        SceneBackdrop(SceneName.lesson)
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .wrapContentWidth()
                .widthIn(max = 620.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Foni(Modifier.size(78.dp), mood = FoniMood.happy, reactsToTap = true)
                FoniaText(
                    "Gata verificarea!",
                    Modifier.semantics { heading() },
                    style = ui(28, UIWeight.black, TextRole.title),
                    color = Palette.ink,
                    maxLines = 1,
                    minimumScaleFactor = 0.7f,
                )
            }
            var order = 0
            for (verdict in CheckVerdict.entries) {
                val group = record.sounds(verdict)
                if (group.isEmpty()) continue
                GroupCard(
                    verdict = verdict,
                    sounds = group,
                    app = app,
                    order = order++,
                    chosen = if (verdict == CheckVerdict.Practice && practice.size > 1) first else null,
                    onTap = if (verdict == CheckVerdict.Practice && practice.size > 1) ({ id: SoundID ->
                        chosen = id
                        applyPlan()
                    }) else null,
                )
            }
            if (before != null) ThenAndNow(moved, app)
            FoniaText(
                "E o verificare orientativă, nu o evaluare.",
                style = fCaption,
                color = Palette.inkSoft,
                textAlign = TextAlign.Center,
            )
        }
        // The next step waits at the bottom, where every other page keeps it.
        Dock(
            Modifier
                .fillMaxWidth()
                .background(Palette.paper)
                .wrapContentWidth()
                .widthIn(max = 620.dp),
        ) {
            val letter = first?.let { app.content.sound(it).shortLetter }
            if (letter != null) {
                FoniaText(
                    "Lecțiile de acasă vor începe cu $letter.",
                    Modifier.fillMaxWidth(),
                    style = fCaption,
                    color = Palette.inkSoft,
                    textAlign = TextAlign.Center,
                )
            }
            StyledButton(onClick = onDone, style = FoniButtonStyle()) { CTALabel(letter?.let { "Începem cu $it" } ?: "Gata") }
        }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupCard(verdict: CheckVerdict, sounds: List<SoundID>, app: AppModel, order: Int, chosen: SoundID?, onTap: ((SoundID) -> Unit)?) {
    val card = remember { ContinuousRoundedRectangle(26.dp) }
    Column(
        Modifier
            .fillMaxWidth()
            .softShadow(card)
            .background(Palette.sheet, card)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FoniaText(verdict.title(), style = ui(19, UIWeight.bold, TextRole.headline), color = Palette.ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            sounds.forEachIndexed { index, id ->
                val letter = app.content.sound(id).shortLetter
                val isFirst = chosen == id
                val sticker: @Composable () -> Unit = {
                    DroppingSticker(letter, verdict.sticker(), order * 4 + index, isFirst)
                }
                if (onTap != null) {
                    StyledButton(
                        onClick = { onTap(id) },
                        style = PressableStyle(),
                        modifier = Modifier.semantics { contentDescription = "Începem cu $letter" },
                    ) { sticker() }
                } else {
                    sticker()
                }
            }
        }
        if (onTap != null) {
            FoniaText("Atinge sunetul cu care vrei să începi.", style = fCaption, color = Palette.inkSoft)
        }
    }
}

/** A sticker letter that drops into its place, one after another; the chosen one wears a ring. */
@Composable
private fun DroppingSticker(letter: String, sticker: StickerSet, order: Int, ringed: Boolean) {
    val reduce = LocalReduceMotion.current
    val p = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduce) return@LaunchedEffect
        delay(180L + order * 90L)
        p.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 300f))
    }
    Box(
        Modifier
            .graphicsLayer {
                val v = p.value
                alpha = (v * 2f).coerceIn(0f, 1f)
                translationY = (1f - v) * -60f
                scaleX = 0.7f + 0.3f * v
                scaleY = 0.7f + 0.3f * v
            }
            .then(if (ringed) Modifier.border(3.dp, Palette.accent, CircleShape).padding(6.dp) else Modifier.padding(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        StickerText(letter, sticker, fontSize = if (letter.length > 1) 34.sp else 44.sp, weight = 900f)
    }
}

/** „Atunci și acum”: which sounds moved to „Ies bine” since the last check. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThenAndNow(moved: List<SoundID>, app: AppModel) {
    val card = remember { ContinuousRoundedRectangle(26.dp) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.accentSoft, card)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FoniaText("Atunci și acum", style = ui(17, UIWeight.bold, TextRole.headline), color = Palette.ink)
        if (moved.isEmpty()) {
            FoniaText("Ca data trecută. Mai exersăm și vedem din nou.", style = ui(16, UIWeight.medium, TextRole.body), color = Palette.ink)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FoniaText("Acum ies bine:", style = ui(16, UIWeight.medium, TextRole.body), color = Palette.ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (id in moved) StickerText(app.content.sound(id).shortLetter, FoniaClassicSticker.Good, fontSize = 26.sp, weight = 900f)
                }
            }
        }
    }
}
