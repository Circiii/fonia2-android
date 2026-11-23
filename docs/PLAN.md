# Planul Fonia Android

Scris pe 6 noiembrie 2026. Deciziile sunt în `docs/decizii.md`. Planul se sprijină pe cinci documente din `../fonia-android-plan/`:

| Document | Ce conține |
|---|---|
| `01_inventar_ios.md` | aplicația iOS bucată cu bucată: fiecare ecran, prag, culoare, test, plus riscurile și ordinea de portare (6.796 de rânduri) |
| `02_stack_android_ml.md` | runtime-urile și modelele ML pe Android, verificate la sursă |
| `03_cercetare_produs.md` | funcțiile cu dovezi și praguri, regulile MDR/AI Act/GDPR, impactul, modul de colectare |
| `04_cercetare_date_resurse.md` | dataset-uri, resurse pentru română, repo-uri de refolosit, papers |
| `05_verificare_continut_ios.md` | verificarea listelor de cuvinte din iOS cu regulile `fonia-continut-ro` |

## 1. Pe scurt

1. **Întâi paritate, apoi ML.** Android reface exact aplicația iOS: aceleași ecrane, texte, praguri și verdicte. Fiecare test iOS devine test de paritate. Abia apoi vin funcțiile noi.
2. **iOS e specificația, research-ul e busola.** Ce face aplicația vine din codul iOS. Ce adăugăm, și în ce ordine, vine din hub, cu dovezi și praguri citabile.
3. **Pe telefon, nu în cloud.** Vocea și imaginea nu pleacă niciodată de pe telefon. Doar adulții, opțional și cu acord, pot trimite text și cifre unui LLM.
4. **Patru direcții ML:** pronunția copiilor, citirea și dislexia, adulții (engleză și vorbit în public), antrenorul adaptiv. Toate stau pe același motor de exersare: KR în aplicație, KP la logoped, feedback redus treptat, doza numărată.
5. **Validarea e produsul.** Telefonul loghează, fără audio, tot ce trebuie pentru pilotul de 30 de zile și pentru studiul de 90.

## 2. Unde suntem

| Ce | Stare |
|---|---|
| Decizii | luate pe 6 noiembrie (`docs/decizii.md`) |
| Repo-ul de conținut `Circiii/fonia-content` | creat, identic cu iOS (1.299 de fișiere), cu verificarea de sincronizare și verificarea listelor de cuvinte |
| Repo-ul `Circiii/fonia-android` | schelet Gradle cu modulele iOS, conținutul ca submodul, APK de debug care se construiește |
| CI | GitHub Actions: testele de paritate pe JVM și APK-ul de debug la fiecare push; cheie de citire pentru conținut |
| Faza 1, paritatea | **încheiată** (6 noiembrie): toate modulele iOS portate, cu testele lor (`docs/paritate.md`); ședințele identice octet cu octet cu iOS; Whisper dă aceleași verdicte ca iOS pe toate clipurile de evaluare; cele 9 drumuri UI din iOS și 5 noi trec în emulator |
| Faza 2, fundația ML | **în mare parte gata:** managerul de modele, shadow mode cu ZIPA, jurnalul pilotului pe telefon, contorul de doză. Telemetria Microsoft din ONNX Runtime e scoasă, iar build-ul pică dacă revine |
| Faza 3 | **pornită:** scorul pe foneme (`:phones`, AUC 0,979 pe perechile din conținut); proba de citire și raportul OMEN în lucru |
| Mărimea APK-ului | release, doar arm64: 121 MB (imagini 45 MB, biblioteci native 44 MB, voci 17 MB). Următorii pași: imagini WebP și un ONNX Runtime minimal, doar cu operatorii lui ZIPA |

## 3. Principii

- **Același comportament, nu ceva asemănător.** Aceleași nume, constante, ordine a pașilor și texte. Unde Kotlin cere altă formă, scriem de ce.
- **Numerica DSP reproduce convențiile vDSP:** împachetarea și scara FFT-ului real, ferestrele, rotunjirea „jumătate departe de zero”, `Float32`. Toate pragurile în dB depind de ele.
- **Modulele pure rulează pe JVM** (`:dsp`, `:domain`, `:store`, `:speech`, `:face`): testele merg pe Windows în câteva secunde și în CI fără emulator.
- **Ce nu se poate face identic se scrie** în `docs/paritate.md`, cu motivul și cu ce facem în loc.
- **Regulile din research se aplică din prima zi:** fără „risc”, „severitate”, „diagnostic”; fără emoții deduse; fără „greșit” și fără roșu de eroare; fără denoising înaintea S/Ș; doar licențe permisive în aplicație.

