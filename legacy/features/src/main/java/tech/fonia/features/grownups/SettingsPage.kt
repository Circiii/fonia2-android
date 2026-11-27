package tech.fonia.features.grownups

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.MascotPose
import tech.fonia.design.MascotView
import tech.fonia.design.Palette
import tech.fonia.design.PressableStyle
import tech.fonia.design.Radius
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fCaption
import tech.fonia.design.softShadow
import tech.fonia.design.ui
import tech.fonia.domain.SoundID
import tech.fonia.features.app.ActionRole
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.Destination
import tech.fonia.features.app.DialogAction
import tech.fonia.features.app.FoniaAlert
import tech.fonia.features.app.FoniaConfirmationDialog
import tech.fonia.features.app.FoniaSheet
import tech.fonia.features.app.FullWipe
import tech.fonia.features.app.isChild
import tech.fonia.features.app.rememberAdultGate
import tech.fonia.features.app.rememberStoredBoolean
import tech.fonia.features.app.shortLetter
import tech.fonia.features.guide.AdultCards
import tech.fonia.features.guide.AdultCardsSeen
import tech.fonia.features.home.DayActivity
import tech.fonia.features.home.rememberPushDestination
import tech.fonia.features.speaking.SpeakingMemory
import tech.fonia.store.Profile
import tech.fonia.features.home.DailyRounds
import tech.fonia.features.session.FrontTeeth

/**
 * Everything an adult can look at or change (iOS `SettingsPage`), on one page and in the order they need it: who this
 * is, what to show the speech therapist, how the practice is set up, then the app itself. What can't be undone stands
 * at the bottom. [profile] is null when nobody is chosen yet, and then the page lists the profiles instead. [week] is
 * Android's week strip under the who card. [onClose] leaves the page after a wipe or a deleted profile.
 */
@Composable
fun SettingsPage(profile: Profile?, app: AppModel, onClose: () -> Unit, modifier: Modifier = Modifier, week: List<DayActivity>? = null) {
    val push = rememberPushDestination()
    val context = LocalContext.current
    val guard = rememberAdultGate(app)
    var showPrivacy by remember { mutableStateOf(false) }
    var addingChild by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    /** What stayed on the phone after "Șterge tot" (null: no failure to show). */
    var wipeLeft by remember { mutableStateOf<List<String>?>(null) }
    var isWiping by remember { mutableStateOf(false) }

    /** Everything goes, or the adult hears what didn't. Once it worked the app is back at its first run. */
    fun wipe() {
        if (isWiping) return
        isWiping = true
        app.scope.launch {
            val left = try {
                app.wipeEverything()
            } finally {
                isWiping = false
            }
            if (left.isEmpty()) onClose() else wipeLeft = left
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 620.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            if (profile != null) {
                Column(Modifier.arriving(0), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    WhoCard(profile, app) { push(Destination.Who) }
                    if (week != null) WeekStrip(week)
                }
                if (profile.isChild) LogopedTiles(profile, app, Modifier.arriving(1), push, guard)
                Activity(profile, app, Modifier.arriving(2), push, guard)
                Practice(profile, app, Modifier.arriving(3), push)
            } else {
                Profiles(app, Modifier.arriving(0), onOpen = { push(Destination.ProfileDetail(it.id)) }, onAdd = { addingChild = true })
            }
            AppGroup(app, Modifier.arriving(4))
            MoreGroup(app, Modifier.arriving(5))
            SettingsGroup("Datele tale", SettingsTint.Blue, Modifier.arriving(6)) {
                SettingsRow(FoniaIcon.lock.rawValue, "Ce păstrăm pe telefon", chevron = true) { showPrivacy = true }
                RowDivider()
                InstallCountRow(app)
                RowDivider()
                SettingsRow(FoniaIcon.book.rawValue, "Licențe", "Fonturile, desenele și codul pe care e făcută Fonia", chevron = true) { push(Destination.Licences) }
                RowDivider()
                SettingsRow(FoniaIcon.help.rawValue, "Ajutor", "Se deschide în browser", chevron = true) {
                    guard { LegalInfo.open(context, LegalInfo.SUPPORT_URL) }
                }
            }
            SettingsGroup("Ștergere", SettingsTint.Danger, Modifier.arriving(7)) {
                if (profile != null) {
                    SettingsRow(SettingsIcons.TRASH, "Șterge profilul ${settingsName(profile)}", destructive = true) { confirmDelete = true }
                    RowDivider()
                }
                SettingsRow(SettingsIcons.TRASH, "Șterge tot de pe telefon", destructive = true, enabled = !isWiping) {
                    guard { confirmWipe = true }
                }
            }
            Footer(LegalInfo.version(context))
        }
    }

    AddChildSheet(app, isPresented = addingChild, onDone = { addingChild = false })
    FoniaSheet(isPresented = showPrivacy, onDismiss = { showPrivacy = false }) {
        PrivacyNote(onDone = { showPrivacy = false }, withReading = app.arguments.drafts, onOpenLink = { LegalInfo.open(context, it) })
    }
    FoniaConfirmationDialog(
        isPresented = confirmWipe,
        onDismiss = { confirmWipe = false },
        title = "Ștergi tot?",
        message = "Profilurile, încercările, înregistrările, jurnalul și alegerile din Setări dispar de pe telefon. Nu se pot recupera.",
        actions = listOf(
            DialogAction("Șterge tot", ActionRole.Destructive) { wipe() },
            DialogAction("Anulează", ActionRole.Cancel),
        ),
    )
    FoniaAlert(
        isPresented = wipeLeft != null,
        onDismiss = { wipeLeft = null },
        title = "Nu am putut șterge tot",
        message = wipeLeft?.let { FullWipe.leftLine(it) },
        actions = listOf(
            DialogAction("Încearcă din nou") {
                wipeLeft = null
                wipe()
            },
            DialogAction("Anulează", ActionRole.Cancel),
        ),
    )
    if (profile != null) {
        FoniaConfirmationDialog(
            isPresented = confirmDelete,
            onDismiss = { confirmDelete = false },
            title = "Ștergi profilul?",
            message = "Dispar și încercările și înregistrările acestui profil. Nu se pot recupera.",
            actions = listOf(
                DialogAction("Șterge profilul", ActionRole.Destructive) {
                    val profileID = profile.id
                    app.scope.launch {
                        try {
                            app.repository.deleteProfile(profileID)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                        }
                        AdultCardsSeen.forget(profileID, app.preferences)
                        SpeakingMemory.forget(profileID, app.preferences)
                        DailyRounds.forget(profileID, app.preferences)
                        FrontTeeth.forget(profileID, app.preferences)
                        // And every row of the pilot log about this profile.
                        try {
                            app.pilot?.deleteProfile(profileID)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                        }
                        onClose()
                    }
                },
                DialogAction("Anulează", ActionRole.Cancel),
            ),
        )
    }
}

