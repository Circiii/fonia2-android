# 06. Metoda și conținutul

Capitol din planul Fonia 2. Deține pachetele **I1–I8**, **F2** (constructorul de lecții) și **F5** (seria, ținta săptămânală, amintirea). Numele, deciziile și etapele sunt cele din `00_PLAN_MASTER.md`. Surse: raportul AA (metoda), rezumatele cărții (`carte_ivanov/parte_1–4`), rapoartele Y, X, V, R, N, conținutul din `fonia-content` și notele `fonia-brain` despre `:domain`.

**Regula de drepturi (F2 din decizii):** niciun text de exercițiu, nicio listă și nicio frământare din cartea lui Carmen Ivanov nu apare aici sau în aplicație. Tot ce e marcat „original” e scris pentru Fonia. Frământările populare sunt numite prin primele cuvinte și se iau din surse care nu sunt cartea.

Notații în tabelele de cuvinte: cuvânt simplu = **poza există** în `images/pictures`; cuvânt urmat de emoji = poză de adăugat din același set de emoji; **∅** = nu există emoji potrivit, trebuie desen; **\*** = conține și alt sunet greu (se dă mai târziu sau la copiii mai mari); **(V)** = intră în sesiuni doar după ce îl aprobă logopedul.

---

## 1. Metoda, într-o pagină

Copilul urcă, pentru fiecare sunet, aceeași scară. Emiterea sunetului e treaba logopedului; aplicația consolidează ce există deja.

| # | `Rung` | Scop | Exercițiu (pas din `LESSON`) | Repetări pe sesiune | Reușită | Cine judecă |
|---|---|---|---|---|---|---|
| 1 | `listen` | Aude diferența | Foni spune bine sau „încurcat”; copilul atinge poza potrivită (`Listen`) | 6–8 | 5 din 6 | Telefonul (atingere) |
| 2 | `warmup` | Trezește gura | 2–3 exerciții din §5 (`Warmup`) | 3–4 de fiecare, ≤ 60 s | Poziția ținută 1–1,5 s | Camera sau microfonul; altfel adultul |
| 3 | `isolated` | Sunetul singur | Sunet ținut sau rafală scurtă (`Hold`) | 6–10 | 12 reușite adunate, **apoi confirmarea adultului** | Telefonul + adultul |
| 4 | `syllables` | Sunet + vocală | sa, se, si…; întâi despărțit, apoi legat (`Syllables`) | 10–15 | 5 din 6 pe bloc | Telefonul; adultul la L |
| 5 | `back` | Sunetul după vocală și între vocale | as, es…; asa, ese… (`Syllables`) | 10–15 | 5 din 6 | La fel |
| 6 | `wordsInitial` | Sunetul la început | Numește poza, apoi repetă (`Words`) | 12 | 5 din 6, două blocuri | Telefonul + adultul din când în când |
| 7 | `wordsMedial` | Sunetul la mijloc | La fel | 12 | 5 din 6 | La fel |
| 8 | `wordsFinal` | Finalul clar | La fel, finalul ținut puțin | 12 | 5 din 6 | La fel |
| 9 | `clusters` | Sunetul lângă altă consoană | La fel | 12 | 5 din 6 | La fel |
| 10 | `pairs` | Nu confundă sunetele vecine | Perechi reale: ascultă, apoi spune (`Pairs`) | 8–10 | 5 din 6 la spus | Amândoi |
| 11 | `sentences` | Vorbire legată | Propoziții de 3–6 cuvinte (`Sentence`) | 6 | 5 din 6 | Telefonul (cuvintele) + adultul |
| 12 | `twister` | Control în secvență | O frământare, rar, apoi cu un pas mai repede (`Twister`) | 3–4 | Clar de 2 ori la rând | Adultul |
| 13 | `retell` | Vorbire aproape liberă | 3 poze, copilul povestește (`Retell`) | 1–2 | Adultul apreciază | Adultul |
| 14 | `everyday` | Automatizare | O misiune pe zi (`Mission`) | 1 | Adultul bifează | Adultul |

**Lacătul.** După `isolated` nu se urcă până nu confirmă un adult (`AdultGate`) că sunetul sună bine. Motivul: fără el, copilul ar repeta de zeci de ori un sunet greșit. Telefonul nu coboară singur un copil; coborârea cere tot un adult.

**Unde urmăm logopedia, nu cartea (F3):** buze rotunjite la Ș, J, CE, GE; la R acceptăm și o singură bătaie; fără creion, fără „până la durere”, fără rețineri de aer, fără „spune intenționat greșit”. Cel care greșește e Foni, niciodată copilul.

**Când aplicația spune „Arată-i asta logopedului”** (doar adultului, în „Pentru adult”, fără exersare intensivă propusă):

| Situație | Semnalul |
|---|---|
| Aerul iese pe o parte (`Lateral`) | de 3 ori în 2 sesiuni, sau adultul bifează „sună ud” |
| R din gât (`RUvular`) | la fel |
| Limba între dinți (`Interdental`) la două sau mai multe sunete | camera + sunetul, în zile diferite |
| 9 blocuri slabe la rând pe aceeași treaptă | regula `weakBlocksBeforeLogoped` de azi |
| `isolated` nu se strânge în 5 zile exersate | 12 reușite neatinse |
| Adultul scrie că vorbirea sună „pe nas” sau că apar blocaje | întrebare din zona adultului |

---

## 2. F2 — Constructorul de lecții

### 2.1 Șablonul de 4 minute

`SessionBuilder.build(request)` întoarce o listă de pași. Șablonul nou (din raportul Y):

| Timp | Pas | Conținut | Producții |
|---|---|---|---|
| 0:00–0:15 | Deschidere | Replica de poveste a treptei (`hero.chapters[rung]`) | 0 |
| 0:15–0:45 | Încălzire | 2–3 exerciții din §5 sau „Umflă litera” cu prag mic | 5 |
| 0:50–1:45 | Bloc 1 | Materialul treptei de dedesubt (reușită aproape sigură) | 12–15 |
| 1:45–2:00 | Mișcare | „Zbori ca Foni!” 3 respirații, gura se odihnește | 0 |
| 2:00–2:50 | Bloc 2 | Materialul treptei curente | 9–12 |
| 2:55–3:40 | Bloc 3 | Treapta curentă, amestecat cu cuvinte știute; **itemul-surpriză** la jumătate | 9–12 |
| 3:40–4:00 | Final | O ultimă reușită sigură, „Ascultă-te!”, rândul pentru adult, „Încă una?” | 1–7 |

- **„Încă una?”** construiește o rundă scurtă: un bloc de 6 din treapta curentă plus finalul (~90 s). Aceeași rundă e și **ziua minimă** din §10.
- Dacă semnalele de atenție cad (2 din 3: latență +50 %, răspuns sub 70 %, atingeri în afara țintei dublate), blocul 3 se taie la jumătate și se trece la final. Sesiunea se termină mereu cu o reușită.
- Sesiunea după o pauză de 7 zile sau mai mult: două blocuri, doar cuvinte știute.

### 2.2 Ce folosește fiecare treaptă

| `Rung` | Bloc 1 | Bloc 2 și 3 | Mini-joc (C5) |
|---|---|---|---|
| `listen` | — | 6–8 alegeri între două poze | Ariciul și norul (ascultare) |
| `warmup` | — | 3 exerciții | (oglinda) |
| `isolated` | încălzire | sunet ținut; rafale la Ț, CE, GE, R | Umflă litera |
| `syllables` | `isolated` | trenuri de 3 silabe directe | Trenul de silabe |
| `back` | `syllables` | inverse, apoi între vocale | Trenul de silabe, Toba literelor |
| `wordsInitial/Medial/Final`, `clusters` | silabe | 9 + 9 cuvinte cu poză | Autocolantul fuge din cuvânt, Ecoul din peșteră |
| `pairs` | cuvinte știute | 4–5 perechi: ascultă, apoi spune | Ariciul și norul (S/Ș); altfel alegere între poze |
| `sentences` | cuvinte | 6 propoziții | Ecoul din peșteră |
| `twister` | propoziții | o frământare la două tempouri | Toba literelor |
| `retell` | propoziții | o poveste din 3 poze | — |
| `everyday` | cuvinte amestecate | misiunea zilei | — |

### 2.3 Alegerea itemilor

1. **Fără repetare imediată** și întâi itemii nefolosiți în sesiunea trecută (regula `Deck` de azi, păstrată).
2. **Știute față de noi:** 70 % cuvinte cu cel puțin o reușită, 30 % noi. Prima sesiune pe o treaptă: 50/50, cu model audio înaintea fiecărui item.
3. **Poziția-țintă:** blocul 2 are doar poziția treptei; blocul 3 amestecă pozițiile deja trecute.
4. **Filtre:** vârsta (`ageBand`), `needsTherapistCheck` neaprobat = exclus, `otherSounds` care conțin un sunet încă nelucrat de copil = amânat în a doua jumătate a listei.
5. **Itemul-surpriză:** un singur item în blocul 3, pe poziția 5–7, luat din treapta următoare sau un cuvânt nou cu autocolant special. Nu intră în calculul progresiei.
6. Perechile se dau întregi (ambele cuvinte), în ordine amestecată.

### 2.4 Adaptarea ascunsă

Ținta e 80–85 % încercări reușite pe ultimele 8–10. Copilul vede mereu aceeași țintă („balonul plin”).

| Dacă reușita e… | Ce se mișcă |
|---|---|
| peste 90 % în două blocuri | pragul de durată la `Hold` crește (1,5 → 3 s), trenul de silabe se lungește (2 → 5), ponderea cuvintelor noi urcă la 40 %, modelul audio vine doar la cerere |
| între 70 și 90 % | nimic |
| sub 70 % | prag mai mic, tren mai scurt, 85 % cuvinte știute, model înaintea fiecărui item, tempo „melc” |

Adaptarea nu schimbă niciodată treapta; treapta o schimbă doar `Progression` (F3). Starea adaptării intră în `SessionRequest` ca date, deci constructorul rămâne pur.

### 2.5 Determinism și vectori comuni cu iOS

- Tot ce e aleator trece prin `SeededRandom` (SplitMix64). Sămânța rămâne `request.seed ^ sessionsCompleted·φ ^ hash(focusKey)`.
- Aceeași cerere + aceeași versiune de conținut = aceeași sesiune, octet cu octet.
- Vectori: `spec/vectors/sessions-v2.jsonl`, câte o linie `{request, contentVersion, steps[]}`. Minimum 9 sunete × 14 trepte × 3 semințe = 378 de cereri, plus „încă una”, pauza lungă și cele trei stări de adaptare. Testul Swift citește același fișier.

### 2.6 Ce rămâne și ce se schimbă față de `SessionBuilder` de azi