## 4. Arhitectura

| Modul Android | Modul iOS | Tehnologie | Testat cu |
|---|---|---|---|
| `:app` | `App/` | Activity + Compose, iconița, permisiunile, argumentele de lansare ca extras de intent | testele UI |
| `:features` | `FoniaFeatures` | Compose: rădăcina, file, primul drum, Acasă, lecții, ședință, ghid, antrenorul de gură, vorbit în public, jurnal, progres, zona adulților | `PathModelTests`, `SessionRunTests`, cele 9 drumuri UI |
| `:design` | `FoniaDesign` | Compose: paleta, fonturile din assets, componentele, Foni pe 13 straturi, gurile din față și din lateral, scenele, arcurile | galeria de componente comparată cu iOS |
| `:domain` | `FoniaDomain` | Kotlin pur, kotlinx.serialization, `SeededRandom` identic bit cu bit | 145 de teste iOS portate |
| `:dsp` | `FoniaDSP` | Kotlin pur, FFT propriu cu convențiile vDSP | 193 de teste iOS portate, plus evaluarea pe clipurile reale |
| `:store` | `FoniaStore` | SQLite prin `androidx.sqlite` (driverul inclus), aceeași schemă și același export | 60 de teste iOS portate |
| `:speech` | `FoniaSpeech` | judecătorii în Kotlin pur; runtime-ul Whisper nativ (§5.3) | 101 teste iOS, plus marjele din `tools/eval_speech` |
| `:face` | `FoniaFace` | maparea și pragurile în Kotlin pur; sursa camerei cu CameraX + MediaPipe Face Landmarker | 37 de teste iOS, plus înregistrări ale acelorași fețe |
| `:audio` | `FoniaAudio` + `FoniaAudioRT` | C++ (NDK) cu AAudio/Oboe pentru captură, inel SPSC, Media3 pentru redare | testele audio portate, plus bugetul de 5 ms pe hop |
| `content/` | resursele iOS | submodulul `Circiii/fonia-content`, copiat în assets la build | verificarea de sincronizare și de conținut |

Legăturile la rulare rămân ca pe iOS: captura → hopuri de 10 ms → `LiveAnalyzer`; clipul capturat → analiza DSP și Whisper → verdict; `SessionBuilder` și `Progression` → baza de date.

## 5. Faza 1: paritate cu iOS

Ordinea vine din inventar (§14.2). Fiecare pas se încheie cu testele lui verzi.

### 5.1 Valul 1: piesele pure (în lucru)

| Pas | Ce | Gata când |
|---|---|---|
| 0 | Conținutul: JSON, 939 de clipuri, imagini, fonturi | gata: submodul, assets generate la build |
| 1 | `:domain`: tipuri, încărcare, `SessionBuilder`, `Progression`, `VoiceIndex`, `Plural` | cele 145 de teste trec; aceleași ședințe ca pe iOS pentru aceleași semințe |
| 2 | `:dsp`: FFT → trăsături → YIN → R → S/Ș → analiza clipului → judecăți → accent și melodie → `LiveAnalyzer` | cele 193 de teste trec; `LiveAnalyzer` fără alocări, sub 5 ms pe hop |
| 3 | `:store`: schema, migrările, retenția, ștergerea, agregatele, exportul | cele 60 de teste trec; exportul identic octet cu octet |
| 6, 8 | `:speech` și `:face`, partea pură, plus interfețele pentru runtime și pentru cameră | testele pure trec |
| 10–11 | `:design`: paleta, fonturile, componentele, Foni, gurile | galeria arată ca pe iOS |

### 5.2 Valul 2: platforma

