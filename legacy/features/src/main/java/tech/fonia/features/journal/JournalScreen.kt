package tech.fonia.features.journal

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.MascotPose
import tech.fonia.design.MascotView
import tech.fonia.design.Palette
import tech.fonia.design.PlusButtonStyle
import tech.fonia.design.SceneBackdrop
import tech.fonia.design.SceneName
import tech.fonia.design.StyledButton
import tech.fonia.design.Surface
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.fBody
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fHeadline
import tech.fonia.design.fTitle
import tech.fonia.design.monospacedDigit
import tech.fonia.design.ui
import tech.fonia.features.app.ActionRole
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.rememberAdultGate
import tech.fonia.features.app.Destination
import tech.fonia.features.app.DialogAction
import tech.fonia.features.app.FoniaAlert
import tech.fonia.features.app.FoniaSheet
import tech.fonia.features.app.PageHeader
import tech.fonia.features.app.SheetDetent
import tech.fonia.features.app.capitalizedFirstLetter
import tech.fonia.features.app.isChild
import tech.fonia.features.home.PathModel
import tech.fonia.features.home.rememberPushDestination
import tech.fonia.features.session.key
import tech.fonia.store.MoodlessNote
import tech.fonia.store.localDay
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Dates in Romanian, as `Locale(identifier: "ro_RO")` formats them. */
private val romanian = Locale("ro", "RO")

/** "5 noiembrie": `.dateTime.day().month(.wide)`. */
internal fun dayAndMonth(date: LocalDate): String = DateTimeFormatter.ofPattern("d MMMM", romanian).format(date)

/** One day of the month grid: its date and its `YYYY-MM-DD` key. */
data class JournalDay(val date: LocalDate, val key: String) {
    val id: String get() = key
}

private val weekdays = listOf("L", "M", "M", "J", "V", "S", "D")

/**
 * A month, with the days something was done marked. A day opens what happened then, and the plus adds a note for
 * the logoped. It shows what was done and never what was missed.
 */
@Composable
fun JournalScreen(path: PathModel, app: AppModel) {
    var month by rememberSaveable { mutableStateOf(YearMonth.now()) }
    var openedDay by remember { mutableStateOf<JournalDay?>(null) }
    var shownDay by remember { mutableStateOf<JournalDay?>(null) }
    val push = rememberPushDestination()
    val guard = rememberAdultGate(app)
    val zone = ZoneId.systemDefault()
    val practised = path.sessions.filter { it.endedAt != null || it.attempts > 0 }.map { it.localDay }.toSet()
    val today = LocalDate.now(zone)
    // The month's days in a grid, with blanks before the first so it starts under its weekday (Monday first).
    val first = month.atDay(1)
    val blanks = first.dayOfWeek.value - 1
    val cells: List<JournalDay?> = List(blanks) { null } + (1..month.lengthOfMonth()).map { number ->
        val date = month.atDay(number)
        JournalDay(date, localDay(date.atStartOfDay(zone).toInstant(), zone))
    }

    Box(Modifier.fillMaxSize()) {
        SceneBackdrop(SceneName.cold)
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            PageHeader("Jurnal")
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 26.dp)
                    .padding(end = 130.dp),
            ) {
                FoniaText(
                    month.month.getDisplayName(TextStyle.FULL_STANDALONE, romanian).capitalizedFirstLetter,
                    style = ui(26, UIWeight.black, TextRole.title),
                    color = Palette.ink,
                    maxLines = 1,
                    minimumScaleFactor = 0.7f,
                )
                FoniaText("${month.year}", style = ui(18, UIWeight.black, TextRole.headline), color = Palette.ink)
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .padding(top = 34.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
                    for (letter in weekdays) {
                        FoniaText(letter, Modifier.weight(1f), style = ui(15, UIWeight.semibold, TextRole.subheadline), color = Palette.ink, textAlign = TextAlign.Center)
                    }
                }
                for (week in cells.chunked(7)) {
                    Row(Modifier.fillMaxWidth()) {
                        for (day in week) {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                if (day != null) {
                                    DayCell(day, isToday = day.date == today, hasPractice = day.key in practised) {
                                        openedDay = day
                                        shownDay = day
                                    }
                                } else {
                                    Spacer(Modifier.height(44.dp))
                                }
                            }
                        }
                        repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            FoniaText(
                "Alege o zi ca să vezi ce ai lucrat atunci.",
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .padding(top = 20.dp),
                style = fHeadline,
                color = Palette.ink,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f).heightIn(min = 12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MonthStep(FoniaIcon.back, "Luna trecută", enabled = true) { month = month.minusMonths(1) }
                StyledButton(
                    // A child's notes are for the logoped: the adult writes them.
                    onClick = { if (path.profile.isChild) guard { push(Destination.Notes(path.profile.id)) } else push(Destination.Notes(path.profile.id)) },
                    style = PlusButtonStyle(),
                    modifier = Modifier.semantics { contentDescription = tech.fonia.features.grownups.AudienceCopy.addNoteLabel(path.profile.isChild) },
                ) {
                    FoniaIconView(FoniaIcon.plus, size = 24f)
                }
                MonthStep(FoniaIcon.forward, "Luna următoare", enabled = month != YearMonth.now(zone)) { month = month.plusMonths(1) }
            }
        }
        // Foni peeks in from the right, over everything, and lets taps through.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars)
                .offset(x = 62.dp, y = 6.dp)
                .size(170.dp)
                .clearAndSetSemantics {},
        ) {
            MascotView(MascotPose.peek, Modifier.fillMaxSize())
        }
    }

    FoniaSheet(
        isPresented = openedDay != null,
        onDismiss = { openedDay = null },
        detents = listOf(SheetDetent.medium, SheetDetent.large),
        showsDragIndicator = true,
    ) {
        shownDay?.let { DaySheet(it, path, app) }
    }
}

