package tech.fonia.features.grownups

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import tech.fonia.features.app.RowLabel
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.MascotPose
import tech.fonia.design.MascotView
import tech.fonia.design.Palette
import tech.fonia.design.PracticeWeight
import tech.fonia.design.ProvideTouchFloor
import tech.fonia.design.RungStrip
import tech.fonia.design.StyledButton
import tech.fonia.design.Surface
import tech.fonia.design.TextRole
import tech.fonia.design.TouchFloor
import tech.fonia.design.fBody
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fCaption
import tech.fonia.design.fLead
import tech.fonia.design.practice
import tech.fonia.domain.Plural
import tech.fonia.domain.Progression
import tech.fonia.domain.Rung
import tech.fonia.domain.SoundDef
import tech.fonia.features.app.AdultGateBody
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.needsAdultGate
import tech.fonia.features.app.ButtonRow
import tech.fonia.features.app.Destination
import tech.fonia.features.app.DetailRow
import tech.fonia.features.app.FoniaSheet
import tech.fonia.features.app.HeaderPage
import tech.fonia.features.app.InsetGroupedList
import tech.fonia.features.app.ListRow
import tech.fonia.features.app.ListSection
import tech.fonia.features.app.ListSectionScope
import tech.fonia.features.app.NavigationRow
import tech.fonia.features.app.SystemColors
import tech.fonia.features.app.ToggleRow
import tech.fonia.features.app.ValueRow
import tech.fonia.features.app.at
import tech.fonia.features.app.field
import tech.fonia.features.app.index
import tech.fonia.features.app.isChild
import tech.fonia.features.app.orderedSounds
import tech.fonia.features.guide.AdultCards
import tech.fonia.features.app.progressionState
import tech.fonia.features.app.shortLetter
import tech.fonia.features.app.title
import tech.fonia.features.home.DayActivity
import tech.fonia.features.home.PathModel
import tech.fonia.features.home.displayName
import tech.fonia.features.home.rememberPushDestination
import tech.fonia.features.journal.dayAndMonth
import tech.fonia.features.progress.play
import tech.fonia.features.setup.ChildSetup
import tech.fonia.features.setup.SoundPicker
import tech.fonia.store.MoodlessNote
import tech.fonia.store.Profile
import tech.fonia.store.Recording
import tech.fonia.store.SoundAgreement
import tech.fonia.store.SoundOutcomeCounts
import tech.fonia.store.SoundProgress
import tech.fonia.store.localDay
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * How it's going, the notes and the settings. A plain settings page: no door, no question.
 */
@Composable
fun ParentsScreen(profile: Profile, app: AppModel, path: PathModel, onShowGrownups: (Boolean) -> Unit) {
    // No grown-ups' door any more (v2-flow, owner 2026-11-24): the one who practises opens his own settings.
    HeaderPage("Setări", closes = true) {
        // With a child on the phone, the settings are the adult's (iOS AdultGate): the check comes first.
        if (app.needsAdultGate) {
            AdultGateBody(app.gate, onPass = {}, Modifier.weight(1f))
        } else {
            // The adult's page: an adult's touch floor, even with a child's profile on the phone.
            ProvideTouchFloor(TouchFloor.adult) { Rooms(profile, app, path, onShowGrownups) }
        }
    }
}

@Composable
private fun Rooms(profile: Profile, app: AppModel, path: PathModel, onShowGrownups: (Boolean) -> Unit) {
    val push = rememberPushDestination()
    val context = LocalContext.current
    InsetGroupedList {
        ListSection(header = profile.name ?: if (profile.isChild) "Copilul" else "Eu", clear = true) {
            row { WeekStrip(path.week) }
        }
        ListSection {
            profileActivityRows(profile, app, context, push)
        }
        ListSection(header = "Reglaje") {
            row {
                DetailRow(
                    "Calibrare pe vocea ta",
                    "Douăzeci de secunde: liniște, apoi „aaa”, „sss”, „șșș”. Telefonul învață camera, vocea și S-ul și Ș-ul tău.",
                    onClick = { app.calibrating = true },
                )
            }
            profileSetupRows(profile, push)
            row { NavigationRow("Cine exersează", onClick = { push(Destination.Who) }) }
            row { ButtonRow("Alte setări", onClick = { onShowGrownups(true) }, color = Palette.ink) }
        }
    }
}

