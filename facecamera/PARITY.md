# `:facecamera` parity with iOS `ARKitFaceSource` + `FaceCameraView`

The camera face source behind `:face`'s `FaceTracker` seam (face/PARITY.md, "Interfața pentru sursa cu cameră"):
CameraX frames from the front camera, analysed by MediaPipe's Face Landmarker models run directly on LiteRT, handed to
`TrackerFaceSource` as `FaceObservation`s. The MediaPipe Tasks library is not used: its `TasksStatsLogger` sends usage
statistics to Google with no documented opt-out, and this app is used by children (02_stack_android_ml.md, item 24).
Specification: inventory §7.2, §7.4, §7.5, §7.7, §7.8, §7.9, §7.12, §7.13 and §13.4.1; `FoniaFace/ARKitFaceSource.swift`,
`FaceCameraView.swift`, `FaceSource.swift`. Models, licences and test data: MODELS.md.

| iOS | Android (`tech.fonia.facecamera`) |
|---|---|
| `ARKitFaceSource` (an `ARSession` with `ARFaceTrackingConfiguration`) | `CameraFaceSource` = `TrackerFaceSource` (from `:face`, ARKitFaceSource's control flow) over `CameraFaceTracker` (CameraX + `FaceLandmarkerPipeline`) |
| `ARKitFrameReceiver.reading(of:camera:)`: 7 blendshapes, `viewMatrix(for: .portrait) * transform`, `lookAtPoint` | `FaceLandmarkerPipeline` + `observationOf`: the 7 coefficients by ARKit name through `BlendshapeCalibration`, the head pose, the gaze |
| `requireCameraPermission()` | `CameraFaceTracker.requireCameraPermission()`; the prompt is the hosting `FaceCameraView`'s Activity launcher |
| `makePreferredFaceSource()` | `makePreferredFaceSource(context)` |
| `FaceCameraView` / `FaceCameraHostView` | `FaceCameraView` composable: `PreviewView` (mirrored, fill) or the `face.smiling` placeholder |
| `VisionFaceSource` | not ported: every Android phone gets the blendshape path; see Differences |
| the ARKit delegate queue (`.userInteractive`) | one process-wide camera thread, `tech.fonia.face.camera`, `THREAD_PRIORITY_DISPLAY` |

## The API `:features` uses

```kotlin
// MouthCoach.swift:59: the source is made once, when the coach appears.
val source = remember { makePreferredFaceSource(context) } // CameraFaceSource; FakeFaceSource only on an emulator without a front camera
val levels = remember { FaceLevels() }
// The camera card (MouthCoach.swift:131-160), rebuilt per step like `.id(step.id)`: start on entering, stop on leaving.
key(step.id) {
    FaceCameraView(source, levels, Modifier.clip(RoundedCornerShape(14.dp))) { error -> cameraProblem = error }
}
// The hold (MouthCoach.watch): every FaceHold.POLL_INTERVAL, hold.poll(check, levels.snapshot()).
// FaceSourceError.CameraDenied → "Camera e oprită. O pornești din Configurări, la Fonia."; anything else → "Camera nu merge acum."
```

`:features` should depend on `project(":facecamera")` instead of `libs.mediapipe.tasks.vision` (that dependency would
bring the Tasks library and its statistics back). Optional: `(source as? CameraFaceSource)?.tracker?.calibration` takes
recalibrated curves; `tracker.analysisListener` receives every analysed frame (the recording tool uses it).

## From a camera frame to a `FaceObservation`

1. **Camera.** `ImageAnalysis`, 640×480 (4:3, what the iOS Vision path asks for; the face is resampled to 256×256
   anyway), RGBA, `STRATEGY_KEEP_ONLY_LATEST`: a frame that arrives while the last one is analysed is dropped. A
   `Preview` at 4:3 feeds the `PreviewView`. Both are bound to a lifecycle that is resumed only between `run` and
   `pause` and only while the hosting screen is started.
2. **Orientation.** The buffer stays in sensor orientation. `RgbaFrame` takes CameraX's clockwise `rotationDegrees`
   (front cameras: usually 270) and a mirror flag read from `sensorToBufferTransformMatrix` (a reflection; the
   analysis buffer is not mirrored on the devices CameraX knows). Every sample position is computed in the upright,
   unmirrored image ARKit's portrait camera space describes and mapped with integers onto the buffer, so nothing is
   rotated or copied besides one `ByteBuffer.get` into a reused array.
3. **MediaPipe's graph, by hand** (`face_landmarker_graph.cc` v1.2.2 and subgraphs, each constant from the source):
   - no tracked face: the short-range BlazeFace detector on the whole frame letterboxed to 128×128 (zero border, values
     in [−1, 1]); 896 SSD anchors (strides 8/16/16/16, interpolated scale), sigmoid scores ≥ 0.5, weighted NMS at
     IoU 0.5 keeping the best cluster, projection out of the letterbox, a rectangle from the box rotated by the eyes
     (keypoints 0→1) and scaled 1.5;
   - tracked face: the rectangle from the previous frame's landmarks (bounding box, rotation 33→263, squared on the
     long side, scaled 1.5);
   - the rectangle resampled to 256×256 (replicated border, values in [0, 1]) exactly as MediaPipe's CPU
     `ImageToTensorCalculator` does it: OpenCV `warpPerspective` with fixed-point bilinear weights (1/32-pixel
     positions, 15-bit weights, `(32767, 0, 0, 1)` on exact pixels);
   - face mesh: 478 landmarks, presence = sigmoid > 0.5, otherwise the face is lost and the next frame detects again;
     landmarks projected back with the image-size projection matrix (`LandmarkProjectionCalculator`);
   - stream mode: the One Euro filter of FaceLandmarksDetectorGraph (min cutoff 0.05, beta 80, derivative cutoff 1),
     in pixels, scaled by the face size, reset when the face is lost; the next region uses the raw landmarks;
   - blendshapes: 146 landmarks in pixels → 52 scores;
   - head pose and gaze (below).
