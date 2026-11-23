# 02. Arhitectura și mutarea codului existent

Capitol scris pe 20 noiembrie 2026. Respectă `00_PLAN_MASTER.md` (D1–D16, F1–F9, numele din §3). Acoperă pachetele **A1–A5, E1, E9, F1, F4, G1** (partea de inginerie) și **J8**. Surse: raportul AD, auditul `fonia-brain/notes/android-inventory.md`, Z §1 și §9, W, plus codul citit direct (`AppModel.kt`, `SessionRun.kt`, `TakeJudge.kt`, `Verdict.kt`, `AudioHub.kt`, `AudioPorts.kt`, `CaptureEngine.kt`, `MicrophoneSource.kt`, `FoniaCapture.cpp`, `LiveAnalyzer.kt`, `Schema.kt`, `FoniaRepository.kt`, fișierele de build).

Etichete: **[Cod]** = verificat azi în sursă; **[Estimare]**; **[Neverificat]** = nerulat pe acest PC.

## 1. Principii și graful de module

1. **Ce hotărăște e Kotlin pur.** Android aduce doar porturi: microfon, cameră, rularea modelelor, ecran.
2. **Două benzi de date care nu se amestecă:** `LiveSnapshot` (continuă) și `SessionEvent` (rară).
3. **Fiecare regulă are un singur loc:** „fără redare cât microfonul e deschis” în `AudioDirector`, ștergerea în `DataEraser`, permisiunile în `PermissionGateway`.
4. **Orice prag, coeficient sau durată e dată în `spec/`**, iar versiunea ei se salvează pe încercare.
5. **Injecție prin constructor,** un `AppGraph` scris de mână, nicio referință statică.
6. **Motoarele se mută, nu se rescriu.** Folderele rămân, se schimbă numele de proiect. Cele 910 teste rămân verzi după fiecare pas.
7. **Confidențialitatea e verificată de build,** nu promisă.
8. **Ce pleacă spre iOS trece prin `spec/`** (vectori, SQL, tokenuri), nu prin descrieri.

| Modul | Plugin | Folder | Depinde de | Ce conține; ce intră (+) și ce iese (−) |
|---|---|---|---|---|
| `:core:model` | JVM | `model/` nou | — | `SoundId`, `Verdict`, `ErrorKind`, `Rung`, `ModelVersion`, `JudgeRequest`, interfața `ModelHost` |
| `:core:domain` | JVM | `domain` | `:core:model` | Conținut, `SessionBuilder`, `Progression`, adaptiv. + `PathPlanner` (din `PathModel`), `ReadingScore`, `Connectors`, `ReadAloud`. `SoundID` devine `typealias` spre `:core:model` |
| `:core:data` | JVM | `store` | `:core:model` | SQL, repository-uri, export. + migratorul pe fișiere `.sql`, `EventLog` (din `features/pilot`), `RecordingVault`, `DataEraser`, tabelul `setting` |
| `:core:glyphs` | JVM | `glyphs/` nou | — | Interpolatorul de contururi (cap. 05) |
| `:core:designsystem` | Android + Compose | `design` | `:core:model`, `:core:glyphs`, `:engine:live` | Tokenuri, componente, `rememberLiveFrame`. − tot look-ul vechi |
| `:core:navigation` | JVM | `navigation/` nou | `:core:model` | Cheile ecranelor (`FoniaKey`), fără AndroidX |
| `:core:mouth3d` | Android | `mouth3d/` nou | `:core:model` | Motorul OpenGL ES (cap. 04). + `MouthGeometry`, `SagittalView` ca rezervă 2D |
| `:engine:dsp` | JVM | `dsp` | — | Neatins |
| `:engine:live` | JVM | `live/` nou | `:engine:dsp`, `:core:model` | `LiveSnapshot`, `LiveBoard`, `LivePipeline`, `Clip`, `HoldTracker`; porturile `PcmSource`, `AudioDirector` |
| `:engine:judge` | JVM | `speech` + sursele din `phones` | `:engine:dsp`, `:core:model` | Judecătorii și fuziunea; porturile `Transcriber` (există), `AcousticModel` (există). + `TakeJudge` |
| `:engine:face` | JVM | `face` | `:core:model` | Neatins în acest capitol (cap. 04 lărgește `FaceObservation`) |
| `:engine:session` | JVM | `session/` nou | `:core:model` | Reducerul, `SessionStore`, portul `EffectHandler` |
| `:platform:audio` | Android + NDK | `audio` | `:engine:live` | Oboe, inel, redare. + `OboeAudioDirector` (din `AudioHub` + `LiveAudioBackend`) |
| `:platform:camera` | Android | `facecamera` | `:engine:face`, `:core:model` | CameraX + modelele feței pe LiteRT; `FaceEngine` implementează `ModelHost` |
| `:platform:ml` | Android | `ml/` nou | `:engine:judge`, `:core:model`, `:platform:whisper` | `ModelRegistry`, `OrtHost`, `WhisperHost`, `AssetModelFile` |
| `:platform:whisper` | Android + NDK | `whisper` | `:engine:judge` | Runtime-ul whisper.cpp. − `HttpsModelFetcher` |
| `:feature:onboarding`, `:path`, `:lesson`, `:mirror`, `:adult`, `:speaking` | Android + Compose | `feature/<nume>/` noi | `:core:*`, `:engine:*` | Ecrane + `ViewModel`. Nu văd `:platform:*` și nici alt `:feature:*` |
| `:app` | Android app | `app` | toate | `AppGraph`, `MainActivity`, `NavDisplay`, manifest, `PermissionGateway`, `KeystoreRecordingKey`, codul „fixture” din `src/debug` |
| `:benchmark` | Android test | `benchmark/` nou | `:app` | Macrobenchmark + baseline profile |

`:platform:whisper` e partea „(+ `whisper`)” din master, cu nume propriu fiindcă un proiect Gradle are un singur folder (vezi §16). Folderul `features` nu are urmaș: iese din build la sfârșitul mutării și rămâne pe ramura `main`.

```kotlin
// settings.gradle.kts (extras)
include(":core:model", ":core:domain", ":core:data", ":engine:dsp", ":engine:judge", ":platform:audio" /* … */)
project(":core:domain").projectDir = file("domain")
project(":core:data").projectDir = file("store")
project(":core:designsystem").projectDir = file("design")
project(":engine:dsp").projectDir = file("dsp")
project(":engine:judge").projectDir = file("speech")     // + kotlin.srcDir("../phones/src/main/kotlin")
project(":engine:face").projectDir = file("face")
project(":platform:audio").projectDir = file("audio")
project(":platform:camera").projectDir = file("facecamera")
project(":platform:whisper").projectDir = file("whisper")
```

Testele din `phones` (ONNX Runtime pe JVM, cu modelul ZIPA) devin suita separată `phonesTest` a lui `:engine:judge`, ca poarta rapidă să rămână la 910 teste fără model.

| Regulă | Cum se verifică | Unde rulează |
|---|---|---|
| Fără `import android.` / `androidx.` în `:engine:*` și în `:core:model`, `domain`, `data`, `glyphs`, `navigation` (excepție: `androidx.sqlite`) | task `checkPureModules` (caută în surse) + modulele au doar pluginul `fonia.jvm.library` | la fiecare `check`, CI |
| Niciun `:feature:*` nu depinde de alt `:feature:*` sau de `:platform:*` | `checkModuleGraph` citește dependențele de proiect | CI |
| Manifestul combinat are doar permisiunile din lista albă: `RECORD_AUDIO`, `CAMERA`, `VIBRATE`, `POST_NOTIFICATIONS`. `INTERNET` și `ACCESS_NETWORK_STATE` pică build-ul | `check<Variant>Permissions` pe `MERGED_MANIFEST` (tiparul lui `CheckOnnxRuntimeTask`, care există) | după fiecare `assemble` |
| Niciun SDK de analytics sau client de rețea (`firebase`, `play-services`, `crashlytics`, `sentry`, `okhttp`, `ktor`…) | dependency-guard pe `releaseRuntimeClasspath` + listă neagră; furnizorul de telemetrie ORT rămâne scos | CI |
| Doar licențe permisive | Licensee (Apache-2.0, MIT, BSD, ISC) | CI |
| `allowBackup=false`, `dataExtractionRules` care exclud tot | același task de manifest | CI |
| Texte: virgulă la ș/ț, fără cuvinte interzise | `tools/lint_copy.py` (există), extins cu lista din cap. 07 | CI |

## 2. Mutarea codului, fișier cu fișier

**Din `:features` (ce supraviețuiește):**

