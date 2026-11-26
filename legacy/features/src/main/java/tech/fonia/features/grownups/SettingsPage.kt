package tech.fonia.features.grownups

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.LocalReduceMotion
import tech.fonia.design.MascotPose
import tech.fonia.design.MascotView
import tech.fonia.design.MiniPillStyle
import tech.fonia.design.Palette
import tech.fonia.design.PressableStyle
import tech.fonia.design.Radius
import tech.fonia.design.StyledButton
import tech.fonia.design.TemplateImage
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fCaption
import tech.fonia.design.softShadow
import tech.fonia.design.swiftSpring
import tech.fonia.design.ui
import tech.fonia.domain.SoundID
import tech.fonia.features.IS_DEBUG_BUILD
import tech.fonia.features.app.ActionRole
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.InstallCount
import tech.fonia.features.app.Destination
import tech.fonia.features.app.DialogAction
import tech.fonia.features.app.FoniaAlert
import tech.fonia.features.app.FoniaConfirmationDialog
import tech.fonia.features.app.FoniaSheet
import tech.fonia.features.app.FoniaSwitch
import tech.fonia.features.app.FullWipe
import tech.fonia.features.app.SpeechHub
import tech.fonia.features.app.isChild
import tech.fonia.features.app.rememberAdultGate
import tech.fonia.features.app.rememberStoredBoolean
import tech.fonia.features.app.shortLetter
import tech.fonia.features.guide.AdultCards
import tech.fonia.features.guide.AdultCardsSeen
import tech.fonia.features.home.DayActivity
import tech.fonia.features.home.rememberPushDestination
import tech.fonia.features.ml.ModelCatalog
import tech.fonia.features.ml.ModelEntry
import tech.fonia.features.ml.ModelsHub
import tech.fonia.features.session.Settings
import tech.fonia.features.session.ShadowBadgeSettings
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

// MARK: The app

/** Once the code has gone the switch has nothing left to stop, and the line says so. */
@Composable
private fun InstallCountRow(app: AppModel) {
    var countInstall by rememberStoredBoolean(app.preferences, InstallCount.ON_KEY, InstallCount.DEFAULT_ON)
    val installSent by rememberStoredBoolean(app.preferences, InstallCount.SENT_KEY)
    SwitchRow(
        FoniaIcon.chart.rawValue,
        "Numără instalarea",
        countInstall,
        if (installSent) {
            "Codul a plecat. Nu se mai trimite altul, decât după „Șterge tot”."
        } else {
            "La o zi după primul profil, trimite o dată un cod făcut la întâmplare, ca să știm câte instalări sunt. Fără nume și fără nimic din exerciții."
        },
    ) { countInstall = it }
}

@Composable
private fun AppGroup(app: AppModel, modifier: Modifier) {
    var keepRecordings by rememberStoredBoolean(app.preferences, Settings.keepRecordings)
    var effectsOff by rememberStoredBoolean(app.preferences, Settings.effectsOff)
    SettingsGroup("Aplicația", SettingsTint.Gold, modifier) {
        SwitchRow(FoniaIcon.speaker.rawValue, "Sunete în aplicație", !effectsOff) { on ->
            effectsOff = !on
            app.audio.effectsMuted = !on
        }
        RowDivider()
        SwitchRow(
            FoniaIcon.microphone.rawValue,
            "Păstrează înregistrările copiilor",
            keepRecordings,
            "Dacă o lași oprită, telefonul ascultă și uită. Dacă o pornești, păstrăm ultimele 3 înregistrări la fiecare sunet, cel mult 90 de zile.",
        ) { keepRecordings = it }
        RowDivider()
        SpeechModelRow(app.speech)
        if (app.preferences != null) {
            RowDivider()
            ReminderRow(app)
        }
    }
}

/** The speech package (Android: it comes as a download): what it's for, and its download or delete. */
@Composable
private fun SpeechModelRow(speech: SpeechHub) {
    ActionRow(FoniaIcon.waves.rawValue, "Recunoașterea vorbirii", "Fără ea, telefonul nu înțelege engleza și propozițiile.") {
        when (val state = speech.state) {
            SpeechHub.State.Ready -> Pill("Șterge pachetul (${speech.megabytes} MB)") { speech.remove() }
            is SpeechHub.State.Downloading -> DownloadBar(state.fraction)
            SpeechHub.State.Absent, SpeechHub.State.Failed -> Pill("Descarcă pachetul (${speech.megabytes} MB)") { speech.download() }
        }
    }
}

// MARK: The study and the experiments

/**
 * The trial's own switches and the experiments, folded away until an adult opens them. All of them start off, and none
 * changes what a child sees or hears. The group stays in release builds, because the pilot runs on them.
 */
