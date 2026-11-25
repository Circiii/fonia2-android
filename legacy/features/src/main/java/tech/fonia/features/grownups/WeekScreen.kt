package tech.fonia.features.grownups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tech.fonia.design.ChoiceTileStyle
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.PracticeWeight
import tech.fonia.design.RoundButtonStyle
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.ToolButtonStyle
import tech.fonia.design.UIWeight
import tech.fonia.design.fBody
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fCaption
import tech.fonia.design.fHeadline
import tech.fonia.design.practice
import tech.fonia.design.ui
import tech.fonia.domain.SoundID
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.ClipDecoder
import tech.fonia.features.app.HeaderPage
import tech.fonia.features.app.InsetGroupedList
import tech.fonia.features.app.ListSection
import tech.fonia.features.app.SystemColors
import tech.fonia.features.app.field
import tech.fonia.features.app.isChild
import tech.fonia.features.app.orderedSounds
import tech.fonia.features.app.rememberStoredBoolean
import tech.fonia.features.app.shortLetter
import tech.fonia.features.app.title
import tech.fonia.features.guide.AdultCards
import tech.fonia.features.session.Settings
import tech.fonia.features.session.WeakJudgeGate
import tech.fonia.store.Profile
import java.time.LocalDate
import java.util.UUID

/**
 * One week of a child's practice, for the adult and the logoped (iOS `WeekScreen`): what was worked on, what moved,
 * and a few kept takes to hear on this phone, each with the adult's two answers. It opens on this week; earlier weeks
 * are a tap away. Behind the adult check, like every page reached from Setări.
 */
@Composable
fun WeekScreen(profile: Profile, app: AppModel) {
    val keepRecordings by rememberStoredBoolean(app.preferences, Settings.keepRecordings)
    val thisMonday = remember { PracticeReport.monday(LocalDate.now()) }
    var monday by remember { mutableStateOf(thisMonday) }
    var firstMonday by remember { mutableStateOf<LocalDate?>(null) }
    var report by remember { mutableStateOf<WeekReport?>(null) }
    var playing by remember { mutableStateOf<UUID?>(null) }
    var playback by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun stopPlaying() {
        if (playing == null) return
        playing = null
        playback?.cancel()
        app.scope.launch { app.audio.stopSpeaking() }
    }

    /** Plays the take, or stops it when it is the one playing. Nothing plays while the microphone is open. */
    fun toggle(take: WeekReport.Take) {
        if (playing == take.id) {
            stopPlaying()
            return
        }
        if (app.audio.isListening) return
        val file = runCatching { app.repository.recordingFileURL(take.recording) }.getOrNull() ?: return
        playback?.cancel()
        playing = take.id
        playback = scope.launch {
            try {
                app.audio.stopSpeaking()
                app.audio.play(ClipDecoder.decode(file))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            } finally {
                if (playing == take.id) playing = null
            }
        }
    }

    /** The adult's answer goes on the attempt, next to the phone's, as it would during a lesson. */
    fun mark(take: WeekReport.Take, ear: AdultEar) {
        val outcome = ear.outcome(take.attempt.adultOutcome)
        if (outcome == take.attempt.adultOutcome) return
        report = report?.marking(take.id, outcome)
        app.scope.launch {
            runCatching { app.repository.setAdultOutcome(take.id, outcome) }
            // On the sounds the phone still hears poorly, two „Sună bine” on two days can move the child on.
            runCatching { WeakJudgeGate.apply(app.repository, app.profiles.firstOrNull { it.id == profile.id } ?: profile) }
        }
    }

    LaunchedEffect(monday) {
        val id = profile.id
        val sessions = orEmpty { app.repository.sessions(id) }
        val attempts = orEmpty { app.repository.attempts(id, monday.toString(), monday.plusDays(6).toString()) }
        val recordings = orEmpty { app.repository.recordings(id) }
        val progress = orEmpty { app.repository.soundProgress(id) }
        firstMonday = WeekReport.firstMonday(sessions)
        report = WeekReport.build(
            monday = monday,
            sessions = sessions,
            attempts = attempts,
            recordings = recordings,
            progress = progress,
            order = profile.orderedSounds(app.content).map { it.id.rawValue },
        )
    }
    DisposableEffect(Unit) { onDispose { stopPlaying() } }

    HeaderPage(WeekCopy.TITLE) {
        InsetGroupedList {
            val current = report
            ListSection(footer = if (profile.isChild && !keepRecordings) WeekCopy.KEEPING_OFF else null) {
                row {
                    WeekSwitcher(
                        monday = monday,
                        thisMonday = thisMonday,
                        canGoBack = firstMonday?.let { monday > it } ?: false,
                        onMove = { days ->
                            stopPlaying()
                            monday = monday.plusDays(days)
                        },
                    )
                }
                if (current != null) {
                    row {
                        FoniaText(WeekCopy.summary(current), Modifier.padding(horizontal = 16.dp, vertical = 14.dp), style = fBody, color = Palette.ink)
                    }
                }
            }
            if (current != null) {
                for (sound in current.sounds) {
                    ListSection {
                        row { SoundSummary(sound, app) }
                        if (sound.picked.isNotEmpty()) {
                            row {
                                FoniaText(WeekCopy.takesLead(sound), Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = fCaption, color = Palette.inkSoft)
                            }
                            for (take in sound.picked) {
                                row {
                                    TakeRow(
                                        take,
                                        isPlaying = playing == take.id,
                                        onToggle = { toggle(take) },
                                        onMark = { ear -> mark(take, ear) },
                                    )
                                }
                            }
                        }
                    }
                }
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (current.sounds.any { it.picked.isNotEmpty() }) {
                        FoniaText(WeekCopy.WHAT_MARKS_DO, style = fCaption, color = SystemColors.secondaryLabel)
                        if (WeekCopy.showsWeakSoundMarks(current.sounds, profile.isChild)) {
                            FoniaText(WeekCopy.WEAK_SOUND_MARKS, style = fCaption, color = SystemColors.secondaryLabel)
                        }
                        FoniaText(WeekCopy.STAYS_HERE, style = fCaption, color = SystemColors.secondaryLabel)
                    }
                    FoniaText(AdultCards.REPORT_DISCLAIMER, style = fCaption, color = SystemColors.secondaryLabel)
                }
            }
        }
    }
}

