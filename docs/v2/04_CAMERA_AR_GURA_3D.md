# 04. Camera, AR și gura 3D

Capitolul pachetelor **H1–H10**. Respectă `00_PLAN_MASTER.md` (D7, F1, F3, F4, F5, F6, numele din §3). Surse: rapoartele Q, U, V, AC §6–7, AB, AA §C–D, `android-inventory.md`, codul din `face/` și `facecamera/` (commit `311f55c`), `gnm_head.npz` citit cu `npz_read.py`.

Etichete: **[Verificat]** = citit azi în cod, în model sau într-un raport care citează sursa; **[Estimare]** = calcul sau cunoștință nemăsurată; **[Propunere]** = alegerea mea, de reglat pe date.

## 1. Ce poate și ce nu poate ști camera

**Regula capitolului: microfonul judecă sunetul; camera verifică gestul care se vede și alege sfatul.** Camera singură nu corectează niciodată un copil (§10). Limba ascunsă se arată pe `Mouth3D`, ca model, nu ca măsurătoare.

| Organ | Ce măsurăm | Cum | Încredere |
|---|---|---|---|
| Buze | deschidere și lățime în mm, rotunjire, întindere, închidere | inelele de puncte + scara din iris, relativ la repausul copilului | bună: ±1–2 mm + 4–10 % din scară [Estimare, U §3.4] |
| Falcă | deschidere în mm (bărbie față de baza nasului) | puncte + scară | medie: ±2–3 mm, țesut moale [Estimare] |
| Dinți | doar „se văd / nu se văd” în deschiderea gurii | culoare pe decupaj | slabă; fără distanță între incisivi în v1 |
| Limbă | afară, stânga, dreapta, sus, jos (gimnastică); între dinți doar cu modelul H10 | euristică de culoare, apoi clasificator | bună cu gura deschisă; **zero în vorbire** [Verificat, V §1] |

| Sunet | Semnătura vizibilă | Camera poate | Camera nu poate | Rolul în verdict |
|---|---|---|---|---|
| S, Z | buze întinse, dinți apropiați; limba ascunsă | întindere, deschidere mică; limba între dinți (H10) | locul limbii, aerul lateral, sonoritatea | alege sfatul buze/limbă; confirmă `Interdental` |
| Ț | ca S, foarte scurt | forma buzelor în jurul segmentului | închiderea și explozia | alege sfatul |
| Ș, J | buze rotunde, împinse | rotunjirea (cel mai sigur gest) | „ceșcuța” limbii | alege sfatul buze/limbă |
| CE/CI, GE/GI | rotunjire ușoară, scurtă | rotunjirea pe o fereastră lărgită | închiderea, sonoritatea | alege sfatul |
| R | gura întredeschisă | „falca prea închisă” | bătaia vârfului, R din gât | doar sfatul „deschide puțin gura” |
| L | gura întredeschisă; buze nerotunjite | rotunjirea greșită (L→U); vârful sus doar în exercițiul cu gura mare, cu H10 | contactul la alveole în cuvinte | confirmă `LtoGlide` cu buze rotunde |

Nicio validare a trackerelor pe fețe de copii nu există (Q, punctul 1) [Verificat]. De aceea toate pragurile sunt **relative la repausul copilului** și butonul „Am făcut” rămâne mereu.

## 2. H1: lărgirea „seam”-ului camerei

**Azi** [Verificat în cod]: `FaceLandmarkerPipeline` calculează 478 de puncte, 52 de scoruri și un scor propriu al modelului pentru limbă (`tongueOut`, ieșirea 2, ignorată de MediaPipe), dar `FaceObservation` duce spre `:face` doar 7 scoruri, `faceToCamera` și `lookAt`. Analiza e 640×480 RGBA, `KEEP_ONLY_LATEST`, pe un singur fir (`tech.fonia.face.camera`, prioritate DISPLAY). Netezirea One-Euro (min cutoff 0,05, beta 80) e pe puncte. Preview-ul e `PreviewView` (mod COMPATIBLE, FILL_CENTER), oglindit. Camera se închide la `ON_STOP`.

**Contractul `FaceFrame` v2** (aditiv: câmpurile vechi rămân, cele 62 de teste `:face` rămân verzi):

```kotlin
// :engine:face — umplut de :platform:camera, două instanțe prealocate, schimbate atomic
class FaceFrame(
  var tNanos: Long, var isPresent: Boolean, var detected: Boolean,
  var imageW: Int, var imageH: Int,          // imagine dreaptă, NEoglindită
  val lipsOuter: FloatArray,                 // 20×(x,y,z) normalizat, ordinea LIPS_OUTER
  val lipsInner: FloatArray,                 // 20×3, ordinea LIPS_INNER
  val anchors: FloatArray,                   // 12×3, ordinea ANCHORS
  val irises: FloatArray,                    // 10×3: punctele 468…477
  val blendshapes: FloatArray,               // 52, ordinea Blendshapes.NAMES
  var faceToCamera: Float4x4, var lookAt: Float3,
  var presence: Float, var tongueModelScore: Float,
  var lumaFace: Float, var lumaMouth: Float, var clippedShare: Float,
  /* + câmpurile v1: jawOpen, smile, funnel, pucker, puff, gaze*, head* */)
```

| Constantă | Indici MediaPipe |
|---|---|
| `LIPS_OUTER` | 61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291, 375, 321, 405, 314, 17, 84, 181, 91, 146 |
| `LIPS_INNER` | 78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308, 324, 318, 402, 317, 14, 87, 178, 88, 95 |
| `ANCHORS` | 1 (vârful nasului), 2 (baza nasului), 4, 10 (frunte), 152 (bărbie), 234, 454 (marginile feței), 33, 133, 263, 362 (colțurile ochilor), 168 |
| Iris | 468 centru + 469–472 (ochiul drept al persoanei), 473 + 474–477 (stâng) |

Colțurile ochilor și irisul sunt [Verificat în `FaceGeometry`]. Inelele de buze și ancorele sunt scrise din memorie [Estimare]: primul test din H1 le compară cu `FACE_LANDMARKS_LIPS` și cu o imagine de referință.

**Convenții.** x spre dreapta și y în jos, normalizate la imaginea dreaptă, neoglindită; z relativ, la scara lui x. „Stânga/dreapta” înseamnă mereu **ale copilului**. Pe ecran, oglindirea se face o singură dată, la desen: `xEcran = (1 − x)`, apoi matricea FILL_CENTER a `PreviewView`. Un test unitar fixează că limba spre mâna stângă a copilului apare în stânga ecranului.