/** iOS `displayName` on this page: the name, or what the profile is. */
internal fun settingsName(profile: Profile): String = profile.name ?: if (profile.isChild) "Copil" else "Adult"

/** The line under the name: what this person works on. */
internal fun settingsSummary(profile: Profile, app: AppModel): String {
    if (!profile.isChild) {
        return when (profile.track) {
            Profile.Track.En -> "Adult, lucrează la engleză"
            Profile.Track.Dictie -> "Adult, lucrează la vorbitul în public"
            Profile.Track.Ro -> "Adult, lucrează la sunetele românei"
        }
    }
    val letters = profile.targetSounds.mapNotNull { SoundID.fromRawValue(it) }.map { app.content.sound(it).shortLetter }
    val age = "Copil, ${profile.ageBand.rawValue} ani"
    return if (letters.isEmpty()) age else "$age, exersează ${letters.joinToString(", ")}"
}

// MARK: Who

/** Who this is, as the head of the page: the mascot on a soft green field, the name large. */
@Composable
private fun WhoCard(profile: Profile, app: AppModel, onOpen: () -> Unit) {
    val shape = ContinuousRoundedRectangle(Radius.card + 4.dp)
    StyledButton(onClick = onOpen, style = PressableStyle(), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Palette.sage, shape)
                .border(1.dp, Palette.accentSoftEdge.copy(alpha = 0.6f), shape)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(72.dp).background(Palette.sheet.copy(alpha = 0.7f), CircleShape).clearAndSetSemantics {}) {
                MascotView(MascotPose.easy, Modifier.size(72.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                FoniaText(
                    "Cine exersează".uppercase(),
                    // Read as written, not letter by letter.
                    Modifier.clearAndSetSemantics { text = AnnotatedString("Cine exersează") },
                    style = ui(13, UIWeight.bold, TextRole.caption),
                    color = Palette.accentText,
                    tracking = 0.6.sp,
                )
                FoniaText(settingsName(profile), style = ui(24, UIWeight.bold, TextRole.title2), color = Palette.ink, maxLines = 1, minimumScaleFactor = 0.8f)
                FoniaText(settingsSummary(profile, app), style = fCaption, color = Palette.inkSoft)
            }
            Box(Modifier.size(32.dp).background(Palette.sheet, CircleShape), contentAlignment = Alignment.Center) {
                FoniaIconView(FoniaIcon.forward, size = 14f, tint = Palette.accentText)
            }
        }
    }
}

