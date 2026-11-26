# `:features` față de `FoniaFeatures` (iOS)

Tot `Packages/FoniaKit/Sources/FoniaFeatures` e portat în Compose, fișier cu fișier, în `tech.fonia.features`. Prima
jumătate: pornirea și navigarea, primul drum, profilurile, Acasă, ecranul „Hai să exersăm”, lecțiile, jurnalul,
progresul și zona adulților. A doua jumătate: ședința cu toate exercițiile, judecarea încercării și textele
verdictelor, finalul ședinței, „Cum spun?”, antrenorul gurii și „Vorbit în public”. `:app` rămâne un înveliș:
`MainActivity` → `FoniaRoot(intent.extras)`, cu motoarele reale (`:audio`, `:whisper`).

Verificat pe emulatorul `sp_d` (1080 × 2400, 420 dpi): primul drum, Acasă, fiecare ușă, Progres, jurnalul, zona
adulților, drumul adultului, o ședință întreagă din încercările înregistrate, ghidul sunetului, antrenorul gurii și o
prezentare, cu capturile din `screenshots/` puse lângă cadrele iOS din `fonia-app-rezultate/walkthrough*.mp4`.

## Ce e portat

| iOS | Aici | Ce conține |
|---|---|---|
| `App/RootView.swift` | `FoniaRoot.kt` | pornirea (Foni pe nor), primul drum, Acasă sau „Cine exersează”, coperta ședinței, foaia „Setări” |
| `App/AppModel.swift` | `app/AppModel.kt`, `app/SpeechHub.kt` | `AppRoute`, `SessionTicket`, `AppModel`, `SpeechHub`; plus `LaunchArguments` și `FoniaPlatform` |
| `App/AudioHub.swift` | `app/AudioHub.kt`, `app/AudioPorts.kt`, `app/LiveAudio.kt`, `app/FakeAudio.kt`, `app/MicrophonePermission.kt` | `AudioHub` peste motoarele din `:audio` |
| `App/Bridges.swift` | `app/Bridges.kt` | aceleași punți între `:domain`, `:store`, `:dsp` și `:design` |
| `App/ClipDecoder.swift` | `app/ClipDecoder.kt` | peste `AudioDecoder.decodeMono` din `:audio` |
| `App/PageChrome.swift` | `app/PageChrome.kt` | `PageHeader`, `HeaderPage`, `ScenePage`, `ScenePanel`, butoanele din antet |
| (SwiftUI) | `app/Navigation.kt`, `app/Presentation.kt`, `app/SystemList.kt`, `app/Preferences.kt`, `app/Tasks.kt` | `NavigationStack` cu zoom, `.sheet`, `.fullScreenCover`, `.alert`, `.confirmationDialog`, `List(.insetGrouped)`, `Toggle`, `@AppStorage`, `.task`, `ProgressView()` |
| `FoniaFace/FaceSource.swift` (`makePreferredFaceSource`), `FaceCameraView.swift` | `app/FaceSources.kt` | locul unde intră camera (vezi mai jos) |
| `Home/*.swift` (6) | `home/MainTabs.kt`, `HomeScreen.kt`, `PathModel.kt`, `Rows.kt`, `StartSheet.kt`, `WhoView.kt` | |
| `Setup/FirstRunFlow.swift` | `setup/FirstRunFlow.kt` | |
| `Lessons/LessonsScreen.swift` | `lessons/LessonsScreen.kt` | |
| `Journal/JournalScreen.swift` | `journal/JournalScreen.kt` | |
| `Progress/ProgressScreen.swift` | `progress/ProgressScreen.kt` | |
| `Grownups/*.swift` (3) | `grownups/Grownups.kt`, `ParentsScreen.kt`, `Calibration.kt` | |
| `Session/SessionScreen.swift` | `session/SessionScreen.kt`, `session/Recap.kt` | ecranul ședinței, dialogul de ieșire; finalul ședinței e `Recap`, iar vechiul `EndScreen`, pe care nu-l mai deschidea nimic, s-a șters |
| `Session/SessionRun.swift` | `session/SessionRun.kt`, `session/SessionKeys.kt` | starea ședinței, regulile blocurilor și ale dozei, salvarea; `isVerdict`, `soundID`, `key`, `Settings` |
| `Session/ExerciseHost.swift` | `session/ExerciseHost.kt`, `session/MouthCard.kt` | `ExerciseHost`, `IntroExercise`, `HowExercise`, `WarmupExercise`, `MissionExercise`, `MouthCard`, `StepList`, `talkDriver` |
| `Session/ListenExercise.swift` | `session/ListenExercise.kt` | |
| `Session/SpeakExercise.swift` | `session/SpeakExercise.kt` | `SpeakExercise`, `ListeningBar`, `ResultPanel`, `LiveLevels.heardColour`, `Prosody.cue`; `FittingRow`, nefolosit, s-a șters |
| `Session/PromptView.swift` | `session/PromptView.kt` | |
| `Session/SustainExercise.swift` | `session/SustainExercise.kt` | cu `HeldTime` |
| `Session/TakeJudge.swift` | `session/TakeJudge.kt` | |
| `Session/CaptureLog.swift` | `session/CaptureLog.kt` | ce se ține despre captura fiecărei încercări în `detail.capture`: microfonul, rata, pragurile DSP, modelul, versiunea, dinții din față |
| `Session/FrontTeeth.swift` | `session/FrontTeeth.kt` | „Îi cad dinții din față”, pe copil; `areOut` primește și o funcție de citire, pentru testele JVM |
| `Session/Playback.swift` | `session/Playback.kt` | ce redă finalul blocului amestecat, rândurile lui și cele trei încercări alese |
| `Session/PlaybackBeat.swift` | `session/PlaybackBeat.kt` | ecranul dintre încercări; cu TalkBack așteaptă o atingere, rândul e o regiune live politicoasă |
| `Home/DailyRounds.swift` | `home/DailyRounds.kt` | a doua rundă la 4–5 ani, din ședințele încheiate în ziua locală |
| `Grownups/ChildDayRows.swift` | `grownups/SettingsPage.kt` (`Practice`) | cele două comutatoare ale copilului, în „Exersarea”, sub fișele pentru adult |
| `App/AdultGate.swift`, `Grownups/AdultGateView.swift` | `app/AdultGate.kt`, `app/AdultGateScreen.kt` | înmulțirea cu patru răspunsuri, pauza după două greșeli |
| `Grownups/SettingsPage.swift` | `grownups/SettingsPage.kt`, `ReminderSettings.kt` | setările pe grupuri; rândurile doar de Android (pachetul de vorbire, jurnalul, ZIPA, calibrarea) stau în grupul lor |
| `Progress/TallyScreen.swift` | `progress/TallyScreen.kt` | zilele și stelele: comutatorul, calendarul lunii, pragurile, peretele de stele; pop, spray și luciul din Pow desenate în Compose |
| `Probe/ProbePlan.swift`, `ProbeStore.swift`, `ProbeScreens.swift` | `probe/ProbePlan.kt`, `probe/ProbeStore.kt`, `probe/ProbeScreens.kt` | „Cuvinte noi”, ciornă: listele în `assets/drafts/probes.json` (copie octet cu octet), proba ca `CustomRun.Kind.Probe` |
| `Session/Verdict.swift` | `session/Verdict.kt` | `TakeVerdict`, `Praise`, `VerdictWriter`, `Rating.attemptOutcome`, `Rating.effect` |
| `Guide/SoundGuide.swift` | `guide/SoundGuide.kt` | |
| `Guide/MouthCoach.swift` | `guide/MouthCoach.kt` | `MouthCoach` și `MouthDiagram` |
| `Guide/MouthGallery.swift` | în `:design` (`DesignGallery`, prima grilă a paginii `guri`) | se deschide cu `foniaMouths` sau `foniaGallery=guri` |
| `Speaking/SpeakingCoach.swift` | `speaking/SpeakingHome.kt` | `SpeakingHome`, `MyTalkEditor`, `TalkScreen`, `TalkFocus`, `TalkReport`, `SpeechPrompt.shortDuration` |