| Subiect | Decizie |
|---|---|
| Rezoluție | 640×480 implicit [Verificat]. Mod `MouthCrop` (gimnastica limbii, reluarea): 1280×960, ca gura să aibă ≥ 100 px (V §1) [Propunere]; costul copiei se măsoară în H1 |
| Decupajul gurii | `MouthCrop`: pătrat cu latura 1,8 × lățimea gurii, aliniat la linia colțurilor, 96×96 RGB (analiză) și 192×192 RGB565 (reluare), eșantionat cu `FrameSampler` în buffere refolosite |
| Cadre | țintă 30 fps, podea 15; sub 12 fps poarta de calitate cade. Măsurat pe telefonul real la M0 |
| Fire | firul camerei: model → `MouthStateEngine` (< 0,3 ms [Estimare]) → publicare. UI citește doar în faza de desen. Firul de analiză audio ia ultima valoare (§3) |
| Netezire | One-Euro pe puncte rămâne; One-Euro pe scalarii `MouthState` (min cutoff 1,5 Hz, beta 0,3 [Propunere]); overlay-ul interpolează între cadrele de analiză la rata ecranului |
| Disc | nimic. `FaceRecording` (CSV de depanare) se mută în source set-ul `debug`. Test: după 60 s de oglindă, niciun fișier nou în directoarele aplicației |

## 3. H2: motorul stării gurii

Kotlin pur în `:engine:face`, fără alocări pe cadru. Notații: `p_i` = punctul i în pixeli; `yaw`, `pitch` din `faceToCamera`.

| Mărime | Formulă | Note |
|---|---|---|
| Scara `s` (mm/px) | `s = irisMm / d̄`, `d̄` = mediana pe 15 cadre a mediei diametrelor celor două irisuri (‖469−471‖, ‖470−472‖, ‖474−476‖, ‖475−477‖) | `irisMm`: 11,7 la adulți, 10,6 la 4–10 ani [Verificat în U]; eroare de scară ±4–10 % |
| Calibrare cu moneda (opțional, o dată) | adultul ține o monedă de 50 de bani (Ø 23,75 mm [Verificat în U]) lângă gură și îi atinge marginile pe un cadru înghețat în RAM; rezultă `irisMm` al copilului | se salvează **un singur număr** pe profil; cade primul la tăiere |
| Lățimea `W` | `s·‖p291 − p61‖ / cos(yaw)` | mm |
| Deschiderea buzelor `A` | `s·‖p13 − p14‖ / cos(pitch)` | mm |
| Aria interioară | formula șiretului pe `LIPS_INNER`, × `s²` | mai fiabilă decât protruzia (Q [28]) |
| Falca `J` | `s·(‖p152 − p2‖ − ‖p152 − p2‖₀) / cos(pitch)` | mm peste repaus |
| Rotunjire `round` | `0,6·clamp((W₀ − W)/(0,25·W₀)) + 0,4·clamp(max(Δpucker, Δfunnel)/0,5)` | 0…1, față de repaus [Propunere] |
| Întindere `spread` | `clamp((W − W₀)/(0,15·W₀))` | 0…1 |
| Protruzie | diferența de z buze–ancore față de repaus, împărțită la distanța dintre ochi | doar relativ; încredere ≤ 0,3 (abatere z de 11 mm RMS, U) |
| Buze închise | `A ≤ max(1 mm, 0,03·W₀)` și arie ≤ `0,01·W₀²`, două cadre la rând | |
| Zâmbet | `spread ≥ 0,5` și `Δ(mouthSmileL+R)/2 ≥ 0,2` | |
| Simetrie | `1 − 2·|dS − dD|/(dS + dD)`, `dS`, `dD` = distanțele colțurilor la linia mediană (168→2) | orientativ; doar la adult, niciodată copilului |

**Repausul** (`RestBaseline`: `W₀`, `A₀`, `‖152−2‖₀`, scorurile de repaus, culorile din §5). Se ia la intrarea în fiecare sesiune de oglindă: „Fă o față liniștită, cu gura închisă”, 1 s; mediana a ≥ 20 de cadre trecute de poartă, cu abaterea lățimii < 3 %. Dacă nu reușește în 4 s, se ia automat din cele mai liniștite 15 cadre în care microfonul nu aude vorbire. **Trăiește doar în RAM și moare cu ecranul.**

**Poarta de calitate** (`FaceQuality`: `Good`, `Weak(motiv)`, `Absent`) [Propunere, după Q §6]:

| Condiție | Prag | Mesaj când cade |
|---|---|---|
| Față prezentă, `presence > 0,5` | — | „Unde ești? Vino în oglindă” |
| Mărime | lățimea gurii ≥ 60 px; distanță 0,15–0,60 m | „Vino mai aproape” / „Puțin mai departe” |
| Unghi | \|yaw\|, \|pitch\| ≤ 20°, rotire ≤ 25° | „Uită-te la Foni” |
| Lumină | luminozitatea medie a decupajului gurii 50…235 din 255; ≤ 5 % pixeli arși | „Mai multă lumină pe față” |
| Ritm | ≥ 12 cadre/s, cadru mai nou de 150 ms | (tăcere; camera se abține) |

**Ieșirea:**

```kotlin
class MouthState(
  var tNanos: Long, var quality: FaceQuality, var scaleSource: ScaleSource, // Iris | Coin
  var apertureMm: Float, var widthMm: Float, var jawOpenMm: Float, var innerAreaMm2: Float,
  var round: Float, var spread: Float, var protrusion: Float, var symmetry: Float,
  var lipsClosed: Boolean, var tongue: TongueState,   // None|Out|Left|Right|Up|Down|Unknown
  var confGeom: Float, var confShape: Float, var confTongue: Float)
```

Încrederea = poartă × stabilitate (inversul abaterii pe ultimele 5 cadre) × plafonul mărimii (deschidere și lățime 0,9; rotunjire 0,7; falcă 0,6; protruzie și simetrie 0,3).

**Spre `LiveSnapshot`.** Camera dă ~30 de valori pe secundă, banda continuă 100. Firul de analiză copiază ultima `MouthState` („sample and hold”) în câmpurile `mouthAperture`, `mouthWidth`, `mouthRound`, `mouthSpread`, `jawOpen`, `lipsClosed`, `tongue`, `faceQuality`, `faceAgeMs`. În paralel, `MouthTrack` ține ultimele 3 s (90 de stări cu timp) pentru fuziune (§10).

## 4. H3: `MIRROR` și `LipTargetOverlay`

`MirrorView` = `PreviewView` oglindit + `LipTargetOverlay` (Canvas Compose) + `Mouth3D` într-un colț + `MicCapsule`.

**Cum iese conturul-țintă.** Inelele copilului din repaus se trec într-un reper local: originea la mijlocul colțurilor, axa `u` pe linia colțurilor, unitatea `W₀`. Pentru fiecare punct `(u, v)`:

```
u' = u · wide · (1 − 0,30·round)
v' = v · (1 + 0,25·round) ± (open·W₀/2) · cos²(π·u)     // + inelul de sus, − cel de jos
```

Conturul rezultat se așază pe fața vie: poziția și rotația din colțurile și ancorele curente, scara din distanța dintre ochi. Ținta urmărește capul, nu buzele.

