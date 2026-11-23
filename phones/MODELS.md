# Models of `:phones`

## ZIPA-small CR-CTC, 500k steps, int8

The phone recogniser behind the scoring: ZIPA (Zhu et al., ACL 2025), the Zipformer trained with consistency-regularised CTC on IPAPack++ (17,132 h, 88 languages, Romanian included through Common Voice and FLEURS). The research hub chose it (report E §4, §6) as the only multilingual phone model with Romanian that fits a phone and has a permissive licence.

| | |
|---|---|
| Repository | [`anyspeech/zipa-small-crctc-500k`](https://huggingface.co/anyspeech/zipa-small-crctc-500k), commit `dfe87c97662606fa6db90ad06f627d4363314744` (2026-10-06) |
| File | `model.int8.onnx`, 70,677,672 bytes |
| URL (pinned) | `https://huggingface.co/anyspeech/zipa-small-crctc-500k/resolve/dfe87c97662606fa6db90ad06f627d4363314744/model.int8.onnx` |
| SHA-256 | `d0e28b68164e8b1fbd6105100c01798828aa0855000ce9bbbd1a2cec233adf13` |
| Tokens | `tokens.txt` at the same commit, 769 bytes, SHA-256 `f8e042a0c9130532b22d03ec7cae2f75a23fbec70c450c31a8efb51787b2b8fe`; identical to `ipa_simplified/tokens.txt` in `lingjzhu/zipa`. Built into `ZipaInventory` and checked against the download by `ZipaModelTest`. |
| Parameters | 64 M |
| Model licence | **CC BY 4.0** |
| Code licence | MIT (`lingjzhu/zipa`) |

`ZipaModel.INT8` and `ZipaModel.TOKENS` hold these pins. The app's model manager (PLAN §6; `ModelCatalog.ZIPA` and `ModelManager` in `:features`) fetches the same bytes on unmetered networks only, checks the same size and hash, and keeps them in `noBackupFilesDir/FoniaModels/zipa-small-crctc-500k-int8/dfe87c976626/`; `ModelCatalog.zipa(baseUrl)` points it at Fonia's own server once the file is hosted there. Fonia 2 ships it **inside the APK** (`:platform:ml`, `spec/models/manifest.json`, copied from the same cache at build time; the v1 legacy model manager above is not used). It is never committed: the tests download it once into `~/.gradle/fonia-models/zipa-small-crctc-500k/` (`fonia.models`).

### Licence, checked at the source on 6 November 2026

- The model card at the pinned commit declares `license: cc-by-4.0`.
- The authors' table in the `lingjzhu/zipa` README (commit `21e9e8aa4cc6c130cb05d836a1979886f9245302`) gives CC BY 4.0 for `zipa-small-crctc-500k`. It also says why the `ns` models (`zipa-small-crctc-ns-700k` and the no-diacritics variants) are CC BY-NC-SA 4.0: their pseudo-labelled data, MMS-ulab v2, is NC-SA. Those are research only and must not replace this file.
- The repository is MIT (GitHub API: `MIT`, `LICENSE` file "Copyright (c) 2025 jzhu").

CC BY 4.0 needs attribution in the app's `NOTICE`:

> ZIPA-small CR-CTC (500k), © the ZIPA authors (Jian Zhu et al.), licensed under CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/). Source: https://huggingface.co/anyspeech/zipa-small-crctc-500k. Used unmodified (int8 export by the authors). Paper: Zhu et al., "ZIPA: A family of efficient models for multilingual phone recognition", ACL 2025.

### Graph

Exported by the authors (`zipformer_crctc/export-onnx-ctc.py`: opset 13, then `onnxruntime.quantization.quantize_dynamic` on the MatMuls, `QInt8` weights; activations are quantised at run time). Nothing was re-exported here.

