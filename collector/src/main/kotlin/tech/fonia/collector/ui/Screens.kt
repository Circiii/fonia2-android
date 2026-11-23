package tech.fonia.collector.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import tech.fonia.collector.CollectorModel
import tech.fonia.collector.Screen
import tech.fonia.collector.TakePhase
import tech.fonia.collector.core.Level
import tech.fonia.collector.core.Levels
import tech.fonia.collector.core.Naming
import tech.fonia.collector.core.PromptCatalog
import tech.fonia.collector.core.Role
import tech.fonia.collector.deviceTypeName
import java.util.Locale

@Composable
fun CollectorApp(vm: CollectorModel) {
    BackHandler(enabled = vm.screen != Screen.Home) { vm.back() }
    when (vm.screen) {
        Screen.Home -> HomeScreen(vm)
        Screen.Consent -> ConsentScreen(vm)
        Screen.Speaker -> SpeakerScreen(vm)
        Screen.Room -> RoomScreen(vm)
        Screen.Record -> RecordScreen(vm)
        Screen.Done -> DoneScreen(vm)
        Screen.Export -> ExportScreen(vm)
    }
}

private fun db(value: Float): String = String.format(Locale.ROOT, "%.0f", value).replace("-", "−")

private fun packLine(vm: CollectorModel, packId: String, role: Role): String {
    val pack = vm.catalog.pack(packId) ?: return packId
    val letters = pack.sounds.joinToString(", ") { s -> vm.catalog.sound(s)?.letter ?: s }
    val n = vm.planSize(packId, role)
    return "$letters · $n rostiri · ~${PromptCatalog.estimatedMinutes(n, child = role == Role.Child)} min"
}

@Composable
private fun FixtureBadge(vm: CollectorModel) {
    val f = vm.fixture ?: return
    Tag("Mod de test: ${f.description}, fără microfon", face = C.sunFace, ink = C.sunDeep, edge = C.sun)
}

@Composable
fun HomeScreen(vm: CollectorModel) {
    val active = vm.active
    Page(bottom = {
        if (active != null) {
            FButton("Continuă sesiunea", vm::resume, Modifier.fillMaxWidth(), ButtonKind.Primary)
            FButton("Sesiune nouă", vm::newSession, Modifier.fillMaxWidth())
        } else {
            FButton("Sesiune nouă", vm::newSession, Modifier.fillMaxWidth(), ButtonKind.Primary)
        }
        FButton("Export și ștergere", vm::openExport, Modifier.fillMaxWidth(), ButtonKind.Quiet)
    }) {
        Spacer(Modifier.height(S.s))
        Txt("Fonia Colector", T.display)
        Txt("Înregistrăm sunete spuse corect și imitate, ca Fonia să învețe să le audă.", T.body, color = C.inkSecondary)
        FixtureBadge(vm)
        if (active != null) {
            val s = active.session
            FCard {
                Txt("Sesiune începută", T.label, color = C.inkSecondary)
                Txt("${s.speaker} · Pachetul ${s.pack} · runda ${s.round}", T.title2)
                Txt("${active.index} din ${vm.plan.size} rostiri", T.body)
                ProgressRail(if (vm.plan.isEmpty()) 0f else active.index.toFloat() / vm.plan.size)
            }
        }
        FCard {
            Txt("Pașii", T.title2)
            listOf(
                "Acordul (adult pe ecran; copil: formularul semnat de părinte)",
                "Fișa vorbitorului: cod, grupă de vârstă, pachet",
                "Verificarea camerei: 3 secunde de liniște",
                "Rostirile, cu reascultare și refacere",
                "Exportul: o arhivă .zip, luată pe calculator",
            ).forEachIndexed { i, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(S.m), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(C.fill), contentAlignment = Alignment.Center) {
                        Txt("${i + 1}", T.label, color = C.accentDeep)
                    }
                    Txt(step, T.body, modifier = Modifier.weight(1f))
                }
            }
        }
        Txt("Nimic nu pleacă de pe telefon decât arhiva pe care o exportați voi.", T.caption)
    }
}

