# Antrenorul de vorbit („Vorbit în public”)

Camera din față pe tot ecranul, o singură indicație pe rând, un raport la final. Tot ce măsoară rulează pe telefon.
Pragurile sunt în `spec/thresholds/speech-coach.json` (un test pică dacă diferă de valorile din `CoachSpec.kt`).

## Ce măsoară și cum

| Lucru | Cum | Fișier |
|---|---|---|
| Ritm | Silabe pe secundă de vorbire. Modelul de foneme (ZIPA) ascultă bucăți de 2 s și numără vocalele (`VowelNuclei`, `ZipaHost.vowelOnsets`, `VowelFeeder`); ritmul = intervale între vocale ÷ timpul lor, un interval mai lung de 0,6 s numărându-se doar până la 0,6 s (pauza nu încetinește ritmul și nu depinde de zgomot). Fără model (emulator, `-Pfonia.zipa=false`): vârfuri de intensitate pe banda vocalelor (de Jong & Wempe 2009), împărțite la 0,75, cât găsesc ele pe vorbire reală. Banda: 3,3–6,6. | `AudioCoach.kt` |
| Privire | Unghiul capului + unghiul ochilor în cap; când te uiți în lentilă, cele două se anulează, deci suma e aproape de zero la oricine. `gx = yaw + 60·eyeH`, `gy = pitch + 25·eyeV + 5`; toleranță 11° lateral, 10° în sus, 7° în jos. Fără bază automată: o numărătoare inversă petrecută citind nu devine „lentila”. Cu calibrarea lui de 6 s (lentila, un rând jos, un rând sus; `GazeCalibration`, păstrată în preferințele aplicației): centrul lui și toleranțele lui. Indicația apare după 2,5 s ținute. | `FaceCoach.kt` |
| Mișcare | Împrăștierea poziției capului (în distanțe dintre ochi) și a unghiurilor pe 3 s; indicația de la 1,6 în sus, ținută 3 s. | `FaceCoach.kt` |
| Voce | Nivelul vorbirii față de al lui (percentila 80, care nu se actualizează cât e „încet”); „Mai tare” la 6 dB sub el, ținut 3 s. | `AudioCoach.kt` |
| Viu | 65 % melodia (împrăștierea înălțimii în semitonuri: 1,5 plat, 3 viu; Hincks 2005), 35 % mișcarea feței (sprâncene, mușchii zâmbetului; 0,03–0,18). Doar mișcare și variație; nicio emoție nu e numită. | `CoachSession.kt` |
| Indicații | Una pe rând, ținută cel puțin 2,2 s; 4 s între două, 12 s până revine aceeași (Schneider et al., Presentation Trainer; Tanveer et al., Rhema: indicații rare). | `CoachCues.kt` |

## Bancul de filmări reale

`HSIC/coach_bench/` (nu intră în repo): `fetch_ia.py` aduce filmările, `selfie.py` le recadrează, `track.py` trece modelul feței
(același `face_landmarker.task`) peste fiecare cadru, `asr.py` face transcriptul, `summary.py` face tabelul.

```
./gradlew :engine:phones:test --tests "*VowelNucleiBenchTest*" -Pfonia.coach.bench=C:/…/coach_bench
./gradlew :engine:speaking:test --tests "*CoachBenchTest*" -Pfonia.coach.bench=C:/…/coach_bench
```

Rezultatele: `HSIC/output/speech2/bench.md`.

## Probă cu o filmare în locul camerei (doar build de debug)

`--es foniaRoute speech:60:free:go --es foniaCoachVideo x.mp4 --es foniaMicFile x.wav`, cu fișierele în folderul `files/`
extern al aplicației. Filmarea rulează pe ceasul antrenorului de la începutul numărătorii; sunetul e tăiat cu 3 s mai târziu.
Codifică filmarea doar din cadre-cheie (`-g 1`), altfel decodarea e lentă.

## Poarta de calitate

Un cadru fără față, prea întunecat, prea departe, prea aproape sau la margine nu se măsoară deloc (`FaceCoach.issueOf`,
pragurile în `quality`). Un singur indiciu mic sub lentilă, după 1,5 s: „Nu te văd”, „Mai multă lumină”, „Mai aproape”,
„Puțin mai departe”, „Vino în mijloc”, „E zgomot în jur”. În zgomot, „Mai tare” și „Mai viu!” tac. Raportul nu dă scor la
privire sub 50 % acoperire și spune cât din timp a fost măsurat când e sub 90 %.

## Raportul

Întâi ce a mers cel mai bine (dacă a trecut de 70), apoi un singur lucru de lucrat. Cuvinte pe minut estimate din silabe
(2,4 silabe pe cuvânt), marcate ca estimare. „Ultimele 5”: câte o bară pe discurs, pe fiecare arie.

## Nefăcut, de știut

- Categoriile de pauze (sub 200 ms, 200–1000 ms, peste 1 s), ritmul și plaja de înălțime obișnuite ale lui ca bază,
  un exercițiu concret pe aria cea mai slabă, tendința pe ecranul de pregătire.
- Calibrarea privirii nu e probată pe un om real: pe o filmare nu învață nimic.
- „Mai tare” legat de mărimea feței (s-a îndepărtat).
- Mâinile (MediaPipe Hand Landmarker, Apache-2.0, 7,8 MB): doar după ce restul e măsurat pe oameni reali.
- Transcriere pe telefon (Parakeet-Ro 110M, +115–130 MB): cere confirmarea scrisă a UPB (date de antrenare NC); umpluturile
  rămân pe detectorul pe sunet.
- Benzile sunt potrivite pe zece vorbitori adulți, în alte limbi decât româna; nimic nu e măsurat pe vocea și fața
  proprietarului.
