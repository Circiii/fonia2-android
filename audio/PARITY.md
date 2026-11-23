# `:audio` parity with iOS `FoniaAudio` + `FoniaAudioRT`

Port of `Packages/FoniaKit/Sources/FoniaAudio` (15 Swift files) and `FoniaAudioRT` (C/Obj-C) to `tech.fonia.audio`,
one Kotlin file per Swift file, plus `src/main/cpp` for everything that runs on the audio thread. Inventory sections
1.5, 1.6, 6, 11.5, 13.3 and 14.2 steps 4–5 are the specification.

| iOS | Android |
|---|---|
| `FoniaAudioRT.h/.m`: `FNARing`, `FNAMakeSinkNode` | `cpp/FoniaAudioRT.h/.cpp`: the same ring statement for statement (C++ atomics, same memory orders), `FNADeliver`; `cpp/FoniaCapture.*`: the Oboe input stream that replaces the sink node; `cpp/FoniaJni.cpp` |
| `AudioState`, `AudioGraphEvents`, `AudioSessionController` | same names; `SharedAudioSession` stands in for `AVAudioSession.sharedInstance()` |
| `CaptureRing`, `CaptureSource`, `CaptureEngine`, `FixtureSource`, `MicrophoneSource` | same names |
| `LiveLevels`, `LoudnessEnvelope`, `PlaybackEngine`, `SoundEffects`, `SpeechFallback`, `RecordingWriter`, `AudioDecoding` | same names |
| `FoniaFeatures/App/AudioHub.swift`: `SpeechGate`, `waitForTakeEnd`; `AppModel`/`AudioHub`: the recorded-take choice | `SpeechGate.kt` (`SpeechGate`, `TakeEnd`), `FixtureMode` in `FixtureSource.kt`: moved down so `:features` and its tests share one copy |

Libraries added in `audio/build.gradle.kts`: `com.google.oboe:oboe:1.11.0` (prefab, `ANDROID_STL=c++_shared`), NDK
`28.2.13676358`, CMake `3.22.1`. Media3 ExoPlayer 1.11.1 and coroutines were already in the module (catalog). All native
libraries in the AAR (`libfonia_audio_rt.so`, `liboboe.so`, `libc++_shared.so`) have 16 KB LOAD alignment (checked with
`llvm-readelf`).

## What is not identical, and what the port does instead

