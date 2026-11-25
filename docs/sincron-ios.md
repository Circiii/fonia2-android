# Sincronizare cu iOS (24 noiembrie 2026)

Aplicația livrată (`:legacy:app` → `:legacy:features`) a fost adusă la starea de lansare a versiunii iOS. Munca nouă a colegului (antrenamentul de dicție, verificarea rapidă, oglinda nouă, gura 3D) a rămas pe loc.

## Ce s-a adus din iOS

- **Timpul de judecare.** O încercare care nu primește verdict la timp se oprește. Copilul aude „Nu am putut asculta” și poate încerca din nou.
- **Oglinda.** Fiecare pas cu microfon e verificat după câmpul lui `mic`, ca în `CoachEar.swift`. La final se ascultă fiecare silabă.
- **„Șterge tot”.** Se golesc baza de date, preferințele, vocea, verificările rapide, exercițiile, citirea, modul adaptiv, fișierele exportate și jurnalul. Dacă ceva rămâne, adultul vede ce anume.
- **Exportul.** Fișierul folosește schema 2 și nu conține numele copilului. Numele scrise în notițe devin „[nume]”. Rezumatul jurnalului nu mai conține modelul telefonului. Jurnalul de exersare e oprit până îl pornește adultul, din Setări.
- **Textul de confidențialitate.** Formularea e cea din iOS, plus ce păstrează doar Android. Setările mai au:
  - operatorul și emailul de contact;
  - linkurile spre politică și spre ajutor, care se deschid în browser;
  - versiunea aplicației.

  Primul start are o pagină pentru adult.
- **Verificarea adultului.** E portată din `AdultGate` al shell-ului `:app`. Adultul ține apăsat 3 secunde, apoi scrie cu cifre un număr dat în litere.
  - Apare doar dacă pe telefon există un profil de copil.
  - Păzește Setările (cu exportul, „Șterge tot” și linkurile), notițele unui copil și trimiterea raportului de citire.
  - Se închide după 3 minute în fundal.
  - Pentru teste există flagul de debug `foniaGate`, care fixează numărul cerut.
- **Ciornele.** Bac, „Cuvântul important”, proba de citire și antrenamentul de dicție pe sunete apar doar în build-ul debug sau cu flagul `foniaDrafts`.
- **Textele.**
  - Pastila de pe ecranul principal arată o bifă și „N zile”, fără flacără.
  - Profilurile de adult nu mai văd rânduri scrise pentru adultul unui copil (`AudienceCopy`).
  - Mulțumirile numesc MediaPipe, LiteRT, CameraX, Oboe, Media3 și scanarea 3D (CC BY 3.0 Lee Perry-Smith, dinți MakeHuman CC0).
  - Efectele sonore sunt descrise așa cum spune `tools/sfx`: generate cu ElevenLabs și sintetizate. Rândul vechi „UI SFX (CC0)” nu mai era adevărat.
- **„Cum merge”.** Sub fiecare sunet apare „Etapa: …”: Emitere, Consolidare, Diferențiere sau Automatizare.

### A doua tură (24 noiembrie 2026)

- **Testele instrumentate pentru verificarea adultului.** Trec verificarea cu `foniaGate`, ca aplicația de acum.
- **Licențele.** Pagina „Licențe” din Setări are textul fiecărei biblioteci și al fiecărui font.
- **Accesibilitate.** TalkBack citește în cuvinte punctele lecțiilor. Țintele pentru copil au cel puțin 56 dp.
- **Raportul pentru logoped, ca PDF.** Se face pe telefon, fără numele copilului, și se trimite din Setări, după verificarea adultului. Fiecare sunet are treapta, etapa, încercările telefonului și ale adultului și un grafic pe săptămâni.
- **Fișa pentru adult.** Înaintea primei lecții de fiecare fel, adultul unui copil vede o fișă scurtă: ținta, cum arată o încercare bună, ce face dacă nu iese. Fișele se recitesc în Setări, la „Ce faci la fiecare activitate”, și din ecranul de start („Ce faci, pe scurt”). Pentru teste există flagul `foniaNoAdultCards`.
- **Vorbit în public, pentru adulți.**
  - „Compară”: telefonul spune exemplul, face o pauză, apoi pune înregistrarea ta și pune o singură întrebare.
  - Subiectele zilnice sunt o scară de 7 pași, de la cel mai personal la cel mai public. „Un pas înapoi” te duce la pasul de dinainte.
  - „Doar începutul” repetă primul minut al prezentării tale.
  - „Ai emoții?” apare întreg la primele două discursuri, apoi la o atingere.
  - „Ăă”-urile se compară doar cu discursurile tale de dinainte (mediana ultimelor 5 cu cel puțin 30 de secunde de vorbire), niciodată cu o normă.