| Pas | Ce | Cum | Gata când |
|---|---|---|---|
| 4 | Captura în timp real | C++ cu Oboe 1.11 (AAudio), 48 kHz, mono; sursa `UNPROCESSED` unde telefonul o suportă, altfel `VOICE_RECOGNITION`; inel SPSC de 2^17 eșantioane; hopuri de 10 ms. Doar ramura ML primește 16 kHz: la 16 kHz sibilantele se taie (02) | testele de captură trec; pe telefon nu există AGC sau reducere de zgomot (verificat cu un ton de test) |
| 4 | Poarta de calitate a capturii | sub 32 kHz sau sub 11 kHz de bandă (Bluetooth), S/Ș se oprește și apare „Nu am auzit clar”, ca pe iOS | testul cu sursa la 16 kHz |
| 5 | Redarea | Media3: vocea-model la 0,6 cu tonul păstrat, efectele sonore, poziția pentru gură; vocea `ro-RO` de rezervă prin `TextToSpeech` | `PlaybackEngineTests`, `SpeechFallbackTests` |
| 7 | Whisper pe telefon | același model (`openai/whisper-base`) prin whisper.cpp 1.9.5 (JNI): expune decodorul și logiții la fiecare pas, deci decodarea forțată din iOS se poate reface, plus timpii pe token prin DTW; descărcare doar pe Wi-Fi, cu SHA-256, de pe un server propriu (Hugging Face vede adresa telefonului) | aceleași verdicte ca pe iOS pe setul din `tools/eval_speech`; dacă marjele se mută, pragurile se recalibrează și se documentează |
| 9 | Camera și fața | CameraX + modelele Face Landmarker (52 de blendshapes cu nume ARKit). Biblioteca MediaPipe Tasks trimite statistici de utilizare la Google, fără o oprire documentată, deci pentru copii rulăm modelele (Apache-2.0) direct prin LiteRT, fără bibliotecă (02). Pragurile se recalibrează pe înregistrări ale acelorași fețe pe iPhone și pe Android | toate planurile din `coach_plans.json` parcurse cu sursa scriptată și cu camera; zero trafic de rețea din partea camerei |
| 9 | Limba | MediaPipe nu are `tongueOut`: pașii cu limba devin desen și autoverificare, cu „Am făcut” mereu disponibil. Pasul `en-th` din engleză rămâne fără sursă de date pe Android | intră în `docs/paritate.md` |

### 5.3 Valul 3: ecranele

| Pas | Ce | Gata când |
|---|---|---|
| 12 | Rădăcina, încărcarea, `AppModel`, `AudioHub`, `SpeechHub`, filele cu butonul galben ridicat, zoomul din cartonaș (tranziții cu elemente comune în Compose), foile cu detente, argumentele de lansare (`-foniaResetData` ca extra) | testele UI de navigare |
| 13 | Primul drum, profilurile, Acasă, `PathModel`, foaia de start, paginile de lecții | `PathModelTests` și testele UI pentru primul drum |
| 14 | Ședința: toate exercițiile, `TakeJudge`, verdictele, finalul cu steluțe; modul „încercarea din clipul înregistrat” pornit explicit în emulator | `SessionRunTests` și testul UI al ședinței |
| 15 | Ghidul sunetului și antrenorul de gură | parcurgerea tuturor planurilor |
| 16 | Vorbit în public, `TalkReport` | rapoarte identice pe aceleași înregistrări |
| 17 | Jurnalul, Progresul, zona adulților, setările, exportul, calibrarea S/Ș | testele UI și exportul comparat octet cu octet |
| 18 | Cele 9 drumuri UI din iOS | trec în emulator; CI-ul filmează parcurgerea, ca pe iOS |

### 5.4 Când e gata paritatea

- toate testele portate sunt verzi;
- cele 9 drumuri UI trec;
- fiecare ecran are o captură pusă lângă cea de pe iPhone;
- DSP-ul și Whisper dau aceleași verdicte ca iOS pe clipurile reale (diferențele, numărate și explicate);
- APK-ul e sub 100 MB: imagini WebP și împărțire pe procesoare (azi are 187 MB).

## 6. Faza 2: fundația pentru ML

