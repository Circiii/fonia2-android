package tech.fonia.features.probe

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.fonia.audio.CapturedClip
import tech.fonia.design.CTALabel
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.Dock
import tech.fonia.design.Foni
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.FoniMood
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.LocalReduceMotion
import tech.fonia.design.Palette
import tech.fonia.design.PressableStyle
import tech.fonia.design.Radius
import tech.fonia.design.RisingPanel
import tech.fonia.design.SceneBackdrop
import tech.fonia.design.SceneName
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.ToolButtonStyle
import tech.fonia.design.UIWeight
import tech.fonia.design.easeOut
import tech.fonia.design.fBody
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fCaption
import tech.fonia.design.fPrompt
import tech.fonia.design.swiftSpring
import tech.fonia.design.ui
import tech.fonia.domain.NewWordsRecord
import tech.fonia.domain.ProbeLevel
import tech.fonia.domain.ProbeMark
import tech.fonia.domain.ProbeRules
import tech.fonia.domain.SoundID
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.ignoringFailure
import tech.fonia.features.app.isChild
import tech.fonia.features.app.rememberAdultGate
import tech.fonia.features.app.shortLetter
import tech.fonia.features.session.SessionRun
import java.util.UUID

/**
 * The end of „Cuvinte noi”. A child hears thanks, never a count; the adult marks what they heard behind the adult
 * check. An adult learner sees the marks straight away. The lesson the probe opened follows either way.
 */
@Composable
fun ProbeEnd(run: SessionRun, app: AppModel, onNext: () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    var showsMarks by remember { mutableStateOf(!run.profile.isChild) }
    val passGate = rememberAdultGate(app)
    val record = remember { run.probeRecord }
    LaunchedEffect(Unit) {
        if (run.profile.isChild) app.audio.sayIfRecorded(ProbeCopy.thanks)
    }
    Crossfade(
        showsMarks && record != null,
        animationSpec = if (reduceMotion) snap<Float>() else easeOut<Float>(0.25),
        label = "probe end",
    ) { marks ->
        if (marks && record != null) {
            val clips = remember { run.probeTakes.mapNotNull { (index, take) -> take.clip?.let { index to it } }.toMap() }
            ProbeMarksView(record, clips, run.profile.id, app, onDone = onNext)
        } else {
            ForTheLearner(onAdult = { passGate { showsMarks = true } }, onNext = onNext)
        }
    }
}

@Composable
private fun ForTheLearner(onAdult: () -> Unit, onNext: () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val settled = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduceMotion) {
            delay(100)
            settled.animateTo(1f, swiftSpring(0.55, 0.3))
        }
    }
    Box(Modifier.fillMaxSize()) {
        SceneBackdrop(SceneName.lesson)
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Spacer(Modifier.weight(1f).heightIn(min = 12.dp))
            Foni(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .height(230.dp)
                    .clearAndSetSemantics {}
                    .graphicsLayer {
                        val v = settled.value
                        scaleX = 0.8f + 0.2f * v
                        scaleY = 0.8f + 0.2f * v
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        alpha = v.coerceIn(0f, 1f)
                    },
                mood = FoniMood.happy,
                reactsToTap = true,
            )
            RisingPanel(color = Palette.sheet) {
                Column(
                    Modifier.padding(horizontal = 20.dp).padding(top = 24.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FoniaText(ProbeCopy.thanks, Modifier.semantics { heading() }, style = fPrompt, color = Palette.ink)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StyledButton(
                            onClick = onAdult,
                            style = FoniButtonStyle(kind = FoniButtonStyle.Kind.secondary, minHeight = 64.dp),
                            modifier = Modifier.weight(1f),
                        ) { CTALabel(ProbeCopy.forAdult) }
                        StyledButton(
                            onClick = onNext,
                            style = FoniButtonStyle(minHeight = 64.dp),
                            modifier = Modifier.weight(1f),
                        ) { CTALabel(ProbeCopy.onward) }
                    }
                }
            }
        }
    }
}

/**
 * The adult's page: every item with its take to hear again, and three marks. An item left unmarked stays so; only
 * what was heard clearly is marked. Below, what the marks so far suggest, and nothing changes without a yes.
 */
