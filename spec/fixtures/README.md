# Fixtures of the app's fixture mode (debug builds only)

Nothing here ships in a release build: `app/build.gradle.kts` adds these folders to the **debug** assets only.

## Audio (`audio/`, built by `make_fixtures.py`)

| File | What | Source / licence |
|---|---|---|
| `s_hold.wav`, `sh_hold.wav` | 3 s synthetic S / Ș hiss with room tone (lesson `Hold`) | generated (fixed seeds) |
| `sarah_s.wav`, `sarah_sh.wav` | the first word („Sare”, „Șarpe”) of a synthetic voice's word list (lesson `Words`) | ElevenLabs test voice "Sarah", generated for Fonia on 21.11.2026 |
| `fillers.wav` | 15.5 s of the same synthetic voice speaking freely with fillers („Bună ziua, ăă, astăzi vreau să vă povestesc… deci, practic… mmm… cum să zic…”), room tone around it; the speaking screens' fixture (`--es fixture fillers`) | same voice, `sarah_fillers.wav` whole (≤ 20 s cap in the script) |

The synthetic voices only prove the chain runs (mic/fixture → detectors → verdict / transcript); they say nothing about
accuracy on children. `python spec/fixtures/make_fixtures.py <dir with sarah_*.wav>` rebuilds them byte for byte.

## Face (`--es face fixture`, MIRROR)

No file in this folder: the debug build copies `facecamera/src/androidTest/assets/faces/small_smile.png` (already in
the repo for the camera's instrumented tests) into its assets as `faces/small_smile.png` (`faceFixtureAssets` task).

- **Image:** Kayla Barron, official portrait (2021), NASA — **public domain** (a work of the US federal government).
  Source: https://commons.wikimedia.org/wiki/File:Kayla_Barron,_official_portrait_(2021).jpg (facecamera/MODELS.md).
- **Derived frames** (made at run time by `app/src/debug/…/FixtureFace.kt`, never saved): a crop around the face,
  the same crop with the lips drawn together (a local horizontal squeeze of the mouth region, for the „round lips”
  states), a smaller copy on a flat backdrop (too far) and a darkened copy (too dark).
- The frames go through the real Face Landmarker pipeline (`StillFrameFeed`, `:platform:camera` debug source set).

The CC/Pexels clips in `HSIC/tmp/face_videos/` were **not** used: their `SOURCES.md` says they are local test data
that must not be committed or shipped, so the public-domain still already in the repository was used instead.