4. **Seam.** `BlendshapeCalibration` maps the six coefficients MediaPipe has to ARKit's scale (identity for now);
   `tongueOut` is 0. `faceToCamera` is the pose with the fit's scale divided out and the translation in metres (ARKit's
   units); `lookAt` is the gaze in face space. `FaceFrameReceiver` turns it into a `FaceFrame` as on iOS.

Everything in steps 2–4 is allocated once; on the device a tracked frame allocates 2 small objects (64 bytes), both
inside LiteRT's Java wrapper (`LatencyTests.trackedFramesDoNotAllocate`). The seam's `FaceObservation` (seven small
immutable objects) is the only allocation of ours per frame, as `FaceFrame` is a value on iOS.

### Head pose and gaze

**Pose.** MediaPipe's face geometry pipeline (`geometry_pipeline.cc`, FACE_LANDMARK_PIPELINE, the graph's defaults:
perspective camera with a 63° vertical field of view, near plane 1 cm, top-left origin): the 468 mesh landmarks are put
on the near plane, scaled twice against the canonical face with weighted orthogonal Procrustes fits on the 33-landmark
basis, and the last fit is the pose. The rotation is found with Horn's quaternion method (a 4×4 Jacobi eigen-solve) in
double precision, which gives the same rotation as MediaPipe's SVD. Axes are ARKit's: camera +x right on the upright
unmirrored image, +y up, +z towards the viewer; face +x to the face's left, +y up, +z out of the face; so a face
looking into the camera has the identity rotation and `FaceFrame.headYaw` is positive towards the right of the
unmirrored image (pout: +37.8°; the same frame mirrored: −37.0°).

