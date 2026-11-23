# Paritate: `:face` (FoniaFace)

Portat din `fonia-app/Packages/FoniaKit/Sources/FoniaFace` (9 fișiere), cu `Tests/FoniaFaceTests/FaceTests.swift`. Specificația: inventarul, §1.8, §7, §11.3 și §13.4.1. Modulul rămâne fără dependențe. Rândurile din „Abateri” au coloanele din `docs/paritate.md`.

## Ce e portat

| iOS | Kotlin (`tech.fonia.face`) |
|---|---|
| `FaceLevels.swift` | `FaceLevels.kt`: `FaceFrame` (§7.3), `FaceLevels` |
| `FaceMapping.swift` | `FaceMapping.kt`: `BlendshapeValues`, `DirectionAngles`, `FaceFrame(blendshapes, faceToCamera, lookAt)`, `MouthLandmarks`, `MouthShape`; tipurile `simd` folosite sunt în `Simd.kt` |
| `FaceSource.swift` | `FaceSource.kt`: `FaceSourceKind`, `FaceSourceError`, `FaceSource` |
| `FakeFaceSource.swift` | `FakeFaceSource.kt`, cu `SPEAKING_SCRIPT` (4 × 200 ms, §7.6) |
| `MirrorInput.swift` | `MirrorInput.kt`, pragul limbii `0.35` |
| `PresenceTracker.swift` | `PresenceTracker.kt`: `PresenceSample`, `PresenceReport`, `romanianCount`, `PresenceTracker`, cu propozițiile românești identice |
| `ARKitFaceSource.swift` (fluxul, fără ARKit) | `CameraFaceSource.kt`: `TrackerFaceSource` (start, permisiune, oprire) și `FaceFrameReceiver` (publicare sub lacăt) |
| `VisionFaceSource.swift` (citirea unui cadru) | `FaceFrame.fromLandmarks(yawRadians, pitchRadians, landmarks)` |
| `MouthCoach.matches` și `MouthCoach.watch` (§7.9.1, §7.9.2) | `FaceChecks.kt`: `FaceCheck` și `FaceHold` |

## Interfața pentru sursa cu cameră

`:features` implementează `FaceTracker` cu CameraX și MediaPipe Face Landmarker și îl dă lui `TrackerFaceSource`:

```kotlin
interface FaceTracker {
    val isSupported: Boolean
    val canDetectTongue: Boolean
    val canEstimateEyeGaze: Boolean
    suspend fun requireCameraPermission()          // aruncă FaceSourceError.CameraDenied
    fun run(sink: FaceObservationSink)             // pornește camera de la zero, livrează pe firul ei
    fun pause()
}
interface FaceObservationSink {
    fun onFrame(observation: FaceObservation?)     // null = nicio față urmărită
    fun onInterrupted()
    fun onFailure(error: Throwable)
}
data class FaceObservation(val blendshapes: BlendshapeValues, val faceToCamera: Float4x4, val lookAt: Float3)
data class BlendshapeValues(val jawOpen: Float, val mouthSmileLeft: Float, val mouthSmileRight: Float,
    val mouthFunnel: Float, val mouthPucker: Float, val cheekPuff: Float, val tongueOut: Float)
```

MediaPipe dă blendshapes cu nume ARKit: `BlendshapeValues.fromScores(map)` le citește după nume (unul lipsă e 0). Matricea de transformare a feței (16 valori pe coloane, `Float4x4.columnMajor`) trebuie adusă în spațiul camerei de portret din iOS: +z de la cameră spre privitor, +y în sus, yaw pozitiv spre dreapta privitorului pe imaginea neoglindită. Testele cu rotații (`BlendshapeMappingTests`) sunt vectorii de verificare. Fără privirea ochilor, `lookAt = (0, 0, 1)`, iar privirea e direcția capului.

## Abateri