| Azi (`features/…`) | Devine | Schimbarea |
|---|---|---|
| `session/TakeJudge.kt` `TakeJudge` | `:engine:judge` `tech.fonia.judge.TakeJudge` | Primește `JudgeRequest` în loc de `Exercise`, `Clip` în loc de `CapturedClip`; întoarce rezultat structurat (`SoundOutcome` + dovezi), fără text |
| `session/Verdict.kt` `TakeVerdict`, `VerdictWriter`, `Praise` | `Verdict` în `:core:model`; textele în stratul de copy din `:feature:lesson` (E7) | Dispare `Rating` din `:design`; lauda devine deterministă |
| `session/SessionRun.kt` | `:engine:session` (reducer) | Contoarele și blocurile intră în `SessionModel`; salvarea devine efectul `SaveAttempt`; `SessionRunTests` devin vectori `spec/session/` |
| `session/SustainExercise.kt` `HeldTime` | `:engine:live` `HoldTracker` | Alimentează `LiveSnapshot.holdSeconds` |
| `app/AudioHub.kt` (`AudioHub`, `AnalyzerProcessor`) | `LivePipeline` în `:engine:live`; `OboeAudioDirector` în `:platform:audio` | Orchestrarea microfon/redare ajunge într-un singur loc |
| `app/AudioPorts.kt`, `LiveAudio.kt`, `FakeAudio.kt` | portul `AudioDirector` (`:engine:live`); implementarea în `:platform:audio`; `FakeAudioDirector` în test fixtures | Șase interfețe mici devin una |
| `app/SpeechHub.kt` | `:platform:ml` `WhisperHost` | Dispar stările de descărcare; `StubTranscriber` rămâne în `:engine:judge` |
| `ml/MlHub.kt`, `ModelManager.kt` (`ModelCatalog`, `ModelEntry`) | `:platform:ml` `ModelRegistry` + `spec/models/manifest.json` | Fără descărcare |
| `ml/ShadowMode.kt` (`ZipaScoringSource`, `PhoneScoring`) | `:engine:judge` `PhonemeJudge` | ZIPA intră în fuziune; coada scurtă trece în `ModelHost` |
| `pilot/*` (`PilotStore`, `PilotRecords`, `PilotSummary`, `PilotExport`) | `:core:data` pachetul `eventlog` | Fișierul SQLite separat se unește cu baza principală (tabelul `eventLog`) |
| `home/PathModel.kt` (`NextStep`, `LessonState`) | `:core:domain` `PathPlanner` | Fără `mutableStateOf` |
| `reading/ReadingScore.kt`, `speaking/bac/Arguments.kt`, `speaking/ReadAloud.kt`, `MarkedText.kt` | `:core:domain` (`reading`, `speaking`) | Mutate cu testele lor; folosite de G10–G12 |
| `adaptive/AdaptiveStore.kt` | `:core:data` | Rămâne oprit |
| `app/Preferences.kt` | tabelul `setting` din `:core:data` | Un singur loc de șters |
| `app/AppModel.kt`: `LaunchArguments` | `:app/src/debug` `LaunchFlags` | Doar în debug |
| `app/AppModel.kt`: `FoniaPlatform`, `AppModel` | `AppGraph` | `AppModel` se sparge în `ProfileRepository`, `CalibrationRepository`, `StartupMaintenance` |
| `app/MicrophonePermission.kt`, `FaceSources.kt` | `:app` (`PermissionGateway`, `AppGraph.faceSource`) | |
| `reading/ReadingReportPdf.kt` | model pentru raportul din `:feature:adult` (G9) | Se păstrează tiparul `PdfDocument` |

**Din `:design`:** `Animation.kt` (`AnimatedVector`, resorturile), `Geometry.kt`, `Painting.kt`, `WrapLayout`, `FoniaAssets`, `NumericText`, `rememberFrameClock` și `LocalReduceMotion` trec în `:core:designsystem` (pachetele `motion`, `draw`, `layout`, `assets`). `MouthGeometry.kt`, `MouthView.kt` și `SagittalView.kt` trec în `:core:mouth3d/fallback2d`.

**Se șterg de pe `v2`:** toate ecranele din `features`, `NavStack`, `PageChrome`, `Presentation`, `SystemList`, `ShadowBadge`, `ShadowHooks`, `AdaptiveRun`, restul lui `reading/*` și `speaking/*`, `Palette`, `Typography`, `Controls`, `Cards`, `Foni.kt`, `Motion.kt`, `Scenes.kt`, galeria de depanare, cele 15 drumuri UI din `app/src/androidTest`, `HttpsModelFetcher`, `StoreSpeechModel`, `FakeSpeechModel`, bibliotecile nefolosite (`navigation-compose`, KSP, `turbine`, `truth`, `espresso`).

**Ordinea** (după fiecare pas: `:engine:dsp:test :core:domain:test :core:data:test :engine:judge:test :engine:face:test` = 910 verzi, apoi `assembleDebug`):

| Pas | Ce se face | De ce e sigur |
|---|---|---|
| 0 | Tag `v1-final` pe `main`, ramura `v2` | — |
| 1 | Numele noi în `settings.gradle.kts`; `project(":dsp")` → `project(":engine:dsp")` în fișierele de build; comenzile din CI | Nu se mută niciun fișier |
| 2 | `build-logic/` cu pluginurile de convenție; fișierele de build se scurtează | Nu se atinge nicio sursă |
| 3 | `:core:model` (tipuri noi); `:core:domain` îl ia ca `api`, cu `typealias SoundID` | Testele vechi compilează neschimbate |
| 4 | `:engine:live`: `Clip` și `HopProcessor` coboară din `:audio` (`typealias CapturedClip = Clip`) | `:audio` își păstrează testele |
| 5 | Logica pură iese din `features` cu testele ei: întâi `ReadingScore`, `Connectors`, `ReadAloud`, `pilot/*`; apoi `TakeJudge`; apoi hub-urile ML și audio | Fiecare mutare e un commit; `features` încă compilează prin `typealias` |
| 6 | Reducerul de sesiune trece vectorii scoși din `SessionRunTests`; `SessionRun` se șterge | Vectorii sunt plasa |
| 7 | `:app` pornește noua cochilie; `features` și fișierele vechi din `design` ies din build | `v1-final` păstrează tot |

## 3. Cele două benzi

### 3.1 Banda continuă: `LiveSnapshot`

```kotlin
class LiveSnapshot {                 // :engine:live — mutabil, 3 instanțe prealocate, zero alocări pe hop
    var seq = 0L                     // crește cu 1 la fiecare publicare
    var captureNanos = 0L            // CLOCK_MONOTONIC al celui mai nou eșantion analizat
    var level = 0f                   // 0..1 peste podeaua camerei, RMS pe ultimul hop (calea rapidă)
    var speaking = false
    var voicing = 0f                 // 0..1 claritatea pitch-ului
    var pitchHz = 0f
    var sibilance = 0f               // 0..1 tăria fricțiunii
    var sNess = 0.5f                 // 0 = Ș … 1 = S; 0,5 fără fricțiune
    var centroidHz = 0f
    var trill = 0f
    var blow = 0f
    var onTarget = 0f                // 0..1 cât de aproape e sunetul de ținta pasului
    var fault: Byte = -1             // LiveFault sau -1
    var holdSeconds = 0f             // timp continuu „bun” (HoldTracker)
    var uncertainty = 0f             // 0..1 → tremurul literei
    var captureQuality: Byte = 0     // Ok, TooQuiet, Clipping, Noisy, NarrowBand
    var dropped = 0
}
```

**Triplu buffer** (`LiveBoard`): un scriitor (firul „analysis”), un cititor (firul principal). Cititorul nu vede niciodată un cadru pe jumătate scris, spre deosebire de câmpurile `@Volatile` din `LiveLevels` de azi **[Cod]**.

```kotlin
class LiveBoard {
    private val slots = Array(3) { LiveSnapshot() }
    private val shared = AtomicInteger(1)          // biții 0–1: slotul din mijloc; bitul 2: „e nou”
    private var back = 0; private var front = 2
    fun back(): LiveSnapshot = slots[back]                       // scriitorul îl umple, apoi publish()
    fun publish() { back = shared.getAndSet(back or 4) and 3 }
    fun latest(): LiveSnapshot {                                 // cititorul, o dată pe cadru
        if (shared.get() and 4 != 0) front = shared.getAndSet(front) and 3
        return slots[front]
    }
}
```

**Citirea în Compose** (`:core:designsystem/live`): o singură buclă pe ecran, iar valorile ajung doar în lambdele citite la desen.

```kotlin
class LiveFrame { val tick = mutableLongStateOf(0L); var now = LiveSnapshot() }   // `now` nu e stare Compose
@Composable fun rememberLiveFrame(board: LiveBoard): LiveFrame =
    remember { LiveFrame() }.also { f -> LaunchedEffect(board) { while (true) withFrameNanos { t -> f.now = board.latest(); f.tick.longValue = t } } }

LiveLetter(text = "S", inflation = { live.tick.longValue; live.now.holdSeconds / targetSeconds },
           roundness = { 100f * (1f - live.now.sNess) })
```