| Ce | De ce |
|---|---|
| Managerul de modele: descărcare pe Wi-Fi, SHA-256 fixat, server propriu, versiuni, ștergere | modelele noi (ZIPA, whisper-ro, Moonshine) ajung pe telefon fără să umfle APK-ul |
| Un singur ONNX Runtime | sherpa-onnx 2.0.1 vine cu ONNX Runtime 1.28.2, Moonshine cu altul, iar `onnxruntime-android` e 1.31.1. Un test scurt alege o singură copie pentru toată aplicația, înainte să intre al doilea model (02) |
| Biblioteci native aliniate la 16 KB | cerința Google Play pentru telefoanele cu pagini de 16 KB; NDK r28+ o face implicit, verificăm fiecare `.so` adus din afară (02) |
| Banc de măsură pe telefon: latență, RTF, memorie, pe fiecare model și procesor | pragurile de timp real (biofeedback < 100 ms) se verifică pe telefoane reale, nu se presupun |
| Shadow mode | ML-ul nou rulează lângă DSP fără să schimbe feedback-ul și se loghează acordul (03 §1.2, P13) |
| Jurnalul de evenimente al pilotului | activare, aderență, doză, retenție, verdicte, probe; fără audio în build-ul public (03 §4.1) |
| Verificarea textelor | portul lui `tools/lint_copy.py` (sedile, liniuțe, cuvinte interzise), extins cu „risc”, „severitate”, „diagnostic” și emoții |

## 7. Faza 3: cele patru direcții ML

Numerele P, C, E, V și T trimit la tabelele din `03_cercetare_produs.md`, unde sunt dovezile și pragurile.

### 7.1 Pronunția copiilor (română)

| Ordine | Ce | Prag sau criteriu |
|---|---|---|
| 1 | P1: verdict în trei stări („corect”, „mai încearcă”, „nu am auzit clar”), cu abținere la nesiguranță | respingeri false ≤ 10% pe producțiile corecte |
| 2 | P6: contorul de doză și „săptămâna intensivă” | ≥ 50 de încercări-țintă pe zi în pilot |
| 3 | P5: modurile Achiziție și Generalizare | Achiziție sub ~60% reușită; Generalizare peste ~80% în 2 sesiuni |
| 4 | P2: S-o-metrul calibrat pe copil (3 „sss” + 3 „șșș”) | latența afișajului < 100 ms |
| 5 | P3: R bătut acceptat ca bun, tril doar în jocurile de emitere | 86% din R-ul românesc e bătut |
| 6 | P13: calibrare locală din 5–10 producții confirmate, ML nou în shadow mode | |
| 7 | P4: scorul pe foneme cu ZIPA-small int8 + GOP fără segmentare, restrâns la alternativele plauzibile (la R: [ɾ], [l], [j], omisiune). ZIPA nu are tokeni pentru africate, deci Ț, CE/CI și GE/GI se scorează ca secvențe de doi tokeni. Codul public CTC-GOP n-are licență, așa că îl reimplementăm (04 §3). ZIPA rulează direct pe ONNX Runtime, pentru că sherpa-onnx nu dă probabilitățile pe cadre de care are nevoie GOP-ul; ieșirea e la 50 Hz (02) | AUC 0,914 în literatură; alarme false < 5% |
| 8 | P7: „Cel mai bun eu”, P10: profilul mișcat de DSP și harta vocalelor, P12: probe de generalizare pentru notare oarbă | |

### 7.2 Citirea și dislexia

| Ordine | Ce | Prag sau criteriu |
|---|---|---|
| 1 | C1: proba de citire de 1 minut, adultul bifează greșelile | WCPM = corecte în 60 s; norme RO doar ca bandă orientativă (final de clasa I: ~36–40) |
| 2 | C2: raportul pe Anexa 1 OMEN 3124/2017, PDF făcut pe telefon | avertisment: „orientativ; diagnosticul se pune doar prin evaluarea complexă” |
| 3 | C3: karaoke cu timpi reali, lectura repetată | 3–4 lecturi până la criteriu |
| 4 | C4: conștiința fonologică legată de litere | ~10 ore cumulate în 8–10 săptămâni |
| 5 | C9: presetările de lizibilitate, cu test pe copil | 5–8 cuvinte pe rând |
| 6 | C5 dictarea, C7 pseudocuvintele, C8 RAN, C12 phonics | |
| 7 | C6: WCPM automat, aliniat pe textul cunoscut, cu adultul care confirmă cuvintele nesigure. Alinierea vine din Fluente (MIT, 80 de teste), portată în Kotlin cu testele ei (04 §3) | nicio cifră afișată înainte de un pilot cu 30–50 de copii notați și de logoped |

### 7.3 Adulții: engleză și vorbit în public