@Composable
fun ConsentScreen(vm: CollectorModel) {
    Page(bottom = {
        FButton("Mai departe", vm::acceptConsent, Modifier.fillMaxWidth(), ButtonKind.Primary, enabled = vm.consentValid)
    }) {
        Txt("Acordul", T.title)
        Row(horizontalArrangement = Arrangement.spacedBy(S.m)) {
            FChip("Adult sau logoped", !vm.consentChild, { vm.consentChild = false; vm.consentAgreed = false }, Modifier.weight(1f))
            FChip("Copil", vm.consentChild, { vm.consentChild = true; vm.consentAgreed = false }, Modifier.weight(1f))
        }
        if (!vm.consentChild) {
            FCard {
                Txt("Acord pentru înregistrarea vocii", T.title2)
                Txt(
                    "Vă rugăm să spuneți la telefon sunete, silabe, cuvinte și propoziții: uneori corect, alteori imitând " +
                        "dinadins cum le spun copiii care încă învață sunetul. Telefonul înregistrează doar vocea. Nu filmăm și nu fotografiem.",
                )
                Txt(
                    "Înregistrările servesc doar ca aplicația Fonia să învețe să audă sunetele. Stau pe acest telefon și pe un " +
                        "singur calculator al echipei, sub un cod, nu sub numele dumneavoastră. Nu ajung pe internet și nu apar în " +
                        "prezentări. Le aud doar membrii echipei și logopezii care ne ajută.",
                )
                Txt(
                    "Le ștergem la 31 ianuarie 2027. Vă puteți retrage oricând, fără explicații: spuneți codul de vorbitor și " +
                        "ștergem totul în 48 de ore.",
                )
            }
            CheckRow("Am citit și sunt de acord să îmi fie înregistrată vocea.", vm.consentAgreed, { vm.consentAgreed = it })
            CheckRow("Pot fi păstrate până la 31 decembrie 2027 (opțional).", vm.consentExtended, { vm.consentExtended = it })
            FField(
                "Inițialele dumneavoastră", vm.initials, { vm.initials = it.take(4) },
                help = "2–4 litere. Numele întreg nu se scrie în aplicație.",
                capitalization = KeyboardCapitalization.Characters,
            )
        } else {
            FCard {
                Txt("Acordul părintelui", T.title2)
                Txt(
                    "Un copil se înregistrează doar după ce părintele a semnat pe hârtie acordul Fonia pentru părinți, " +
                        "înainte de prima rostire. Copilul poate opri oricând: dacă spune „nu mai vreau”, ne oprim.",
                )
                Txt("În aplicație nu se scrie numele copilului, doar codul. Nu filmăm și nu fotografiem.")
            }
            CheckRow("Părintele a semnat formularul de acord pentru înregistrarea vocii copilului.", vm.consentAgreed, { vm.consentAgreed = it })
            CheckRow("Pe formular este bifată păstrarea până la 31 decembrie 2027.", vm.consentExtended, { vm.consentExtended = it })
            FField(
                "Codul formularului de acord", vm.formCode, { vm.formCode = it.take(12) },
                help = "Codul scris pe formularul semnat, de exemplu P013.",
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeakerScreen(vm: CollectorModel) {
    val role = vm.role
    val problem = vm.speakerProblem
    Page(bottom = {
        if (problem != null && vm.speaker.isNotEmpty()) Txt("→ $problem", T.label, color = C.inkSecondary)
        FButton("Verificăm camera", vm::startSession, Modifier.fillMaxWidth(), ButtonKind.Primary, enabled = problem == null)
    }) {
        Txt("Fișa vorbitorului", T.title)
        Txt("Fără nume și fără dată de naștere. Legătura cod ↔ nume stă doar pe hârtie.", T.caption)
        FField(
            "Codul vorbitorului", vm.speaker,
            { v -> vm.speaker = v.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }.take(4) },
            help = "A07 adult · T02 logoped · C13 copil" + (role?.let { " — rol: ${it.title}" } ?: ""),
        )
        FField(
            "Runda", vm.round, { v -> vm.round = v.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.take(3) },
            help = "r01 pentru prima rundă de înregistrări.", capitalization = KeyboardCapitalization.None,
        )
        Txt("Grupa de vârstă", T.label)
        val bands = Naming.ageBandsFor(role ?: if (vm.consentChild) Role.Child else Role.Adult)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(S.s), verticalArrangement = Arrangement.spacedBy(S.s)) {
            bands.forEach { band ->
                FChip(if (band == "adult") "adult" else "$band ani", vm.ageBand == band, { vm.ageBand = band })
            }
        }
        Txt("Pachetul", T.label)
        val planRole = role ?: Role.Adult
        vm.catalog.packs.forEach { pack ->
            FChip(pack.title, vm.packId == pack.id, { vm.packId = pack.id }, Modifier.fillMaxWidth(), sub = packLine(vm, pack.id, planRole))
        }
        CheckRow("Flux automat: înregistrarea pornește singură și trece singură mai departe.", vm.autoFlow, { vm.autoFlow = it })
        FCard {
            Txt("Se completează singure", T.label, color = C.inkSecondary)
            KeyValue("Telefon", "${vm.device.manufacturer} ${vm.device.model}")
            KeyValue("Android", "${vm.device.android} (API ${vm.device.sdk})")
            KeyValue("Sursa audio", "se vede la verificarea camerei")
        }
    }
}

@Composable
fun RoomScreen(vm: CollectorModel) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.measureRoom() else vm.micMessage = "Fără microfon nu putem înregistra. Permiteți microfonul din Setări → Aplicații → Fonia Colector."
    }
    fun listen() {
        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (vm.fixture != null || granted) vm.measureRoom() else launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
    val room = vm.room
    Page(bottom = {
        when {
            room == null -> FButton(
                if (vm.roomMeasuring) "Ascult camera…" else "Ascultă camera", ::listen, Modifier.fillMaxWidth(), ButtonKind.Primary,
                enabled = !vm.roomMeasuring,
            )
            else -> ButtonRow {
                FButton("Mai ascult", ::listen, Modifier.weight(1f), enabled = !vm.roomMeasuring)
                FButton(if (room.noisy) "Continuăm aici" else "Începem", vm::startRecording, Modifier.weight(1f), ButtonKind.Primary, enabled = !vm.roomMeasuring)
            }
        }
    }) {
        Txt("Verificarea camerei", T.title)
        FixtureBadge(vm)
        Txt("Trei secunde de liniște. Telefonul pe masă, la 20–30 cm de gură; nimeni nu vorbește.", T.body)
        FCard {
            LevelMeter(vm.recorder.levels, active = vm.roomMeasuring)
            Txt(if (vm.roomMeasuring) "Ascult camera…" else "Nivelul camerei apare aici.", T.caption)
        }
        if (room != null) {
            FCard {
                Txt("Podeaua camerei: ${db(room.floorDb)} dBFS", T.headline)
                if (!room.noisy) {
                    Txt("✓ Camera e liniștită.", T.body, color = C.parrotDeep)
                } else {
                    Txt("E cam gălăgie. Mergem într-un loc mai liniștit?", T.body)
                    Txt("Dacă rămânem aici, se notează în fișa sesiunii.", T.caption)
                }
            }
        }
        vm.capture?.let { cap ->
            FCard {
                Txt("Ce a dat telefonul", T.label, color = C.inkSecondary)
                KeyValue(
                    "Sursa",
                    when (cap.audioSource) {
                        "UNPROCESSED" -> "UNPROCESSED (fără procesare)"
                        "VOICE_RECOGNITION" -> "VOICE_RECOGNITION (telefonul nu oferă sursa fără procesare)"
                        "fixture" -> "fișier de test"
                        else -> cap.audioSource
                    },
                )
                KeyValue("Rata", "${cap.sampleRate} Hz")
                KeyValue("Banda", if (cap.narrowBand) "îngustă (Bluetooth sau rată mică)" else "largă")
                if (!cap.fixture) KeyValue("Microfon", deviceTypeName(cap.deviceType))
            }
        }
        vm.micMessage?.let { Txt("⚠ $it", T.body) }
    }
}

@Composable
fun RecordScreen(vm: CollectorModel) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val active = vm.active ?: return
    val prompt = vm.current ?: return
    val plan = vm.plan
    val soundPrompts = plan.withIndex().filter { it.value.sound == prompt.sound }
    val inSound = soundPrompts.indexOfFirst { it.index == active.index } + 1
    val phase = vm.phase
    val child = active.session.mode == "child"

    Column(Modifier.fillMaxSize().background(C.bg).safeDrawingPadding().padding(horizontal = S.page, vertical = S.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("${active.session.speaker} · Pachetul ${active.session.pack}", T.label, color = C.inkSecondary, modifier = Modifier.weight(1f))
            FButton(if (vm.autoFlow) "Automat: da" else "Automat: nu", { vm.setAuto(!vm.autoFlow) }, kind = ButtonKind.Quiet, height = 48.dp)
            FButton("Pauză", vm::goHome, kind = ButtonKind.Quiet, height = 48.dp)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Txt("${active.index + 1} / ${plan.size}", T.counter, modifier = Modifier.weight(1f))
            Txt("${prompt.letter}: $inSound din ${soundPrompts.size}", T.label, color = C.inkSecondary)
        }
        Spacer(Modifier.height(S.s))
        ProgressRail(active.index.toFloat() / plan.size.coerceAtLeast(1))
        Spacer(Modifier.height(S.l))

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(S.m, Alignment.CenterVertically),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(S.s)) {
                Tag(prompt.level.title + (prompt.position?.let { " · ${it.title}" } ?: ""))
                if (prompt.isError) {
                    Tag("Imitație", face = C.sunFace, ink = C.sunDeep, edge = C.sun)
                } else if (!child) {
                    Tag("Corect", face = C.parrotFace, ink = C.parrotDeep, edge = C.parrot)
                }
            }
            if (prompt.pic != null) Txt(prompt.pic, TextStyle(fontSize = 88.sp, lineHeight = 100.sp))
            if (prompt.isError) {
                Txt("Spune „${prompt.text}” ${prompt.hint}", T.headline, align = TextAlign.Center)
            }
            val big = if (prompt.isError) prompt.imitation ?: prompt.text else prompt.text
            val style = when {
                prompt.level == Level.Sentence -> T.practiceLine
                big.length > 9 -> T.practiceWord.copy(fontSize = 40.sp, lineHeight = 48.sp)
                else -> T.practiceWord
            }
            Txt(if (prompt.isError && prompt.imitation != null) "„$big”" else big, style, align = TextAlign.Center)
            if (prompt.isError && prompt.imitation != null) Txt("în loc de „${prompt.text}”", T.body, color = C.inkSecondary)
            if (!prompt.isError && prompt.hint != null) Txt(prompt.hint, T.body, color = C.inkSecondary, align = TextAlign.Center)
            if (child) Txt("Adultul spune cuvântul, copilul îl repetă.", T.caption, align = TextAlign.Center)
        }

        Spacer(Modifier.height(S.m))
        LevelMeter(
            vm.recorder.levels, active = phase == TakePhase.Listening || phase == TakePhase.Starting,
            threshold = Levels.meter(vm.recorder.speechThresholdDb),
        )
        Spacer(Modifier.height(S.s))
        val take = vm.lastTake
        Txt(
            when (phase) {
                TakePhase.Idle -> if (vm.autoFlow) "Pornește singur…" else "Atinge „Înregistrează” și spune."
                TakePhase.Starting -> "Pregătesc microfonul…"
                TakePhase.Listening -> "Ascult. Spune acum."
                TakePhase.Saving -> "Salvez…"
                TakePhase.Review -> when {
                    take == null -> ""
                    take.speechDetected -> "Păstrat: t${take.take}, ${String.format(Locale.ROOT, "%.1f", take.durationSec)} s. Ascultă, refă sau mergi mai departe."
                    else -> "Nu am auzit vocea. Refă sau mergi mai departe."
                }
            },
            T.body, modifier = Modifier.fillMaxWidth().height(48.dp), align = TextAlign.Center,
        )
        vm.micMessage?.let { Txt("⚠ $it", T.caption) }
        Spacer(Modifier.height(S.s))
        when (phase) {
            TakePhase.Idle -> FButton("● Înregistrează", { vm.startTake() }, Modifier.fillMaxWidth(), ButtonKind.Primary, height = 64.dp)
            TakePhase.Starting, TakePhase.Listening -> FButton("■ Gata", vm::stopTake, Modifier.fillMaxWidth(), ButtonKind.Primary, height = 64.dp)
            TakePhase.Saving -> FButton("Salvez…", {}, Modifier.fillMaxWidth(), ButtonKind.Primary, enabled = false, height = 64.dp)
            TakePhase.Review -> {
                ButtonRow {
                    FButton(if (vm.playing) "▶ Ascult…" else "▶ Ascultă", { vm.play(take?.file) }, Modifier.weight(1f))
                    FButton("↻ Refă", vm::redo, Modifier.weight(1f))
                }
                Spacer(Modifier.height(S.m))
                FButton(
                    if (vm.autoNextIn > 0) "Următorul → (${vm.autoNextIn})" else "Următorul →", vm::accept, Modifier.fillMaxWidth(),
                    ButtonKind.Primary, height = 64.dp,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (prompt.isError) {
                FButton("N-am reușit s-o imit", vm::couldNotImitate, Modifier.weight(1f), ButtonKind.Quiet, height = 48.dp)
            } else {
                Spacer(Modifier.weight(1f))
            }
            FButton("Sari la sunetul următor", vm::skipSound, Modifier.weight(1f), ButtonKind.Quiet, height = 48.dp)
        }
    }
}