- **Amintirea zilnică.** O notificare locală pe zi, la ora aleasă (implicit 19:30). E oprită până o pornește adultul din Setări, după verificare.
  - Android cere permisiunea de notificări abia atunci.
  - Dacă notificările sunt oprite, rândul spune asta și are „Deschide Setările”.
  - Alarma e inexactă (fereastră de 10 minute), deci nu cere permisiunea de alarme exacte. Revine după repornirea telefonului, după schimbarea orei și la deschiderea aplicației după o oprire forțată.
  - „Șterge tot” oprește alarma și notificarea afișată.

### A treia tură (25 noiembrie 2026)

Sursa: `docs/port-android.md` §21, §22 și §23 din aplicația iOS.

- **Partea de jos a unei încercări.** Jos e o singură foaie albă, cu aceeași mărime în „Ascultă”, „Acum tu”, „Te aud” și „Mă gândesc”.
  - Linia Ș–S stă chiar deasupra rândului de jos. Rândul are înălțimea lui „Mai departe”: 64 dp la copil, 56 la adult.
  - Pistele liniei stau pe loc tot timpul. Bila se vede doar cât e microfonul deschis.
  - Pe o încercare fără linie (tăcută sau judecată de el), locul liniei rămâne gol, dacă alte încercări din lecție o au. Așa foaia nu-și schimbă mărimea.
  - Cardul cu rezultatul e aceeași foaie. „Ascultă-te”, „Așa se spune” și „Mai rar” sunt trei butoane egale pe un rând, cu iconița deasupra. La text foarte mare trec unul sub altul. Dovada încercării (linia) stă chiar deasupra lui „Mai departe”.
  - Cercul „Te aud” crește doar în pătratul lui de 56 dp, așa că nu acoperă cuvintele. Barele vocii au loc rezervat și se văd doar cât ascultă.
  - Cuvintele se schimbă pe loc: cele vechi ies, apoi intră cele noi. Cardul intră după ce linia a ieșit. Pagina următoare intră după ce a ieșit cea veche. Cu „Reduce mișcarea”, doar se estompează.
  - „Nu te aud” stă peste Foni, nu sub linie.
  - La „Ține sunetul” e aceeași foaie, iar la final linia spune „Ai ținut până la capăt. Bravo!”, „Ai stins lumânarea. Bravo!” sau „Gata”.
- **Fără vârste.** Propoziția cu vârsta a ieșit din notele pentru părinte, iar `typicalByAge` a ieșit din conținut și din cod. Notele sunt identice cu cele de pe iOS.
- **Regula celor două zile.** Un copil urcă o treaptă doar dacă a avut un bloc bun și într-o zi de dinainte. Două lecții bune în aceeași zi nu ajung. Ce își notează copilul singur („Bine”, „Aproape”) nu umple un bloc. Adultul care exersează singur nu are regula asta.
- **Butonul mare.** Un sunet ajuns la „Peste tot” (automatizare) lasă loc următorului. De la propoziții în sus, două sunete se iau pe rând, câte unul pe zi, chiar dacă exersează o zi da, una nu.
- **Întrebarea despre misiune.** După o zi cu „Azi, peste zi”, următoarea lecție întreabă adultul o singură dată: „A folosit sunetul peste zi?”, cu „Da”, „Uneori”, „Încă nu” și „Nu acum”.
  - Pagina se cheamă „Pentru adult” la copil și „Azi, peste zi” la adult.
  - Nu apare la verificarea rapidă și nici cu `foniaNoAdultCards`. Pentru probă există `foniaMissionSample`.
  - Răspunsul apare în Jurnal și în raportul PDF, pe un rând: „R: A folosit sunetul peste zi? Uneori.”
  - E salvat în tabelul nou `missionCheck` (migrarea `v3-mission-checks`). Se șterge cu profilul și cu „Șterge tot”. Nu intră în exportul JSON, ca pe iOS.
