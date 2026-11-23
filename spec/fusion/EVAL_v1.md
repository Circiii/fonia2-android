# Judge evaluation v1: the full head (ZIPA) next to v0 (2026-11-21)

**Caveat first, and it stands.** Every voice here is a synthetic adult (ElevenLabs: the prompt voice of
`content/voice`, Sarah, Roger, Lily). These numbers show that the phoneme model is wired into the judge end to end and
that it moves the verdicts the right way on this audio; they say **nothing** about accuracy on children. The error
takes are cross-tests (the same audio judged against a neighbouring target), words the voices were asked to say in
place of the target („lac” for „rac”), and crude synthetic edits. Nothing is unlocked for children:
`spec/unlock.json` stays at wave 1 = `trial`, the rest `locked`.

Run: `JudgeEvalTest` with `-Pfonia.judge.zipa=true` (see `speech/JUDGE.md`); per take with features and phone evidence
in `speech/build/judge-eval/cases.csv`, the full report (with the per-voice tables) in `report.md`.
"Detector view" = every kind unlocked, i.e. what the head would name.

## What v1 is

- **Phone evidence** (`ZipaPhoneScorer`, `:engine:phones`): the take resampled to 16 kHz (48 kHz: exact-third FIR,
  `Decimator3`), padded with 250 ms of −75 dBFS noise, ZIPA-small int8, then one CTC forward pass per candidate of the
  target's widened confusion set (plan 03 §5, `ConfusionSets`) with the rest of the prompt's G2P as context;
  `lpr.<x>` = log P(target) − log P(x) clipped to ±12 nats, plus `zipa.gop`, the fit per label and a Viterbi
  alignment. Isolated sounds are scored as the target alone, held or repeated; syllables and words through the G2P.
- **Reliability**: under 10 frames, a fit under −3 nats per label (words) or −2 (isolated, syllables), or a winning
  candidate the alignment gives no frame → `reliable = false` → the judge keeps the fast head (`phonesUnreliable`).
  89 of the 1137 scored takes with phone evidence were unreliable.
- **Full head** (`FullHead.build`, `spec/fusion/make_v1.py`): a log-linear pool of the fast head and ZIPA,
  `logit_k = α·fast_k/T − c·lpr(candidate_k)` (+ `g·zipa.gop + correctBias` on "correct"); a class without a candidate
  (weakOrShort) rides on "correct" with the fast head's own difference. Four constants shared by all nine sounds:
  **α 0.5, c 0.75, g 0.5, correctBias −0.5**, chosen by grid search (360 points) on a per-take cost (false alarm 6,
  praised error 5, wrong kind 3, abstained 1, Almost on a correct take 0.6), every voice weighing the same. Fast heads
  are the v0 ones, unchanged.
- **Leave-one-voice-out**: for each voice the constants were chosen on the other three and that voice judged with
  them. The held-out folds chose (0.5, 0.75, 0.5, −1) for the prompt voice, (0.25, 0.5, 0.5, −1) for Lily,
  (0.5, 0.75, 0.5, +1) for Roger, (0.5, 0.75, 0.5, −0.5) for Sarah: the surface is flat, α and c are stable.