| Clasă de buze (`spec/poses/<sound>.json`, blocul `lips`) | Sunete | `wide` | `open` (× W₀) | `round` | La W₀ = 40 mm |
|---|---|---|---|---|---|
| Zâmbet cu dinții apropiați | S, Z, Ț | 1,12 | 0,08 | 0 | deschidere ~3 mm |
| Pâlnie | Ș, J, CE/CI, GE/GI | 0,78 | 0,12 | 0,65 | deschidere ~5 mm |
| Întredeschis | R | 1,00 | 0,25 | 0 | ~10 mm |
| Întredeschis, ușor zâmbet | L | 1,04 | 0,22 | 0 | ~9 mm |

Valorile sunt [Propunere] din AA §C și se confirmă cu logopedul luni. Buzele la Ș/J/CE/GE sunt rotunde (F3).

**Potrivirea.** `m = 1 − max(|A − A*|/tolA, |W − W*|/tolW, |round − round*|/tolR)`, cu `tolA = max(2,5 mm, 0,35·A*)`, `tolW = 0,08·W₀`, `tolR = 0,25` [Propunere]. Histerezis: „potrivit” pornește la `m ≥ 0,75` ținut 300 ms și se stinge la `m < 0,55` ținut 400 ms. Cu poarta căzută, conturul devine neutru și nu se judecă nimic.

| Stare | Ce se vede (niciodată doar culoare) |
|---|---|
| Ținta, nepotrivit | contur punctat, 3 dp, albastru, cu halou moale |
| Aproape (`m` 0,55–0,75) | liniuțele se lungesc proporțional cu `m` |
| Potrivit | contur plin, 5 dp, verdele papagalului, o bifă mică lângă colțul gurii, un singur puls de ≥ 300 ms; Foni dă din cap |
| Camera nesigură | contur gri subțire + mesajul porții |

Cât ascultă microfonul: fără sunet și fără vibrație (AC §8). După ce microfonul se închide și verdictul e `Correct`: **scântei din colțurile gurii** (punctele 61 și 291), 20–40 de particule, 500 ms, prin `FxGate`; cel mult 60 de particule pe ecranul cu camera. Nimic nu acoperă buzele.

**Viteză** [Estimare, de măsurat la M2]: model 10–20 ms, `MouthState` < 0,3 ms, overlay < 2 ms (două `Path`-uri de 20 de puncte în 3 straturi; `BlurMaskFilter` pe API 29). **Mișcare redusă:** fără particule, fără puls, fără interpolare elastică; starea se schimbă direct din punctat în plin, cu bifa.

## 5. H4 și H10: limba afară și „sala de gimnastică”

### 5.1 Detectorul euristic (H4)

**Intrări:** `MouthCrop` 96×96 RGB, `FaceFrame`, `RestBaseline`, `tongueModelScore`.

**Reperul gurii** se ia din colțuri (61, 291) și din ancore (2, 152), nu din inelele buzelor, pentru că punctele buzelor alunecă atunci când iese limba.

**Modelul de culoare, luat la repaus.** În cromaticitate normalizată (`r = R/(R+G+B)`, `g = G/(R+G+B)`) plus luminanța `Y`: media și covarianța pixelilor de **buză** (între inele) și de **piele** (benzile de deasupra buzei de sus și de sub buza de jos). Un pixel e „țesut roșu” dacă distanța Mahalanobis până la buză e mai mică decât până la piele; e „dinte” dacă `Y > 0,9·Y_piele` și saturația < jumătate din a pielii; e „gol” dacă `Y < 0,35·Y_piele`. Dacă buza și pielea nu se separă la repaus (d′ < 1,5), detectorul se declară `Unknown` pentru toată sesiunea.

**Zone** (în unități `W₀`, față de mijlocul colțurilor, `v` pozitiv în sus):

| Zonă | Dreptunghi (u; v) | Clasă |
|---|---|---|
| `Zcentru` | \|u\| ≤ 0,25; −0,45…−0,05 | `Out` |
| `Zjos` | \|u\| ≤ 0,30; −0,90…−0,45 | `Down` (spre bărbie) |
| `Zsus` | \|u\| ≤ 0,25; +0,20…+0,55 | `Up` (peste buza de sus, spre nas) |
| `Zstânga`, `Zdreapta` | u = ±(0,50…0,85); \|v\| ≤ 0,20 | `Left`, `Right` |

**Regula pe cadru:** `Δf(zonă) = cota de țesut roșu acum − cota la repaus`. Limba e într-o zonă dacă `Δf ≥ 0,25`, pata roșie e **legată** de deschiderea gurii (aceeași componentă conexă), aria ei ≥ `0,12·W₀²` și `A ≥ 5 mm`. Clasa e zona cu `Δf` maxim; la egalitate decide centroidul petei. `tongueModelScore` intră ca dovadă în plus doar dacă la M0 se arată util [Verificat că există în cod; fiabilitate necunoscută].

**În timp:** votul majoritar pe 5 cadre; starea pornește după 250 ms și se stinge după 200 ms.

| Unde greșește | Ce facem |
|---|---|
| Lumină roșiatică, ruj, buze pătate | totul e relativ la repaus; fără separare la repaus → `Unknown` |
| Ten la care buza și pielea au culori apropiate | testul d′; altfel „Am făcut” |
| Buza de jos răsfrântă luată drept limbă | pata trebuie să treacă cu ≥ 0,2·W₀ de marginea de repaus a buzei și gura să fie deschisă |
| Mână, suzetă, acadea în fața gurii | `presence` scade sau pata nu e legată de deschidere → abținere |
| Lumină slabă în gură | poarta citește luminozitatea decupajului; ecran alb ca lumină, crescut în 500 ms |
| Stânga/dreapta inversate | convenția „ale copilului” + test unitar |
| „Sus” atins doar pe buza de sus | e normal: se măsoară efortul, nu atingerea nasului (V §2) |

Când detectorul se abține, exercițiul se încheie cu „Am făcut” (copilul sau părintele). La gimnastică, spre deosebire de vorbire, camera **are voie** să dea reușita, pentru că gestul se vede (V §2).

### 5.2 Cele 15 încălziri (AA §D)

| # | Exercițiu | Verificare | Regula „reușit” [Propunere] |
|---|---|---|---|
| D1 | Burta-balon | microfon | expir lung (cap. 03) |
| D2 | Pupicul | **cameră** | `round ≥ 0,6` ținut 1,5 s, de 3 ori |
| D3 | Zâmbetul de crocodil | **cameră** | `spread ≥ 0,6` și `A ≤ 6 mm`, 1,5 s × 3 |
| D4 | Zâmbet și pupic | **cameră** | 4 treceri între D3 și D2 |
| D5 | Hipopotamul | **cameră** | `A ≥ 0,45·W₀`, apoi buze închise, × 3 |
| D6 | Motorașul buzelor | microfon | vibrație joasă, ritmică |
| D7 | Căluțul | microfon | 4–6 plescăituri (clicuri) |
| D8 | Cățelușul bea apă | **cameră (H4)** | `Out` ≥ 0,5 s, de 4 ori |
| D9 | Ceasul tic-tac | **cameră (H4)** | 4 alternări `Left`/`Right` |
| D10 | Cameleonul | **cameră (H4)** | 4 alternări `Up`/`Down` |
| D11 | Limba urcă pe deal | părinte; cameră abia cu H10 | clasa `upInside`, 1 s |
| D12 | Gâtul care bâzâie | microfon | sonoritate |
| D13 | Șarpele lung | microfon | durată și stabilitate |
| D14 | Morișca | microfon | suflat |
| D15 | Periuța cu limba | părinte | atingere |