Ce s-a păstrat exact: textele (copiate din Swift, cu Ș și Ț cu virgulă; `tools/lint_copy.py` găsește 0 probleme),
ordinea pașilor, regulile din `PathModel` și din `SessionRun` (doza, marginea blocului, misiunea care primește mereu
rândul, o singură încheiere pe exercițiu, reluarea care intră în doză dar nu în bloc, treapta mutată o singură dată pe
ședință, nota blocului tăcut, recapitularea care nu mută nimic), pragurile din `TakeJudge` și din `TalkReport`,
temporizările (sfârșitul încercării sondat la 50 ms cu 0,7 / 1,2 / 1,8 / 2,5 s de liniște și 5…45 s, sunetul ținut
numărat în pași de 40 ms, fața în pași de 60 ms cu +0,06 / −0,03, trecerile automate la 850 ms,
`max(1300 ms − vorbit, 350 ms)`, 900 ms, 650 ms, 450/600 ms, nota blocului 2,4 s, diagrama 450 ms) și regulile
copil/adult (inventar §2.31). Răspunsul e doar KR: „bine”, „aproape”, „încă nu” sau că nu s-a auzit; niciodată
„greșit”, niciodată roșu.

## Fluxurile exercițiilor

Pe iOS fiecare exercițiu își duce tot fluxul într-un `.task` legat de identitatea vederii
(`.id("\(index)-\(retryToken)")`). Aici exercițiul curent stă într-un `AnimatedContent` cu cheia (index, reluare), iar
fluxul rulează în `ExerciseTask`, un `LaunchedEffect` pe firul principal (`ViewTask`, ca `@MainActor`). Exercițiul
care pleacă rămâne pe ecran cât durează animația de ieșire, dar fluxul lui se oprește din prima clipă
(`LocalExerciseActive`), deci nicio trecere automată nu vine de la un exercițiu care nu mai e cel curent. Paza
`trialSettled` și `isClosed` din `SessionRun` sunt aceleași. Ce pornește dintr-un buton (`Task { }` pe iOS) rulează în
`app.scope`, cât trăiește aplicația. Ca pe iOS, doar un exercițiu nou alunecă (arcul de 0,28 s pe `index`); reluarea
aceluiași exercițiu îl înlocuiește pe loc.