/** The week, Monday first. It shows what was done and never counts a streak to break. */
@Composable
fun WeekStrip(days: List<DayActivity>) {
    Surface(
        Modifier.clearAndSetSemantics {
            contentDescription = "Săptămâna asta"
            stateDescription = Plural.format(days.count { it.hasPractice }, Plural.Noun.Zi)
        },
        padding = 12.dp,
    ) {
        Row(Modifier.fillMaxWidth()) {
            for (day in days) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    FoniaText(day.letter, style = fCaption, color = if (day.isToday) Palette.ink else Palette.inkSoft)
                    Box(
                        Modifier
                            .size(30.dp)
                            .background(if (day.hasPractice) Palette.accent else Palette.progressTrack, CircleShape)
                            .then(if (day.isToday && !day.hasPractice) Modifier.border(2.dp, Palette.ink, CircleShape) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (day.hasPractice) FoniaIconView(FoniaIcon.tick, size = 13f, tint = Palette.buttonLabel)
                    }
                }
            }
        }
    }
}

/** What an adult looks at for one person. */
fun ListSectionScope.profileActivityRows(profile: Profile, app: AppModel, context: Context, push: (Destination) -> Unit) {
    if (profile.isChild) {
        row { DetailRow("Cum merge", "Sunete, încercări, ce a auzit telefonul", onClick = { push(Destination.HowItGoes(profile.id)) }) }
        row { DetailRow(WeekCopy.TITLE, WeekCopy.ROW_DETAIL, onClick = { push(Destination.Week(profile.id)) }) }
        if (app.arguments.drafts) row { DetailRow("Citire", "Un minut de citit cu voce tare. Tu atingi cuvintele, telefonul măsoară timpul.", onClick = { push(Destination.Reading(profile.id)) }) }
    }
    row { NavigationRow(AudienceCopy.notesTitle(profile.isChild), onClick = { push(Destination.Notes(profile.id)) }) }
    row {
        DetailRow(
            AudienceCopy.exportTitle(profile.isChild),
            AudienceCopy.exportDetail(profile.isChild),
            onClick = { app.scope.launch { ProfileExport.share(context, app.repository, profile.id, app.pilot, profile, app.adaptive) } },
            chevron = false,
        )
    }
    if (profile.isChild) row { PracticeReportRow(profile, app, context) }
}

/**
 * The PDF the logoped can read in a minute (iOS `reportRow`). It leaves the phone, so it sits behind the adult check
 * with the rest of this page; with nothing practised yet the row only says so.
 */
@Composable
private fun PracticeReportRow(profile: Profile, app: AppModel, context: Context) {
    var hasPractice by remember(profile.id) { mutableStateOf(false) }
    LaunchedEffect(profile.id) { hasPractice = runCatching { PracticeReportPdf.hasPractice(app.repository, profile.id) }.getOrDefault(false) }
    if (hasPractice) {
        DetailRow(
            "Raportul pentru logoped",
            "Un PDF scurt, fără numele copilului și fără voce.",
            onClick = { app.scope.launch { PracticeReportPdf.share(context, app.repository, app.content, profile.id) } },
            chevron = false,
        )
    } else {
        ListRow(Modifier.alpha(0.6f)) {
            RowLabel("Raportul pentru logoped", "Încă nu e nimic de arătat. Raportul apare după prima zi de exersare.", Modifier.weight(1f))
        }
    }
}

