package tech.fonia.features.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.PracticeSheet
import tech.fonia.design.PracticeWeight
import tech.fonia.design.QuietButtonStyle
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.fTitle
import tech.fonia.design.practice
import tech.fonia.domain.SoundID
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.HeaderPage
import tech.fonia.features.app.LocalDismiss
import tech.fonia.features.app.ignoringFailure
import tech.fonia.features.app.isChild
import tech.fonia.features.app.shortLetter
import tech.fonia.features.guide.AdultCards
import tech.fonia.store.MissionCheck
import tech.fonia.store.Profile
import tech.fonia.store.localDay
import java.time.Instant

/**
 * The words of the question about the last "Azi, peste zi", and the line its answer leaves in the journal and the
 * report. The question is for the adult; the child is never asked and never told what was answered.
 */
object MissionFollowUp {
    const val QUESTION = "A folosit sunetul peste zi?"
    const val LATER = "Nu acum"

    fun label(answer: MissionCheck.Answer): String = when (answer) {
        MissionCheck.Answer.Yes -> "Da"
        MissionCheck.Answer.Sometimes -> "Uneori"
        MissionCheck.Answer.NotYet -> "Încă nu"
    }

    /** "S: A folosit sunetul peste zi? Uneori." */
    fun line(letter: String, answer: MissionCheck.Answer): String = "$letter: $QUESTION ${label(answer)}."

    /**
     * The page title: on a child's profile the question is for the adult beside them, so it says so, as the adult
     * cards do. An adult practising alone sees the mission's own title.
     */
    fun title(isChild: Boolean): String = if (isChild) AdultCards.PAGE_TITLE else "Azi, peste zi"

    /** The capital the journal and the report show for a mission's sound. */
    fun letter(app: AppModel, mission: MissionCheck): String =
        SoundID.fromRawValue(mission.sound)?.let { app.content.sound(it).shortLetter } ?: mission.sound.uppercase()
}

/**
 * Before the next session of a profile that was given a mission on an earlier day, asks the adult once how it went,
 * then goes on to [content]. "Nu acum" goes on without an answer; either way the question is not shown again.
 * [asks] is false for the quick check and anything else that isn't practice. [onClose] is the page's cross.
 */
@Composable
fun MissionCheckGate(profile: Profile, app: AppModel, asks: Boolean = true, onClose: () -> Unit, content: @Composable () -> Unit) {
    var step by remember { mutableStateOf<MissionStep>(MissionStep.Looking) }
    val scope = app.scope
    when (val shown = step) {
        MissionStep.Looking -> {
            Box(Modifier.fillMaxSize().background(Palette.paper))
            LaunchedEffect(Unit) {
                val pending = if (asks && !app.arguments.hidesAdultCards) {
                    runCatching { app.repository.pendingMission(profile.id, today = localDay(Instant.now())) }.getOrNull()
                } else {
                    null
                }
                step = if (pending != null) MissionStep.Asking(pending) else MissionStep.Done
            }
        }
        is MissionStep.Asking -> CompositionLocalProvider(LocalDismiss provides onClose) {
            MissionCheckCard(shown.mission, profile.isChild, MissionFollowUp.letter(app, shown.mission)) { answer ->
                step = MissionStep.Done
                scope.launch { ignoringFailure { app.repository.answerMission(shown.mission.id, answer) } }
            }
        }
        MissionStep.Done -> content()
    }
}

private sealed interface MissionStep {
    data object Looking : MissionStep

    data class Asking(val mission: MissionCheck) : MissionStep

    data object Done : MissionStep
}

/**
 * The question on a white sheet, in the look of the cards for the adult: the sound, the question, three equal answers
 * and a quiet way past.
 */
@Composable
fun MissionCheckCard(mission: MissionCheck, isChild: Boolean, letter: String, onAnswer: (MissionCheck.Answer?) -> Unit) {
    HeaderPage(MissionFollowUp.title(isChild), closes = true) {
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 620.dp).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                PracticeSheet {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        FoniaText(letter, Modifier.clearAndSetSemantics {}, style = practice(26, PracticeWeight.bold, TextRole.title2), color = Palette.ink)
                        FoniaText(MissionFollowUp.QUESTION, Modifier.semantics { heading() }, style = fTitle, color = Palette.ink)
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (answer in MissionCheck.Answer.entries) {
                                StyledButton(
                                    onClick = { onAnswer(answer) },
                                    style = FoniButtonStyle(size = FoniButtonStyle.Size.regular, kind = FoniButtonStyle.Kind.secondary),
                                ) { FoniaText(MissionFollowUp.label(answer)) }
                            }
                        }
                    }
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .background(Palette.paper)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            StyledButton(onClick = { onAnswer(null) }, style = QuietButtonStyle(Palette.inkSoft)) { FoniaText(MissionFollowUp.LATER) }
        }
    }
}
