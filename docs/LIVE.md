# Litera reacționează doar la sunetul ei (versiunea 30, 2026-11-25)

Cerința: litera vie și bara de ținut să nu mai reacționeze la orice zgomot, ci doar când se aude sunetul exersat. Documentul spune ce s-a schimbat, ce s-a măsurat și ce nu merge încă. Toate cifrele sunt pe voci sintetice de adult și pe zgomote generate; nimic nu e măsurat pe copii sau pe un telefon real.

## Ce face acum aplicația

| Exercițiu | Cine decide „se aude sunetul acum” | Unde e codul |
|---|---|---|
| Silabe, cuvinte, propoziții | Modelul de foneme (ZIPA) ascultă ultima secundă de 5 ori pe secundă | `phones/.../LiveSpotter.kt`, `legacy/features/.../session/LiveSpot.kt`, `ZipaHost.spot` |
| Sunet ținut singur („sss”) | Măsurătoarea acustică, cu o gardă contra zgomotelor plate | `LiveSmoother.kt` (`AimRules.flatMax`), `SustainExercise.kt` |
| Fără model în APK sau model neîncărcat | Măsurătoarea acustică (aceeași gardă) | `LiveSmoother.step`, ramura fără `spotted` |

- **Litera stă nemișcată** până se aude sunetul ei. Vocea în general mișcă doar inelul „Te aud”, ca să se vadă că microfonul merge.
- **Sunetul ținut singur nu poate fi judecat de modelul de foneme:** pe un „ssss” fără silabă în jur nu răspunde nimic (`tools/eval/out/live_probe.txt`). De aceea acolo rămâne măsurătoarea acustică.
- **Garda de zgomot:** un S sau Ș real are un vârf în spectru (planeitate mediană 0,2–0,5 pe clipuri), iar aspiratorul, hârtia, jucăriile și apa sunt plate (0,57–0,87). Un șuierat cu planeitate peste 0,55 nu aprinde nimic și nu umple bara.
- **Vocea (S/Z, Ș/J) nu se verifică live:** între vocale, analizorul marchează S ca „cu voce” la fel de des ca Z. Verdictul de după rostire o verifică.

## Ce s-a măsurat pe PC

Set: 687 de clipuri (8 voci sintetice) și 14 zgomote (tuse, râs, palme, televizor, hârtie, apă, ușă, muzică, suflat, câine, aspirator, jucării, liniște, un bip). Se rulează cu fișierul-steag `tools/eval/out/live_eval.on`:

```bash
touch tools/eval/out/live_eval.on
./gradlew :engine:judge:test --rerun --tests "tech.fonia.judge.LiveSpotEval.measure" -Pfonia.judge.zipa=true   # modelul de foneme
./gradlew :legacy:features:testDebugUnitTest --tests "tech.fonia.features.session.LiveAimEval.measure"          # regula acustică
```

**Modelul de foneme, la pragurile livrate** (`LiveSpotter.tau`):

| Sunet | Aprins când sunetul e în clip | Aprins când nu e | Zgomote care aprind |
|---|---|---|---|
| S | 97 / 101 | 98 / 586 | 0 / 14 |
| Ș | 78 / 80 | 108 / 607 | 0 / 14 |
| Z | 61 / 69 | 37 / 618 | 0 / 14 |
| J | 47 / 67 | 76 / 620 | 0 / 14 |
| Ț | 49 / 70 | 12 / 617 | 0 / 14 |
| CE | 56 / 78 | 41 / 609 | 0 / 14 |
| GE | 38 / 73 | 59 / 614 | 0 / 14 |
| R | 171 / 230 | 12 / 457 | 0 / 14 |
| L | 117 / 124 | 8 / 563 | 0 / 14 |

Rândul Ț e de la o rulare anterioară cu aceeași regulă (oprire „t” + șuierat „s”), la pragul 0,35.

Cum se citesc „aprinderile false”: cele mai multe sunt sunete din aceeași familie, nu zgomote. S se aprinde la Ț și la Z desonorizat la final („orez”), Ș la CE („ceai” conține un Ș), J la GE. Litera spune deci „am auzit un șuierat de felul ăsta”, nu „ai spus corect”; verdictul vine după rostire.