/** What an adult changes for one person. */
fun ListSectionScope.profileSetupRows(profile: Profile, push: (Destination) -> Unit) {
    if (profile.isChild) {
        row { NavigationRow("Ce sunete exersează", onClick = { push(Destination.SoundChoice(profile.id)) }) }
        row { DetailRow(AdultCards.SCREEN_TITLE, AdultCards.SCREEN_DETAIL, onClick = { push(Destination.AdultCards) }) }
        // The adaptive pilot (docs/PLAN.md §7.4): off until an adult switches it on.
        row { NavigationRow("Mod adaptiv (pilot)", onClick = { push(Destination.Adaptive(profile.id)) }) }
    }
    if (profile.isChild || profile.voiceGroup != Profile.VoiceGroup.Child) {
        row {
            DetailRow(
                "Reglează S și Ș pentru vocea asta",
                AudienceCopy.calibrationDetail(profile.isChild),
                onClick = { push(Destination.Calibration(profile.id)) },
            )
        }
    }
}

/** A plain list on the plain page, for the pages only adults open. */
@Composable
private fun AdultPage(title: String, content: @Composable () -> Unit) {
    HeaderPage(title) {
        InsetGroupedList { content() }
    }
}

/** Which sounds a child works on, in which order, and how far they can go alone. */
@Composable
fun SoundChoiceScreen(profileID: UUID, app: AppModel) {
    val profile = app.profiles.firstOrNull { it.id == profileID } ?: return
    // Seeded once: from here on the picker is the source and writes back to the profile.
    var picked by rememberSaveable { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) { if (picked == null) picked = profile.targetSounds }
    AdultPage("Ce sunete exersează") {
        ListSection(footer = "Le exersăm în ordinea în care le alegi. Hotărâți tu și logopedul.") {
            row {
                Box(Modifier.padding(12.dp)) {
                    SoundPicker(app.content, picked ?: profile.targetSounds, onChange = { value ->
                        picked = value
                        val current = app.profiles.firstOrNull { it.id == profileID } ?: return@SoundPicker
                        app.scope.launch { app.save(current.copy(targetSounds = value)) }
                    })
                }
            }
        }
        ListSection {
            row {
                ToggleRow("Ascult eu înainte să treacă la silabe", profile.requireConfirmation) { value ->
                    app.scope.launch { app.save(profile.copy(requireConfirmation = value)) }
                }
            }
            row {
                ToggleRow("Deschide toate lecțiile", profile.openPath) { value ->
                    app.scope.launch { app.save(profile.copy(openPath = value)) }
                }
            }
        }
    }
}

