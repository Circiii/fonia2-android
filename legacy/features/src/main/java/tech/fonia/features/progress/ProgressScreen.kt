package tech.fonia.features.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.PracticeWeight
import tech.fonia.design.PressableStyle
import tech.fonia.design.ProgressPill
import tech.fonia.design.RingMedal
import tech.fonia.design.StatCard
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.ToolButtonStyle
import tech.fonia.design.UIWeight
import tech.fonia.design.fBody
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fCaption
import tech.fonia.design.fHeadline
import tech.fonia.design.fTitle
import tech.fonia.design.practice
import tech.fonia.design.ui
import tech.fonia.domain.Plural
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.AppRoute
import tech.fonia.features.app.ClipDecoder
import tech.fonia.features.app.capitalizedFirstLetter
import tech.fonia.features.app.isChild
import tech.fonia.features.app.orderedSounds
import tech.fonia.features.app.shortLetter
import tech.fonia.features.home.DayActivity
import tech.fonia.features.home.PathModel
import tech.fonia.features.home.StatTile
import tech.fonia.features.home.rememberPush
import tech.fonia.features.journal.dayAndMonth
import tech.fonia.store.Profile
import tech.fonia.store.Recording
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/** What's been done, in counts that only go up. There's no streak to lose and no score to chase. */
@Composable
fun ProgressScreen(profile: Profile, app: AppModel, path: PathModel, bottomInset: Dp) {
    var recordings by remember { mutableStateOf<List<Recording>>(emptyList()) }
    LaunchedEffect(profile.id) {
        app.repository.recordingsStream(profile.id).collect { recordings = it }
    }
    val push = rememberPush()
    val chosen = path.chosenSounds
    val workedSounds = chosen + profile.orderedSounds(app.content).filter { sound ->
        path.saved(sound.id) != null && chosen.none { it.id == sound.id }
    }
    val today = path.week.firstOrNull { it.isToday }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Palette.paper)
                .padding(horizontal = 12.dp)
                .padding(top = 2.dp, bottom = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            FoniaText("Progres", Modifier.semantics { heading() }, style = fTitle, color = Palette.ink)
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                StyledButton(
                    onClick = { push(AppRoute.Settings) },
                    style = ToolButtonStyle(diameter = 44.dp),
                    modifier = Modifier.semantics { contentDescription = "Setări" },
                ) {
                    FoniaIconView(FoniaIcon.settings, size = 22f)
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .padding(top = 8.dp, bottom = 16.dp + bottomInset)
                    .wrapContentWidth()
                    .widthIn(max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TodayCard(today)
                if (profile.track == Profile.Track.Ro) tech.fonia.features.check.CheckCard(path.lastCheck, app)
                StyledButton(onClick = { push(AppRoute.Journal) }, style = PressableStyle(), onClickLabel = "Deschide jurnalul") {
                    WeekCard(path.week)
                }
                if (!profile.isChild) CourseCard(path)
                if (workedSounds.isNotEmpty()) {
                    StatCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            FoniaText("Sunetele tale", style = ui(16, UIWeight.bold, TextRole.headline), color = Palette.ink)
                            Row(verticalAlignment = Alignment.Top) {
                                for (sound in workedSounds.take(3)) {
                                    val unit = path.catalog.unit(sound)
                                    val done = path.doneCount(unit)
                                    val total = unit.lessons.size
                                    StyledButton(
                                        onClick = { push(AppRoute.Unit(unit.id)) },
                                        style = PressableStyle(),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clearAndSetSemantics {
                                                role = Role.Button
                                                contentDescription = "Sunetul ${sound.shortLetter}"
                                                ChildCounts.medalValue(done, total, profile.isChild)?.let { stateDescription = it }
                                            },
                                    ) {
                                        RingMedal(done.toDouble() / max(total, 1), ChildCounts.medalCaption(done, total, profile.isChild)) {
                                            FoniaText(sound.shortLetter, style = practice(24, PracticeWeight.bold, TextRole.title2), color = Palette.ink)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                val tiles = listOf(
                    Triple(FoniaIcon.fire, Palette.today, "${path.totalDays}") to Plural.word(path.totalDays, Plural.Noun.Zi).capitalizedFirstLetter,
                    Triple(FoniaIcon.star, Palette.starTint, "${path.totalLessons}") to "Stele",
                    Triple(FoniaIcon.target, Palette.accent, "${path.totalAttempts}") to "Încercări",
                    Triple(FoniaIcon.hourglass, Palette.accent, "${path.totalMinutes}") to Plural.word(path.totalMinutes, Plural.Noun.Minut).capitalizedFirstLetter,
                )
                // Before anything is done the four counts would all read 0, so one calm line says what gathers here.
                if (path.totalDays == 0) {
                    StatCard {
                        FoniaText(
                            "După prima lecție, aici se adună zilele, stelele și minutele tale.",
                            Modifier.padding(16.dp),
                            style = fBody,
                            color = Palette.inkSoft,
                        )
                    }
                } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (pair in tiles.chunked(2)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            for ((look, label) in pair) {
                                StatTile(
                                    look.first,
                                    look.second,
                                    look.third,
                                    label,
                                    Modifier
                                        .weight(1f)
                                        .background(Palette.sheet, ContinuousRoundedRectangle(20.dp))
                                        .padding(14.dp),
                                )
                            }
                        }
                    }
                }
                if (recordings.isNotEmpty()) RecordingsCard(recordings, app)
            }
        }
    }
}

@Composable
private fun TodayCard(today: DayActivity?) {
    val line = if (today == null || !today.hasPractice) "Încă n-ai exersat azi" else "Ai făcut ${Plural.format(today.attempts, Plural.Noun.Incercare)}"
    StatCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FoniaText("Azi", style = ui(15, UIWeight.regular, TextRole.subheadline), color = Palette.ink)
            FoniaText(line, style = fHeadline, color = Palette.ink)
        }
    }
}