- **Numărătorile.** Copilul nu mai vede câte lecții are făcute: nici pe Acasă, nici la medalie, nici „N din M” pe cardurile din Lecții. A ieșit și promisiunea „6–8 săptămâni”.

Din §23:

- **Ecranul de final.** O lecție terminată primește „Bravo!”. „Ai exersat!” apare doar când nicio încercare n-a ieșit. Foni e mereu vesel. Sub titlu stă pasul următor pentru sunetul exersat („Urmează la R: …”). „Încă una” și „Gata” apar doar când există un pas următor. Copilul care a terminat tot vede la revedere.
- **Paginile cu scenă.** Butonul înapoi stă mereu pe o bandă de hârtie care apare după ce scena iese din ecran. Nu mai e voal cu gradient.
- **Pagina sunetului.** Sub scenă scrie „<eroul> te învață <sunetul>”. Lecțiile următoare au numărul pe un disc. Lecțiile încuiate stau sub un singur rând, fără număr: „Urmează și alte lecții”, „Se deschid după ce te ascultă un adult.” și legătura „Pentru adult”. Punctele de reușită ale lui Android au rămas pe rândul acela.
- **Încălzirea.** E o pagină cu scenă și câte un rând pentru fiecare exercițiu.
- **Primul start.** Ordinea e: vocea, cine exersează, apoi (la copil) nota pentru adult și copilul. Stilul ales e unul singur, cu bifă și chenar. Butonul care așteaptă spune ce lipsește („Alege vârsta”).
- **Vorbit în public.** Fiecare pas are X. Cât vorbește, X întreabă „Te oprești aici?”, cu „Mă opresc” și „Mai rămân”, și spune „Ce ai spus până acum nu se păstrează.”. „Sar peste” sare întrebarea despre cum te-ai simțit. Butoanele raportului stau jos. Ritmul apare doar când are destule date. „Ai ajuns la capăt” apare doar dacă discursul a durat cel puțin 80% din timp.
- **Acasă.** Pagina se oprește deasupra butonului galben. O bandă de culoarea paginii stă sub ceas cât pagina e derulată. Rotița „Setări” duce în zona adultului, prin aceeași verificare. „0 zile” și „0” stele nu apar până la prima lecție.
- **Progres.** Butonul „Exersează” a ieșit. Barele săptămânii arată minute, cu numărul deasupra și „Minute pe zi” dedesubt. Rândul cursului spune „Ai N minute. Merge cel mai bine cu puțin și des.”.
- **Verificarea rapidă.** „Mai târziu” e un buton text verde. Pe rezultat, butonul stă jos, cu „Lecțiile de acasă vor începe cu X.” deasupra.
- **Titluri și notițe.** Un titlu lung trece pe două rânduri („Notițe pentru logoped”). „Salvează” stă mereu acolo și e oprit cât nota e goală.
- **Bara lecției** are pista culorii câmpului, cerneală la 9%.
- **Cuvintele cu cratimă** nu se mai rup la capăt de rând în ghid (pași, „Dacă nu iese”), în fișele pentru adult și în indiciile oglinzii. Cititorul de ecran citește textul simplu.
- **Oglinda** se cheamă „Încearcă S”, ca butonul care o deschide.
- **Colțuri.** Trei raze: 12, 16 și 22.

Decizii din tura asta:

- **Regula celor două zile după propoziții.** Pe Android, de la propoziții în sus, copilul urcă prin reușita lui, fără să fie întrebat un adult. Regula celor două zile se aplică și acolo: o singură zi bună nu ajunge.
- **„Mă gândesc”.** Linia spunea „Ascult” cât se judecă încercarea. Acum spune „Mă gândesc”, ca pe iOS, cu clepsidra.
- **Linia pe încercările tăcute.** Android arăta linia Ș–S și pe încercările tăcute sau judecate de copil. Acum o arată doar unde iOS o arată (blocurile care răspund pe loc), iar pe celelalte îi ține locul gol.
- **Butonul mare.** Regula din `PathModel.todaysSound` e aceeași ca pe iOS: sunetul exersat ultima dată într-o zi de dinainte stă, cel de azi rămâne, fără lecții se ia primul.
- **„Câte 12 minute, de 4 ori pe săptămână.”** E doar în pagina „Engleză”, pe care o văd doar adulții. A rămas.
- **„peste câteva săptămâni”** din proba de citire e identic cu iOS și îl vede doar adultul (Setări, „Citire”, doar cu ciornele). A rămas.