| Rămâne exact | Se schimbă |
|---|---|
| `SeededRandom` și formula seminței | Șablonul: „deschidere, încălzire de ascultare, ținta A, ținta B, 12 mixte, final fix” devine cel din §2.1 |
| `Deck`: nefolosite întâi, fără repetare consecutivă | `Rung` primește `listen`, `warmup`, `back`, `twister` (azi are 10 valori) |
| Blocuri de 6; avans la 5; coborâre sub 3 doar cu adult; 12 reușite la `isolated`, apoi adultul; 9 blocuri slabe → logoped | Ținta adaptării: de la 60–80 % la 80–85 % (prag în `spec/thresholds`) |
| Puritatea: fără ceas, fără I/O | Itemul-surpriză, „încă una”, sesiunea de după pauză, tăierea blocului 3 |
| Cheile `SoundId` și `Rung` ca valori în JSON și în baza de date | Vectorii vechi (`ios-sessions.txt`, 276 de cereri) se arhivează; se generează cei noi |
| Lauda deterministă și textele îmblânzite pentru copil (regulile iOS din 18.11) | Lauda se alege după **ce s-a întâmplat** (§7), nu la rând |

---

## 3. I1 — Schema de conținut v2

### 3.1 Ce există azi (`fonia-content`)

- `sounds.json`: listă de 9 obiecte cu `id`, `letter`, `graphemes`, `hero {name, picture, says, story, chapters{13 chei}}`, `viseme`, `detector`, `voiced`, `isAffricate`, `typicalByAge`, `parentNote`, `how[4]`, `fixes[{when, tip}]`, `warmups[3]`, `syllables {direct, inverse, between, repeated}`, `words {initial, medial, final, clusters}` (fiecare cuvânt: `text`, `picture`, `otherSounds`, `clusters`, `namable`, `recorded`), `sentences`, `twisters[{text, folk}]`, `contrasts[{with, pairs[{a, b, spoken}]}]`, `retell`, `describe`, `missions`.
- Acoperire azi: 3–7 cuvinte pe poziție (finalul are 3–5), 3–4 propoziții, 1–2 frământări, 1–3 perechi, 3 misiuni pe sunet. Lipsesc cheile de capitol `listen` și `twister`.
- `exercises.json` (19 încălziri: `id`, `name`, `say`, `picture`, `pose`, `mode` camera/audio/self, `holdMs`, `reps`, `match`), `coach_plans.json` (pași de așezare și 3 `faults` pe sunet), `listening.json`, `diction.json` (arii, norme de fonație, `finalIWords`, perechi de intonație, texte de ritm și de pauză, 7 teme de vorbit, umpluturi), `english.json` (rămâne neatins în v1).
- Poze: 184 de fișiere numite după codul emoji (`1f981.png`), descrise în `images/catalog.json`.
- Voce: `voice/manifest.json`, cheie `limbă|voce|text normalizat` → fișier MP3 cu nume-hash; 404 clipuri românești pe vocea `ro`.

### 3.2 Forma nouă

Fișiere: `json/v2/sounds/<id>.json` (unul pe sunet), `items/<id>.json`, `messages.json`, `parent.json`, `foni_lines.json`, `warmups.json`, `diction.json`, plus `content.lock.json` (versiune și sume de control).

```json
// sounds/s.json
{ "schema": 2, "id": "s", "letter": "S", "graphemes": ["s"],
  "hero": { "name": "Sâsâilă", "picture": "1f40d", "says": "Ssssss",
            "story": "…", "chapters": { "listen": "…", "warmup": "…", "…": "…", "twister": "…", "everyday": "…" } },
  "voiced": false, "isAffricate": false, "typicalByAge": 5,
  "howParent": ["Zâmbet larg, dinții aproape.", "Vârful limbii jos, după dinții de jos.", "Aer rece, drept pe mijloc."],
  "tricks": [{ "id": "din-i", "text": "Un «iii» lung, apoi suflă.", "forTherapist": true }],
  "errors": [{ "kind": "Interdental", "sounds": "ca «th»", "seen": "limba între dinți", "toTherapist": false },
             { "kind": "Lateral", "sounds": "șuierat ud", "toTherapist": true }],
  "warmups": ["zambet", "dinti", "limba-jos", "sarpele-lung"],
  "rungs": ["listen","warmup","isolated","syllables","back","wordsInitial","wordsMedial","wordsFinal","clusters","pairs","sentences","twister","retell","everyday"],
  "judge": { "syllables": "phone", "sentences": "phone+adult" } }
```

```json
// items/s.json — un rând pe item
{ "id": "s.w.sanie", "type": "word", "text": "sanie", "rung": "wordsInitial",
  "target": [{ "at": 0, "pos": "initial" }], "picture": "1f6f7", "clip": "ro/foni/s.w.sanie",
  "difficulty": 1, "ageBand": "4+", "otherSounds": [], "clusters": [], "namable": true,
  "needsTherapistCheck": false, "review": { "status": "draft", "by": null, "date": null },
  "source": "original" }
```

Tipuri de item: `syllable`, `word`, `sentence`, `pair` (`a`, `b`, `contrast`, `spoken`), `twister` (`tempos: ["slow","step"]`, `source: "folk" | "original"`, `folkRef`), `story` (`pictures[3]`, `lines[3]`, `prompt`), `mission`, `listenFoil` (cuvântul „încurcat” de Foni, cu `mimics: ErrorKind`).

```json
// messages.json — un rând pe (ErrorKind, sunet sau grup)
{ "kind": "Devoicing", "sounds": ["z","j","ge"], "child": "Pornește motorul din gât.",
  "adult": "A sunat fără voce. Puneți-i palma pe gât: trebuie să bâzâie.",
  "animation": "throatBuzz", "clip": "ro/foni/msg.devoicing", "wave": 1 }
```

`parent.json` ține cele șase `ParentCard` (`target`, `goodAttempt`, `ifNot`), sfaturile, șabloanele de rezumat, textele de amintire și textul de confidențialitate (§9). `foni_lines.json` ține replicile (`id`, `category`, `when`, `text`, `clip`, `maxSeconds`).

### 3.3 Versiuni

- `schema` (întreg) în fiecare fișier; `contentVersion` (`2.0.0-m1`…) în `content.lock.json`, scris în fiecare încercare salvată, alături de versiunile pragurilor.
- Un item nu-și schimbă niciodată `id`-ul; un text corectat păstrează `id`-ul și crește `rev`.

### 3.4 Validări în CI (`tools/check_content_v2.py`, rulat și în `fonia-android`)

| Regulă | Eșec dacă |
|---|---|
| Diacritice | apare ş sau ţ cu sedilă (U+015F, U+0163) sau ã în loc de ă |
| Poziția-țintă | sunetul nu e la poziția declarată, după pronunție (`ce/ci`, `ge/gi`, `che/ghe`, i final nesilabic) |
| Lungimi | replică de capitol peste 12 cuvinte pe propoziție; rând pentru copil peste 8 cuvinte; propoziție de exersat peste 6 cuvinte la 4+ și 8 la 7+ |
| Cuvinte interzise | „greșit”, „diagnostic”, „terapie”, „tratează”, „tratament”, „vindecă”, „risc”, „severitate”, „metoda Carmen Ivanov” |
| Clipuri | item fără clip în manifest sau clip care n-a trecut verificarea din §7 |
| Poze | `word`, `pair`, `story` fără poză în catalog; aceeași poză de două ori în aceeași listă de poziție |
| Perechi | cele două cuvinte diferă prin mai mult de un sunet |
| Acoperire | sub 12 itemi aprobați sau în ciornă pe o poziție (avertisment, nu eșec, la Z, J, GE final) |
| Sursa | `source` lipsă; `folk` fără `folkRef` |
| Mesaje | lipsește rândul pentru o combinație (`ErrorKind`, sunet) deblocată în `spec/unlock.json` |

### 3.5 Migrarea

Scriptul `tools/migrate_v1_v2.py` citește fișierele de azi și scrie forma nouă: `how` → `howParent` (rescris), `fixes` + `coach_plans.faults` → `errors` și `messages.json`, `contrasts` → itemi `pair`, `describe` și `retell` → `story`, `recorded` → prezența clipului. `v1` rămâne pe loc până când Ștefan mută iOS; `sync_from_ios.py` nu atinge `json/v2/`.

### 3.6 Mesajele pe tip de eroare

Rândul pentru copil are cel mult 8 cuvinte și spune ce să facă. Numele animațiilor sunt propuneri pentru E7/C4.

| `ErrorKind` | Unde | Copil | Adult | Animație |
|---|---|---|---|---|
| `Omission` | toate | „Mai spune o dată, cu tot cu {S}.” | „Sunetul a lipsit. Spuneți voi cuvântul rar, apăsând pe el.” | `letterPopIn` |
| `Stopping` | S, Ș, Z, J | „Lasă aerul să curgă lung.” | „A ieșit scurt, ca un T. Țineți împreună sunetul 2 secunde.” | `airFlowLong` |
| `Devoicing` | Z, J, GE | „Pornește motorul din gât.” | „A sunat fără voce. Palma pe gât: trebuie să bâzâie.” | `throatBuzz` |
| `PlaceSwap` | S, Z | „Zâmbește larg. Aer rece.” | „A sunat ca Ș. Buzele întinse, limba jos.” | `lipsSpread` |
| `PlaceSwap` | Ș, J | „Buze rotunde. Aer cald.” | „A sunat ca S. Buzele ca la pupic, limba ceșcuță.” | `lipsRound` |
| `Lateral` | sibilante | „Suflă drept, pe mijloc.” | „Aerul pare să iasă pe o parte. Arătați-i asta logopedului.” | `airCenter` |
| `Interdental` | S, Z, Ț, L | „Ascunde limba după dinți.” | „Limba s-a văzut între dinți. Dinții aproape, ca un gard.” | `tongueBehindTeeth` |
| `Deaffrication` | Ț, CE, GE | „Începe scurt, ca un T.” | „A sunat ca S sau Ș. Încercați «t-s» tot mai repede.” | `tapThenHiss` |
| `AffricatePlace` | Ț | „Zâmbește. Scurt și rece.” | „A sunat ca CE. Buzele întinse.” | `lipsSpread` |
| `AffricatePlace` | CE, GE | „Buze rotunde. Scurt și cald.” | „A sunat ca Ț. Buzele ca la pupic.” | `lipsRound` |
| `RtoL` | R | „Fă limba să tremure: trrr.” | „A sunat ca L. Încercați «t-d-t-d» repede, apoi «trrr».” | `tongueTrill` |
| `RtoGlide` | R | „Urcă vârful limbii sus.” | „A sunat ca I. Vârful limbii urcă după dinții de sus.” | `tongueTipUp` |
| `RtoVowel` | R | „Pornește motorașul: drrr.” | „R-ul a lipsit sau s-a topit în vocală. Reveniți la «dra, dre».” | `tongueTrill` |
| `RUvular` | R | „Vârful limbii sus, pe deal.” | „R-ul pare făcut din gât. Arătați-i asta logopedului.” | `tongueTipUp` |
| `LtoGlide` | L | „Lipește limba sus. Apoi cântă.” | „A sunat ca I sau U. Limba se lipește după dinții de sus.” | `tongueTipHold` |
| `LtoR` | L | „Ține limba lipită. Nu tremura.” | „A sunat ca R. Limba stă lipită, vocea curge pe lângă.” | `tongueTipHold` |
| `WeakOrShort` | toate | „Mai tare și mai lung!” | „S-a auzit prea încet sau prea scurt. Telefonul mai aproape.” | `letterInflate` |

