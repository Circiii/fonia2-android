# :core:mouth3d — the stylised 3D mouth (packages H6–H8)

A procedural, stylised 3D mouth — a premium-toy "bust slice" — drawn by a small own OpenGL ES 3 renderer inside a
Compose composable (decision F6). One closed model is seen from any angle; at the profile the near cheek slides away
and the cut is capped, so the child sees teeth, palate, ridge and tongue as real volumes cut on the midline.

- **Head** (`style/HeadModel.head`): one watertight surface — an ellipsoid head shell (cheeks with a soft blush, nose
  tip, chin, jaw) whose skin runs over the lips (soft cupid's bow, full volume, glossy), rolls into the mouth and lines
  the cavity: vestibule, gum crests over the teeth's necks, lingual gums, palate with the ridge, cheeks, floor, throat.
  The solid between skin and lining is the tissue the cut caps. The lower half turns with the jaw (about the joint).
- **Teeth**: closed crowns (superelliptic sections, rounded chisel edge, labial bulge) along two curved arches, necks
  sunk in the gums, a little occlusion toward the gum.
- **Tongue**: the side view's closed outline (`SagittalShape.tongue`, the reviewed 2D side drawing) inflated across into
  a body — slices shrink toward the outline's centre, so it rounds off, the tip is narrow and it can never cross
  itself. Centre groove (deeper for S), cup (Ș), sides lowered and narrower (L). Its root sinks into the floor.
- **Cut-away** (`StyleScene`): a clip plane slides from outside the near cheek to the midline; each closed mesh is
  re-drawn into the stencil (parity), then a flat quad on the plane is drawn where the stencil is odd: tongue, teeth,
  then the head's tissue over them (so the tongue's root and the teeth's necks vanish into the floor and the gums).
  The head's cap is tissue-coloured with the lips' red in front of the incisors. `CutOverlay` adds the airflow, the
  contact ring, the voice mark, R's motion ticks, the tongue's outline and, for the tongue step, a glow.
- **Front cues**: R shows white motion arcs either side of the tip; L's tongue is narrower, so dark gaps show beside it
  (the air goes round there); the air step shows the air leaving the mouth.

## Public API

```kotlin
Mouth3D(
    sound: SoundId?,            // null = rest
    progress: Float,            // 0 rest … 1 the sound's pose (springs smooth every change)
    inside: Boolean,            // swing to the profile and open the cut; only for approved poses (N17) or the override
    colors: Mouth3DColors,      // from the app's tokens; Mouth3DColors.Default follows docs/v2/04 §7
    modifier: Modifier = Modifier,
    playing: Boolean = true,    // Ț/CE/GE closure → release (air held, then a puff), R flutter + pulses, L release
    autoRotate: Boolean = false,
    reduceMotion: Boolean = rememberReduceMotion(),
    allowUnapprovedInside: Boolean = Mouth3DReview.showUnapprovedInside,  // review/owner override only
    onDoubleTap: (() -> Unit)? = null,        // e.g. toggle inside
    onFrameStats: ((Mouth3DFrameStats) -> Unit)? = null,
    forceFallback: Boolean = false,           // 2D side diagram
    autoFallback: Boolean = true,             // 2D after 30 frames > 12 ms
    groups: Set<PoseGroup> = PoseGroup.ALL,   // the guide's step-by-step reveal
    highlight: Articulator? = null,           // the step's part glows (lips, teeth, tongue; air = airflow)
)
Mouth3DAssets.load(context): Mouth3DData   // cached; .insideAvailable(sound), .spec(sound).caption/.description/.steps
Mouth3DReview.showUnapprovedInside = true  // owner/debug switch, honoured by every insideAvailable / Mouth3D
PoseStep.articulator                       // the step chip's part, for `highlight`
```

Unchanged signatures. New behaviour: drag ±100° sideways (±25° up/down, springy return after 2 s); turning past 80°
opens the cut by itself when the pose may show it (approved or the override) — `SceneInputs.cutAllowed`,
`SceneFrame.cutAmount`. `Mouth3DData` no longer has `mesh` / `poser` (the GNM mesh is gone; nobody outside used them).

Pure, JVM-tested parts: `style/Geo`, `style/Sagittal` (side anatomy, tongue blend, air path), `style/Head`,
`style/CutOverlay`, `style/StyleScene`, `MouthScene`, `PoseLibrary`. Android parts: `render/StyleGl` (one shader, the
cut plane, stencil passes, MSAA), `render/Mouth3DThread` (EGL with stencil on a TextureView), `Mouth3D.kt`.

## Cost

≈ 7 k vertices / 13.6 k triangles a frame (head 3.4 k, teeth 2.6 k, tongue 1.6 k vertices), rebuilt on the CPU into
reused arrays in ≈ 0.7 ms on a desktop JVM (estimate 2–3 ms on a mid phone). One draw pass; the cut adds three stencil
re-draws of the same buffer and three quads. MSAA 4× from EGL, else a 4× multisampled FBO resolved by a blit. The GL
thread draws only while something moves (~30 fps for the idle breath/air), nothing while paused; a context loss
rebuilds everything from the CPU side. Not zero-allocation yet: the side-profile maths (`SagittalShape`) still builds
small lists each frame.

## Data

`spec/poses/mouth3d/<rest|s|sh|z|j|ts|ce|ge|r|l>.json`: channel values (`PoseChannel`), optional `animation` /
`flutter`, `air` (`stream`: narrow | wide | pulse | lateral, `warm`, `stop`), `voiced`, camera presets (inside: yaw 90°,
pitch 0°), `"insideApproved": false`, caption key + text, TalkBack text, a note for the therapist. A therapist approves
a pose by setting `insideApproved: true` and filling `review`. Copied into the assets (`mouth3d/poses/`) by the
`mouth3dAssets` task. No network, no mesh file.

`TongueBlend` anchors must match the pose files: when a pose's tongue channels or jaw change, update its anchor
(`StyleGeometryTest` checks exactness, tip positions and that head, teeth and tongue stay closed meshes).

## Previews

`python -I tools/mouth3d/preview.py style OUT [--only s,sh] [--clip rotate|front]` (needs `JAVA_HOME`) runs the JVM
software copy of the shader and its passes (`src/test/.../StylePreview.kt` — keep it identical to `render/StyleGl.kt`)
and writes `sheet.png` (front / 45° / profile / cut-away per sound), one PNG per view, closer looks and, with `--clip`,
an mp4. Artefacts: `C:\Users\circi\Desktop\HSIC\output\gura3d\v3\`.

Demo: `:mouth3d-demo`, `adb shell am start -n tech.fonia.mouth3d.demo/.DemoActivity --es sound sh --ez inside true`
(extras also `progress`, `play`, `spin`, `fallback`, `autofallback`, `ui`, `stage` face|blue, `highlight`
lips|teeth|tongue|air, `box` = the guide's 250 dp card).

## Licence

Everything is procedural; the side anatomy is the app's own 2D drawing. Nothing from Google GNM remains (the mesh,
its export script and NOTICE were removed on 2026-11-21) — the licences screen no longer needs the GNM line.

## Pitfalls

- Every capped mesh must stay watertight: a hole or a fold flips the stencil parity and leaves a hole in the cap. Rows
  behind the lips are forced to lie further back than the row before (`zPrev`), poles are single points.
- Cap order matters: tongue, teeth, then the head (same plane, `GL_LEQUAL`, identical quad).
- Cavity rows are clamped behind the face (`skinZ − 4…6 mm`) so nothing pokes through the cheeks at any angle.
- Kinds (`style/Kind`) are per triangle; the shader takes them `flat`. No face culling: lighting is two-sided.
- The emulator (SwiftShader) has no MSAA config: `StyleGl` uses the multisampled FBO (depth24-stencil8).
