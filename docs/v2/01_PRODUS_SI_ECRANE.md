# 01. Produsul și ecranele

Ce face aplicația, fiecare ecran cu stările lui și pachetele G1–G12. Numele și deciziile sunt cele din `00_PLAN_MASTER.md` și nu sunt repetate aici. Literele din paranteze trimit la rapoartele din `Fonia_Research_Hub/redesign/`. **[P]** = propunere a acestui capitol, ușor de răsturnat. Replicile lui Foni au chei (`on2.ask`) pentru scriptul vocii din capitolul 06. Mărimile sunt în dp (pe iOS, pt).

## 1. Principii

| # | Principiu, pe ecran | De unde vine |
|---|---|---|
| 1 | **Vocea înaintea formularelor.** Foni vorbește în 5 s, copilul pe la secunda 45, prima reușită vine sub 3 minute. Fără cont. Permisiunile se cer în clipa folosirii | N: Duolingo, ~+20% utilizatori zilnici după mutarea înscrierii |
| 2 | **Un drum, un buton.** Un ecran (PATH), un `ContinueButton`. În lecție nimic nu se mișcă de la sine | O: neatenție 38,6% în clasa decorată, 28,4% în cea simplă |
| 3 | **Litera e feedback-ul,** pe măsura vocii: cât ții, atât se umflă | S; Y: efectele moderate bat și lipsa lor, și excesul (N = 3.018) |
| 4 | **Microfon deschis înseamnă liniște.** Niciun sunet, nicio vibrație; răspunde doar litera | Y §8c; O |
| 5 | **Nu „greșit”, ci ce să faci.** Fără roșu, X sau sunet de eșec. O eroare e numită doar dacă tipul ei e deblocat | AB §3.1; X: feedback-ul care informează, d = +0,33 |
| 6 | **Abținere cinstită.** Când telefonul nu e sigur, litera tremură și hotărăște adultul | AB §3.1; Y: Apraxia World, părinți îngăduitori, același progres |
| 7 | **Metodă cu lacăt, în doze de 4 minute:** ~48 de rostiri, blocuri de 45–75 s | AA §B2; Y §8b |
| 8 | **Adultul are un rol de 10 secunde:** un card înainte, degetul mare în lecție, o propoziție la final | M: „Nu știu dacă exersează corect”, 17 din 51; X §5 |
| 9 | **Te auzi reușind.** „Ascultă-te!” la copil, „Tu, fără ăăă” la adolescent. Fără monede și clasamente | X: recompensele anunțate, d = −0,40; R |
| 10 | **Nimic pierdut, nimic care împinge, nimic care pleacă.** Numerele doar cresc, Foni nu e niciodată trist, aplicația nu cere internet | X: seriile rupte duc la renunțare; ghidul UE 2025; N |

## 2. Cele trei cochilii

Cochilia ține de profil. La ON-0, „Un copil și un adult” face un profil de copil (4–10 ani): PATH, cu „Pentru adult” în colț. „Doar eu” deschide TEEN-HOME. Vârsta nu se verifică (N §3). Din ADULT-SETTINGS, un profil poate trece pe cealaltă cochilie fără să piardă progresul **[P]**. La copil, vârsta schimbă doar conținutul: la 4–6 ani totul e spus de Foni; la 7–10 ani apar texte scurte și jocurile de ritm.

| | Copil | Adult lângă copil | Adolescent, adult |
|---|---|---|---|
| Intrare | PATH, fără file | colțul din PATH, după `AdultGate` | TEEN-HOME, trei file |
| Cine vorbește | Foni, cu voce; textul e sprijin | text, fără Foni | text, fără Foni |
| Ton | „tu”, joacă, cel mult 8 cuvinte | „voi”, calm, cu cifre | „tu”, direct, fără diminutive |
| Litere (Allo) | `ROND` 80–100, groase, `ss01` | `ROND` ~50 | `ROND` 20–40 |
| `StickerText` | sunetul-țintă, reușita, onboarding | deloc | deloc; `LiveWord` fără contur |
| Ținte | ≥ 64 dp; acțiunea principală ≥ 72 dp | ≥ 48 dp | ≥ 48 dp |
| Numere | obiecte | cifre | cifre măsurate, fără scor |
| Telefonul nu e sigur | e întrebat adultul | răspunde el | e întrebat omul |

## 3. Harta navigării

```
Pornire
├─ niciun profil ────────► ON-0 → ON-1 → … → ON-7 → PATH   („Doar eu”: varianta scurtă → TEEN-HOME)
├─ un profil ────────────► PATH sau TEEN-HOME
└─ mai multe profiluri ──► foaia „Cine exersează?” → PATH sau TEEN-HOME

PATH
├─ ContinueButton / nodul curent → [ParentCard, prima dată] → LESSON → END → PATH
│                                                    END „Încă una?” → LESSON
├─ nod terminat → LESSON (reluare; nu schimbă treapta)
├─ nodul „Trezim gura” / butonul „gura” → MIRROR
├─ nod cu lacăt → AdultGate → confirmarea sunetului
├─ banda literelor → schimbă sunetul, pe loc
└─ „Pentru adult” → AdultGate → ADULT-HOME
        ├─ ADULT-SOUNDS · ADULT-CALIBRATE
        ├─ ADULT-REPORT → foaia de partajare a telefonului
        └─ ADULT-SETTINGS → „Cine exersează?” → ON-0 … (profil nou)

LESSON (Listen · Warmup · Hold · Syllables · Words · Pairs · Sentence · Twister · Retell · Mission)
├─ butonul „gura” / sfatul unei erori → MIRROR → același item
└─ X → foaia „Ne oprim?” → END scurt → PATH

TEEN-HOME (Azi · Exersează · Progres)
├─ Azi → MINUTE · NO-UM · pasul următor din DICTION
├─ Exersează → DICTION (→ MIRROR la încălzire) · NO-UM · MINUTE · „Sunete” → PATH simplu → LESSON → END
└─ Progres → „atunci și acum” · ADULT-SETTINGS
```