@Composable
fun DoneScreen(vm: CollectorModel) {
    val s = vm.finished ?: return
    val clips = vm.keptClips(s).asReversed()
    Page(bottom = {
        FButton("Exportă", vm::openExport, Modifier.fillMaxWidth(), ButtonKind.Primary)
        ButtonRow {
            FButton("Alt pachet", vm::anotherPack, Modifier.weight(1f))
            FButton("Acasă", vm::goHome, Modifier.weight(1f))
        }
    }) {
        Txt("Gata!", T.display)
        Txt("${s.speaker} · Pachetul ${s.pack}: ${s.clipsKept} rostiri păstrate din ${s.promptCount}.", T.body)
        if (s.skippedSounds.isNotEmpty()) Txt("Sunete lăsate pentru altă dată: ${s.skippedSounds.joinToString(", ") { raw -> vm.catalog.sound(raw)?.letter ?: raw }}", T.caption)
        SectionTitle("Reascultare")
        clips.forEach { clip ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt(clip.imitation?.let { "„$it” (${clip.text})" } ?: clip.text, T.headline)
                    Txt("${clip.label} · t${clip.take} · ${String.format(Locale.ROOT, "%.1f", clip.durationSec)} s", T.caption)
                }
                FButton("▶", { vm.play(clip.file) }, kind = ButtonKind.Quiet, height = 48.dp)
            }
        }
    }
}

