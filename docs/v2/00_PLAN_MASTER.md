# Fonia 2: planul de implementare (document-mamă)

Scris pe 20 noiembrie 2026, pe baza a 18 rapoarte de research (`Fonia_Research_Hub/redesign/`, M–Z și AA–AD), a auditului codului Android (`fonia-brain/notes/android-inventory.md`) și a cărții „Șase sași în șase saci”, citită integral de patru agenți (`redesign/carte_ivanov/`).

Acest document fixează deciziile, numele și ordinea. Capitolele 01–08 detaliază fiecare zonă și **nu au voie să schimbe** un nume sau o decizie de aici fără o notă în `09_DECIZII_NOI.md`.

| Capitol | Ce conține |
|---|---|
| `01_PRODUS_SI_ECRANE.md` | Ce face aplicația, fiecare ecran cu stările lui, fluxurile, textele |
| `02_ARHITECTURA.md` | Module, fire, date, mutarea codului vechi, build, CI |
| `03_MOTORUL_DE_SUNET.md` | Cum află aplicația dacă sunetul e corect și ce e greșit, pentru toate cele 9 sunete |
| `04_CAMERA_AR_GURA_3D.md` | Oglinda, ghidajele pe față, gimnastica limbii, gura 3D |
| `05_LITERA_VIE_SI_DESIGN.md` | Fontul Allo animat, sistemul de design, efecte, sunet, vibrații, Foni |
| `06_METODA_SI_CONTINUT.md` | Scara fiecărui sunet, cuvinte, exerciții, vocea lui Foni, traseul de dicție |
| `07_QA_VALIDARE_AGENTI.md` | Teste, agenți de QA, probele cu logopezi și familii, riscuri |
| `08_CALENDAR_SI_PACHETE.md` | Pachetele de lucru, zi cu zi, cu ce pornește fiecare sesiune |

## 1. Ce construim, într-o pagină

**Fonia 2 e o aplicație Android în care copilul își exersează sunetele vorbind cu o literă vie.** Litera aude vocea și reacționează pe loc: se umflă cât ții sunetul, se ascute la S și se rotunjește la Ș, sare când cuvântul iese bine. Când nu iese, litera arată **ce să faci altfel** (limba în spatele dinților, aerul pe mijloc, buzele rotunde), nu doar că n-a ieșit. Când telefonul nu e sigur, litera tremură și îl întreabă pe adult.

Patru lucruri o deosebesc de orice există în română:

1. **Verificare reală a sunetului, cu motivul.** Trei judecători (măsurători acustice, un model de foneme, un model de cuvinte) hotărăsc împreună și se abțin când nu sunt siguri. Fiecare copil e judecat față de propriile lui încercări reușite.
2. **Oglinda cu ghidaje și gura 3D.** Camera vede buzele și falca copilului, desenează forma-țintă peste fața lui și arată alături o gură 3D cu dinți și limbă, pe care o poți privi și pe dinăuntru.
3. **O metodă, nu un joc cu premii.** Scara logopedică în 14 trepte pentru fiecare sunet, cu structura de exerciții din manualele de dicție românești, în sesiuni de 4 minute construite pe ce se știe despre atenția copiilor.
4. **Adultul e parte din buclă.** Un card scurt îi spune ce are de făcut, degetul lui mare calibrează telefonul, iar raportul pentru logoped pleacă din aplicație ca PDF.

Pentru adolescenți și adulți: un traseu de dicție după ordinea manualului și „Tu, fără ăăă”, în care îți auzi propria voce cu umpluturile tăiate.

**Tot ce ține de voce și imagine rămâne pe telefon. Versiunea 1 nici nu cere permisiunea de internet.**

## 2. Decizii

### Luate de echipă (20.11)