Detectoarele de microfon țin de capitolul 03, textele și ordinea de pachetul I3. Aici livrăm doar verificările cu camera și starea comună `WarmupCheck(progress 0…1, source: Camera | Mic | Tap)`. Gimnastica e prezentată ca „trezim gura”, nu ca tratament (V, punctul 3).

### 5.3 Clasificatorul mic (H10, etapa M6)

| Subiect | Plan |
|---|---|
| Clase | `none`, `outCenter`, `outUp`, `outDown`, `outLeft`, `outRight`, `betweenTeeth`, `upInside` (V §2) |
| Intrare | `MouthCrop` 96×96 RGB, aliniat la colțuri |
| Model | MobileNetV3-Small redus sau CNN de 150–400 de mii de parametri, int8, ≤ 1 MB, LiteRT, ≤ 4 ms, la fiecare al doilea cadru [Estimare] |
| Date din weekend | în colector, cu **bifă separată de acord** pentru „imagini cu gura”: la fiecare indicație de gimnastică se păstrează 3–5 s din decupajul gurii (128×128, nu toată fața). Eticheta slabă = indicația afișată + ieșirea euristicii. Fișierele pleacă pe PC-ul echipei prin USB |
| Etichetare | în unealta B3: confirmă sau respinge pe clip; 10 % etichetate de doi oameni (κ) |
| Cantitate | M6: ≥ 25 de persoane (≥ 8 copii), ≥ 1.500 de cadre, împărțite **pe persoane**. Țintă de produs: 3.000–6.000 de cadre de la ≥ 50 de persoane (V §5) [Estimare] |
| Antrenare (PC) | PyTorch, backbone pre-antrenat, augmentări de lumină și nuanță, oglindire cu schimbarea etichetei stânga/dreapta, 5 împărțiri pe persoane; export LiteRT int8 |
| Când înlocuiește euristica | pe persoane nevăzute: ≥ 90 % pe clasele „afară” și direcții, „afară” fals ≤ 3 %, cu ≥ 5 puncte peste euristică |
| `betweenTeeth` în fuziune | doar cu precizie ≥ 0,85 pe ≥ 150 de exemple de la ≥ 15 copii (AB §5), adică valul 3 |

## 6. H5: reluarea cu încetinitorul

| Subiect | Decizie |
|---|---|
| Inel | `MouthRing` în `:platform:camera`: un `ByteBuffer` direct, prealocat, 90 de cadre de 192×192 RGB565 = 90 × 73,7 KB ≈ **6,6 MB** [calculat] |
| Ritm, durată | 30 de cadre/s, ultimele 3 s; se redau cele 2 s din jurul segmentului sonor |
| Redare | 0,4× (2 s devin 5 s), fără sunet, alături de `Mouth3D` din față, care joacă aceeași tranziție la aceeași viteză. Cele două pornesc aliniate la începutul sunetului găsit de audio |
| Ce vede copilul | stânga „Tu”, dreapta „Foni arată”, eticheta „Încetinit”, butoanele „Încă o dată” și „Gata”. **Fără verdict, fără scor, fără culori de judecată** |
| Ștergere | la ieșirea din ecran, la `ON_STOP` și la o nouă încercare: bufferul e umplut cu zero și eliberat. Test automat |
| Capturi | `FLAG_SECURE` pe fereastra oglinzii, ca sistemul să nu păstreze miniaturi cu fața copilului [Propunere]; oprit doar în build-ul de demo |

## 7. H6: gura 3D stilizată, din GNM

**Ce am verificat azi în `gnm_head.npz`:** 17.821 de vârfuri, 35.324 de triunghiuri; axe: x spre stânga persoanei, y în sus, z în față; metri. Grupurile sunt binare. Vârfuri pe grup: `tongue` 933, `teeth` 1.868, `gums` 1.404, `upper_teeth_and_gums` 1.440, `lower_teeth_and_gums` 1.440, `mouth_sock` 406, `upper_lip` 145, `lower_lip` 145, `upper_lip_region` 338, `lower_lip_region` 372, `chin_region` 136. `mirror_indices` e o involuție cu 315 vârfuri pe planul median. Componentele limbii ating doar vârfurile limbii (485–933 fiecare). Deplasarea maximă la greutatea 1: `tongue_000` 11,5 mm, `tongue_mean` 9,2 mm, `lower_face_region_000…003` 6,8 / 5,4 / 4,5 / 3,4 mm, apoi sub 2 mm.

**Pipeline offline** (`tools/gnm/build_mouth_asset.py`, pe PC, într-un venv cu numpy; pe acest PC numpy lipsește azi):

| Pas | Ce face |
|---|---|
| 1. Verificare | SHA-256 al `gnm_head.npz` față de `checksums.sha256` |
| 2. Vârfuri păstrate | `tongue` ∪ `upper_teeth_and_gums` ∪ `lower_teeth_and_gums` ∪ `mouth_sock` ∪ `upper_lip_region` ∪ `lower_lip_region` ∪ `chin_region` ∪ un inel de piele la ≤ 12 mm de ele. ≈ 5.100–5.600 de vârfuri [Estimare din numărători] |
| 3. Tăiere | rămân triunghiurile cu toate cele 3 vârfuri păstrate. Marginea peticului de piele primește atributul `edgeFade` (1 → 0 pe ultimii 8 mm), ca peticul să se stingă moale. Fără capac geometric: fețele din spate se colorează plat în shader |
| 4. Componente | toate cele 32 ale limbii; din cele 150 ale feței de jos, cele mai puține care acoperă 98 % din energia pe vârfurile păstrate, cel mult 32 (aștept ~24). Fără bazele de identitate și de ochi |
| 5. Stilizare | identitatea neutră; peticul de piele netezit; buzele îngroșate cu 10 % pe normală. **Dinții, gingiile și limba nu se deformează**: anatomia rămâne corectă |
| 6. Decimare | niciuna (plasa e deja mică) |
| 7. Reper | originea în mijlocul muchiei incisivilor de sus; milimetri; aceleași axe |
| 8. Repere numite | indicii vârfurilor din §8, aleși de script și verificați vizual |
| 9. Normale | nu se exportă; se recalculează la rulare |

**Formatul `mouth.fm3d`** (little-endian): `"FM3D"`, `u32` versiune, `u32` lungimea JSON-ului, antet JSON (UTF-8, completat la 4 octeți), apoi blocuri binare referite prin offset.