Ce nu s-a făcut în tura asta:

- Pe iOS, cardurile din Lecții mai arată „N din M” și la copil. Pe Android nu le mai arată.
- Ecranele de dicție, verificarea rapidă și oglinda nu folosesc foaia de jos. Au rămas cum erau.
- Alegerea momentului („Alege un moment”). Android nu are pasul acela la primul start.
- „Nu acum” pe rezultatul verificării rapide. Pe Android planul se pune singur la final, deci nu e nimic de refuzat.
- Razele din verificarea rapidă, listele de setări, citire, BAC și accent. Verificarea e a colegului, iar celelalte nu sunt în §23.
- Ghidul sunetului de pe Android nu are nota pentru adult, iar indiciile oglinzii noi au un singur rând. Acolo nu era nimic de legat.
- Rândul „Se deschide când îți iese sunetul de 5 ori.” nu mai apare pe pagina sunetului. Punctele de reușită au rămas.

### A patra tură (25 noiembrie 2026, seara)

Sursa: `docs/port-android.md` §24 și §25 din aplicația iOS. Conținutul e fixat la `f5acae7` din fonia-content („clearer english and pitch subtitles”).

Din §25 (texte):

- **Conținutul.** `english.json` și `diction.json` vin direct din submodul, fără pas de generare: „Accentul în cuvânt”, cele două indicii noi la contrastele de accent și „Cum urcă și coboară vocea” la Ton. Prima propoziție din indiciu e și subtitlul unității din pagina „Engleză”.
- **Cursul (`Course.kt`).** Încălzirea gurii: „Câteva mișcări pentru limbă și buze”. Dicția: „Pauzele” („Te oprești la fiecare linie”), „Odată cu vocea” („Asculți și spui în același timp”), „Sfârșitul cuvintelor”. Unitatea Tonul: „Cum urcă și coboară vocea”.
- **Engleză.** Nota de sus are textul nou de pe iOS. Pe Android era deja una mai scurtă („Întâi accentul și vocalele. …”), dar s-a luat textul iOS, ca să fie la fel.
- **Încălzirea.** „Înainte de sunete, încălzim gura. Tragi aer pe nas, apoi sufli încet.”
- **Vorbit în public.** „Exersează asta”, „A curs bine. Acum spune același lucru în mai puțin timp.” și „După câteva încercări îți arăt cum se schimbă la tine.” Propoziția de sub scenă era deja cea scurtă.
- **Setări.** Jurnalul de exersare: „Notează pentru logoped câte încercări au fost în fiecare zi, doar ca numere. Fără voce.”

Din §24 (așezare):

- **Scena paginilor cu scenă.** Titlul și desenul au fiecare banda lor: titlul la 12 dp sub bara de stare, cu 60 dp liberi pe laturi, iar desenul centrat între titlu și panoul cu propoziția, cu 12 dp de aer. Scena păstrează înălțimea de până acum și crește când titlul trece pe două rânduri. Desenul nu mai e coborât cu 16 dp. Bara apare după înălțimea reală a scenei.
- **Bara de sus a paginilor cu scenă** ia titlul pe două rânduri, ca antetul paginilor simple.
- **Butoanele rotunde** din antet și din bară stau la mijlocul primului rând al titlului (0,62 din linia de bază).
- **Panoul cu propoziția.** Peste 120 de caractere (70 de la scara de text 1,3 în sus) textul stă la stânga, cu 18 dp pe laturi.
- **Butonul secundar alb** are contur de 1 dp, `chipEdge` la 55%. Oprit, nu are.
- **Vorbit în public** stă pe hârtie.
- **Rândurile de lecție.** Detaliul are cel mult două rânduri (era trei). De la scara de text 1,3 în sus nu mai e tăiat.
- **„Ascultă-te”, „Așa se spune”, „Mai rar”.** Conținutul pornește de sus, cu 10 dp deasupra.
- Titlurile de pagină (`fTitle`) se rup în rânduri echilibrate (`LineBreak.Heading`), ca pe iOS: „TH, sunetul cu / limba afară”, nu „TH, sunetul cu limba / afară”.