| # | Decizie |
|---|---|
| D1 | Aplicația se reface cap-coadă până la încărcarea HSIC (26–27.11). Finala e pe 5.12 |
| D2 | Android întâi (Kotlin + Compose + C++). Ștefan ia pull și reface în Swift |
| D3 | În centru e copilul cu părintele |
| D4 | Rămâne papagalul Foni, îmbunătățit |
| D5 | Culori: albastru, roz, verdele papagalului (plus galbenul aripii), puține roluri |
| D6 | Fontul Allo, animat, cu toate funcțiile lui; interpolator propriu de contururi |
| D7 | Gura 3D: doar gura, stilizată (fără cap realist) |
| D8 | **Toate cele 9 sunete** sunt în aplicație; copilul alege ce exersează |
| D9 | La fiecare sunet, exerciții scurte în stilul cărții, cu litere animate: o animație la rostirea bună și una diferită când nu iese |
| D10 | Vocea lui Foni: generată cu ElevenLabs |
| D11 | Test pe un telefon Android real, prin USB |
| D12 | O sesiune de 4 minute pe zi, apoi „încă una?” |
| D13 | O serie de zile (streak) e în regulă deocamdată; mecanica de retenție se regândește după semifinală |
| D14 | Pentru concurs livrez capturi și demo-uri; PPT-ul și filmul le montează echipa |
| D15 | Probe cu logopezi și familii: 21–22, 23–24 și 25 noiembrie |
| D16 | Versiunea 1 trebuie să aibă: onboarding + drumul sunetelor, oglinda, zona adultului cu raportul, „Tu, fără ăăă” |

### Luate de mine, de confirmat (se pot răsturna ieftin)

| # | Decizie | De ce |
|---|---|---|
| F1 | **Fără permisiunea `INTERNET` în v1.** Modelele stau în APK | E cea mai puternică promisiune de confidențialitate și scapă de descărcări care pot pica la demo. Costul: APK mare (țintă ≤ 200 MB pentru arm64) și niciun LLM din cloud pentru adulți deocamdată |
| F2 | Textele din carte cuvânt cu cuvânt intră doar după acordul scris al autoarei. Până atunci: exerciții noi, pe structura ei, plus frământări tradiționale | Drepturi de autor. Pentru copii oricum trebuie conținut nou: textele ei sunt pentru adulți |
| F3 | Unde cartea și logopedia se contrazic, urmăm logopedia (de exemplu buze rotunjite la Ș, nu zâmbet). Creionul între dinți nu apare la copii | Notele agenților care au citit cartea |
| F4 | Copilul nu vede niciodată „greșit”, roșu sau un sunet de eșec. „Animația când nu iese” e una care arată **ce să facă** | Regula din cod și din toate rapoartele; respectă și D9 |
| F5 | O eroare e **numită** copilului doar pentru tipurile deblocate. Deblocarea se face pe date, pe sunet și pe tip de eroare | Altfel riscăm sfaturi greșite. Vezi §5 |
| F6 | Gura 3D se desenează cu un motor mic propriu (OpenGL ES), nu cu o bibliotecă 3D mare | Un singur obiect, control total, ușor de refăcut pe iOS |
| F7 | Păstrăm folderele vechi și le dăm nume noi de proiect în Gradle; fără framework de DI; Navigation 3; SQL scris de mână | Recomandările din raportul AD; cel mai puțin risc |
| F8 | Păstrăm cel mult 3 înregistrări pe sunet, 90 de zile, criptate, ca azi | Fără ele nu există „ascultă-ți cea mai bună încercare” și „atunci și acum” |
| F9 | Lucrăm în `fonia-android`, pe ramura `v2`. Planul stă aici până pornește implementarea, apoi se copiază în `fonia-android/docs/v2/` | Ștefan vede tot prin pull |

## 3. Nume comune (toate capitolele le folosesc exact așa)

**Sunete** (`SoundId`): cheile din `fonia-content/json/sounds.json` pentru S, Ș, Z, J, Ț, CE/CI, GE/GI, R, L.

**Verdicte** (`Verdict`): `Correct`, `Almost`, `NamedError(kind)`, `Unsure`, `Retake`.
- Pentru copil: „Bravo!” / „Aproape!” / o instrucțiune de cel mult 8 cuvinte / „Hai să mai încercăm” (și adultul e întrebat) / „Mai spune o dată”.

**Tipuri de eroare** (`ErrorKind`): `Omission`, `Stopping`, `Devoicing`, `PlaceSwap`, `Lateral`, `Interdental`, `Deaffrication`, `AffricatePlace`, `RtoL`, `RtoGlide`, `RtoVowel`, `RUvular`, `LtoGlide`, `LtoR`, `WeakOrShort`.

