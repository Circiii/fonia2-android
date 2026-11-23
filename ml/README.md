# `:platform:ml` (package `tech.fonia.ml`)

The phoneme model inside the APK (plan 02 §6, plan 03 §5): ZIPA-small CR-CTC int8 on onnxruntime-android, behind the
judge's `PhoneScorerPort` (the **full head** of `TakeJudge`). No download code, no `INTERNET`.

| Type | What |
|---|---|
| `ZipaHost` | the model on its own thread `ml-zipa`: lazy load, `warmUp()`, `score(clip, request)` (≤ 600 ms or null), `port()` (blocking `PhoneScorerPort`), `trim()`, `state` |
| `ModelHost`, `HostState`, `ModelVersion` | the host contract of plan 02 §6 (lives here until `:core:model`'s owner takes it) |
| `ModelRegistry` | `assets/models/manifest.json` (= `spec/models/manifest.json`); `versions()` → `zipa@dfe87c976626` for the trace |
| `AssetModelFile` | maps the uncompressed asset (`openFd` + `FileChannel.map`), fallback copy to `noBackupFilesDir/models/…` with SHA-256 |
| `ModelMemoryPolicy` | `ComponentCallbacks2`: trims on UI hidden / background / running-critical |

## Wiring in `:app` (one place)

`app/build.gradle.kts`:

```kotlin
android {
    androidResources { noCompress += "onnx" }          // REQUIRED: the model must stay mappable (else a 70 MB copy on first run)
    defaultConfig { ndk { abiFilters += "arm64-v8a" } } // release; debug may add x86_64 for the emulator
    packaging { jniLibs.excludes += listOf("armeabi-v7a", "x86").flatMap { listOf("lib/$it/libonnxruntime.so", "lib/$it/libonnxruntime4j_jni.so") } }
}
dependencies { implementation(project(":platform:ml")) }
```

Copy `CheckOnnxRuntimeTask` and its registration from `legacy/app/build.gradle.kts` (the block "One ONNX Runtime in the
APK", `check${variant}OnnxRuntime`, finalizing every assemble): one `libonnxruntime.so` per shipped ABI, none on 32-bit,
and no `ai.onnxruntime.TelemetryInitializer` in the merged manifest. It still holds for a consumer of this module:
`ml/src/main/AndroidManifest.xml` removes the provider **and** the AAR's `INTERNET` / `ACCESS_NETWORK_STATE`
(checked: the merged manifest of this module's test APK has none of the three); extend the check to fail on
`android.permission.INTERNET` too. Show `spec/models/NOTICE` (ZIPA is CC BY 4.0: attribution is a licence condition).

Code (application + lesson + take):

```kotlin
val zipa = ZipaHost.create(appContext)                                    // cheap: reads only the manifest
appContext.registerComponentCallbacks(ModelMemoryPolicy(listOf(zipa)))
val modelVersions = ModelRegistry.load(appContext.assets).versions()      // save with each attempt

// entering LESSON (plan 02 §6: warm while in LESSON)
lessonScope.launch { zipa.warmUp() }                                      // ~1–3 s on a phone, off the main thread

// each take, after AttemptEnded (JUDGE.md "How the app calls it")
val m = withContext(Dispatchers.Default) { judge.measure(request, clip) }
val fast = judge.decide(m, runtime = runtime)
if (fast.head == Head.Gate || fast.fastFinal) emit(fast)                  // ≤ 100 ms
else {
    val phones = zipa.score(clip, request)                                // the 48 kHz Clip; null after 600 ms or on failure
    emit(phones?.let { judge.decide(m, it, runtime = runtime) } ?: fast)
}

// leaving LESSON
zipa.trim()
```

`score` awaits a job on the host thread, so the 600 ms timeout returns on time even while the inference finishes in
the background (the next take waits behind it). For callers without coroutines, `zipa.port()` is the blocking
`PhoneScorerPort` (16 kHz in).

## Assets

`modelAssets` copies every entry of `spec/models/manifest.json` from `~/.gradle/fonia-models/<cache>` into
`build/generated/fonia-models` after checking bytes and SHA-256, and **fails with what to do** when the file is missing
(fetch it once with `./gradlew :engine:phones:test` on a PC with network). Nothing is committed; the app downloads
nothing.

## Memory, threads, size

- Session: 2–4 intra-op threads (half the cores), one inter-op thread, spinning off. Emulator (x86_64): warm-up 1.1 s,
  PSS +85 MB warm, 52 ms p50 / 62 ms p95 per 1.2 s take (resample 2 ms, model 44 ms, GOP 4 ms). A real phone is unmeasured.
- APK (arm64-v8a): model 70.7 MB stored + `libonnxruntime.so` 34.2 MB stored (12.8 MB if native libs are compressed
  with `useLegacyPackaging`, at the cost of extraction on install) + JNI 0.1 MB + ~0.3 MB dex ≈ **+105 MB**
  (≈ +84 MB with compressed native libs); download (store-compressed) ≈ +68 MB.

## Pitfalls

- Without `noCompress += "onnx"` in the **app**, `AssetModelFile` falls back to copying 70 MB to `noBackupFilesDir`.
- The emulator is too full for the 113–153 MB test APK: run the instrumented test with
  `-Pfonia.abi=x86_64 -Pfonia.ml.modelOnDevice=true` after
  `adb push ~/.gradle/fonia-models/zipa-small-crctc-500k/model.int8.onnx /data/local/tmp/fonia/zipa-small-crctc-500k/`
  (and remove it afterwards). The asset-mapping assertion then runs only when the model is in the APK.
- AGP's incremental packaging leaves holes: after toggling those flags delete `build/outputs/apk/androidTest` or the
  APK keeps its old size. `connectedDebugAndroidTest` reports BUILD SUCCESSFUL even when the install fails: read the
  log or `build/outputs/androidTest-results`.
- `RunOptions.setTerminate` is not used: a timed-out take still occupies `ml-zipa` until it ends (≈ the model time).