// MARK: For the speech therapist

/** The four things an adult opens most, as big tiles before any list. */
@Composable
private fun LogopedTiles(profile: Profile, app: AppModel, modifier: Modifier, push: (Destination) -> Unit, guard: (() -> Unit) -> Unit) {
    val context = LocalContext.current
    var hasPractice by remember(profile.id) { mutableStateOf(false) }
    LaunchedEffect(profile.id) { hasPractice = runCatching { PracticeReportPdf.hasPractice(app.repository, profile.id) }.getOrDefault(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GroupTitle("Pentru logoped")
        TileRow {
            SettingsTile(SettingsIcons.CHART, SettingsTint.Green, "Cum merge", "Sunete și încercări", Modifier.weight(1f)) { push(Destination.HowItGoes(profile.id)) }
            SettingsTile(FoniaIcon.listenBack.rawValue, SettingsTint.Blue, WeekCopy.TITLE, "Ce s-a lucrat, de ascultat", Modifier.weight(1f)) { push(Destination.Week(profile.id)) }
        }
        TileRow {
            SettingsTile(SettingsIcons.NOTE, SettingsTint.Gold, AudienceCopy.notesTitle(true), "Ce ai observat", Modifier.weight(1f)) { push(Destination.Notes(profile.id)) }
            // The PDF leaves the phone, so it waits for the adult check like the export; with nothing practised yet the
            // tile only says so.
            if (hasPractice) {
                SettingsTile(SettingsIcons.SHARE, SettingsTint.Rose, "Raportul pentru logoped", "PDF, fără nume și fără voce", Modifier.weight(1f)) {
                    guard { app.scope.launch { PracticeReportPdf.share(context, app.repository, app.content, profile.id, app.probes) } }
                }
            } else {
                SettingsTile(SettingsIcons.SHARE, SettingsTint.Rose, "Raportul pentru logoped", "După prima zi de exersare", Modifier.weight(1f).alpha(0.6f), onClick = null)
            }
        }
    }
}

@Composable
private fun TileRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/** A big tile for a page an adult opens often: the icon on its colour, the name, a few words. */
@Composable
private fun SettingsTile(icon: String, tint: SettingsTint, title: String, detail: String, modifier: Modifier, onClick: (() -> Unit)?) {
    val shape = ContinuousRoundedRectangle(Radius.card)
    val body: @Composable () -> Unit = {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .heightIn(min = 126.dp)
                .softShadow(shape)
                .background(Palette.sheet, shape)
                .clip(shape)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(46.dp).background(tint.soft, ContinuousRoundedRectangle(13.dp)), contentAlignment = Alignment.Center) {
                SettingsIcon(icon, 24f, tint.ink)
            }
            Spacer(Modifier.weight(1f))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FoniaText(title, style = fBodyStrong, color = Palette.ink, maxLines = 2, minimumScaleFactor = 0.85f)
                FoniaText(detail, style = fCaption, color = Palette.inkSoft, maxLines = 2)
            }
        }
    }
    if (onClick != null) {
        StyledButton(onClick = onClick, style = PressableStyle(), modifier = modifier.fillMaxHeight()) { body() }
    } else {
        Box(modifier.fillMaxHeight().semantics(mergeDescendants = true) { disabled() }) { body() }
    }
}

@Composable
private fun Profiles(app: AppModel, modifier: Modifier, onOpen: (Profile) -> Unit, onAdd: () -> Unit) {
    SettingsGroup("Cine exersează", SettingsTint.Green, modifier) {
        app.profiles.forEachIndexed { index, item ->
            if (index > 0) RowDivider()
            SettingsRow(FoniaIcon.people.rawValue, settingsName(item), settingsSummary(item, app), chevron = true) { onOpen(item) }
        }
        RowDivider()
        SettingsRow(FoniaIcon.plus.rawValue, "Adaugă un copil", onClick = onAdd)
    }
}