@Composable
private fun MonthStep(icon: FoniaIcon, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(56.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = label }
            // A disabled plain button is drawn faded on iOS.
            .alpha(if (enabled) 1f else 0.3f),
        contentAlignment = Alignment.Center,
    ) {
        FoniaIconView(icon, size = 14f, tint = Palette.ink)
    }
}

@Composable
private fun DayCell(day: JournalDay, isToday: Boolean, hasPractice: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = dayAndMonth(day.date)
                stateDescription = listOfNotNull(if (isToday) "Azi" else null, if (hasPractice) "Ai exersat" else null).joinToString(", ")
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .then(if (isToday) Modifier.background(Palette.today, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            FoniaText(
                "${day.date.dayOfMonth}",
                style = ui(17, UIWeight.bold, TextRole.headline).monospacedDigit(),
                color = if (isToday) Color.White else Palette.ink,
            )
            if (hasPractice) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 3.dp)
                        .size(7.dp)
                        .background(if (isToday) Color.White else Palette.accent, CircleShape),
                )
            }
        }
    }
}

/** What was done on one day, and what was noted. */
@Composable
private fun DaySheet(day: JournalDay, path: PathModel, app: AppModel) {
    var notes by remember { mutableStateOf<List<MoodlessNote>>(emptyList()) }
    var missions by remember { mutableStateOf<List<tech.fonia.store.MissionCheck>>(emptyList()) }
    LaunchedEffect(day.key) {
        val all = try {
            app.repository.notes(path.profile.id)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        notes = all.filter { it.localDay == day.key }
        val answered = try {
            app.repository.missionAnswers(path.profile.id)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        missions = answered.filter { it.localDay == day.key }
    }
    val units = path.catalog.allUnits(path.soundOrder)
    val titles = path.sessions
        .filter { it.localDay == day.key && (it.endedAt != null || it.attempts > 0) }
        .mapNotNull { session ->
            val focus = session.focus ?: return@mapNotNull null
            units.firstNotNullOfOrNull { unit -> unit.lessons.firstOrNull { it.focus.key == focus }?.title }
        }
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.paper)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FoniaText(dayAndMonth(day.date), style = fTitle, color = Palette.ink)
        if (titles.isEmpty() && notes.isEmpty() && missions.isEmpty()) {
            FoniaText("Nimic în ziua asta.", style = fBody, color = Palette.ink)
        }
        for (title in titles) {
            Surface(padding = 14.dp) { FoniaText(title, style = fBodyStrong, color = Palette.ink) }
        }
        for (mission in missions) {
            val answer = mission.answer ?: continue
            Surface(padding = 14.dp) { FoniaText(MissionFollowUp.line(MissionFollowUp.letter(app, mission), answer), style = fBody, color = Palette.ink) }
        }
        for (note in notes) {
            Surface(padding = 14.dp) { FoniaText(note.text, style = fBody, color = Palette.ink) }
        }
    }
}