Ce nu s-a făcut în tura asta:

- „Ce urmează alege un adult.”: rezultatul verificării rapide de pe Android nu are rândul acesta.
- Intro-ul BAC și eticheta „alege ce simți tu”: Android nu are autoevaluarea pe competențe.
- Textele cu momentul zilei de la primul start: Android nu are pasul acela.
- Iconița din rândurile de setări: Setările de pe Android sunt o listă simplă, fără pătrate cu iconiță.
- Rândul „Urmează la S: …” după dicție: pe Android, la finalul unui pas de dicție rândul spune deja pasul următor al antrenamentului („Urmează: …”, fără sunet), nu lecția din drumul pe sunete.
- „Spui odată cu vocea” rămâne la lecțiile de melodie din engleză, ca pe iOS.
- Textele cu voce înregistrată din `sounds.json` nu s-au schimbat (nici pe iOS).

### A cincea tură (25 noiembrie 2026)

Sursa: `docs/port-android.md` §27 din aplicația iOS și `docs/PENTRU_STEFAN.md` §5, punctul 4: la automatizare, verdictul apare doar la circa jumătate din rostiri.

- **Ritmul (`Curriculum.kt`, `SessionBuilder.kt`).** `Stage.isAutomatizare` e adevărat la propoziții, povestire și zi cu zi. `FeedbackSchedule.automatizare` așază rostirile de exersat din blocurile A, B și din cel amestecat în perechi. Din fiecare pereche, una arată verdictul (`Quick`, sau `Teach` dacă era deja așa), iar cealaltă e `Silent`. Prima rostire are mereu verdict, deci niciodată trei la rând fără. Sămânța e trasă ultima din `SeededRandom`, ca itemii să rămână aceiași. Ritmul e identic cu iOS: semințele 7 și 42 au același șir de referință pe ambele platforme. Deschiderea, ascultarea, finalul și misiunea nu s-au schimbat.
- **`ios-sessions.txt`.** Fișierul e rescris din constructorul iOS și copiat aici. Diferă doar coloana de feedback, la sesiunile de sunet pe cele trei trepte.
- **Rostirea fără verdict (`SpeakExercise.kt`, `acknowledge`).** Se judecă și se salvează ca oricare, prin `run.finishAttempt`, cu rezultatul telefonului, deci progresul, blocurile de șase și raportul pentru adult merg ca înainte. Se aude un „pop”, rândul de jos arată „Te-am auzit” (faza nouă `SpeakPhase.Heard`), iar copilul aude clipul înregistrat care exista deja. După 1,2 s, sau cât ține clipul, vine itemul următor. Nu apar panoul, „Încă o dată”, litera colorată sau vibrația.
- **`TakeFeedbackRules.hidesVerdict`.** O rostire `Silent` nu mai setează `verdict` pe nicio treaptă. Înainte, pe Android, litera arăta „corect” sau „aproape” și telefonul vibra chiar și pe rostirile `Silent`. „Nu te-am auzit” și întrebarea pentru adult sau copil rămân.
- **Contoare vii.** Nu se mai arată la automatizare (`MeterPlan.shows(feedback, automatizare)`).
- **Pentru adult.** În pagina pentru părinți, sub etapă, la sunetul aflat la automatizare: `AdultCards.AUTOMATIZARE_NOTE`, același text ca pe iOS.
- **Teste.** `SessionBuilderTests` (ritmul pe cele trei trepte, 3 din 6 în blocurile A și B, prima cu verdict, niciodată trei la rând fără, ritmul de referință). `SessionRunTests` (o sesiune întreagă la propoziții salvează toate rostirile, cu tot cu cele fără verdict, și umple blocurile de șase). `TakeFeedbackTests` (`hidesVerdict`, durata lui „Te-am auzit”, fără contoare la automatizare).

### A șasea tură: polish după v29 (25 noiembrie 2026)