| Bloc | Tip | Mărime estimată |
|---|---|---|
| `positions` | float32 × 3 | 66 KB |
| `indices` | uint16 × 3 | ~63 KB |
| `part` (0 petic, 1 buze, 2 dinți, 3 gingii, 4 limbă, 5 căptușeală), `edgeFade` | uint8 | 11 KB |
| componentele limbii (32) | rar: indice uint16 + delta int16 × 3, cu `scale` pe componentă | ~240 KB |
| componentele feței de jos (~24) | la fel | ~880 KB |
| **Total** | | **≈ 1,3 MB** (țintă ≤ 2 MB) [Estimare] |

Antetul JSON: sursa și licența, SHA-256 al sursei, unități și axe, numărători, lista componentelor (`name`, `scale`, `count`, offset), reperele numite, tabela părților. Dacă trece de 2 MB: prag de 0,02 mm pe delte, apoi 16 componente pentru fața de jos.

**Aspectul** (D7): peticul din jurul gurii **nu are culoare de piele**; e un „autocolant” în albastrul deschis al aplicației, deci fără ten și fără cap realist. Buze roz închis, limbă roz, gingii roz pal, dinți alb cald, căptușeală albastru-mov închis (nu negru). Umbrire în trei tonuri, lumină de contur galben pal. Culorile vin din tokenurile capitolului 05.

**Licență:** Apache-2.0. În `NOTICE` și în ecranul de licențe: „Gura 3D folosește GNM Head v3.0, © Google LLC, licență Apache 2.0, modificat de Fonia (decupat, cuantizat, stilizat)”. Modelul Face Landmarker (MediaPipe) e tot Apache-2.0.

## 8. H7: pozele pe sunet

Componentele GNM nu au nume, deci pozele se **rezolvă** din ținte geometrice.

**Repere** (indici în `mouth.fm3d`): limbă — `TT` vârf, `TB` lamă (≈ 10 mm în spatele vârfului), `TD` dos, `TR` rădăcină, `TLs`/`TLd` marginile la nivelul lamei și `TMs`/`TMd` la nivelul molarilor; buze — `UL`, `LL`, `CS`, `CD`; dinți — `UI`, `LI` (muchiile incisivilor); cerul gurii — `AR` (creasta alveolară), `PA` (postalveolar), `PH` (bolta).

**Rezolvarea.** Necunoscuta `w` = [greutățile feței de jos; greutățile limbii]. Fiecare țintă dă rânduri în `A` (deltele reperului pe fiecare componentă) și în `b` (poziția dorită − poziția de repaus), cu ponderea din `W`. Axele nespecificate rămân libere.

```
w = argmin ‖W(A·w − b)‖² + λ‖w‖² + μ‖w − w_vecin‖²  =  (AᵀW²A + (λ+μ)I)⁻¹ (AᵀW²b + μ·w_vecin)
```

`λ` se alege ca `|w_k| ≤ 3`; `μ` leagă pozele vecine din aceeași tranziție. Urmează 3–5 treceri de coliziune: 60 de vârfuri ale limbii se verifică față de dinții de sus și de cerul gurii; fiecare pătrundere devine o țintă nouă, de împingere în afară. Rezolvarea e comună pentru falcă și limbă, deci nu contează dacă în GNM limba urmează sau nu falca. Scriptul raportează reziduul în mm pe fiecare țintă.

**Țintele** (mm față de `UI`; din AA §C și fonetica articulatorie standard) [Estimare până la semnătura logopedului]:

| Poză | Falcă (`LI` sub `UI`) | Buze | Limbă |
|---|---|---|---|
| Repaus | 2 | închise | vârful după incisivii de jos, relaxată |
| S, Z | 1–2 | clasa „zâmbet” | `TT` jos, pe fața din spate a incisivilor de jos; lama la 1,5 mm sub `AR`, cu șanț median de ~2 mm; marginile lipite de molarii de sus |
| Ș, J | 2–3 | clasa „pâlnie” | „ceșcuță”: `TT` ridicat la 1,5–2 mm sub `PA`, la 6–8 mm în spatele lui `UI`; marginile sus; corpul retras 3–4 mm față de S |
| Ț | 1–2 | zâmbet | două chei: închidere (lama lipită de `AR`) → poza S, în 60–90 ms |
| CE/CI, GE/GI | 2–3 | pâlnie ușoară | închidere la `PA` → poza Ș |
| R | 6–8 | întredeschise | `TT` sus la `AR`, marginile etanșe; chei alternate contact / 1,5 mm, redate la 0,25× cu eticheta „Încetinit” |
| L | 6–9 | întredeschise | `TT` lipit de `AR`, marginile **coborâte** cu 2–3 mm, apoi desprindere în jos |

Z, J și GE au aceeași poză ca perechea surdă; vocea se arată pe literă și cu o undă la marginea de jos a peticului.

| Poză de eroare (`ErrorKind`) | Cum arată |
|---|---|
| `Interdental` (S, Z, Ț, Ș, J) | `TT` între incisivi, 2–4 mm în fața lui `UI`; falca la 3–4 mm |
| `Lateral` | lama lipită pe mijloc la `AR`, o margine coborâtă cu 3 mm, săgeată de aer pe acea parte |
| `PlaceSwap` | S cu buze rotunde și corp retras; Ș cu buze întinse și vârf în față |
| `Stopping`, `Deaffrication` | închidere completă ținută; respectiv poza fricativei fără cheie de închidere |
| `RUvular` | `TT` jos, `TD` ridicat spre spate cu ~6 mm |
| `RtoL` | `TT` lipit, fără alternare |
| `RtoGlide`, `LtoGlide` | `TT` jos, partea din față a dosului ridicată spre boltă (ca la I); varianta cu buze rotunde pentru L→U |
| `Devoicing`, `Omission`, `WeakOrShort`, `AffricatePlace`, `RtoVowel`, `LtoR` | fără poză proprie; se folosesc litera și poza corectă |

**Fișierul** `spec/poses/<sound>.json`:

```json
{ "sound": "s", "version": 1, "asset": "mouth.fm3d@sha256:…",
  "review": { "status": "draft", "by": null, "date": null },
  "lips": { "wide": 1.12, "open": 0.08, "round": 0.0, "tol": { "open": 0.35, "wide": 0.08, "round": 0.25 } },
  "poses": {
    "rest":    { "lf": [/*24*/], "tongue": [/*32*/] },
    "correct": { "lf": [], "tongue": [], "targets": [{ "lm": "TT", "mm": [0, -3, -3], "w": 3 }], "residualMm": 0.6 },
    "errors":  { "Interdental": { "lf": [], "tongue": [] }, "Lateral": { "lf": [], "tongue": [], "air": "left" } } },
  "timeline": [ { "t": 0.00, "pose": "rest" }, { "t": 0.18, "pose": "correct", "spring": "soft" },
                { "t": 0.90, "pose": "correct" }, { "t": 1.10, "pose": "rest" } ],
  "air": "centerCold", "camera": "sideCut", "sagittal2d": { /* canalele SagittalPose */ } }
```

