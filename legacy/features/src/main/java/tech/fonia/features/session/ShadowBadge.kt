package tech.fonia.features.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import tech.fonia.design.FoniaText
import tech.fonia.design.fCaption
import tech.fonia.features.IS_DEBUG_BUILD
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.rememberStoredBoolean
import tech.fonia.features.pilot.ShadowRecord
import tech.fonia.store.Attempt

/** The developer switch of the badge, a preference like the other adult switches. */
object ShadowBadgeSettings {
    const val KEY = "devShadowAgreement"
}

/**
 * Debug builds only, behind the switch in the settings: shadow mode's last verdict next to the one the child got, and
 * how often the two agreed in this session (decisions only; an abstention on either side is counted apart). Release
 * builds compile this to nothing, so nobody but a developer ever sees ZIPA's verdict. It takes no touches.
 */
@Composable
fun ShadowBadge(app: AppModel, modifier: Modifier = Modifier) {
    if (!IS_DEBUG_BUILD) return
    val ml = app.ml ?: return
    val enabled by rememberStoredBoolean(app.preferences, ShadowBadgeSettings.KEY)
    if (!enabled) return
    val latest by ml.shadow.latest.collectAsState()
    val tally by ml.shadow.tally.collectAsState()
    // Only this session's takes: what was there when the badge appeared is the baseline.
    val startTally = remember { ml.shadow.tally.value }
    val startLatest = remember { ml.shadow.latest.value }
    val session = tally - startTally
    val last = latest?.takeIf { it !== startLatest }
    val first = when {
        !ml.isShadowing -> "ZIPA: pachetul nu e pe telefon"
        last == null -> "ZIPA: aștept prima încercare"
        else -> "ZIPA ${zipaWord(last)} · telefonul ${phoneWord(last.shown)}"
    }
    val second = "Acord ${session.agreed} din ${session.compared}" +
        (if (session.phoneAbstained + session.zipaAbstained > 0) " · fără verdict ${session.phoneAbstained + session.zipaAbstained}" else "") +
        (if (session.notScored > 0) " · nescorate ${session.notScored}" else "")
    Column(
        modifier
            .background(Color(0xCC1C1C1E), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        FoniaText(first, style = fCaption, color = Color.White)
        FoniaText(second, style = fCaption, color = Color.White)
    }
}

private fun zipaWord(record: ShadowRecord): String = when (record.verdict) {
    "correct" -> "corect"
    "tryAgain" -> "mai încearcă"
    "notHeard" -> "nu am auzit clar" + (record.abstention?.let { " ($it)" } ?: "")
    else -> "?"
}

private fun phoneWord(outcome: Attempt.Outcome): String = when (outcome) {
    Attempt.Outcome.Good -> "bine"
    Attempt.Outcome.Almost -> "aproape"
    Attempt.Outcome.NotYet -> "încă nu"
    Attempt.Outcome.Unclear -> "neclar"
    Attempt.Outcome.NotJudged -> "pentru adult"
}