@Composable
fun ProbeMarksView(
    record: NewWordsRecord,
    /** The takes, by item, in memory only. */
    clips: Map<Int, CapturedClip>,
    profileID: UUID,
    app: AppModel,
    onDone: () -> Unit,
) {
    val marks = remember { mutableStateListOf<ProbeMark?>(*record.items.map { it.adult }.toTypedArray()) }
    var earlier by remember { mutableStateOf<List<NewWordsRecord>>(emptyList()) }
    var sentencesSet by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var playing by remember { mutableIntStateOf(-1) }
    val marked = record.withMarks(marks.toList())
    val letter = SoundID.fromRawValue(record.sound)?.let { app.content.sound(it).shortLetter } ?: record.sound

    LaunchedEffect(Unit) {
        val stored = runCatching { app.probes?.stored(profileID) }.getOrNull()
        earlier = (stored?.probes ?: emptyList()).filter { it.sound == record.sound && it.id != record.id }
        sentencesSet = stored?.sentences?.contains(record.sound) ?: false
    }

    fun play(clip: CapturedClip, position: Int) {
        playing = position
        app.scope.launch {
            try {
                app.audio.play(clip)
            } finally {
                playing = -1
            }
        }
    }

    fun moveToSentences() {
        sentencesSet = true
        val sound = record.sound
        app.scope.launch { ignoringFailure { app.probes?.moveToSentences(sound, profileID) } }
    }

    fun save() {
        if (isSaving) return
        isSaving = true
        val kept = marked
        app.scope.launch {
            ignoringFailure { app.probes?.save(kept, profileID) }
            onDone()
        }
    }

    Box(Modifier.fillMaxSize()) {
        SceneBackdrop(SceneName.lesson)
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .wrapContentWidth()
                    .widthIn(max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FoniaText(
                    "${ProbeCopy.title} · $letter",
                    Modifier.semantics { heading() },
                    style = ui(28, UIWeight.black, TextRole.title),
                    color = Palette.ink,
                    maxLines = 1,
                    minimumScaleFactor = 0.7f,
                )
                FoniaText(ProbeCopy.adultWhy, style = fBody, color = Palette.ink)
                FoniaText(ProbeCopy.adultHint, style = fCaption, color = Palette.inkSoft)
                record.items.forEachIndexed { position, item ->
                    ItemRow(
                        text = item.text,
                        said = item.phone != null,
                        mark = marks.getOrNull(position),
                        clip = clips[position],
                        canPlay = playing < 0,
                        onPlay = { clip -> play(clip, position) },
                        onMark = { mark -> marks[position] = if (marks[position] == mark) null else mark },
                    )
                }
                Suggestion(record.level, earlier + marked, sentencesSet, onYes = { moveToSentences() })
            }
            Dock(
                Modifier
                    .fillMaxWidth()
                    .background(Palette.paper)
                    .wrapContentWidth()
                    .widthIn(max = 620.dp),
            ) {
                StyledButton(onClick = { save() }, style = FoniButtonStyle(), enabled = !isSaving) { CTALabel(ProbeCopy.save) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemRow(
    text: String,
    said: Boolean,
    mark: ProbeMark?,
    clip: CapturedClip?,
    canPlay: Boolean,
    onPlay: (CapturedClip) -> Unit,
    onMark: (ProbeMark) -> Unit,
) {
    val shape = remember { ContinuousRoundedRectangle(Radius.panel) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.sheet, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            FoniaText(text, Modifier.weight(1f), style = fBodyStrong, color = Palette.ink)
            if (clip != null) {
                StyledButton(
                    onClick = { onPlay(clip) },
                    style = ToolButtonStyle(diameter = 40.dp),
                    enabled = canPlay,
                    modifier = Modifier.semantics { contentDescription = "Ascultă: $text" },
                ) { FoniaIconView(FoniaIcon.speaker, size = 16f) }
            }
        }
        if (!said) {
            FoniaText("Nespus.", style = fCaption, color = Palette.inkSoft)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (each in ProbeMark.entries) {
                    // One mark; a second tap takes it back, and the item is unmarked again.
                    val isOn = mark == each
                    StyledButton(
                        onClick = { onMark(each) },
                        style = PressableStyle(),
                        modifier = Modifier.semantics {
                            contentDescription = "$text: ${ProbeCopy.title(each)}"
                            selected = isOn
                        },
                    ) {
                        Box(
                            Modifier
                                .background(if (isOn) Palette.accent else Palette.accentSoft, CircleShape)
                                .heightIn(min = 40.dp)
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            FoniaText(ProbeCopy.title(each), style = fBody, color = if (isOn) Palette.sheet else Palette.ink)
                        }
                    }
                }
            }
        }
    }
}

/** What the marks so far suggest: sentences after two good word probes, the logoped after good sentences. */
@Composable
private fun Suggestion(level: ProbeLevel, history: List<NewWordsRecord>, sentencesSet: Boolean, onYes: () -> Unit) {
    val shape = remember { ContinuousRoundedRectangle(Radius.panel) }
    val card = Modifier
        .fillMaxWidth()
        .background(Palette.accentSoft, shape)
        .padding(horizontal = 16.dp, vertical = 12.dp)
    if (level == ProbeLevel.Words && ProbeRules.suggestsSentences(history)) {
        Column(card, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FoniaText(if (sentencesSet) ProbeCopy.sentencesSet else ProbeCopy.suggestSentences, style = fBody, color = Palette.ink)
            if (!sentencesSet) {
                StyledButton(
                    onClick = onYes,
                    style = FoniButtonStyle(size = FoniButtonStyle.Size.regular, kind = FoniButtonStyle.Kind.secondary),
                ) { CTALabel(ProbeCopy.yesSentences) }
            }
        }
    } else if (level == ProbeLevel.Sentences && ProbeRules.tellsTherapist(history)) {
        FoniaText(ProbeCopy.tellTherapist, card, style = fBody, color = Palette.ink)
    }
}