Salvările din `SessionRun` (încercarea, înregistrarea M4A, `SoundProgress`, sfârșitul ședinței) rulează în ordinea în
care au fost cerute, printr-un lacăt corect, cum pornesc pe iOS sarcinile de pe actorul principal; scrierea M4A
(`RecordingWriter.writeM4A`) rulează în afara firului principal, ca `Task.detached(priority: .utility)`.

## Motoarele

`FoniaPlatform()` dă motoarele reale, iar aplicația nu cere altceva:

| Ce | Aici |
|---|---|
| Sunetul | `LiveAudioBackend` (`app/LiveAudio.kt`): `AudioSessionController`, `PlaybackEngine`, `SoundEffects(voice/sfx)` (fără vocea telefonului: un text fără clip rămâne tăcut, spre deosebire de iOS) și, pentru fiecare încercare, un `CaptureEngine` nou peste `MicrophoneSource`, sau peste `FixtureSource(clip, RealTime)` când `foniaFixtures` cere încercări înregistrate (`FixtureMode.captureSource`: clipul textului, apoi „soare”, apoi microfonul). Restul lanțului e același: pași de 10 ms, analiza live, sfârșitul încercării, judecata, salvarea |
| Analiza live | `AudioHub` face câte un `LiveAnalyzer` pentru fiecare încercare, cu rata reală a sursei, și publică în `LiveLevels` și `SpeechGate` la fiecare pas de 10 ms (`AnalyzerProcessor`, fără alocări) |
| S/Ș pe microfoane înguste | sub 32 kHz analizoarele opresc singure S/Ș, ca pe iOS; în plus, când `MicrophoneSource.captureInfo.isNarrowBand` (căști Bluetooth SCO sau LE, unde banda se oprește pe la 8 kHz chiar și la 48 kHz), `AudioHub` dă analizei live și judecății `DSPThresholds.v1` cu `sibilant.minSampleRateHz = ∞`, deci verdictul e „Nu aud bine sunetul” cu sfatul despre căști, ca pentru o bandă îngustă pe iOS |
| Vorbirea | `StoreSpeechModel(FoniaWhisper.modelStore(context), WhisperCppModel.megabytes())`: descărcarea HTTPS doar pe Wi-Fi, whisper.cpp, aceleași stări în `SpeechHub`. Modelul se încarcă la prima cerere, iar `TakeJudge` și `TalkReport` îl rulează în afara firului principal |
| Fața | vezi mai jos |

Înlocuitorii rămân doar pentru teste: `NoAudioBackend` în testele JVM (nu se aude nimic și nicio captură nu pornește),
`FakeSpeechModel` cu `foniaFakeSpeech`. `FixtureAudioBackend` (tăcerea care ținea locul lui `:audio`) s-a șters: modul
cu încercări înregistrate trece acum prin motoarele reale, ca în simulatorul iOS.