Nimic nu pornește singur: și amintirea zilnică deschide PATH, nu lecția. `AdultGate` apare doar dacă există un profil de copil și păzește zona adultului, lacătul, schimbarea profilului, partajarea și ștergerea.

## 4. Onboarding: ON-0 … ON-7

Ordinea: voce → reușită → date → amintire (N). ~3:25 în total.

| Ecran, timp | Pe ecran; ce fac copilul și adultul | Foni spune | Permisiune și rezervă | Iese când |
|---|---|---|---|---|
| **ON-0** 0:00–0:06 | Foni aterizează pe o literă. Două cartonașe cu desen: „Un copil și un adult” / „Doar eu” | `on0.hello` „Bună! Eu sunt Foni. Cine e lângă mine?” | — | atingere |
| **ON-1** până la 0:30 | Card pentru adult (trei rânduri, „Citește tot”), roata vârstei, nume opțional. **Wow:** fiecare literă tastată cade ca autocolant, sare și cântă o notă | `on1.wait` „Asta e pentru adult. Eu aștept.”; `on1.name` „Uite cum sare numele tău!” | — | „Începem” |
| **ON-2** până la 0:50 | Foni cu aripa la ureche, `MicCapsule`. Adultul spune primul „Bună!”, apoi copilul; Foni se zburlește după voce. Se măsoară zgomotul camerei | `on2.ask` „Vreau să te aud! Spune: Bună!”; `on2.heard` „Ura, te-am auzit!”; `on2.noise` „E cam gălăgie. Ne mutăm?” | **Microfonul.** Pentru adult: „Ascult doar în timpul exercițiilor. Nimic nu se trimite.” Refuz: modul „cu degetul”. Gălăgia nu blochează | o voce sau „Continuăm așa” |
| **ON-3** până la 1:05 | Un „A” uriaș și subțire. Copilul ține „aaa” ~1,5 s. **Wow:** litera crește cu vocea lui și face POP. „A” îi iese oricărui copil | `on3.inflate` „Umflă litera cu vocea: aaaa!”; `on3.pop` „Poc! Tu ai umflat-o!” | fără microfon: se umflă cât ține degetul | POP |
| **ON-4** până la 2:05 | Instantaneul vocii: 6 cuvinte cu poză, câte unul. Copilul repetă; adultul marchează în banda de jos „A ieșit” / „Încă nu” | `on4.intro` „Eu zic, tu zici!”; `on4.tried` „Bravo că ai încercat!” | fără microfon merge la fel | 6 cuvinte sau „Sărim peste” |
| **ON-5** până la 2:25 | Alfabetul viu: 9 litere; cele propuse pulsează și au o pană. **Copilul alege** (D8); litera se umflă și devine eroul. Adultul poate alege alta | `on5.propose` „Eu aș începe cu literele cu pană. Tu alegi!”; `on5.free` „Alege o literă de joacă.”; `on5.hero.<sunet>` | — | o literă |
| **ON-6** până la 3:00 | „Vreau să mă văd” / „Mai târziu”. `MirrorView` cu `LipTargetOverlay` pe primul gest vizibil al sunetului; forma ținută o secundă, litera zboară pe Drum | `on6.ask` „Vrei să te vezi în oglinda fermecată?”; `on6.win` „Prima ta literă e pe drum!” | **Camera**, doar la cerere. Pentru adult: „Camera e ca o oglindă. Nu înregistrează nimic.” Refuz: litera zboară oricum | forma, „Am făcut” sau „Mai târziu” |
| **ON-7** până la 3:25 | Pentru adult: „Când exersați de obicei?”, apoi „După cină, cu Foni. Să vă amintesc la 19:30?” | `on7.wait` „Încă puțin pentru adult. Apoi pornim!” | **Notificările** (dialog de la Android 13), doar la „Da”. Refuz: planul rămâne scris în ADULT-HOME | alegere → PATH |

**Cardul din ON-1:** „Vocea și imaginea copilului nu pleacă de pe telefon. Fără cont. E exersare prin joacă și nu ține locul logopedului. Stați alături, patru minute pe zi.”

**Se salvează, doar local:** felul profilului, vârsta, numele (în exporturi, `[nume]`), stările permisiunilor, răspunsurile adultului, ce a bănuit telefonul („în probă”), sunetul ales, momentul amintirii și evenimentele de parcurs din N §6 (`first_utterance_detected`, `first_win` etc.).

**Instantaneul (ON-4).**
- Câte un cuvânt pentru S, Ș, Z, J, R și L, fiecare cu un singur sunet greu **[P: supă, șapcă, zână, pijama, rac, lună; lista finală în capitolul 06]**. Africatele nu sunt întrebate: se sprijină pe S, Ș și J (AA §C) și rămân în alfabet.
- Nu dă scor. Sunt propuse cel mult 3 litere, cele cu „Încă nu”, în ordinea sunetelor, doar din răspunsurile adultului. Se poate reface din ADULT-SOUNDS.

**Modul „cu degetul”** (microfon refuzat): capsula devine butonul „Am zis”, litera reacționează la apăsare și judecă adultul. Cererea se repetă o singură dată, la prima lecție.

**Varianta „Doar eu”:** ON-1 „Ce vrei să-ți iasă mai bine?” (vorbit în public / dicție / un sunet); ON-2 o propoziție citită cu voce tare; ON-3 „Cât ții un S pe un aer?”; ON-4 „Prezintă-te în 30 de secunde”; ON-5 doar la „un sunet”; ON-6 aceleași 30 de secunde auzite fără „ăăă”; ON-7 la singular.

**Etape:** M1 = ON-0–3 și ON-5 fără propunere; M2 = ON-4, ON-6; M3 = ON-7, „Doar eu”.

## 5. PATH (Drumul)

