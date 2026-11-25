# Pentru Ștefan și asistentul lui de cod: cum continui și ce mai e de lustruit (2026-11-25)

Citește întâi `docs/REGULI.md`: regulile de produs de acolo nu se negociază. `docs/STARE.md` e starea de la 24 noiembrie; fișierul de față e mai nou și spune unde a rămas lucrul și ce merită îmbunătățit. Scopul tău e îmbunătățirea și polish-ul, nu rescrierea.

## 1. Ce rulează de fapt

- **Aplicația care se instalează acum e `:legacy:app`** (`legacy/app`, `legacy/features`, `legacy/design`, applicationId `tech.fonia.app`). `docs/REGULI.md` o numește „doar referință”, dar din noiembrie ea primește toate motoarele noi și e cea testată pe telefon (versiunea 29). `:app` e shell-ul nou și e încă mic.
- Construiești și instalezi așa:
  ```bash
  export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # pe macOS: JBR-ul din Android Studio
  ./gradlew :legacy:app:assembleDebug -Pfonia.abi=arm64-v8a         # telefon
  ./gradlew :legacy:app:assembleDebug -Pfonia.abi=x86_64            # emulator
  ```
- **Modelele nu sunt în git.** Se iau din `~/.gradle/fonia-models/` (lista și sha256 sunt în `spec/models/manifest.json`).
  - Fără modelul de cuvinte, pune `-Pfonia.roasr=false`. Aplicația merge, doar propozițiile se judecă după sunet.
  - ZIPA (capul complet al judecătorului) intră în testele PC cu `-Pfonia.judge.zipa=true`.
  - Detalii despre modelul de cuvinte în `docs/ASR.md`. Licența lui nu e lămurită (se așteaptă acordul UPB): nu-l pune în niciun commit. Dacă îl vrei local, îl ceri lui Circi și îl pui în `~/.gradle/fonia-models/roasr-parakeet-ro-110m/`.

## 2. Unde stau lucrurile importante

| Ce | Unde |
|---|---|
| Judecătorul de sunet (decizia pe o rostire) | `speech/src/main/kotlin/tech/fonia/judge/TakeJudge.kt` |
| Pragurile și coeficienții pe sunet | `spec/fusion/<sunet>.json`, generate de `spec/fusion/make_v2.py` (nu le edita de mână) |
| Judecata din lecție (sunet + cuvintele auzite) | `legacy/features/.../session/SoundJudge.kt` |
| Recunoașterea cuvintelor în română | `words/` (`:engine:words`) și `RoAsrHost` în `ml/` |
| Antrenorul de vorbit în public | `speaking/` (motor) și `legacy/features/.../speaking/coach/` (ecrane) |
| Pornirea datelor, căile de test | `legacy/features/.../app/AppModel.kt` |
| Evaluarea judecătorului pe PC | `tools/eval/` și `speech/src/test/.../EvalV2Runner.kt`, `EvalV2Fits.kt` |
| Rezultatele evaluării și istoria reglajelor | `spec/fusion/EVAL_v2.md` |

### Lansări de test (doar în build-ul debug)

- `foniaStart drill:<sunet>:<i>` intră direct într-un exercițiu: 0 încălzire, 1 sunetul singur, 2 silabe, 3 cuvinte, 4 perechi, 5 propoziții, 6 frământare. La Ș scrii diacriticul: `drill:ș:3`.
- `foniaMicFile <folder>` ia rostirile din WAV-uri, nu din microfon. Folderul e în `files/` intern și se umple cu `run-as tech.fonia.app cp`. Clipul se alege după numele ASCII al textului cerut.
- Probele cu fișier lucrează pe o copie a datelor (`noBackupFilesDir/TestRun/`), deci nu strică istoricul utilizatorului.
- Jurnalul: `adb logcat -s FoniaMic FoniaJudge FoniaWords`.

## 3. Cum verifici că n-ai stricat judecătorul

```bash
./gradlew --stop
FONIA_EVAL_MANIFEST="$(pwd)/tools/eval/out/manifest.jsonl" FONIA_EVAL_TAG=proba \
  ./gradlew :engine:judge:test --rerun --tests tech.fonia.judge.EvalV2Runner -Pfonia.judge.zipa=true
python3 -I tools/eval/summarize.py proba
```

- Manifestul îl face `python3 -I tools/eval/build_manifest.py`. Clipurile (8 voci sintetice) nu sunt în repo. Dacă nu le ai, cere-le lui Circi sau sari peste pasul ăsta și spune asta în commit.
- Tabelele din `summary.md` sunt pe vocile ținute deoparte. Cifra livrată se scoate din coloana `fullShipped` din `cases.csv`.
- **Regula de cost:** o laudă pe o greșeală e mai rea decât o rostire bună respinsă. Nu crește acceptarea dacă lauda pe greșeli trece de ~5 %.