| | |
|---|---|
| Inputs | `x` float32 (N, T, 80) fbank; `x_lens` int64 (N) |
| Outputs | `log_probs` float32 (N, T′, 127), log-softmax; `log_probs_len` int64 (N) |
| Frame rate | T′ = (T − 7) / 2: **50 Hz** (20 ms per row), as `02_stack_android_ml.md` found (CR-CTC models upsample the Zipformer's 25 Hz) |
| Minimum input | 9 fbank frames (about 90 ms); the scorer abstains under 10 output frames |
| Blank | token 0 `<blk>` |
| Word start | token 3 `▁`. The model emits it at the start of every utterance and word, so every label sequence starts with it. Without it the first frames have nowhere to go and word-initial fricatives scored as affricates in the first experiments. |
| Affricates | none in the inventory, no tie bar: Ț = `t s`, CE/CI = `t ʃ`, GE/GI = `d ʒ` |

### Front end (`ZipaFbank`)

lhotse's `Fbank()` with its `FbankConfig` defaults, which ZIPA used in training (`OnTheFlyFeatures(Fbank())`) and in its ONNX inference script (`FbankConfig(num_filters=80, dither=0.0, snip_edges=False)`):

- 16 kHz mono, samples as floats in [−1, 1] (soundfile's scale). No dither, no gain, no CMVN: the network takes raw log energies.
- 25 ms frames every 10 ms, (n + 80) / 160 of them, the signal mirrored at both ends (snip_edges = False).
- Per frame: mean removal, pre-emphasis 0.97 (first sample repeated), Povey window (Hann over 399 intervals)^0.85, zero-padding to 512, power spectrum.
- 80 Kaldi mel filters from 20 Hz to 7600 Hz (mel = 1127 ln(1 + f/700)), Nyquist bin unused, then ln(max(E, FLT_EPSILON)).

How it was checked:

- `ZipaFbankTest` compares with vectors lhotse 1.33.0 computed (`tools/fbank_reference.py`, signals and outputs in `src/test/resources/fbank`): tones, a chirp to 7.9 kHz, noise, a click, a DC step, a voiced sound and a hiss, a quarter second, 250 samples. Window and filter edges are rounded as lhotse rounds them in float32; the rest runs in Double. Within 20 dB of each frame's peak the log energies differ by at most 1.6e-5, except 1.3e-4 for a pure tone on the far edge of the top filter (float32 rounding in the reference).
- lhotse's `layers.py` in 1.27.0 (August 2024) and 1.33.0 differ only in where `get_mel_banks` comes from (torchaudio then, a local copy now, same formulas), so the front end has not moved since ZIPA was trained.
- `ZipaModelTest` runs the Kotlin front end and ONNX Runtime on the authors' `sample.wav` and gets, token for token, the transcription they published for this checkpoint. The int8 graph quantises activations per run, so the input differences left (at most 1.6e-4 on bands above e⁻¹⁰) flip the best token of 3 of 289 frames: our output equals their fp32/fp16 line, which differs from their int8 line by one token (`g ɑ s` against `g ɔ ɑ s`). Fed lhotse's own features, ONNX Runtime 1.31.1 here returns exactly what Python onnxruntime 1.27 returns (all 289 × 127 values equal) and the int8 line.

### Runtime

- ONNX Runtime 1.31.1 on the CPU, one session. Tests use the desktop jar; the app brings `onnxruntime-android` (same `ai.onnxruntime` API), the one ONNX Runtime of the APK, for arm64-v8a and x86_64 only: 34.2 MB and 40.8 MB of `libonnxruntime.so`, stored uncompressed in the APK. `:app`'s `check<Variant>OnnxRuntime` fails the build if an ABI ever carries a second copy (sherpa-onnx and Moonshine bring their own).
- The Android AAR also carries Microsoft's 1DS telemetry: a `ContentProvider` (`ai.onnxruntime.TelemetryInitializer`) that loads the runtime at every app start, reads `android_id`, the phone model, locale and time zone, and uploads events over HTTP through `ai.onnxruntime.telemetry.HttpClient` (the native side keeps an offline event store). The app removes the provider from its manifest, without which the runtime logs "Android telemetry is unavailable because the 1DS Java HttpClient was not initialized"; `ZipaModel.load` also calls `setTelemetry(false)`, and the same check fails the build if the provider comes back. Re-check this at every ONNX Runtime update.
- In the app, shadow mode runs the scorer on one thread at the lowest priority with 1 ONNX Runtime thread, loads the model on the first take and closes it after 30 s without takes. On the `sp_d` emulator (x86_64): 1.9 s to load the model, then 110–200 ms per one-syllable take, logged as `loadMs` and `scoreMs`. Phones still to be measured (PLAN §6).
- Threads: 2–4 on a phone. On this PC a one-second word takes 32–35 ms with 1 thread, 23–26 ms with 4 and 43–51 ms with ONNX Runtime's default (one per core, 24 here): small inputs lose to thread overhead. Cold load: 2.3–2.7 s on this PC. Memory was not measured.
- On Windows the JVM of Android Studio (JBR 21) ships msvcp140.dll 14.29, which ONNX Runtime 1.31 crashes in (it needs 14.40+). `phones/build.gradle.kts` runs the tests on a copy of that JDK carrying the system's VC++ runtime (`phones/build/test-jdk`). Android is not affected.

### Not used

| Model | Why not |
|---|---|
| `zipa-small-crctc-500k` fp32 (`model.onnx`, 260,267,872 bytes, SHA-256 `ec62e6f583cc3658c6a28a8841c65a6343e8c4b8b350ce1fd5bd8c717c280d96`) | Same checkpoint, 3.7 times larger. Used only to check what int8 costs (nothing measurable, see `EVAL.md`); the evaluation compares it when it is already in the cache and never downloads it. |
| `zipa-small-crctc-ns-700k` and the `no-diacritics` variants | CC BY-NC-SA 4.0 |
| `zipa-large-crctc-500k` (int8 309.9 MB) | Too large for the phone budget; a candidate teacher for a Romanian student model |
| ZIPA transducers (`zipa-small-noncausal-*`) | GOP needs frame posteriors over the label set, which CTC gives directly |
| ZIPA through sherpa-onnx | sherpa returns text and timings, not posteriors, and assumes a subsampling factor of 4 (`02_stack_android_ml.md` §17) |

## Test assets

| File | Source | SHA-256 | Use |
|---|---|---|---|
| `sample.wav` (187,404 bytes) | `https://raw.githubusercontent.com/lingjzhu/zipa/21e9e8aa4cc6c130cb05d836a1979886f9245302/sample.wav` (LibriSpeech dev-clean 1272-128104-0000, CC BY 4.0) | `799f78ed4beb4de7ceae3a809262d4ce242394342ccd1d58cef7d49dbc2def46` | End-to-end check against the authors' published output; cached in `~/.gradle/fonia-models/zipa-reference/` |
| `src/test/resources/fbank/*.f32` | `tools/fbank_reference.py` (synthetic signals, lhotse 1.33.0 wheel SHA-256 `8697bc74a8f3101594fca5661c7318c30899f3fdb132a44c7e99e794be6ac061`) | | Front-end parity |

`./gradlew :phones:test -Pfonia.models.offline=true` stops the tests from downloading; missing files then skip the model tests with the reason.