- **Ținte de atingere.** Pe ecranele copilului orice buton ia atingeri pe cel puțin 56 dp, la adult rămâne 48 (`TouchFloor.kt`, `ProvideTouchFloor`). Desenul nu se schimbă: Compose lărgește zona de atingere după `minimumTouchTargetSize`. Butoanele din sistemul de design (`FoniButton`, `ChoicePill`, `Pill`, `IconTile`, `MiniPill`) au la copil și înălțimea desenată de 56. Foile (`FoniaSheet`) dau pragul mai departe, pentru că un dialog are configurația lui. Pastila „30 s” din antrenorul de vorbit se apasă acum pe toată suprafața.
- **Contrast.** `starTint` e `#BA8000` (3,4:1 pe alb, 3,2:1 pe hârtie; era 2,2 și 2,1). Inelul care respiră sub butonul galben păstrează galbenul vechi, ca `startHalo`.
- **Acasă în repaus.** Toate animațiile fără sfârșit (Foni, norii, stelele de noapte, inelul butonului galben, litera de pe Acasă) merg pe un singur ritm, de 12 pe secundă (`AmbientBeat`). Se opresc sub o lecție, sub foaia de start, la calibrare și pe pagina de tab care nu se vede (`AmbientMotion`). Foni își calculează poza la fiecare cadru, dar o desenează doar când se vede ceva mișcându-se. Pe emulator (Pixel 7, GPU de pe gazdă), după 30 s fără atingere: procesul de la 36–49 % la 19–23 % dintr-un nucleu, `RenderThread` de la 31 % la 18 %, firul principal de la 12 % la 4 %.
- **Minute.** O zi cu exerciții sub un minut numără un minut și în grafic, și în totalul din plăcuța „Minute”. Înainte graficul arăta 1 și plăcuța 0.
- **Fără numere pentru copil.** Bara lecției se citește cu TalkBack în cuvinte („Peste jumătate”), nu în procente. Cardul „Antrenament” nu mai spune „N din M” unui copil. Pe pagina „Vorbit în public”, copilul vede doar durata, fără „Pasul N din M”.
- **Stări goale.** Înainte de prima lecție, „Progres” arată un singur rând („După prima lecție, aici se adună zilele, stelele și minutele tale.”) în locul celor patru plăcuțe cu 0. Jurnalul spune „Aici apar zilele în care exersezi.”. O zi care n-a venit încă spune „Ziua asta n-a venit încă.”. Raportul antrenorului de vorbit, după prima încercare: „După încă o încercare vezi aici cum se schimbă.”.
- **Verificare rapidă.** Copilul nu mai vede pe card literele cu verdict de după o verificare, ci „Hai să vedem ce s-a schimbat”, ca pe iOS.
- **Tranziții.** Foile urcă cu arc, iar paginile de tab alunecă (sărite cu „Elimină animațiile”). Compose ține cont de scara animațiilor din sistem. Nu s-a adăugat nicio animație nouă.
- `versionCode` 30. „Merge bine așa” și nota din `pacing.json`, ca pe iOS.

Rămas pentru proprietar:

- Pe Android, ușa „Vorbit în public” apare și copilului (pe iOS doar adultului).
- Raportul pentru logoped: iOS scrie „Fonia nu pune diagnostice”, Android „Fonia doar numără încercările, nu trage concluzii”. Trebuie ales un text.
- Testele `ArtTests` din `:legacy:design` caută `legacy/content/images`, care nu există în worktree. Nu au legătură cu schimbările de aici.

### A șaptea tură: raportul săptămânal pentru adult (25 noiembrie 2026)

Sursa: `docs/port-android.md` §29 din aplicația iOS. Răspunde la §5.5 din `PENTRU_STEFAN.md` (modelul McKechnie din `CERCETARE_DISLALIE.md`: aplicația spune „bine / încă nu”, iar un om ascultă o dată pe săptămână).

