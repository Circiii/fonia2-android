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

Sursa: `docs/port-android.md` §21 și §22 din aplicația iOS.

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

Decizii din tura asta:

- **Regula celor două zile după propoziții.** Pe Android, de la propoziții în sus, copilul urcă prin reușita lui, fără să fie întrebat un adult. Regula celor două zile se aplică și acolo: o singură zi bună nu ajunge.
- **„Mă gândesc”.** Linia spunea „Ascult” cât se judecă încercarea. Acum spune „Mă gândesc”, ca pe iOS, cu clepsidra.
- **Linia pe încercările tăcute.** Android arăta linia Ș–S și pe încercările tăcute sau judecate de copil. Acum o arată doar unde iOS o arată (blocurile care răspund pe loc), iar pe celelalte îi ține locul gol.

Ce nu s-a făcut în tura asta:

- Pe iOS, cardurile din Lecții mai arată „N din M” și la copil. Pe Android nu le mai arată.
- Ecranele de dicție, verificarea rapidă și oglinda nu folosesc foaia de jos. Au rămas cum erau.

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
