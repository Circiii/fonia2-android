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

## Deciziile și de ce

- **Verificarea adultului vs. „fără ușa adultului” (a2dd627).** Cererea de sincronizare a cerut explicit verificarea, ca pe iOS. Ușa „Sunt adult” nu a revenit. Verificarea apare doar când se intră într-o zonă de adult. Testele instrumentate vechi care caută „Sunt adult” erau deja nepotrivite înainte de această muncă. Se pot repara cu `foniaGate`.
- **Lacătul după sunetul singur.** Lecțiile se deschid în continuare prin reușita copilului (5 reușite). Pasul spre silabe așteaptă totuși ca un adult să asculte și să apese „Am ascultat, e bine” în Setări. Așa cere „Lacătul” din `docs/v2/06`, iar la copii e implicit pornit. Cu lacătul oprit, sau după confirmarea adultului, cele 5 reușite îl duc mai departe. Pragul de 12 din plan rămâne doar în `Progression`.
- **Ecranul de final.** Copilul nu mai vede „N au ieșit bine din M” și nici „N din M în zonă”. Vede cuvântul („Bravo!”, „Ai exersat!”) și cel mai lung sunet. Adultul vede în continuare numărătoarea.
- **Ciornele.** Se văd în debug sau cu flag, cum cere decizia proprietarului. Pe iOS flagul e cerut și în debug.
- **ZIPA.** Aplicația livrată nu are permisiunea INTERNET, așa că descărcarea nu poate merge. Secțiunea e ascunsă cât timp modelul lipsește.

## Ce nu s-a făcut

- Pagina de licențe și oprirea descărcării unui model (lista B). Oprirea nu are sens fără internet.
- Testele instrumentate vechi (`FoniaUITests`) încă așteaptă ușa „Sunt adult”.
- Modelele Whisper copiate din APK nu se șterg la „Șterge tot”, pentru că fac parte din aplicație.