De sus în jos:
1. **Bara:** zilele („7 zile”) și inelul săptămânii; în dreapta „Pentru adult” (48 dp).
2. **Banda literelor:** toate cele 9 sunete ca `LiveLetter`. Litera crește cu stăpânirea **[P: `wght` 200 → 900, `ROND` 20 → 100 între treapta 1 și 14]**; sunetul terminat are o bifă-autocolant; litera aleasă e singura cu contur.
3. **Drumul:** lumea pictată a sunetului și 14 `PathNode`, cu nodul curent la mijloc. Înaintea lor, semnul de start, bifat din onboarding (N). Nod făcut (plin, cu bifă), curent (mare), următor (contur), cu lacăt. Foni stă lângă nodul curent (`path.today.<treaptă>`).
4. **`ContinueButton`:** unul singur, la nivelul degetului mare, nu lipit de marginea de jos (O §3); singurul lucru plin colorat.

| # | `Rung` | Nodul, pentru copil | Pasul; mini-jocul | Judecă |
|---|---|---|---|---|
| 1 | `listen` | Urechi de detectiv | `Listen` | telefonul (atingerea) |
| 2 | `warmup` | Trezim gura | `Warmup`; oglinda | camera, microfonul sau adultul |
| 3 | `isolated` | Sunetul singur | `Hold`; Umflă litera | telefonul și adultul; **lacăt** |
| 4–5 | `syllables`, `back` | Trenul; Trenul pe dos | `Syllables`; Trenul de silabe, Toba literelor | telefonul |
| 6–9 | `wordsInitial`, `wordsMedial`, `wordsFinal`, `clusters` | La început; La mijloc; La capăt; Vecini lipiți | `Words`; Autocolantul fuge din cuvânt, Ecoul din peșteră | telefonul și adultul |
| 10 | `pairs` | Perechi | `Pairs`; Ariciul și norul | amândoi |
| 11 | `sentences` | Propoziții | `Sentence` | telefonul și adultul |
| 12 | `twister` | Melcul și iepurașul | `Twister` | adultul |
| 13 | `retell` | Povestea | `Retell` | adultul |
| 14 | `everyday` | Misiunea | `Mission` | adultul |

**Lacătul.** După `isolated`, restul drumului stă în ceață. `ContinueButton` devine „Cheamă un adult” (`path.adult`). După `AdultGate`, adultul ascultă cea mai bună încercare, copilul spune sunetul de trei ori, iar adultul alege „Da, sună bine”, „Încă nu” sau „Întreb logopedul”.

**Zilele (D13).** Totalul zilelor exersate doar crește; o zi contează de la o rundă (~90 s, X). „3 la rând” apare doar cât seria e întreagă; când se rupe, rândul lipsește, fără mesaj **[P]**. Inelul arată ținta săptămânii (implicit 3 zile).

**După o pauză.** Nimic nu a scăzut: `path.back` „Bine ai venit! Începem cu ceva ușor.” După 4 zile, prima lecție e mai ușoară (X §8.5).

| Starea | Ce se vede | `ContinueButton` |
|---|---|---|
| Prima zi | start bifat, Foni la primul nod | „Continuă”, pulsează o dată |
| Zi obișnuită, după pauză | nodul curent la mijloc | „Continuă” |
| Gata pe azi | `path.rest` „Gura se odihnește. Pe mâine!” | buton cu contur, „Mai exersez” |
| Lacăt | lacătul, restul în ceață | „Cheamă un adult” |
| Sunet terminat | litera plină, cu bifă | „Alege altă literă” |
| Niciun sunet | banda literelor mare: „Alege o literă” | ascuns |

## 6. LESSON (Lecția)

**Ecranul,** de sus în jos: X, firul lecției (5 puncte) și butonul „gura”; Foni mic, cu replica scrisă; scena (`LiveLetter`, `LiveWord` sau poza); `MicCapsule` („Ascultă”, „Acum tu”, „Mă gândesc”); banda adultului („A ieșit” / „Încă nu”), mică, deasupra unei zone moarte de 24 dp. Lecția trece singură la itemul următor.

### 6.1 Șablonul de 4 minute (Y §8b)

| Faza | Timp | Ce se întâmplă | Rostiri |
|---|---|---|---|
| Deschidere | 0:00–0:15 | capitolul zilei din povestea eroului | 0 |
| Pornire ușoară | 0:15–0:45 | ceva ce iese sigur: înainte de lacăt o vocală ținută, apoi sunetul ținut cu prag mic sau cuvinte reușite | ~5 |
| Pauză | 5 s | litera se lipește pe drum; respiri cu litera | — |
| Blocul 1 | 0:50–1:45 | pasul treptei dinainte; copilul alege o dată între două poze | ~15 |
| Mișcare | 1:45–2:00 | „Zboară ca Foni!”; gura se odihnește | — |
| Blocul 2 | 2:00–2:50 | pasul treptei curente | ~10 |
| Pauză | 5 s | autocolantele de azi se așază pe nod | — |
| Blocul 3 | 2:55–3:40 | treapta curentă amestecată, cu un item-surpriză; pe treptele târzii, contrast | ~12 |
| Încheiere | 3:40–4:00 | ultimul exercițiu care a ieșit bine azi; apoi END | ~5 |

Blocurile, după treaptă **[P pentru F2]**: `listen`, `warmup` → `Listen` · `Warmup` · `Listen`; `isolated` → `Warmup` · `Hold` · `Hold`; `syllables` … `clusters` → treapta dinainte · curentă · curentă amestecată; `pairs` → `Words` · `Pairs` ascultat · `Pairs` spus; ultimele patru → `Words` · pasul treptei de două ori.

Dacă atenția scade (două din cele trei semne din Y §8e în același bloc), blocul se scurtează și urmează Mișcarea sau Încheierea.

### 6.2 Ciclul unei încercări