| What | Why | Instead |
|---|---|---|
| Real-time checks by the compiler | iOS builds FoniaAudioRT with `-Werror=function-effects` and `nonblocking`; Clang learns them in version 20, NDK r28 ships Clang 19 | `FNA_NONBLOCKING` and a CMake check turn the same check on once the NDK's Clang has it. Until then by review: the Oboe callback only calls `FNADeliver` → `FNARingWrite` (no allocation, locks, logging or JNI; integer formats go through a 256-sample stack buffer) |
| `AVAudioSinkNode` on `AVAudioEngine.inputNode` | Android has no engine graph | An Oboe input stream (`FoniaCapture.cpp`): 48 kHz requested, mono, float, `LowLatency`, shared, input preset `UNPROCESSED` when `AudioManager.getProperty(PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"`, else `VOICE_RECOGNITION` (never voice communication), the built-in microphone as preferred device (iOS `setPreferredInput(builtInMic)`), privacy-sensitive. Oboe hands over interleaved frames, so channel 0 is every N-th sample; 16/32-bit integers are scaled by 1/32768 and 1/2³¹. No Oboe resampler: like iOS, the engine accepts the rate it gets |
| `.measurement` mode (no AGC, no voice filtering) | Android sets this per stream, and a phone may still attach pre-processing | The stream allocates a session id, and `PlatformEffects` switches off any `NoiseSuppressor`, `AutomaticGainControl` or `AcousticEchoCanceler` it finds switched on; `CaptureInfo.platformEffectsSwitchedOff` names them. Oboe notes a session id can cost latency: on the emulator the stream got 48 kHz and `VOICE_RECOGNITION` but not the fast path (`isLowLatency = false`, burst 768) |
| Narrow-band input | iOS learns it from the clip (`CaptureQuality`) | Same in `:dsp`; in addition `MicrophoneSource.captureInfo` reports the real rate, preset, device type and `isNarrowBand` (Bluetooth SCO / LE microphone, or under 32 kHz) |
| `AVAudioSession` category, activation | Android has no session object | `configure` records `playbackOnly`; `activate` takes transient audio focus (`USAGE_MEDIA`, `CONTENT_TYPE_SPEECH`): other audio pauses and carries on after `deactivate` abandons it (iOS `.notifyOthersOnDeactivation`). A refused request throws `AudioSessionException`. 48 kHz and the 5 ms buffer are stream properties. `.defaultToSpeaker` and `.allowBluetoothA2DP` are Android's defaults for media in `MODE_NORMAL` |
| `setPrefersNoInterruptionsFromSystemAlerts` | — | While a take runs, focus is asked again with the same listener as `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` (no notification sounds), then back to `GAIN_TRANSIENT` |
| Interruption notifications | Android reports audio focus changes | `AUDIOFOCUS_LOSS` / `LOSS_TRANSIENT` → `interruptionBegan`; `AUDIOFOCUS_GAIN` → `interruptionEnded(shouldResume = true)`; a duckable loss is no interruption (the system lowers the volume). Android has no source for `interruptionEnded(shouldResume = false)`: after a permanent loss the state stays `Interrupted` until the next `activate` |
| Route changes, `currentRoute` | Android names no active route before a stream opens | `AudioDeviceCallback`, compared with the previous device set (registration replays every device; that is no change). The route is the one a recording and a media stream would take: a Bluetooth microphone only while SCO is on, then wired/USB, then built-in; media output from `getAudioDevicesForAttributes` (API 33+) or by priority. Port types map to `AudioDeviceInfo` types: `bluetoothHFP` → `TYPE_BLUETOOTH_SCO`, `bluetoothLE` → `TYPE_BLE_HEADSET`, `headphones` → wired headphones/headset and USB headset, `bluetoothA2DP` → `TYPE_BLUETOOTH_A2DP`, plus hearing aids |
| `AVAudioEngineConfigurationChange` | — | Oboe's `onErrorAfterClose` (the stream was disconnected, e.g. by a route change) calls up through JNI and becomes `configurationChanged`; `MicrophoneGraph` rebuilds it 3 times, 200 ms apart, as on iOS |
| `mediaServicesWereLost/Reset` | No Android broadcast | The events and their handling are kept. ExoPlayer giving up with an error is handled as `mediaServicesLost` (the clip ends, the next `play` builds a new player); an audio-server restart disconnects the Oboe stream, which rebuilds as above |
| Microphone permission | Android has no "not asked yet" readable without an Activity, and the prompt needs one | The state starts from `checkSelfPermission`, so a permission not yet granted reads as `MicrophoneDenied`. `requestMicrophone(prompt)` takes the Activity's permission launcher; with the permission held it is not called |
| Actors | — | One lock per object for state changes, with suspension where iOS awaits (so a `stop` during `start` behaves the same). `CaptureEngine.stop` and `MicrophoneSource.stop` are non-cancellable, as an actor call is |
| `deinit` | Kotlin has none | `CaptureEngine.stop` closes the native ring; a microphone stream keeps the ring alive until it is closed (shared ownership in C++). `PlaybackEngine.release()` and `SpeechFallback.release()` free their threads/engine. A `CaptureEngine` dropped mid-take is not cleaned up: `AudioHub` always stops or discards first |
| Types | — | `HopProcessor.process(samples, offset, count, levels)` instead of an `UnsafeBufferPointer`; `CapturedClip` holds a `FloatArray` and compares by content; `ClipLocation` (file or asset) instead of `URL`, since voice clips are assets; errors are exceptions (`CaptureError.*`, `RecordingWriter.WriteError.EmptyClip`); counters are 32-bit `Int` |
| `AVAudioPlayerNode` → `AVAudioUnitTimePitch` | — | Media3 ExoPlayer with only an audio renderer, on its own thread. Clips are decoded once (as on iOS) and played as 16-bit PCM WAV from memory; iOS plays Float32, the difference is quantisation at −96 dBFS. `setSpeed` is `PlaybackParameters(speed, 1f)` (Sonic keeps the pitch). Focus belongs to `AudioSessionController`, so ExoPlayer does not handle it |
| Mouth sync: `rendered − presentationLatency` | — | Media3's position is already the presented one (AudioTrack timestamps, or the playback head minus the track latency), so the same correction is in it and nothing more is subtracted (`EXTRA_PRESENTATION_LATENCY = 0`) |
| `engine.start()` failing → `play` returns false | ExoPlayer reports failures asynchronously | An ExoPlayer error before the clip is ready makes `play` return false; a later one ends the clip. "Engine running" for a route change means a player that is not idle and has no error |
| `AVAudioFile` decoding | — | WAV is parsed in Kotlin with `AVAudioFile`'s scaling (16-bit ÷ 32768, channels summed then × 1/n), so it also runs on the JVM; MP3/AAC go through `MediaExtractor` + `MediaCodec`. Android's MP3 decoder does not drop the codec's priming like Apple's may; `pop.mp3` still decodes to 0.4–0.7 s at 44.1 kHz |
| `AVAudioFile(forWriting:)` AAC with `AVAudioQuality.high` | MediaCodec needs a bit rate; MediaMuxer writes no gapless information and Android's AAC decoder plays the encoder's priming | Android's own AAC-LC encoder (`c2.android.aac.encoder`) by name, so every phone writes the same bits, at `min(128 kbps, 2.67 bits/sample)`; 16-bit PCM in. Its priming (2048 frames, measured at 16, 44.1 and 48 kHz) and the end padding are written as an MP4 edit list, as Apple's files carry them (`Mp4Gapless`), and `AudioDecoder` trims by it. MediaExtractor then reports `encoder-delay=2048`, `durationUs=1000000` for a 1 s take, and ExoPlayer plays it gapless too |
| `AVAudioPlayer` per effect | — | One `SoundPool` on its own thread: an effect loads at first use and plays when loaded; a replay stops the previous stream (iOS restarts the player at 0); a failed play drops the sound so the next call reloads it. `USAGE_GAME` / `CONTENT_TYPE_SONIFICATION` |
| `AVSpeechSynthesizer` | — | `TextToSpeech`, state on the main thread like the `@MainActor` class. Rate 0.9 of normal (Android's normal is 1). The engine starts asynchronously, so an utterance waits for it; with no engine nothing is said and the watchdog ends the wait; a `speak` the engine refuses ends at once. A missing `ro-RO` voice leaves the engine's default voice, as `AVSpeechSynthesisVoice(language:)` returning nil does (the emulator's Google TTS has only en-US). Cancelling the caller stops the voice (iOS does this in `AudioHub` with `withTaskCancellationHandler`); `stop()` from another thread is posted to the main thread. The watchdog counts grapheme clusters, as Swift's `count` |
| `waitForTakeEnd` | — | Same rule, constants (0.7 s silence, 6 s start timeout, 8 s maximum) and 50 ms polling; iOS measures elapsed time with `Date`, the port with the monotonic clock |
| Recorded-take mode | iOS turns it on in the simulator; the emulator has a microphone | Only explicitly: the boolean intent extra `FixtureMode.LAUNCH_ARGUMENT` (`"foniaFixtures"`, iOS `-foniaFixtures`). `FixtureMode.captureSource` makes `AudioHub`'s choice (the text's clip, else `"soare"`, else the microphone) |
| `LoudnessEnvelope` RMS | `vDSP.rootMeanSquare` sums in float SIMD lanes | Sums in double; the difference is ~1e-7 relative, far below the envelope's thresholds |
| Logging | — | `android.util.Log` with iOS's categories as tags (`AudioSession`, `Microphone`, `Playback`, `SoundEffects`) |
| Manifest | iOS declares the microphone use in the app's Info.plist | The library manifest declares `RECORD_AUDIO` and the `TTS_SERVICE` query (package visibility), so they merge into the app |

## Tests

The 70 Swift Testing functions of `FoniaAudioTests` are ported one to one, with the same names and the same inputs and
expected values. Parameterized tests are loops inside one JUnit test, with the argument in the failure message.

| Suite (iOS file) | Tests | Where |
|---|---:|---|
| Audio state machine (`AudioStateTests`) | 7 | JVM |
| Audio session mapping (`AudioStateTests`) | 2 | JVM |
| Capture ring (`CaptureRingTests`) | 6 + 1 | JVM and device (shared source); `sinkNode` device |
| Capture engine (`CaptureEngineTests`) | 12 + 2 | JVM and device (shared source); `mp3FixtureDecodes`, `deniedMicrophone` device |
| Live levels | 3 | JVM |
| Loudness envelope | 10 | JVM |
| Captured clip, Recording writer, Sound effects (`OutputTests`) | 1 + 3 + 3 | JVM, except the writer's `roundTrip` and `createsFolders` (device) |
| Audio session controller | 3 | device |
| Playback engine | 13 | device |
| Speech fallback | 4 | device |

45 run on the JVM (`./gradlew :audio:testDebugUnitTest`) and 25 on a device (`./gradlew :audio:connectedDebugAndroidTest`);
the 18 shared ring and capture-engine tests run on both, on the JVM with the Kotlin ring and on the device with the
native one. Last run: all 45 + 3 Mp4Gapless + 13 other port-only checks pass on the JVM; all 25 + 18 shared + 2
port-only pass on the `sp_d` emulator (API 36, x86_64).

Not ported identically:

| Test | Why | Instead |
|---|---|---|
| `AudioSessionMappingTests.interruptions`, `.routes` | Android decodes focus changes and `AudioDeviceInfo` types, not `AVAudioSession` values | Six focus-change cases (two losses, gain, duckable loss, nil, 99) and the seven routes with the mapped device types. The two `ended(shouldResume: false)` cases have no Android source |
| `AudioSessionControllerTests.configuresSession` | No category, mode or options | Checks the Android equivalents: focus held with `GAIN_TRANSIENT`, `USAGE_MEDIA` + `CONTENT_TYPE_SPEECH`, `MODE_NORMAL`, a preset that is not voice communication, focus released on `deactivate` |
| `AudioSessionControllerTests.lifecycle` | iOS runs it only when the microphone is not denied | The test grants `RECORD_AUDIO` first |
| `CaptureRingTests.sinkNode` | No `AVAudioSinkNode` | The Oboe callback object is made without opening a stream (state Closed) and its delivery writes channel 0 of interleaved input into the ring |
| `CaptureEngineTests.deniedMicrophone` | iOS runs it only on a machine where the permission is denied; revoking a permission kills the test process on Android | `MicrophoneSource` is given a denied permission check, so the test always runs |
| `SoundEffectsTests.fileExists` | The effects come from fonia-content | Checks `content/voice/sfx/<file>`, which the app copies into its assets |
| `.timeLimit(.minutes(1))` | — | `@Test(timeout = 60_000)` (120 s for the three-rate M4A round trip) |
| Playback and speech tests | Kotlin has no deinit | Each test calls `release()` at the end |

Port-only checks (not counted among the 70): `SpeechGateTests` (the end-of-take rule on a fake clock, and once on the
real clock), `CaptureChoicesTests` (preset choice, narrow band, recorded-take source choice, Swift rounding),
`DecodingTests` (WAV scaling, PCM WAV round trip, AAC bit rates), `Mp4GaplessTests` (the edit list on the three MP4
layouts), `CaptureDeviceTests.microphoneCapturesWithThePreferredPreset` (the real microphone through Oboe: 48 kHz,
`VOICE_RECOGNITION` on the emulator, built-in mic, 480-sample hops) and `FixtureChainTests` (recorded take → 10 ms hops →
speech gate → end of take on silence → M4A → playback, with no microphone).

These rows belong in `docs/paritate.md` too; this change only touches `audio/`, so they are kept here.
