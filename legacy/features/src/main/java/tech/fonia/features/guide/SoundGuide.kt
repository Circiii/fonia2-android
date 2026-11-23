package tech.fonia.features.guide

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.PracticeSheet
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.fBody
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fHeadline
import tech.fonia.design.monospacedDigit
import tech.fonia.design.swiftSpring
import tech.fonia.design.ui
import tech.fonia.designsystem.motion.staggeredEntrance
import tech.fonia.domain.SoundDef
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.AppRoute
import tech.fonia.features.app.HeaderPage
import tech.fonia.features.app.capitalizedFirstLetter
import tech.fonia.features.app.field
import tech.fonia.features.app.shape
import tech.fonia.features.app.shortLetter
import tech.fonia.features.home.rememberPush
import tech.fonia.features.session.MouthCard

/**
 * How a sound is made, one step at a time. The picture follows the step you're on: the lips from the front, the tongue
 * from the side. From here the mirror checks you do it too.
 */
@Composable
fun SoundGuide(sound: SoundDef, app: AppModel) {
    var current by remember { mutableIntStateOf(0) }
    val plan = remember(sound.id) { app.content.coachPlan(sound.id.rawValue) }
    val field = sound.id.family.field
    val push = rememberPush()
    val lines = plan?.steps?.map { it.say } ?: sound.how

    // `.onDisappear { Task { await app.audio.stopSpeaking() } }`.
    DisposableEffect(Unit) { onDispose { app.scope.launch { app.audio.stopSpeaking() } } }

    HeaderPage("Cum spun ${sound.shortLetter}?") {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 620.dp)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val step = plan?.steps?.getOrNull(current)
                GuidePicture(sound, plan, step)

                Steps(lines, current) { current = it }

                Row(Modifier.fillMaxWidth().staggeredEntrance(lines.size), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StyledButton(
                        onClick = { app.scope.launch { app.audio.say(sound.hero.says) } },
                        style = FoniButtonStyle(field = field, size = FoniButtonStyle.Size.regular, kind = FoniButtonStyle.Kind.secondary),
                        modifier = Modifier.weight(1f),
                    ) { OneLineLabel("Ascultă", FoniaIcon.speaker) }
                    StyledButton(
                        onClick = { app.scope.launch { app.audio.saySlowly(sound.hero.says) } },
                        style = FoniButtonStyle(field = field, size = FoniButtonStyle.Size.regular, kind = FoniButtonStyle.Kind.secondary),
                        modifier = Modifier.weight(1f),
                    ) { OneLineLabel("Mai rar") }
                    StyledButton(
                        onClick = { push(AppRoute.Mirror(sound.id)) },
                        style = FoniButtonStyle(field = field, size = FoniButtonStyle.Size.regular),
                        modifier = Modifier.weight(1f),
                    ) { OneLineLabel("Încearcă", FoniaIcon.mirror) }
                }

                if (sound.fixes.isNotEmpty()) {
                    PracticeSheet(Modifier.staggeredEntrance(lines.size + 1)) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            FoniaText("Dacă nu iese", style = fHeadline, color = Palette.ink)
                            for (fix in sound.fixes) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    FoniaText(fix.`when`.capitalizedFirstLetter, style = fBodyStrong, color = Palette.ink)
                                    FoniaText(fix.tip, style = fBody, color = Palette.inkSoft)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

/**
 * `Label { Text(title) } icon: { FoniaIconView(icon) }` in a third of the row. A word that doesn't fit shrinks a
 * little rather than breaking in the middle, which Compose would do on a narrow phone.
 */
@Composable
private fun OneLineLabel(title: String, icon: FoniaIcon? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { FoniaIconView(it) }
        FoniaText(title, maxLines = 1, minimumScaleFactor = 0.7f)
    }
}

/** The steps, each a button that shows its picture; the one shown is in bold, on a full green circle. */
@Composable
private fun Steps(lines: List<String>, current: Int, onPick: (Int) -> Unit) {
    val circleBaseline = with(LocalDensity.current) { 8.dp.roundToPx() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEachIndexed { index, line ->
            val isCurrent = index == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .staggeredEntrance(index)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onPick(index) }
                    .semantics { selected = isCurrent }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        // `.alignmentGuide(.firstTextBaseline) { $0[.bottom] - 8 }`.
                        .alignBy { it.measuredHeight - circleBaseline }
                        .size(26.dp)
                        .background(if (isCurrent) Palette.accent else Palette.accentSoft, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    FoniaText(
                        "${index + 1}",
                        style = ui(15, UIWeight.bold, TextRole.footnote).monospacedDigit(),
                        color = if (isCurrent) Palette.buttonLabel else Palette.accentText,
                    )
                }
                FoniaText(
                    line,
                    Modifier.alignBy(FirstBaseline).weight(1f),
                    style = ui(18, if (isCurrent) UIWeight.semibold else UIWeight.regular, TextRole.body),
                    color = Palette.ink,
                )
            }
        }
    }
}