| Starea | Pe ecran | Sunet, vibrație | Trece mai departe |
|---|---|---|---|
| Model („Ascultă”) | Foni spune; litera face mișcarea-model | vocea lui Foni | la sfârșitul clipului. De la `pairs`, doar la cerere sau după două nereușite |
| Rândul copilului („Acum tu”) | litera răspunde vocii în sub 80 ms | **nimic** | sfârșitul vorbirii + 150 ms, sau durata maximă |
| Judecată („Mă gândesc”) | litera îngheață în cel mult 100 ms | nimic | verdict; după 1,5 s decide măsurătoarea rapidă; peste max(20 s, durata + 10 s), `Retake` |
| Verdict | `VerdictStamp` și animația literei | efect sub 0,6 s; vibrație doar la `Correct`; Foni cel mult 1,5 s | 0,8–1,5 s |
| Fereastra adultului | banda se luminează | nimic | 2 s, fără să blocheze; la `Unsure`, 6 s |

**Tăcerea:** la 4 s capsula pulsează; la 8 s microfonul se închide și Foni repetă modelul; la a doua tăcere apar „Mai ascult” și „Sărim”. Un item sărit nu se numără.

### 6.3 Pașii

Durate maxime **[P]**; valorile finale în `spec/thresholds`.

| Pasul | Pe ecran | Litera, cât ascultă microfonul → la rostirea bună | Max |
|---|---|---|---|
| `Listen` | Foni spune un cuvânt bine sau pe dos („sanie” / „tanie”); două poze | fără microfon → poza bună primește chenar și bifă, cealaltă se estompează; litera arată ce a spus Foni | 10 s |
| `Warmup` | `MirrorView`, `LipTargetOverlay`, `Mouth3D` (§8); 2–3 mișcări | vibrează la „brrr”, sare la plescăit, se umflă la suflat → conturul se închide cu bifă | 10 s; „Am făcut” mereu |
| `Hold` | **Umflă litera.** La Ț, CE/CI, GE/GI, R: rafale („ț-ț-ț”) | crește cu durata și urmărește sunetul (se ascute spre S, se rotunjește spre Ș, unda vocii la Z și J); la rafale, câte o literă mică la rând → POP la prag. Pragul se mută nevăzut (1,5–3 s), pentru 80–85% reușită | ținta + 2 s |
| `Syllables` | **Trenul de silabe**; la africate, vagoanele „t” și „s” se apropie până se lipesc; de la 7 ani, **Toba literelor** | fiecare silabă face un vagon să sară → trei la rând dau un vagon auriu; o ratare doar oprește creșterea | 6 s |
| `Words` | **Autocolantul fuge din cuvânt**: poză și `LiveWord`; târziu, **Ecoul din peșteră** | valul vocii trece prin litere → autocolantul se dezlipește și zboară pe nod | 4 s |
| `Pairs` | **Ariciul și norul**: două personaje; asculți și atingi, apoi spui | litera alunecă între cele două forme → personajul cerut o primește | 4 s |
| `Sentence` | 3–5 cuvinte, cu sunetele-țintă ca autocolante | cuvintele se îngroașă pe rând → autocolantele reușite sar; cel nereușit cere doar cuvântul lui | 8 s |
| `Twister` | o frământare, „ca melcul”, apoi „ca iepurașul”; fără cronometru | silabele se umplu în ritmul copilului → hotărăște adultul (clar de două ori la rând) | 15 s |
| `Retell` | trei poze; copilul spune povestea | litera-erou merge de la o poză la alta → hotărăște adultul | 45 s |
| `Mission` | cartonașul misiunii („La cină, cere sarea!”) | ca la `Sentence` → cartonașul zboară la „Pentru adult”; a doua zi adultul e întrebat | 8 s |

### 6.4 Verdictele

| `Verdict` | Litera și `VerdictStamp` | Foni | Adultul; ce urmează |
|---|---|---|---|
| `Correct` | animația bună; „Bravo!” cu bifă | laudă pentru ce a făcut (`v.correct.*`: „Ai ținut ș-ul lung!”) | poate marca; itemul următor |
| `Almost` | plină pe trei sferturi, oblică, se leagănă; „Aproape!” cu jumătate de cerc | `v.almost` „Aproape! Încă o dată.” | modelul și același item, cel mult 2 reluări |
| `NamedError(kind)` | animația-instrucțiune (§6.5); ștampila e chiar instrucțiunea, cu săgeată | o spune (`v.err.<kind>.<sunet>`) | vede fraza pentru adult. `Mouth3D` mic arată gestul (atingere → MIRROR). Aceeași instrucțiune cel mult de două ori, apoi un item mai ușor |
| `Unsure` | **tremură**; fără ștampilă | `v.unsure` „Hai să mai încercăm.” | banda urcă, cu ce a bănuit telefonul („Poate S spre Ș.”). „A ieșit”: animația bună, iar încercarea devine reper de calibrare. „Încă nu” sau 6 s fără răspuns: același item |
| `Retake` | stă pe loc, Foni cu aripa la ureche; „Mai spune o dată” cu săgeată rotundă | `v.retake` | vede cauza („mai aproape”, „e gălăgie”); nu se numără |

Un tip nedeblocat nu ajunge la copil: el primește `Almost` sau `Unsure`, iar adultul vede bănuiala „în probă”.

### 6.5 Animația „când nu iese” (D9, F4)

Cel mult 1,2 s, apoi fraza lui Foni și modelul. O singură gramatică (AB): tremurul = „nu sunt sigur”; unda = vocea; virgula = locul limbii; rotunjimea = buzele; puful = aerul; piciorul care bate = vârful limbii.

