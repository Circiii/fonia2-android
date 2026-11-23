# Judge evaluation v0 (2026-11-21)

**Caveat first.** Every voice here is a synthetic adult (ElevenLabs). These numbers show that the chain P0–P5 works
end to end and that the cues point the right way; they say **nothing** about accuracy on children. The coefficients
(`spec/fusion/*.json`, `"source": "hand-set v0, not trained"`) were set by hand while looking at these same takes, so
the numbers are also optimistic for this audio. The error takes are cross-tests (the same audio judged against a
neighbouring target) and synthetic edits, not real child errors.

Run: `JudgeEvalTest` (opt-in, see `speech/JUDGE.md`); full per-take table with features in
`speech/build/judge-eval/cases.csv`. "Detector" = every kind unlocked (what the head would name); "shipped" =
`spec/unlock.json` as committed (wave 1 in trial, nothing unlocked).

## Audio

| Set | What | Takes |
|---|---|---|
| A. ElevenLabs WAV, 48 kHz | Sarah and Roger S list, Sarah Ș list; split on the sibilants (the words are run together, silence does not split them). `roger_sh.wav` did not split, `sarah_rest.wav` not used (R/L words have no anchor) | 154 |
| B. Prompt voices, MP3 44.1 kHz (`content/voice`) | 268 single words, syllables and isolated sounds (`sanie`, `asa`, `ssssss`…), one voice | 728 |

Cases per word: judged as its own sound (expected `correct`); S → target Ș (`placeSwap`, heard s), Ș → S; S → Z and
Ș → J (`devoicing`; word-final excluded, Romanian devoices there); S → Ț (`deaffrication`); L → R (`rToL`); on S and Ș
words: frication cut (`omission`), −24 dB (`weakOrShort`), replaced by closure + 4 ms burst + 15 ms aspiration
(`stopping`). Set A also: silence, white noise, clipping (×30), ×0.003, first 80 ms only, noise at about 0 dB, and
the same voice declared as a child.

## Correct takes (detector view)

| Target | n | Correct | Almost | Unsure | Retake | Named (false alarm) |
|---|---|---|---|---|---|---|
| S (A) | 14 | 8 | 1 | 3 | 0 | 2 |
| Ș (A) | 6 | 6 | 0 | 0 | 0 | 0 |
| R (A) | 6 | 4 | 0 | 2 | 0 | 0 |
| S (B) | 36 | 35 | 0 | 0 | 0 | 1 |
| Ș (B) | 30 | 29 | 1 | 0 | 0 | 0 |
| Z (B) | 33 | 20 | 9 | 2 | 0 | 2 |
| J (B) | 25 | 12 | 5 | 5 | 0 | 3 |
| Ț (B) | 29 | 19 | 5 | 2 | 0 | 3 |
| CE (B) | 25 | 8 | 9 | 6 | 1 | 1 |
| GE (B) | 23 | 4 | 7 | 9 | 0 | 3 |
| R (B) | 71 | 48 | 0 | 22 | 0 | 1 |
| L (B) | 48 | 0 | 0 | 48 | 0 | 0 |
| **All** | **346** | **193 (56 %)** | **37 (11 %)** | **99 (29 %)** | **1** | **16 (4.6 %)** |

R's Unsure are R outside vowel–R–vowel (plan 03 §3.8: the DSP cannot judge it, ZIPA must). L is always Unsure
without the phoneme model, by design (§3.9). The S false alarm in B is the syllable `si`, measured Ș-like (sNess 0.05):
likely the TTS said "și" (Q9: ElevenLabs misreads syllables). In A, two S before o (`Soare`) read Ș-like: lip
rounding lowers the S, the group prior does not know the voice; calibration (anchors) is the fix.

## Error takes (detector view)