@Composable
private fun MoreGroup(app: AppModel, modifier: Modifier) {
    var showsMore by remember { mutableStateOf(false) }
    var faceGuides by rememberStoredBoolean(app.preferences, Settings.faceGuides)
    var cameraCoach by rememberStoredBoolean(app.preferences, Settings.cameraCoach)
    val reduceMotion = LocalReduceMotion.current
    // How many of the folded switches are on, so nothing turned on stays out of sight unnoticed.
    val on = listOf(app.isPilotLogOn, faceGuides, cameraCoach).count { it }
    val summary = when (on) {
        0 -> "Toate sunt oprite"
        1 -> "Una e pornită"
        else -> "$on sunt pornite"
    }
    val turn by animateFloatAsState(if (showsMore) 180f else 0f, swiftSpring(0.4, 0.15), label = "moreChevron")
    val shape = ContinuousRoundedRectangle(Radius.card)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StyledButton(
            onClick = { showsMore = !showsMore },
            style = PressableStyle(),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { stateDescription = if (showsMore) "Deschis" else "Închis" },
            onClickLabel = "Opțiunile pentru studiul cu logopezii și cele experimentale",
        ) {
            Row(
                Modifier.fillMaxWidth().background(Palette.sheet, shape).padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompositionLocalProvider(LocalSettingsTint provides SettingsTint.Grey) { SettingsIconTile(FoniaIcon.target.rawValue) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    FoniaText("Studiu și funcții noi", style = fBodyStrong, color = Palette.ink)
                    FoniaText(summary, style = fCaption, color = Palette.inkSoft)
                }
                FoniaIconView(FoniaIcon.down, Modifier.rotate(turn), size = 13f, tint = Palette.inkSoft)
            }
        }
        AnimatedVisibility(
            visible = showsMore,
            enter = if (reduceMotion) fadeIn() else fadeIn() + expandVertically(swiftSpring(0.4, 0.15)) + slideInVertically(swiftSpring(0.4, 0.15)) { -it / 6 },
            exit = if (reduceMotion) fadeOut() else fadeOut(tween(150)) + shrinkVertically(swiftSpring(0.4, 0.15)),
        ) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                PilotGroup(app)
                SettingsGroup("Experimental", SettingsTint.Grey) {
                    SwitchRow(FoniaIcon.mirror.rawValue, "Ghidaje pe față în oglindă", faceGuides, "Folosește camera din față. Nimic nu pleacă de pe telefon.") { faceGuides = it }
                    RowDivider()
                    SwitchRow(
                        FoniaIcon.loud.rawValue,
                        "Vorbit în public în fața camerei",
                        cameraCoach,
                        "Un pas nou la „Vorbit în public”: te vezi pe ecran și primești semne pe loc. Folosește camera din față. Nimic nu pleacă de pe telefon.",
                    ) { cameraCoach = it }
                }
            }
        }
    }
}

/**
 * The trial's switches: the practice log, the phone scorer of shadow mode with its size before anything is fetched and
 * a delete, and in debug builds the switch for the agreement badge in lessons.
 */
@Composable
private fun PilotGroup(app: AppModel) {
    SettingsGroup("Pentru studiul cu logopezii", SettingsTint.Grey) {
        SwitchRow(
            SettingsIcons.NOTE,
            "Jurnalul de exersare",
            app.isPilotLogOn,
            "Notează pentru logoped câte încercări au fost în fiecare zi, doar ca numere. Fără voce. Pornit doar dacă îl pornești tu.",
        ) { app.setPilotLog(it) }
        val ml = app.ml
        if (ml != null) {
            val entry = ModelCatalog.ZIPA
            val state = ml.models.state(entry)
            // The shipped app has no internet permission (app/src/main/AndroidManifest.xml), so it can't fetch the
            // package: the row would only fail. It shows where the permission is there (a build that asks for it).
            val context = LocalContext.current
            val canFetch = remember { context.checkSelfPermission(android.Manifest.permission.INTERNET) == android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (canFetch || state != ModelsHub.State.Absent) {
                val detail = buildString {
                    append("Un al doilea fel de a asculta, tot pe telefon. Notează ce a auzit doar în jurnalul pentru logoped, fără voce. Copilul vede și aude la fel. Se descarcă doar pe Wi-Fi.")
                    if (!app.isPilotLogOn) append(" Notează doar cu jurnalul de exersare pornit.")
                    if (state is ModelsHub.State.Failed && state.noNetwork) append(" Acum nu e Wi-Fi.")
                }
                RowDivider()
                ActionRow(FoniaIcon.download.rawValue, "Ascultare pe sunete, în probă", detail) { ShadowModelAction(ml.models, entry) }
            }
            if (IS_DEBUG_BUILD) {
                var badge by rememberStoredBoolean(app.preferences, ShadowBadgeSettings.KEY)
                RowDivider()
                SwitchRow(
                    FoniaIcon.check.rawValue,
                    "Acordul cu ZIPA în lecție",
                    badge,
                    "Doar în varianta de test. În lecție apar, sus, verdictul ZIPA lângă al telefonului și acordul dintre ele.",
                ) { badge = it }
            }
        }
    }
}

@Composable
private fun ShadowModelAction(models: ModelsHub, entry: ModelEntry) {
    if (!models.isSupported) {
        FoniaText("Nu merge pe acest telefon", style = fCaption, color = Palette.inkSoft)
        return
    }
    when (val state = models.state(entry)) {
        ModelsHub.State.Ready -> Pill("Șterge pachetul (${entry.megabytes} MB)") { models.remove(entry) }
        is ModelsHub.State.Downloading -> DownloadBar(state.fraction)
        ModelsHub.State.Absent, is ModelsHub.State.Failed -> Pill("Descarcă pachetul (${entry.megabytes} MB)") { models.download(entry) }
    }
}

// MARK: The footer

@Composable
private fun Footer(version: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(36.dp).alpha(0.8f).clearAndSetSemantics {}) { MascotView(MascotPose.easy, Modifier.size(36.dp)) }
        FoniaText("Fonia $version", style = fCaption, color = Palette.inkSoft)
        FoniaText("Totul rămâne pe telefon.", style = fCaption, color = Palette.inkSoft)
    }
}