@Composable
fun ExportScreen(vm: CollectorModel) {
    val context = LocalContext.current
    Page(bottom = { FButton("Acasă", vm::goHome, Modifier.fillMaxWidth()) }) {
        Txt("Export", T.title)
        Txt("Arhiva .zip, cu lista de sume SHA-256, se scrie în folderul aplicației. Pe calculator, cu telefonul legat prin USB:", T.body)
        FCard(background = C.fill, border = null) {
            Txt("adb pull /sdcard/Android/data/tech.fonia.collector/files/export/ .", T.label.copy(fontFeatureSettings = "tnum"))
        }
        Txt("Sau „Trimite…” prin Nearby Share ori Bluetooth. Nu pe chat și nu în cloud.", T.caption)
        if (vm.speakers.isEmpty()) Txt("Nu există încă înregistrări pe telefon.", T.body, color = C.inkSecondary)
        vm.speakers.forEach { s ->
            val id = "${s.round}/${s.speaker}"
            val export = vm.exports[id]
            FCard {
                Txt("${s.round} · ${s.speaker}", T.title2)
                Txt(
                    "${s.clips} rostiri păstrate · ${String.format(Locale.ROOT, "%.1f", s.bytes / 1_048_576.0)} MB · ${s.sessions} " +
                        if (s.sessions == 1) "sesiune" else "sesiuni",
                    T.caption,
                )
                if (export != null) {
                    Txt(export.zip.name, T.label)
                    Txt("SHA-256 al arhivei: ${export.sha.take(16)}…", T.caption)
                    if (export.problems.isEmpty()) {
                        Txt("✓ Verificată: ${export.files} fișiere, sumele se potrivesc.", T.body, color = C.parrotDeep)
                    } else {
                        Txt("⚠ ${export.problems.first()}", T.body)
                    }
                }
                ButtonRow {
                    FButton(if (vm.exportBusy == id) "Fac arhiva…" else "Fă arhiva", { vm.export(s) }, Modifier.weight(1f), enabled = vm.exportBusy == null)
                    if (export != null && export.problems.isEmpty()) {
                        FButton("Trimite…", {
                            val uri = FileProvider.getUriForFile(context, "tech.fonia.collector.files", export.zip)
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "Trimite arhiva").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }, Modifier.weight(1f))
                    }
                }
                FButton(
                    if (vm.deleteArmed == id) "⚠ Atinge din nou: se șterg toate înregistrările lui ${s.speaker}" else "⚠ Șterge de pe telefon",
                    { vm.delete(s) }, Modifier.fillMaxWidth(), ButtonKind.Caution, height = 48.dp,
                )
            }
        }
        Txt("Ștergeți de pe telefon doar după ce arhiva e pe calculator și sumele s-au verificat.", T.caption)
    }
}