| `ErrorKind` | Ce are de făcut copilul (exemplu) | Ce face litera | Val |
|---|---|---|---|
| `Omission` | „Nu uita șarpele de la început!” | locul gol strălucește; litera cade în el | 1 |
| `Stopping` | „Lasă aerul să curgă lung: sss.” | litera scurtă se întinde ca o panglică | 1 |
| `Devoicing` | „Pune vocea: bâzâie ca albina!” | o undă rapidă pornește pe linia de jos | 1 |
| `PlaceSwap` | S: „Zâmbește și ține limba în față.” Ș: „Fă buzele pâlnie, limba înapoi.” | virgula sare și litera se ascute, sau virgula se prinde și litera se rotunjește | 1 |
| `RtoL` | „Lasă vârful limbii să bată sus.” | litera primește un picior care bate o dată | 1 |
| `Deaffrication` | „Atinge dinții o clipă, apoi ț!” | un T mic (D la GE) se lipește în față | 2 |
| `AffricatePlace` | „Zâmbește, limba în față: ț.” | ca la `PlaceSwap`, pe coada literei | 2 |
| `RtoGlide` | „Ridică vârful limbii după dinți.” | I-ul crește un picior și devine R | 2 |
| `RUvular` | „Vârful limbii lucrează, nu gâtul.” | motorașul literei se mută din spate în vârf | 2 |
| `LtoGlide` | „Lipește vârful limbii sus, după dinți.” | I-ul se îndoaie în L; la U, litera își pierde rotunjimea | 2 |
| `Lateral` | „Suflă aerul pe mijloc, ca prin pai.” | două pufuri laterale se adună la mijloc | 3 |
| `Interdental` | „Ține limba în spatele dinților.” | o limbuță iese și alunecă după o linie de dinți; doar cu confirmarea camerei | 3 |
| `RtoVowel` | „Vârful limbii sus: rrr!” | vocala se strânge într-un R care bate | * |
| `LtoR` | „Ține limba lipită, nu o bate.” | piciorul se oprește și se lipește | * |
| `WeakOrShort` | „Încă o dată, mai tare!” | litera subțire se îngroașă cu vocea | * |

\* Valul îl stabilește capitolul 03. La `PlaceSwap`, `AffricatePlace` și `LtoGlide` sfatul trimite și la oglindă. La `Lateral` și `RUvular`, adultul vede „de arătat logopedului”. S dentalizat sau pe nas nu ajunge niciodată la copil (AB).

### 6.6 Reguli

- **Liniștea:** de la „Acum tu” până la sfârșitul vorbirii + 150 ms nu pornește niciun sunet și nicio vibrație.
- **Trei nesigure la rând:** un exercițiu mai ușor, iar la END adultul află ce a observat telefonul (AB §3.1). **Două nereușite** la același item: unul mai ușor (AA §E).
- **Degetul adultului** nu blochează niciodată. Banda se luminează singură la `Unsure`, la primele 6 încercări ale unei trepte noi și la lacăt. Răspunsul hrănește calibrarea și raportul.
- **Ieșirea:** X → „Mai stau” / „Gata”, fără niciun text despre pierdere. La un apel, microfonul se închide; la întoarcere, „Continuăm?”.

## 7. END (Finalul)

Sub 10 secunde, fără nimic în buclă.
1. **Reușita:** cea mai mare literă de azi se lipește pe nod, Foni sare la nodul următor: `end.done` „Ai exersat până la capăt!”. Singurul număr: „Ai încercat de 38 de ori.”
2. **„Ascultă-te!”:** cea mai bună încercare a lecției, apoi sunetul eroului (X §8.3). Lipsește dacă nu există înregistrare.
3. **Propoziția pentru adult,** una: câte încercări, un lucru măsurat, o laudă gata de spus. „Azi: 38 de încercări la Ș; cel mai lung ș, 3 secunde. Spune-i: «Ai încercat până a ieșit.»”
4. **Butoane:** „Gata” (plin) și „Încă una?” (contur; D12), cel mult o dată pe zi **[P]**. `end.bye` „Mi-a plăcut să exersăm împreună. Pa-pa!”

Finalul scurt, după o ieșire devreme cu cel puțin o rundă: „Ai exersat puțin azi. Pe mâine!”; ziua contează.

## 8. MIRROR (Oglinda)

**Intrări:** nodul „Trezim gura” și pasul `Warmup` (oglinda stă în lecție); butonul „gura” din PATH (oglinda liberă a sunetului); butonul „gura” sau sfatul unei erori din LESSON (întoarcere la același item; încercarea întreruptă nu se numără).

**Ecranul.** Sus (~60%), `MirrorView` cu `LipTargetOverlay`: conturul formei-țintă, potrivit pe gura copilului în repaus (măsurată o secundă la intrare, doar în memorie). La potrivire, conturul se închide cu bifă și un inel se umple cât e ținută forma (1–1,5 s). Jos, `Mouth3D` în aceeași poză: din față sau „pe dinăuntru”, rotit cu degetul. Butoane: „Am făcut” (mereu), „Încet”, X.

**Ce judecă.** Sunetul îl judecă microfonul; camera verifică doar gestul vizibil (Q). Limba din gură nu e urmărită: la pașii cu limba, poza se vede doar pe `Mouth3D`.

| Încălzirea (AA §D), verificată de | Mișcări |
|---|---|
| cameră | pupicul, zâmbetul de crocodil, hipopotamul; cu limba afară (H4): cățelușul, ceasul, cameleonul |
| microfon | motorașul buzelor, căluțul, gâtul care bâzâie, șarpele lung, morișca |
| „Am făcut” | limba urcă pe deal, periuța cu limba |

**Reluarea cu încetinitorul:** ultimele 2–3 secunde ale gurii copilului, la jumătate de viteză, lângă gura-model. Nu dă verdict (Q pct. 9).

**Nu se păstrează niciodată:** cadrele, punctele feței, reluarea (în memorie cel mult 3 s). Pe disc ajung doar evenimente: ce mișcare, cât a fost ținută.

| Starea | Ce se vede |
|---|---|
| Fără permisiune | `Mouth3D` pe tot ecranul și „Vreau să mă văd” |
| Fața lipsește peste 1 s, cap întors, prea departe | oval punctat; „Nu te văd. Vino mai aproape!”; numărătoarea stă pe loc |
| Lumină slabă | „E cam întuneric. Aprindeți o lumină.” |
| Două fețe | e urmărită cea mai mare; la salturi, „Doar copilul în oglindă” |
| Imagine sacadată | ghidajul se ascunde; rămân `Mouth3D` și „Am făcut” |