**Pagina logopedului:** `fonia-brain/gallery/gnm-review.html`, crescută din `gnm.html`. Încarcă `mouth.fm3d` și pozele; arată pe sunet cele trei vederi, poza corectă și erorile, lângă descrierea românească. Lista de bifat: vârf, lamă, dos, margini, falcă, buze, dinți. Butoane „Aprobat” / „De corectat” cu comentariu; exportă `review_<nume>_<data>.json` (fără rețea). **Aplicația arată secțiunea doar pentru pozele `approved`**; până atunci sunetul are numai vederea din față (buze și dinți).

**Cartea față de logopedie** (F3): buze rotunde la Ș, J, CE, GE; vârful jos la S (CJRAE); R acceptat și cu o singură bătaie. Indicația cărții (colțurile trase) se păstrează în `lipsAlt`, numai pentru traseul `DICTION`.

## 9. H8: `Mouth3D` în `:core:mouth3d`

| Subiect | Decizie |
|---|---|
| Suprafață | `AndroidView` + `TextureView` cu un fir GL propriu (EGL 1.4, context ES 3.0). `TextureView` se compune corect cu Compose (decupare, alfa, tranziții). Desen la cerere: 60 fps doar cât se mișcă ceva, zero în repaus |
| Morfare pe CPU | `pos = bază + Σ wₖ·Δₖ` pe deltele rare: ~0,4 milioane de operații pe cadru, < 1 ms [Estimare]; normalele recalculate pe ~10.500 de triunghiuri, ~0,5 ms; `glBufferSubData` ~130 KB. Dacă pe telefon trece de 2 ms, bucla se mută în C++ cu NEON |
| Camere | `front` (24° câmp); `threeQuarter` (35° lateral, −8° sus); `sideCut` (profil, plan de tăiere x = 0); `inside` (de sus, blocul de sus ca fantomă, zonele de contact ale limbii evidențiate; etapa M6) |
| Animație | un arc critic amortizat pe fiecare greutate (ω = 18 rad/s; „soft” 10), integrat semi-implicit, cu `dt` plafonat. Bătaia lui R folosește chei, nu arcuri |
| Sincron cu clipul-model | ceasul e poziția din `PlaybackEngine`; cheile se așază pe timpii fonemelor din alinierea clipului (livrată de I5), cu 60 ms avans, ca gura să ajungă înaintea sunetului [Propunere] |
| Atingere | tragere cu un deget: ±60° lateral, ±25° vertical, revenire elastică după 2 s; dublă atingere: secțiune pornită/oprită. Fără zoom la copil |
| Bugete | cadru ≤ 4 ms; ≤ 12.000 de triunghiuri; ≤ 8 MB RAM; pornire < 150 ms; MSAA 4× unde există [Estimare, măsurat la M4] |
| Rezervă 2D | dacă ES 3 lipsește, telefonul e `isLowRamDevice` sau 30 de cadre la rând trec de 12 ms: logica `SagittalView` existentă (`SagittalPose`), cu culorile noi, hrănită din blocul `sagittal2d` |
| Accesibilitate | fiecare poză are o descriere pentru cititorul de ecran; mișcare redusă = treceri de 150 ms fără rotire automată |

**Shaderul** (fragment, GLSL ES 3.00):

```glsl
uniform vec4 uClip; uniform vec3 uLight, uRim; uniform vec3 uPal[8];
in vec3 vPos, vNrm, vView; flat in int vPart; in float vFade; out vec4 o;
void main() {
  if (dot(vec4(vPos, 1.0), uClip) < 0.0) discard;              // secțiunea
  vec3 base = uPal[vPart];
  if (!gl_FrontFacing) { o = vec4(mix(base, uPal[7], 0.55), vFade); return; }  // interior plat
  vec3 n = normalize(vNrm);
  float d = dot(n, uLight) * 0.5 + 0.5;                         // half-Lambert
  float ramp = 0.5 * smoothstep(0.34, 0.40, d) + 0.5 * smoothstep(0.68, 0.74, d);  // 3 tonuri
  float rim = pow(1.0 - max(dot(n, normalize(vView)), 0.0), 2.5);
  o = vec4(base * (0.72 + 0.28 * ramp) + uRim * rim * 0.35, vFade);
}
```

**Pe iOS:** același `mouth.fm3d` și aceleași `spec/poses`. Desenarea se rescrie în Metal (`MTKView`); shaderul are 15 linii. Contractul e `spec/mouth3d/vectors.json`: pentru fiecare poză, pozițiile reperelor după morfare (toleranță 0,05 mm), plus imagini de referință 512×512 pe cameră.

## 10. H9: fuziunea cu sunetul

**Alinierea în timp.** Fiecare `MouthState` poartă timpul cadrului camerei, iar segmentul sunetului-țintă `[t₀, t₁]` vine din `:engine:judge`, pe același ceas. La M0 se măsoară decalajul cameră–microfon cu o bătaie din palme. Fereastra camerei: `[t₀ − 40 ms, t₁ + 40 ms]`; pentru sunete scurte (Ț, CE, GE, R) ±120 ms în jurul centrului, fiindcă forma buzelor e anticipată. Se folosesc doar cadrele `Good`; sub 3 cadre, camera lipsește.

**Dovada camerei** (`CameraEvidence`): pentru fiecare gest, `Yes`, `No` sau `Unknown`, din mediana pe fereastră: `v = sigmoid((Δ − prag_copil)/σ)`; încrederea `c = poartă · min(1, cadre/6) · |2v − 1|`; sub 0,6 rezultatul e `Unknown` [Propunere]. Pragurile stau în blocul `camera` din `spec/fusion/<sound>.json`. Încercarea salvată poartă doar numerele, nu cadre.

| Audio | Camera | Rezultat |
|---|---|---|
| `Correct` | orice | `Correct`. Dacă buzele au fost sigur altfel: o notă blândă doar în `ParentCard` |
| Ș, J, CE, GE auzite spre S/Z/Ț (`PlaceSwap`, `AffricatePlace`) | buze sigur nerotunjite | sfatul despre buze: „Fă buzele pâlnie” |
| la fel | buze rotunde | sfatul despre limbă („Limba puțin înapoi”) + `Mouth3D` în secțiune |
| S, Z, Ț auzite spre Ș/J/CE | buze sigur rotunde | „Zâmbește larg” |
| la fel | buze întinse | sfatul despre limbă |
| S, Z, Ț, Ș, J cu indicele frontal ridicat (AB §3.2, punctul 4) | limba între dinți: `Yes` din H10 validat | `NamedError(Interdental)`, dacă e deblocat |
| la fel | `No` sau `Unknown` | `Almost`; adultul vede „posibil interdental sau dentalizat” |
| L auzit ca U (`LtoGlide`) | buze rotunde | „Zâmbește, vârful limbii sus” |
| R, L sau `WeakOrShort`, necorect | falca prea închisă (`A` sub jumătate din mediana copilului la acel sunet) | „Deschide puțin gura” |
| Cuvânt cu P, B sau M, poarta de cuvânt nesigură | numărul de închideri ale buzelor se potrivește cu cuvântul | sprijină „cuvântul a fost încercat” (`Unsure` în loc de `Retake`); nimic mai mult |
| `Unsure` sau `Retake` | orice | rămâne așa; camera alege doar ce vede adultul |
| orice | `Unknown` sau poarta căzută | exact verdictul audio |