/** Pronunciation moves after hundreds of minutes spread over weeks, so that is the honest thing to show. */
@Composable
private fun CourseCard(path: PathModel) {
    StatCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FoniaText("Spre 400 de minute", style = ui(16, UIWeight.bold, TextRole.headline), color = Palette.ink)
            ProgressPill(min(path.totalMinutes, 400), 400)
            FoniaText(
                "Ai ${Plural.format(path.totalMinutes, Plural.Noun.Minut)}. Merge cel mai bine cu puțin și des.",
                style = fBody,
                color = Palette.inkSoft,
            )
        }
    }
}

@Composable
private fun RecordingsCard(recordings: List<Recording>, app: AppModel) {
    StatCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FoniaText("Înregistrările tale", Modifier.padding(bottom = 6.dp), style = ui(16, UIWeight.bold, TextRole.headline), color = Palette.ink)
            for (recording in recordings.take(10)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { app.play(recording) }
                        .heightIn(min = 56.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(32.dp).background(Palette.accent, CircleShape), contentAlignment = Alignment.Center) {
                        FoniaIconView(FoniaIcon.play, size = 14f, tint = Palette.buttonLabel)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        FoniaText(recording.label, style = fBodyStrong, color = Palette.ink)
                        FoniaText(dayAndMonth(recording.ts.atZone(ZoneId.systemDefault()).toLocalDate()), style = fCaption, color = Palette.inkSoft)
                    }
                }
            }
        }
    }
}

/** Plays a kept recording back. */
fun AppModel.play(recording: Recording) {
    val file = runCatching { repository.recordingFileURL(recording) }.getOrNull() ?: return
    scope.launch {
        val clip = try {
            ClipDecoder.decode(file)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@launch
        }
        audio.play(clip)
    }
}

/**
 * Bars stand for minutes, the one count every kind of lesson has. A day with practice under a minute still counts one,
 * in the bars and in the total alike.
 */
private fun minutes(day: DayActivity): Int = day.minutes

/** How much happened on each day this week, as columns. A day with nothing is just low, never red. */
@Composable
private fun WeekCard(days: List<DayActivity>) {
    val highest = max(days.maxOfOrNull { minutes(it) } ?: 0, 1)
    StatCard(
        Modifier.clearAndSetSemantics {
            contentDescription = "Săptămâna asta"
            stateDescription = "${Plural.format(days.count { it.hasPractice }, Plural.Noun.Zi)}, ${Plural.format(days.sumOf { minutes(it) }, Plural.Noun.Minut)}"
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FoniaText("Săptămâna asta", Modifier.weight(1f), style = ui(16, UIWeight.bold, TextRole.headline), color = Palette.ink)
                FoniaText("Jurnal", style = ui(14, UIWeight.semibold, TextRole.footnote), color = Palette.accentText)
                Spacer(Modifier.size(width = 8.dp, height = 0.dp))
                FoniaIconView(FoniaIcon.forward, size = 11f, tint = Palette.accentText)
            }
            Row(Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                for (day in days) {
                    val count = minutes(day)
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        FoniaText(
                            if (count > 0) "$count" else " ",
                            style = ui(12, UIWeight.semibold, TextRole.caption).copy(fontFeatureSettings = "tnum"),
                            color = Palette.inkSoft,
                        )
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                            val share = count.toFloat() / highest
                            Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(max(12.dp.value, maxHeight.value * share).dp)
                                    .background(if (count > 0) Palette.accent else Palette.progressTrack, ContinuousRoundedRectangle(8.dp)),
                            )
                        }
                        FoniaText(
                            day.letter,
                            style = ui(13, if (day.isToday) UIWeight.semibold else UIWeight.regular, TextRole.caption),
                            color = if (day.isToday) Palette.ink else Palette.inkSoft,
                        )
                    }
                }
            }
            FoniaText("Minute pe zi", style = fCaption, color = Palette.inkSoft)
        }
    }
}