Testele pure trebuie să rămână verzi (comenzile sunt în `README.md`).

## 4. Starea la versiunea 29 (`77ba943`)

Rostiri corecte acceptate: 83 % în total, cu laude pe greșeli 2,3 %. Pe sunete: R 96, L 90, S 90, Ș 88, Z 84, Ț 78, J 72, GE 64, **CE 52**. Toate cifrele sunt pe voci sintetice de adult: nimic nu e încă măsurat pe copii.

Ce s-a făcut în ultimele versiuni (24–29):
- recunoașterea românească în lecție și în antrenor;
- mesajele de reluare spun ce să schimbi;
- probele cu fișier nu mai ating datele reale;
- ecranul Acasă desenează mai rar când nu se întâmplă nimic;
- antrenorul propune un exercițiu de 30 s pentru zona cea mai slabă („De lucrat”);
- judecătorul nu mai laudă când alinierea e nesigură;
- prima rostire așteaptă modelul de foneme.

## 5. Ce e de îmbunătățit, în ordinea în care contează

### Judecătorul (cel mai important: respingerile false îl blochează pe copil)

1. **CE (52 %).** ZIPA aude [s] în loc de [tʃ] la rostiri bune. Din constante nu se repară fără laude false. E nevoie de greșeli reale de CE înregistrate (ce→țe, ce→șe) sau de un semnal DSP care separă explozia de după pauză.
2. **GE (64 %, laude pe greșeli 16 %) și J (72 %).** Aceeași cauză: [ʒ] aude în loc de [dʒ].
3. **L final („val”).** Capul complet vede `lToGlide` și dă „Lipește limba sus” la o rostire bună. Merită un prag pe poziție sau clipuri în plus.

### Pedagogie (vezi `docs/CERCETARE_DISLALIE.md`)

4. **Feedback adaptiv.** La automatizare, verdictul se arată doar la ~50 % din rostiri (Lowe & Buchwald 2017).
5. **Raportul săptămânal pentru adult**, cu rostirile ascultabile, ca indicațiile fine să vină de la logoped (modelul RCT McKechnie 2020).

### Polish

6. **Performanță pe Acasă.** Ținta e sub 30 % dintr-un nucleu cu ecranul neatins; măsori cu `adb shell top -H`. Neverificat pe telefon după versiunea 28.
7. **Ecranele:** spațieri, stări goale, tranziții, ținte de 56 dp pentru copii, contrast. Păstrează Baloo 2 pentru UI, fontul Allo doar pentru literele vii, bara de jos și butonul galben ale lui Ștefan.
8. **Antrenorul:**
   - reperele de ton și ritm ale vorbitorului;
   - cuvintele de umplutură din recunoaștere pe cronologie;
   - citirea cu voce tare și accentul pe recunoașterea nouă (au nevoie de aliniere forțată CTC).
9. **Sunetul de pornire.** Acum e oprit; trebuie unul acceptat de echipă.

## 6. Reguli care se țin mereu

- **Git:** lucrezi pe `main` din acest depozit (`Circiii/fonia2-android`), cu commituri mici și mesaje simple în română. Depozitul e public: nu urca modele, înregistrări de voce sau chei.
- **Fără internet și fără analiză:** fără permisiunea `INTERNET` și fără SDK-uri de analiză. Nicio înregistrare sau imagine nu pleacă de pe telefon.
- **Copilul nu vede** „greșit”, roșu sau sunet de eșec. Fără cuvintele „diagnostic”, „terapie”, „tratează”. Ș și Ț cu virgulă dedesubt.
- **Audio:**
  - nimic nu se redă cât microfonul e deschis;
  - vocile din aplicație sunt înregistrări ElevenLabs, nu TTS-ul telefonului;
  - nu deducem și nu numim emoții (AI Act).
- **Licențe:** doar permisive.
- **Conținut:** nimic din cartea Carmen Ivanov. Conținutul e original sau din frământări tradiționale.
- **Pragurile** se schimbă în `spec/` (prin `make_v2.py` la fuziune), cu versiunea crescută, și se trec în `EVAL_v2.md` cu cifrele înainte și după.
- **`versionCode`** din `legacy/app/build.gradle.kts` crește la fiecare build dat mai departe.
- **Nu urca** `spec/fusion/__pycache__` și nici fișiere `.pyc`.
