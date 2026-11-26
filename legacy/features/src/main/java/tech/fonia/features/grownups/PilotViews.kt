package tech.fonia.features.grownups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.PracticeWeight
import tech.fonia.design.TextRole
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fCaption
import tech.fonia.design.practice
import tech.fonia.domain.Plural
import tech.fonia.domain.SoundID
import tech.fonia.features.IS_DEBUG_BUILD
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.ButtonRow
import tech.fonia.features.app.ListSection
import tech.fonia.features.app.ProgressRow
import tech.fonia.features.app.ToggleRow
import tech.fonia.features.app.ValueRow
import tech.fonia.features.app.field
import tech.fonia.features.app.rememberStoredBoolean
import tech.fonia.features.app.shortLetter
import tech.fonia.features.journal.dayAndMonth
import tech.fonia.features.ml.ModelCatalog
import tech.fonia.features.ml.ModelEntry
import tech.fonia.features.ml.ModelsHub
import tech.fonia.features.pilot.DoseSummary
import tech.fonia.features.pilot.IntensiveWeek
import tech.fonia.features.pilot.SoundDose
import tech.fonia.features.session.ShadowBadgeSettings
import tech.fonia.store.Profile
import java.time.LocalDate

/**
 * The settings for the new ML (docs/PLAN.md §6): the phone scorer of shadow mode, with its size before anything is
 * fetched and a delete; in debug builds, the switch for the agreement badge in lessons.
 */
@Composable
internal fun ModelSections(app: AppModel) {
    val ml = app.ml ?: return
    val entry = ModelCatalog.ZIPA
    val state = ml.models.state(entry)
    val footer = buildString {
        append("Un al doilea fel de a asculta, tot pe telefon. Notează ce a auzit doar în jurnalul pentru logoped, fără voce. Copilul vede și aude la fel. Se descarcă doar pe Wi-Fi.")
        if (!app.isPilotLogOn) append(" Notează doar cu jurnalul de exersare pornit.")
        if (state is ModelsHub.State.Failed && state.noNetwork) append(" Acum nu e Wi-Fi.")
    }
    // The shipped app fetches no model: its one use of the internet is the install code, and without
    // ACCESS_NETWORK_STATE (removed in legacy/app/src/main/AndroidManifest.xml) the fetcher finds no Wi-Fi to use.
    // So the row would only fail, and shows only for a package already on the phone.
    if (state != ModelsHub.State.Absent) {
        ListSection(header = "Ascultare pe sunete, în probă", footer = footer) {
            row { ShadowModelRow(ml.models, entry) }
        }
    }
    if (IS_DEBUG_BUILD) {
        var badge by rememberStoredBoolean(app.preferences, ShadowBadgeSettings.KEY)
        ListSection(
            header = "Pentru dezvoltare",
            footer = "Doar în varianta de test. În lecție apar, sus, verdictul ZIPA lângă al telefonului și acordul dintre ele.",
        ) {
            row { ToggleRow("Acordul cu ZIPA în lecție", badge) { badge = it } }
        }
    }
}

@Composable
private fun ShadowModelRow(models: ModelsHub, entry: ModelEntry) {
    if (!models.isSupported) {
        ValueRow("Nu merge pe acest telefon", "")
        return
    }
    when (val state = models.state(entry)) {
        ModelsHub.State.Ready -> ButtonRow("Șterge pachetul (${entry.megabytes} MB)", onClick = { models.remove(entry) }, destructive = true)
        is ModelsHub.State.Downloading -> ProgressRow("Se descarcă…", state.fraction)
        ModelsHub.State.Absent, is ModelsHub.State.Failed -> ButtonRow("Descarcă pachetul (${entry.megabytes} MB)", onClick = { models.download(entry) })
    }
}

private val weekLetters = listOf("L", "M", "M", "J", "V", "S", "D")

/**
 * The dose counter (03 §1.2 P6): counted productions per day and per week for each sound, and the intensive start of
 * a sound. Numbers only grow, and a day without practice is just a zero: nothing here is a streak to lose.
 */