**Gaze.** The mesh also places the irises (468, 473). Both eyes go through the same conversion into face space, and
each eye's direction comes from where its iris centre sits between the corners (33/133, 263/362), as on an eyeball
whose radius is 0.45 of the corner-to-corner width: `yaw = asin(dx/r)`, `pitch = asin(dy/r)`. An eye is left out when
its blink score is over 0.5, when the head is turned away from it by more than 30°, or when its angles pass 35° (yaw)
or 30° (pitch), which is what a closed or hidden eye gives. The others are averaged; with none left, `lookAt =
(0, 0, 1)` and the gaze is the head direction. On `smile` (head 6° off, eyes back on the camera) the gaze comes out
within 4° of the camera; on `pout` and `wide_open` both eyes are left out. `canEstimateEyeGaze = true`. This is an
estimate not measured against ARKit's `lookAtPoint`; its only reader, PresenceTracker, compares each reading with the
speaker's own first moments, so a constant offset cancels out.

## Validation

The official MediaPipe Face Landmarker (Python 1.2.2) gave the reference values; the pipeline was first written in
numpy on LiteRT's Python interpreter (`tools/face_reference/prototype.py`) until it matched, then in Kotlin.

| Check | Where | Result |
|---|---|---|
| Five still frames, IMAGE mode: 478 landmarks (0…1), 52 scores, pose | `ModelParityTests`, LiteRT on the sp_d emulator | landmarks ≤ 9.8e-6, scores ≤ 1.7e-4, rotation ≤ 2.9e-5, translation ≤ 4.3e-4 cm |
| The same, numpy prototype on the PC | `tools/face_reference/prototype.py` | landmarks ≤ 2.3e-5, scores ≤ 5.9e-4, rotation ≤ 3.1e-5 |
| Model inputs (detector 128², landmarks 256²) for `smile` and `wide_open` | `FrameSamplerTests` (JVM) | identical to the prototype's, pixel for pixel |
| Buffers rotated 90/180/270 and mirrored, with the flags | `FrameSamplerTests`, `OrientationTests` | identical inputs; identical landmarks and scores (≤ 1e-6) |
| Pose from MediaPipe's own landmarks | `FaceGeometryTests` (JVM) | rotation ≤ 2e-4, translation ≤ 2e-3 cm of MediaPipe's matrix |
| VIDEO mode, 24 frames: sliding face, two gray frames, the face back | `StreamParityTests` | a face on the same frames (the detector runs at frames 0 and 14, tracking in between); landmarks ≤ 2.6e-4, scores ≤ 3.9e-3, rotation ≤ 3.9e-4 |
| One Euro smoothing | `LandmarkSmootherTests` (JVM) | ≤ 2e-6 of a transcription of `one_euro_filter.cc` |

Tracking feeds each frame's landmarks into the next frame's crop, and the 1/32-pixel resampling makes that loop
sensitive: in the prototype, nudging frame 0's landmarks by 1e-6 grows to 4.5e-4 in landmarks and 6e-3 in scores
within ten frames (`tools/face_reference/sensitivity.py`). The stream differences above are of that size, so the
stream test's bounds are looser than the stills'.

Not validated here: the values on children's faces and against ARKit on the same faces; that is the recalibration.

## Mapping to ARKit's scale, and the recording tool

The mouth coach's thresholds (`smile > 0.42`, `jawOpen > 0.22`, `pucker > 0.42`, …, inventory §7.9.1) were tuned by
eye on ARKit. MediaPipe's blendshape model imitates ARKit, but the same face need not give the same number.
`BlendshapeCalibration` holds one increasing piecewise-linear curve per coefficient the seam carries, `(model score →
ARKit value)` knots, clamped at both ends; `IDENTITY` passes the scores through. On the five frames the identity
already gives the expected checks (`SeamTests.theCoachsChecksOnRealFaces`: `smile` and `small_smile` pass `.smile`,
`wide_open` passes `.open` and `.wideOpen`, `speaking` passes `.relaxed`, `pout` passes `.lipBite`, nothing passes
`.tongueOut`).