Testul `LiveLetterRecompositionTest` publică 200 de cadre și cere **zero recompuneri**.

### 3.2 Banda de evenimente

`LivePipeline` și gazdele de modele trimit `SessionEvent` cu `trySend` într-un `Channel<SessionEvent>(UNLIMITED)` (sub 10 evenimente pe secundă, deci nu se pierde nimic și firul de analiză nu se blochează). `SessionStore` rulează pe `Dispatchers.Main.immediate`: ia evenimentul, cheamă reducerul, publică `StateFlow<SessionModel>` și dă efectele unui `EffectHandler`. `ViewModel`-ul îl transformă în `StateFlow<UiState>`, citit cu `collectAsStateWithLifecycle`.

| Nu trece niciodată | De ce |
|---|---|
| `LiveSnapshot` printr-un `Flow`, `StateFlow` sau parametru de composable | 100 de recompuneri pe secundă |
| Un verdict sau o tranziție de pas dedusă din `LiveSnapshot` în UI | Banda continuă pierde cadre; deciziile vin doar din reducer |
| Eșantioane audio sau cadre de cameră pe vreuna dintre benzi | Rămân în `Clip`, ținut de `:platform:audio`, cu referință `ClipRef` |
| Ceasul sistemului citit în reducer | Timpul intră ca eveniment |

## 4. Audio în timp real (E1)

| Subiect | Decizie | Stare |
|---|---|---|
| Sursa | `UNPROCESSED` dacă `PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED == "true"`, altfel `VOICE_RECOGNITION`; niciodată `MIC` sau `VOICE_COMMUNICATION`; AGC, NS și AEC oprite prin `PlatformEffects` | Există **[Cod: `InputPreset.preferred`]**. Nou: sursa se scrie pe fiecare încercare |
| Fluxul | Oboe, intrare, 48 kHz mono float, `LowLatency`, `PrivacySensitiveMode::Enabled`; `Exclusive` încercat întâi, `Shared` ca rezervă (azi doar `Shared`) | Există; `Exclusive` e nou **[Neverificat pe telefon]** |
| Hop | 480 de eșantioane = 10 ms | Există (`CaptureEngine.hopSize`) |
| Fereastra spectrală | 2048 de eșantioane = 42,7 ms (`analysis.frameSeconds = 0.04`), păstrată ca să rămână testele de paritate | **[Cod]** |
| Rata analizei | Azi analiza grea rulează la **50 Hz**, nu la 100: `live.analysisHopSeconds = 512/48000` e mai mare decât hopul de 480, deci un hop din două e sărit. `spec/thresholds/dsp.json` îl pune la `0.01` | **[Cod: `LiveAnalyzer.process`]**; costul dublu se măsoară (≤ 3 ms pe hop) |
| Calea rapidă | `level` se calculează pe ultimul hop (10 ms), nu pe fereastra de 43 ms; forma (`sNess`, `centroidHz`) vine din fereastra lungă | Nou, în `LivePipeline` |
| Firul de analiză | Un fir propriu `fonia-analysis` cu `THREAD_PRIORITY_AUDIO`, trezit de callback printr-un semafor (`ringWaitRead` în `FoniaAudioRT.cpp`), în locul `delay(10)` pe `Dispatchers.Default` de azi | Nou; scoate până la 10 ms de așteptare și concurența cu modelele |
| Ceasul capturii | `FNARing` numără eșantioanele scrise; firul de analiză citește `getTimestamp(CLOCK_MONOTONIC)` la 100 ms și calculează `captureNanos` pe cadru | Nou |
| 16 kHz pentru modele | Decimarea 3:1 se face o singură dată, pe `Clip`, la `AttemptEnded` (`SpeechResampler` există). Niciun model nu rulează în flux în v1, deci nu construim inelul live de 16 kHz | Simplificare față de AD |

**Bugetul de latență** (voce → literă, p95 ≤ 80 ms) **[Estimare până la măsurătoarea de sâmbătă]**:

| Segment | Buget |
|---|---|
| Microfon → callback (HAL + burst) | ≤ 10 ms |
| Așteptarea hopului | ≤ 10 ms |
| Întârzierea ferestrei (cale rapidă / spectrală) | 5 / 21 ms |
| Analiza | ≤ 3 ms (p99 ≤ 5) |
| Așteptarea cadrului (60 Hz) | ≤ 17 ms |
| **Software, măsurat de overlay** (`vsync − captureNanos`), p95 | **≤ 55 ms** |
| Desen + afișare | ~25 ms |

**Overlay-ul de latență** (doar debug, `--ez fonia.latencyOverlay true`): p50/p95 pe ultimele 5 s pentru `vsync − captureNanos`, sursa reală (`CaptureInfo`), rata, burst-ul, eșantioane pierdute, cadre peste 32 ms. Partea de afișare se măsoară o dată: filmăm cu alt telefon la 240 fps o bătaie din palme și litera, apoi numărăm cadrele.

**Un singur loc pentru regula de redare:**

```kotlin
interface AudioDirector {                     // port în :engine:live; OboeAudioDirector în :platform:audio
    val state: StateFlow<AudioState>          // Idle, Speaking, Settling, Listening, Suspended(reason)
    suspend fun play(prompt: PromptRef): Boolean       // închide întâi microfonul; revine la sfârșitul clipului
    suspend fun openMic(target: LiveTarget): CaptureInfo   // doar din Idle, după micOpenDelayMs (150)
    suspend fun closeMic(): Clip
    fun earcon(e: Earcon)                     // ignorat în Listening (azi: CaptureActivity.isActive)
    fun suspendAll(reason: SuspendReason)
}
```

Nimeni altcineva nu atinge `PlaybackEngine`, `SoundEffects` sau `CaptureEngine`. Testul JVM `AudioDirectorRulesTest` încearcă toate ordinile de apel și cere ca redarea și captura să nu se suprapună niciodată.

| Ciclu de viață | Comportament |
|---|---|
| Aplicația iese din prim-plan | `ProcessLifecycleOwner` `ON_STOP` → `suspendAll(Background)` → evenimentul `Interrupted`. Azi nu există nicio legătură de acest fel **[Cod, audit §7]** |
| Focus audio | `AUDIOFOCUS_GAIN_TRANSIENT` la intrarea în `LESSON`; la pierdere → `Interrupted(FocusLost)` |
| Căști cu fir sau USB | `AudioDeviceCallback` → fluxul se reface (există în `MicrophoneGraph`), evenimentul `RouteChanged` |
| Microfon Bluetooth | Forțăm microfonul telefonului cu `setDeviceId`; dacă nu se poate, `captureQuality = NarrowBand`, iar S/Ș nu se judecă (există: `isNarrowBand`) |
| Deconectare | `onErrorAfterClose` → refacere de cel mult 3 ori (există), apoi `Interrupted(NoInput)` |

## 5. Motorul de sesiune (F1)

```kotlin
sealed interface SessionState {
    data object Idle : SessionState
    data class Intro(val until: Long) : SessionState
    data class Modeling(val item: Int, val tries: Int) : SessionState                 // se aude modelul
    data class Listening(val item: Int, val tries: Int, val since: Long) : SessionState
    data class Judging(val item: Int, val tries: Int, val clip: ClipRef, val since: Long) : SessionState
    data class AskAdult(val item: Int, val clip: ClipRef) : SessionState              // după Unsure
    data class Feedback(val item: Int, val verdict: Verdict, val until: Long) : SessionState
    data class MovementBreak(val resumeItem: Int, val until: Long) : SessionState
    data class Paused(val resume: SessionState, val reason: PauseReason) : SessionState
    data class Ended(val reason: EndReason) : SessionState
}
data class SessionModel(val state: SessionState, val counters: Counters, val activeMs: Long, val lastAt: Long)
data class Transition(val model: SessionModel, val effects: List<Effect>)

fun reduce(model: SessionModel, event: SessionEvent, plan: LessonPlan): Transition   // pur: fără I/O, ceas sau hazard
```

| Evenimente (`SessionEvent`, toate cu `at` în ms) | Efecte (`Effect`) |
|---|---|
| `Start`, `Tick`, `PromptEnded(id)`, `AttemptStarted`, `AttemptEnded(clip)`, `VerdictReady(verdict, evidence)`, `JudgeFailed`, `AdultAnswered(good)`, `Tapped(Continue / Replay / Skip / Exit)`, `Interrupted(reason)`, `Resumed`, `RouteChanged(narrowBand)`, `PermissionChanged` | `Play(promptId)`, `StopAudio`, `OpenMic(target)`, `CloseMic`, `Judge(clip, request)`, `SaveAttempt(draft)`, `AddAnchor(clip, good)`, `SaveCheckpoint`, `Haptic(kind)`, `ShowParentCard(id)`, `FinishSession(summary)`, `Log(kind)` |