| Modul | Ce | De ce | Ce facem în loc |
|---|---|---|---|
| `:face` | Numele urmează convențiile Kotlin: `FaceFrame.ABSENT`, `FakeFaceSource.SPEAKING_SCRIPT`, `FaceSourceKind.ARKIT`, `MirrorInput.TONGUE_OUT_THRESHOLD`; erorile sunt excepții `sealed` (`FaceSourceError.Unsupported`); constructorii cu eșec (`init?`) sunt `invoke` în `companion` și întorc `null` | Kotlin | Aceleași valori și praguri |
| `:face` | Tipurile din `FaceMapping` sunt publice | Pe iOS sursele cu cameră sunt în același modul; pe Android sursa e în `:features` | Maparea rămâne aici, pură și testată |
| `:face` | `FaceLevels` folosește câmpuri `@Volatile` în loc de atomice relaxate pe biți | `AtomicInteger.setOpaque` cere Android 13 (API 33), aplicația pornește de la API 29 | Ordonare puțin mai strictă, aceleași valori; câmpurile rămân independente |
| `:face` | `FakeFaceSource` redă scriptul pe un fir daemon, nu într-un `Task` pe actorul principal | `:face` nu are corutine | Un lacăt comun cu `stop()` păstrează garanția: niciun cadru după oprire. `Duration` este `kotlin.time.Duration` |
| `:face` | `FaceSource` nu poate cere firul principal (`@MainActor`) | Kotlin nu verifică așa ceva | Scris în documentație; `start` e `suspend` |
| `:face` | `makePreferredFaceSource()` nu e aici | Alegerea cere CameraX și MediaPipe | Se scrie în `:features`, lângă sursa cu cameră; `FakeFaceSource` doar pentru emulator fără cameră, ca pe simulator |
| `:face` | `ARKitFaceSource` devine `TrackerFaceSource` peste `FaceTracker`; mesajul de jurnal „Face tracking failed: …” îl scrie tracker-ul | ARKit e doar pe iOS | Aceeași ordine: `isSupported`, `wantsCamera`, permisiunea, renunțarea dacă `stop()` a venit între timp, `attach`, `run` |
| `:face` | Tipurile `simd` sunt înlocuite de `Float2`, `Float3`, `Float4`, `Float4x4` | Kotlin nu are `simd` | Aceeași ordine a operațiilor în Float; `simd` poate fuziona o înmulțire cu o adunare, deci ultimul bit poate diferi |
| `:face` | `PresenceTracker` e clasă, nu `struct` | Kotlin | O copie a trackerului împarte mostrele |
| `:face` | `FaceCheck` e un tip nou: aceleași 11 cazuri și valori brute ca `CoachFaceJudge` (FoniaDomain), cu regulile din `MouthCoach.matches`; `FaceHold` e aritmetica din `MouthCoach.watch` fără ceas (+0,06 s la potrivire, −0,03 s altfel, minim 0,6 s, sondare la 60 ms) | `CoachFaceJudge` stă în `:domain`, iar `:face` nu are dependențe | `:features` trece de la unul la altul cu `FaceCheck.fromRawValue(judge.rawValue)`. Pozițiile desenate (`Viseme`, §7.9.4) sunt date din FoniaDomain și rămân în `:domain` |
| `:face` | Egalitatea `data class` compară `Float` cu `equals` (NaN egal cu NaN, -0.0 diferit de 0.0) | Kotlin | Contează doar pentru NaN și zero negativ |
| `:face` | `tongueOut` nu există în MediaPipe | Modelul nu îl are | `fromScores` dă 0, deci verificarea `.tongueOut` nu trece pe Android; pasul `en-th` rămâne cu „Am făcut” (PLAN, pasul 9) |
| `:face` | Testele care citeau câmpurile cu `Mirror` le citesc prin reflecția Java | Kotlin fără `kotlin-reflect` | Aceeași verificare: niciun câmp de emoție sau zâmbet |

## Testele

| Suită iOS (`FaceTests.swift`) | Teste iOS | Portate | Trec |
|---|---:|---:|---:|
| Blendshape mapping | 8 | 8 | 8 |
| Landmark mapping | 6 | 6 | 6 |
| Levels and mirror | 5 | 5 | 5 |
| Fake source | 4 | 3 | 3 |
| Presence | 11 | 11 | 11 |
| Camera view lifetime (doar iOS) | 3 | 0 | amânate |
| **Total** | **37** | **33** | **33** |

JUnit numără cazurile parametrizate separat (`headYawFromMatrix` 3, `headRollChangesNothing` 3, `plural` 10). În plus, teste noi, care nu sunt de paritate: `CameraSeamTests` (9: fluxul `TrackerFaceSource` și `FaceFrameReceiver`, citirea după nume, matricea pe coloane, calea cu landmarks) și `FaceCheckTests` (7: fiecare prag din §7.9.1, valorile `CoachFaceJudge`, cele 18 verificări din `coach_plans.json`, 17 sondări pentru o secundă, scăderea, podeaua de 0,6 s, deducția din §7.6 că scriptul trece singur doar `.open` și `.lipBite`).

## Amânate pentru pasul de platformă

| Test iOS | De ce | Când |
|---|---|---|
| `FakeSourceTests.simulatorFallback` | Cere simulatorul iOS și clasele ARKit și Vision | testul pentru `makePreferredFaceSource()` din `:features`, pe emulator |
| `CameraViewLifetimeTests.followsTheWindow` | `FaceCameraHostView` și `UIWindow` | vederea camerei în Compose (`:features`): pornește la intrarea în ecran, se oprește la ieșire, test instrumentat |
| `CameraViewLifetimeTests.leavingBeforeTheTaskRuns` | idem | idem |
| `CameraViewLifetimeTests.unsupportedSource` | idem, plus imaginea de rezervă `face.smiling` | idem |
