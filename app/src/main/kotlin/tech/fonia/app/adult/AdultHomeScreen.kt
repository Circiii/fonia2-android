package tech.fonia.app.adult

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.fonia.app.AppGraph
import tech.fonia.app.data.AdultReport
import tech.fonia.app.data.CalibrationBook
import tech.fonia.app.data.SoundReport
import tech.fonia.app.text.T
import tech.fonia.designsystem.components.QuietButton
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaSpace
import java.time.LocalDate

/**
 * ADULT-HOME „Cum merge” (chapter 01 §9): one sentence for the week, then one card per practised sound — tries today
 * and this week, how often the phone said „bine”, how often it abstained (and the adult answered), what it suspected
 * „în probă” in neutral words, and the calibration. Then the rows: calibration, settings.
 */
@Composable
fun AdultHomeScreen(graph: AppGraph, onCalibrate: () -> Unit, onSettings: () -> Unit, onBack: () -> Unit, onSound: (tech.fonia.model.SoundId) -> Unit = {}) {
    var report by remember { mutableStateOf<AdultReport?>(null) }
    LaunchedEffect(Unit) {
        report = runCatching {
            val attempts = graph.attempts.allAttempts()
            val book = graph.calibration.refresh()
            AdultReport.build(attempts, book, LocalDate.now())
        }.getOrElse { Log.e("FoniaAdult", "report", it); AdultReport(0, 0, emptyList()) }
    }
    AdultPage(T.adultHomeTitle, onBack, graph.settings.reduceMotion) {
        val r = report ?: return@AdultPage
        AdultCard { AdultText(T.adultWeek(r.daysThisWeek, r.attemptsThisWeek)) }
        if (r.sounds.isEmpty()) AdultText(T.adultNothing, secondary = true)
        r.sounds.forEach { SoundCard(it) }
        if (r.sounds.any { it.trial.isNotEmpty() }) AdultCaption(T.adultMayErr)
        // M3: per sound, the lock after `isolated`, „Deblochează tot”, today's mission
        AdultHeading(T.adultSoundsHeading)
        for (s in tech.fonia.app.path.ROAD) AdultRow(T.adultSoundTitle(s), onClick = { onSound(s) })
        AdultRow(T.adultRowCalibrate, T.calWhy.substringBefore(". ") + ".", onClick = onCalibrate)
        AdultRow(T.adultRowSettings, onClick = onSettings)
    }
}

@Composable
private fun SoundCard(s: SoundReport) {
    val c = FoniaTheme.colors
    AdultCard(Modifier.semantics(mergeDescendants = true) { contentDescription = "Sunetul ${T.soundName(s.sound)}" }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // the adult shell has no stickers (chapter 01 §2): a plain Allo letter
            BasicText(s.sound.letter, style = FoniaTheme.type.display.copy(color = c.accentDeep), modifier = Modifier.width(64.dp))
            Spacer(Modifier.width(FoniaSpace.s))
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(T.adultSoundToday(s.today), style = FoniaTheme.type.label.copy(color = c.ink))
                BasicText(T.adultSoundWeek(s.week), style = FoniaTheme.type.label.copy(color = c.ink))
                BasicText(
                    if (s.calibrated) T.adultCalibrated else T.adultCalibrating(s.confirmedGood.coerceAtMost(CalibrationBook.CALIBRATED_AT), CalibrationBook.CALIBRATED_AT),
                    style = FoniaTheme.type.caption.copy(color = if (s.calibrated) c.parrotDeep else c.inkSecondary),
                )
            }
        }
        AdultText(T.adultCorrect(s.correctWeek, s.judgedWeek))
        AdultText(T.adultAbstained(s.abstainedWeek, s.adultAnsweredWeek), secondary = true)
        if (s.trial.isNotEmpty()) {
            for (o in s.trial.take(3)) {
                AdultText(T.adultTrialObservation(s.sound, o.heardAs, T.kindName(o.kind.raw), o.times, s.judgedWeek))
            }
            AdultCaption(T.adultTrialNote)
        }
    }
}