### Unde intră camera

`app/FaceSources.kt`, obiectul `FaceCamera`, e singurul loc. Pe ramura asta fiecare telefon primește gura scriptată
(`FakeFaceSource`, ca simulatorul iOS) și vederea ei de rezervă (iconița pe fondul secundar, ca `face.smiling`).
Cu `:facecamera` în dependențe, fiecare din cele două corpuri devine un singur apel:

```kotlin
fun source(context: Context): FaceSource = tech.fonia.facecamera.makePreferredFaceSource(context)
fun View(source, levels, modifier, onFailure) = tech.fonia.facecamera.FaceCameraView(source, levels, modifier, onFailure)
```

`MouthCoach` cere sursa prin `FoniaPlatform.face` (implicit `makePreferredFaceSource(context, arguments)`, care
întâi se uită la `foniaFace`) și arată camera prin `FaceCamera.View`. Verificările feței sunt `FaceCheck` și `FaceHold`
din `:face` (aceleași praguri și aceeași aritmetică, sondare la 60 ms).

## Argumentele de pornire

Pe iOS sunt argumente de linie de comandă; aici sunt extras în intent, citite de `FoniaRoot`
(`adb shell am start -n tech.fonia.app/.MainActivity --ez foniaResetData true`), sau, ca pe iOS, o listă de
șiruri în extra-ul `arguments`. Toate, în afară de `foniaFixtures`, merg doar în build-ul de depanare.

| Extra | Ce face |
|---|---|
| `foniaResetData` | șterge profilurile la pornire |
| `foniaFixtures` | încercările vin din clipurile înregistrate (pe iOS, mereu în simulator) |
| `foniaStart` (șir) | deschide o ședință: `s:wordsInitial`, `en:th`, `melody`, `dictie:pitch` |
| `foniaSpeakOnly`, `foniaShort` | ședința păstrează doar exercițiile vorbite; doar primele două, cu doza 2 |
| `foniaMouths`, `foniaGallery` (șir) | galeria din `:design` în locul aplicației (`guri`, `componente`, `acasa`, `lectia`, `foni`, `scene`) |
| `foniaRoute` (șir) | doar Android: deschide o pagină la pornire (`sounds`, `warmup`, `play`, `guides`, `stories`, `english`, `speaking`, `journal`, `settings`, `unit:<id>`, `guide:s`, `mirror:s`, `coach:<cheie>`) |
| `foniaFace` (șir) | doar Android: antrenorul gurii vede fața dată, nemișcată (`smile=0.8,jawOpen=0.1`; `jawOpen`, `smile`, `funnel`, `pucker`, `puff`, `tongueOut`) |
| `foniaFakeSpeech` | doar Android: modelul de vorbire înlocuit cu `FakeSpeechModel` |
| `foniaReduceMotion` | doar Android, pentru testele UI: fără animațiile care nu se opresc (Foni, cerul) și cu contoarele vocii nemișcate |

## Diferențe care rămân, și de ce