**Trei reguli fixe:** (1) camera nu coboară niciodată un `Correct`; (2) camera nu produce singură un `NamedError`: doar alege între sfaturi când audio a spus deja „nu încă” și confirmă `Interdental`; (3) când nu e sigură, tace. Orice sfat ales cu camera trece prin deblocarea din `spec/unlock.json` (F5); până atunci apare doar la adult, „în probă”.

## 11. Confidențialitate și siguranță

| Subiect | Regula |
|---|---|
| Cadre | doar în RAM, în buffere refolosite; niciun cadru pe disc, niciunul trimis. Fără permisiunea `INTERNET` (F1), verificat pe manifestul final |
| Identitate | nicio recunoaștere a feței, niciun vector de identitate, nicio emoție dedusă. Punctele feței nu se salvează |
| Ce se salvează | cu încercarea: cel mult 8 numere (de exemplu `round`, `apertureMm`, cadre folosite). Pe profil: `irisMm`, dacă s-a făcut calibrarea cu moneda. Ambele se șterg la „Șterge tot” |
| Repausul | doar în RAM, pe durata ecranului |
| Indicator | punctul verde al sistemului (Android 12+) plus o pastilă proprie „Camera e pornită”, cu oprire dintr-o atingere. Camera merge doar în `MIRROR`, în pașii `Warmup` cu verificare și se închide la `ON_STOP` [Verificat: comportamentul există] |
| Explicația pentru părinte | înainte de prima cerere de permisiune: „Camera e o oglindă. Telefonul se uită doar la forma buzelor. Nicio imagine nu se salvează și nu pleacă de pe telefon.” |
| Fotosensibilitate | nimic nu clipește de peste 3 ori pe secundă; strălucirile cresc și scad în ≥ 300 ms; fără roșu; ecranul alb folosit ca lumină urcă în 500 ms |
| Fără permisiune | totul merge: `MIRROR` arată `Mouth3D` mare, încălzirile se încheie cu „Am făcut”, fuziunea folosește doar sunetul. Permisiunea nu se cere a doua oară fără gestul adultului |
| Colectorul (H10) | singura excepție de la „niciun cadru salvat”: build separat, acord scris separat, doar decupajul gurii; vezi §15 |

## 12. Validare

| Ce măsurăm | Cum | Minimum | Țintă [Propunere] |
|---|---|---|---|
| Deschiderea în mm | distanțiere de 5, 10, 20 mm între dinți, la 25/35/50 cm, în două lumini; Bland–Altman | 10 adulți × 3 × 3 × 2 | abatere ≤ 1 mm; limite ±3 mm |
| Lățimea în mm | riglă pe buze în repaus și în zâmbet | 10 adulți + copiii disponibili | ±3 mm |
| Scara din iris | comparație cu moneda | 10 adulți, ≥ 5 copii | diferență ≤ 10 % |
| Rotunjirea și potrivirea | pozele „repaus / pupic / zâmbet”, câte 5; adultul bifează „a făcut” | 15 persoane, ≥ 5 copii | „potrivit” fals ≤ 10 %; abținere ≤ 30 % (Q §6) |
| Limba afară și direcțiile | un om apasă în timp real ce vede; se compară cu detectorul. Fără video salvat | 15 persoane × 5 clase × 8 probe = 600 | afară: sensibilitate ≥ 0,90, fals ≤ 5 %; direcții ≥ 0,80; κ raportat |
| Viteză | fps, ms pe cadru, memorie, pe telefonul real | — | 30 fps în oglindă |
| Familii (23–25.11) | cât timp poarta e bună, câte încălziri trec cu camera și câte cu „Am făcut” | 3–5 familii | doar descriptiv |

**Putem spune:** „măsoară deschiderea și lățimea gurii, cu eroare de X mm pe N adulți”; „verifică limba afară, la stânga și la dreapta, cu acord κ față de un om, pe N persoane”; „nicio imagine salvată”. **Nu spunem:** „vede limba când copilul vorbește”, „milimetri exacți la copii”, „precizie clinică”, niciun procent pe copii fără numărul lor.

## 13. Ce există la fiecare etapă și ordinea de tăiere

| Etapă | Există |
|---|---|
| M0 | H1: `FaceFrame` v2 trece prin seam; ecran de depanare cu inelele desenate; fps și decalajul cameră–microfon măsurate. H6: scriptul scoate primul `mouth.fm3d` |
| M1 | H2 complet, cu teste pe PC. H6 final. H8 desenează poza de repaus pe telefon. H7: S și Ș rezolvate |
| M2 | `MIRROR` cu `LipTargetOverlay` pentru cele 9 sunete (4 clase de buze). Gimnastica: D2–D5 și D8–D10 cu camera. `Mouth3D` static pentru toate sunetele, din față; secțiunea doar la pozele aprobate. Pagina logopedului, luni |
| M3 | H5 reluarea. H8 animat și legat de clipul-model. H9 v1: sfatul buze/limbă, „în probă” la adult |
| M4 | cifrele din §12, bugetele măsurate, mișcare redusă, rezerva 2D, ecranul de licențe, pozele de eroare pentru tipurile deblocate |
| M5 | capturi și demo-uri; nimic nou |
| M6 | H10; `Interdental` în fuziune; camera `inside`; vectorii pentru iOS |

**Ordinea de tăiere în capitol** (de acord cu §6 din master): 1) H10; 2) camera `inside` și rotirea cu degetul; 3) calibrarea cu moneda; 4) H5; 5) din H9 rămâne doar sfatul buze/limbă; 6) direcțiile limbii (rămâne „afară”); 7) animația `Mouth3D` (rămân pozele statice). **Nu cad:** H1, H2, conturul-țintă de bază, o gură 3D statică pentru fiecare sunet.

## 14. Pachetele de lucru

