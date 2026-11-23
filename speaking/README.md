# :engine:speaking + :feature:speaking — „Tu, fără ăăă” (G11) and the prosodic transcript (G12)

Pure Kotlin engine `tech.fonia.speaking` (depends on `:engine:dsp`, `:engine:judge`, `:engine:live`, `:core:model`) and
Compose screens `tech.fonia.feature.speaking` (no `:platform:*`). Everything runs on the device; nothing is uploaded or
stored unless the screen's „Păstrează” callback does it.

## Public API (engine)

```kotlin
val pipeline = SpeakingPipeline(transcriber = whisperOrNull)       // RO, verbatim; null = sound only
val analysis = pipeline.analyze(clip)                               // suspend, CPU-bound: call on Dispatchers.Default
analysis.spans          // List<FlaggedSpan>: HESITATION / MARKER / REPETITION / FALSE_START / LENGTHENING, cut + unsure
analysis.transcript     // ProsodicTranscript: words (loudness, pitchSt, movement, rate, pauseBefore, emphasis, kind) + summary
analysis.headline()     // „3 umpluturi · 1 pornire falsă · 1 pauză de 2,1 s”
val clean = pipeline.clean(clip, analysis, cuts = analysis.defaultCuts)  // CleanResult: samples, edits, timeMap, stats
clean.timeMap.toOld(newSeconds) / toNew(oldSeconds)                  // the transcript follows the cleaned playback
SpeakingText.cleaned(clean.stats)                                    // „Ai scos 4 umpluturi. 6,2 secunde mai scurt.”
CutQuality.check(clip.samples, clean, analysis.frames.speechMedianDb) // objective join checks (click, quiet edges, length)
```

Lower level: `SpeakingAnalyzer`, `HesitationDetector` (sound), `LexicalDetector` (transcript), `CleanCut.plan/apply`,
`SpeechFrames`. Test helpers in main: `FakeSpeakingAudio`, `SyntheticVoice`, `SpeakingFixtures`.

## What `:app` implements

1. **`SpeakingAudio`** (in `tech.fonia.speaking`) on `OboeAudioDirector` / `PlaybackEngine`:
   `record(maxSeconds, onLevel)` (mono, capture rate, no noise reduction; `onLevel(level 0–1, vadSpeaking)` ~30–100 Hz
   from any thread; returns on `stopRecording()`/max/cancel), `play(samples, rate, fromSeconds)` (suspends to the end
   or `stopPlayback()`), `playbackSeconds()` (what is heard now, latency included; NaN when idle). FxGate rules apply.
2. **`Transcriber`** = the existing whisper.cpp port (`tech.fonia.speech.Transcriber`); pass null when the model is
   absent. The pipeline resamples to 16 kHz and asks `verbatim = true`.
3. **Navigation**: `NO-UM` → `NoUmScreen(rememberNoUmState(audio, transcriber, topic, onKeep))`,
   `MINUTE` → `MinuteScreen(rememberMinuteState(audio, transcriber, topics = speechPrompts, startRung, onSelfView))`.
   Wrap in `FoniaTheme(shell = FoniaShell.Grown)`. `onDone` leaves the screen; `onKeep(original, clean)` is the only
   way a take is persisted. Mic permission must be granted before the screen opens.

## Data

`spec/thresholds/speaking.json` (version `speaking-1`, bundled in the jar at the same path; save the version with each
take): analysis, hesitation detector, pauses (> 1,2 s → ~0,6 s), cut (crossfades 12/20 ms, guards 25/60/10 ms, cut
line 0,6, sure line 0,8), prosody, quality, and the Romanian filler lexicon with its context rules. Code defaults
mirror it (`SpeakingTextTest` fails on drift).

## How the cut works

Spans → shrink against protected words (recognised words ± guard; without a transcript, active speech ± guard) →
merge cuts with nothing kept between → cap the silence a cut joins (~0,4 s) → long pauses lose their middle (room tone
stays) → each edge snaps to the quietest point within 25 ms, then to a zero crossing → equal-power crossfade. Output is
exactly `original − Σ removed` samples; `TimeMap` maps both ways.

## Pitfalls

- Whisper's word timings are ±50 ms and it stretches words over fillers: the analyzer gives the sound's bounds to
  fillers, trims swallowing words, and moves boundaries next to cut words onto real level dips only (≥ 15 dB).
- A recognised „ăăă” with no steady sound near it is offered (dotted) but not cut: its timing is often a word off.
- Without a transcript only sustained sounds are found; a long legitimate vowel („copiii”) after a stop closure can
  pass for a pause-bounded „mmm” (seen once in the evaluation).
- Thresholds were tuned on two synthetic voices only (`RealAudioEvaluationTest`, opt-in by file presence; outputs in
  `HSIC/output/fara_aaa/`). Retune on real Romanian takes before G11's 50-clip check.
- The transcript draws ~150 `StickerText` words; playback is read in `graphicsLayer`/draw blocks through
  `PlaybackClock` (one frame loop), so following playback never recomposes the words.