// MARK: What was done

@Composable
private fun Activity(profile: Profile, app: AppModel, modifier: Modifier, push: (Destination) -> Unit, guard: (() -> Unit) -> Unit) {
    val context = LocalContext.current
    // The file leaves the phone, so it waits for the adult check.
    val export: @Composable () -> Unit = {
        SettingsRow(SettingsIcons.SHARE, AudienceCopy.exportTitle(profile.isChild), AudienceCopy.exportDetail(profile.isChild)) {
            guard { app.scope.launch { ProfileExport.share(context, app.repository, profile.id, app.pilot, profile, app.adaptive, app.probes) } }
        }
    }
    if (profile.isChild) {
        SettingsGroup("Mai mult pentru logoped", SettingsTint.Rose, modifier) {
            export()
            if (app.arguments.drafts) {
                RowDivider()
                SettingsRow(
                    FoniaIcon.book.rawValue,
                    "Citire",
                    "Un minut de citit cu voce tare. Tu atingi cuvintele, telefonul măsoară timpul.",
                    chevron = true,
                ) { push(Destination.Reading(profile.id)) }
            }
        }
    } else {
        SettingsGroup("Ce ai lucrat", SettingsTint.Green, modifier) {
            SettingsRow(SettingsIcons.NOTE, AudienceCopy.notesTitle(false), "Ce ai observat între întâlnirile cu logopedul", chevron = true) {
                push(Destination.Notes(profile.id))
            }
            RowDivider()
            export()
        }
    }
}

// MARK: How the practice is set up

@Composable
private fun Practice(profile: Profile, app: AppModel, modifier: Modifier, push: (Destination) -> Unit) {
    val tunesVoice = profile.isChild || profile.voiceGroup != Profile.VoiceGroup.Child
    // The room check calibrates the one who practises now.
    val checksRoom = profile.id == app.activeProfileID
    if (!profile.isChild && !tunesVoice && !checksRoom) return
    SettingsGroup("Exersarea", SettingsTint.Blue, modifier) {
        var first = true
        val divider: @Composable () -> Unit = { if (!first) RowDivider(); first = false }
        if (profile.isChild) {
            divider()
            SettingsRow(FoniaIcon.mouth.rawValue, "Ce sunete exersează", "Ordinea lor și cum avansează", chevron = true) { push(Destination.SoundChoice(profile.id)) }
            divider()
            SettingsRow(FoniaIcon.book.rawValue, AdultCards.SCREEN_TITLE, AdultCards.SCREEN_DETAIL, chevron = true) { push(Destination.AdultCards) }
            // Two of a child's own switches, kept per child (iOS `ChildDayRows`): a second short round on the same day
            // for the youngest, and the front teeth being out.
            if (DailyRounds.isOffered(profile)) {
                divider()
                var roundsOn by rememberStoredBoolean(app.preferences, DailyRounds.key(profile.id))
                SwitchRow(FoniaIcon.hourglass.rawValue, DailyRounds.TITLE, roundsOn, DailyRounds.DETAIL) { roundsOn = it }
            }
            if (FrontTeeth.isOffered(profile)) {
                divider()
                var teethOut by rememberStoredBoolean(app.preferences, FrontTeeth.key(profile.id))
                SwitchRow(FoniaIcon.mouth.rawValue, FrontTeeth.TITLE, teethOut, FrontTeeth.DETAIL) { teethOut = it }
            }
            divider()
            // The adaptive pilot (docs/PLAN.md §7.4): off until an adult switches it on.
            SettingsRow(FoniaIcon.target.rawValue, "Mod adaptiv (pilot)", chevron = true) { push(Destination.Adaptive(profile.id)) }
        }
        if (tunesVoice) {
            divider()
            SettingsRow(FoniaIcon.waves.rawValue, "Reglează S și Ș pentru vocea asta", AudienceCopy.calibrationDetail(profile.isChild), chevron = true) {
                push(Destination.Calibration(profile.id))
            }
        }
        if (checksRoom) {
            divider()
            SettingsRow(
                FoniaIcon.waves.rawValue,
                "Calibrare pe vocea ta",
                "Douăzeci de secunde: liniște, apoi „aaa”, „sss”, „șșș”. Telefonul învață camera, vocea și S-ul și Ș-ul tău.",
                chevron = true,
            ) { app.calibrating = true }
        }
    }
}
