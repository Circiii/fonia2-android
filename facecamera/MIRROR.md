# MIRROR: the mirror with lip guides (H1–H4)

The front camera watches the lips and jaw; the screen shows a mirrored preview with the child's own lip contour and
the target lip shape for the chosen sound; `:engine:face` turns each frame into a `MouthState` and a `CameraEvidence`.
Frames stay in RAM: nothing is saved or sent (`docs/v2/04` §11).

## Public API

| Name | Where | What |
|---|---|---|
| `FaceMeshFrame` | `:engine:face`, `tech.fonia.face` | H1 seam: lip rings (outer, inner, 20 points each), 12 anchors, 10 iris points, 52 blendshape scores, head yaw/pitch/roll, distance, presence, the model's tongue score, mouth/face luma, a 64×64 mouth crop. Reused buffers. Indices in `FaceMeshIndices` (verified against MediaPipe `FACEMESH_LIPS`). |
| `MouthStateEngine` | `:engine:face` | `process(FaceMeshFrame, MouthState)`: measures ÷ eye distance, quality gate, rest calibration, One-Euro smoothing, tongue heuristic. `recalibrate()`, `irisMm` (10.6 child / 11.7 adult), `speaking`. `targetRings(frame, LipPose, outer, inner)`. |
| `MouthState` | `:engine:face` | `quality` (`FaceQuality.Good/Weak/Absent`) + `issue` (`QualityIssue`), `aperture`/`width`/`jaw` (÷ W₀, the child's rest mouth width), `open`, `round`, `spread` (0…1), `lipsClosed`, `smile`, `tongue` (`TongueState`), mm values, confidences. |
| `PoseSpec.bundled(SoundId)` | `:engine:face` | `spec/poses/<s|sh|z|j|ts|ce|ge|r|l>.json`: `lips` (target ring), `cameraChecks` (bands + advice keys), `visible` (`tongue: false` for every sound). Thresholds in `spec/poses/camera.json` (`CameraSpec.BUNDLED`). |
| `TargetMatcher(pose).update(state, CameraEvidence)` | `:engine:face` | `status` (`Matches`/`Adjust`/`Abstain`), `match` (m), `matched` (hysteresis 0.75/300 ms on, 0.55/400 ms off), `advice` (`CameraAdvice`: `lips.round`, `lips.spread`, `lips.lessRound`, `mouth.openMore`, `mouth.openLess`, `lips.part`…), `abstain` + `issue`. |
| `MouthLevels` / `MirrorSnapshot` | `:engine:face` | Lock-free latest snapshot (sequence lock): state, evidence, child rings, target rings. `read(into)` from any thread, no allocation. |
| `MouthTracker(CameraFaceSource)` | `:platform:camera`, `tech.fonia.facecamera` | Runs the engine on the camera thread. `sound`, `onMouth(open, round, nanos)`, `onState(state, evidence)`, `speaking`, `recalibrate()`, `levels`. |
| `MirrorView(source, faceLevels, mouth, sound, colors, …)` | `:platform:camera` | Mirrored `PreviewView` (via `FaceCameraView`) + `LipTargetOverlay`, edge to edge. |
| `LipTargetOverlay(levels, colors, …)` | `:platform:camera` | The guide alone, drawn in the draw phase (no recomposition per frame). |
| `MirrorColors(target, matched, childLips, neutral, hintBackground, hintText)` | `:platform:camera` | Passed in by the screen (design system); never red. |

## Showing the mirror for a sound

```kotlin
val source = remember { CameraFaceSource(context) }           // or makePreferredFaceSource(context)
val faceLevels = remember { FaceLevels() }
val mouth = remember(source) { MouthTracker(source) }
MirrorView(
    source = source, faceLevels = faceLevels, mouth = mouth, sound = SoundId.Sh,
    colors = MirrorColors(target = Blue, matched = ParrotGreen, childLips = White, neutral = Grey,
                          hintBackground = Black.copy(alpha = 0.55f), hintText = White),
    modifier = Modifier.fillMaxSize(),
    reducedMotion = settings.reducedMotion,
)
```

The rest ("Fă o față liniștită, cu gura închisă", 1 s) is taken automatically when the mirror opens (`MouthTracker`
resets on `attach`); after 4 s without a steady closed mouth it takes the 15 most closed frames. Call
`mouth.recalibrate()` if the adult asks. `MirrorSnapshot.restProgress` (0…1) can drive a progress ring.

## Mouth values for the live band and the session

```kotlin
mouth.onMouth = { open, round, nanos -> director.pipeline.postMouth(open, round, nanos) }   // camera thread
mouth.onState = { state, evidence -> /* copy what you keep: objects are reused */ }
mouth.speaking = liveSnapshot.speaking                                                       // optional
// or pull from any thread:
val snap = MirrorSnapshot(); if (mouth.read(snap)) { snap.state.round; snap.evidence.advice?.key }
```

`onMouth` fires only while the quality gate is `Good`; otherwise nothing is posted and the band's `mouthNanos` ages
(the band must treat old values as unknown). `nanos` is `System.nanoTime()` at publication (CLOCK_MONOTONIC).

## Still images through the real pipeline (debug builds)

`StillFrameFeed(context, tracker)` (`src/debug`, so only debug builds of a consumer see it) runs upright RGBA images
through the same Face Landmarker models on the camera thread and hands each result to `tracker.meshListener`, as a camera
frame would: a `MouthTracker` attached to that tracker cannot tell the difference, and the camera never opens.
Keep-only-latest (`submit` returns false while busy); `reset()` before an image that jumps (a fresh detection). `:app`
uses it for `--es face fixture` (emulators: the front camera is the host webcam). Stills make the quality gate flicker
`Unsteady` now and then; a screen should forgive single frames.

## Permissions and privacy

- `CAMERA` only (declared here); `FaceCameraView` asks through the host Activity's launcher. Without permission the
  mirror shows nothing from the camera and `onMouth` never fires: everything else must work with "Am făcut".
- No `INTERNET`. No frame, crop or landmark is written anywhere; `RestBaseline` dies with the screen.
- Show the parent explanation before the first permission request (docs/v2/04 §11).

## Pitfalls

- **Mirroring happens once, at drawing**: all engine coordinates are the upright, *unmirrored* image; left/right are
  the child's own (`TongueState.Left` = the child's left = the screen's left in the mirror).
- The overlay assumes the preview and the analysis share the 4:3 field of view and `PreviewView` is `FILL_CENTER`
  (both set by `FaceCameraView`/`CameraFaceTracker`). If a screen crops the preview differently, the rings drift.
- The camera judges only what is visible. `PoseSpec.visible.tongue` is `false` for every sound: never tell the
  child about the tongue from the camera. The camera chooses advice; the microphone gives the verdict.
- `TongueState` is for the gymnastics warm-ups only (mouth open, tongue out). It needs the rest to separate lip and
  skin colours; otherwise it stays `Unknown` → keep "Am făcut".
- Thresholds are relative to the child's rest. Until it is taken (`MouthState.rest == Default`, ~1 s of calm face
  at the start, 4 s at most) the evidence is `Abstain` with `AbstainReason.Calibrating`: on real video the population
  fall-back read narrow mouths as rounded. Ask for "o față liniștită" when the mirror opens.
- `MouthStateEngine.tongueEnabled = false` saves ~1.5 ms per frame (emulator) where no tongue gymnastics run.
- One `MouthTracker` per camera tracker: it owns `CameraFaceTracker.meshListener`.
- Real-video results and limits: `output/camera/RAPORT_CAMERA.md` (HSIC folder).