Pentru `Unsure`: „Hai să mai încercăm.” și, către adult, „Nu sunt sigur. Cum a sunat?” cu două butoane. Pentru `Retake`: „Mai spune o dată.”

---

## 4. I2 — Conținutul, sunet cu sunet

Baza listelor sunt fișele C1–C9 din raportul AA, filtrate pentru 4–7 ani și completate. Silabele: întâi cu **a, o, u, e, i**, apoi **ă, î**. La fiecare sunet: 3 povești (trei poze în ordine) și 5 misiuni. Toate propozițiile și frământările marcate „original” sunt scrise pentru acest capitol.

### 4.1 S — Sâsâilă (șarpele)

| | |
|---|---|
| Replica | „Sâsâilă alunecă prin iarbă: sssss. Sâsâie și tu cu el!” (existentă) |
| `syllables` | sa, so, su, se, si, să, sî · tren: sa-sa-sa, sa-se-si |
| `back` | as, os, us, es, is · asa, oso, usu, ese, isi |
| `wordsInitial` (14) | sanie, supă, suc, soare\*, sare\*, salată\*, semafor\*, sac ∅, sapă (⛏ există), sos 🥫, sandale 👡, sabie 🗡, săpun 🧼, sobă ∅ |
| `wordsMedial` (12) | pisică, casă, păsărică\*, masă ∅, oase (poza „os”), pisoi 🐈, fasole 🫘, vase 🍽, desen 🖍, mustață\* 🥸, busolă (V), coasă ∅ (V) |
| `wordsFinal` (12) | nas, os, ananas, cactus, sos 🥫, vas 🏺, pas 👣, cocos 🥥, compas 📐, ceas\*, atlas (V), cos (V) |
| `clusters` (14) | stea, scaun, spital, castel, cosmonaut, stilou 🖊, muscă 🪰, mască 🎭, vestă 🦺, spic 🌾 (V), urs\*, castravete\*, strugure\* 🍇, sandviș\* |
| `pairs` | S–Ș: cos–coș, nas–naș, muscă–mușcă · S–Z: sar–zar, vase–vaze · S–T: sobă–tobă, sac–tac · S–Ț: sap–țap (V) |
| `sentences` (original) | „Sanda pune supa pe masă.” · „Pisica a sosit acasă.” · „Sami bea suc de ananas.” · „În sac sunt sandale.” · „Pisoiul stă sub masă.” · „Vasul e pe sobă.” · „Am un nas și un os.” |
| `twister` | original: „Sus pe sanie stă Sami, sub sanie stă un sac.” · tradițională (după `pairs`): „Șase sași în șase saci” |
| `retell` | sanie → pisică → casă · supă → masă → pisoi · sac → ananas → suc |
| `everyday` | „La masă, cere: sare, te rog!” · „Găsiți trei lucruri care încep cu S.” · „La culcare: somn ușor!” · „În parc, strigă: sus, sus!” · „Numără pașii până la ușă: un pas, doi pași.” |

### 4.2 Ș — Trenulețul Șușu

| | |
|---|---|
| Replica | „Trenulețul Șușu pleacă la drum: șșșș! Suflă și tu, ca trenul.” (existentă) |
| `syllables` | șa, șo, șu, șe, și · tren: șu-șu-șu |
| `back` | aș, oș, uș, eș, iș · așa, oșo, ușu, eșe, iși |
| `wordsInitial` (12) | șosete, șah, șapcă, șal 🧣, șase 6️⃣, șapte 7️⃣, șină 🛤, șoim 🦅, șuncă 🥓, șoarece\*, șurub\*, șarpe\* |
| `wordsMedial` (12) | mașină, ușă, cămașă, păpușă 🪆, cușcă ∅, pișcot 🍪, mașinuță\* ∅, roșie\* 🍅, cireșe\*, mușețel\* 🌼, mătușă\* 👵 (V), pușculiță\* 🐖 (V) |
| `wordsFinal` (12) | coș, cocoș, iepuraș, duș 🚿, copilaș 👶, mieluș 🐑, pantofaș 👟, gălbenuș 🍳, cățeluș\* 🐕, fluturaș\* 🦋, măgăruș\*, oraș\* 🏙 |
| `clusters` (10) | pește, căpșună, școală 🏫, poștă 📮, căști 🎧, ștampilă ∅, șnur ∅ (V), broaște\* 🐸, șorț\* ∅, sandviș\* |
| `pairs` | Ș–S: coș–cos, naș–nas, mușcă–muscă · Ș–J: șoc–joc (V), șir–jir (V) |
| `sentences` (original) | „Mașina stă lângă ușă.” · „Păpușa are o șapcă.” · „Cocoșul șade pe coș.” · „Am șase șosete noi.” · „Mielușul face duș.” · „Iepurașul a șoptit ceva.” · „Șoimul e pe șină.” |
| `twister` | tradițională: „Știu că știi că știu că știi” · original: „Păpușa Șuși șade-n coș și șoptește: șșș!” |
| `retell` | mașină → ușă → păpușă · iepuraș → coș → șapcă · cocoș → duș → cămașă |
| `everyday` | „Când te încalți: întâi șosetele!” · „La culcare, șoptește: șșș, doarme!” · „În mașină, numărați mașinile.” · „Închide ușa și spune: ușa!” · „La baie: fac duș!” |

### 4.3 Z — Albinuța Zuzu

| | |
|---|---|
| Replica | „Albinuța Zuzu zboară din floare în floare: zzzz! Palma pe gât și bâzâie cu ea.” (existentă) |
| `syllables` | za, zo, zu, ze, zi, ză, zî |
| `back` | az, oz, uz, ez, iz · aza, ozo, uzu, eze, izi |
| `wordsInitial` (12) | zână, zăpadă, zece 🔟, zid 🧱, zambilă 🪻, zâmbet 😀, zero\* 0️⃣, zar\*, ziar\*, zebră\*, zahăr\* ∅, zimbru\* 🦬 (V) |
| `wordsMedial` (12) | vază, buză, poză (🖼 există), muzică (🎵 există), cozonac 🍞, magazin 🏪, cazan ∅ (V), gazon ∅ (V), mazăre\*, dinozaur\*, televizor\* 📺, bluză\* |
| `wordsFinal` (9; rar în română) | autobuz, roz 🩷, ovăz 🌾 (V), gaz (V), miez ∅ (V), orez\*, obraz\* ∅, praz\* ∅, troleibuz\* · se exersează și în expresii: „în autobuz”, „un orez” |
| `clusters` (10) | zmeu, cizme, ghiozdan 🎒, mânz 🐎, zebră\*, frunză\*, brânză\*, varză\* 🥬, zbor\* ✈, bluză\* |
| `pairs` | Z–S: zar–sar, vaze–vase · Z–J: zar–jar, cozi–coji |
| `sentences` (original) | „Zâna zice bună ziua.” · „Vaza e pe zid.” · „Zoe vede zece poze.” · „În magazin e muzică.” · „Zâna zâmbește în zăpadă.” · „Buzele zânei sunt roz.” · „Zoe ia un cozonac din magazin.” |
| `twister` | original: „Zoe și Zizi zăresc zece zâne pe zid.” · original: „Zuzu zice zi de zi: zum, zum, zum!” |
| `retell` | zână → zăpadă → zmeu · vază → zambilă → zid · autobuz → magazin → cozonac |
| `everyday` | „La prânz: bună ziua!” · „Numără până la zece.” · „Vezi o albină? Bâzâie: zzz!” · „Găsiți ceva roz în casă.” · „Pe stradă, căutați trecerea de zebră.” |

### 4.4 J — Vântul Vâjâilă

| | |
|---|---|
| Replica | „Vântul Vâjâilă suflă printre frunze: jjjj! Palma pe gât și suflă cu el.” (existentă) |
| `syllables` | ja, jo, ju, je, ji |
| `back` | aj, oj, uj, ej, ij · aja, ojo, uju, eje, iji |
| `wordsInitial` (13) | joc, jeleu, jos ⬇, joben 🎩, jambon 🍖, jachetă ∅, jeton 🪙 (V), judo 🥋 (V), jar\*, jaguar\*, jucărie\*, junglă\* 🌴, jurnal\* 📓 |
| `wordsMedial` (12) | ojă, cojoc, pijama ∅, coajă ∅, bijuterie\* 💎, prăjitură\* 🍰, stejar\* 🌳, vijelie\* 🌪, ajutor\*, vrăjitoare\*, plajă\*, pajiște\* 🌿 |
| `wordsFinal` (11) | bagaj, etaj 🏢, ruj 💄, bandaj 🩹, mesaj (💬 există), patinaj ⛸, peisaj (V), masaj (V), garaj\*, ambalaj\* 📦, colaj\* (V) · la 4–5 ani rămân: bagaj, ruj, etaj, bandaj, garaj |
| `clusters` (7; rare) | plajă\*, vrăjitoare\*, vrajă\* (poza „magie”), grajd\* ∅, cârjă\* 🩼, drojdie\* (V), jgheab (V) |
| `pairs` | J–Z: jar–zar, coji–cozi · J–Ș: joc–șoc (V), jir–șir (V) |
| `sentences` (original) | „Jeleul e jos.” · „Jojo joacă un joc nou.” · „Pijamaua e în bagaj.” · „Mama are ojă și ruj.” · „Bagajul e la etaj.” · „Jana ajută un pui.” · „Cojocul e jos, lângă joben.” |
| `twister` | original: „Jojo joacă jos un joc cu jeleu.” · original: „Jana ajunge jos cu-n bagaj bej.” |
| `retell` | jucărie → jos → ajutor · bagaj → plajă → joc · cojoc → vijelie → jar |
| `everyday` | „Cade ceva? Spune: e jos!” · „Cere ajutor: mă ajuți, te rog?” · „Alege un joc și spune-i numele.” · „Seara: îmi iau pijamaua!” · „La plecare: unde e bagajul?” |

### 4.5 Ț — Șoricelul Țuțu