## 9. „Pentru adult”

**`AdultGate`.** „Scrie cu cifre:” și un număr în cuvinte, 21–99, niciodată rotund. La greșeală apare alt număr. Se închide după 180 s în fundal, la repornire și la „Șterge tot”. Când un copil atinge colțul, Foni spune „Cheamă un adult!”.

**ADULT-HOME.**
- „Cum merge”, într-o propoziție: „Săptămâna asta ați exersat în 3 zile: 112 încercări. La Ș ați ajuns la cuvinte.” Fără zile: „N-ați exersat încă. Două minute ajung pentru azi.”
- Un grafic: încercările pe zi, 14 zile; zilele goale rămân goale. Alături, inelul săptămânii.
- Un singur anunț odată (lacătul, misiunea de ieri, „De arătat logopedului”) și „Atunci și acum”: două înregistrări ale aceluiași sunet (F8).
- Rânduri: Sunete și trepte · Raport pentru logoped (al doilea, O §4) · Învață telefonul vocea lui · Ce faci la fiecare activitate (cardurile și cele 10 sfaturi din AA §E) · Setări.

**ADULT-SOUNDS.** Pe fiecare sunet: litera, „treapta 6 din 14”, etapa (Pregătire, Emitere, Consolidare, Diferențiere, Automatizare), încercările săptămânii, calibrarea. În detaliu:
- **Schimbă treapta,** în sus sau în jos (singurul loc din care coboară). Peste lacăt: „Ascultă și confirmă” sau „Logopedul a confirmat sunetul”.
- **Ce a bănuit telefonul,** pe tip de eroare, în numere: „S spre Ș: de 6 ori din 40. În probă: copilului nu i se spune.” ori „Deblocat: copilul primește sfatul…”. Dedesubt: „Telefonul poate greși. E o observație, nu o evaluare.”
- Comutatorul „Foni dă sfaturi la acest sunet” **[P]**; scoate din drum sau adaugă; refă instantaneul.

**ADULT-CALIBRATE.** De ce: „Fiecare copil sună altfel. Când îi arătați telefonului cum sună sunetul lui bun, telefonul îl compară cu el însuși, nu cu alți copii.” (AB pct. 5). Cum: pe fiecare sunet, starea („7 din 10 încercări bune”) și „Calibrează acum (un minut)”: copilul spune sunetul de 6–10 ori, iar adultul marchează fiecare rostire. Se păstrează măsurătorile, nu sunetul; aici ajung și degetele mari din lecții. Numărul de repere: capitolul 03 (E4).

**ADULT-REPORT.** Previzualizare, perioadă (implicit 4 săptămâni, cel mult 12), „Notițe pentru logoped”, „Trimite sau salvează”. Estompat până la prima zi exersată.

| Pagina | Conținut |
|---|---|
| 1. Pe scurt | perioada, „Profil”, vârsta, fără nume; „Exersare acasă, între ședințe; nu e o evaluare.”; zile, lecții, minute, încercări; săptămânile față de țintă; pe sunet: treapta, etapa, încercările, calibrarea |
| 2. Pe sunete | rândul „Telefonul” (bine, aproape, încă nu, fără răspuns) și rândul „Adultul”; „au spus același lucru la N din M”; ce a bănuit telefonul, pe tip, cu „în probă” sau „deblocat”; „de arătat logopedului” |
| 3. Notițe | notițele adultului (700 de caractere), misiunile, un chenar gol „Ținta pentru săptămâna viitoare” pentru logoped (X §8.4), „Cum se citește”, versiunile din `spec/` |

A4, cel mult 3 pagini, doar numere, niciun procent, fără sunet. **Fără internet:** PDF-ul se face pe telefon și pleacă prin foaia de partajare a Androidului, „Salvează în telefon” sau „Tipărește”; trimiterea o face aplicația aleasă de adult (F1).

**ADULT-SETTINGS** (explicația stă sub fiecare comutator):

| Rând | Ce face |
|---|---|
| Cine exersează | profiluri, vârstă, cochilie |
| Amintirea | o notificare pe zi, pentru adult: „E momentul pentru câteva minute cu Foni.” După 3 ignorate, una pe săptămână (X §8.5). Niciodată seara târziu |
| Ținta săptămânii | 2–7 zile, implicit 3 |
| Zilele la rând; sunete; vibrații; ghidaje pe față | comutatoare |
| Înregistrările | păstrarea (F8) și ștergerea lor |
| Ce păstrăm pe telefon | textul întreg de confidențialitate |
| Șterge tot | cere iar numărul porții; golește tot, anulează amintirea, duce la ON-0 |
| Licențe; Despre metodă | Allo (OFL 1.1) și restul; creditul din AA §F, fără „metoda” sau „aprobat de”; „Vocea lui Foni e generată pe calculator.” |

**`ParentCard`.** Apare prima dată pe fel de activitate, înaintea lecției; „Am citit” o pornește. Nu apare la „doar eu”. Textele finale: I8.

| Felul | Ținta | O încercare bună | Dacă nu iese |
|---|---|---|---|
| Sunetul singur | sunetul curat, ținut 2–3 s | litera se umflă până la capăt | arătați-i gura voastră; după două încercări, altceva |
| Silabele | sunetul lipit de o vocală | rămâne la fel de curat | despărțiți „s… a”, apoi apropiați |
| Cuvintele | sunetul la început, la mijloc, la capăt | sunetul-țintă e curat | spuneți voi cuvântul, rar: „Da, o sanie!” |
| Ascultarea | copilul aude diferența | arată poza fără să se uite la voi | spuneți cuvintele mult mai diferit |
| Propoziții și povești | sunetul rămâne bun fără să se gândească la el | curat în cele mai multe cuvinte | propoziții mai scurte; nu-l opriți din poveste |
| Oglinda | buzele și falca fac forma | forma ținută o secundă | „Am făcut”; camera poate greși |