| Pachet | Scop și pași | Fișiere și module | Teste / „gata” | Ore-agent | Depinde de | Riscuri |
|---|---|---|---|---|---|---|
| **H1** punctele feței | lărgește `FaceObservation`/`FaceFrame`; expune inele, iris, 52 de scoruri, luminanțe, `tongueModelScore`; `MouthCrop`; modul 1280×960; mută `FaceRecording` în `debug` | `:platform:camera` (`FaceAnalysis`, `FaceLandmarkerPipeline`, `CameraFaceTracker`, `FrameSampler`), `:engine:face` (`FaceLevels`, `CameraFaceSource`) | testele vechi verzi; test de indici față de imaginea de referință; test de oglindire; test „fără fișiere”; fps pe telefon ≥ 24 | 5 | A1 | fps la 1280×960; indicii din memorie greșiți |
| **H2** starea gurii | `MouthStateEngine`, `RestBaseline`, `FaceQuality`, scara din iris, moneda, `MouthTrack`, câmpurile din `LiveSnapshot` | `:engine:face` (nou: `MouthState.kt`, `MouthMetrics.kt`, `Quality.kt`), `:engine:live` | vectori sintetici (inele generate cu deschideri cunoscute, ±0,2 mm); test de histerezis; fără alocări pe cadru | 6 | H1, E1 | scara irisului la copii; z nesigur |
| **H3** oglinda și ghidajul | `MirrorView`, `LipTargetOverlay`, potrivirea, stările vizuale, scânteile prin `FxGate`, mișcare redusă | `:feature:mirror`, `:core:designsystem` | teste pe formula țintei; capturi verificate de agentul de design pentru 4 stări; pe telefon: fără tremur vizibil | 7 | H2, D1, D5, C4 | „potrivit” prea ușor; decalaj între overlay și preview |
| **H4** limba afară și gimnastica | modelul de culoare, zonele, votul în timp, `WarmupCheck`, verificările D2–D5 și D8–D10 | `:engine:face` (`TongueHeuristic.kt`), `:feature:lesson` (pasul `Warmup`) | teste pe decupaje sintetice colorate; lista de eșecuri din §5.1 ca teste; pe telefon, 3 persoane × 5 clase | 6 | H1, H2, I3 | lumină, ten, ruj; se abține prea des |
| **H5** reluarea | `MouthRing`, ecranul de reluare, alinierea la sunet, ștergerea, `FLAG_SECURE` | `:platform:camera`, `:feature:mirror` | test de ștergere (buffer zero după ieșire); memorie ≤ 7 MB; redare cursivă | 4 | H1, H8, E1 | memorie pe telefoane mici; părinți incomodați (întrebarea 4) |
| **H6** exportul gurii | scriptul, formatul, cititorul Kotlin, `NOTICE` | `tools/gnm/`, `spec/mouth3d/mouth.fm3d`, `:core:mouth3d` (`Fm3dReader.kt`) | dus-întors: pozițiile recitite ≤ 0,02 mm; mărime ≤ 2 MB; SHA în antet | 5 | A3 | găuri la marginea peticului; cuantizare vizibilă |
| **H7** pozele | alegerea reperelor, rezolvarea, coliziunile, 9 fișiere de poze, pagina logopedului | `tools/gnm/solve_poses.py`, `spec/poses/*.json`, `fonia-brain/gallery/gnm-review.html` | reziduu ≤ 1 mm pe țintele cu pondere mare; zero pătrunderi; semnătura logopedului pe fiecare poză afișată în secțiune | 8 (+ 30 min logoped) | H6 | spațiul limbii GNM nu atinge „ceșcuța” sau șanțul; pozele greșite învață greșit |
| **H8** desenarea 3D | firul GL, morfarea, shaderul, camerele, arcurile, sincronul, atingerea, rezerva 2D | `:core:mouth3d` (`Mouth3D.kt`, `GlThread.kt`, `Morph.kt`, `PoseSpring.kt`), `:core:designsystem` | vectorii din `spec/mouth3d/vectors.json`; imagini de referință; cadru ≤ 4 ms pe telefon; fără scurgeri la 50 de intrări și ieșiri | 10 | H6, H7, D1, I5 | context GL pierdut; `TextureView` lent pe unele telefoane |
| **H9** fuziunea | `CameraEvidence`, ferestrele, tabelul de decizie, blocul `camera` din `spec/fusion`, numerele salvate | `:engine:face`, `:engine:judge`, `:engine:session` | teste-tabel pentru fiecare rând din §10; testul „camera nu coboară un `Correct`”; testul „camera absentă = verdict audio” | 5 | H2, E5, E7, F1 | decalaj de ceas; sfat greșit despre buze |
| **H10** modelul mic | acordul și colectarea decupajelor, etichetarea, antrenarea, exportul, comutarea euristică → model | colectorul (B1), `tools/tongue/`, `:platform:ml`, `:engine:face` | pragurile din §5.3 pe persoane nevăzute; ≤ 1 MB; ≤ 4 ms | 10 (+ antrenare) | B1, B2, B3, H4, E9 | prea puține persoane; acordul părinților |

Total: ~66 de ore-agent. H6 → H7 → H8 și H1 → H2 → H3/H4 merg în paralel, pe subagenți diferiți (module diferite).

## 15. Propuneri de schimbare și întrebări deschise

### Propuneri de schimbare (pentru `09_DECIZII_NOI.md`)

| # | Unde | Propunere | De ce |
|---|---|---|---|
| 1 | §4 „Cadrele stau doar în memorie”, pachetele B1–B2 | **Colectorul** să poată păstra, cu acord separat, decupaje ale gurii la gimnastică | fără ele H10 nu are date; e singura excepție și trebuie scrisă explicit |
| 2 | §3, nume comune | să intre: `MouthState`, `FaceQuality`, `RestBaseline`, `TongueState`, `CameraEvidence`, `MouthRing`, `WarmupCheck` | sunt folosite de capitolele 02, 03 și 06 |
| 3 | §3, `spec/` | dosar nou `spec/mouth3d/` (`mouth.fm3d`, `vectors.json`) | contractul cu iOS pentru gura 3D |
| 4 | §3, `LiveSnapshot` | câmpurile gurii sunt „ultima valoare ținută” la 30 Hz, cu `faceAgeMs` | banda e la 100 Hz, camera nu |
| 5 | M2, „prima gură 3D” | secțiunea (cu limba) apare doar la pozele aprobate de logoped; până atunci, vederea din față | recenzia e tot luni; o limbă greșită învață greșit |
| 6 | §5, valul 3 | `Interdental` cere H10 validat; euristica H4 nu ajunge în vorbire | AB §3.2, punctul 4 |
| 7 | ecranul `MIRROR` | `FLAG_SECURE`, oprit în build-ul de demo | D14 cere capturi, copiii cer protecție |

Fără conflict cu D7 (peticul din jurul gurii nu are culoare de piele) și cu F6 (motor propriu OpenGL ES, deși U recomanda Filament).

### Întrebări deschise

1. Ce telefon e cel de test (versiune de OpenGL ES, rezoluția camerei frontale)?
2. Părinții din weekend acceptă bifa separată pentru decupajele gurii?
3. Logopedul confirmă „vârful jos” la S și cele patru clase de buze din §4?
4. Reluarea arată gura copilului pe ecran: e în regulă pentru familii și pentru școli?
5. Calibrarea cu moneda intră în v1 sau rămâne pentru după semifinală?
6. Camera pornește implicit la `Warmup` sau doar după ce adultul o activează o dată?
7. Capitolul 06 livrează timpii fonemelor pentru clipurile-model (necesari la sincronul din §9)?
8. Scorul de limbă al modelului de puncte (`tongueModelScore`) e folositor? Se află la M0, cu 3 persoane.
