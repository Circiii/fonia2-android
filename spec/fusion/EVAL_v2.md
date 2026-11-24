# Judge evaluation v2: a bigger set, held out by voice (2026-11-24)

**Caveat first, and it stands.** Every voice here is synthetic: ElevenLabs voices and the app's own prompt voice.
None is a child, none is the owner, none is a real person at a real microphone (six clips were played back through
the emulator's mic path). The numbers show how the judge behaves on clean, adult-ish, synthetic Romanian; they say
**nothing** about accuracy on children and only a little about a real teenager. Nothing is unlocked for children
(`spec/unlock.json`, wave 1 stays `trial`); the new `self` rule (below) is for a teen or adult practising alone.

Run: `tools/eval/` (`build_manifest.py` → `EvalV2Runner` → `summarize.py`, `apply_chosen.py`; see the scripts'
headers). Audio stays local (`tmp/test_audio/words_v1`, `words_v2`, `content/voice`, `output/aude/clips/words`).

## The set

756 clips, 1 573 judgments (a clip judged against its own target, a neighbouring target, or the word it stands in
for) plus 345 runner-made edits of S/Ș words (frication cut, −24 dB, a burst for the hiss): **1 918 scored takes,
876 correct and 1 042 error takes**.

| Group | Voices (clips) | Correct takes | Error takes |
|---|---|---|---|
| man | Roger (79: v1 words + 22 new front-vowel items), Will (66, new) | 164 | 220 |
| woman | prompt voice "Andrea" (299), Sarah (57), Lily (57) | 466 | 462 |
| young | Liam (66, young man: the closest to the owner), Sally (66, "youthful children's storyteller"), Jessica (66, young woman) | 246 | 360 |

New ElevenLabs clips (2026-11-24, `eleven_multilingual_v2`, `ro`, MP3 44.1 kHz / 192 kbps, one word or syllable per
file): 66 items × Liam, Will, Sally, Jessica + 24 × Roger = **302 files, 557 characters** of synthesis. Every sound in
initial / medial / final position and before i/e (sită, sete, si, se, șir, șes, și, șe, zi, ze, zebră, jir, je,
jeleu, ține, țel, cină, rece, rid, lemn, lin, gem, gi, girafă, deget…), and error words that are real words where
Romanian has them: sar for zar, sap / tap for țap, șină for cină, jer for ger, lac / male / cal for rac / mare / car,
iac for rac, sold ↔ șold, plus bursts „ci-ci-ci”, „ge-ge-ge”. Manifest: `tools/eval/out/manifest.jsonl`.

Dropped as ground truth (documented in `build_manifest.py`): the new voices' **„si”** — each one sits at that voice's
own Ș (centroid 4.8–6 kHz, peak 3.4–3.7 kHz, ZIPA prefers [ʃ], [ts] or [t] to [s]): the TTS did not say [si]. That is
most likely why „si” was "always Unsure" before: the audio, not the judge. And „fuji” for „fugi” (the English [dʒ]).

## v1 against v2, held out by voice

v1 = the committed v1 judge (talker group from the DSP median F0). v2 held out = each of the 8 voices judged with
everything chosen on the other 7 (stages below). Detector view (every kind unlocked), full head where ZIPA answered.

| | v1 | v2 held out |
|---|---|---|
| correct takes accepted (Correct) | 641 / 876 (73 %) | **682 (78 %)** |
| correct takes abstained (Almost / Unsure / Retake) | 202 (23 %) | 167 (19 %) |
| **false alarm** (a named error on a correct take) | 33 (3.8 %) | **27 (3.1 %)** |
| error takes: right kind named | 670 / 1 045 (64 %) | 680 / 1 042 (65 %) |
| error takes: wrong kind named | 63 (6.0 %) | **30 (2.9 %)** |
| **false praise** (an error take called Correct) | 34 (3.3 %) | 34 (3.3 %) |
| error takes abstained | 278 (27 %) | 298 (29 %) |

| Group | accepted v1 → v2 | false alarm v1 → v2 | right kind v1 → v2 | wrong kind v1 → v2 | false praise v1 → v2 |
|---|---|---|---|---|---|
| man | 73 % → **77 %** | 7 → 5 | 61 % → 62 % | 10 → 4 | 9 → 8 |
| woman | 76 % → **81 %** | 13 → 9 | 69 % → 70 % | 31 → 12 | 15 → 11 |
| young | 67 % → **73 %** | 13 → 13 | 60 % → 61 % | 22 → 14 | 10 → 15 |

Per sound, v2 held out (correct accepted / false alarm; error takes right / false praise):

| Sound | accepted (v1) | FA (v1) | right (v1) | praised (v1) |
|---|---|---|---|---|
| S | 90 % (93 %) | 1 (0) | 42 % (47 %) | 7 (15) |
| Ș | **84 % (75 %)** | 1 (1) | 56 % (59 %) | 17 (12) |
| Z | **81 % (68 %)** | 5 (5) | 92 % (93 %) | 2 (1) |
| J | **67 % (46 %)** | 9 (8) | 93 % (90 %) | 1 (1) |
| Ț | 75 % (78 %) | 0 (0) | **82 % (58 %)** | 2 (1) |
| CE | 55 % (52 %) | **2 (9)** | 25 % (50 %) | 0 (0) |
| GE | 41 % (38 %) | 4 (6) | 36 % (25 %) | 5 (4) |
| R | 89 % (84 %) | 4 (3) | 90 % (88 %) | 0 (0) |
| L | 85 % (84 %) | 1 (1) | 74 % (67 %) | 0 (0) |

Per sound × group: `tools/eval/out/v2/summary.md`.

## What changed, per weak spot

- **Per-sound weight of the phone evidence** (`fullFrom` per sound in `spec/fusion/*.json`, `make_v2.py`; new
  `cPlace` = ZIPA's weight on the place classes). Chosen per sound on a grid (alpha × c × cPlace × correctBias, 400
  points) with leave-one-voice-out. Shipped: S a0.5 c0.25; Ș a0.75 c0.5 bias −1; Z a1 c0.75; J a0.5 c1.25; Ț a0.25
  c1.25 cPlace 0.5; CE a0.25 c0.75 cPlace 0; GE a0.25 c0.5; R a1 c0.75; L a0.25 c0.75 (g 0.5 throughout). **Ș**
  accepted 75 % → 84 % (the fast head leads, ZIPA's [s] counts half as much); J, Z, Ț clearly better.
- **Cost the fit minimises** follows the owner's brief: false alarm 6, false praise 5, wrong kind 3, abstaining on a
  correct take 2 (Almost 1.2), abstaining on an error 1; each voice weighs the same; runner edits weigh half.
- **Vowel context (S/Ș before i/e)**: the mechanism is in (`Features.FRONT_CONTEXT`, `judge2.contextShift`), but the
  folds chose unstable signs (s:i from −1 to +1, ș:i from −2 to +4) once the false „si” clips were out: on these
  voices S before i/e is not Ș-ward (centroids of S-before-i = S elsewhere). **Shipped off**; to fit on real takes.
- **Place trust** (mask ZIPA's place candidate when the spectrum is clearly in the target zone): every fold chose off.
- **Praise when the heads agree** (`judge2.praise`): 2 of 8 folds wanted it; shipped off. A man's „sare” is no longer
  the problem: with the full head every Roger/Will „sare/sară” is accepted (p_full 0.82–1.0 against p_fast 0.59–0.90).
- **Talker group**: `TalkerEstimator` (YIN first dip + DSP median, octave errors folded, two-octave splits averaged).
  On these voices it fixes the halved and doubled takes (Lily „colț” 63 → 134 Hz, „joc” 88 → ~180 Hz); Lily's own
  F0 really sits at 130–180 Hz on many words, so she still reads as "man" on about half her takes.
- **Word gate**: ZIPA's fit gate (−3 per label) sent 28 correct one-word takes back as "another word"; the first
  percentile of the correct words' fits is −4.2, so the gate is now **−4.5** (`population.json`, hand-set from that
  distribution, not fitted: the set has no truly different words). Correct takes sent back as "another word": 28 → 7.
- **R / L**: R 84 % → 89 % accepted, L unchanged; R→L named 90 %; no false praise on either.
- **Bursts** (`judge2.burst.minFound` 2): the median burst speaks. „ci-ci-ci” accepted 3/4 voices (v1 2/4). Weak: a
  voiceless burst asked as „gi-gi-gi” is praised on 3/4 voices (voicing is not measured on 40 ms bursts and ZIPA's fit
  on bursts is poor); „ge-ge-ge” mostly Unsure.
- **GE** stays weak (41 % accepted, 5 false praise): on these voices ZIPA scores good „ger” and said „jer” alike
  (lpr ʒ −1…−4 on both) and the word-initial closure is invisible to the DSP; only GE with a vowel before it is reliable.

## The `self` policy (spec/unlock.json → rules.self)

For a teen or adult practising alone (`PolicyLevel.select`: the voice is a man's or a woman's, or the profile is an
adult's): a kind is named and `TakeResult.visual.hint` shown when its probability is ≥ **0.85**. Held out by voice:

| threshold | hint shown on a correct take | named kind on a correct take | hint on an error take |
|---|---|---|---|
| 0.80 | 4.8 % | 3.1 % | 72 % |
| **0.85** | **3.8 %** (man 3.0 %, woman 2.6 %, young 6.5 %) | 3.1 % | 69 % |
| 0.90 | 3.0 % | 2.6 % | 64 % |

Hints on error takes at 0.85: addVoice 167, moreAir 113, tapTongue 113, stopThenRelease 104, lipsRound 94,
lipsSpread 76, tongueTipUp 45, holdLonger 9. Children: unchanged (nothing unlocked, trial kinds "în probă").

## On the emulator (full head, model in the APK)

`:legacy:app:assembleDebug -Pfonia.abi=x86_64` (no `-Pfonia.zipa=false`) gives a 243 MB APK with the 70.7 MB ZIPA
model (`assets/models/…/model.int8.onnx`, uncompressed) and `libonnxruntime.so` for x86_64. `JudgeOnDeviceTest`
(`:platform:ml:connectedDebugAndroidTest`, emulator-5564, AVD FinEdu36, the committed spec, four prompt-voice words):
the **full head decided 20/20 takes**; warm-up 4.3 s; per take measure p50 115 ms, ZIPA p50 330 ms (max 707 ms),
decide 0.8 ms, talker estimate 8 ms; total p50 463 ms, max 1.1 s. The emulator was shared with another agent's
session at the time (v1 measured ZIPA at 52 ms on an idle emulator); above 600 ms the app falls back to the fast head.
