# `:design` față de `FoniaDesign` (iOS): ce a rămas diferit

Portarea urmează fișier cu fișier `Packages/FoniaKit/Sources/FoniaDesign` (14 fișiere Swift → 14 fișiere Kotlin cu
același nume, în `tech.fonia.design`), plus cinci fișiere de sprijin: `Geometry.kt` (`CGPoint`, `CGRect`,
`CGAffineTransform`, `CGPath` cu marginea strânsă a lui `Path.boundingRect`, colțurile „continue”), `Animation.kt`
(springurile SwiftUI și `AnimatedVector`), `Painting.kt` (pensule, gradiente, blur), `Theme.kt` (`FoniaTheme`,
`FoniaText`, mediul SwiftUI ca `CompositionLocal`) și `FoniaAssets.kt` (citirea `images/` și `fonts/` din APK, cu cache).
Din tura a zecea mai sunt două: `Press.kt` (apăsarea ca pe iOS: coborâre în 50 ms, revenire cu spring în 0,3 s,
vibrația la apăsare, `.pressable`, `.rowPress`, filtrul de luminozitate) și `Effects.kt` (efectele Pow refăcute în
Compose din matematica lor: luciu, săritură, ping, `PopIn`, `Spray`, `scrollSettle`, `NumericText`).

Verificat pe emulator (1080 × 2400, 420 dpi) cu galeria de depanare, pagină lângă pagină cu cadrele din
`fonia-app-rezultate/walkthrough*.mp4`: Acasă (cadrul de la 0:55), „Cum spun S?” (2:45 din `walkthrough-mic`),
pastilele, bara cu butonul galben. Gura S: buzele ies 197,7 dp late și stau la 0,549 din înălțimea pielii;
pe iPhone 191 pt (video estompat) și 0,558. Galeria: `adb shell am start -n
tech.fonia.app/tech.fonia.design.DesignGalleryActivity --es section guri` (`componente`, `acasa`, `lectia`,
`foni`, `guri`, `scene`). Capturile sunt în `screenshots/`.

## Diferențe care rămân, și de ce

| Ce | Pe iOS | Aici | De ce |
|---|---|---|---|
| Mărimea textului | `Font.custom(_:size:relativeTo:)` urmează curba Dynamic Type a fiecărui stil; `@ScaledMetric` la fel | toate mărimile sunt în sp și cresc cu scara unică a fontului din sistem; `relativeTo` e păstrat în semnături, dar nu schimbă nimic; iconițele și `ArtTile` cresc cu `fontScale` | Android are o singură scară (neliniară din Android 14) |
| `minimumScaleFactor` | micșorare continuă până la factor | `TextAutoSize.StepBased` în pași de 0,25 sp; `TargetWord` ține câte un rând pe cuvânt și micșorează cuvântul înainte să-l rupă | Compose nu are echivalent direct |
| Colțuri „continue” | squircle-ul Apple | conturul iOS 7+ refăcut din coeficienții cunoscuți (1,528665 × raza); când latura e prea scurtă pentru netezire, colțul devine arc de cerc cu aceeași rază | iOS reduce netezirea treptat; formula exactă nu e publică |
| `Color.mix(with:by:)` (aerul din profil) | spațiu perceptual | `lerp` din Compose, în Oklab | presupunem că „perceptual” e Oklab |
| Blur (nas, obraji, umbra buzelor 2,5, luciul 3, umbra pastilelor 6) | rază SwiftUI | Gaussian cu sigma = raza, prin `BlurMaskFilter` (merge pe randarea hardware de la API 28) | raza SwiftUI e tratată ca deviație standard; comparația cu gura S nu arată diferențe |
| `.snappy` (`CountPill`) | preset de sistem | `spring(duration: 0.5, bounce: 0.15)` | valoarea presetului nu apare în cod |
| Cifrele care se rulează (`.numericText`) | animație de sistem, cu estompare | fiecare cifră schimbată alunecă și se estompează, în sus când crește | nu există echivalent; `StatRow` nu animă valoarea (pe iOS doar în tranzacții animate) |
| Netezirea vocii (Foni, `VoiceRings`) | 0,35 / 0,08 pe cadru, la 60 Hz | același pas, raportat la timpul trecut (`1 − (1 − k)^(dt·60)`) | ecranele Android merg la 60, 90 sau 120 Hz; la 60 Hz rezultatul e identic |
| `Canvas(rendersAsynchronously: true)` | randare în afara firului principal | desen obișnuit; poza lui Foni se calculează în `withFrameNanos` și doar desenul o citește (fără recompunere) | Compose nu are canvas asincron |
| Zgomotul din `sin` (privire, clipit, stele, steluțe) | libm Darwin | `kotlin.math.sin` | diferențe de ordinul 1e-11 după înmulțirea cu 43758, invizibile |
| Reduce Motion | setare dedicată | „Elimină animațiile” (scara animatorului = 0), citită de `FoniaTheme` în `LocalReduceMotion` | Android nu are altă setare |
| Vibrații | `.impact(.light)` la Foni, `.impact(.medium)` la butonul galben; la apăsare, după stilul butonului | `HapticFeedbackType.ContextClick` și `VirtualKey`; la apăsare, după stil (moale → `ContextClick`, tic → `SegmentTick`), oprite cu `LocalFoniaHapticsEnabled` | Taptic Engine nu există; efectul variază după telefon |
| Apăsarea | fiecare `ButtonStyle` își face apăsarea | la fel: `StyledButton` nu mai are scara generică de 0,94 și nici vibrația la clic; sunetul de atingere rămâne | aceeași mișcare pe fiecare stil |
| Luminozitatea la apăsare | `.brightness` | un strat cu matrice de culoare | Compose nu are `.brightness` |
| Butoanele plate (Round, Tile, MiniPill, Plus) | ridicate, cu margine (§35) | doar noua temporizare și vibrația; forma rămâne plată | restilizarea lor n-a intrat în tura a zecea |
| Iconițele | SVG vectorial, „template” | SVG prin AndroidSVG, desenat la mărimea afișată și colorat `SrcIn` cu culoarea textului; stratul duotone își păstrează opacitatea 0,2 | rezultat identic, dar e bitmap la mărimea cerută |
| Imaginile | catalog compilat, comprimat cu pierderi | PNG/JPEG din `content/`, decodate întregi, cu mipmap pentru micșorare; cache LRU de 180 MiB golit la presiune de memorie | compresia la împachetare ține de `:app` |
| `RisingPanel` | `ignoresSafeArea(edges: .bottom)` doar dacă atinge marginea | continuă mereu sub bara de navigare, cu înălțimea ei | ecranele îl pun mereu jos |
| `Dock` | stă în `safeAreaBar`, pagina se estompează în spatele lui | doar marginile; estomparea o face ecranul | `safeAreaBar` nu există |
| `MiniPillStyle` `.fixedSize()` | eticheta nu se rupe și poate depăși rândul | eticheta e limitată la un rând, dar se încadrează în lățimea dată | Compose nu lasă un copil să depășească limitele părintelui |
| `Label` (`.titleAndIcon`) | spațiere de sistem | `FoniaLabel`: iconiță, 8 dp, text | 8 pt măsurat pe video |
| `ButtonStyle` | `Button { } label: { }.buttonStyle(X())` | `StyledButton(onClick, style = X()) { … }`; aceleași nume de stiluri | Compose nu are stiluri de buton |
| `MouthGallery` | `.font(.caption2)`, SF 11 | Roboto 11 | singurul font de sistem din aplicație |