| | |
|---|---|
| Replica | „Șoricelul Țuțu e mic și iute: ț-ț-ț! Fă și tu ca el, scurt și repede.” (existentă) |
| `syllables` | ța, țo, țu, țe, ți · „trenulețul”: t…s → ț |
| `back` | aț, oț, uț, eț, iț · ața, oțo, uțu, ețe, iți |
| `wordsInitial` (12) | țap, țintă, țânțar, țeavă ∅, țepi ∅, țară (V), țiglă ∅ (V), țestoasă\*, țurțur\* ∅, țarc\* ∅, țăran\* 👨‍🌾, țelină\* ∅ (V) |
| `wordsMedial` (13) | ață, cuțit, fetiță, cățel\*, bețe 🥢, gheață 🧊, bunicuță 👵, pizza 🍕 (V), rață\*, căsuță\*, ceață\* 🌫, dulceață\*, linguriță\* ∅ |
| `wordsFinal` (13) | pătuț, puiuț 🐥, băț 🪵, coteț ∅, căluț\*, ursuleț\*, brăduț\* 🎄, ghemuleț\* 🧶, lanț\*, prinț\*, colț\* ∅, puț ∅ (V), hoț (V) |
| `clusters` (11) | lanț, munți, dinți, prinț\*, colț\*, cărți\* 📚, frați\* 👬, porți\* ∅, brațe\*, șorț\* ∅, țânțar\* |
| `pairs` | Ț–T: ține–tine, laț–lat, puț–put · Ț–CE: țeapă–ceapă, ațe–ace · Ț–S: țap–sap (V) |
| `sentences` (original) | „Fetița ține o ață.” · „Puiuțul e în pătuț.” · „Pițu ține un băț.” · „Țapul e lângă coteț.” · „Cățelul are un băț.” · „Pe gheață e un puiuț.” · „Bunicuța taie pizza cu un cuțit.” |
| `twister` | original: „Țuțu ține-n lăbuțe un băț și-o ață.” · original: „Un puiuț, doi puiuți, toți țopăie-n pătuț.” |
| `retell` | fetiță → ață → cățel · puiuț → pătuț → țânțar · țap → coteț → băț |
| `everyday` | „Când primești ceva: mulțumesc!” · „La periuță: dinți curați!” · „Mai vrei? Spune: încă puțin.” · „Numără degetele: toți zece!” · „Afară e frig? Spune: ce ger, ce gheață!” |

### 4.6 CE/CI — Stropitoarea Cici

| | |
|---|---|
| Replica | „Stropitoarea Cici udă florile: ci-ci-ci! Stropește și tu, scurt, ca ea.” (existentă) |
| `syllables` | cea, cio, ciu, ce, ci · „trenulețul”: ce…a → cea, ci…o → cio |
| `back` | ace, oce, uce, ece, ice · aci, oci, uci (cu i scurt, nesilabic) |
| `wordsInitial` (14) | ceas, ceapă, ceai, ciocan 🔨, cinci 5️⃣, cioc ∅, cină ∅ (V), ceață 🌫, ciocolată\*, cireșe\*, cizme\*, ciupercă\*, cerc\* ⭕, cerb\* 🦌 |
| `wordsMedial` (12; aici intră și silaba finală „-ce”) | cocean 🌽, zece 🔟, ace (poza „cos”, de înlocuit cu 🪡), vecin ∅ (V), bicicletă\*, felicitare\*, motocicletă\* 🏍, lăcustă\* 🦗, căciulă\* ∅, berbece\* 🐏, foarfece\* ✂, măceșe\* (V) |
| `wordsFinal` (14; „-ci” cu i nesilabic) | nuci, vaci, cinci 5️⃣, maci 🌺, papuci 🥿, copaci 🌳, bunici 👴, saci ∅, arici\*, raci\*, melci\*, porci\*, colaci\* 🥯, furnici\* 🐜 |
| `clusters` (9) | cinci, bocanci 🥾, melci\*, porci\*, cerc\*, cercel\* ∅, bicicletă\*, ciupercă\*, tricicletă\* ∅ |
| `pairs` | CE–Ț: ceapă–țeapă, ace–ațe · CE–Ș: cină–șină · CE–T: cine–tine · CE–GE: cer–ger, reci–regi |
| `sentences` (original) | „Ce faci, vecine?” · „Cici bea ceai cu nuci.” · „Bunicii au cinci vaci.” · „Tata ține un ciocan.” · „La cină avem ceapă.” · „Am zece ace și cinci nuci.” · „Cinci nuci cad pe ceas.” |
| `twister` | original: „Cinci pitici cer nuci, cinci nuci cer pitici.” · original: „Cici face cinci ceaiuri și zice: ce de ceai!” |
| `retell` | arici → nuci → copaci · ceas → cină → ceai · bunici → vaci → ciocan |
| `everyday` | „Întreabă pe cineva: ce faci?” · „Numără treptele până la cinci.” · „Seara: îmi citești ceva?” · „La masă: ce bun e!” · „Arată cinci degete și spune: cinci!” |

Atenție: în „nuci”, „arici”, „papuci” i-ul final nu e silabă. Clipul-model trebuie să sune exact așa (§7).

### 4.7 GE/GI — Motorașul Gigel

| | |
|---|---|
| Replica | „Motorașul Gigel pornește: gi-gi-gi! Palma pe gât și pornește-l și tu.” (existentă) |
| `syllables` | gea, gio, giu, ge, gi · „trenulețul”: ge…a → gea |
| `back` | age, oge, uge, ege, ige · agi, ogi, ugi (i scurt) |
| `wordsInitial` (14) | geam, gem, geacă, geantă, gemeni 👯, geană ∅ (V), gigant ∅ (V), gips ∅ (V), girafă\*, ger\*, gel\* 🧴, gimnastică\*, girofar\* 🚨 (V), genunchi\* 🦵 |
| `wordsMedial` (12) | pagină, magie, deget ☝, agendă 📒 (V), imagine ∅ (V), vegetale\* 🥦 (V), regină\*, rege\* 🤴, magician\*, frigider\* ∅, legendă\* (V), fulgere\* |
| `wordsFinal` (11; aproape doar plurale) | covrigi\*, fulgi\*, colegi\*, pungi 🛍, mingi ⚽, blugi\* 👖, fagi (V), fragi\* ∅, dragi (V), lungi (V), fugi (V) · logopedul poate sări treapta |
| `clusters` (9) | minge 🏀, ninge 🌨, geantă, înger\*, fulgere\*, mărgele\*, gimnastică\*, merge\* (🚶 există), argint\* 🥈 |
| `pairs` | GE–CE: ger–cer, regi–reci, fagi–faci, magi–maci (V) |
| `sentences` (original) | „Gina are gem pe deget.” · „Geanta e lângă geam.” · „Mingea e în geantă.” · „Afară ninge și e ger.” · „Gelu pune gem în pungi.” · „Gigi dă o pagină.” · „Gemenii au mingi noi.” |
| `twister` | original: „Gigi unge gem pe deget și fuge la geam.” · original: „Ninge, ninge, Gelu-și pune mingea-n geantă.” |
| `retell` | girafă → geam → geantă · gem → deget → covrigi · minge → fulgi → geacă |
| `everyday` | „Uită-te pe geam și spune ce vezi.” · „La micul dejun: pâine cu gem!” · „La plecare: îmi iau geanta!” · „Ninge? Spune: ninge!” · „Arată un deget și numește-l.” |

„ghe/ghi” (ghete, ghiozdan) e G dur și nu intră aici.

### 4.8 R — Tigrul Rică

| | |
|---|---|
| Replica | „Tigrul Rică mârâie când se joacă: rrr! Mârâie și tu, ca un tigru.” (existentă) |
| `syllables` | ra, ro, ru, re, ri, ră, râ · ajutor: tra, dra, tro, dru |
| `back` | ar, or, ur, er, ir · ara, oro, uru, ere, iri |
| `wordsInitial` (15) | rac, roată, robot, ramă (🖼 există), rechin 🦈, raton 🦝, rachetă 🚀, radio 📻, râmă 🪱, râu 🏞, robinet 🚰, rață\*, roșie\* 🍅, rochie\*, rinocer\* |
| `wordsMedial` (14) | pară, coroană, iepure, ureche 👂, vioară 🎻, pirat 🏴‍☠️, baterie 🔋, mure 🫐, aripă 🪽, pădure 🌲, burete 🧽, girafă\*, sirenă\* 🧜, parașută\* 🪂 |
| `wordsFinal` (16) | măr, nor, vapor (🚢 există), pahar (🍷 există, de înlocuit cu 🥛), păr 💇, piper 🌶, castor 🦫, pompier 🧑‍🚒, bucătar 🧑‍🍳, covor ∅, motor ∅, car (V), zar\*, ziar\*, dinozaur\*, picior\* 🦶 |
| `clusters` (15) | tren (poza lui Șușu), trei 3️⃣, tractor, trandafir, trompetă 🎺, drum 🛣, dragon 🐉, broască 🐸, creion ✏, tigru (poza lui Rică), morcov, barcă ⛵, cort ⛺, arc 🏹, furnică 🐜 |
| `pairs` | R–L: rac–lac, ramă–lamă, car–cal, var–val, rege–lege · lipsă: rață–ață, rac–ac |
| `sentences` (original) | „Racul are o roată.” · „Robotul repară radioul.” · „Rada are o pară.” · „Pe râu e o rață.” · „Iepurele are urechi mari.” · „Norul e sus, pe cer.” · „Vaporul are motor.” · „Trei tigri trag un tren.” |
| `twister` | tradiționale: „Capra calcă piatra…”, „Rică nu știa să zică…” · original: „Rada ară, Radu rade, rața râde.” |
| `retell` | rac → râu → rață · robot → roată → rachetă · iepure → pădure → măr |
| `everyday` | „La gustare: un măr, te rog!” · „Găsiți trei lucruri roșii.” · „Mârâie ca un tigru: rrr!” · „Spune cuiva: mersi frumos!” · „La culcare: noapte bună, somn ușor!” |

La copii acceptăm și R-ul cu o singură bătaie. Lista „spune R cu L” din carte nu se folosește.

### 4.9 L — Leul Lalalu

| | |
|---|---|
| Replica | „Leul Lalalu cântă cu limba sus: la-la-la! Cântă și tu cu el.” (existentă) |
| `syllables` | la, lo, lu, le, li, lă, lî |
| `back` | al, ol, ul, el, il · ala, olo, ulu, ele, ili |
| `wordsInitial` (15) | lună, leu, lămâie, lalea, lac, lapte, limbă (👅 există), lup 🐺, lebădă 🦢, lacăt 🔒, liliac 🦇, lift 🛗, lupă 🔍, lingură\*, lumânare\* 🕯 |
| `wordsMedial` (15) | balon, telefon, elefant, cămilă 🐫, delfin 🐬, balenă 🐋, pilot 🧑‍✈️, halat 🥼 (V), ulei ∅, bilă ∅, salată\*, pălărie\*, ochelari\*, ciocolată\*, calendar\* (📅 există) |
| `wordsFinal` (16) | cal, inel, val, fotbal (⚽ există), miel 🐑, copil 🧒, tunel 🚇, bol 🥣, papagal 🦜, hotel 🏨, cățel\*, castel\*, spital\*, porumbel\* 🕊, purcel\* 🐷, clopoțel\* |
| `clusters` (15) | melc, albină (🐝 există), bloc 🏢, plic ✉, ploaie 🌧, glob 🌍, clovn 🤡, flaut 🪈, vulpe 🦊, clătite 🥞, bluză\*, plajă\*, floare\* 🌸, fluture\* 🦋, clopoțel\* |
| `pairs` | L–R: lac–rac, cal–car, lamă–ramă, val–var, lege–rege · L–I: lac–iac 🐃 (V) |
| `sentences` (original) | „Lupul bea lapte.” · „Luna e lângă lac.” · „Lili are un balon alb.” · „Calul e pe deal.” · „Copilul ține un inel.” · „Elefantul e la bal.” · „Delfinul face un val.” · „Papagalul cântă la-la-la.” |
| `twister` | original: „Leul Lalalu lipește litere pe lună.” · tradițională (7+): „Luni la lumina lumânării…” |
| `retell` | leu → balon → lună · lup → lac → lebădă · copil → cal → deal (val) |
| `everyday` | „Dimineața: vreau lapte, te rog!” · „La plecare: la revedere!” · „Seara, căutați luna.” · „Cântă la-la-la la baie.” · „Numește ce e alb în cameră.” |