## 10. Adolescent și adult

**TEEN-HOME.** *Azi:* un singur lucru plin colorat (pasul următor), cartonașul NO-UM și zilele. *Exersează:* DICTION, NO-UM, MINUTE, „Sunete”. *Progres:* numere care doar cresc, „atunci și acum”, umpluturile față de obișnuitul tău, setările. „Sunete” folosește același PATH și aceeași LESSON, în stil simplu; lacătul se deschide când omul își ascultă încercarea lângă exemplu și confirmă singur **[P]**.

**DICTION** (ordinea din AA §B3; modulele negata arată „Urmează”):

| # | Modulul | Exerciții | Măsoară | v1 |
|---|---|---|---|---|
| 1 | Postură și respirație | „Câte numere pe un aer?”; S pe un expir | durata, numărul | da |
| 2 | Vocea | afirmație ↔ întrebare; crescendo | tonul, tăria | un exercițiu, dacă rămâne timp |
| 3 | Încălzirea | gimnastica gurii, în MIRROR | camera, microfonul | da |
| 4 | Sunete pe poziții | grila de silabe; „Finaluri clare” | detectoarele | M6 |
| 5 | Frământări pe viteză | lent, mediu, alert | silabe pe secundă | M6 |
| 6 | Citit cu voce tare | pauzele pe punctuație | pauze, intonație | M6 |
| 7 | Prezentarea | MINUTE și NO-UM | umpluturi, ritm, pauze | da |

**NO-UM** („Tu, fără ăăă”):

| Starea | Pe ecran | Mai departe |
|---|---|---|
| 1. Pregătire | subiect sau „Liber”; „Vorbește până la 60 de secunde. Totul rămâne pe telefon.” | „Începe” |
| 2. Prima rostire | valul vocii prin litere, un inel al timpului; niciun contor | „Gata”, 60 s, sau 3 s de tăcere |
| 3. Transcriptul | după „Mă gândesc”: textul cu `LiveWord`. Umpluturile și pornirile false rămân la loc, dar subțiri, tăiate cu o linie; pauzele lungi sunt goluri care se strâng. Sus: „3 umpluturi · 1 pornire falsă · 1 pauză de 2,1 s”. O tăietură nesigură e punctată și se anulează cu o atingere | „Ascultă-te fără ăăă” |
| 4. Redarea curată | aceeași voce, tăiată (treceri de 10–20 ms; pauzele de peste 1,2 s aduse la ~0,6 s); comutator „cum a fost” / „fără ăăă” | „Acum spune-o tu așa” |
| 5. A doua rostire | ca la 2 | la fel |
| 6. Rezultatul | „Umpluturi: 3 → 0” și un lucru măsurat. Neschimbat: „3 → 3. Încearcă o pauză în locul lui «ăă».” | „Încă o dată” / „Gata” |

Sub 10 secunde: „Mai spune puțin.” Tăieturile se arată prin formă, nu prin roșu. Rostirile dispar la ieșire dacă omul nu apasă „Păstrează”. Nicio voce sintetizată (R §4).

**MINUTE** („Un minut pe zi”). Scara de 7 subiecte, de la personal la public, cu „Pasul 3 din 7” și „Un pas înapoi”. După rostire: cum ți s-a părut ție; transcriptul prozodic; un lucru măsurat care a mers; unul de încercat; „Încă o dată, mai scurt”. Fără scor. În transcript (`LiveWord`, S nr. 3): **grosimea = tăria, linia de bază = tonul, golul = pauza**, iar umplutura e un cuvânt subțire, punctat. Cititorul de ecran primește textul și un rezumat („58 de secunde, 6 pauze”).

## 11. Texte și accesibilitate

| | Copil | Adult lângă copil | Adolescent |
|---|---|---|---|
| Cum | Foni, „tu”, cel mult 8 cuvinte, un singur lucru de făcut | „voi”, propoziții întregi, cifre | „tu”, scurt |
| Lauda | ce a făcut: „Ai ținut ș-ul lung!” | gata de spus: „Spune-i: …” | un lucru măsurat |
| Când nu iese | instrucțiune sau „Hai să mai încercăm” | ce a bănuit telefonul, cu „poate” | o întrebare pentru el |

**Cuvinte interzise** (verificare automată, A2 și J5): diagnostic, depistare, screening, evaluare, terapie, tratament, tratează, corectează, clinic, risc, severitate, tulburare, scor, notă, procent, test. Pentru copil, în plus: greșit, greșeală, rău, „ai pierdut”, „grăbește-te”, orice emoție a lui Foni sau dedusă despre copil. În loc: „exersare între ședințe”, „ce a auzit telefonul”, „în probă”, „de arătat logopedului”, „încă nu”.

**Pluralul** (`Plural`, din cod): „1 zi”, „3 zile”, „20 de zile”; „de” când ultimele două cifre sunt 00 sau cel puțin 20, niciodată la zero; „1.000 de încercări”; „O dată din N”, „De N ori din N”. De adăugat: umplutură, pauză, silabă, secundă, lecție. Niciun număr lipit de un substantiv fără `Plural`.

**Numerele, pentru copii:** niciodată procente, fracții, cronometre. Cantitățile sunt obiecte (vagoane, autocolante). Un singur număr scris, la END. Foni nu rostește numere și nume (vocea e generată dinainte, D10).

**Accesibilitate:**
- **TalkBack:** `LiveLetter` dă textul și starea („Ș, pe jumătate umflat”); verdictul se anunță o dată; pozele poartă numele cuvântului; firul lecției se citește în cuvinte („Peste jumătate”).
- **Nimic doar prin culoare,** grosime sau mișcare: orice stare are și formă, iconiță sau cuvânt.
- **Mișcare redusă:** estompări în loc de alunecări; umflarea în trei trepte; tremurul devine contur punctat cu semnul întrebării; instrucțiunea devine două cadre fixe cu săgeată.
- **Text mărit:** ecranele adultului și ale adolescentului țin până la 200%; verificat la 1,0, 1,3 și 2,0.
- **Ținte:** copil ≥ 64 dp, adult ≥ 48 dp, 12 dp între ele. Contrast ≥ 4,5:1.
- **Subtitrări:** fiecare replică a lui Foni apare și scrisă.