**Regula acustică singură** (pentru comparație și pentru sunetul ținut):

| | Aprins când sunetul e în clip | Aprins când nu e | Zgomote care aprind |
|---|---|---|---|
| Familia S (S, Z, Ț) | 171 / 240 | 34 / 447 | 0 / 14 |
| Familia Ș (Ș, J, CE, GE) | 237 / 296 | 81 / 391 | 5 / 14 (tuse, râs, câine, muzică, palme) |
| R | 16 / 230 | 17 / 457 | 3 / 14 |

Înainte de garda de planeitate, familia Ș se aprindea la 8 din 14 zgomote.

## Ce s-a verificat pe emulator (clipuri redate prin `foniaMicFile`)

- **S ținut:** aspirator 0,00 s din 2 s; S real 3,00 din 3 s (plin); Ș la exercițiul de S 0,47 din 4 s.
- **Cuvinte cu R:** 9 din 9 „Corect”; litera s-a aprins live la 6 din 9.
- **Cuvinte cu L la exercițiul de R:** litera nu s-a aprins niciodată; 0 laude din 17 rostiri.
- **Cuvinte cu L la exercițiul de L:** litera s-a aprins la 8 din 10; „val” primește încă „Lipește limba sus”.

## Alte două reparații din aceeași versiune

- **Calibrarea vocii respinge zgomotul.** Pe emulator, calibrarea făcută cu microfonul oprit salvase același zgomot ca „S” și ca „Ș” (centroid 14 036 Hz și 13 995 Hz, voce la −64 dBFS), iar de atunci totul se judeca pe o referință stricată. Acum o calibrare se păstrează doar dacă s-a auzit o voce (peste −50 dBFS și cu 15 dB peste cameră) și dacă S și Ș sunt două șuierături diferite (cel puțin 700 Hz în centroid sau 5 dB în înclinare). O referință stricată deja salvată e ignorată. Cod: `PersonalReference.separated`, `audible`, `usableRoom`; teste în `CalibrationGuardTests`.
- **Alt cuvânt nu mai e lăudat.** La un singur cuvânt, recunoașterea de cuvinte ascultă și ea. Dacă e sigură că s-a spus alt cuvânt (cel puțin două litere diferite în afara sunetului exersat), lauda devine „Mai spune o dată”. Sunetul exersat nu e niciodată judecat de ea: „lac” spus la „rac” rămâne treaba judecătorului de sunet. Cod: `words/.../OtherWord.kt`, `SoundJudge.judge`.

## Ce nu merge încă

- **J, GE, CE live:** litera se aprinde la doar 52–72 % din clipurile corecte. Modelul de foneme aude des altceva (J ca „j” sau „ʃ”, GE ca „g” tare).
- **Setul de test e parțial stricat la CE și GE.** Vocile sintetice citesc unele silabe și cuvinte scurte ca în engleză: „pace” iese [peɪs], „rece” iese [riːs], „ge” iese [ge] cu G tare (`tools/eval/out/live_greedy.txt`). Deci o parte din „rostirile corecte respinse” la CE și GE sunt de fapt clipuri greșite. Setul trebuie curățat înainte de orice nou reglaj la aceste sunete.
- **Întârziere:** litera se aprinde la 0,2–0,4 s după sunet și rămâne aprinsă cam 0,4 s.
- **Costul pe telefon nu e măsurat:** modelul rulează de 5 ori pe secundă cât e microfonul deschis. De măsurat cu `adb shell top -H` pe un telefon real.
- **R în cuvinte, live:** 74 % din clipuri. R-ul final scurt („nor”, „păr”) scapă des.
- **Sunetul ținut la familia Ș** se mai aprinde scurt la tuse, râs, palme și muzică (sub o secundă fiecare).
- **Pe emulator, microfonul gazdei** trebuie pornit la lansare (`-allow-host-audio`) și e foarte încet (vârf −45 dBFS). Fără el aplicația arată „Nu te aud”.
