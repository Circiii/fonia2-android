# Recunoașterea vorbirii în română (ASR)

## Ce e

- Modelul: **SpeD Parakeet-Ro 110M** (TDT-CTC, Universitatea Politehnica București, laboratorul SpeD), folosit doar cu capul **CTC**, exportat în ONNX și cuantizat **int8**: `roasr-parakeet-ro-110m/ctc.int8.onnx`, 131,65 MB (sha256 `76d3e51b…14ef40`, în `spec/models/manifest.json`, intrarea `roasr`).
- Licența cardului: **Apache-2.0**. Datele de antrenare includ însă corpusul RSC (licență NC-ND). Până la un acord scris al UPB, modelul rămâne **opțional** și nu intră în depozitul public (vezi „Acord” mai jos).
- Rulează pe telefon, prin ONNX Runtime, pe firul `ml-roasr`. Nimic nu pleacă de pe telefon: aplicația nu are permisiunea `INTERNET`.

## Cod

| Unde | Ce |
|---|---|
| `:engine:words` (`words/`) | Kotlin pur: `RoAsrFeatures` (mel 80, ca NeMo), `RoAsrModel` (decodare CTC greedy, timpi pe token, cadre de 80 ms), `Heard`, `WordMatcher`, `ReadingCheck`, `WordsAdvice`, `SpeechText` (cuvinte/min, umpluturi, repetări, rânduri cu timp) |
| `:platform:ml` | `RoAsrHost`: încărcare leneșă, `hear(samples, rate, timeoutMs)`, `lastTimings`, `trim()` după înregistrări lungi; `create()` dă `null` când APK-ul nu are modelul |
| `legacy/features/.../session/SoundJudge.kt` | La propoziții, recunoașterea ascultă în paralel cu capetele de sunet (`withWords`): cuvinte lipsă → „Aproape” cu ce lipsește; toate cuvintele auzite, sunet nehotărât → „Ai spus toată propoziția!”; abținere → verdictul sunetului rămâne |
| `legacy/features/.../app/RoWordsTranscriber.kt` | `Transcriber` peste `RoAsrHost` (doar transcriere): „Tu, fără ăăă”, raportul de discurs, proba orală BAC; `AppModel.wordsTranscriber` alege Whisper dacă există, altfel acesta |
| `legacy/features/.../speaking/coach/` | Antrenorul: după discurs, „Ce ai spus” (cuvinte/min reale, umpluturi pe nume, repetări, textul pe rânduri, apăsabil) |

Citirea cu voce tare și exercițiul de accent rămân pe calea veche: au nevoie de `scorePair` / `scoreSentence` (aliniere forțată), pe care CTC-ul greedy nu le dă.

## Build

- Modelul se ia din `~/.gradle/fonia-models/roasr-parakeet-ro-110m/ctc.int8.onnx`. Dacă lipsește, sau cu `-Pfonia.roasr=false`, `ModelAssetsTask` îl sare și scrie un manifest fără el; aplicația merge ca înainte (propozițiile se judecă doar după sunet).
- Exportul: `tools/asr/export.py` (NeMo → ONNX CTC → int8), verificarea trăsăturilor: `tools/asr/check_feat.py`, evaluarea: `tools/asr/eval_fleurs.py`, `tools/asr/eval_app.py`.

## Cifre

| Măsură | Valoare |
|---|---|
| WER FLEURS ro (300 de enunțuri, fără normalizarea punctuației) | 13,92 % f32, **14,06 % int8** |
| WER pe card | CV21 4,20 %, FLEURS 11,85 % (CTC greedy) |
| Samsung S22: încărcare | 2,1 s (o dată, leneș) |
| Samsung S22: 4,9 s audio | 0,29 s (RTF 0,06) |
| Samsung S22: 103 s audio | 3,0 s (RTF 0,03); memorie ~916 MB imediat după, de aceea `trim()` |
| Emulator x86_64, propoziții de 3–4 cuvinte (voce de bărbat, ElevenLabs) | 120–390 ms (RTF 0,05–0,14); 6/6 propoziții R și L lăudate, cuvintele auzite 3/3, 4/4, 3/4 |

## Ce nu face

- Nu scrie „ăăă” / „mmm” (textul de antrenare e normalizat); ezitările acustice rămân la `HesitationDetector`.
- Nu dă un scor de pronunție: spune ce cuvinte au fost auzite, nu cât de bine a fost rostit un sunet (asta rămâne la ZIPA + GOP).
- Nu e testat pe voci de copii. Pe copii mici se poate abține mai des; o abținere nu schimbă verdictul sunetului.

## Acord

Cererea către UPB (SpeD) e în `HSIC/output/asr/cerere_UPB.md`. Până la răspuns:
- modelul nu intră în `Circiii/fonia2-android` (public) și nici în vreun commit; stă doar în cache-ul Gradle local;
- APK-urile cu model sunt doar pentru probe interne (pilot), nu pentru distribuire publică.