**Treptele** (`Rung`, 14): `listen`, `warmup`, `isolated`, `syllables`, `back`, `wordsInitial`, `wordsMedial`, `wordsFinal`, `clusters`, `pairs`, `sentences`, `twister`, `retell`, `everyday`. Lacăt după `isolated`: nu se urcă până nu confirmă un adult.

**Cele două benzi de date:** `LiveSnapshot` (continuă, 100 de valori pe secundă, citită doar la desen) și `SessionEvent` (rară, fără pierderi, trece prin reducerul sesiunii).

**Module** (numele de proiect; folderul vechi între paranteze):
`:core:model`, `:core:domain` (`domain`), `:core:data` (`store`), `:core:glyphs` (nou), `:core:designsystem` (`design`), `:core:navigation` (nou), `:engine:dsp` (`dsp`), `:engine:live` (nou), `:engine:judge` (`speech` + `phones`), `:engine:face` (`face`), `:engine:session` (nou), `:platform:audio` (`audio`), `:platform:camera` (`facecamera`), `:platform:ml` (+ `whisper`), `:core:mouth3d` (nou), `:feature:onboarding`, `:feature:path`, `:feature:lesson`, `:feature:mirror`, `:feature:adult`, `:feature:speaking`, `:app`, `:benchmark`, `build-logic/`, `spec/`.

**Componente:** `LiveLetter` (litera-erou), `LiveWord` (cuvânt cu 2–50 de litere), `StickerText`, `FoniMascot`, `PathNode`, `ContinueButton`, `MicCapsule`, `VerdictStamp`, `MirrorView`, `LipTargetOverlay`, `Mouth3D`, `AdultGate`, `ParentCard`.

**Ecrane:** `ON-0…ON-7` (onboarding), `PATH` (Drumul), `LESSON` (cu pașii `Listen`, `Warmup`, `Hold`, `Syllables`, `Words`, `Pairs`, `Sentence`, `Twister`, `Retell`, `Mission`), `MIRROR`, `END`, `ADULT-HOME`, `ADULT-SOUNDS`, `ADULT-CALIBRATE`, `ADULT-REPORT`, `ADULT-SETTINGS`, `TEEN-HOME`, `DICTION`, `NO-UM`, `MINUTE`.

**Praguri și modele ca date:** `spec/thresholds/*.json`, `spec/fusion/<sound>.json`, `spec/unlock.json`, `spec/poses/<sound>.json`. Fiecare încercare salvată poartă versiunile lor.

## 4. Arhitectura, pe scurt

- **Tot ce hotărăște e Kotlin pur** (`:engine:*`, `:core:*`), testat pe PC în secunde. Android aduce doar microfonul, camera, rularea modelelor și ecranele. Azi trec 910 teste în 18 secunde; le păstrăm verzi la fiecare pas.
- **Banda continuă:** microfon (48 kHz, sursa fără procesare) → inel fără blocări → firul de analiză → `LiveSnapshot` → litera îl citește la fiecare cadru, doar în faza de desen. Țintă: sub 80 ms de la voce la literă.
- **Banda de evenimente:** început și sfârșit de încercare, verdict → reducer pur al sesiunii (`:engine:session`) → starea ecranului. Reducerul nu face I/O; timpul intră ca eveniment. Același fișier de test îl va rula și Swift.
- **Camera:** CameraX → modelul de puncte ale feței → `:engine:face` (starea gurii) → `LipTargetOverlay` și fuziunea cu sunetul. Cadrele stau doar în memorie.
- **`spec/`** e contractul cu iOS: vectori de test, praguri, coeficienți, tokenuri de design, contururile literelor, pozele gurii.
- **Ce păstrăm din codul de azi:** motoarele (`dsp`, `domain`, `store`, `speech`, `face`, `audio`, `phones`, `whisper`, `facecamera`). **Ce rescriem:** tot UI-ul (`design`, ecranele din `features`), navigarea și starea (`AppModel` dispare). Logica fără UI din `features` (`TakeJudge`, `Verdict`, `SessionRun`, `AudioHub`, `SpeechHub`, `MlHub`) se mută în module noi.

## 5. Cum ajunge „toate sunetele, cu ce e greșit” să fie adevărat

