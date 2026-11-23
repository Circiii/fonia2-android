# `:dsp` parity with iOS `FoniaDSP`

Port of `Packages/FoniaDSP` (21 Swift files) to `tech.fonia.dsp`: one Kotlin file per Swift file, same type and function names, same constants (`DSPThresholds` value for value) and the same order of operations, in `Float` wherever Swift uses `Float`. Three support files stand in for what Apple gives the Swift code: `Accelerate.kt` (the vDSP reductions), `SwiftSupport.kt` (Swift rounding, `min`/`max`, `log2`, `Float.pi`, `Character` and `String` semantics) and `Json.kt` (Foundation's JSON). No dependencies.

## Tests

All 193 Swift test functions (218 cases with their arguments) are ported one to one with the same inputs, expected values and tolerances, in files and classes with the Swift names. `NumericsParityTests` (6 functions, 10 cases) is new: it pins `Float.pi`, Swift rounding, `log2`, the FFT against a direct DFT and the per-rate sizes of inventory §4.19. `./gradlew :dsp:test` runs 228 cases, all passing.

`RealSpeechEvalTests` writes `dsp/build/eval-report.md` (iOS writes `Tests/eval-report.md` into its sources). Compared with iOS's committed report, every figure is identical (bandwidths 12834/14298/15612 Hz, the calibrated boundary 7687 Hz, 6619 Hz, 13.8 dB, every table row, the swap, omission, false tap and false trill lists, 109/14/32 syllables, live agreement 21/22 and 15/15) except two lines:

| Line | iOS | Android | Why |
|---|---|---|---|
| Lowest per-word median F0 | 97 Hz | 96 Hz | The word `sap` has 17 voiced frames; one of them changes rank between the two decodes (95.85 Hz here). |
| 16-20 kHz energy relative to 5-11 kHz in frication | -78.0 dB | -100.8 dB | Apple's MP3 decoder leaves more energy above the encoder's lowpass than ffmpeg's. A single-precision FFT gives the same -100 dB as the port's, so this is the decoder, not vDSP precision. |

Three more lines list the same outcome counts in a different order: Swift sorts a `Dictionary` whose order is random per process, so ties have no fixed order there.

## What could not be identical

| What | Why | What the port does |
|---|---|---|
| FFT (`vDSP_DFT_zrop`) | vDSP's algorithm and rounding are not published. | A radix-2 FFT of N/2 complex points on the even/odd-packed input, then the real-input split, in Double. Output follows vDSP's packed format (twice the DFT, Nyquist in `imag[0]`, dropped), then `zvmags` and the `1/(4N²)` scale in Float, so power is \|X\|²/N². Checked: A²/4 for a bin-centred sine, 1/N² for an impulse, a direct DFT within 1e-6. |
| `vDSP_sve`, `dotpr`, `svesq`, `rmsqv`, `desamp`, `conv` | vDSP sums in SIMD lanes, in an order a loop cannot reproduce. | Accumulate in Double and round once to Float, the closest to the exact sum vDSP approximates. Swift's own scalar loops (running sums, prefix sums, `reduce`) stay sequential Float, as in Swift. |
| `log10f`, `expf`, `sinf`, `cosf`, `powf`, `log2f` | Darwin libm is not on the JVM. | Computed in Double with `java.lang.Math` and rounded to Float: can differ by one ulp in rare cases. `log2` is exact on powers of two, as Darwin's is, so `ceil(log2(1024))` stays 10. |
| Swift value types | Kotlin has none. | Thresholds, summaries and results are immutable data classes, changed with `copy` (the Swift tests' `var x = …; x.field = …` became `copy`). The live path cannot allocate a result per hop, so `FrameFeatures`, `LiveFrame` and the internal `PitchEstimate`, `TrillResult` and `FricationFrame` are mutable and filled in place; their `silent`/`none` values are getters that return a new instance. |
| `LiveAnalyzer.process(_:) -> LiveFrame` | Returning a new object allocates on the JVM. | `process(samples, offset, count, into)` writes the reading into the caller's `LiveFrame` and allocates nothing; `process(samples, offset, count)` returns a new `LiveFrame`, which is what the Swift value return means. Pointers became `(FloatArray, offset)` pairs; `FloatBuffer` is a `FloatArray` alias (nothing here needs 16-byte alignment). |
| Allocation check | libmalloc's `malloc_logger` does not exist on the JVM. | `ThreadMXBean.getCurrentThreadAllocatedBytes` on the current thread, with the same sanity allocation first. The JIT can defer one-time linking (constants, call sites) to a later pass, so the test warms up until a whole pass allocates nothing (at most 10 passes), then requires 0 bytes. Run interpreted (`-Xint`), every pass after the first allocates 0 bytes. |
| 5 ms hop budget | Swift is compiled ahead of time. | The timing test warms the JIT up on a separate analyzer first. Average here: 0.02 to 0.04 ms per 10 ms hop. |
| MP3 decoding for `RealSpeechEvalTests` | No MP3 decoder on the JVM, and no LGPL/GPL one in the module. | The ffmpeg command line, cached under `dsp/build/voice-pcm`; the suite is skipped with a message if ffmpeg or the content submodule is missing. AVAudioFile drops the decoder's 529-sample delay but keeps the 576-sample LAME encoder delay that ffmpeg's gapless trimming removes, so the test decodes untrimmed (`-flags2 +skip_manual`) and drops 529 samples. With ffmpeg's default trimming about 50 report lines differed from iOS; with this alignment the two above. |
| Synthetic signals (`Synth.swift`) | `vDSP_vramp`, `vvsinf`, `vDSP_vsma` are not available. | Ramp as A + n·B in Float (vDSP's documented formula), Float sine, multiply then add (no fused multiply-add). Signals can differ from iOS at the ulp level; every test threshold still holds. |
| `Character`, `String` | Kotlin strings are UTF-16 units. | Letters are extended grapheme clusters (`\X`), compared after NFC, as Swift compares canonically equivalent characters; `lowercased()` is the locale-invariant `lowercase()`; `isLetter` is the Alphabetic property; string ordering is by Unicode scalar of the NFC form (`swiftStringOrder`), which fixes the evaluation's word order. |
| `Dictionary` iteration | Random per process in Swift. | `UtteranceTracker` gives a tie between faults to the one heard first (`LinkedHashMap`); in Swift a tie has no fixed answer. |
| Sorting | | Kotlin's stable sort with comparators that reproduce Swift's `>` predicates. Swift's sort is stable too, so equal `hfDb` (bandwidth frames) and equal prominence (stress) keep index order in both. |
| `Codable`, `JSONEncoder`, `JSONSerialization` | Foundation is not available, and the module stays dependency-free. | `Json.kt` reads and writes JSON. `DSPThresholds.load`/`jsonData` keep Swift's layout (property names as keys, nested objects, partial overrides merged over `v1`, missing keys or wrong types throw). `TalkerCalibration.toJson`/`fromJson` read and write what Swift's synthesized Codable does (`ageYears` left out when nil, places by raw value), so a calibration stored by iOS reads back. Numbers may be written differently (`1.0E-5` for `1e-05`) but read back to the same values. |
| Enums with associated values, tuples | | Sealed classes (`SoundOutcome`, `StressOutcome`, `WAVError`) and small classes (`DecodedWAV`, `PathStep`). Plain enums use Kotlin's UPPER_SNAKE_CASE with a `rawValue` equal to Swift's. |
| Labels Kotlin cannot use | `as` is a keyword. | `TalkerCalibration.add(_ segment:, as:)` is `add(segment, place)`; `expectedFricatives(in:)` is `expectedFricatives(text)`, `rhoticPositions(in:)` is `rhoticPositions(text)`. |
| Reading past a buffer | Swift reads past the frame (undefined) if YIN needs more samples than the frame holds. | Kotlin would throw. It cannot happen at any standard rate (checked 8 to 192 kHz); it would at odd rates such as 50 kHz. |

`Float.pi` is reproduced exactly: Swift's is π rounded toward zero (`0x1.921fb4p1`), one ulp below `PI.toFloat()`. Swift rounding (`rounded()` ties away from zero, `Int(x)` truncation), nearest-rank percentiles, the upper median and the population standard deviation are reproduced as in Swift. No Float literal in the Swift sources rounds differently when, as on ARM64, it passes through Double first.

`docs/paritate.md` (outside `dsp/`) does not list this module yet; it can link to this file.

## Not on iOS: word emphasis (`Emphasis.kt`)

New pure analysis for the emphasis exercise and the BAC report (fonia-android-plan/03 §1.5 V2, H F2); nothing above it
changed. Given a span per word (from Whisper), each word is measured on its loudest syllable nucleus (the nuclei and the
vowel-band dips of `analyzeStress`): peak vowel-band level, how long the vowel stays within `vowelDropDb` of it
(`finalLengthening` before a pause ≥ 250 ms), and the 90th percentile of the word's strict F0 in semitones re the take's
median. Each word is compared with the mean of up to two measured neighbours on each side (other key words left out when
possible). Stressed = 2 of 3 cues: ≥ +30 % duration, ≥ +3 dB, ≥ +2 st; a weighted prominence (1.5 / 1.5 / 1) ranks words.
All thresholds are `EmphasisThresholds` [S] values, uncalibrated. `EmphasisTests` (12) run on synthetic speech.
