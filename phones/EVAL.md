# Evaluation: ZIPA-small int8 + restricted GOP-SF

Run on 6 November 2026, on this repository's content clips. `./gradlew :phones:test` reproduces every number here: `PronunciationEvalTest` writes `phones/build/eval-report.md` (all tables) and `phones/build/eval-trials.csv` (one line per trial).

## In short

- **Separation.** On the minimal pairs the content teaches, with both words recorded (lac/rac, cal/car, lamă/ramă, cos/coș, sar/zar, sare/zare, zar/jar, șoc/joc, țap/sap: 18 trials), the score separates the substituted target from correct words with **AUC 0.979** (95 % CI 0.960–0.993). On 556 simulated substitutions in words: **AUC 0.965** (0.947–0.980). On all 310 clip pairs one substitution apart, syllable drills included: 0.936.
- **False alarms at the chosen thresholds** (fixed before the first run: "try again" at LPR ≤ −3, "correct" at LPR ≥ 0, abstain in between): **3.6 % of correct words** (8 of 221), with **8.6 % "not heard clearly"**. Detection: 14 of the 18 content-pair trials (77.8 %, the other 4 abstained, none was called correct) and 80.6 % of simulated substitutions in words. The substitute was named right in 14 of 14 and 429 of 448.
- **By sound.** R and L are separated perfectly (AUC 1.000, no false alarm). S, Z, J and CE/CI are good (AUC 0.91–1.00). Ș, Ț and GE/GI are the weak ones, with 20–47 % of their correct words called "try again" or not heard: the model often hears this voice's ș as s (Ș: 10.5 % false alarms, 31.6 % abstentions), where the 44.1 kHz DSP judge accepts every Ș word.
- **Latency per word on this PC** (Intel Core Ultra 9 275HX, one-second words, medians of three runs): **32–35 ms with 1 thread**, 23–26 ms with 4 threads. The fbank takes 0.6–0.7 ms and the GOP with the verdict 0.13–0.17 ms; the rest is the network.
- **int8 costs nothing measurable**: the fp32 export gives the same rates and AUCs within 0.002.
- **What this cannot show:** anything about children, distortions, real substitutions, other voices or microphones (§ What the numbers do not show). It shows that the pipeline works end to end and that ZIPA-small hears the Romanian R/L contrasts well and Ș/Ț less well, on clean adult speech.

## Data and protocol