L nu are detector acustic propriu: la `syllables` și `back` judecă adultul, iar cardul o spune clar.

**Bilanț:** ~470 de cuvinte, din care ~170 au deja poză; ~230 se acoperă cu emoji din același set; ~70 cer desen (∅) sau rămân pe dinafară până la M6. Sub pragul de 12: Z final (9), J final (11) și grupuri (7), GE final (11), Ș/Z/CE/GE grupuri (9–10).

---

## 5. I3 — Biblioteca de încălzire (15 exerciții)

| # | `id` | Nume pentru copil | Cum o spune Foni | Cât | Pregătește | Verificare |
|---|---|---|---|---|---|---|
| 1 | `burta-balon` (nou) | Burta-balon | „Mâna pe burtă. Umflă balonul, apoi suflă lung.” | 3 respirații | toate | microfon (expir lung) |
| 2 | `pupic` | Pupicul | „Fă gura ca pentru un pupic!” | 3 × 1,5 s | Ș, J, CE, GE | cameră |
| 3 | `zambet` | Zâmbetul de crocodil | „Zâmbește cât poți de larg!” | 3 × 1,5 s | S, Z, Ț | cameră |
| 4 | `zambet-pupic` | Zâmbet și pupic | „Schimbă: zâmbet, pupic, zâmbet, pupic!” | 4 schimbări | S↔Ș, Ț↔CE | cameră |
| 5 | `hipopotam` | Hipopotamul cască | „Deschide gura mare, ca un hipopotam!” | 3 × 1,5 s | L, R | cameră |
| 6 | `motorasul-buzelor` (nou) | Motorașul buzelor | „Buze moi. Fă ca motorul: brrr!” | 3 × 2 s | R | microfon |
| 7 | `calul` | Mersul căluțului | „Plescăie cu limba: clop, clop, clop!” | 4–6 | R, L, CE | microfon (clicuri) |
| 8 | `catelusul` | Cățelușul bea apă | „Scoate limba și bag-o la loc!” | 4 | L | cameră (limba afară) |
| 9 | `ceasul` | Bate ceasul tic-tac | „Mută limba dintr-un colț în altul!” | 4 | L, R | cameră |
| 10 | `cameleon` | Limba cameleonului | „Întinde limba spre nas, apoi spre bărbie!” | 4 | L, R, Ș | cameră |
| 11 | `limba-sus-gura` | Limba urcă pe deal | „Deschide gura. Urcă limba după dinții de sus.” | 3 × 1,5 s | L, R | cameră doar cu gura larg deschisă; altfel adultul |
| 12 | `gat-vibreaza` | Gâtul care bâzâie | „Palma pe gât. Spune lung: mmmm.” | 2 × 1,5 s | Z, J, GE | microfon (voce) |
| 13 | `sarpele-lung` (nou) | Șarpele lung | „Sâsâie cât mai lung: sssss.” | 2–3 | S, Z | microfon (durată) |
| 14 | `morisca` / `lumanarea` | Morișca | „Suflă tare în morișcă! Acum încet.” | 3 suflări | S, Ș, R | microfon (suflat) |
| 15 | `periuta` (nou) | Periuța cu limba | „Limba e periuță. Atinge fiecare dinte.” | ~10 s | S, Ț, L | adultul |

Rămân în fișier, dar în afara celor 15: `balon`, `dinti`, `buze-nas`, `limba-jos`, `limba-sus`, `bomboana`, `tic-tac` (folosite de listele `warmups` existente).

**Reguli.** Cel mult **60 de secunde** de încălzire pe sesiune: două exerciții din lista sunetului plus unul care se rotește, alese determinist. Dacă poarta de calitate a camerei cade, exercițiul se închide cu „Am făcut” de la adult, fără verdict. Fără rețineri de aer. În texte îi spunem „trezim gura”; nu promitem că exercițiile repară vorbirea.

---

## 6. I4 — Frământări

### 6.1 Tradiționale (folclor, numite prin primele cuvinte; textul se ia din surse care nu sunt cartea și se notează în `folkRef`)

| Sunet | Frământare | Dificultate | Unde |
|---|---|---|---|
| S | „Sarea scoasă din solniță…” | ușor | copii |
| S/Ș | „Șase sași în șase saci” | mediu | copii, după `pairs` S–Ș |
| Ș | „Știu că știi că știu că știi” | mediu | copii |
| S/Ș | „Cosașul Sașa…”, „Șapte sape late…” | greu | adolescenți, adulți |
| R | „Rică nu știa să zică…” | ușor | copii |
| R | „Capra calcă piatra…” | mediu | copii 7+ |
| R | „O barză brează…”, „Hribi fierți…” | mediu | adolescenți |
| Z | „Când am zis c-am zis…” | mediu | adolescenți |
| L | „Luni la lumina lumânării…” | mediu | copii 7+ |
| Ț | „E pestriță prepelița…” | mediu | adolescenți |
| CE | „Cinci sute cincizeci și cinci…” | greu | adolescenți |
| amestec | „Stanca stă-n castan…”, „Cupă cu capac…”, „Un vultur stă pe pisc…” | ușor | încălzire pentru adolescenți |

### 6.2 Originale pentru copii (scrise pentru Fonia)

1. S — „Sus pe sanie stă Sami, sub sanie stă un sac.”
2. S — „Sosul sare-n supă, supa sare-n sos.”
3. Ș — „Păpușa Șuși șade-n coș și șoptește: șșș!”
4. Ș — „Șase șoimi șoptesc și șed pe șine.”
5. Z — „Zoe și Zizi zăresc zece zâne pe zid.”
6. Z — „Zuzu zice zi de zi: zum, zum, zum!”
7. J — „Jojo joacă jos un joc cu jeleu.”
8. J — „Jana ajunge jos cu-n bagaj bej.”
9. Ț — „Țuțu ține-n lăbuțe un băț și-o ață.”
10. Ț — „Un puiuț, doi puiuți, toți țopăie-n pătuț.”
11. CE — „Cinci pitici cer nuci, cinci nuci cer pitici.”
12. CE — „Cici face cinci ceaiuri și zice: ce de ceai!”
13. GE — „Gigi unge gem pe deget și fuge la geam.”
14. GE — „Ninge, ninge, Gelu-și pune mingea-n geantă.”
15. R — „Rada ară, Radu rade, rața râde.”
16. R — „Trei tigri trag trei trăsuri.”
17. L — „Leul Lalalu lipește litere pe lună.”
18. L — „Un balon alb, un val, un cal: la-la-la la bal!”

### 6.3 Scara de tempo

- **Copii:** două trepte, „melc” (o silabă pe bătaia tobei, ~1,5 silabe/s) și „iepuraș” (un pas mai repede, ~2,5 silabe/s). Treapta a doua apare doar după o rostire clară pe prima. **Niciodată cronometru sau numărătoare inversă**; toba doar ține ritmul. Reușită: clar de două ori la rând, hotărăște adultul.
- **Adolescenți și adulți:** 2,5 → 3 → 5 silabe/s, de trei ori la rând fără oprire. La o rostire neclară coboară o treaptă; după două reușite urcă înapoi. 7,5 silabe/s e doar „provocare”, în M6. Viteza se măsoară din vârfurile de energie (silabe pe secundă), claritatea din potrivirea cuvintelor.

---

## 7. I5 — Replicile și vocea lui Foni

### 7.1 Categorii de replici

| Categorie | Cheie | Câte | Exemplu |
|---|---|---|---|
| Onboarding `ON-0…ON-7` | `on.<n>.<k>` | 28 | vezi mai jos |
| Deschideri pe erou și treaptă | `open.<sunet>.<rung>` | 126 (9 × 14) | „Sâsâilă a pierdut cuvintele cu S. Le căutăm?” |
| Model și „rândul tău” | `model.*` | 14 | „Ascultă.” · „Acum tu!” · „Spune după mine.” · „Ce e în poză?” |
| Laudă care numește fapta | `praise.<fapt>` | 36 | tabelul de mai jos |
| Instrucțiuni pe eroare | `msg.<kind>.<grup>` | 17 | §3.6 |
| „Nu sunt sigur” | `unsure.*` | 6 | „Hmm, n-am auzit bine. Mai spunem o dată?” · „Întreabă-l pe cel mare de lângă tine.” |
| „Mai spune o dată” | `retake.*` | 5 | „A fost gălăgie. Încă o dată!” · „Mai aproape de telefon.” |
| Încălziri | `warm.<id>` | 22 | §5 |
| Mișcare | `move.*` | 4 | „Sus! Zboară ca mine: fâl, fâl, fâl!” |
| Final și „încă una” | `end.*` | 12 | „Gata pe azi! Ascultă-te.” · „Mai vrei una mică?” · „Pe mâine!” |
| Lacătul | `lock.*` | 3 | „Cheamă un om mare să te asculte.” |
| Rostite pentru adult | `adult.*` | 12 | „Pentru adult: vocea copilului rămâne pe telefon.” · „Dacă a sunat bine, apăsați degetul mare.” |
| Dicție (adolescenți) | `dic.*` | 30 | „Inspiră patru timpi. Expiră șase.” |
| **Total replici** | | **~315** | |
| Itemi (silabe, cuvinte, propoziții, perechi, frământări, povești) | `<sunet>.<tip>.<slug>` | ~1.300 | |

**Onboarding** (de aliniat cu capitolul 01): `ON-0` „Bună! Eu sunt Foni. Cine e lângă mine?” · `ON-1` „Asta e pentru omul mare. Eu aștept.” · `ON-2` „Vreau să te aud! Spune: Bună!” · `ON-3` „Ura, te-am auzit!” / „E cam gălăgie aici. Ne mutăm?” · `ON-4` „Eu zic, tu zici: șarpe!” / „Bravo că ai încercat!” · `ON-5` „Vrei să te vezi în oglinda magică? Fă buze de pește!” · `ON-6` „Prima ta literă! Mâine îți arăt un secret.” · `ON-7` (adult) „Când exersați de obicei? Vă amintesc eu, o singură dată pe zi.”

**Lauda** se alege după ce s-a întâmplat, nu la întâmplare. Nu există „Ești deștept”.

