# `:whisper` parity with iOS (WhisperKit + Core ML)

`:whisper` is the platform half of `FoniaSpeech`: it implements the seams `:speech` defines in `WhisperRuntime.kt` (`WhisperRuntimeLoader`, `WhisperRuntime`, `WhisperDecoderRuntime`, `WhisperTokenizer`) and `SpeechModelFetcher`, on whisper.cpp v1.9.5 (the pinned submodule `src/main/cpp/whisper.cpp`, compiled unchanged) through the NDK. Everything above the seams (`ForcedDecoder`, `WhisperSession`, `WhisperEngine`, `TokenTiming`, the judges) is the `:speech` port, untouched. iOS runs the same model, `openai_whisper-base`, through WhisperKit and Core ML.

## Result

On the emulator (`sp_d`, Android 16, x86_64), over the 23 pairs, 7 sentences and 2 free-speech takes of iOS `tools/eval_speech`, with `SpeechThresholds.STANDARD` on both sides:

| | Same as iOS |
|---|---|
| Pair outcome (`PairJudge`) | 23 of 23 |
| Pair margin | shift (Android minus iOS) median +0.00 nats, median absolute 0.03, largest 0.09 (en-sink); single words the same |
| Greedy transcript of every clip | 32 of 32 identical, including the odd "Shab" for en-ship and "Astați" in ro-speech |
| Sentence result (`SentenceJudge`) | 7 of 7, same scores |
| Sentence word status | 34 of 34 |
| Word start/end (DTW over the cross-attention) | 66 of 68 edges identical at 10 ms, the other 2 one encoder position (20 ms) apart |
| Free speech (`SpeechReport`) | 2 of 2: same words per minute (174, 190), pace, fillers, pauses, score and focus |

No threshold was moved and `speech/eval_speech/calibration.json` stays `{}`. None would need to move for this set: every verdict already agrees. The closest calls, on both platforms on the same side:

| Row | Android | Boundary | Distance |
|---|---|---|---:|
| en-wrong-think sentence, "things" (heard "sings") | logp -0.405 | `rescueLogp` -0.4 (above it the word would be `weak`, score 0.40 instead of 0.25, still `severalMissed`) | 0.005 nats |
| en-wine said, ship/sheep asked | margin +0.60 | `okMargin` 0.5, but `otherWord` decides first, by 6.9 nats | 0.10 nats |
| en-sink, both directions | margin +0.01 / -0.01 (iOS -0.08 / +0.08) | `okMargin` 0.5 | 0.49 nats |
| en-animal (sameText) | no-speech 0.53 (iOS 0.53) | soft silence gate 0.6 | 0.07 |
| silence | no-speech 0.96 (iOS 0.94) | hard silence gate 0.9 | 0.06 |

The raw rows are in `eval_speech/results.jsonl` (same keys as iOS `results.jsonl`) and the side-by-side tables in `eval_speech/report.md`; both are written by `WhisperIntegrationTests.harness`.

**Read this as iOS reads its report:** synthetic adult voices, one per accent, decoded from MP3. It shows the runtime reproduces WhisperKit, not that the judges are clinically right, and it says nothing about children. The arm64 builds were compiled and disassembled but never run: there is no arm64 device here, so phone parity and latency still need one run on a phone (`./gradlew :whisper:connectedDebugAndroidTest` on it).

## What `:features` calls

```kotlin
val store = FoniaWhisper.modelStore(context)   // SpeechModelStore over noBackupFilesDir/FoniaSpeech
WhisperCppModel.megabytes()                    // 151: the size the UI shows before anything is fetched
store.isReady                                  // sizes only, cheap
store.download(allowsCellular = false) { p -> } // Wi-Fi/Ethernet only, resumable; cancel the coroutine to stop
val engine: WhisperEngine = store.makeEngine()  // the Transcriber: scorePair, scoreSentence, transcribe
engine.unload(); store.delete()                 // "Șterge pachetul"
```

