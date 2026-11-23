# `:engine:live` — the continuous band

Pure Kotlin (no `android.*`). The letter reacts to the voice through this module: one `LiveSnapshot` per 10 ms hop,
published lock-free to a `LiveBoard`, read once per drawn frame. Decisions (verdicts, step changes) never come from
here: they come from the session reducer. Plan: `docs/v2/02_ARHITECTURA.md` §3–4, quiet rule `05` §9.3, N3/N4/N8.

## Public API (`tech.fonia.live`)

| Type | Role |
|---|---|
| `LiveSnapshot` | Mutable frame: `seq`, `captureNanos`, `publishNanos`, `level` (10 ms fast path), `levelSlow`, `speaking`, `voicing`, `pitchHz`, `sibilance`, `fricative`, `sNess` (0 = Ș … 1 = S), `centroidHz` (ZCR hint), `trill`, `blow`, `onTarget`, `onTargetNow`, `fault` (`LiveFaults`), `holdSeconds`, `bestHoldSeconds`, `uncertainty`, `captureQuality` (`CaptureQualityCode`), `dropped`, `analysisMicros`, mouth (last value held, N4): `mouthOpen`, `lipRound`, `mouthNanos` |
| `LiveBoard` | Triple buffer. Writer: `back()` + `publish()`. Reader: `latest()` (one reader thread) |
| `LivePipeline(board, config, clock)` | Hop processor: `start(rate, target)`, `process(samples, offset, count, captureNanos)`, `stop()`; any thread: `setTarget`, `postMouth(open, round, nanos)`; `events: LiveEvents` (utterance ended); `stats: LiveStats` |
| `LiveConfig` | Data: `analysisHopSeconds` (0.01, N3), `talker`, hold grace, hold thresholds, clipping |
| `LiveTarget` | `Sibilant(side, voiced)`, `Trill`, `Voice`, `Blow`, `AnySound`; `LiveTarget.of(SoundId)` |
| `HoldTracker` | Continuous "good" time with a 150 ms grace (heir of `HeldTime`) |
| `LiveCapture(pipeline, clock, threadFactory)` | The `fonia-analysis` thread: `start(source, target)`, `stop(): Clip`; `LiveCapture.runToEnd(source, pipeline, target)` for tests |
| `PcmSource`, `ArrayPcmSource`, `WavPcmSource` | Where PCM comes from; WAV/array sources for JVM tests and fixtures |
| `Clip` | A finished take (pure twin of `CapturedClip`) |
| `AudioDirector` | Port: `state`, `board`, `micGate`, `play(PromptRef)`, `openMic(LiveTarget): CaptureInfo`, `closeMic(): Clip`, `earcon(Earcon)`, `suspendAll(reason)`, `resume()`, `setTarget` |
| `BaseAudioDirector` | The shared state machine (Idle → Speaking → Settling → Listening, Suspended) |
| `FakeAudioDirector` | No hardware; records a timeline (`overlaps()`); optional WAV source runs the real pipeline |
| `FxGate`, `MicGate`, `QuietRule` | The quiet rule: nothing plays while the mic is open; mic opens 150 ms after the last sound; 150 ms tail after closing; 120 ms between effects |
| `FrameLatencyMeter` | Overlay: `vsync − captureNanos` p50/p95, frames over 32 ms |

## Reading the board in Compose

One loop per screen; the snapshot is NOT Compose state, only a tick is:

```kotlin
class LiveFrame { val tick = mutableLongStateOf(0L); var now = LiveSnapshot() }
@Composable fun rememberLiveFrame(board: LiveBoard, meter: FrameLatencyMeter? = null): LiveFrame {
    val f = remember { LiveFrame() }
    LaunchedEffect(board) {
        while (true) withFrameNanos { t -> f.now = board.latest(); meter?.onFrame(t, f.now); f.tick.longValue = t }
    }
    return f
}
// Read only inside draw-phase lambdas (graphicsLayer / drawBehind / Canvas), never in composition:
LiveLetter("S", inflation = { live.tick.longValue; live.now.holdSeconds / target },
                roundness = { 100f * (1f - live.now.sNess) })
```

## Wiring `OboeAudioDirector` (`:platform:audio`)

```kotlin
val director = OboeAudioDirector(appContext, scope = appScope, config = liveConfigFromSpec,
    effects = SoundEffects(sfxDir),
    sourceFor = { if (fixtureMode) FixtureSource(clip) else MicrophoneSource(appContext) })
ProcessLifecycleOwner ON_STOP -> director.suspendAll(SuspendReason.Background); ON_START -> director.resume()
director.play(PromptRef.Asset("voice/ro/…mp3")); director.openMic(LiveTarget.of(SoundId.S)) // CaptureInfo: save audioSource
val clip = director.closeMic()                // to the judge; 16 kHz decimation happens on the clip
director.pipeline.events = LiveEvents { ok, fault, s, t -> channel.trySend(...) }  // event band
director.pipeline.postMouth(open, round, nanos)  // from the face engine
director.metrics()                            // debug overlay numbers
```

## Pitfalls

- One reader of `LiveBoard.latest()`. Share the instance; never call it from two threads.
- The back slot holds an old frame: the writer copies a full frame (`copyFrom`), never patches fields.
- Never pass a `LiveSnapshot` through a `Flow`, `State` or composable parameter (100 recompositions per second).
- Don't play sound or haptics outside `FxGate`/`AudioDirector`; `earcon()` drops (does not queue) effects while listening.
- `play()` while listening closes the mic and drops the take: the reducer should close it first.
- `sNess` is calibrated per voice: a child's S sits higher than an adult's (`LiveConfig.talker`).
- `centroidHz` is a zero-crossing hint; `LiveFrame` in `:engine:dsp` does not expose the spectral centroid yet.
- Capture clock = `now − Oboe input latency` refreshed every 100 ms; without timestamps (fixtures) it is the drain time.