| Expected | Set | n | Right kind named | Wrong kind | Correct (wrong praise) | Almost | Unsure | Retake |
|---|---|---|---|---|---|---|---|---|
| placeSwap, Ș said for S | A | 6 | 6 | 0 | 0 | 0 | 0 | 0 |
| placeSwap, S said for Ș | A | 14 | 5 | 0 | 5 | 3 | 1 | 0 |
| placeSwap, Ș said for S | B | 30 | 28 | 0 | 0 | 2 | 0 | 0 |
| placeSwap, S said for Ș | B | 36 | 33 | 0 | 2 | 1 | 0 | 0 |
| devoicing (S for Z / Ș for J) | A | 14 | 11 | 0 | 0 | 1 | 2 | 0 |
| devoicing (S for Z / Ș for J) | B | 47 | 41 | 0 | 2 | 3 | 1 | 0 |
| deaffrication (S for Ț) | A | 14 | 3 | 0 | 0 | 0 | 11 | 0 |
| deaffrication (S for Ț) | B | 36 | 14 | 1 | 3 | 11 | 7 | 0 |
| omission (frication cut) | A | 20 | 11 | 2 | 0 | 0 | 6 | 1 |
| omission (frication cut) | B | 64 | 28 | 0 | 7 | 4 | 21 | 4 |
| stopping (burst for S/Ș) | A | 20 | 1 | 2 | 0 | 5 | 12 | 0 |
| stopping (burst for S/Ș) | B | 64 | 15 | 4 | 5 | 13 | 26 | 1 |
| weakOrShort (−24 dB) | A | 20 | 4 | 2 | 1 | 8 | 4 | 1 |
| weakOrShort (−24 dB) | B | 64 | 8 | 10 | 7 | 24 | 13 | 2 |
| rToL (L said for R) | B | 48 | 3 | 0 | 5 | 1 | 39 | 0 |

Totals: A 108 error takes, right kind named 38 % (41), wrong kind 6 % (6), praised as Correct 6 % (6), abstained
(Almost or Unsure) 49 % (53); B 389, right 44 % (170), wrong 4 % (15), praised 8 % (31), abstained 43 % (166). The most likely error class is the right
one on 79 % (A) and 77 % (B), named or not. Every named error's `heardAs` was right (25/25 A, 119/119 B).

## Capture gates (set A, both voices)

Silence → `noSpeech`, white noise → `noSpeech`, clipping → `clipped`, ×0.003 → `tooQuiet`, first 80 ms → `tooShort`,
noise at about 0 dB → `noisy`: 12/12 retakes. Roger declared a child → `adultVoice`; **Sarah declared a child →
`adultVoice` too** (her median F0 is under 0.7 × the 260 Hz population default: use the child's own `childF0Hz`). No
correct take of either set was sent back except one CE in B.

## Abstention and the shipped policy

- Unsure on 28 % of all non-retake takes (B: 201/728; A: 42/140), Almost on 16 % (B: 114/728).
- With `spec/unlock.json` as committed, **no error is named to the child** (wave 1 is `trial`); those takes become
  `Almost`/`Unsure` with an `adultNote` "în probă". On B's correct takes: Correct 175, Almost 36, Unsure 108,
  Retake 1. On B's error takes: Unsure 292, Almost 59, Retake 7, **Correct 31 (8 % wrong praise)**.
- P0–P3 time on this PC: median 11 ms (A, 48 kHz) and 18 ms (B), p95 17 / 22 ms, max 33 ms. Not measured on a phone.

## What this says

- Strong on this audio: S↔Ș place (with ≥ 11 kHz bandwidth), devoicing of Z/J, the capture gates, `heardAs`.
- Usable, abstains a lot: omission, deaffrication, R correct (V-R-V only).
- Weak: stopping and weak/short on synthetic edits (mostly Unsure/Almost), GE and CE correct takes (many Almost),
  R→L without ZIPA (the dip cue alone names only 3/48, correctly abstaining on the rest).
- Before any kind leaves `trial`: real child recordings, coefficients fitted per speaker-held-out folds (E6), the
  `ChildNorm` z-scores (E4), and the ZIPA `full` head.