- **Pagina „Săptămâna pe scurt”** (`grownups/WeekScreen.kt`, `Destination.Week`). Rând nou sub „Cum merge”, la fiecare copil, în spatele verificării adultului. Se deschide pe săptămâna curentă; săgețile (48 dp) duc la „Săptămâna trecută” și mai înapoi, până la prima săptămână cu exerciții.
- **Ce arată.** Zilele și minutele săptămânii; pe fiecare sunet litera, treapta, etapa, zilele, minutele, încercările și ce s-a mișcat; apoi cel mult cinci înregistrări păstrate, fiecare cu butonul de ascultat, ziua și ora, ce a zis telefonul și „Sună bine” / „Mai lucrăm” (cel puțin 48 dp).
- **Alegerea** (`WeekReport.pick`, funcție pură, ca pe iOS): întâi prima și ultima rostire a aceluiași cuvânt, perechea cea mai depărtată în timp; apoi cele la care telefonul a zis „aproape” sau nu a judecat și la care adultul nu a răspuns; apoi orice altă rostire fără răspuns; apoi restul. Cele mai noi întâi, afișate de la cea mai veche. După un răspuns lista nu se reordonează.
- **Ce scrie un răspuns.** „Sună bine” pune `adultOutcome = Good`; „Mai lucrăm” pune `NotYet` și lasă un „aproape” al adultului cum era. Se vede în „Cum merge” și în raportul PDF. Treapta, blocurile și motorul adaptiv nu se schimbă: un răspuns nu urcă niciodată copilul.
- **Ascultarea** trece prin playerul existent (`ClipDecoder` + `audio.play`). Nu pornește cât e microfonul deschis (`AudioHub.isListening`, nou) și se oprește la ieșirea din pagină.
- **Cu „Păstrează înregistrările copiilor” oprit** apare un singur rând sub săptămână, cu locul comutatorului.
- **Nimic nu pleacă de pe telefon.** Nu s-a adăugat export sau partajare de înregistrări.
- **Store:** `FoniaRepository.attempts(profileId, fromLocalDay, throughLocalDay)`, cu testul `attemptsBetweenDays`.
- **Mostra de debug:** `foniaWeekSample` (sau `-foniaWeekSample` în `arguments`) dă unui copil fără exerciții două săptămâni pe primul sunet, cu trei înregistrări făcute din vocile aplicației. Cu `keepChildRecordings` pornit se vede și lista.
- **Teste:** `WeekReportTests` (aceleași cazuri ca pe iOS).

Diferență față de iOS: Android nu fixează primele încercări la cuvinte noi, deci pe fiecare sunet rămân doar ultimele trei înregistrări. Perechea „prima / cea mai nouă” apare rar, iar săptămânile vechi rămân de obicei fără înregistrări. Rândul de jos spune doar „La fiecare sunet rămân ultimele trei.”.

## Deciziile și de ce

- **Verificarea adultului vs. „fără ușa adultului” (a2dd627).** Cererea de sincronizare a cerut explicit verificarea, ca pe iOS. Ușa „Sunt adult” nu a revenit. Verificarea apare doar când se intră într-o zonă de adult. Testele instrumentate vechi care caută „Sunt adult” erau deja nepotrivite înainte de această muncă. Se pot repara cu `foniaGate`.
- **Lacătul după sunetul singur.** Lecțiile se deschid în continuare prin reușita copilului (5 reușite). Pasul spre silabe așteaptă totuși ca un adult să asculte și să apese „Am ascultat, e bine” în Setări. Așa cere „Lacătul” din `docs/v2/06`, iar la copii e implicit pornit. Cu lacătul oprit, sau după confirmarea adultului, cele 5 reușite îl duc mai departe. Pragul de 12 din plan rămâne doar în `Progression`.
- **Ecranul de final.** Copilul nu mai vede „N au ieșit bine din M” și nici „N din M în zonă”. Vede cuvântul („Bravo!”, „Ai exersat!”) și cel mai lung sunet. Adultul vede în continuare numărătoarea.
- **Ciornele.** Se văd în debug sau cu flag, cum cere decizia proprietarului. Pe iOS flagul e cerut și în debug.
- **ZIPA.** Aplicația livrată nu are permisiunea INTERNET, așa că descărcarea nu poate merge. Secțiunea e ascunsă cât timp modelul lipsește.

## Ce nu s-a făcut

- Oprirea descărcării unui model. Nu are sens fără internet.
- Modelele Whisper copiate din APK nu se șterg la „Șterge tot”, pentru că fac parte din aplicație.
- Conturul intonației din „Compară” (iOS bd3be47). Cere mult cod nou de analiză și desen. „Compară” pune exemplul și înregistrarea una după alta.
- Anunțul vocal „gata” de la finalul unui discurs. Android nu are `SessionVoice`.
- Butonul „Pentru adult” din ghidul sunetului (`SoundGuide`). Ghidul de pe Android nu are secțiunea aceea. Fișele se găsesc în Setări și în ecranul de start.
- Alegerea momentului zilei („După cină” etc.) la primul start. Ora se alege din Setări. Momentele există în cod (`PracticeMoment`).