| Ce | Pe iOS | Aici | De ce |
|---|---|---|---|
| Stiva de pagini | `NavigationStack(path:)` cu `navigationDestination` | `NavStack` și `NavigationHost` proprii: o stivă, starea fiecărei pagini salvată până iese din stivă | tranzițiile se aleg exact ca pe iOS (zoom doar din ușile de pe rădăcină, împingere în rest, gestul de înapoi) fără rute serializabile; dependența `navigation-compose` a fost scoasă |
| Zoomul din ușă | `.navigationTransition(.zoom)` cu `matchedTransitionSource` | `SharedTransitionLayout` și `sharedBounds`, colțuri continue de 18 dp | aceeași mișcare; arcul e cel implicit din Compose |
| Înapoi | butonul „Înapoi” și glisarea de la marginea stângă | butonul „Înapoi” și gestul de înapoi din Android, cu previzualizare | Android are un singur gest, de pe ambele margini |
| Gestul de înapoi peste ce e deasupra | foile se trag în jos; coperțile (ședința, prezentarea) nu au gest | gestul închide foile și alertele; în ședință e X-ul (pleacă direct sau întreabă „Te oprești aici?”), pe final e „Gata pe azi”; în prezentare e X-ul, iar cât vorbești e „Am terminat”; cât ascultă înapoi nu face nimic | pe Android înapoi închide ce e deasupra |
| Foile, alertele, `confirmationDialog` | ale sistemului | `FoniaSheet`, `FoniaAlert`, `FoniaConfirmationDialog`, desenate după iOS 26 | componentele Material arată altfel |
| `ProgressView()`, `ProgressView(value:)`, `Stepper`, `TextField` | ale sistemului | `ActivityIndicator` (opt spițe care se rotesc), bara liniară gri cu verde, capsula − / + și un câmp alb cu colțuri de 14 | aceeași formă ca pe iOS |
| Vibrațiile | `.selection`, `.success`, `.impact(weight: .light)` | `SegmentTick`, `Confirm`, `ContextClick` | Taptic Engine nu există; efectul variază după telefon |
| Mărimile de accesibilitate | la `typeSize.isAccessibilitySize` exercițiul derulează | de la scara fontului 1,5 | Android are o singură scară |
| `.contentTransition(.numericText())` pe ceasul prezentării | cifrele se rostogolesc | cifrele se schimbă pe loc | nu e o componentă publică în `:design` |
| Ziua subiectului „Un minut pe zi” | `Calendar.current.ordinality(of: .day, in: .era, for: .now)` | ziua epocii + 719 163 (calendarul gregorian proleptic, ca Foundation; pe 5 noiembrie 2026 iese „Prezintă-te”, ca în cadrul iOS) | |
| Formatul ritmului | `.number.precision(.fractionLength(1)).locale(ro_RO)` | `romanianDecimal`: zecimala cea mai scurtă rotunjită la par, cu virgulă | Java rotunjește altfel la jumătate |
| „Configurări” | „Pornește-l din Configurări, la Fonia.” (și camera) | „Pornește-l din Setări, la Fonia.” | aplicația de setări se numește „Setări” pe Android |
| Mărimea pachetului de vorbire | „147 MB” (Core ML) | `WhisperCppModel.megabytes()`, adică 151 MB (greutățile ggml și tokenizerul) | e ce se descarcă de fapt |
| Mulțumirile | „Whisper și WhisperKit (MIT). Date: GRDB (MIT).” | „Whisper și whisper.cpp (MIT). Date: SQLite (domeniu public) și AndroidX SQLite (Apache 2.0).” | bibliotecile Android; restul listei (AndroidSVG, Oboe, Media3, LiteRT, toate Apache 2.0) trebuie verificat înainte de livrare |
| Clipurile în loc de microfon | mereu în simulator | doar cu `foniaFixtures` | un emulator poate avea microfon și nu se poate recunoaște sigur |
| Decodarea clipurilor | `AVAudioFile`, canalul 0 | `AudioDecoder.decodeMono`, canalele mediate | toate clipurile și încercările sunt mono, deci eșantioanele sunt aceleași |
| Camera | ARKit sau Vision | gura scriptată pe orice telefon, până intră `:facecamera` (vezi mai sus); `tongueOut` nu există în modelele Android, deci pasul `en-th` trece doar cu „Am făcut” | |
| S/Ș pe căști | doar banda măsurată din clip | și dispozitivul numit de `captureInfo` | Android îl știe dinainte |
| Reduce Motion | setarea de accesibilitate | „Elimină animațiile” (scara animatorului 0), plus `foniaReduceMotion` | Android nu are altă setare |
| Microfonul din jurnalul de captare | primul port de intrare din ruta `AVAudioSession` | `AudioDeviceInfo.type` al microfonului ales (`MicrophoneSource.microphone`); `dsp` e eticheta pragurilor folosite de fapt | Android nu are rute; un microfon Bluetooth îngust apare ca `v1-noSibilants`, ca în jurnalul pilot |
| A doua rundă pe Acasă | propoziția și butonul cardului de azi („Încă o rundă mică?”, „Încă o rundă”) | doar rândul de sub titlul cardului („Încă o rundă mică?”, „Ai exersat de două ori azi. Bravo!”) | cardul de pe Android n-are propoziție și buton propriu; lecția pornește din butonul galben din bară |
| Foni la o încercare bună | un salt (`jump(14)`) | săritura dublă din `foniReaction` | rămâne cea de pe Android |
| Luciul pe „Gata” la final | „Gata” e butonul principal | „Gata” e butonul alb secundar, deci luciul abia se vede | butonul era așa înainte de tura a zecea |
| Fișierul probelor „Cuvinte noi” | `Application Support/Fonia/Probes/<profil>.json`, exclus din backup | `noBackupFilesDir/Fonia/Probes/<profil>.json` | Android nu are `Application Support`; dosarul fără backup ține locul excluderii |
| Vocea la probă | modelul și rândul de început din clip, altfel vocea telefonului | doar din clip; fără clip, modelul și rândul de început tac | Android n-are voce a telefonului în lecție; clipurile se generează după aprobarea listelor |
| Ciornele în release | doar `draftsApproved` | și cu `foniaDrafts` dat la pornire, din adb (`LaunchArguments.drafts`) | regula de dinainte pentru toate ciornele Android (a500252); din lansator rămân ascunse |
| Butoanele din „Cum spun?” | `Label { Text(…) } icon: { … }` în a treia parte a rândului | textul stă pe un rând și se micșorează până la 0,7 | pe 411 dp Compose rupea „Ascultă” și „Încearcă” în mijlocul cuvântului |