- **Timpul:** `SessionStore` trimite `Tick(at)` la 250 ms și pune `at` pe fiecare eveniment. Duratele (`listenTimeoutMs`, `judgeTimeoutMs`, `feedbackMs`, `micOpenDelayMs`, `budgetMs = 240000`, `breakAtMs`, `breakMs`, `maxTries`) stau în `LessonPlan.config`, cu valorile din capitolul 06. Depășirea lui `judgeTimeoutMs` dă `Unsure`, niciodată o blocare.
- **Efectele** le execută `LessonEffects : EffectHandler` din `:feature:lesson`, care primește din `AppGraph` doar porturi (`AudioDirector`, `Judge`, `AttemptRepository`, `Haptics`). Rezultatul unui efect se întoarce ca eveniment.
- **Întrerupere:** `Interrupted` din orice stare → `Paused(resume)`, cu efectele `CloseMic`, `StopAudio`, `SaveCheckpoint`. Încercarea începută se aruncă, nu se salvează. `Resumed` reia itemul de la `Modeling`, niciodată din mijlocul ascultării.
- **Moartea procesului:** `SaveCheckpoint` scrie în rândul `session` sămânța planului, itemul, `activeMs` și contoarele. La repornire, un checkpoint mai nou de 30 de minute propune „Continuăm?”; altfel sesiunea se închide ca `partial`.

**Formatul `spec/session/*.json`**: o stare de pornire și o listă de pași (eveniment → stare așteptată + efecte așteptate, comparate exact). Kotlin îl rulează cu un test parametrizat, Swift cu `Codable` + XCTest.

```json
{ "name": "corect_din_prima", "plan": "plans/s_isolated_3.json",
  "start": { "type": "Modeling", "item": 0, "tries": 0 },
  "steps": [
    { "event": { "type": "PromptEnded", "id": "s.model.0", "at": 1800 },
      "state": { "type": "Listening", "item": 0, "tries": 0, "since": 1800 },
      "effects": [ { "type": "OpenMic", "target": "s" } ] },
    { "event": { "type": "AttemptEnded", "clip": "c1", "at": 3400 },
      "state": { "type": "Judging", "item": 0, "tries": 0, "clip": "c1", "since": 3400 },
      "effects": [ { "type": "CloseMic" }, { "type": "Judge", "clip": "c1", "request": "s.isolated" } ] },
    { "event": { "type": "VerdictReady", "verdict": { "type": "Correct" }, "at": 3480 },
      "state": { "type": "Feedback", "item": 0, "verdict": { "type": "Correct" }, "until": 4680 },
      "effects": [ { "type": "SaveAttempt", "item": 0 }, { "type": "Haptic", "kind": "success" }, { "type": "Play", "id": "foni.bravo.1" } ] },
    { "event": { "type": "Tick", "at": 4700 },
      "state": { "type": "Modeling", "item": 1, "tries": 0 },
      "effects": [ { "type": "Play", "id": "s.model.1" } ] } ] }
```

```json
{ "name": "nesigur_intreaba_adultul_apoi_intrerupere", "plan": "plans/s_isolated_3.json",
  "start": { "type": "Judging", "item": 1, "tries": 0, "clip": "c2", "since": 9000 },
  "steps": [
    { "event": { "type": "VerdictReady", "verdict": { "type": "Unsure" }, "at": 9300 },
      "state": { "type": "AskAdult", "item": 1, "clip": "c2" },
      "effects": [ { "type": "ShowParentCard", "id": "thumb" } ] },
    { "event": { "type": "AdultAnswered", "good": true, "at": 12100 },
      "state": { "type": "Feedback", "item": 1, "verdict": { "type": "Correct" }, "until": 13300 },
      "effects": [ { "type": "SaveAttempt", "item": 1 }, { "type": "AddAnchor", "clip": "c2", "good": true }, { "type": "Haptic", "kind": "success" } ] },
    { "event": { "type": "Interrupted", "reason": "Background", "at": 12600 },
      "state": { "type": "Paused", "reason": "Background", "resume": { "type": "Modeling", "item": 2, "tries": 0 } },
      "effects": [ { "type": "CloseMic" }, { "type": "StopAudio" }, { "type": "SaveCheckpoint" } ] } ] }
```

## 6. Modelele în APK (E9)

```kotlin
interface ModelHost<I, O> : AutoCloseable {      // interfața în :core:model
    val version: ModelVersion                    // id, version, sha256, runtime
    val state: StateFlow<HostState>              // Cold, Loading, Warm, Failed
    suspend fun warmUp()                         // încarcă + o inferență pe intrare zero
    suspend fun run(input: I): O                 // pe firul gazdei; anulabil
    fun trim()                                   // eliberează memoria; următorul run reîncarcă
}
```

| Gazdă | Runtime, modul | Fișier în APK | Mărime | Fir, coadă | Anulare | Cald când |
|---|---|---|---|---|---|---|
| `ZipaHost` | ONNX Runtime 1.32, `:platform:ml` | `models/zipa-small-crctc-500k/model.int8.onnx` (int8, există) | 70,7 MB | `ml-zipa`, capacitate 1, așteaptă | `RunOptions.setTerminate` **[Neverificat]** | Tot timpul în `LESSON` |
| `WhisperHost` | whisper.cpp 1.9.5, `:platform:whisper` | `models/whisper-base/ggml-base-q5_1.bin` + `tokenizer.json` | ~60 + 2,5 MB **[Neverificat: mărimea q5_1]**; f16 de azi are 148 MB | `ml-whisper`, capacitate 1 | `abort_callback` **[Neverificat]** | Doar la pașii `Sentence`, `Twister`, `Retell` și în `NO-UM`, `MINUTE` |
| `FaceEngine` | LiteRT 2.3.2, `:platform:camera` | `models/face_landmarker.task` (există) | 3,6 MB | firul unic al camerei (există), ultimul cadru câștigă | renunță la cadru | Doar în `MIRROR` și la pașii cu cameră |

- **Fără rețea:** task-ul `modelAssets` copiază fișierele fixate (SHA-256 din `spec/models/manifest.json`) din `~/.gradle/fonia-models` în assets generate și pică dacă lipsesc. `fetchModels` le aduce o dată pe PC și în CI. Aplicația nu descarcă nimic.
- **Încărcare fără copie pe disc:** modelele stau necomprimate în APK și se mapează în memorie (`AssetModelFile`: `openFd` + `FileChannel.map`; ORT `createSession(ByteBuffer)`, whisper `whisper_init_from_buffer`). Rezerva, dacă maparea nu merge pe un runtime: copiere la prima pornire în `noBackupFilesDir/models/<id>/<version>/`, cu SHA-256 verificat.
- **Whisper cuantizat** intră doar dacă harness-ul din `whisper/PARITY.md` rămâne la 23/23 perechi și 7/7 propoziții; altfel încercăm `q8_0` (~80 MB).
- **Memorie:** PSS ≤ 350 MB în `LESSON` (ZIPA + față), ≤ 450 MB cu Whisper încărcat **[Estimare]**. `onTrimMemory(RUNNING_LOW)` cheamă `trim()` pe Whisper, apoi pe față. Sub 6 GB RAM, Whisper și camera nu stau calde împreună.
- **Versiuni:** `ModelRegistry.versions()` dă șirul `zipa@dfe87c976626;whisper-base-q5_1@…;face@…`, salvat în `attempt.modelVersions` împreună cu `thresholdsVersion` și `fusionVersion`.
- **ABI:** release = doar `arm64-v8a` (ca azi); debug pentru emulator cu `-Pfonia.abi=x86_64` (există). `armeabi-v7a` iese de tot: nici Whisper, nici ORT nu rulează pe 32 de biți.

**Bugetul APK-ului arm64 (≤ 200 MB):**

| Componentă | Azi | Țintă | Cum |
|---|---|---|---|
| ZIPA | 0 (se descarcă) | 71 | — |
| Whisper + tokenizer | 0 (se descarcă 151) | 63 | `q5_1` |
| Modelele feței | 3,6 | 4 | — |
| Biblioteci native (ORT 34, whisper, LiteRT, Oboe) | 46 | 24 | comprimate în APK (`useLegacyPackaging`) sau ORT cu operatori reduși **[Estimare]** |
| Dex + resurse | ~10 | 10 | R8 |
| Imagini | 47 | 12 | WebP, cel mult 512 px |
| Voce | 17,5 | 12 | Opus sau AAC la debit mic |
| Fonturi, contururi, gura 3D, `spec/` | 1,5 | 4 | — |
| **Total** | 121 fără modele (~340 cu ele) | **~200** | poarta `apkSizeGate` pică peste 200 MB |

Marja e zero: dacă măsurătoarea de la M2 iese peste, vezi §16.

## 7. Datele (F4)