**Recording.** `FaceRecording` writes one CSV row per analysed frame: time, the pose being held (a `FaceCheck` raw
value or `neutral`), presence, whether the detector ran, the 52 raw scores (`score_*`), the seven mapped values
(`mapped_*`), head and gaze angles, eyes used, the model's tongue score, and the analysis time. Numbers only. The
debug-only `FaceRecorderActivity` (`adb shell am start -n <package>/tech.fonia.facecamera.FaceRecorderActivity`)
shows the camera and the values the coach would read, lets the person pick the pose and records to
`/sdcard/Android/data/<package>/files/face-recordings/*.csv` (`adb pull`). `RecordingToolTests` and
`RecorderScreenTests` cover both.

**Recalibrating** (to do with real faces, adults first, children only with the parent's consent):
1. the same people hold the same poses (`neutral` and the eleven checks), a few seconds each, on an iPhone and an
   Android phone; the iPhone side needs a small debug recorder that writes ARKit's seven coefficients in the same CSV
   layout (not part of this module);
2. per coefficient, pool the frames of all poses and match quantiles between the two platforms; the matched pairs are
   the knots (monotone by construction);
3. check per pose and per check how often the iOS rule passes on iPhone and the same rule passes on Android after the
   mapping, on people left out of the fit;
4. ship the curves in `CameraFaceTracker.calibration` (from `:features`), and record the data set and fit here.

## Latency (sp_d emulator, x86_64, 2 LiteRT threads, other emulator users running)

| Frame | Median | 90th percentile |
|---|---:|---:|
| tracked (landmarks 11.6–17.5 ms, blendshapes 0.7–1.1, pose and gaze 0.1–0.2) | 13.0–20.2 ms | 14.8–24.2 ms |
| with the detector (detector 3.1–4.5 ms) | 15.9–23.3 ms | 17.3–28.8 ms |
| camera frame without a face (copy + detector) | 4.1 ms | |

Ranges over three runs; the later ones shared the emulator with other test runs. A frame has 33 ms at 30 fps; the coach reads
every 60 ms. No phone was measured: the 256×256 face mesh is the cost, and on a 2023 mid-range phone (2 big cores)
XNNPACK is expected in the same range; if not, keep-only-latest lowers the rate rather than the latency. GPU
(`litert-gpu`) was left out: MediaPipe itself keeps the blendshapes on the CPU with GPU, and the emulator has none.

## No network, no telemetry

- No `INTERNET` permission in the module's manifest. litert-api's `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC`
  (for Play AI-pack downloads) are removed with `tools:node="remove"` (the merged test manifest has neither).
- `com.google.android.play:ai-delivery` is excluded from LiteRT (it pulls Google Play services and asset delivery);
  `consumer-rules.pro` silences R8 about the classes that referenced it, which are never loaded.
- `NoTelemetryTests` (JVM): no MediaPipe Tasks, data transport, Firebase, Clearcut, Play services or AI-pack classes on
  the module's runtime classpath; the manifest asks for the camera only.
- `NoNetworkTests` (device): while the models load, run five stills and a 24-frame stream, and the camera tracks for
  3 s, every file descriptor of the process is watched every 5 ms for a new socket, and StrictMode reports untagged
  sockets. Result on sp_d: 85 camera frames, no new socket, no violation.
- `ACCESS_NETWORK_STATE` appears in the merged manifest through CameraX 1.6's `camera-view` → `camera-video` →
  Media3; it reads the network type and opens nothing (`:audio`'s ExoPlayer brings it too).

## Differences from iOS

| What | Why | Instead |
|---|---|---|
| `tongueOut` is 0 and `canDetectTongue = false` | MediaPipe's blendshapes have `_neutral` where ARKit has `tongueOut` | `.tongueOut` (`en-th`) never passes; the step keeps "Am făcut", as on the iOS Vision path. The landmark model has an undocumented `tongue_out` output that MediaPipe drops (0.0000–0.054 on the five frames, none with a tongue out); it is recorded (`tongueOutModel`) and unused until measured |
| `kind` is `FaceSourceKind.ARKIT` | `:face` has no Android case | The source follows ARKit's path (blendshapes, pose, gaze), not Vision's |
| Values from MediaPipe's model, thresholds from ARKit | Android has no ARKit | Identity mapping until the recordings above; the "Am făcut" button stays, as on iOS |
| Gaze from the iris landmarks | No eye tracker | The estimate above; the head direction when the eyes are not reliable |
| Pose camera: MediaPipe's assumed 63° field of view | The real intrinsics are not used | Angles match MediaPipe; the translation is approximate (unused by `FaceFrame`) |
| 30 frames a second | CameraX's default rate; ARKit runs face tracking at 60 | The coach polls every 60 ms and counts polls, not frames, so the hold times are the same |
| A lost face is absent for one frame, the next frame detects | MediaPipe's tracking loop | Same as MediaPipe; `FaceHold` subtracts 0.03 s per missed poll, as on iOS |
| No Vision fallback | iOS's Vision path is for devices without ARKit; every Android device with a camera gets the blendshape path | `makePreferredFaceSource` returns the camera source whenever a front camera exists |
| The scripted face on an emulator only without a front camera | An emulator can have a camera (virtual scene, webcam); the simulator has none | `preferredFaceSourceKind(isEmulator, cameraSupported)`; a phone without a front camera gets the camera source, unsupported, as iOS gets an unsupported Vision source |
| The view's lifetime follows the composition, not `didMoveToWindow` | Compose | `LaunchedEffect` starts the source (cancelled with the composition, so a view that leaves first never starts it), `DisposableEffect` stops it |
| Background: the camera stops through the host lifecycle | Android does not interrupt the camera for us | `onInterrupted` (face absent) when the screen stops, frames again when it starts, as an ARKit interruption |
| The permission prompt needs an Activity | Android | `FaceCameraView` lends its launcher; outside a view the permission must already be granted (`CameraDenied` otherwise). A "don't ask again" refusal answers at once with `CameraDenied`, as iOS's `.denied` |

## Tests

The four tests `:face` deferred to the camera step (face/PARITY.md), now on a device:

| iOS test | Android | On sp_d |
|---|---|---|
| `FakeSourceTests.simulatorFallback` | `CameraFaceTrackerTests.simulatorFallback`: an emulator without a front camera gets the scripted source; the camera source is unsupported, has no tongue, has gaze, and refuses to start (iOS checks ARKit and Vision; Android has one camera source) | passes |
| `CameraViewLifetimeTests.followsTheWindow` | `FaceCameraViewTests.followsTheWindow` | passes |
| `CameraViewLifetimeTests.leavingBeforeTheTaskRuns` | `FaceCameraViewTests.leavingBeforeTheTaskRuns`: a `ComposeView` attached and detached in one main-thread task | passes |
| `CameraViewLifetimeTests.unsupportedSource` | `FaceCameraViewTests.unsupportedSource` | passes |

New tests: 31 on the JVM (`testDebugUnitTest`: bundle, sampler, detector decoding, geometry, smoothing, the seam and
the mapping, the source choice, no telemetry, the CSV) and 17 on the device (`connectedDebugAndroidTest`, sp_d,
Android 16: the four above, model parity, orientation, stream parity, latency and allocations, the camera on a real
camera stream, no network, the recording tool and its screen). sp_d has no front camera, so the camera tests run the
tracker on its emulated back camera (a moving test pattern, no face); on a phone they use the front camera.

## Libraries (in `facecamera/build.gradle.kts`)

| Library | Version | Licence | Note |
|---|---|---|---|
| `com.google.ai.edge.litert:litert` (+ `litert-api`) | 2.3.2 | Apache-2.0 | literal coordinates; `com.google.android.play:ai-delivery` excluded; brings Guava and `kotlinx-coroutines-guava` |
| CameraX `camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view` | 1.6.2 (catalog) | Apache-2.0 | |
| Compose UI, Foundation (BOM 2026.09.00), `activity-compose` 1.13.0, `lifecycle-runtime-compose` 2.11.0, coroutines 1.11.0 | catalog | Apache-2.0 | |
| Tests: `kotlinx-serialization-json`, `androidx.test` runner/rules/ext, Compose `ui-test-junit4` / `ui-test-manifest` | catalog | Apache-2.0 | |