// MARK: The pieces

/** The colour of a group's icons, so each part of the page is told apart at a glance (iOS `SettingsTint`). */
internal enum class SettingsTint(val ink: Color, val soft: Color) {
    Green(Palette.accentText, Palette.accentSoft),
    Blue(Palette.cold, Palette.coldSoft),
    Gold(Palette.tipDeep, Palette.tipSoft),
    Rose(Palette.warm, Palette.warmSoft),
    Grey(Palette.notYet, Palette.notYetSoft),
    Danger(Palette.danger, Palette.dangerSoft),
}

/**
 * The icons iOS added for this page (`FoniaIcon.chart`, `.note`, `.share`, `.trash`), by their file in `images/icons`.
 * They stay here until `FoniaIcon` has them.
 */
internal object SettingsIcons {
    const val CHART = "chart-line-up-duotone"
    const val NOTE = "note-pencil-duotone"
    const val SHARE = "export-duotone"
    const val TRASH = "trash-duotone"
}

/** `FoniaIconView` by file name: grows with the text, no semantics of its own. */
@Composable
internal fun SettingsIcon(name: String, size: Float, tint: Color, modifier: Modifier = Modifier) {
    val side = size * LocalDensity.current.fontScale
    TemplateImage(name, modifier.size(side.dp), tint)
}

private val LocalSettingsTint = compositionLocalOf { SettingsTint.Green }

/** A group's name over its card. */
@Composable
private fun GroupTitle(title: String) {
    FoniaText(title, Modifier.padding(start = 6.dp).semantics { heading() }, style = ui(14, UIWeight.bold, TextRole.footnote), color = Palette.inkSoft)
}

/** A title over one white card of rows. The danger group has a red edge. */
@Composable
private fun SettingsGroup(title: String, tint: SettingsTint, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = ContinuousRoundedRectangle(Radius.card)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GroupTitle(title)
        CompositionLocalProvider(LocalSettingsTint provides tint) {
            Column(
                Modifier
                    .fillMaxWidth()
                    // The shadow sits under the card's fill, outside the clip.
                    .softShadow(shape)
                    .background(Palette.sheet, shape)
                    .then(if (tint == SettingsTint.Danger) Modifier.border(1.dp, Palette.danger.copy(alpha = 0.25f), shape) else Modifier)
                    .clip(shape),
                content = content,
            )
        }
    }
}

/** The icon of a row, on its soft tile. [pulse] rings it once in the accent when it turns true. */
@Composable
private fun SettingsIconTile(icon: String, destructive: Boolean = false, pulse: Boolean = false) {
    val tint = if (destructive) SettingsTint.Danger else LocalSettingsTint.current
    val reduceMotion = LocalReduceMotion.current
    val ring = remember { Animatable(1f) }
    var last by remember { mutableStateOf(pulse) }
    LaunchedEffect(pulse) {
        val turnedOn = pulse && !last
        last = pulse
        if (!turnedOn || reduceMotion) return@LaunchedEffect
        ring.snapTo(0f)
        ring.animateTo(1f, tween(700))
    }
    val accent = Palette.accent
    Box(
        Modifier
            .size(38.dp)
            .drawWithContent {
                drawContent()
                val t = ring.value
                if (t < 1f) {
                    // Pow's pulse: a ring that grows out of the tile and fades.
                    val grow = 10.dp.toPx() * t
                    val radius = 11.dp.toPx() + grow
                    drawRoundRect(
                        accent.copy(alpha = (1f - t) * 0.9f),
                        topLeft = Offset(-grow, -grow),
                        size = Size(size.width + 2 * grow, size.height + 2 * grow),
                        cornerRadius = CornerRadius(radius),
                        style = Stroke(2.dp.toPx()),
                    )
                }
            }
            .background(tint.soft, ContinuousRoundedRectangle(11.dp)),
        contentAlignment = Alignment.Center,
    ) {
        SettingsIcon(icon, 21f, tint.ink)
    }
}