Schema de azi e identică cu cea GRDB de pe iOS: șase migrări (`v1-profiles`, `v1-recordings`, `v1-practice`, `v1-talks`, `v1-notes`, `v2-session-focus`) ținute în `grdb_migrations` **[Cod]**. Păstrăm mecanismul. Fiecare migrare devine un fișier `spec/sql/NNNN_<identificator>.sql`: `0001`–`0006` reproduc textul din `Schema.kt` caracter cu caracter (testul `SchemaTests` îl îngheață), iar v3 adaugă:

```sql
-- 0007_v3-attempt-evidence.sql  (machineOutcome și adultOutcome rămân, pentru agregatele existente)
ALTER TABLE "attempt" ADD COLUMN "verdict" TEXT;             -- Correct|Almost|NamedError|Unsure|Retake
ALTER TABLE "attempt" ADD COLUMN "errorKind" TEXT;           -- ErrorKind
ALTER TABLE "attempt" ADD COLUMN "errorShown" BOOLEAN NOT NULL DEFAULT 0;  -- numită copilului (F5)
ALTER TABLE "attempt" ADD COLUMN "confidence" DOUBLE;
ALTER TABLE "attempt" ADD COLUMN "judges" TEXT;              -- JSON: scorul fiecărui judecător
ALTER TABLE "attempt" ADD COLUMN "rung" TEXT;
ALTER TABLE "attempt" ADD COLUMN "step" TEXT;
ALTER TABLE "attempt" ADD COLUMN "audioSource" TEXT;         -- unprocessed|voiceRecognition|fixture
ALTER TABLE "attempt" ADD COLUMN "sampleRate" INTEGER;
ALTER TABLE "attempt" ADD COLUMN "deviceModel" TEXT;
ALTER TABLE "attempt" ADD COLUMN "captureQuality" TEXT;
ALTER TABLE "attempt" ADD COLUMN "faceUsed" BOOLEAN NOT NULL DEFAULT 0;
ALTER TABLE "attempt" ADD COLUMN "modelVersions" TEXT;
ALTER TABLE "attempt" ADD COLUMN "thresholdsVersion" TEXT;
ALTER TABLE "attempt" ADD COLUMN "fusionVersion" TEXT;
ALTER TABLE "attempt" ADD COLUMN "unlockVersion" TEXT;
ALTER TABLE "attempt" ADD COLUMN "calibrationCount" INTEGER;
ALTER TABLE "session" ADD COLUMN "activeMs" INTEGER NOT NULL DEFAULT 0;
ALTER TABLE "session" ADD COLUMN "endReason" TEXT;
ALTER TABLE "session" ADD COLUMN "contentVersion" TEXT;
ALTER TABLE "session" ADD COLUMN "checkpoint" TEXT;
ALTER TABLE "soundProgress" ADD COLUMN "rungKey" TEXT;       -- una dintre cele 14 trepte
ALTER TABLE "soundProgress" ADD COLUMN "adultConfirmedAt" DATETIME;
ALTER TABLE "soundProgress" ADD COLUMN "chosen" BOOLEAN NOT NULL DEFAULT 0;

-- 0008_v3-calibration.sql
CREATE TABLE "calibrationAnchor" (
  "id" BLOB PRIMARY KEY NOT NULL,
  "profileId" BLOB NOT NULL REFERENCES "profile"("id") ON DELETE CASCADE,
  "sound" TEXT NOT NULL, "ts" DATETIME NOT NULL,
  "label" TEXT NOT NULL,            -- good|notGood
  "source" TEXT NOT NULL,           -- adultThumb|calibrateScreen
  "features" TEXT NOT NULL,         -- JSON: vectorul de trăsături, fără audio
  "featureVersion" TEXT NOT NULL, "audioSource" TEXT NOT NULL,
  "attemptId" BLOB REFERENCES "attempt"("id") ON DELETE SET NULL);
CREATE INDEX "calibrationAnchor_on_profile_sound" ON "calibrationAnchor"("profileId", "sound");

-- 0009_v3-recording-crypto.sql
ALTER TABLE "recording" ADD COLUMN "codec" TEXT NOT NULL DEFAULT 'm4a';   -- nou: 'pcm16'
ALTER TABLE "recording" ADD COLUMN "keyVersion" INTEGER NOT NULL DEFAULT 0; -- 0 = necriptat (vechi)
ALTER TABLE "recording" ADD COLUMN "sampleRate" INTEGER;
ALTER TABLE "recording" ADD COLUMN "kind" TEXT NOT NULL DEFAULT 'best';   -- best|first|speaking
ALTER TABLE "recording" ADD COLUMN "rung" TEXT;

-- 0010_v3-habit.sql
CREATE TABLE "practiceDay" (
  "profileId" BLOB NOT NULL REFERENCES "profile"("id") ON DELETE CASCADE,
  "localDay" TEXT NOT NULL, "sessions" INTEGER NOT NULL DEFAULT 0,
  "activeMs" INTEGER NOT NULL DEFAULT 0, "attempts" INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY ("profileId", "localDay"));
CREATE TABLE "habit" (
  "profileId" BLOB PRIMARY KEY NOT NULL REFERENCES "profile"("id") ON DELETE CASCADE,
  "weeklyGoalDays" INTEGER NOT NULL DEFAULT 4, "reminderMinute" INTEGER,
  "streak" INTEGER NOT NULL DEFAULT 0, "bestStreak" INTEGER NOT NULL DEFAULT 0, "lastPracticeDay" TEXT);

-- 0011_v3-event-log.sql
CREATE TABLE "eventLog" (
  "id" INTEGER PRIMARY KEY AUTOINCREMENT, "ts" DATETIME NOT NULL, "localDay" TEXT NOT NULL,
  "profileId" BLOB REFERENCES "profile"("id") ON DELETE CASCADE, "sessionId" BLOB,
  "kind" TEXT NOT NULL, "data" TEXT NOT NULL DEFAULT '{}');
CREATE INDEX "eventLog_on_day_kind" ON "eventLog"("localDay", "kind");
CREATE TABLE "eventDaily" ("localDay" TEXT NOT NULL, "kind" TEXT NOT NULL, "count" INTEGER NOT NULL,
  PRIMARY KEY ("localDay", "kind"));

-- 0012_v3-settings.sql
CREATE TABLE "setting" ("key" TEXT PRIMARY KEY NOT NULL, "value" TEXT NOT NULL);
```

Tabelele `profile`, `speech` și `note` rămân cum sunt. `eventLog` ține detaliile 90 de zile, apoi rămân doar totalurile din `eventDaily` (regula din `PilotStore`). Seria se calculează din `practiceDay`; `habit` e doar memoria ei.

**Exportul JSON v2** (`spec/export/export_v2.schema.json`): fără audio, fără vectorii de calibrare, cu numele înlocuit.

```json
{ "schemaVersion": 2, "exportedAt": "2026-11-24T18:02:11Z",
  "app": { "version": "2.0.0", "content": "<sha>", "spec": "<sha>", "deviceModel": "…" },
  "profile": { "id": "<uuid>", "name": "[nume]", "ageBand": "…", "createdAt": "…" },
  "sounds": [ { "sound": "s", "rung": "wordsInitial", "adultConfirmedAt": "…", "anchors": 7,
      "weeks": [ { "week": "2026-W47", "attempts": 64, "correct": 41, "almost": 12, "unsure": 6, "errors": { "PlaceSwap": 5 } } ] } ],
  "sessions": [ { "id": "…", "localDay": "2026-11-23", "sound": "s", "activeMs": 241000, "attempts": 22, "endReason": "completed" } ],
  "attempts": [ { "ts": "…", "sound": "s", "rung": "isolated", "target": "s", "verdict": "NamedError", "errorKind": "PlaceSwap",
      "errorShown": true, "confidence": 0.83, "adultOutcome": null, "audioSource": "unprocessed",
      "modelVersions": "…", "thresholdsVersion": "…", "fusionVersion": "…" } ],
  "habit": { "weeklyGoalDays": 4, "streak": 3, "days": [ "2026-11-21", "2026-11-22" ] }, "notes": [] }
```

**Înregistrările.** Azi sunt fișiere `.m4a` necriptate de aplicație; le protejează doar criptarea sistemului din `noBackupFilesDir` **[Cod: `RecordingFiles`, `RecordingWriter.writeM4A`]**. În v3:

| Aspect | Decizie |
|---|---|
| Format | PCM 16 biți mono la rata capturii, criptat în memorie, deci niciun octet în clar nu atinge discul. O încercare scurtă are 100–300 KB **[Estimare]**; cel mult 3 pe sunet, 90 de zile (`RecordingRetentionPolicy` rămâne) |
| Fișier | `FREC1` · `keyVersion` (1 octet) · nonce (12) · text cifrat · etichetă GCM (16); AAD = id-ul înregistrării |
| Cod | `RecordingVault` în `:core:data` (AES-256-GCM cu `javax.crypto`, testat pe JVM) + portul `RecordingKeyProvider` |
| Cheia | `KeystoreRecordingKey` în `:app`: o cheie AES din Android Keystore (aliasul `fonia.rec.v1`, neexportabilă) împachetează o cheie de date aleatoare, ținută în `noBackupFilesDir/Fonia/keys/` |
| Redare | decriptare în memorie → `Clip` → `PlaybackEngine.play(clip)` (există) |
| Trimitere către logoped | doar la gestul adultului: decriptare → `writeM4A` în `cacheDir/share/` → `FileProvider` → ștergere la întoarcere |
| Fișierele vechi | rândurile cu `keyVersion = 0` și fișierele lor sunt șterse la prima pornire v3 (nu există utilizatori reali pe Android) |

**„Șterge tot”** (`DataEraser.eraseAll()`), în ordine: (1) `AudioDirector.suspendAll`, camera oprită, sesiunea anulată; (2) aliasul din Keystore și cheia împachetată, după care înregistrările sunt ilizibile; (3) folderul de înregistrări; (4) baza închisă și fișierele `fonia.sqlite*` șterse, nu doar rândurile; (5) `cacheDir`; (6) `AppGraph` reconstruit, navigare la `ON-0`. Garanția e testată de două ori: pe JVM (`DataEraserTest`: arborele e gol, iar un fișier cifrat copiat dinainte nu se mai deschide) și instrumentat în `:app` (`filesDir`, `noBackupFilesDir` și `cacheDir` goale, aliasul absent).

**Fără backup:** `allowBackup="false"` (există), `dataExtractionRules` care exclud tot, toate fișierele sub `noBackupFilesDir`.

## 8. Cochilia și navigarea (G1)

```kotlin
class AppGraph(context: Context, flags: LaunchFlags, overrides: Overrides = Overrides()) {   // :app, singurul loc cu `new`
    val spec = SpecStore(context.assets)                       // praguri, fuziune, unlock, poze, manifest
    val database by lazy { FoniaDatabase.live(context.noBackupFilesDir) }
    val profiles = ProfileRepository(database);  val attempts = AttemptRepository(database)
    val models = ModelRegistry(context, spec.models)
    val liveBoard = LiveBoard()
    val audio: AudioDirector = overrides.audio ?: OboeAudioDirector(context, liveBoard, spec.thresholds)
    val judge: Judge = FusionJudge(TakeJudge(spec.thresholds), models.zipa, models.whisper, spec.fusion)
    fun faceSource(): FaceSource = overrides.face ?: CameraFaceSource(context)
    fun lessonViewModel(key: LessonKey) = LessonViewModel(key, SessionStore(::reduce), LessonEffects(audio, judge, attempts), profiles)
    val eraser = DataEraser(/* … */)
}
```

Testele și modul „fixture” construiesc același graf cu `Overrides` (rolul lambdelor din `FoniaPlatform` de azi).

**Cheile** (`:core:navigation`, `@Serializable sealed interface FoniaKey`; Navigation 3 primește orice tip de cheie, deci modulul rămâne JVM pur):

| Ecran | Cheie | Cochilie |
|---|---|---|
| `ON-0…ON-7` | `OnboardingKey(step: Int)` | fără cochilie |
| `PATH` | `PathKey` | copil (rădăcină) |
| `LESSON` | `LessonKey(sound, rung, sessionId)`; pașii (`Listen`…`Mission`) sunt stări ale reducerului, nu intrări în stivă | copil, ecran întreg |
| `MIRROR` | `MirrorKey(sound: SoundId?)` | copil, ecran întreg |
| `END` | `EndKey(sessionId)` | copil |
| `ADULT-HOME`, `ADULT-SOUNDS`, `ADULT-CALIBRATE`, `ADULT-REPORT`, `ADULT-SETTINGS` | `AdultHomeKey`, `AdultSoundsKey`, `AdultCalibrateKey(sound)`, `AdultReportKey`, `AdultSettingsKey` | adult (stivă proprie, după `AdultGate`) |
| `TEEN-HOME`, `DICTION`, `NO-UM`, `MINUTE` | `TeenHomeKey`, `DictionKey(moduleId)`, `NoUmKey`, `MinuteKey` | adolescent (rădăcină `TeenHomeKey`) |

Cochilia se alege după profilul activ. Stiva e un `SnapshotStateList<FoniaKey>` ținut de `Navigator` (testat pe JVM), afișat cu `NavDisplay`; fiecare intrare are propriul `ViewModel` prin `lifecycle-viewmodel-navigation3`.

| „Înapoi” din | Face |
|---|---|
| `ON-n` | pasul anterior; din `ON-0` închide aplicația |
| `LESSON` | `Tapped(Exit)` → pauză cu „Mai stăm?” sau „Gata pe azi”; nu se iese fără confirmare |
| `END` | `PATH`, niciodată înapoi în lecție |
| `MIRROR` | de unde a venit; camera se oprește înainte de tranziție |
| `ADULT-HOME` | golește stiva adultului; următoarea intrare trece iar prin poartă |
| `PATH`, `TEEN-HOME` | închide aplicația (gestul predictiv e deja activ în manifest) |

- **Restaurarea stării:** stiva se salvează serializată (`rememberSerializable`). După moartea procesului, `LESSON` revine din checkpoint (§5), iar stiva adultului **nu** se restaurează (se cade pe `PATH`). Lista largă `configChanges` din manifest se scoate; rămâne `portrait`.
- **Edge-to-edge:** `enableEdgeToEdge()` în `MainActivity`; insetele se consumă într-un singur `Scaffold` pe cochilie. `LESSON` și `MIRROR` desenează sub bare și țin controalele în `safeDrawing`.
- **Permisiuni** (`PermissionGateway`, stări `NotAsked`, `Granted`, `Denied`, `DeniedForever`, trimise reducerului ca `PermissionChanged`):

| Permisiune | Când se cere | Dacă e refuzată |
|---|---|---|
| `RECORD_AUDIO` | în onboarding, la primul pas în care copilul vorbește, după o explicație pentru adult | lecția merge doar cu pașii de ascultat; un card îi arată adultului cum o pornește din setări |
| `CAMERA` | la prima intrare în `MIRROR` | oglinda arată gura 3D și ghidajul fără cameră |
| `POST_NOTIFICATIONS` (API 33+) | în zona adultului, la pornirea amintirii zilnice | amintirea rămâne oprită |

## 9. Build și CI (A1, A2)

**Catalogul țintă** (din AD §1.3, verificat acolo): AGP 9.5.2, Gradle 9.9.2, Kotlin 2.4.21, Compose BOM 2026.11.01, lifecycle 2.11.1, `navigation3` 1.2.2, CameraX 1.7.0, ONNX Runtime 1.32.0, LiteRT 2.3.2, Oboe 1.11.0, Roborazzi 1.79.0 + ComposablePreviewScanner 0.10.1 + Robolectric 4.18.1, Licensee 1.14.1, Spotless 8.11.0, benchmark 1.5.0, profileinstaller 1.4.1. Fără KSP, DataStore sau Tink. Actualizările intră una câte una, cu testele rulate; dacă una rupe ceva sâmbătă dimineață, rămâne versiunea veche.

| Plugin în `build-logic/` | Ce fixează |
|---|---|
| `fonia.jvm.library` | Kotlin JVM 17, JUnit 5, `fonia.content`, `checkPureModules` |
| `fonia.android.library` | compileSdk 37, minSdk 29, JVM 17, lint |
| `fonia.android.compose` | Compose, Roborazzi, lint-urile de Compose |
| `fonia.android.feature` | cele de mai sus + dependențele permise unui `:feature:*` |
| `fonia.android.ndk` | NDK 28.2, CMake 3.22.1, `-Werror`, pagini de 16 KB, `consumer-rules.pro` |

| Task Gradle | Ce face |
|---|---|
| `contentAssets` (există, mutat din `app/build.gradle.kts`) | copiază `content/json`, `voice`, `images`, `fonts` în assets |
| `fetchModels`, `modelAssets` | aduce pe PC și verifică modelele fixate; le pune în assets |
| `specAssets` | copiază `spec/thresholds`, `fusion`, `unlock.json`, `poses`, `models`, `tokens`, `glyphs` în assets |
| `fixtureAssets` (doar debug) | copiază `spec/fixtures` în assets de debug |
| `checkPureModules`, `checkModuleGraph`, `check<Variant>Permissions`, `apkSizeGate`, `lintCopy` | regulile din §1 |

**GitHub Actions** (`.github/workflows/android.yml`, pe `ubuntu-latest`, la fiecare push pe `v2` și la PR):

