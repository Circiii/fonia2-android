# The sound judge (`tech.fonia.judge`, in `:engine:judge`)

Packages E2 + E3 + E5 + E8 of `docs/v2/03_MOTORUL_DE_SUNET.md`. One take in (a `Clip` + what was asked), a `TakeResult` out:
was the target sound right, and if not, **what** was wrong (`ErrorKind`), with abstention (`Unsure`, `Retake`) when
it is not sure. Pure Kotlin/JVM, no text: the session layer maps the result to animations and lines through
`spec/feedback.json`. First cut, hand-set coefficients (`"source": "hand-set v0, not trained"`).

## Public API

```kotlin
val spec  = JudgeSpec.load(File("spec"))                       // or JudgeSpec.fromStrings(population, fusionTexts, unlock, feedback)
val judge = TakeJudge(spec)                                    // thread-safe, no mutable state; DSPThresholds.v1 by default
val req   = JudgeRequest(SoundId.S, Level.Word, "sare", Position.Initial, TalkerGroup.Child, calibration, childF0Hz)
val m: Measured   = judge.measure(req, clip)                   // P0–P3, ≈ 20 ms on a PC for a 1–2 s clip, no model
val r: TakeResult = judge.decide(m, phones = null, runtime = RuntimeContext(adultPresent = true, calibrationTier = 1))
// later, if ZIPA answered in time:  judge.decide(m, phones)   // phones = ZipaPhoneScorer / ZipaHost (:platform:ml) output
judge.judge(req, clip)                                         // measure + decide in one call
Feedback(spec.feedback).forKind(SoundId.S, ErrorKind.PlaceSwap) // animation, instructionKey, child line (≤ 8 words)
```

`TakeResult`: `verdict` (`Verdict` from `:core:model`, incl. `NamedError(kind, heardAs)`), `confidence`, `retakeReason`
(`RetakeReason`: clipped, tooQuiet, tooShort, noSpeech, noisy, dropped, adultVoice, otherWord), `head`
(`Fast`/`Full`/`Gate`), `probabilities` (class → p), `topError` + `topErrorProbability` (always, for the trace),
`withheldKind` (what the detector would have named without `spec/unlock.json`), `adultNote` (kind "în probă" for the
adult view), `evidence` (`JudgeEvidence`: every feature by name, the parity-locked `SoundOutcome`, `CaptureReport`,
segments, blocked kinds, notes), `versions` (`SpecVersions` of every data file), `fastFinal`, `micros` per stage.

Ports for the models that do not run on the JVM: `PhoneScorerPort` (ZIPA → `PhoneEvidence`: `lpr` keyed by IPA
label, `gop`, `fitPerLabel`, `frames`, `reliable`, `targetSpan`, `notes`) and `WordCheckerPort` (Whisper →
`WordEvidence(otherWord)`). Absent, or `reliable = false` → the fast head (note `phonesUnreliable`).

## The full head (E3, v1)

- **Implementation of the port**: `tech.fonia.phones.ZipaPhoneScorer` (`:engine:phones`, pure JVM; the dependency is
  phones → judge) and, on the phone, `tech.fonia.ml.ZipaHost` (`:platform:ml`, `ml/README.md`: model in the APK,
  `score(clip, request)` with the 600 ms budget).
- **Per take**: `to16k(clip)` (48 kHz: `Decimator3`, exact third; other rates `SpeechResampler`) → 250 ms of −75 dBFS
  padding → ZIPA → restricted GOP over `ConfusionSets.of(target)` (plan 03 §5 widened sets, keys = `lpr.<key>`
  inputs) with the prompt's G2P as context. Isolated: the target alone, held or repeated 1–4×. `Position` picks the
  occurrence; several → the one least likely correct. `lpr` clipped ±12 nats; `zipa.gop`, `zipa.fit` are inputs too.
- **Accepted realisations** (`ConfusionSets.accepted`): R trill or tap; J/Z also [dʒ]/[dz] (ZIPA hears a stop onset on
  good J/Z); word-final J/Z also voiceless (plan 03 §3.10).
- **Reliability**: ≥ 10 frames, fit ≥ −3 per label (words) / −2 (isolated, syllables), the winner aligned (Viterbi,
  `CtcAligner`). A phone interval more than 60 ms from every DSP segment adds the note `zipaDspApart` (trace only).