/** An icon on a soft tile, a name, and a line saying what's inside; top-aligned so a long line keeps the tile by the name. */
@Composable
private fun SettingsRowLabel(icon: String, title: String, detail: String?, destructive: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        SettingsIconTile(icon, destructive)
        Column(Modifier.weight(1f).heightIn(min = 38.dp), verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
            FoniaText(title, style = fBodyStrong, color = if (destructive) Palette.danger else Palette.ink)
            if (detail != null) FoniaText(detail, style = fCaption, color = Palette.inkSoft)
        }
    }
}

/** A row that opens something or does it. It lights up under the finger, the way a system list does. */
@Composable
private fun SettingsRow(
    icon: String,
    title: String,
    detail: String? = null,
    chevron: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (pressed) Palette.cloud else Color.Transparent)
            .clickable(interactionSource = interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsRowLabel(icon, title, detail, destructive, Modifier.weight(1f))
        if (chevron) FoniaIconView(FoniaIcon.forward, size = 13f, tint = Palette.inkSoft)
    }
}

/** A row that explains something, with its action under the words (iOS `soundsModelRow`). */
@Composable
private fun ActionRow(icon: String, title: String, detail: String, action: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SettingsRowLabel(icon, title, detail, destructive = false, modifier = Modifier.semantics(mergeDescendants = true) {})
        Box(Modifier.padding(start = 50.dp)) { action() }
    }
}

/**
 * A switch on the line of its name, with what it does underneath: beside the switch, a long explanation would be
 * squeezed into a narrow column. The icon rings once when it is turned on.
 */
@Composable
internal fun SwitchRow(
    icon: String,
    title: String,
    checked: Boolean,
    detail: String? = null,
    /** False when the row sits inside a padded block of its own (the reminder). */
    inset: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().then(if (inset) Modifier.padding(horizontal = 14.dp, vertical = 11.dp) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SettingsIconTile(icon, pulse = checked)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // The switch says the name itself; the line underneath is read on its own.
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 38.dp)
                    .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FoniaText(title, Modifier.weight(1f), style = fBodyStrong, color = Palette.ink)
                FoniaSwitch(checked)
            }
            if (detail != null) FoniaText(detail, style = fCaption, color = Palette.inkSoft)
        }
    }
}

/** The rule between two rows, from under the text. */
@Composable
internal fun RowDivider() {
    Box(Modifier.padding(start = 64.dp).fillMaxWidth().height(1.dp).background(Palette.cloud))
}

/** A small action under a row's words. */
@Composable
internal fun Pill(title: String, onClick: () -> Unit) {
    StyledButton(onClick = onClick, style = MiniPillStyle(), modifier = Modifier.heightIn(min = 44.dp)) { FoniaText(title) }
}

/** `ProgressView(value:)` under "Se descarcă…". */
@Composable
private fun DownloadBar(fraction: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FoniaText("Se descarcă…", style = fCaption, color = Palette.inkSoft)
        val track = Palette.progressTrack
        val fill = Palette.accent
        Canvas(
            Modifier
                .widthIn(max = 240.dp)
                .fillMaxWidth()
                .height(4.dp)
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction.toFloat().coerceIn(0f, 1f), 0f..1f) },
        ) {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = radius)
            drawRoundRect(fill, size = Size(size.width * fraction.toFloat().coerceIn(0f, 1f), size.height), cornerRadius = radius)
        }
    }
}

/**
 * Each part of the page settles into place a beat after the one above it: it fades in and comes up 14 dp on a soft
 * spring, 40 ms after the part above. With Reduce Motion it is simply there.
 */
@Composable
internal fun Modifier.arriving(order: Int, step: Long = 40, duration: Double = 0.45, bounce: Double = 0.12): Modifier {
    val reduceMotion = LocalReduceMotion.current
    val progress = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (progress.value >= 1f) return@LaunchedEffect
        delay(order * step)
        progress.animateTo(1f, swiftSpring(duration, bounce))
    }
    return graphicsLayer {
        alpha = progress.value.coerceIn(0f, 1f)
        translationY = (1f - progress.value) * 14.dp.toPx()
    }
}