## Testele

- `PathModelTests.swift`: 10 din 10; `SessionRunTests.swift`: 11 din 11 (suitele „SessionRun” și „Calibration
  bridge”). Pe JVM, cu baza de date în memorie și conținutul din `content/`.
- `ArtTests.swift`: `everyLessonHasItsPicture` aici; celelalte 5 sunt în `:design`.
- În plus, verificări doar pentru portare (`SessionPortTests`, 6): ziua subiectului, formatul românesc, fereastra de
  ritm, frazele, oprirea S/Ș pe căști și câteva texte de verdict.
- `FoniaUITests.swift`: 9 din 9, ca teste Compose în `app/src/androidTest` (aceleași etichete, căutate ca în
  XCUITest: descrierea de conținut sau textele butonului unite cu „, ”).
- Drumuri noi, fără pereche pe iOS (`SecondHalfJourneys`, 5): o ședință întreagă din încercările înregistrate până la
  „Gata pe azi”, ieșirea care întreabă după prima încercare numărată, ghidul sunetului până la oglindă, antrenorul
  gurii cu o față scriptată până la „Așa se spune S”, o prezentare până la raport și „mai scurt”.

```bash
./gradlew :features:testDebugUnitTest            # 28 de teste, câteva secunde
./gradlew :app:connectedDebugAndroidTest         # 14 drumuri pe sp_d
```

## Biblioteci

Adăugat: `implementation(project(":whisper"))` în `:features` (motorul din spatele `SpeechHub`, ca WhisperKit în spatele
FoniaSpeech). Nicio bibliotecă nouă din afară. Scoasă mai devreme: `libs.androidx.navigation.compose`. Doar în
classpath-ul testelor JVM (driverul SQLite pentru desktop, pe care rulează și testele din `:store`):
`androidx.sqlite:sqlite-bundled-jvm:2.7.1`, `androidx.sqlite:sqlite-jvm:2.7.1`.

## Emulatorul

`sp_d` are în jur de 600 MB liberi, iar APK-ul de depanare are 131 MB. Cu modelul Whisper copiat și în datele
aplicației (150 MB), instalarea dă `INSTALL_FAILED_INSUFFICIENT_STORAGE`; atunci șterge copia
(`run-as tech.fonia.app rm -r no_backup/FoniaSpeech/openai_whisper-base`) sau dezinstalează aplicația. Pentru Whisper
în aplicație: modelul pus cu `adb push` în `/data/local/tmp/fonia/openai_whisper-base/`, apoi
`run-as tech.fonia.app cp` în `no_backup/FoniaSpeech/openai_whisper-base/`. Pe emulatorul x86_64 o transcriere durează
cam 20 s, deci raportul prezentării și încercările în engleză așteaptă atât. `Medium_Phone` nu pornește (vezi
`design/PARITY.md`).

## Capturile

`screenshots/` (540 × 1200, 256 de culori), prima jumătate: `01-first-run-voice` … `35-settings-adult`, plus
`ios-vs-android-home`.

`screenshots/session/`, a doua jumătate, de pe `sp_d` cu `foniaFixtures` (încercările vin din clipurile înregistrate și
trec prin motoarele reale), în afară de `10`, unde ascultă microfonul emulatorului, care tace:

| Fișiere | Ce arată |
|---|---|
| `01`–`05` | ședința „Facem cunoștință”: sunetul, „Cum spun S?” din față și din lateral, „Ce ai auzit?” înainte și după alegere |
| `06`–`10` | silabele: modelul, „Acum tu” cu nivelul vocii, „Da!” (trece singur mai departe), „Aproape. A sunat ca Ș” cu sfatul, „Nu te-am auzit” |
| `11`–`15` | „Ține Z cât poți”, încălzirea, „Te oprești aici?”, finalul cu stea și „Ne vedem mâine” (limita de timp a ședinței) |
| `16`–`18` | ghidul „Cum spun S?”: pasul 1 din față, pasul 3 din lateral, „Dacă nu iese” și „Pentru adult” |
| `19`–`24` | antrenorul gurii cu `foniaFace=smile=0.8,jawOpen=0.1`: buzele și dinții trec singuri, limba cere „Am făcut”, aerul e ascultat la microfon, apoi „Așa se spune S” |
| `25`–`33` | adultul: Acasă, „Vorbit în public”, exercițiile scurte, „Prezentarea mea”, o prezentare (gata, vorbește, ascult, „Cum ți s-a părut?”, raportul). Profilul e pe engleză, ca pe iOS, deci Whisper a transcris în engleză clipul românesc |
| `ios-vs-android-*` | cadrul iOS din `walkthrough.mp4` (stânga) lângă Android: `intro`, `how`, `listen`, `warmup`, `speaking` |

Înregistrările iOS nu au ghidul, antrenorul, finalurile sau o prezentare; acolo comparația s-a făcut cu codul Swift.

## Ce nu e pe iOS: fundația ML (PLAN §6, §7.1 P6 și P13)

Adăugiri, nu diferențe de port. Ecranele iOS rămân cum sunt, cu câteva rânduri în plus:

| Ce | Unde | Ce se vede |
|---|---|---|
| Managerul de modele: ZIPA-small int8 din `phones/MODELS.md` (71 MB, CC BY 4.0), doar pe rețele fără cost, cu mărimea și SHA-256 fixate, reluabil, în `noBackupFilesDir/FoniaModels` | `ml/ModelManager.kt`, `ml/MlHub.kt` | „Setări” → „Ascultare pe sunete, în probă”: „Descarcă pachetul (71 MB)” sau „Șterge pachetul” |
| Shadow mode: după TakeJudge, aceeași încercare e scorată cu `PhoneScorer` pe un fir al ei, iar verdictul copilului rămâne al lui TakeJudge | `ml/ShadowMode.kt`, `ml/ShadowHooks.kt`, apelate din `SpeakExercise` și `MouthCoach` | nimic; în debug, cu „Acordul cu ZIPA în lecție”, o etichetă sus în lecție (`session/ShadowBadge.kt`) |
| Jurnalul pilotului (03 §4.1): fișierul SQLite `noBackupFilesDir/FoniaPilot/pilot.sqlite`, fără audio și fără text liber; detaliile 90 de zile, apoi doar totaluri pe zi; se șterge cu profilul și cu „Șterge tot” | `pilot/PilotLog.kt`, `pilot/PilotStore.kt`, apelate din `SessionRun` și `AppModel` | „Ce păstrăm pe telefon” → „Jurnalul de exersare” |
| Contorul de doză (P6) și săptămâna intensivă (primele 5 zile cu un sunet) | `pilot/PilotSummary.kt`, `grownups/PilotViews.kt` | „Cum merge” → „Încercări pe zi și pe săptămână” |
| Rezumatul pentru logoped (03 §4.3): JSON agregat, sub pseudonim, ca al doilea fișier lângă exportul iOS, care rămâne neschimbat | `pilot/PilotExport.kt`, `ProfileExport.share` | „Trimite fișierul la logoped” trimite două fișiere |

ONNX Runtime 1.31.1 (`onnxruntime-android`) intră în `:app`, doar pentru arm64-v8a și x86_64. Furnizorul lui de
telemetrie (Microsoft 1DS, pornit la fiecare deschidere a aplicației) e scos din manifest; `checkDebugOnnxRuntime`
oprește build-ul dacă revine sau dacă apare a doua copie a runtime-ului. Verificarea pe emulator:
`./gradlew :app:zipaModelToDevice`, apoi `ShadowSessionTests` (o ședință întreagă cu ZIPA, eticheta, jurnalul, „Cum
merge” și „Setări”).