| Ce s-a întâmplat | Replici |
|---|---|
| Sunet ținut lung | „Ai ținut sunetul lung!” · „Ce lung a fost!” · „Până la capăt!” |
| Mai lung decât data trecută | „Mai lung ca ieri!” · „Ai bătut recordul tău!” |
| Buze rotunde (cameră) | „Buze rotunde, exact așa!” · „Pupic perfect!” |
| Zâmbet larg (cameră) | „Zâmbet de crocodil!” · „Ți-am văzut dinții!” |
| Limba a stat ascunsă | „Limba a stat după dinți!” · „Limba cuminte, în gură!” |
| Voce pornită (Z, J, GE) | „Ți-a bâzâit gâtul!” · „Motorul a pornit!” |
| Aer rece, S clar | „S ascuțit, ca un ac!” · „Aer rece, drept!” |
| Ș clar | „Ș rotund și cald!” · „Ca un tren adevărat!” |
| R a vibrat | „Limba a tremurat!” · „Ai mârâit ca Rică!” |
| L cu limba sus | „Limba a urcat sus!” · „Ai cântat ca Lalalu!” |
| Trei silabe la rând | „Trei la rând!” · „Tot trenul, vagon cu vagon!” |
| Sunetul la final nu s-a pierdut | „Finalul s-a auzit clar!” · „N-a fugit sunetul!” |
| A reușit după mai multe încercări | „Ai mai încercat și a ieșit!” · „Nu te-ai lăsat!” |
| Reușită după instrucțiune | „Ai schimbat ceva și a mers!” · „Ai făcut exact ce ți-am arătat!” |
| A numit singur poza | „Ai știut singur!” · „Fără ajutor!” |
| A prins greșeala lui Foni | „M-ai prins!” · „Ai urechi bune!” |
| Propoziție întreagă | „Toată propoziția, clar!” · „Fiecare cuvânt s-a auzit!” |
| Frământare la tempo mai mare | „Și repede, și clar!” · „Limba nu s-a încurcat!” |

### 7.2 Chei și fișiere

- Manifestul păstrează forma `ro|foni|<text normalizat>` → fișier, ca `VoiceIndex` să meargă neschimbat, și primește un al doilea index `clipId → fișier` (`ro/foni/<clipId>.mp3`). Vocea veche `ro` rămâne ca rezervă până la M4.
- Sursa pentru generare: `voice/src/foni.csv` cu coloanele `clipId, text, speakAs, type, sound, maxSeconds, seed, rev`. `speakAs` ține ortografia ajutătoare (de exemplu „nuci” cu indicație de final scurt).

### 7.3 Generarea cu ElevenLabs (pe PC, la construirea conținutului; aplicația rămâne fără internet)

| Pas | Decizie |
|---|---|
| Vocea | **Una singură**, aleasă printr-o audiție: 3 voci candidate × 20 de rânduri (silabe, cuvinte cu ș/ț/î, o propoziție, o laudă), ascultate de echipă și de logoped. Criterii: ș și ț corecte, ritm calm, timbru cald, fără accent străin. Fără clonarea vocii cuiva |
| Modelul | modelul multilingv stabil al contului (`eleven_multilingual_v2` sau cel mai nou care trece audiția), cu limba fixată pe română |
| Setări | stabilitate 0,55–0,65, similaritate 0,75, stil 0, „speaker boost” pornit, sămânță fixă pe rând, context (`previous_text`) gol la cuvinte izolate |
| Lot | `tools/voice_generate.py` citește CSV-ul, sare peste rândurile cu `rev` neschimbat, scrie WAV brut în `voice/raw/` |
| Formatul final | MP3 mono 44,1 kHz, 96 kbps (îl citesc și Android, și iOS); −19 LUFS, vârf ≤ −1,5 dBTP; liniște tăiată la 60 ms în față și 120 ms la coadă |
| Cost (estimare) | replici ~315 × 45 caractere ≈ 14.000; itemi ≈ 16.000; dicție și adult ≈ 5.000. Total ≈ 35.000 de caractere; cu 2–3 reluări, **~100.000 de caractere**, adică un abonament lunar de nivel „Creator”. De verificat în cont |
| Mărime în APK | ~1.600 de clipuri × ~12 KB ≈ 20 MB |

### 7.4 Verificarea automată a fiecărui clip (`tools/voice_check.py`)

| Verificare | Regulă |
|---|---|
| Durată | silabă 0,25–0,9 s; cuvânt 0,4–1,6 s; propoziție ≤ 5 s; replică ≤ `maxSeconds`; laudă ≤ 1,5 s |
| Tărie | −19 LUFS ± 1; fără tăiere a vârfurilor; liniște la capete în limite |
| Cuvântul | recunoaștere de vorbire pe PC; textul normalizat trebuie să fie identic |
| Fonemele | modelul de foneme din `:engine:judge`, rulat pe PC: secvența așteptată, cu sunetul-țintă la poziția declarată |
| Sunetul-țintă | detectoarele proprii: S față de Ș (centrul spectral), voce la Z, J, GE, rafală la Ț, CE, GE, vibrație la R |
| Finalul „-ci/-gi” | fără vocală plină la final (durata vocalică sub prag) |
| La eșec | până la 3 semințe noi; apoi clipul intră în coada de ascultat de om |

### 7.5 Silabele și sunetele izolate (unde sinteza greșește de obicei)

1. **Sunetele izolate** (sss, șșș, zzz, jjj, lll, rrr, ț-ț-ț, ci-ci-ci, gi-gi-gi) **nu se sintetizează din text.** Le rostește un om (ideal logopedul), iar înregistrarea se trece prin funcția „voce în voce” ca să capete timbrul lui Foni. Dacă rezultatul nu trece verificarea, rămâne înregistrarea omului.
2. **Silabele** se generează în purtător („sa, sa, sa.”), se taie rostirea din mijloc cu aliniere forțată și se verifică fonem cu fonem. Cele care pică de trei ori trec pe calea de la punctul 1.
3. **Cuvintele „încurcate” de Foni** pentru `listen` („tanie” pentru „sanie”) se fac tot pe calea 1, pentru că sinteza le „corectează”.
4. Trenurile de silabe se montează din silabe verificate, cu pauze fixe.

### 7.6 Ascultarea pe sărite de către logoped

Lista pentru om (o foaie HTML cu redare și trei butoane: bun / de refăcut / de scos): toate sunetele izolate (9), toate silabele (≈ 290), toate cuvintele cu final „-ci/-gi”, toate perechile, cuvintele „încurcate”, câte 10 cuvinte la întâmplare pe sunet și toate instrucțiunile pe eroare. Rezultatul se scrie în `review` (§12).

---

## 8. I6 — Traseul de dicție pentru adolescenți și adulți

Șapte module, în ordinea clasică a manualelor românești de dicție. Toate textele sunt originale; structura e cea descrisă în raportul AA §B3.

| Modul | Exerciții (texte originale sau generatoare) | Ce măsoară telefonul | Când |
|---|---|---|---|
| **1. Postură și respirație** | „Stai drept, în picioare.” · ciclu numărat 4 inspir – 6 expir × 5, apoi 4–8 și 5–10; pauză de cel mult 2 s · **„Câte numere pe un aer?”**: numeri 1, 2, 3… pe un singur expir; trepte 10 / 15 / 20 / 25 / 30; se oprește la prima inspirație auzită · lista care crește: „La piață am luat mere.” → „…mere și pere.” → „…mere, pere și prune.” | durata expirului, durata vocii, numărul atins | **v1 (M3)** |
| **2. Vocea** | accent mutat: „Maria a dus ieri cartea la bibliotecă.”, spusă de patru ori ca răspuns la Cine? Ce? Când? Unde? · emoții: „Mâine plecăm la munte.”, pe rând vesel, obosit, îngrijorat, ironic, hotărât, cald · afirmație ↔ întrebare (perechile din `diction.json`) · crescendo fără trepte, din șoaptă până la voce de sală (nu strigăt) · „Simte secunda”: numeri până la 30 și compari cu ceasul | înălțimea vocii pe cuvântul apăsat, plaja în semitonuri, curba de volum fără salturi | M6 |
| **3. Încălzire** | 3 minute: zâmbet ↔ buze rotunde × 10; „brrr” 5 s × 3; limba pe fiecare dinte; limba la nas și la bărbie; „trrr” 15–30 s; falca stânga–dreapta 15 s; gumă imaginară; vocale mimate fără sunet | cameră (buze, falcă, limba afară), microfon (vibrații) | **v1 (M3)** |
| **4. Sunete pe poziții** | **grila de silabe** generată: consoană × 7 vocale × tiparele CV, VC, VCV, CVC, CVCV, cu **cursor de tempo** 1,5 / 2,5 / 3 / 4 / 5 silabe/s; despărțit → contopit la CE, GE, Ț · **finaluri clare**: „Doi pomi înalți, trei nori albi, patru brazi verzi.” · „Un brad verde, un pod lat, un gard ud.” · lista `finalIWords` · „schimbă sunetul” doar cu cuvinte reale (sare–zare–țare) | detectoarele pe sunet, energia de la final, silabe/s | M6 |
| **5. Frământări pe scara de viteză** | tradiționalele din §6.1 și originale pentru adulți: „Sanda strânge sistematic scrisorile sosite sâmbătă seara.” · „Rareș repară rapid trei radiouri ruginite.” · „Cecilia cere cinci cești de ceai cu gheață.” · regula 2,5 → 3 → 5 silabe/s, de trei ori | silabe/s, potrivirea cuvintelor, sunetele pierdute | **v1 (M3)**, cu 6 frământări |
| **6. Citit cu voce tare** | text cu pauze marcate: „Dimineața, \| orașul se trezește încet. \| Un tramvai trece, \| o fereastră se deschide, \| și cineva, \| undeva, \| fluieră.” · aceeași citire cu buzele lipite, înregistrată: pauzele trebuie să cadă pe semne · șase sfaturi de accent (cuvântul nou, negația, cifra, finalul care coboară, paranteza spusă mai jos, cuvântul care întreabă) | pauzele față de punctuație, intonația la final | M6 |
| **7. Prezentare** | `MINUTE`: 60 de secunde pe o temă din `speechPrompts`, apoi **`NO-UM` („Tu, fără ăăă”)**: aceeași înregistrare cu umpluturile tăiate, apoi „spune-o tu așa” | umpluturi, ritm, pauze, diferența între cele două rostiri | **v1 (M3)** (D16) |

**Siguranță.** Fără rețineri lungi de aer (maximum 2 s) și fără exerciții de efort; la amețeală, mesajul „Oprește-te și respiră normal”. Fără coborârea forțată a vocii. **Creionul între dinți** nu apare în v1; dacă intră în M6, e doar pentru profilul de adult, opțional, cu avertisment („doar un obiect curat, niciodată la copii, oprește-te dacă te doare falca”) și fără verificare automată. La umpluturi rămâne nota existentă despre bâlbâială și logoped.

