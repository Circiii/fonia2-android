# :core:mouth3d — the 3D articulation guide

The lower face is drawn by the OpenGL ES 3 renderer inside Compose. Its visible parts move with the sound's pose.
In profile the near cheek can be cut away to reveal the tongue, teeth, palate and direction of the air.

- **Skin and lips** (`style/Perioral.kt`, `style/Head.kt`): nose, philtrum, cheeks and chin start from a sampled
  face mesh. The vermilion border, pads and wet line are separate contours. The lips turn behind the commissures,
  round forward at the centre, and blend into the skin as the jaw and lip pose change. The same closed surface
  continues through the vestibule, gums, hard and soft palate, floor and throat.
- **Teeth** (`style/ReferenceAnatomy.kt`): 28 closed crowns adapted from the MakeHuman arches, with separate
  upper and lower movement. The model excludes wisdom teeth and uses a gradual enamel colour toward the gingiva.
- **Tongue**: its sagittal landmarks come from the existing pose guide. The 3D surface uses a broad dorsum,
  a narrower blade, a rounded tip and a root embedded in the floor. A central groove, cupping and the lateral
  shape are driven separately by the pose. Air markers exit between the registered incisors and wet lip edges.
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

About 8.4 k vertices and 16 k triangles in the front view, rebuilt on the CPU into reused arrays. The preview records
the current JVM build timing in `timing.txt`. One draw pass; the cut adds three stencil
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

## Anatomy references and provenance

- [MakeHuman body mesh](https://github.com/makehumancommunity/makehuman/blob/a8bc2d54ff0ac92e78ff71431b1023eda42bf482/makehuman/data/3dobjs/base.obj)
  and [system pack `teeth/teeth_base/teeth_base.obj`](https://static.makehumancommunity.org/assets/assetpacks/makehuman_system_assets.html)
  are CC0. The face is resampled as a depth field. The tooth asset is split into crowns, triangulated and registered
  to the guide's incisal edge and jaw pivot. The source gum shell is replaced by Fonia's deforming cavity.
  Source SHA-256 values are `8e761e6624b8f54536409135d1636da63b32486a90d4897f84e121d144f6fb4c` and
  `f55198069e55d360c4b4cc7ecb1cc292b2c7665753ab8b65eabf46a8783f2875` respectively.
- [Dresden Vocal Tract Dataset](https://springernature.figshare.com/articles/dataset/Dresden_Vocal_Tract_Dataset_complete_archive_/11897187)
  (CC0) and [Birkholz et al. 2020](https://doi.org/10.1038/s41597-020-00597-w).
  Inspected subject 1 airway meshes for /s/, /ʃ/, /l/ and /i/ on three sagittal planes.
  These are segmented air spaces in sustained German sounds. They provide references for the central
  constriction and lateral escape, and are distinct from the meshes packaged in the app.
- [Badin et al. 2002](https://www.gipsa-lab.grenoble-inp.fr/~christophe.savariaux/PDF/pb_JPHON02.pdf)
  motivates independent lip, jaw and tongue controls. [Sforza et al. 2010](https://pubmed.ncbi.nlm.nih.gov/20570070/)
  documents variation in vermilion height and mouth width. The depicted face represents an adult demonstrator.
- The [Romanian Academy phonetics reference](https://iit.academiaromana-is.ro/srol/en/unitati_segmentale.htm)
  describes the Romanian articulations. Fonia's existing sound targets and release approval flags are preserved.
  The geometry and renderer tests are engineering checks. The profile positions still require approval by a speech therapist.

Bake the checked source geometry with Blender:

```text
blender -b --factory-startup --python-exit-code 1 -P tools/mouth3d/prepare_anatomy.py -- base.obj teeth_base.obj
```

## Previews

`python -I tools/mouth3d/preview.py style OUT [--only s,sh] [--clip rotate|front] [--mesh]` (needs `JAVA_HOME`) runs the JVM
software copy of the shader and its passes (`src/test/.../StylePreview.kt` — keep it identical to `render/StyleGl.kt`)
and writes `sheet.png` (front / 45° / profile / cut-away per sound), one PNG per view, closer looks and, with `--clip`,
an mp4. `--mesh` also writes the runtime geometry, including closure poses, under `OUT/mesh`.
Build the editable Blender scene with `blender -b --factory-startup --python-exit-code 1 -P tools/mouth3d/blender_study.py -- OUT/mesh OUT`.
Each part has shape keys for all demonstrated phonemes and the affricate closures.

Demo: `:mouth3d-demo`, `adb shell am start -n tech.fonia.mouth3d.demo/.DemoActivity --es sound sh --ez inside true`
(extras also `progress`, `play`, `spin`, `fallback`, `autofallback`, `ui`, `stage` face|blue, `highlight`
lips|teeth|tongue|air, `box` = the guide's 250 dp card).

## Licence

Fonia's model combines original geometry and the CC0 MakeHuman derivatives documented above.

## Pitfalls

- Every capped mesh must stay watertight: a hole or a fold flips the stencil parity and leaves a hole in the cap. Rows
  behind the lips are forced to lie further back than the row before (`zPrev`), poles are single points.
- Cap order matters: tongue, teeth, then the head (same plane, `GL_LEQUAL`, identical quad).
- The wet roll retreats inside each commissure. The vestibule and gum rows stay behind the wet line and skin rim
  so the lining remains inside the cheek under puckering and jaw movement.
- Kinds (`style/Kind`) are per triangle; the shader takes them `flat`. No face culling: lighting is two-sided.
- The emulator (SwiftShader) has no MSAA config: `StyleGl` uses the multisampled FBO (depth24-stencil8).