## Ce nu e pe iOS: emfaza (V2) și simularea BAC oral (V6) (PLAN §7.3)

Adăugiri, nu diferențe de port. „Vorbit în public” are o secțiune nouă, „Citește cu voce tare”, cu două rânduri
(`speaking/SpeakingExtras.kt`; în `SpeakingHome` sunt trei apeluri). Totul rulează pe telefon, și pentru minori.

| Ce | Unde | Cum |
|---|---|---|
| „Cuvântul important” (V2, H F2) | `speaking/emphasis/` | o întrebare, apoi propoziția de citit cu cuvântul marcat; Whisper forțat pe propoziție (`scoreSentence`) dă locul fiecărui cuvânt, `analyzeEmphasis` din `:dsp` măsoară vocala cea mai tare a fiecărui cuvânt față de vecini (±2, fără celelalte cuvinte-cheie). Accentuat = 2 din 3: +30 % durată, +3 dB, +2 semitonuri; durata și tăria cântăresc ×1,5 în proeminența continuă. Răspunsul numește indicele măsurat („a fost mai lung și mai tare”), cu valorile față de vecini; la final: cuvinte marcate ieșite în față, reperul de 80 %, „totul accentuat” peste 40 % (de la 8 cuvinte în sus) |
| Simularea BAC oral, proba A (V6, H F7) | `speaking/bac/` | bilet aleator (altul decât ultimul) → pregătire 5/10/15/30 min (implicit 30), cu cuvintele-cheie marcate de elev → citire → cerințele 1 și 2 → opinia. Raportul are doar indicatori, fiecare cu reperul lui: cuvinte citite ca în text (transcrierea aliniată pe text; propozițiile cu diferențe sunt tăiate din înregistrare și forțate pe textul lor; peste `rescueLogp` = scăparea recunoașterii, sub = citit altfel, inclusiv o terminație schimbată), silabe pe secundă, pauze la punctuație (≥ 250 ms, reper 80 %) și în afara ei (reper ≤ 2/min), PVQ (reper 0,15), cuvintele-cheie (reper 80 %), „ăă” la 100 de cuvinte, argumentele numărate după conectori (reguli simple, elevul poate corecta numărul). Fără scor, fără nivel, fără notă estimată; scrie că nota o dau profesorii. Nivelurile apar doar ca descriptorii publicați („ce urmăresc profesorii”) |
| Conținutul | `assets/drafts/emphasis.json`, `assets/drafts/bac_oral.json` | ciorne scrise pentru Fonia: 16 propoziții în română și 10 în engleză, 6 bilete (2 literare, 4 nonliterare, 107–122 de cuvinte, fără cifre); așteaptă un profesor de română (și de engleză) |
| Salvarea | `SpeechRecord` din `:store`, schema neschimbată | doar indicatorii, fără transcriere: „Cuvântul important”, „Simulare BAC oral, biletul N” |

Testele: 40 pe JVM (`speaking/*Tests`): textul marcat, conținutul (regulile din `tools/lint_copy.py`, fără „;”), regula
pe vorbire sintetică cu timpi exacți (`src/sharedTest`: `SyntheticSpeech`, `ScriptedTranscriber`) și pe clipurile
înregistrate din `whisper/src/androidTest/assets/speech` cu timpii dați de Whisper pe emulator
(`whisper/eval_speech/results.jsonl`), textele răspunsului (fără stări deduse, AI Act art. 5(1)(f)), raportul BAC întreg.
Pe `sp_d`, `SpeakingMlTest` (3): pagina „Vorbit în public” până la BAC, o simulare întreagă până la raport și două
propoziții de emfază; captura e scriptată, recunoașterea la fel, analiza rulează de-adevăratelea. Capturile sunt în
`screenshots/speaking-ml/`.

```bash
./gradlew :features:assembleDebugAndroidTest -Pfonia.deviceTestAbi=x86_64
adb install -r -t features/build/outputs/apk/androidTest/debug/features-debug-androidTest.apk
adb shell am instrument -w -e class tech.fonia.features.speaking.SpeakingMlTest tech.fonia.features.test/androidx.test.runner.AndroidJUnitRunner
```

De știut: pragurile sunt de pornire ([S]), necalibrate pe vorbitori români; fără modelul Whisper testele JVM nu pot
rula forțarea pe clipurile înregistrate, deci acolo acuratețea e cea din transcriere (25 din 29 la `ro-speech`).