1. **Azi:** detectoarele există și spun deja motivul (`SoundOutcome`, `LiveFault`), dar pragurile sunt reglate doar pe voci de adult.
2. **Sâmbătă–duminică (21–22):** înregistrăm cu aplicația-colector adulți și logopezi care spun fiecare sunet corect și cu fiecare greșeală tipică, plus copiii disponibili. Din ele ies primii coeficienți ai fuziunii.
3. **Fiecare copil își calibrează telefonul:** adultul confirmă câteva încercări bune; de acolo înainte sunetul lui e comparat cu al lui.
4. **Deblocare pe date** (`spec/unlock.json`), în valuri. Valul 1: S↔Ș, lipsa vocii (Z→S, J→Ș), oprirea (S→T), omisiunea, R→L. Valul 2: africatele, R→I, L→I, R din gât. Valul 3: lateral și interdental (interdentalul doar cu camera).
5. **Până la deblocare,** un tip de eroare apare doar la adult, în „Pentru adult”, cu mențiunea „în probă”. Copilul primește reacția literei la efort și degetul mare al adultului.
6. **Ce spunem pe 26:** câte încercări am înregistrat, cât de des se potrivește telefonul cu logopedul pe datele noastre, și regula de deblocare. Fără procente „pe copii” pe care nu le avem.

## 6. Etape

| Etapă | Când | Ce există la final (o aplicație întreagă de fiecare dată) |
|---|---|---|
| **M0 Scheletul** | sâmbătă 21, la prânz | Ramura `v2`, modulele noi, CI. Colectorul de înregistrări pe telefon. Microfon → S/Ș → `LiveLetter` → încercare salvată |
| **M1 S și Ș cap-coadă** | duminică 22, seara | Onboarding scurt, Drumul, lecție completă de 4 minute cu trei mini-jocuri, verdict cu motiv pentru S și Ș, final cu „încă una?” |
| **M2 Toate sunetele + oglinda** | luni 23, seara | Cele 9 sunete pe scară, judecătorul nou pentru toate, oglinda cu ghidaj pe buze, gimnastica limbii, prima gură 3D |
| **M3 Adultul + adolescenții** | marți 24, seara | Poarta, „Cum merge”, calibrarea, raportul PDF, ștergerea totală, „Tu, fără ăăă”, începutul traseului de dicție |
| **M4 Lustruit și măsurat** | miercuri 25, seara | Efecte, sunete, vibrații, Foni nou, accesibilitate, viteză măsurată pe telefon, cifrele din probe, APK semnat |
| **M5 Încărcarea** | joi 26 – vineri 27 | Capturi și demo-uri pentru PPT și film, reparații, nimic nou |
| **M6 Finala** | 28.11 – 4.12 | Valul 2 de erori, modelul mic pentru limbă, restul traseului de dicție, sprijin pentru portarea iOS |

**Ordinea de tăiere, dacă timpul se termină:** întâi cade traseul de dicție dincolo de primele module, apoi modelul mic pentru limbă, apoi erorile din valul 2, apoi gura 3D în mișcare (rămâne statică). Nu cad niciodată: lecția cu litera vie, verdictul cu abținere, zona adultului cu raportul, oglinda de bază.

## 7. Pachete de lucru (detaliate în capitolul 08)