| Ordine | Ce | Prag sau criteriu |
|---|---|---|
| 1 | V1 pauzele, V14 ritmul, V15 umpluturile, V3 intonația (PVQ), V16 volumul | pauze ≥ 250 ms; PVQ 0,15–0,25 țintă; ≤ 2,5 umpluturi la 100 de cuvinte |
| 2 | V2 emfaza | accentuat = 2 din 3: +30% durată, +3 dB, +2 st |
| 3 | V6 simularea BAC oral, Proba A, totul pe telefon | nota o dau profesorii; aplicația arată doar indicatori |
| 4 | E2 sesiunile HVPT și E3 harta vocalelor F1/F2 | percepție ≥ 85% pe voci noi |
| 5 | E4 scorul pe foneme în engleză; transcrierea în streaming cu Moonshine (bibliotecă Android; 234 ms pentru Small pe un Pixel 10a, 02) | feedback din șabloane și perechi minimale, nu din LLM |
| 6 | V4 contactul vizual (MediaPipe), V5 respirația și scara de expunere | contact ≥ 60%; SUDS auto-raportat |
| 7 | V7, V10, V18: LLM-ul text pentru 18+, cu acord | primește doar text și cifre; filtru contra emoțiilor deduse |

### 7.4 Antrenorul adaptiv

| Ordine | Ce | Prag sau criteriu |
|---|---|---|
| 1 | T3 programul de feedback: KR la ~50% din încercări pe măsură ce reușita crește | |
| 2 | T2 Challenge Point | 60–80% reușită pe bloc |
| 3 | T1 starea pe copil × sunet × poziție × treaptă (BKT) | treaptă trecută la P ≥ 0,9 în 2 sesiuni din zile diferite |
| 4 | T6 regulile de retenție, T4 recapitulările (FSRS), T5 alegerea copilului între 2–3 jocuri | |
| 5 | T8 perechile minimale extrase din lexicon (MFA `romanian_cv`, CC0) | fără LLM |
| 6 | T7 fabrica de conținut: LLM propune, G2P verifică, logopedul aprobă (în unelte, nu pe telefon) | acceptare ≥ 80% la logoped |
| 7 | T10 coach-ul text pentru adulți. Pe telefon: Gemma 4 E2B (Apache-2.0) prin LiteRT-LM, fișier de 2,0–2,6 GB, descărcat doar la cerere. În cloud: testul modelelor din cloud pe 30 de răspunsuri în română, după verificarea juridică de la §11. Gemini Nano iese din calcul: termenii lui exclud aplicațiile folosite probabil de minori și nu suportă româna (02) | |

## 8. Faza 4: date și modele proprii

- **Înainte de primul copil:** aviz de etică, DPIA, consimțământul părintelui și acordul copilului cu pictograme, un build separat de cercetare, criptare, ID-uri pseudonime, 2–3 logopezi care notează în orb (03 §5).
- **De ce avem nevoie de date proprii:** nu există date publice cu copii români care pronunță greșit sau citesc cu voce tare. În plus, pragurile S/Ș din iOS au fost reglate doar pe clipurile ElevenLabs, deci paritatea cu iOS nu dovedește încă și corectitudinea pe copii reali (04 §6).
- **Ce se poate folosi de azi, cu licență permisivă:** Samrómur Children, JIBO Kids, speechocean762, Common Voice ro, FLEURS, VoxCommunis și dicționarul MFA `romanian_cv` pentru pre-antrenare și evaluare; MUSAN, DEMAND și RIRS pentru zgomot. Toate seturile cu copii sunt la 16 kHz, deci banda sibilantelor de peste 8 kHz vine doar din înregistrările noastre la 48 kHz (04 §1).
- **Ce se înregistrează și cum se etichetează:** audio la 48 kHz fără pierderi, cu metadate; logopedul marchează fiecare sunet-țintă ca corect, substituție, distorsiune sau omisiune (04 §6).
- **Cât:** 30–40 de copii la început, apoi 80–120; 2–5 ore de vorbire pentru reglajul fin (04 §6).
- **Antrenarea:** ZIPA și whisper-ro reglate fin pe copii români, evaluate pe date ținute deoparte, exportate int8, livrate prin managerul de modele și pornite întâi în shadow mode.
- **Licențe:** exemplul TTS din sherpa-onnx aduce eSpeak NG (GPL) prin `piper-phonemize`, deci nu intră în aplicație. Fonemizarea vine dintr-un lexicon făcut cu G2P-ul nostru (04 §2).