---

## 9. I8 — Textele pentru părinți

### 9.1 Cele șase `ParentCard`

| Card (trepte) | Ținta | O încercare bună | Dacă nu iese |
|---|---|---|---|
| **Ascultăm și trezim gura** (`listen`, `warmup`) | Copilul aude diferența și își mișcă buzele și limba | Arată poza potrivită; ține poziția o secundă | Faceți voi primul, în oglindă. Două exerciții ajung |
| **Sunetul singur** (`isolated`) | Sunetul sună curat, singur | {S} lung și egal, 2 secunde, fără alt sunet lipit | Nu insistați. Opriți-vă și spuneți-i logopedului; treapta următoare se deschide doar când sună bine |
| **Silabe** (`syllables`, `back`) | Sunetul lipit de o vocală | „sa” dintr-o bucată, cu {S} clar | Despărțiți: „s… a”, apoi apropiați încet |
| **Cuvinte** (`wordsInitial` … `clusters`) | Sunetul în cuvinte, la început, mijloc și final | Cuvântul întreg, cu {S} la locul lui | Spuneți voi cuvântul rar, apăsând pe sunet. După două încercări, treceți mai departe |
| **Nu le încurcăm** (`pairs`) | Deosebește {S} de {Ș} | Arată corect poza, apoi spune ambele cuvinte | Rămâneți la ascultat. Foni greșește, copilul îl prinde |
| **Vorbim așa** (`sentences`, `twister`, `retell`, `everyday`) | Sunetul rămâne bun și când vorbește liber | Propoziția curge, sunetul e la locul lui | Încetiniți. Fără cronometru. În restul zilei nu corectați |

### 9.2 Zece sfaturi (forma scurtă pentru aplicație)

1. Stați față în față, la aceeași înălțime, cu telefonul între voi.
2. Liniște în cameră: fără televizor.
3. Puțin și des: patru minute pe zi, la aceeași oră.
4. Opriți-vă cât încă e bine.
5. Lăudați ce a făcut: „Ai ținut s-ul lung!”
6. Nu spuneți „greșit”. Repetați voi cuvântul, rar și frumos.
7. Nu corectați la masă sau în fața altora.
8. După două încercări care nu ies, treceți la ceva mai ușor.
9. Graiul nu e greșeală. Lucrăm doar sunetele de la logoped.
10. Spuneți-i logopedului ce ați auzit. Raportul din aplicație e pentru el.

### 9.3 Rezumatul de la final (o propoziție, cu variabile)

- „Azi: {n} încercări la {S}. Cea mai bună: ▶.”
- „Azi: {n} încercări. {S} a ținut {sec} secunde, mai mult ca data trecută.”
- „Azi ați terminat treapta «{treaptă}». Mâine: «{următoarea}».”
- „Azi a fost mai greu la {poziție}. Mâine o luăm mai ușor.”
- „Telefonul n-a fost sigur de {k} ori. Părerea voastră a contat.”
- „Spuneți-i: ai încercat de {k} ori până a ieșit.”

Fără procente și fără roșu. O săptămână slabă se descrie neutru: „{z} zile, {n} încercări”.

### 9.4 Amintirea (către adult, una pe zi)

- „După {ancoră}, {S} cu Foni. Patru minute.”
- „E ora voastră: {ancoră}. Foni e gata.”
- Luni, după o pauză: „Începem săptămâna asta? O rundă mică ajunge.”
- După trei amintiri ignorate (o singură dată): „Vă mai amintesc doar o dată pe săptămână. Alegeți alt moment din «Pentru adult».”

Niciun text cu vină, cu serie pierdută sau adresat copilului.

### 9.5 Confidențialitatea, pe înțeles

> **Ce se întâmplă cu vocea și cu imaginea copilului**
> Totul rămâne pe acest telefon. Aplicația nici nu are voie să folosească internetul.
> Microfonul ascultă doar când pe ecran apare capsula de microfon. Păstrăm cel mult trei înregistrări pentru fiecare sunet, cel mult 90 de zile, încuiate cu o cheie a telefonului. Ele servesc la „Ascultă-te” și la „atunci și acum”.
> Camera lucrează ca o oglindă. Imaginile nu se salvează nicăieri.
> Nu există cont, e-mail sau reclame. Nimeni din echipa Fonia nu vă vede datele.
> Raportul pentru logoped pleacă doar dacă îl trimiteți voi, și conține numere, nu înregistrări.
> „Șterge tot” din „Pentru adult” șterge tot, pe loc.
> Fonia e un ajutor pentru exersat acasă. Nu pune diagnostice și nu înlocuiește logopedul.

---

## 10. F5 — Seria, ținta săptămânală, amintirea

| Element | Regulă |
|---|---|
| **Zi exersată** | o zi calendaristică (ora telefonului) cu cel puțin o rundă terminată |
| **Ziua minimă** | o rundă de ~90 s (un bloc de 6 + finalul) contează ca zi întreagă |
| **Ce se vede mereu** | „{N} zile cu Foni”: totalul de zile exersate, care **doar crește**; câte o pană colorată pe papagal |
| **Seria blândă (D13)** | „{k} zile la rând” apare doar când k ≥ 2. O zi sărită pe săptămână nu o întrerupe (zi de rezervă, fără buton și fără preț) |
| **După o pauză** | nimic pierdut: totalul și penele rămân, eticheta „la rând” pur și simplu nu se mai arată până la k ≥ 2. **Niciun mesaj despre pauză**, niciun zero, nicio flacără stinsă. Prima sesiune e mai ușoară (§2.1) |
| **Ținta săptămânală** | implicit **3 zile din 7**, cerc din 3 bucăți; logopedul sau părintele o schimbă (2–7) din `ADULT-SETTINGS`. Săptămâna se închide duminică; cea neterminată se arhivează fără comentariu |
| **Amintirea** | una pe zi, către adult, la momentul ales în `ON-7` („după cină, 19:30”). Notificare locală programată pe telefon; fără internet. Nu se trimite dacă ziua e deja exersată și niciodată între 21:00 și 7:00 |
| **Rărirea** | trei amintiri ignorate la rând → una pe săptămână (lunea) și întrebarea despre alt moment. O sesiune făcută readuce ritmul zilnic |
| **Ce nu există** | monede, magazin, cutii-surpriză, clasament, mesaje „Foni e trist”, notificări pentru copil |

**Cele opt măsurători, ținute doar pe telefon:** (1) zile exersate pe săptămână față de țintă; (2) încercări pe zi exersată, pe sunet; (3) sesiuni terminate față de abandonate și durata mediană; (4) „Încă una?” acceptat; (5) sesiuni la ±60 de minute de momentul ales; (6) amintiri urmate de o sesiune în 2 ore și amintiri ignorate la rând; (7) zile până la revenirea după o pauză de 4 zile sau mai mult; (8) raport pentru logoped generat sau trimis. Apar în `ADULT-HOME` și în raportul PDF, ca totaluri.

Logica (`Habit`: zi exersată, serie, rezervă, țintă, rărire) e Kotlin pur în `:core:domain`, cu ceasul primit ca parametru; tabelele vin din F4; programarea notificării stă în `:app`.

---

## 11. I7 — Mesajul către Carmen Ivanov și creditele

**Mesajul (de trimis de echipă, prin formularul de pe dictie.ro, cu profesorul coordonator în copie):**

> Bună ziua, doamna Ivanov,
>
> Suntem o echipă de elevi din [liceul, orașul] și construim Fonia, o aplicație în care copiii de 4–10 ani își exersează acasă, cu un părinte, sunetele lucrate la logoped. Are și un traseu de dicție pentru adolescenți și adulți. Totul rulează pe telefon; vocea nu pleacă nicăieri.
>
> Manualul dumneavoastră, „Șase sași în șase saci”, ne-a ajutat mult să înțelegem cum se construiește dicția: respirație, încălzire, sunet cu sunet, toate vocalele și toate pozițiile, frământări, citit. Traseul nostru pentru adulți urmează această ordine, dar cu exerciții scrise de noi. Nu am preluat niciun text din carte.
>
> Vă cerem două lucruri, dacă aveți timp și plăcere:
> 1. douăzeci de minute în care să ne spuneți ce am înțeles bine și ce nu, pe traseul de dicție;
> 2. permisiunea de a include câteva exerciții din manual (vi le trimitem dinainte, unul câte unul), cu numele dumneavoastră și cu trimitere spre carte și spre dictie.ro.
>
> Ce oferim: credit vizibil în aplicație, un link spre cărțile și cursurile dumneavoastră și, dacă vă interesează, cifre anonime despre exercițiile cele mai folosite. Dacă preferați să nu apăreți deloc, respectăm asta.
>
> Vă mulțumim pentru carte și pentru timpul dumneavoastră.
> [Nume], echipa Fonia · [e-mail] · [telefon]

**Revenirea (după 7–10 zile, o singură dată):**

> Bună ziua, doamna Ivanov. Revenim scurt la mesajul despre Fonia, aplicația de exersat pronunția. Ne-ar ajuta mult și un răspuns de un rând, chiar dacă e „nu acum”. Vă mulțumim! [Nume], echipa Fonia

**Creditele din aplicație („Despre metodă”):**

- **Înainte de răspuns:** „Pentru copii, pașii urmează etapele folosite de logopezii români și sunt revăzuți de logopezi. Traseul de dicție e inspirat din structura clasică a manualelor românești de dicție: respirație, încălzire, sunet cu sunet, frământări, citit. Exercițiile sunt scrise de echipa Fonia. Frământările populare aparțin folclorului.” (fără nume, cum cere regula de mai sus)
- **După acord scris:** se adaugă „Exercițiile marcate cu ✦ sunt din «Șase sași în șase saci. Manual de dicție» de Carmen Ivanov (Favorit, 2013), folosite cu acordul autoarei.” și linkul spre dictie.ro. Itemii respectivi poartă `source: "ivanov"` și `licenseRef`.
- **Niciodată:** „metoda Carmen Ivanov”, „aprobat de”, „recomandat de”, titlul cărții ca nume de ecran, coperta ca decor.

Acordul trebuie să fie în scris, cu lista exercițiilor; pentru o versiune comercială e nevoie și de editură.

---

## 12. Ce revede un logoped înainte de lansare