| Grup | Pachete |
|---|---|
| **A. Pornire** | A1 ramura și modulele · A2 CI și verificările automate · A3 modul „fixture” și `spec/` · A4 unelte, skill-uri, telefon · A5 conținutul la zi |
| **B. Colectorul** | B1 ecranul de înregistrat și exportul · B2 acordul și protocolul · B3 unealta de ascultat și etichetat pe PC · B4 arhiva de date |
| **C. Litera** | C1 exportul contururilor · C2 interpolatorul · C3 `LiveLetter`, `LiveWord`, `StickerText` · C4 efectele literei · C5 cele șase mini-jocuri |
| **D. Design** | D1 tokenuri și temă · D2 componente · D3 Foni nou · D4 sunete și vibrații · D5 mișcare |
| **E. Sunet** | E1 captura și banda continuă · E2 trăsături noi · E3 modelul de foneme lărgit · E4 calibrarea pe copil · E5 fuziunea și decizia · E6 antrenare și evaluare · E7 mesajele și animațiile pe eroare · E8 porțile de captură și de cuvânt · E9 modelele în APK |
| **F. Sesiunea** | F1 reducerul · F2 constructorul de lecții · F3 progresia și lacătul · F4 baza de date v3 · F5 seria, ținta săptămânală, amintirea |
| **G. Ecranele** | G1 cochilia și navigarea · G2 onboarding · G3 Drumul · G4 lecția · G5 verdictul · G6 finalul · G7 zona adultului · G8 cardurile pentru adult · G9 raportul, exportul, ștergerea · G10 adolescenți și dicție · G11 „Tu, fără ăăă” · G12 „Un minut pe zi” |
| **H. Camera și 3D** | H1 punctele feței · H2 starea gurii · H3 oglinda și ghidajul · H4 limba afară și gimnastica · H5 reluarea cu încetinitorul · H6 exportul gurii 3D · H7 pozele pe sunet · H8 desenarea 3D · H9 fuziunea cu sunetul · H10 modelul mic pentru limbă |
| **I. Conținut** | I1 schema nouă · I2 cuvinte, propoziții, perechi, poze · I3 încălzirea · I4 frământările · I5 replicile lui Foni și vocea · I6 textele de dicție · I7 mesajul către Carmen Ivanov și creditele · I8 textele pentru părinți |
| **J. QA și livrare** | J1 testele · J2 agenții de QA · J3 probele și analiza · J4 viteza pe telefon · J5 accesibilitate și confidențialitate · J6 APK-ul · J7 materialele pentru concurs · J8 predarea către iOS |

## 8. Cum lucrez

- **Un singur scriitor pe modul.** Lucrul paralel se face cu subagenți, fiecare în worktree-ul lui, pe pachete care nu ating aceleași fișiere.
- **„Gata” înseamnă:** teste verzi, captură de ecran verificată de un agent de design, rulat pe emulator în modul „fixture”, și, pentru sunet și cameră, încercat pe telefonul real.
- **Agenți de QA** rulează după fiecare etapă: motor, ecrane, flux pe dispozitiv, sunet pe înregistrări, viteză, confidențialitate, texte românești. Agenți de research pornesc doar când un pachet se blochează.
- **Fiecare decizie nouă** se scrie în `09_DECIZII_NOI.md`, iar ce aflu despre cod intră în `fonia-brain`.
- **Nu fac commit și nu împing** până nu spui tu; la fiecare etapă îți arăt ce s-a schimbat.

## 9. Ce am nevoie de la echipă

| Când | Ce |
|---|---|
| Sâmbătă dimineață | Telefonul conectat prin USB, cu „Depanare USB” pornită, și modelul lui |
| Sâmbătă–duminică | 5–10 adulți (și logopezii) care înregistrează 10 minute fiecare în colector; copiii disponibili, cu acordul părinților |
| Luni–marți | 3–5 familii, câte 10 minute pe aplicație, cu un logoped care notează în paralel |
| Luni | Un logoped care se uită 30 de minute la pozele gurii 3D și la lista de cuvinte |
| Miercuri | Ultima rundă de probe; alegem cifrele pentru slide-uri |
| Oricând | Mesajul către Carmen Ivanov, trimis de tine (ți-l pregătesc) |

## 10. Riscuri mari

| Risc | Ce facem |
|---|---|
| Cinci zile pentru tot | Etape care lasă mereu o aplicație întreagă; ordinea de tăiere de mai sus |
| Verdicte greșite la copii | Abținere, calibrare pe copil, deblocare pe date, adultul în buclă |
| Praguri reglate pe adulți | Colectorul din prima zi; copiii din probe intră în date |
| Camera nu vede limba | Gimnastica limbii doar cu limba afară; limba ascunsă se arată pe modelul 3D |
| Telefonul real se poartă altfel | Latența și sursa de microfon se măsoară sâmbătă, nu miercuri |
| APK prea mare sau memorie prea multă | Modele cuantizate, încărcate la cerere; măsurat la M2 |
| Vocea generată pronunță greșit silabe | Fiecare clip e verificat automat și ascultat pe sărite; lista merge la logoped |
| Date de la copii în probe | Acord scris al părinților, datele rămân pe telefonul și PC-ul echipei, ștergere la cerere |
| Afirmații medicale | Fără „diagnostic”, „terapie”, „tratează”; verificare automată a textelor |