## 12. Pachetele G1–G12

„Gata” include regula din documentul-mamă §8 și verificările din §11.

| Pachet | Acoperă | „Gata”, în plus (testabil) | Depinde de | Etapa |
|---|---|---|---|---|
| **G1** Cochilia și navigarea | toate rutele, „Cine exersează?”, `AdultGate`, filele (§2–3) | fiecare ecran se deschide pe ruta lui și „înapoi” duce unde spune harta; după oprirea procesului, revine pe același ecran; poarta se închide la 180 s | A1, D1, D2, F4 | M0; M1 copil; M3 restul |
| **G2** Onboarding | ON-0 … ON-7 (§4) | pe instalare curată, fluxul ajunge în PATH; nicio permisiune înainte de ON-2; orice refuz duce la capăt; în probe, prima voce sub 60 s la 4 familii din 5 | G1, C3, D3, I5, E1, E8, H3, F5 | M1 scurt; M2; M3 |
| **G3** Drumul | PATH (§5) | capturi pentru cele 6 stări; lacătul nu se deschide fără poartă; după 10 zile de pauză simulată, niciun text despre pauză | G1, C3, D2, F3, F5, I1 | M1 (S, Ș); M2 |
| **G4** Lecția | LESSON (§6.1–6.3, 6.6) | o lecție întreagă în „fixture” pentru fiecare pas; niciun sunet și nicio vibrație cât e deschis microfonul (test); durată 3:30–4:30; modul „cu degetul” merge | F1, F2, C5, E1, E5, E8, D4, I2–I5 | M1 (`Hold`, `Syllables`, `Words`); M2 |
| **G5** Verdictul | §6.4–6.5 | captură pentru fiecare `Verdict` și `ErrorKind`, cu și fără mișcare redusă; un tip nedeblocat nu dă text copilului (test pe `spec/unlock.json`) | E5, E7, C4, D2, H7 | M1 (`PlaceSwap`); M2 (valul 1); M6 |
| **G6** Finalul | END (§7) | „Ascultă-te!” redă cea mai bună încercare; propoziția trece `Plural` la 0, 1, 2, 19, 20, 21, 101; „Încă una?” cel mult o dată pe zi | F1, F5, `:core:data`, I5 | M1 |
| **G7** Zona adultului | ADULT-HOME, ADULT-SOUNDS, ADULT-CALIBRATE, ADULT-SETTINGS | „Cum merge” e corectă pe 6 seturi de date; treapta coboară doar de aici; după calibrare, încercările poartă versiunea ei | G1, F3, F4, F5, E4 | M3 |
| **G8** Cardurile | `ParentCard`, cele 10 sfaturi | apare o dată pe fel de activitate și profil; nu apare la „doar eu” | G4, I8 | M3 (`isolated`: M2) |
| **G9** Raportul, exportul, ștergerea | ADULT-REPORT, „Șterge tot” | PDF de cel mult 3 pagini, fără „%” și fără nume în textul extras; pleacă în mod avion; după „Șterge tot” nu rămâne niciun fișier | G7, F4, J5 | M3 |
| **G10** Adolescenți și dicție | TEEN-HOME, DICTION | „doar eu” nu vede Foni, autocolante sau cuvântul „adult”; modulele 1, 3, 7 merg cap-coadă | G1, C3, I6, H4 | M3; M6 |
| **G11** „Tu, fără ăăă” | NO-UM | pe 50 de clipuri românești, fiecare tăietură afișată se aude tăiată, fără pocnet; redarea pornește în 5 s; merge în mod avion | `:engine:judge`, `:platform:audio`, E9, C3 | M3 |
| **G12** „Un minut pe zi” | MINUTE | transcriptul unui clip de test arată tăria, tonul, pauzele și umpluturile la locul lor | G11, C3, `:engine:dsp` | M3 dacă G11 e gata marți la prânz; altfel M6 |

## 13. Propuneri de schimbare și întrebări deschise

**Propuneri de schimbare** (neaplicate aici):
1. **Nume care lipsesc:** foaia „Cine exersează?” (`WHO`), banda literelor (`AlphabetBand`), banda adultului (`AdultStrip`), firul lecției (`LessonTrail`).
2. **`Hold`** acoperă și sunetul ținut, și rafalele: un mod în specificație sau un pas `Burst`.
3. **Seria (D13):** după semifinală, doar totalul și ținta săptămânii (X). Ghidul UE din 2025 cere seriile oprite implicit la minori: comutatorul să pornească oprit.
4. **Albumul de autocolante** (Y) nu are ecran; `ALBUM` la M6.
5. **MIRROR** (§8) se construiește în H3–H5 și nu are pachet în grupul G: H3 să numească G1, G2 și G4 drept consumatori.
7. **Lecția fără microfon:** reducerul (F1) trebuie să primească încercări judecate doar de adult.
8. **Conținut:** în `sounds.json` treapta e `meet`, nu `listen`, iar `twister` nu are capitol (I1).

**Întrebări deschise:**
1. Cele 6 cuvinte ale instantaneului, fără africate: confirmă logopedul? Sau 9 cuvinte, 90 de secunde?
2. Ținta săptămânii: 3 zile (X) sau 5 (logopezii)?
3. La `Lateral` și `RUvular`: doar „de arătat logopedului” sau și oprim urcarea?
4. Foni cel nou poate face „aripa la ureche”? De gest depind ON-2 și `Retake`.
5. Cine înregistrează cele 50 de clipuri cu umpluturi pentru G11?
6. „Încă una?” o dată pe zi și lacătul cu autoconfirmare la „doar eu”: de acord?