- Also new in the judge: the ZIPA word gate (fit < −3) applies to words and sentences only; kinds the DSP blocked only
  for want of evidence (Z/J/GE omission and stopping when the frication was not found, R's kinds outside V-R-V) are
  unblocked by a reliable full head; J and Z accept [dʒ]/[dz] (ZIPA hears a stop onset on good J and Z of all four
  voices, and "J said as GE" is not one of J's kinds) and, word-finally, the voiceless sound (plan 03 §3.10).

## Audio

| Set | What | Takes |
|---|---|---|
| A. ElevenLabs WAV 48 kHz | Sarah S and Ș lists, Roger S list, split on the sibilants (as v0; `roger_sh.wav` did not split) | 140 |
| W. ElevenLabs one word per file (new, `tmp/test_audio/words_v1/`) | 52 words × Sarah, Roger, Lily (MP3 44.1 kHz, ≈ 650 characters): R, L, GE/GI, CE/CI, Ț, Z, J in each position, and substituted words („lac”, „iac”, „ac” for „rac”; „cer”, „zer”, „jer” for „ger”; „țer”, „șer”, „ter” for „cer”; „sară”, „tară”, „ceară” for „țară”; „jare”, „dare” for „zare”; „zoc”, „doc” for „joc”…) | 320 |
| B. Prompt voice MP3 (`content/voice`) | 268 words, syllables, isolated sounds (as v0) | 728 |

1188 judged takes, 1149 scored (the rest are capture-gate checks); 493 correct takes, 656 error takes.

## Correct and error takes, detector view, all sets

v0 = fast head on every take; v1 in-sample = the shipped constants (chosen on these takes); v1 held out = each voice
judged with the constants chosen on the other voices (the honest number).

| | v0 fast | v1 in-sample | v1 held out |
|---|---|---|---|
| correct takes accepted (Correct) | 258 / 493 (52 %) | 389 (79 %) | 379 (77 %) |
| Almost on a correct take | 52 (11 %) | 42 (9 %) | 45 (9 %) |
| abstained on a correct take (Unsure) | 162 (33 %) | 40 (8 %) | 45 (9 %) |
| Retake on a correct take | 1 | 9 | 9 |
| **false alarm** (a named error on a correct take) | **20 (4.1 %)** | **13 (2.6 %)** | **15 (3.0 %)** |
| error takes: right kind named | 251 / 656 (38 %) | 403 (61 %) | 401 (61 %) |
| error takes: wrong kind named | 23 (3.5 %) | 45 (6.9 %) | 46 (7.0 %) |
| **error takes praised as Correct** | **51 (7.8 %)** | **37 (5.6 %)** | **40 (6.1 %)** |
| error takes abstained (Almost / Unsure) | 320 (49 %) | 144 (22 %) | 142 (22 %) |

The wrong-kind increase is almost all one synthetic edit: an S or Ș whose frication was cut out ("omission") is now
named `stopping` (S 21, Ș 9): ZIPA hears the closure the cut leaves as [t]. On the error takes that are real
substitutions (cross-tests and said words: 377 takes) v1 names the right kind on 317 (v0: 176) and praises 5 (v0: 31);
the 5 are three „fuji” for „fugi” (the voices said [dʒ], the English reading: the take is not an error) and two Ș/S
place cross-tests. On the synthetic edits (279 takes: cut, −24 dB, burst) v1 praises 32 (v0 20): ZIPA still hears an
S in an S at −24 dB or with a fade-cut, which is right for a phone model and why weakOrShort stays with the DSP.

## Per sound (correct takes: accepted / false alarm; error takes: right / praised)

| Sound | v0 accepted | v1 held out accepted | v0 false alarm | v1 held out false alarm | v0 errors right | v1 held out right | v0 praised | v1 held out praised |
|---|---|---|---|---|---|---|---|---|
| S | 47/56 (84 %) | 53 (95 %) | 3 | 1 | 75/204 (37 %) | 83 (41 %) | 9 | 23 |
| Ș | 38/39 (97 %) | 31 (79 %) | 0 | 2 | 78/170 (46 %) | 92 (54 %) | 18 | 13 |
| Z | 26/45 (58 %) | 29 (64 %) | 3 | 2 | 39/51 (76 %) | 46 (90 %) | 1 | 1 |
| J | 16/34 (47 %) | 18 (53 %) | 5 | 2 | 29/34 (85 %) | 30 (88 %) | 2 | 0 |
| Ț | 26/38 (68 %) | 29 (76 %) | 3 | 0 | 18/68 (26 %) | 56 (82 %) | 9 | 1 |
| CE | 19/40 (48 %) | 28 (70 %) | 2 | 2 | 0/9 | 5 (56 %) | 2 | 0 |
| GE | 7/32 (22 %) | 14 (44 %) | 3 | 4 | 4/15 (27 %) | 3 (20 %) | 4 | 2 |
| R | 79/140 (56 %) | 116 (83 %) | 1 | 2 | 4/81 (5 %) | 71 (88 %) | 6 | 0 |
| L | 0/69 (0 %) | 61 (88 %) | 0 | 0 | 4/24 (17 %) | 15 (63 %) | 0 | 0 |

- **L can now come out Correct** (61/69 held out, no false alarm); L→I/U named 9/9, L→R 7/12 (in-sample).
- **R**: R outside vowel–R–vowel is judged (Unsure 58 → 9); R→L named on 64/69 cross-tests (v0 4), R→I 9/9 said
  words. R omission („ac” for „rac”) and L omission are still not named (0/3 each): the word gate or Unsure.
- **Ț, CE**: deaffrication (S for Ț) 44/56 named (v0 17); CE stopping/deaffrication/place said words 5/9 (v0 0).
- **GE** stays weak: ZIPA confuses [dʒ]/[ʒ]/[dz] on these voices; 4 false alarms (pagină heard [tʃ], the syllable
  „giu” with a poor fit, „agea”), abstains on 11/32 correct takes.
- **Ș**: accepted drops from 97 % to 79 % (held out): on the prompt voice ZIPA hears [s] on some good Ș („șapcă”,
  „cămașă”, „ușu”) and the pool turns them into Almost; the two false alarms are „căpșună” (heard „căpsună”, a common
  reading) and one syllable. The fast head alone is better here; a per-sound α is the next step once real data exists.
- **J/Z** false alarms 8 → 4: the [dʒ]/[dz] decision and word-final voicing not judged.

Per-voice tables (v0 and v1 held out for each voice) are in `report.md`.

## Shipped policy (spec/unlock.json as committed: nothing unlocked)

Correct takes — v0: Correct 258, Almost 52, Unsure 182, Retake 1; v1: Correct 389, Almost 42, Unsure 53, Retake 9.
Error takes — v0: Correct 51 (7.8 % wrong praise), Almost 95, Unsure 499, Retake 11; v1: Correct 37 (5.6 %), Almost 46,
Unsure 546, Retake 27. No error is named to the child (wave 1 is `trial`); the adult sees them "în probă".

The Retakes are new: ZIPA's word gate (fit < −3 per label, plan 03 §8) sends back 8 correct one-word takes of the
synthetic voices (Sarah „cer”, „ger”, „zer”, „șoc”…) as "another word". A retake never counts, but the threshold
needs checking on children's words.

## Timing

| Where | What | Time |
|---|---|---|
| This PC (desktop JVM, ORT 1.31.1, 2 threads) | resample + pad + fbank + ZIPA int8, per clip (714 clips, 0.5–2 s) | median 55 ms, p95 72 ms, max 104 ms |
| This PC | GOP over the confusion set + alignment, per request | median 0.2 ms, p95 0.5 ms |
| Emulator `emulator-5554` (x86_64, 4 cores, onnxruntime-android 1.31.1, 2 threads) | `ZipaHost.score`, 1.2 s take at 48 kHz, 20 takes | p50 52 ms, p95 62 ms, max 108 ms (resample 2 ms, model 44 ms, scoring 4 ms) |
| Emulator | cold session (mmap + create) / warm-up | 1.1 s / 1.1 s; PSS 198 → 283 MB warm |

The emulator runs on the PC's x86 cores, so it is not a phone. A mid arm64 phone is unmeasured; with the 600 ms budget
(N6) and 60 ms here, a 5–8× slower phone still fits. The parity check on the emulator gives the authors' published
transcription of their `sample.wav`, token for token, as on the PC.

## Standing caveat

Synthetic adult voices prove the chain, not accuracy on children. The constants were chosen on these same voices
(held-out numbers above are the honest ones, and even they share the TTS engine). Before any kind leaves `trial`:
real child recordings (B1/B3), E6 training per speaker-held-out folds with the `lpr.*` inputs, ChildNorm (E4), and the
unlock report with a logoped's signature.