## Ce s-a păstrat exact (riscurile din inventar §13.5)

- Foni: 13 straturi în grila 35 × 35, aceeași ordine, aceiași pivoți, rotații în grade cu y în jos, aceleași formule
  (`ParrotMotion` e funcție pură de timp). Memorie: 13 bitmapuri 720 × 720 (≈ 27 MB) plus mipmap.
- Gurile: `MouthVector` (16 numere) și `SagittalVector` (25 de canale în 32) se mișcă cu un singur spring rezolvat
  analitic (`AnimatedVector`), cu viteza păstrată la întrerupere: limba trece prin fondu, aerul și vocea se estompează.
  Conversia springurilor e cea standard: rigiditate = (2π / durată)², amortizare = 1 − bounce.
- Decuparea `outside(of:)` din gura din față: dreptunghiul și deschiderea merg în același sens, deci cu regula
  non-zero pe iOS deschiderea nu se taie; aici e aceeași cale cu aceeași regulă, deci același rezultat.
- Bifa „da” e decupată cu `DST_OUT` într-un strat separat, ca `drawLayer` + `.destinationOut`.
- `WrapLayout` e un `Layout` propriu, cu interlinia negativă și centrarea pe rând.
- Ploaia de steluțe, norii și stelele au aceleași numere și aceeași funcție de zgomot.
- Testele `ArtTests.swift` care țin de design (iconițe, file, scene în toate formele, poze, fonturi) sunt portate
  în `src/test` și rulează pe JVM pe `content/`; `everyLessonHasItsPicture` cere cursul din `:domain` și rămâne
  pentru `:features`.

## Ce nu e în `:design`

- Iconița aplicației și ecranul de pornire (inventar §9.10) țin de `:app`.
- `SceneCache.preloadHome(context)` trebuie chemat la pornire (pe iOS, în `RootView.init`), iar arborele trebuie pus
  în `FoniaTheme { }` (fonturile, cache-ul, tracking 0,3, tint, Reduce Motion).
- Deschiderea galeriei din aplicație (echivalentul lui `-foniaMouths`) rămâne pentru coordonator.

## Emulatorul

AVD-ul `Medium_Phone` nu mai pornește: `system_server` cade în buclă cu
`IllegalStateException: Failed to read /data/misc/apexdata/com.android.permission/access.abx` (EIO), și la pornire
din snapshot, și la pornire rece. Nu l-am șters și nu l-am resetat. Verificarea s-a făcut pe AVD-ul `sp_d`
(Android 36, același ecran 1080 × 2400 la 420 dpi).