| Job | Pași |
|---|---|
| `jvm` | testele pure (910 + vectorii `spec/`), `phonesTest` cu modelul din cache |
| `android-unit` | `testDebugUnitTest`, `verifyRoborazziDebug`. Capturile de aur se înregistrează **doar aici** (`recordRoborazziDebug` la cerere), fiindcă fonturile se randează altfel pe Windows |
| `static` | Android Lint, Spotless, Licensee, dependency-guard, `checkPureModules`, `checkModuleGraph`, `lintCopy` |
| `apk` | `assembleRelease` semnat (keystore în secrets, base64), verificarea permisiunilor, `apkSizeGate`, APK-ul și rapoartele ca artefacte |

**Ținte:** testele pure ≤ 60 s pe Windows (azi 18 s); `assembleDebug` incremental ≤ 15 s (azi 9 s); CI complet ≤ 15 minute. **R8** complet în release, cu `consumer-rules.pro` în fiecare modul NDK. **Baseline profile** din `:benchmark`, pe drumul pornire → `PATH` → pasul `Hold` cu fixture, generat la M4.

## 10. Modul „fixture” și `spec/` (A3)

| Port | Reală | Înlocuitori |
|---|---|---|
| `PcmSource` | `OboeSource` | `WavFileSource(realtime)` (JVM și debug), `SilenceSource` |
| `FaceSource` | `CameraFaceSource` | `RecordedFaceSource` (JSONL cu observații), `ImageSequenceSource` (PNG prin modelul real), `FakeFaceSource` (există) |
| Ceas | `SystemClock` | `--es fonia.clock 2026-11-22T10:00` |

Înlocuitorii pentru aplicație stau în `app/src/debug`, deci un build release nu îi conține. Steaguri (extras de intent, ignorate în release):

```
adb shell am start -n tech.fonia.app/.MainActivity \
  --es fonia.fixture.audio fixtures/audio/s_corect_01.wav --es fonia.fixture.face fixtures/face/rotund.jsonl \
  --es fonia.start lesson:s:isolated --ez fonia.reset true --ez fonia.reduceMotion true --ez fonia.latencyOverlay true
```

**Emulatorul:** AVD-ul `sp_d` (Android 36) merge; `Medium_Phone` e stricat **[audit]**. Build cu `-Pfonia.abi=x86_64`. Microfonul virtual e oprit implicit, deci pe emulator rulăm doar în modul „fixture”. Latența, sursa de microfon și camera se verifică numai pe telefonul real.

```
spec/
  README.md            reguli, versiuni, cum rulează fiecare platformă vectorii
  detectors/<sound>/   WAV + valori așteptate cu toleranțe
  session/             vectorii reducerului; plans/ = planuri de lecție de test
  progression/         urcare, coborâre, lacăt
  thresholds/          dsp.json, capture.json, face.json, speech.json
  fusion/<sound>.json  coeficienții fuziunii          unlock.json   deblocarea pe tip de eroare
  poses/<sound>.json   pozele gurii 3D                models/manifest.json
  sql/NNNN_<id>.sql    migrările                      export/       schema exportului v2 + exemplu
  tokens/tokens.json   tokenuri DTCG                  glyphs/allo_outlines.json
  fixtures/audio, fixtures/face
```

**Regula „totul e dată”:** codul din `:engine:*` primește obiecte de configurare încărcate din `spec/` și nu are constante de prag proprii. `SpecRoundTripTest` cere ca `spec/thresholds/dsp.json` să fie egal cu `DSPThresholds.jsonData()` după încărcare și ca fiecare cheie din fișier să fie citită. Versiunea unui fișier este `sha256` scurtat al conținutului lui, calculat la build.

## 11. Unelte și ziua 0 (A4)


```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"                       # [Verificat: există]
$env:Path += ";C:\Users\circi\AppData\Local\Android\Sdk\platform-tools"             # [Verificat: adb.exe există]
cd C:\Users\circi\Desktop\HSIC\fonia-android
git config core.longpaths true ; git tag v1-final ; git switch -c v2
# skill-urile, copiate în repo ca să le vadă worktree-urile și Ștefan
foreach ($s in "android-cli","testing-setup","camerax","litert-runtime","navigation-3","jetpack-compose-performance",
  "edge-to-edge","android-permissions-security","compose-expert","swiftui-pro","fonia-continut-ro","fonia-ml-pe-telefon") {
# Android CLI [Neverificat]
curl.exe -fsSL https://dl.google.com/android/cli/latest/windows_x86_64/install.cmd -o "$env:TEMP\i.cmd" ; & "$env:TEMP\i.cmd"
npx skills add takahirom/roborazzi                                                    # [Neverificat]
# telefonul real
adb devices ; adb shell getprop ro.product.model ; adb shell getprop ro.build.version.sdk
.\gradlew.bat :app:installDebug
# worktree-uri pe căi scurte, pentru subagenți
git worktree add C:\w\e1 -b v2-e1 v2
```


## 12. Conținutul (A5)

- Submodulul `content` e la `68e4689` (seed); `../fonia-content` are cel puțin 5 commituri noi **[audit]**. Aducerea lui e un commit separat: `git -C content fetch`, `git -C content checkout <sha>`, `git add content`, apoi testele `:core:domain`. Vectorii `ios-sessions.txt` sunt identici cu iOS-ul de dinainte de 18 noiembrie; dacă noul conținut îi schimbă, se regenerează în același commit, cu diferența atașată.
- `contentAssets` rămâne singurul drum spre assets și capătă conversia imaginilor în WebP (§6).
- **Schema de conținut v2** (I1) intră printr-un folder nou, `content/json/v2/`, citit de un încărcător separat din `:core:domain`. Încărcătorul v1 rămâne până trec toate cele 9 sunete pe v2. Cheile de sunet nu se schimbă (`SoundId`). Detaliile sunt în capitolul 06.

## 13. Predarea către iOS (J8)

| Etapă | Ce primește Ștefan (prin pull, în `spec/` și `docs/v2/`) |
|---|---|
| M0 | `spec/README.md`, `spec/sql/0001–0012`, `spec/session/` (primii vectori), `spec/thresholds/`, contractele porturilor (`docs/v2/contracts/`: `PcmSource`, `AudioDirector`, `FaceSource`, `ModelHost`, `Judge`), acest capitol |
| M1 | `spec/tokens/tokens.json`, `spec/glyphs/allo_outlines.json` + vectorii interpolatorului, `spec/detectors/s`, `sh`, `spec/progression/`, capturile Roborazzi ca referință vizuală |
| M2 | `spec/detectors/` pentru toate cele 9 sunete, `spec/fusion/`, `spec/unlock.json`, `spec/poses/`, fixture-urile de față |
| M3 | `spec/export/` (v2), vectorii pentru calibrare și pentru raport, textele |
| M4 | bugetele măsurate, `spec/models/manifest.json` final, lista diferențelor cunoscute |
| M6 | răspunsuri la întrebările de portare; vectori noi pentru valul 2 |

**Ordinea de portare:** (1) migrările SQL în GRDB (schema de azi e deja a lui); (2) reducerul, ca `enum` + `switch`, până trec vectorii `spec/session/`; (3) `LiveSnapshot` + triplul buffer peste `FoniaAudioRT`, care e deja același cod C; (4) interpolatorul de contururi cu vectorii lui; (5) fuziunea și deblocarea din JSON; (6) tokenurile și componentele; (7) ecranele, după capturi; (8) gura 3D (motorul mic se traduce în Metal sau rulează ca OpenGL ES). Regula: nimic nu se portează „după cod”, ci după vectori; un comportament fără vector primește întâi vector.

## 14. Bugete și scheletul M0

| Metrică | Buget | Cum se măsoară |
|---|---|---|
| Voce → literă, p95 | ≤ 80 ms (software ≤ 55 ms) | overlay pe telefon + filmarea la 240 fps |
| Analiza pe hop | ≤ 3 ms, p99 ≤ 5 ms | contor în `LivePipeline`, afișat în overlay |
| Eșantioane pierdute | 0 într-o lecție | `Clip.dropped` |
| Verdict acustic după sfârșitul rostirii | ≤ 100 ms | `Log` cu timpii reducerului |
| Verdict cu ZIPA / cu Whisper | ≤ 600 ms / ≤ 2,5 s, altfel `Unsure` | la fel |
| Cadre în `LESSON` | 60 fps, sub 1% lente, niciunul peste 32 ms | JankStats în debug, Macrobenchmark `FrameTimingMetric` |
| Recompuneri din banda continuă | 0 | `LiveLetterRecompositionTest` |
| Pornire la rece | ≤ 1,5 s | Macrobenchmark `StartupTimingMetric` |
| Memorie (PSS) | ≤ 350 MB în lecție, ≤ 450 MB cu Whisper | `adb shell dumpsys meminfo tech.fonia.app` |
| APK arm64 | ≤ 200 MB | `apkSizeGate` |
| Teste pure | ≤ 60 s | Gradle |

**Scheletul M0** (sâmbătă la prânz), bifat în ordine:

- [ ] `v2` există; cele 910 teste trec sub numele noi; CI verde cu `checkPureModules` și verificarea permisiunilor (fără `INTERNET`).
- [ ] `spec/detectors/s/` cu 4 WAV (S corect, Ș, tăcere, zgomot) trece pe JVM prin `LivePipeline`.
- [ ] Microfon la 48 kHz → firul de analiză → `LiveBoard`; overlay-ul arată sursa și p95 pe telefonul real.
- [ ] Un ecran cu `LiveLetter("S")`: se umflă cu `holdSeconds`, se rotunjește cu `sNess`, zero recompuneri.
- [ ] Reducerul cu `Modeling → Listening → Judging → Feedback → Ended` trece primul vector din `spec/session/`.
- [ ] `SaveAttempt` scrie un rând `attempt` cu `verdict`, `audioSource`, `modelVersions`, `thresholdsVersion`.
- [ ] Aceeași felie rulează pe emulator cu `fonia.fixture.audio`, condusă de `mobile-mcp`.
- [ ] `AudioDirectorRulesTest` e verde: nicio redare cu microfonul deschis.
- [ ] Colectorul (B1) folosește aceeași captură și scrie `CaptureInfo` lângă fiecare WAV.

## 15. Pachetele mele

| Pachet | Scop și pași | Fișiere atinse | Gata când | Ore-agent | Depinde de | Riscuri |
|---|---|---|---|---|---|---|
| **A1** ramura și modulele | Tag, ramură, numele noi, `build-logic/`, modulele noi goale, pașii 0–4 din §2 | `settings.gradle.kts`, `build-logic/**`, toate `build.gradle.kts`, `gradle/libs.versions.toml`, `model/`, `live/`, `session/`, `navigation/` | 910 verzi, `assembleDebug`, fișiere de build ≤ 20 de rânduri | 5 | — | o actualizare de versiune rupe build-ul: se ia una câte una |
| **A2** CI și verificări | Task-urile de verificare, cele patru joburi, semnarea, Roborazzi în CI | `.github/workflows/android.yml`, `build-logic/**`, `tools/lint_copy.py` | un `INTERNET` adăugat de probă pică build-ul; APK semnat ca artefact | 4 | A1 | cheia de semnare lipsește din secrets: rămâne semnătura de debug până vine |
| **A3** fixture și `spec/` | Structura `spec/`, `SpecStore`, `WavFileSource`, `RecordedFaceSource`, `LaunchFlags`, `fixtureAssets`, `SpecRoundTripTest` | `spec/**`, `live/`, `app/src/debug/**`, `build-logic/**` | lecția-schelet rulează pe emulator numai din steaguri | 5 | A1 | emulatorul nu pornește: folosim AVD-ul `sp_d` |
| **A5** conținut | Submodulul la zi, vectorii regenerați, WebP, locul schemei v2 | `content` (pointer), `domain/src/test/resources`, `build-logic/**` | testele `:core:domain` verzi pe conținutul nou | 2 (+ cap. 06) | A1 | vectorii se schimbă mult: diferența se citește înainte de commit |
| **E1** captura și banda continuă | `LiveSnapshot`, `LiveBoard`, `LivePipeline`, firul de analiză, `ringWaitRead`, ceasul capturii, `OboeAudioDirector`, ciclul de viață, overlay-ul | `live/**`, `audio/src/main/**` (Kotlin și C++), `design/**/live/` | bugetele din §14 măsurate pe telefon; testele `:platform:audio` (JVM + dispozitiv) verzi | 8 | A1, A3 | telefonul nu are calea rapidă sau `UNPROCESSED`: măsurăm sâmbătă și, dacă trebuie, E2 scurtează fereastra live la 1024 |
| **E9** modelele în APK | `ModelHost`, `ModelRegistry`, `AssetModelFile`, `fetchModels`/`modelAssets`, Whisper cuantizat, scoaterea descărcărilor, politica de memorie | `ml/**`, `whisper/**`, `speech/**` (mutări), `spec/models/manifest.json`, `app/src/main/AndroidManifest.xml` | aplicația judecă în mod avion de la prima pornire; paritatea Whisper păstrată; `apkSizeGate` verde | 7 | A1, A2 | maparea din assets nu merge: copiere la prima pornire; `q5_1` strică paritatea: `q8_0` |
| **F1** reducerul | Tipurile, `reduce`, `SessionStore`, `EffectHandler`, formatul JSON, vectorii din `SessionRunTests`, checkpoint | `session/**`, `spec/session/**`, `feature/lesson/**/LessonEffects.kt` | toți vectorii trec; acoperire de ramuri ≥ 90% pe `reduce`; `SessionRun` șters | 8 | A1, cap. 06 (config) | regulile lecției se schimbă după probe: se schimbă planul (date), nu reducerul |
| **F4** baza v3 | Migratorul pe fișiere, `0007–0012`, înregistrări noi, `RecordingVault`, `KeystoreRecordingKey`, `DataEraser`, `EventLog`, exportul v2 | `store/**`, `spec/sql/**`, `spec/export/**`, `app/**/security/` | o bază v2 de test migrează fără pierderi; `DataEraserTest` și testul instrumentat verzi; `SchemaTests` actualizat | 8 | A1 | o migrare livrată greșit nu se mai poate corecta: fiecare `.sql` se îngheață prin hash în test |
| **G1** cochilia și navigarea | `AppGraph`, `FoniaKey`, `Navigator`, cele trei cochilii, „înapoi”, restaurarea, edge-to-edge, `PermissionGateway` | `app/**`, `navigation/**`, `design/**/shell/` | `NavigatorTest` pe JVM pentru tabelul din §8; un flux Maestro prin toate cochiliile; refuzul microfonului nu blochează drumul | 6 | A1, D1–D2 (cap. 05) | API-ul Navigation 3 diferă de ce presupunem: stiva e a noastră, deci rezerva e un `AnimatedContent` peste aceeași listă |
| **J8** predarea către iOS | Tabelul din §13, `docs/v2/contracts/`, `spec/README.md`, lista diferențelor la fiecare etapă | `spec/README.md`, `docs/v2/**` | Ștefan rulează vectorii `spec/session/` în XCTest fără să citească Kotlin | 4 (+1 pe etapă) | toate | specificația rămâne în urma codului: „gata” la orice pachet cere `spec/` actualizat |

Paralelism: după A1 pot lucra simultan cel mult trei agenți (E1 în `audio` + `live`; F4 în `store`; F1 în `session`), fiecare cu worktree-ul lui. E9 și G1 pornesc după M0.

## 16. Propuneri de schimbare și întrebări deschise

**Propuneri de schimbare (masterul rămâne cum e până se hotărăște):**

1. **`:platform:whisper` ca nume de proiect separat** pentru folderul `whisper` (masterul scrie „`:platform:ml` (+ `whisper`)”). Un proiect Gradle are un singur folder.
2. **Regula „`:core:*` e Kotlin pur” are trei excepții:** `:core:designsystem`, `:core:mouth3d` (Android) și `androidx.sqlite` în `:core:data`. Merită scris în §4 din master.
3. **F8 spune „criptate, ca azi”,** dar azi înregistrările nu sunt criptate de aplicație. Propun criptarea din §7 și formatul PCM în loc de M4A.
4. **Bugetul de 200 MB nu are marjă** (§6). Dacă la M2 iese peste: fie acceptăm ≤ 230 MB, fie construim ORT cu operatori reduși (câteva ore, risc mediu).
5. **„100 de valori pe secundă” cere o schimbare de date:** `live.analysisHopSeconds = 0.01`; codul de azi dă 50.
6. **Fără inel live de 16 kHz în v1** (AD îl propunea): modelele rulează după încercare, pe clip.
7. **Jurnalul pilotului și preferințele intră în baza principală,** ca „șterge tot” să aibă un singur fișier de șters; fără DataStore.
8. **Folderul `features` iese din build la M0** și rămâne doar pe `main` (F7 vorbește doar de folderele care primesc nume noi).

**Întrebări deschise:**

1. Ce model e telefonul de test, declară `UNPROCESSED` și are cale rapidă de captură? De asta depinde tot §4.
2. Ștefan vrea coloanele noi în camelCase (ca schema GRDB de azi) și vectorii în JSON-ul din §5?
3. Putem șterge la migrare înregistrările vechi necriptate, sau există date de test care trebuie păstrate?
4. Există cheia de semnare pentru release, sau livrăm semifinala cu cheia de debug?
5. Valorile lui `breakAtMs`, `listenTimeoutMs` și `maxTries` vin din capitolul 06; până atunci vectorii folosesc valori de probă.
6. Raportul PDF și „trimite înregistrarea” au nevoie de un `FileProvider`; e în regulă ca fișierul temporar în clar să existe câteva secunde în `cacheDir`?
7. Păstrăm `x86_64` în build-urile de debug din CI (plus ~50 MB), sau îl construim doar local, pentru emulator?