/** What's been done with one child, sound by sound, and the place where an adult says a sound is good. */
@Composable
fun HowItGoesScreen(seed: Profile, app: AppModel) {
    val profileID = seed.id
    // Seeded once, for the totals. The rows below read the profile live from the app.
    val path = remember { PathModel(seed, app.content) }
    var progress by remember { mutableStateOf<List<SoundProgress>>(emptyList()) }
    var agreement by remember { mutableStateOf<List<SoundAgreement>>(emptyList()) }
    var outcomes by remember { mutableStateOf<List<SoundOutcomeCounts>>(emptyList()) }
    var recordings by remember { mutableStateOf<List<Recording>>(emptyList()) }
    LaunchedEffect(Unit) {
        path.reload(app.repository)
        progress = orEmpty { app.repository.soundProgress(profileID) }
    }
    LaunchedEffect(Unit) { app.repository.agreementStream(profileID).collect { agreement = it } }
    LaunchedEffect(Unit) { app.repository.soundOutcomesStream(profileID).collect { outcomes = it } }
    LaunchedEffect(Unit) { app.repository.recordingsStream(profileID).collect { recordings = it } }
    val profile = app.profiles.firstOrNull { it.id == profileID } ?: return
    AdultPage("Cum merge") {
        ListSection(header = "Până acum") {
            row { ValueRow("Zile cu exerciții", "${path.totalDays}") }
            row { ValueRow("Minute", "${path.totalMinutes}") }
            row { ValueRow("Lecții terminate", "${path.totalLessons}") }
            row { ValueRow("Încercări", "${path.totalAttempts}") }
        }
        // The dose counter of the pilot log (03 §1.2 P6): per day, per week, per sound.
        DoseSection(profile, app)
        val active = profile.orderedSounds(app.content).filter { sound ->
            sound.id.rawValue in profile.targetSounds || progress.any { it.sound == sound.id.rawValue }
        }
        if (active.isNotEmpty()) {
            ListSection(
                header = "Sunete",
                footer = "„Se potrivește” arată cât de des ați spus același lucru, tu și telefonul. E pentru logoped, nu e o notă.",
            ) {
                for (sound in active) {
                    row {
                        SoundRow(sound, profile, app, progress, agreement, outcomes) { updated -> progress = updated }
                    }
                }
            }
        }
        if (recordings.isNotEmpty()) {
            ListSection(header = "Înregistrări") {
                for (recording in recordings.take(10)) {
                    row {
                        ValueRow(
                            recording.label,
                            dayAndMonth(recording.ts.atZone(ZoneId.systemDefault()).toLocalDate()),
                            onClick = { app.play(recording) },
                            color = Palette.ink,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SoundRow(
    sound: SoundDef,
    profile: Profile,
    app: AppModel,
    progress: List<SoundProgress>,
    agreement: List<SoundAgreement>,
    outcomes: List<SoundOutcomeCounts>,
    onProgress: (List<SoundProgress>) -> Unit,
) {
    val saved = progress.firstOrNull { it.sound == sound.id.rawValue }
    val rung = Rung.at(saved?.rung ?: 0)
    val counts = outcomes.firstOrNull { it.sound == sound.id.rawValue }
    val match = agreement.firstOrNull { it.sound == sound.id.rawValue }
    val confirmed = sound.id.rawValue in profile.confirmedSounds
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            FoniaText(sound.shortLetter, style = practice(26, PracticeWeight.bold, TextRole.title2), color = sound.family.field.deep)
            FoniaText(rung.title(sound.shortLetter), Modifier.padding(bottom = 3.dp), style = fBodyStrong)
        }
        // The name Romanian speech therapists give to this stage of work on a sound; a label, not a grade.
        FoniaText(StageNames.line(rung), style = fCaption, color = Palette.inkSoft)
        if (rung.stage.isAutomatizare) {
            FoniaText(AdultCards.AUTOMATIZARE_NOTE, style = fCaption, color = Palette.inkSoft)
        }
        RungStrip(total = Rung.entries.size, reached = rung.index + 1, color = Palette.ink, track = Palette.cloud)
        if (counts != null) {
            FoniaText("Bine ${counts.good}, aproape ${counts.almost}, încă nu ${counts.notYet}.", style = fCaption, color = Palette.inkSoft)
        }
        val rate = match?.agreementRate
        if (rate != null) {
            FoniaText("Se potrivește: ${(rate * 100).toInt()}% din ${Plural.format(match.compared, Plural.Noun.Incercare)}.", style = fCaption, color = Palette.inkSoft)
        }
        val state = profile.progressionState(sound.id, saved)
        // Past sentences the phone stays quiet, so moving on is always a person's call.
        if ((profile.requireConfirmation && !confirmed) || rung == Rung.Sentences || rung == Rung.Retell) {
            if (Progression.suggestsLogoped(state)) {
                FoniaText("Sunetul nu iese de câteva ședințe. Întreabă logopedul.", style = fCaption, color = Palette.warmDeep)
            }
            StyledPlainButton("Am ascultat, e bine") {
                val updated = if (confirmed) profile else profile.copy(confirmedSounds = profile.confirmedSounds + sound.id.rawValue)
                val next = Progression.confirmedByAdult(state)
                val savedProgress = (saved ?: SoundProgress(profileId = profile.id, sound = sound.id.rawValue)).copy(rung = next.rung.index)
                app.scope.launch {
                    app.save(updated)
                    try {
                        app.repository.saveSoundProgress(savedProgress)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                    }
                    onProgress(orEmpty { app.repository.soundProgress(profile.id) })
                }
            }
        }
    }
}

/** `Button(title).buttonStyle(.borderless)` in a list row: the tint colour, no row highlight. */
@Composable
private fun StyledPlainButton(title: String, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(top = 2.dp)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        FoniaText(title, style = fBodyStrong, color = Palette.accentText)
    }
}

/** What an adult noticed, kept for the logoped. */
@Composable
fun NotesScreen(profileID: UUID, app: AppModel) {
    var notes by remember { mutableStateOf<List<MoodlessNote>>(emptyList()) }
    var newNote by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { notes = orEmpty { app.repository.notes(profileID) } }
    val isChild = app.profiles.firstOrNull { it.id == profileID }?.isChild ?: true
    AdultPage(AudienceCopy.notesTitle(isChild)) {
        ListSection {
            row {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp)) {
                    BasicTextField(
                        value = newNote,
                        onValueChange = { newNote = it },
                        textStyle = fBody.copy(color = SystemColors.label),
                        cursorBrush = SolidColor(Palette.accentText),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Ce ai observat azi?" },
                        decorationBox = { field ->
                            if (newNote.isEmpty()) FoniaText("Ce ai observat azi?", style = fBody, color = SystemColors.tertiaryLabel)
                            field()
                        },
                    )
                }
            }
            // Always there, so the page says how a note is kept; it waits for some text.
            val canSave = newNote.isNotBlank()
            row {
                ListRow(
                    Modifier.semantics { if (!canSave) disabled() },
                    onClick = if (!canSave) null else ({
                        val note = MoodlessNote(profileId = profileID, localDay = localDay(Instant.now()), text = newNote)
                        newNote = ""
                        app.scope.launch {
                            try {
                                app.repository.saveNote(note)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                            }
                            notes = orEmpty { app.repository.notes(profileID) }
                        }
                    }),
                ) {
                    FoniaText("Salvează", color = if (canSave) Palette.accentText else SystemColors.tertiaryLabel)
                }
            }
        }
        if (notes.isNotEmpty()) {
            ListSection {
                for (note in notes) {
                    row {
                        ListRow {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                FoniaText(note.localDay, style = fCaption, color = Palette.inkSoft)
                                FoniaText(note.text)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Switching to someone else without closing the app, and adding a child. */
@Composable
fun WhoScreen(app: AppModel) {
    var addingChild by remember { mutableStateOf(false) }
    AdultPage("Cine exersează") {
        ListSection {
            for (profile in app.profiles) {
                val isActive = profile.id == app.activeProfileID
                row {
                    ListRow(
                        Modifier.semantics { selected = isActive },
                        onClick = { app.select(profile) },
                    ) {
                        FoniaText(profile.displayName, Modifier.weight(1f), color = Palette.ink)
                        if (isActive) FoniaIconView(FoniaIcon.tick, size = 16f, tint = Palette.ink)
                    }
                }
            }
            row { ButtonRow("Adaugă un copil", onClick = { addingChild = true }) }
        }
    }
    AddChildSheet(app, isPresented = addingChild, onDone = { addingChild = false })
}

/** `.sheet { ChildSetup(app: app, onBack: nil) { … } }`: saves the child and closes; the active profile stays. */
@Composable
internal fun AddChildSheet(app: AppModel, isPresented: Boolean, onDone: () -> Unit) {
    FoniaSheet(isPresented = isPresented, onDismiss = onDone) {
        ChildSetup(app, onBack = null) { profile ->
            app.scope.launch {
                app.save(profile)
                onDone()
            }
        }
    }
}

/** `(try? await …) ?? []`. */
internal suspend fun <T> orEmpty(fetch: suspend () -> List<T>): List<T> = try {
    fetch()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    emptyList()
}
