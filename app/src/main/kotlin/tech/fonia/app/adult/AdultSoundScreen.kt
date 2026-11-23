package tech.fonia.app.adult

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import tech.fonia.app.AppGraph
import tech.fonia.app.data.Ladder
import tech.fonia.app.data.PracticeState
import tech.fonia.app.text.T
import tech.fonia.designsystem.components.ContinueButton
import tech.fonia.designsystem.components.ContinueStyle
import tech.fonia.model.Rung
import tech.fonia.model.SoundId

/**
 * The adult's page for one sound (M3): where the ladder stands, the lock after `isolated` („Da, sună bine” / „Încă nu” /
 * „Întreb logopedul”, chapter 01 §5), „Deblochează tot pentru acest sunet”, and today's mission with its tick.
 */
@Composable
fun AdultSoundScreen(graph: AppGraph, sound: SoundId, onBack: () -> Unit) {
    var state by remember { mutableStateOf<PracticeState?>(null) }
    var rungNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(sound) {
        state = graph.practice.state()
        rungNames = runCatching { graph.content.get().content.parent.rungNames }.getOrDefault(emptyMap())
    }
    fun change(f: (PracticeState) -> PracticeState) { scope.launch { state = graph.practice.update(f) } }

    AdultPage(T.adultSoundTitle(sound), onBack, graph.settings.reduceMotion) {
        val st = state ?: return@AdultPage
        val p = st.of(sound)
        AdultCard {
            AdultText(T.adultSoundRung(rungNames[p.rung.raw] ?: p.rung.raw))
            AdultText(T.adultSoundWins(p.isolatedWins.coerceAtMost(Ladder.ISOLATED_WINS), Ladder.ISOLATED_WINS), secondary = true)
        }
        AdultCard {
            AdultHeading(T.adultLockTitle)
            if (p.adultConfirmed) {
                AdultText(T.adultLockDone)
            } else {
                AdultText(T.adultLockBody, secondary = true)
                ContinueButton(T.adultLockYes, onClick = { change { it.with(sound, Ladder::confirmIsolated) } }, arrow = false)
                ContinueButton(T.adultLockNo, onClick = onBack, style = ContinueStyle.Outlined, arrow = false)
                ContinueButton(T.adultLockAsk, onClick = onBack, style = ContinueStyle.Outlined, arrow = false)
            }
        }
        AdultToggle(T.adultUnlockAll, T.adultUnlockAllHelp, p.unlockAll) { on -> change { it.with(sound) { sp -> sp.copy(unlockAll = on) } } }
        AdultHeading(T.adultMissionHeading)
        val m = st.mission
        if (m == null) {
            AdultText(T.missionNone, secondary = true)
        } else {
            AdultCard {
                AdultText(m.text)
                AdultCaption(T.missionAdult)
                if (m.done) AdultText(T.missionDoneTick) else ContinueButton(T.missionDone, onClick = { change { it.copy(mission = m.copy(done = true)) } }, arrow = false)
            }
        }
        if (p.rung == Rung.Isolated && !p.adultConfirmed) AdultCaption(T.lockAdultBody)
    }
}