@Composable
internal fun DoseSection(profile: Profile, app: AppModel) {
    val pilot = app.pilot ?: return
    var summary by remember { mutableStateOf<DoseSummary?>(null) }
    LaunchedEffect(profile.id, pilot) {
        summary = try {
            pilot.dose(profile.id, order = profile.targetSounds)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    val loaded = summary ?: return
    ListSection(
        header = "Încercări pe zi și pe săptămână",
        footer = "Se numără încercările în care sunetul a fost spus și a primit un răspuns: bine, aproape sau încă nu. " +
            "Ascultarea și oglinda nu intră aici. Săptămâna intensivă înseamnă primele 5 zile cu un sunet, la rând.",
    ) {
        if (loaded.sounds.isEmpty()) {
            row { ValueRow("Încă nu sunt încercări", "") }
        }
        for (dose in loaded.sounds) {
            row { SoundDoseRow(dose, loaded, app) }
        }
    }
}

@Composable
private fun SoundDoseRow(dose: SoundDose, summary: DoseSummary, app: AppModel) {
    val sound = SoundID.fromRawValue(dose.sound)?.let { id -> runCatching { app.content.sound(id) }.getOrNull() }
    val letter = sound?.shortLetter ?: dose.sound.uppercase()
    val today = summary.today
    val week = dose.week(summary.thisMonday)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            FoniaText(letter, style = practice(26, PracticeWeight.bold, TextRole.title2), color = sound?.family?.field?.deep ?: Palette.ink)
            FoniaText("Azi: ${Plural.format(dose.on(today), Plural.Noun.Incercare)}", Modifier.padding(bottom = 3.dp), style = fBodyStrong)
        }
        WeekCounts(summary.week, today, dose)
        FoniaText(
            "Săptămâna asta: ${Plural.format(week.attempts, Plural.Noun.Incercare)}, în ${Plural.format(week.days, Plural.Noun.Zi)}.",
            style = fCaption,
            color = Palette.inkSoft,
        )
        for (back in 1L..3L) {
            val earlier = dose.week(summary.thisMonday.minusWeeks(back))
            if (earlier.attempts == 0) continue
            FoniaText(
                "Săptămâna de la ${dayAndMonth(earlier.monday)}: ${Plural.format(earlier.attempts, Plural.Noun.Incercare)}, în ${Plural.format(earlier.days, Plural.Noun.Zi)}.",
                style = fCaption,
                color = Palette.inkSoft,
            )
        }
        dose.intensiveWeek?.let { FoniaText(intensiveLine(it, letter), style = fCaption, color = Palette.ink) }
    }
}

/** The intensive start, as a fact: done, or how many of the five days had practice. */
internal fun intensiveLine(week: IntensiveWeek, letter: String): String =
    if (week.isComplete) {
        "Săptămâna intensivă cu $letter: ${IntensiveWeek.DAYS} zile la rând, de la ${dayAndMonth(week.start)}."
    } else {
        "Primele ${IntensiveWeek.DAYS} zile cu $letter: ${week.daysWithPractice} din ${IntensiveWeek.DAYS} cu exerciții."
    }

/** This week, Monday first: the count under each day, nothing under the days still to come. */
@Composable
private fun WeekCounts(days: List<LocalDate>, today: LocalDate, dose: SoundDose) {
    Row(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = "Săptămâna asta, pe zile: " + days.filter { !it.isAfter(today) }.joinToString(", ") { dose.on(it).toString() }
            },
    ) {
        days.forEachIndexed { index, day ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FoniaText(weekLetters[index], style = fCaption, color = if (day == today) Palette.ink else Palette.inkSoft)
                val count = dose.on(day)
                FoniaText(
                    if (day.isAfter(today)) " " else count.toString(),
                    style = fBodyStrong,
                    color = if (count > 0) Palette.ink else Palette.inkSoft,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