| # | Ce | Întrebarea |
|---|---|---|
| 1 | Listele de cuvinte (§4), în primul rând cele cu (V) | Cuvântul e cunoscut la 4–7 ani? Poza se poate numi? Sunetul e la poziția declarată? |
| 2 | Buzele la Ș, J, CE, GE | Rotunjite, împinse înainte: confirmat? (hotărăște pozele 3D și `howParent`) |
| 3 | Ținta la R | Acceptăm o singură bătaie? „tra/dra” ca ajutor înainte de `ra`? |
| 4 | Poziția limbii la S, Z, Ț | Vârful jos, după dinții de jos: asta predăm? |
| 5 | Încălzirile (§5) | Vreuna de scos? „Motorașul” și „trrr” la copiii cu R din gât? |
| 6 | Mesajele pe eroare (§3.6) | Instrucțiunea e corectă și fără risc? Pragurile pentru „arată-i logopedului”? |
| 7 | Trucurile de emitere (`tricks`) | Rămân doar pentru logoped sau le vede și părintele? |
| 8 | Perechile (§4) | Care sunt prea abstracte? |
| 9 | Cuvintele „încurcate” de Foni | E în regulă ca Foni să spună „tanie”? |
| 10 | Frământările pentru copii | Care merg la 4–6 ani? S/Ș amestecate doar după `pairs`? |
| 11 | Clipurile (§7.6) | Sunetele izolate, silabele, finalurile „-ci/-gi” |
| 12 | Cardurile și sfaturile (§9) | Ceva de schimbat în ton sau în conținut? |
| 13 | Vârstele (`typicalByAge`) și ținta de 3 zile | Le păstrăm? |

**Cum se notează corecturile.** Unealta `tools/review_export.py` scoate o foaie (`review/logoped-<data>.csv` și varianta HTML cu redare) cu coloanele `itemId, text, verdict (ok / schimbă / scoate), notă, cine, data`. `review_import.py` scrie verdictul în `review.status` al fiecărui item (`approved`, `rejected`, `draft`) și păstrează foaia în repo. Un item `rejected` nu ajunge în aplicație (CI). Itemii (V) fără verdict nu intră în sesiuni. Raportul de construire spune câți itemi sunt aprobați și câți în ciornă; aceeași cifră apare, cinstit, în „Despre metodă”.

---

## 13. Ce conținut există la fiecare etapă

| Etapă | Conținut gata |
|---|---|
| **M0** | Schema v2 și migrarea; S și Ș: `isolated` și `syllables` din conținutul existent, cu vocea veche `ro`; validările în CI |
| **M1** | **S și Ș complete pe toate cele 14 trepte** (≥ 12 cuvinte pe poziție, cu poze), 3 mini-jocuri alimentate; vocea nouă pentru S, Ș și replicile de bază (onboarding scurt, model, 36 de laude, mesajele din valul 1, final); constructorul nou cu vectori; seria și ziua minimă |
| **M2** | Toate cele 9 sunete: `listen` → `wordsFinal` și `pairs` complete; `sentences` cu minimum 4; cele 15 încălziri; mesajele pentru toate tipurile de eroare (cele nedeblocate apar doar la adult) |
| **M3** | Cardurile, sfaturile, rezumatele, amintirea, confidențialitatea; modulele de dicție 1, 3, 5 și 7; ținta săptămânală și amintirea |
| **M4** | `clusters`, `twister` (2), `retell` (3), `everyday` (5) la toate sunetele; toată vocea regenerată, verificată automat și ascultată pe sărite; corecturile logopedului aplicate; creditele |
| **M5** | Nimic nou; doar reparații de text și de clipuri |
| **M6** | Modulele de dicție 2, 4, 6; desenele pentru itemii ∅; exercițiile cu acord, dacă vine; cuvinte în plus la Z, J, GE |

**Ordinea de tăiere:** (1) modulele de dicție 2, 4, 6; (2) `retell` de la 3 la 1 și `everyday` de la 5 la 3; (3) `clusters` la Z, J, GE; (4) itemii ∅ (rămân doar cei cu emoji); (5) vocea nouă pentru propoziții (rămâne vocea `ro`). **Nu cad:** S și Ș complete, minimum 12 cuvinte cu poză pe poziție la S, Ș, R, L, mesajele pe eroare, cardurile pentru adult, lacătul.

---

## 14. Pachetele de lucru

| Pachet | Ce cuprinde și pași | Fișiere | „Gata” înseamnă | Ore-agent | Depinde de | Riscuri |
|---|---|---|---|---|---|---|
| **I1** Schema | modelul Kotlin (`:core:model`), cititorul (`:core:domain`), scriptul de migrare, validările | `fonia-content/json/v2/**`, `tools/migrate_v1_v2.py`, `tools/check_content_v2.py`, `core/model/Content*.kt` | conținutul vechi migrat fără pierderi; validările rulează în CI; test de dus-întors JSON | 5 | A1, A5 | iOS citește încă v1: `v1` rămâne neatins |
| **I2** Cuvinte, propoziții, perechi, poze | §4 în JSON; pozele noi din setul de emoji; `catalog.json`; lista ∅ | `items/*.json`, `images/pictures/*`, `catalog.json` | fiecare item trece validările; ≥ 12 pe poziție unde limba permite; foaia pentru logoped generată | 10 (S, Ș: 3; restul: 7) | I1 | poziția sunetului greșită; cuvinte prea grele: (V) și revizia |
| **I3** Încălzirea | cele 15 exerciții, 4 noi; legătura cu pozele camerei și cu detectoarele audio | `warmups.json`, `sounds/*.json` | fiecare exercițiu are replică, clip, mod de verificare și cale „Am făcut”; limita de 60 s testată | 3 | I1, H2, H4 | camera nu vede limba în gură: adultul confirmă |
| **I4** Frământările | §6 în JSON; `folkRef`; tempourile | `items/*.json`, `diction.json` | 18 originale + tradiționalele cu sursă; niciun text din carte (verificare cu lista de fraze private, locală) | 2 | I1 | o „tradițională” e de fapt modernă: se scoate |
| **I5** Replici și voce | `foni.csv`, generarea, verificarea, foaia de ascultat, manifestul | `voice/src/foni.csv`, `tools/voice_generate.py`, `tools/voice_check.py`, `voice/ro/foni/*`, `manifest.json` | 100 % din clipuri trec verificarea automată sau au aprobare de om; niciun item fără clip | 12 + ascultarea de om | I1, I2, E3 (modelul de foneme), cont ElevenLabs | silabe pronunțate greșit: calea „voce în voce”; cost: lot incremental pe `rev` |
| **I6** Dicția | cele 7 module ca date; generatorul de grile; textele originale | `diction.json`, `core/domain/Diction*.kt` | modulele 1, 3, 5, 7 rulabile la M3; testele generatorului; avertismentele de siguranță prezente | 6 | I1, G10–G12 | timp: modulele 2, 4, 6 sunt primele tăiate |
| **I7** Mesajul și creditele | textele din §11; steagul `ivanovConsent` | `parent.json`, `docs/` | mesajul predat echipei; creditele „înainte” în aplicație; verificarea de cuvinte interzise trece | 1 | — | răspuns târziu sau negativ: nu schimbă nimic în v1 |
| **I8** Textele pentru părinți | §9 în JSON, cu variabile și plural corect | `parent.json` | fiecare șablon testat cu valori-limită (1, 2, 20 de încercări); niciun cuvânt interzis | 3 | I1, G7, G8 | ton: îl revede logopedul |
| **F2** Constructorul | §2 în `SessionBuilder`; `Rung` cu 14 valori; adaptarea; vectorii | `core/domain/SessionBuilder.kt`, `Curriculum.kt`, `adaptive/*`, `spec/vectors/sessions-v2.jsonl`, `spec/thresholds/adaptive.json` | 378+ vectori stabili la două rulări; teste de proprietăți (fără repetare imediată, raport știute/noi, o singură surpriză, durata estimată 3:30–4:30); testele vechi din `:domain` adaptate, verzi | 10 | I1, F1, F3 | vectorii se schimbă la fiecare corectură de conținut: `contentVersion` în vector |
| **F5** Seria, ținta, amintirea | `Habit` pur; programarea notificării; rărirea; cele 8 măsurători | `core/domain/Habit.kt`, `app/Reminder*.kt`, `spec/vectors/habit.jsonl` | teste pe calendar (schimbarea orei, miezul nopții, pauze, ziua de rezervă); nicio amintire noaptea sau după o zi exersată; verificat pe telefon | 5 | F4, G6, G7 | permisiunea de notificări refuzată: rămâne doar cercul săptămânal |

Total: ~57 de ore-agent, din care I2, I5 și F2 se pot lucra în paralel după I1.

---

## 15. Propuneri de schimbare și întrebări deschise

### Propuneri de schimbare (nu am modificat planul-mamă)

1. **D13 (seria).** Rapoartele X și Y și ghidurile UE din 2025 pentru minori spun că seria zilnică care se rupe face rău. Am păstrat seria, dar blândă (§10). Propun ca după semifinală afișajul principal să rămână totalul „zile cu Foni” și cercul săptămânal.
2. **Ținta adaptării.** Codul de azi (`ChallengePoint`) țintește 60–80 %; cerința capitolului e ~80–85 %. Propun mutarea pragurilor în `spec/thresholds/adaptive.json` și hotărârea după probele cu familii.
3. **R: „tr/dr” înaintea lui `isolated`.** Logopedia obține des R-ul întâi în grup. Ordinea celor 14 trepte rămâne; propun doar silabele ajutătoare „tra, dra” la `syllables` și „trrr” la `isolated`, dacă logopedul confirmă.
4. **D10 față de raportul N.** N recomandă voce umană înregistrată. Urmez D10, dar sunetele izolate și silabele pornesc de la o rostire umană (§7.5). Dacă peste 10 % din clipuri pică la ascultarea logopedului, propun o voce umană pentru silabe.
5. **Nume care se ciocnesc.** În planul-mamă, „F2” și „F5” sunt și decizii (§2), și pachete (§7). Propun prefixul „P-” pentru pachete sau „DF” pentru decizii.
6. **`wordsFinal` la Z, J și GE** nu ajunge la 12 cuvinte potrivite pentru copii (limba română are puține). Propun ca treapta să poată fi sărită de adult la aceste sunete.
7. **Numele animațiilor pe eroare** (§3.6) sunt propuneri; capitolele 03 și 05 le pot redenumi, dacă `messages.json` le urmează.
8. **Replicile `ON-0…ON-7`** sunt scrise după raportul N; ecranele le definește capitolul 01. Dacă numerotarea diferă, se mută cheile, nu textele.

### Întrebări deschise

1. Ce logoped revede foaia din §12 și când (luni, cele 30 de minute din planul-mamă, nu ajung pentru ~470 de cuvinte)?
2. Buzele la Ș, J, CE, GE și ținta la R: confirmarea logopedului înainte de pozele 3D.
3. Cine rostește sunetele izolate și silabele-sursă pentru „voce în voce”?
4. Contul ElevenLabs: ce abonament și ce voce (audiția din §7.3)? Licența vocii alese permite folosirea într-o aplicație comercială?
5. Itemii ∅ (~70): desenează cineva sau rămân pe dinafară până la M6?
6. Ținta săptămânală implicită: 3 zile (propunerea de aici) sau 5 (cât recomandă de obicei logopezii)?
7. Mesajul către Carmen Ivanov: pleacă înainte sau după încărcarea din 26–27? Cine semnează?
8. Creionul pentru adulți: intră vreodată sau îl lăsăm deoparte definitiv?
9. C și G dur (cuc, gâscă), frecvente la preșcolari, lipsesc din cele 9 sunete. Le adăugăm după finală?