## 9. Faza 5: pilotul și publicarea

| Ce | Ținta |
|---|---|
| Pilotul de 30 de zile: 2–3 logopezi, 10–15 copii de 5–10 ani | activare ≥ 80%, ≥ 3 zile pe săptămână, ≥ 50 de încercări pe zi, retenție la ziua 30 ≥ 60%, SUS ≥ 70, 0 incidente de date |
| Acordul aplicație–logoped | κ, AC1 și FRR pe ≥ 300 de tokeni |
| Pilotul de 90 de zile | design SCED (03 §4.2) |
| Google Play | testare internă după pilot; regulile pentru aplicații folosite de copii nu sunt în hub, deci se studiază înainte |
| iOS | PR în `dulgherustefan/fonia-app` care mută conținutul pe `fonia-content`; decide Ștefan |

## 10. Riscuri

| Risc | Ce facem |
|---|---|
| Pragurile DSP depind de numerica vDSP | FFT propriu cu aceleași convenții; vectori de test din iOS |
| Whisper pe alt runtime mută marjele | același model, comparație pe setul de evaluare, recalibrare documentată |
| Captura diferă de la telefon la telefon | `UNPROCESSED` unde există, poarta de calitate, ton de test în setări |
| Fața: MediaPipe nu e ARKit | recalibrare pe aceleași fețe; limba prin autoverificare |
| Foni și gurile sunt greu de animat identic | galeria pusă lângă iOS, ecran cu ecran |
| APK mare (187 MB) | WebP, împărțire pe procesoare, App Bundle |
| Datele copiilor | totul pe telefon; colectarea doar în build-ul de cercetare, cu acord |
| Biblioteci care trimit date singure (MediaPipe Tasks) | modelele rulate direct prin LiteRT; un test care verifică faptul că aplicația nu deschide conexiuni în afara descărcării modelelor |
| Mai multe copii de ONNX Runtime în APK | testul de la §6 alege una singură |

## 11. Întrebări deschise

- Modelul telefonului Android de test.
- Ce înlocuiește funcțiile pentru adulți care cereau voce în cloud (Scribe, Foni Live, vocea proprie): propunerea e transplantul de prozodie pe telefon și LLM-ul text.
- Ordinea unităților de engleză: cea din iOS sau cea după inteligibilitate (03 §1.4).
- Scorurile compuse: regula iOS le interzice, hub-ul propune un profil de expresivitate (V12).
- Textul de consimțământ, DPIA-ul și perioada de retenție pentru audio de cercetare.
- Numele: „FONIA” e deja numele unei aplicații de telecomunicații din Polonia.
- Creditele ElevenLabs (~106.000) expiră pe 29 noiembrie 2026: înainte de atunci merită generate clipurile care lipsesc (93 în `tools/missing-clips.txt` din iOS).
- Termenii API-ului Gemini au aceeași clauză despre minori ca Gemini Nano, ceea ce l-ar putea exclude și pentru funcțiile de adulți dintr-o aplicație folosită de copii. Un jurist trebuie să verifice asta înainte de testul modelelor din cloud (02).
- ONNX Runtime 1.31.1 vine cu telemetria Microsoft (1DS), care citește `android_id`, modelul telefonului, limba și fusul orar. E scoasă din manifest și oprită la încărcare, iar build-ul pică dacă revine. De trecut în DPIA și de reverificat la fiecare versiune nouă.
- Definițiile pilotului, de confirmat înainte de primul copil: „activ în ziua N” = exersare în zilele N−6…N; săptămâna intensivă începe în prima zi exersată; doza nu numără încercările pe care nu le-a putut judeca nimeni. Detaliile se păstrează 90 de zile, deci pentru un studiu de 90 de zile exportul se face înainte de ziua 90.
- ZIPA se descarcă încă de pe Hugging Face, care vede adresa telefonului, până îl găzduim noi.
- Acordurile, în ordinea priorității (04 §7): UPB SpeeD pentru Parakeet-Ro, o universitate parteneră pentru avizul de etică, un cont Mozilla Data Collective pentru Common Voice ro, UTCluj pentru RoLEX (perechile minimale z/ʒ și ʒ/dʒ sunt foarte puține fără un lexicon mare).