`SpeechHub` keeps the iOS states (`absent`, `downloading`, `ready`, `failed`); a `NoAllowedNetworkException` (no unmetered network) is a failed download, as a URLSession with cellular access off fails on iOS. The manifest's byte count is known before the download starts, so the button can name the size (iOS hard-codes "147 MB"; here it is `WhisperCppModel.megabytes()`, 151).

## How the iOS pieces map

| iOS (WhisperKit, Core ML) | Android (`tech.fonia.whisper`) |
|---|---|
| `WhisperKit(config)`, `load: true`, `download: false` | `WhisperCppLoader.load`: tokenizer, `generation_config.json`, `whisper_init_from_file_with_params` on the CPU backend |
| `AutoTokenizerWrapper.from(modelFolder:)` (swift-transformers, vendored in argmax-oss-swift 1.1.1) | `WhisperBpeTokenizer`, a line-by-line port: added-token split, GPT-2 byte-level pre-tokenizer, BPE by merge rank, template post-processor; decode through the ByteLevel decoder (one U+FFFD per ill-formed UTF-8 part) and `cleanUp`; unknown names answer `<|endoftext|>`. Its ids equal Hugging Face `tokenizers` 0.22.1 on 11 strings, Romanian diacritics included |
| `MelSpectrogram.mlmodelc` | whisper.cpp `log_mel_spectrogram` over the padded 30 s window (same STFT, filters from the model file, clamp to max-8, (x+4)/4) |
| `AudioEncoder.encodeFeatures` | `whisper_encode_with_state` (conv, encoder, cross-attention K/V) |
| `TextDecoder.predictLogits`, KV cache 224 slots, `kvCacheUpdateMask`, `decoderKeyPaddingMask` | `fonia_whisper_decode`: whisper.cpp's `whisper_decode_with_state` (one token at `n_past = index`, after dropping the cache cells at and past it) with the alignment heads kept. `kvCacheMaxSequenceLength` stays 224 as in WhisperKit's model, so `maxLength` and the `TextTooLong` limit match |
| `ForcedDecoder.rewind` (re-mask) | nothing: each step drops cells at and past its position before it runs |
| `alignment_heads_weights` (1 x 1500) | the softmaxed cross-attention of the 8 alignment heads listed in `generation_config.json` (3/1, 4/2, 4/3, 4/7, 5/1, 5/2, 5/4, 5/6, the same as whisper.cpp's `WHISPER_AHEADS_BASE`), averaged; each row sums to 1 over the 1500 positions (`WhisperCppTimingTests`) |
| `prewarm: true` (Core ML compile) | load and free once: there is nothing to compile; it proves the file loads and warms the page cache |
| `featureExtractor.windowSamples` | 480 000 from whisper.cpp |
| ephemeral `URLSession`, `allowsCellularAccess` | `HttpsModelFetcher` (see below) |
| Application Support, excluded from backup | `noBackupFilesDir/FoniaSpeech` |

### The decode step

whisper.cpp computes the cross-attention of the alignment heads for its experimental DTW timestamps, but the public `whisper_decode()` passes `save_alignment_heads_QKs = false` to the static `whisper_decode_internal()`. `src/main/cpp/fonia_whisper.cpp` is a unity translation unit: it `#include`s the pinned `whisper.cpp/src/whisper.cpp` and adds `fonia_whisper_decode`, which is `whisper_decode_with_state` with that flag set, then reads the `[1, 1500, 8]` tensor the graph already produced and averages it over the heads. The submodule is not modified and no patch is applied. DTW capture needs `flash_attn = false` (whisper.cpp disables DTW otherwise), so the encoder runs standard attention too, as Core ML does.

The runtime keeps one whisper.cpp state per model. Each decoder remembers its encoder output and the tokens it fed; if another encoder pass or decoder used the state in between, its next step re-encodes and replays its tokens first. `WhisperEngine` never interleaves decoders, so this only guards against misuse. All native calls for a model run on one thread (`fonia-whisper`); each is a suspension point, so cancelling a request stops it between steps.

## Native build

`src/main/cpp/CMakeLists.txt`, NDK 28.2.13676358, CMake 3.22.1, release flags (`-O3`, `NDEBUG`) whatever the Gradle variant. ggml (core and CPU backend) is built by its own CMake as static PIC libraries through `ExternalProject` (once per instruction set, since ggml's target names are fixed), then linked with the unity file and the JNI bridge into one shared library:

| ABI | Library | Flags | When |
|---|---|---|---|
| arm64-v8a | `libfonia_whisper.so` (1.5 MB) | `-march=armv8-a`: NEON, the baseline of every arm64 phone | CPU lacks `asimddp` (Cortex-A53/A73 and other ARMv8.0 cores) |
| arm64-v8a | `libfonia_whisper_dotprod.so` (1.5 MB) | `-march=armv8.2-a+dotprod`: NEON + SDOT/UDOT | every core lists `asimddp` in /proc/cpuinfo |
| x86_64 | `libfonia_whisper.so` (2.1 MB) | SSE4.2, AVX, AVX2, FMA, F16C, BMI2 | emulator only; needs an AVX2 host |

Checked on the built libraries: the baseline has no SDOT/UDOT and no FP16 vector arithmetic (its 4 LSE instructions sit in the outline-atomics helpers that test for LSE at run time); the dotprod build has 966 SDOT/UDOT; every LOAD segment is 16 KB aligned. Neither arm64 build enables `+fp16`: ggml would then accumulate f16 dot products in half precision, which moves the log-probabilities the judges read. Dotprod only speeds up quantized weights; with the f16 model both arm64 builds do the same arithmetic, which is also what the x86_64 build does (f16 weights, activations rounded to f16, f32 accumulation), so the parity measured on the emulator carries over in kind, if not to the last bit. `GGML_LLAMAFILE` stays off for the same reason (its NEON path keeps activations in f32, unlike the x86 path).

The JNI bridge (`fonia_jni.cpp`) allocates nothing per decoder step: `decode` copies the step's logits and alignment row into the caller's arrays with `Set<Float>ArrayRegion`, and the native buffers it reads were sized at load. A ggml thread pool lives as long as the model, instead of ggml creating and joining its workers on every graph. Inside whisper.cpp, a step's KV-cell bookkeeping still inserts one `std::set` node. On the Kotlin side each step returns fresh `FloatArray`s because `ForcedDecoder` keeps the logits of forced steps. Threads: the cores of the faster clusters, at most 4 (`WhisperCppThreads.preferred`).

## Model manifest

`WhisperCppModel.WHISPER_BASE_FROM_HUGGING_FACE`, `openai_whisper-base`, 4 files, 150 717 376 bytes:

| File | Bytes | SHA-256 | From |
|---|---:|---|---|
| `ggml-base.bin` | 147 951 465 | `60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe` | `ggerganov/whisper.cpp` @ `5359861c739e955e79d9a303bcbc70fb988958b1` (openai/whisper-base in ggml f16) |
| `generation_config.json` | 2 762 | `662a99e3db3067708549d04d5f141910eb455f0935aed0c9b06d707f44bfbcaf` | `argmaxinc/whisperkit-coreml` @ `0f63a780…` `openai_whisper-base/`: the file iOS pins and reads |
| `tokenizer.json` | 2 480 466 | `27fc476bfe7f17299480be2273fc0608e4d5a99aba2ab5dec5374b4482d1a566` | `openai/whisper-base` @ `e37978b9…`: the iOS pin |
| `tokenizer_config.json` | 282 683 | `2a4c4281cf9f51ac6ccc406fdc711a087afe6530f671fa7b80953edc498275ce` | same: the iOS pin (`unk_token`, `clean_up_tokenization_spaces`) |

As on iOS, a shipping build should host these files itself: fetching from Hugging Face discloses the device's address to a third party (`WhisperCppModel.whisperBase(...)` takes the three base URLs).

## Download

`HttpsModelFetcher`: plain HTTPS GETs for the manifest's files. `allowsCellular = false` (the default everywhere, as on iOS) means unmetered networks only (Wi-Fi or Ethernet; mobile data and metered hotspots are refused), and the connection is bound to the network that was checked, so it cannot move to mobile data mid-file. No cache, `Accept-Encoding: identity`, `User-Agent: Fonia` instead of Android's (which names the device model); HttpURLConnection sends no cookies unless the app installs a `CookieHandler`. Bytes already fetched stay under the store's `.partial` folder and the next attempt asks only for the rest with a `Range` request (iOS restarts a file); `SpeechModelStore` still checks every finished file's SHA-256 before installing it, and keeps the files an interrupted run already verified. `FoniaWhisper.modelStore` places the fetcher's unfinished files inside `<root>/openai_whisper-base.partial/.fetch`, so `store.delete()` removes them too.

## Tests

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew :whisper:testDebugUnitTest
./gradlew :whisper:connectedDebugAndroidTest      # runs whisperModelToDevice first
# the network tests, as iOS's FONIA_SPEECH_DOWNLOAD_TEST=1:
./gradlew :whisper:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.foniaSpeechDownloadTest=1
```

`whisperModelToDevice` downloads the four pinned files once into `~/.gradle/caches/fonia-whisper` (size and SHA-256 checked) and pushes them once to `/data/local/tmp/fonia/openai_whisper-base` on the device (`ANDROID_SERIAL` picks one of several); the tests find the folder through the instrumentation argument `foniaWhisperModel`, as iOS through `FONIA_WHISPER_MODEL`, and skip when it holds no model. The harness hands `results.jsonl` and `parity.md` back through the test storage service, into `build/outputs/connected_android_test_additional_output/`, and logs them under the tags `FoniaWhisperEval` and `FoniaWhisperParity`.

| iOS file | iOS tests | Ported | Passing on `sp_d` |
|---|---:|---:|---:|
| `WhisperIntegrationTests` | 15 | 15 | 15 |
| `SpeechModelStoreTests`, deferred by `:speech` (`excludedFromBackup`, `defaultLocation`, `realDownload`) | 3 | 3 | 3 (`realDownload` with the flag: 151 MB fetched, verified and transcribed in 27 s) |

Not iOS tests, Android only: `WhisperTokenizerTests` (6: ids against Hugging Face `tokenizers`, decode rules, special tokens, the cached folder against the manifest), `HttpsModelFetcherTests` (3: refusal without an allowed network, unmetered network found, Range resume against Hugging Face; the last two with the flag), `WhisperCppTimingTests` (1: encoder pass, decoder step, memory, alignment rows sum to 1), and on the JVM `WhisperModuleTests` (5: manifest, tokenizer pins equal to iOS's, library choice, cpuinfo parsing, UTF-8 replacement). All pass: 25 instrumented tests in a default run, the 3 network ones with the flag, and the 5 JVM tests.

Each test keeps the iOS inputs and expectations. Changes: `harness` writes its rows through the test storage service instead of `tools/eval_speech/results.jsonl`, adds the comparison with the iOS rows (`ParityReport`), and unloads its engine at the end (the suite's shared engine holds a second model; the emulator has 2 GB). The fixtures are the web app's `e2e/fixtures/speech` WAVs (17 used by the suite, 1.8 MB), packed into the test APK's assets; the iOS figures to compare with are `assets/eval_speech/ios_report.json`, generated from `fonia-app/tools/eval_speech/report.md`.

## Latency and memory on the emulator

`sp_d`: 4 x86_64 vCPUs, 2 GB, on a host shared with other builds, so the numbers move between runs. Median of three runs per row, two harness runs: single-word pair 1.18 to 1.37 s (iOS Simulator 0.59 s), sentence 1.29 to 1.44 s (iOS 0.45 s), free speech (12.6 s and 10.6 s of audio) 2.4 to 2.9 s (iOS 1.2 and 1.5 s). A third run, while other builds loaded the host, took 3 to 4 times as long. For a pair or a sentence nearly all of that is the one encoder pass over the 30 s window, 0.93 to 1.10 s; a decoder step costs 4 to 8 ms, and a pair takes 12 to 14 of them (free speech: 77 and 101). Load 0.2 to 0.8 s, prewarm 0.5 to 1.1 s. A loaded model adds about 170 to 240 MB resident, and about 300 to 380 MB while encoding and decoding.

The research's gate is 500 ms at P95 on the lowest supported phone. Whether a phone meets it is not known until the suite runs on one. The levers, each needing a new parity run because each changes the arithmetic: `GGML_LLAMAFILE` (faster f16 matrix products), flash attention for the encoder only (needs the DTW capture kept on the decoder), quantized weights (q8_0), or encoding only the clip's length (`audio_ctx`), which the iOS model does not do.

## Deviations

| Module | What | Why | What we do instead |
|---|---|---|---|
| `:whisper` | ggml f16 weights (`ggml-base.bin`) instead of the Core ML build | Core ML exists only on Apple platforms | The same openai/whisper-base weights; 4 files, 150 717 376 bytes ("151 MB") instead of 21 files, 149 482 602 bytes ("147 MB", which counts only the model) |
| `:whisper` | Log-mel from whisper.cpp's C++ code, in f32 | `MelSpectrogram.mlmodelc` takes the audio in f16 | Same window, filters and normalisation; margins agree within 0.09 nats |
| `:whisper` | Logits in f32 | Core ML hands them out in f16 | `ForcedDecoder` converts to Float on iOS anyway; differences sit in the last bits |
| `:whisper` | Alignment row from whisper.cpp's DTW graph, through a unity translation unit | whisper.cpp exports no per-step cross-attention | No change to the submodule; the heads come from `generation_config.json`, the same 8 WhisperKit averages |
| `:whisper` | Flash attention off | DTW capture requires it | Standard attention in encoder and decoder, as on Core ML; slower encoder |
| `:whisper` | `ForcedDecoder.rewind` is a no-op | whisper.cpp drops cache cells by position | Each step drops cells at and past its index; `reuseIsClean` passes |
| `:whisper` | `prewarm` loads and frees | Nothing to compile | Checks the file loads and warms the page cache |
| `:whisper` | Two arm64 libraries chosen at run time | A single `armv8.2-a+dotprod` build would crash (SIGILL) on ARMv8.0 phones | Baseline NEON build plus a dotprod build; no FP16 arithmetic in either |
| `:whisper` | Tokenizer reimplemented in Kotlin | swift-transformers is Swift-only | Same steps as the vendored swift-transformers; the added-token split assumes Whisper's `<|…|>` form; checked against Hugging Face `tokenizers` |
| `:whisper` | Store root `noBackupFilesDir/FoniaSpeech`; `excludedFromBackup` checks the location | Android has no per-file backup flag; Auto Backup never copies this folder | `FoniaWhisper.defaultRoot(context)` |
| `:whisper` | `allowsCellular = false` refuses every metered network | Android names networks by metering, not by radio | Same intent as iOS (no mobile data); a metered Wi-Fi hotspot is refused too |
| `:whisper` | Range resume within a file | One 148 MB file, where iOS's largest was 104 MB of 21 | The store's SHA-256 check still decides what is installed |
| `:whisper` | Tests read the model from `/data/local/tmp/fonia/openai_whisper-base`, the fixtures from the test APK | Instrumented tests have no host paths or environment variables | `whisperModelToDevice` fills the folder; the instrumentation arguments `foniaWhisperModel` and `foniaSpeechDownloadTest` stand for `FONIA_WHISPER_MODEL` and `FONIA_SPEECH_DOWNLOAD_TEST` |
| `:whisper` | `harness` output through the test storage service, plus `ParityReport`, plus `engine.unload()` | No write access to the sources from the device; 2 GB emulator | Copies kept in `eval_speech/` |
| `:whisper` | x86_64 needs AVX2 | Emulator-only ABI built for speed | Fine on any recent host; an old host would need a rebuild without AVX2 |

## Libraries

Added in `whisper/build.gradle.kts` with literal coordinates: `androidx.test.services:storage:1.6.0` (androidTest) and `androidx.test.services:test-services:1.6.0` (`androidTestUtil`). From the version catalog: `kotlinx-coroutines-core` and `kotlinx-serialization-json`. Native: whisper.cpp and ggml v1.9.5 (MIT), from the submodule.