@Composable
private fun WeekSwitcher(monday: LocalDate, thisMonday: LocalDate, canGoBack: Boolean, onMove: (Long) -> Unit) {
    val canGoOn = monday < thisMonday
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StyledButton(
            onClick = { onMove(-7) },
            style = ToolButtonStyle(diameter = 48.dp),
            modifier = Modifier.alpha(if (canGoBack) 1f else 0.35f).semantics { contentDescription = WeekCopy.EARLIER },
            enabled = canGoBack,
        ) { FoniaIconView(FoniaIcon.back, size = 16f) }
        Column(
            Modifier.weight(1f).clearAndSetSemantics {
                heading()
                contentDescription = "${WeekCopy.heading(monday, thisMonday)}, ${WeekCopy.range(monday, LocalDate.now().year)}"
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FoniaText(WeekCopy.heading(monday, thisMonday), style = fHeadline, color = Palette.ink, textAlign = TextAlign.Center)
            FoniaText(WeekCopy.range(monday, LocalDate.now().year), style = fCaption, color = Palette.inkSoft, textAlign = TextAlign.Center)
        }
        StyledButton(
            onClick = { onMove(7) },
            style = ToolButtonStyle(diameter = 48.dp),
            modifier = Modifier.alpha(if (canGoOn) 1f else 0.35f).semantics { contentDescription = WeekCopy.LATER },
            enabled = canGoOn,
        ) { FoniaIconView(FoniaIcon.forward, size = 16f) }
    }
}

@Composable
private fun SoundSummary(week: WeekReport.SoundWeek, app: AppModel) {
    val sound = SoundID.fromRawValue(week.sound)?.let { app.content.sound(it) }
    val letter = sound?.shortLetter ?: week.sound.uppercase()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            FoniaText(letter, style = practice(26, PracticeWeight.bold, TextRole.title2), color = sound?.family?.field?.deep ?: Palette.ink)
            FoniaText(week.rungAfter.title(letter), Modifier.padding(bottom = 3.dp), style = fBodyStrong)
        }
        FoniaText(StageNames.line(week.rungAfter), style = fCaption, color = Palette.inkSoft)
        FoniaText(WeekCopy.facts(week), style = fCaption, color = Palette.inkSoft)
        WeekCopy.moved(week, letter)?.let { FoniaText(it, style = fCaption, color = Palette.ink) }
    }
}

@Composable
private fun TakeRow(take: WeekReport.Take, isPlaying: Boolean, onToggle: () -> Unit, onMark: (AdultEar) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StyledButton(
            onClick = onToggle,
            style = RoundButtonStyle(diameter = 48.dp),
            modifier = Modifier.semantics { contentDescription = if (isPlaying) WeekCopy.STOP else WeekCopy.listen(take.word) },
        ) { FoniaIconView(if (isPlaying) FoniaIcon.speaker else FoniaIcon.play, size = 18f) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FoniaText(take.word, style = practice(19, PracticeWeight.semibold, TextRole.headline), color = Palette.ink)
                FoniaText(WeekCopy.time(take.attempt.ts), style = fCaption, color = Palette.inkSoft)
                FoniaText(WeekCopy.phone(take.attempt.machineOutcome), style = fCaption, color = Palette.inkSoft)
                WeekCopy.why(take.why)?.let { FoniaText(it, style = fCaption, color = Palette.ink) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (ear in AdultEar.entries) {
                    StyledButton(
                        onClick = { onMark(ear) },
                        style = ChoiceTileStyle(isOn = take.ear == ear, radius = 14.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                            FoniaText(WeekCopy.title(ear), style = ui(15, UIWeight.semibold, TextRole.subheadline), color = Palette.ink, textAlign = TextAlign.Center, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}