- **Decision changes with a reliable full head**: `Measured.pending` (Z/J/GE omission/stopping when the frication was
  not found, R's kinds outside V-R-V) is lifted from `blocked`; the word gate on the fit applies to words and sentences
  only.
- **Coefficients**: `FullHead.build(spec, spec.fullFrom)`: `α·fast/T − c·lpr(candidate of class)` (+ `g·gop +
  correctBias` on correct; classes without a candidate ride on correct). `spec/fusion/<s>.json` carry `phoneMap`
  (class → candidate), `fullFrom` and the explicit weights; `python -I spec/fusion/make_v1.py` writes them and
  `JudgeSpecTests` checks the two agree. Constants chosen by leave-one-voice-out on synthetic voices
  (`spec/fusion/EVAL_v1.md`), not trained on children.

## How the app calls it

```kotlin
val clip = director.closeMic()                                       // AudioDirector, :engine:live
val m = withContext(Dispatchers.Default) { judge.measure(request, clip) }
val fast = judge.decide(m, runtime = runtime)
if (fast.head == Head.Gate || fast.fastFinal || phoneScorer == null) emit(fast)   // ≤ 100 ms after AttemptEnded
else emit(zipaHost.score(clip, request)?.let { judge.decide(m, it, runtime = runtime) } ?: fast)   // null after 600 ms
```

- `Retake` is not an attempt (no score, no progression). `Unsure` asks the adult; the adult's thumb decides the take.
- `NamedError` reaches the child only when `spec/unlock.json` says `unlocked` and its conditions hold (with
  `evidenceLevel: "imitations"`: adult present, calibrated child, `tauErr + 0.07`, Q3). Today **nothing is unlocked**:
  wave 1 is `trial`, so those kinds come back as `adultNote(inTrial = true)` with `Almost`/`Unsure`.
- Save `evidence` + `versions` + `probabilities` with each attempt (the `JudgeTrace` of plan 03 §1). No audio.
- `RuntimeContext.childOkOffset` / `childErrOffset` carry the adult-disagreement offsets of plan 03 §7.

## The chain

| Stage | Where | What |
|---|---|---|
| P0 capture gate | `TakeJudge.measure` | dropped samples, clipping, flat-and-loud = noisy, flat-and-faint = noSpeech, faint = tooQuiet, < 0.12 s = tooShort, no voice, adult F0 (< 0.7 × child F0) on a child target, 4–8 kHz SNR of the frication and 4–8 kHz range of the clip < 15 dB = noisy |
| P1 word gate | `measure` / `decide` | word level: speech 0.5×–2.5× of `0.09 s × phonemes + 0.15 s`; ZIPA `fitPerLabel < −3` (≥ 10 frames); Whisper `otherWord` |
| P2 target | `Measurer.sibilants` / `rhotic` | `alignFricatives` (sibilants, affricates), by `Position`; R/L slots, taps/trills, nuclei |
| P3 features | `Measurements.kt`, `:engine:dsp` E2 extractors | see `Features` (names = fusion inputs) |
| P4 head | `FusionHead` | multinomial logistic per sound, z-scored clipped inputs, NaN masked, temperature, optional isotonic |
| P5 decision | `DecisionPolicy`, `UnlockView` | `tauOk(Fast)`, `tauErr(+fast extra)`, `delta`, coarse-to-fine `coarseMax`, blocked kinds, unlock |

New DSP extractors (additive, `:engine:dsp`): `SpectralShape.kt` (`segmentSpectrum` multitaper, `spectralMoments`,
`bandFlatness`, `relativeLevelDb`, `lateralCues`, `uvularCues`), `TimingCues.kt` (`Biquad`, `fineEnvelope`,
`TimingEnvelopes`, `affricateCues` gap/burst/rise/duration, `onsetFricationMs`, `dipBetween`), `VoicingCues.kt`
(`voicingCues` voicedFrac + HNR). Tests: `SegmentFeatureTests` on synthetic signals.

## Data files (`spec/`)

| File | What | Who changes it |
|---|---|---|
| `thresholds/population.json` | capture, word-gate and measurement values | by hand, with a version bump |
| `fusion/<s,sh,z,j,ts,ce,ge,r,l>.json` | classes, levels, `heardAs`, inputs (mean/scale/clip), `phoneMap`, `fullFrom`, `fast` and `full` heads, decision thresholds | today `python -I spec/fusion/make_v1.py` (fast = hand-set v0 from `make_v0.py`, full = `FullHead.build`); later E6 training writes them |
| `models/manifest.json`, `models/NOTICE` | the models in the APK (bytes, SHA-256, licence, attribution) | with the model; `:platform:ml` copies it into assets |
| `unlock.json` | per (sound, kind): wave, state `locked`/`trial`/`unlocked`, `evidenceLevel`, requirements, evidence; rules | only with the E6 report, signed (plan 03 §7); `JudgeSpecTests` fails on an `unlocked` entry without evidence |
| `feedback.json` | per (sound, kind): animation (chapter 05 §5.2), variant, instruction key, child line, adult line; verdict and retake lines | chapter 06 owns the wording; tests check ≤ 8 words, no forbidden words, comma-below Ș/Ț |

## Adding a sound or an error kind

1. Name: `SoundId` / `ErrorKind` in `:core:model` (a contract with iOS; another module's owner).
2. Measurement: `traits()` and a `Measurer` method if the family is new; new features get a `Features` name, and
   `Features.VERSION` is bumped when a definition changes.
3. Coefficients: add the class (and inputs) in `make_v0.py`, regenerate; `levels`, `heardAs`, `tauErr` for the kind.
4. `unlock.json` entry (`locked` to start) and a `feedback.json` line; `JudgeSpecTests` checks both exist.
5. A synthetic test in `TakeJudgeTests`, a cross-test in `JudgeEvalTest`.

## Evaluation (opt-in, local audio only)

```bash
./gradlew :engine:judge:test --tests "tech.fonia.judge.JudgeEvalTest" \
  -Pfonia.judge.audio=C:/Users/circi/Desktop/HSIC/tmp/test_audio -Pfonia.judge.voice=<main checkout>/content/voice
```

Add `-Pfonia.judge.zipa=true` for the full head (ZIPA from `~/.gradle/fonia-models`; the test JVM becomes a JDK copy
with the system VC++ runtime, as in `:engine:phones`), and put one-word clips in `<audio>/words_v1/<voice>/<word>.mp3`
for the substitution set. Writes `speech/build/judge-eval/report.md` (with v0/v1 tables and the leave-one-voice-out
grid) and `cases.csv` (every take with its features and phone evidence). Summaries: `spec/fusion/EVAL_v0.md`,
`spec/fusion/EVAL_v1.md`. A worktree without the content submodule: `-Pfonia.content=<checkout>/content`.

## Pitfalls

- **Adult and synthetic voices only.** The coefficients were tuned by hand on them; they say nothing about children.
- `sNess` follows the group prior until `TalkerCalibration` has anchors; coarticulated S (before o/u) falls Ș-ward.
- Absolute floors lie on clean or coded audio (TTS floors sit near digital silence): v0 makes gap and voicing limits
  relative to the take's loud part (`peak − 40 dB`, `voice peak − 20 dB`) and uses `relLevelDb` (vs the vowel), not
  `aboveFloorDb`, for weakness. Deviations from plan 03 §4, marked in the code.
- The DSP detector misses weak voiced frication: for Z, J, GE "not found" blocks `Omission`/`Stopping` (ZIPA decides).
- R: outside V-R-V, or when the syllable nuclei do not line up, R's error kinds are blocked (Unsure) unless a reliable
  full head lifts them; L has no DSP detector for "correct" (always Unsure without ZIPA; with ZIPA it can be Correct).
- Full head on these voices: weakOrShort and fade-cut omissions stay a DSP matter (ZIPA hears an S in a weak S); GE is
  weak ([dʒ]/[ʒ]/[dz] confusions); good Ș sometimes heard [s] on the prompt voice (Almost); the ZIPA word gate sends
  back a few correct one-word synthetic takes (`EVAL_v1.md`).
- The adult-voice gate uses `childF0Hz` (population 260 Hz): a low female voice judged as `Child` is sent back.
- `measure` allocates (spectra, filters on the whole clip); call it off the audio thread.
- A missing `full` head or missing `lpr.*` inputs fall back silently to masking; check `head` in the result.