- **Clips.** The 284 single-token Romanian clips of `content/voice` (151 words, 133 syllable drills; held sounds such as `ssssss` left out): one adult ElevenLabs voice, MP3 at 44.1 kHz, decoded and resampled to 16 kHz by ffmpeg with the SoX resampler (the `librosa.resample` of ZIPA's own inference script). They are taken as correct, as `:dsp` takes them. Nobody listened to them for this report. ElevenLabs' terms forbid training on these clips, and nothing was trained or fitted on them: they are test fixtures only.
- **Scoring.** For each occurrence of the target sound, the forward algorithm gives log P(word | audio) with the target as written (R: trill or tap), with each plausible alternative in its place, and with the target left out. LPR is the accepted realisation against the best alternative; the word's score is its lowest LPR. AUCs rank −LPR.
- **Trials.**

  | Set | What is judged | Expected |
  |---|---|---|
  | Correct | each clip against its own text, once per practice sound in it (221 word trials, 133 drill trials) | correct |
  | Content minimal pairs | the 9 pairs of `sounds.json` contrasts with both clips, each clip against the other word (18) | try again, with the other word's sound |
  | All clip pairs | every two clips whose G2P pronunciations differ only by a target turning into one of its alternatives (310, mostly drills: sa/șa, ța/cea, ra/la…) | try again |
  | Simulated substitutions | each clip against its own word with one sound replaced by a target that lists it as an alternative („sanie” against „șanie”), the protocol of Cao et al. on CMU Kids (556 in words, 316 in drills) | try again, with the clip's own sound |
  | Another word | a word clip against another word with the same sound, at least 3 phones away (221) | not "correct" |

- **Input condition.** Main: each clip inside 250 ms of −75 dBFS white noise with 10 ms fades, as the VAD would hand over a word. Several clips start with the word already at full level (the generator trimmed the silence), which an abrupt start turns into a stop burst. The raw clips are reported too.
- **Thresholds.** `ScoringPolicy` defaults, written before the first evaluation run and not changed after it: try again needs the best alternative ≥ e³ ≈ 20 times as likely as the target, correct needs the target to be the likeliest candidate, and a word whose best candidate trails the unconstrained phone path by more than 3 nats per label was not this word.
- **Alternatives** (`Alternatives`): from PLAN §7.1 (S↔Ș↔Ț, Z↔J, CE/CI↔Ț, GE/GI↔J/Z, R↔[l]/[j]/omission, L↔R), the content's own `fixes` ("sună ca I sau U", "se aude din gât", "sună ca CI"…) and its taught pairs (S↔Z, Ș↔J); omission everywhere. The tapped [ɾ] counts as correct R (P3); `ScoringPolicy(tapAccepted = false)` makes it an alternative for the trill games.

## Results (padded clips)

| Set | Trials | Correct | Try again | Not heard (uncertain / poor fit) | Substitute right |
|---|---|---|---|---|---|
| Correct words | 221 | 87.8 % | 3.6 % | 8.6 % (19 / 0) | |
| Correct drills | 133 | 67.7 % | 6.8 % | 25.6 % (21 / 13) | |
| Content minimal pairs | 18 | 0.0 % | 77.8 % | 22.2 % (4 / 0) | 14 of 14 |
| All clip pairs | 310 | 7.7 % | 65.8 % | 26.5 % (46 / 36) | 179 of 204 |
| Simulated, words | 556 | 4.9 % | 80.6 % | 14.6 % (81 / 0) | 429 of 448 |
| Simulated, drills | 316 | 7.9 % | 64.9 % | 27.2 % (49 / 37) | 172 of 205 |
| Another word | 221 | 3.6 % | 4.5 % | 91.9 % (3 / 200) | |

| AUC of −LPR | Mispronounced | Correct | AUC | 95 % CI (clips resampled) |
|---|---|---|---|---|
| Content minimal pairs vs correct words | 18 | 221 | 0.979 | 0.960–0.993 |
| All clip pairs vs all correct clips | 310 | 354 | 0.936 | 0.915–0.956 |
| Simulated (words) vs correct words | 556 | 221 | 0.965 | 0.947–0.980 |
| Simulated (all) vs all correct clips | 872 | 354 | 0.947 | 0.929–0.963 |

By target sound, words only:

| Sound | Correct trials | False alarms | Not heard | Simulated | Detected | Called correct | AUC |
|---|---|---|---|---|---|---|---|
| S | 27 | 3.7 % | 0.0 % | 55 | 61.8 % | 20.0 % | 0.906 |
| Ș | 19 | 10.5 % | 31.6 % | 72 | 98.6 % | 0.0 % | 0.988 |
| Z | 18 | 11.1 % | 5.6 % | 38 | 76.3 % | 5.3 % | 0.944 |
| J | 10 | 0.0 % | 10.0 % | 37 | 89.2 % | 0.0 % | 0.997 |
| Ț | 17 | 5.9 % | 41.2 % | 100 | 70.0 % | 0.0 % | 0.937 |
| CE/CI | 16 | 6.3 % | 6.3 % | 74 | 64.9 % | 8.1 % | 0.920 |
| GE/GI | 10 | 10.0 % | 10.0 % | 44 | 72.7 % | 15.9 % | 0.852 |
| R | 63 | 0.0 % | 3.2 % | 53 | 98.1 % | 0.0 % | 1.000 |
| L | 41 | 0.0 % | 0.0 % | 83 | 95.2 % | 1.2 % | 1.000 |

Other thresholds (try again at or under `rejectLpr`, correct words and simulated substitutions in words):

| rejectLpr | False alarms | Correct words not heard | Detected (simulated) | Detected (content pairs) |
|---|---|---|---|---|
| −1 | 9.5 % | 2.7 % | 93.2 % | 100 % |
| −2 | 6.3 % | 5.9 % | 88.7 % | 94.4 % |
| **−3 (default)** | **3.6 %** | **8.6 %** | **80.6 %** | **77.8 %** |
| −4 | 2.7 % | 9.5 % | 66.7 % | 61.1 % |
| −6 | 0.9 % | 11.3 % | 41.5 % | 44.4 % |

The 8 false alarms: pagină (GE/GI heard as CI), cosmonaut (S as Z), rinocer (CE as Ț), autobuz (Z as S), clopoțel (Ț as CE), căpșună (Ș as S), orez (Z as S), șoarece (Ș as S). Some may be the clip and not the model: word-final z devoices in Romanian (autobuz, orez) and s before m is often voiced (cosmonaut). Only a listener can tell, and none was asked.

Content pairs: the 4 abstentions are car→cal (the clip's final R is barely there: „car” against its own word abstains too, LPR −2.7), cal→car, coș→cos and jar→zar, all between −3 and 0. Another word was called correct 8 times in 221 (3.6 %), for instance „lac” against „ochelari” for L: the fit gate is per label, so it is lenient with long target words.

**Robustness.** Without the margins (raw clips) the aggregates barely move (false alarms 5.0 %, abstention 6.3 %, AUC 0.971 on the content pairs, 0.970 simulated), but single verdicts do: 18 of 221 correct-word trials change verdict, „pagină” from LPR +5.1 to −10.4. Its greedy transcription turns from `p a d ʒ i n ɤ` into `p a ɪ t ɕ y n ɤ` once noise frames are added around it: the network looks at the whole utterance, and its int8 activations are quantised per run. Real recordings will move verdicts the same way.

**int8 against fp32** (same checkpoint, padded): false alarms 3.6 % against 3.6 %, abstention 8.6 % against 8.1 %, AUC 0.979 against 0.980 on the content pairs and 0.965 against 0.965 simulated.

**Against the DSP judge** on the same clips (`./gradlew :dsp:test`, `dsp/build/eval-report.md`, at 44.1 kHz): it rejects 1 of its 122 sibilant and affricate word trials (zmeu) and abstains on none; on its 117, this scorer says try again for 8 (6.8 %, all eight false alarms above) and abstains on 17 (14.5 %). With the text swapped (S↔Ș, Z↔J in words) the DSP reports the wrong place for 63 of 69 and accepts 4; this scorer says try again for 55 of 75 (each time with the right substitute), abstains on 12 and accepts 8. For sibilant place the full-band DSP is the better judge; ZIPA adds R, L, the affricates and naming the substitute.

**Latency.** 151 word clips (median 1.02 s), after 20 warm-up words, CPU only, last of three runs (the machine was shared with other work; the three runs' median totals were 32–35 ms with 1 thread, 23–26 ms with 4 and 43–51 ms with the default):

| ONNX Runtime threads | fbank | network median / 90th / max | GOP + verdict median / max | total median / 90th |
|---|---|---|---|---|
| 1 | 0.65 ms | 34.0 / 38.8 / 47.6 ms | 0.14 / 1.53 ms | 34.8 / 39.8 ms |
| 4 | 0.68 ms | 25.4 / 29.2 / 37.5 ms | 0.16 / 0.64 ms | 26.3 / 30.1 ms |
| default (one per core, 24) | 0.71 ms | 41.6 / 58.4 / 78.5 ms | 0.17 / 2.53 ms | 43.3 / 61.2 ms |

Cold model load: 2.3–2.7 s. A mid-range phone core is several times slower than this desktop core: the hub's guess is 0.2–1 s per second of audio, and only a measurement on the three test phones (PLAN §6) will tell. Use 2–4 threads; the default oversubscribes.

## What the numbers do not show

- **Children.** One adult voice, synthetic. Children's formants and sibilant spectra sit higher, their timing is slower and more variable, and ZIPA's phone error rate on young children's natural speech is 124 % (TinyVox; Lavechin et al., Interspeech 2026, via hub report E). Nothing here predicts false alarms for a 5-year-old; the PLAN's < 5 % is a criterion on children.
- **Real substitutions.** Every "mispronounced" trial is a correct adult production of another phone (the other word of a pair, or the clip against a word it does not say). A child's substitution is often in between, partial or inconsistent. Omission, I/U for L, the guttural R and the interdental S were never produced by any clip: only the unit tests on synthetic posteriors exercise them.
- **Distortions.** Lateral, interdental, addental sibilants and other distortions are not phones of the inventory, and the model cannot see their cues: it hears 16 kHz audio and its filterbank stops at 7.6 kHz, while the lateral and high-sibilant cues lie above 8 kHz. They stay with the DSP at 48 kHz.
- **Other voices, phones and rooms.** One TTS voice, clean MP3s, simulated room noise. No microphone, no reverberation, no real background, no AGC.
- **The ground truth.** The clips were taken as correct without listening; some "false alarms" may be the voice (see above), and some "correct" calls may be lucky.
- **Thresholds.** They were fixed before the run, but the only data they have met is this. The post-hoc table above shows what moving them does here, not what it would do on children.

## What data would answer the open questions

1. **Romanian children in the app's conditions** (PLAN §8, `04_cercetare_date_resurse.md` §6): 5–10 years, phones at 48 kHz without processing, each target token labelled by a logoped as correct / substituted (with what) / distorted / omitted, 20 % labelled twice. Phase A, 30–40 children × 100–200 tokens, is enough to set the two thresholds per sound for < 5 % false alarms and to estimate κ against the logoped.
2. **Adults producing the errors on purpose** before the pilot (the "simulări proprii" of `04_cercetare_date_resurse.md` §1.1: 10–20 adults on 3–5 phone models, S as Ș, R as L or I, guttural R, lateral and interdental S, omissions), recorded at 48 kHz: real articulations of every alternative, quickly and without children's data.
3. **Many adult Romanian voices with transcripts**: Common Voice ro (CC0) and FLEURS ro (CC BY 4.0), to measure the false alarms per sound across speakers and fit per-sound offsets, and to see how often ZIPA hears Romanian ș as s beyond this one voice.

## Next steps

1. Shadow mode next to the DSP (PLAN §6): log LPR, the substitute and the fit per attempt, show nothing.
2. Fusion: DSP first for S/Ș/Z/J place at 48 kHz, this scorer for R/L, the affricates, the substitute and omissions.
3. Calibration per sound and alternative from item 3, then from the pilot.
4. A better acoustic model for Romanian: fine-tune ZIPA-small on Common Voice ro, or distil a student from ZIPA-large (the ș/s and ț/s confusions are the model's, not the quantisation's).
5. Measure latency and memory on three phones before any UI waits on the scorer.
