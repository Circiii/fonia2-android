# :core:designsystem (D1 + C3 + C4): tokens, FoniaTheme, the living letter

Android library, package `tech.fonia.designsystem`, depends on `:core:glyphs` and `:core:model`.

## Tokens (D1): Kotlin is the single source

`tokens/FoniaTokens.kt` holds the 14 colour roles (`FoniaPalette`), the sticker sets (`FoniaSticker.Voice / Parrot /
Sun / Sky / FlatOnPaper / FlatOnStage / Teeth`), the Allo type scale (`FoniaTypeScale`), spacing, corners, depth,
touch targets, springs and durations (`FoniaMotion`) and the inflation story numbers (`FoniaLetterTokens`).
`spec/tokens/fonia.tokens.json` (W3C `$value`/`$type`, for iOS and review) is **generated** from it:

```bash
./gradlew :core:designsystem:testDebugUnitTest -Pfonia.writeTokens=true   # rewrite the JSON
./gradlew :core:designsystem:testDebugUnitTest                            # fails if the JSON is stale
```

`TokensTest` also recomputes the WCAG table of plan §2.2 and fails on any raw colour (`Color(0x…)`, `#RRGGBB`,
`Color.Red`…, `parseColor`) in `design/` or `app/` Kotlin outside `FoniaTokens.kt`. `FoniaTheme { }` provides colours,
typography (Allo variable font in `res/font/allo_variable.ttf`, OFL in `assets/licenses/Allo-OFL.txt`), shell, surface
(`FoniaStage { }` for the blue scene) and `LocalReduceMotion` (system animator scale 0, or the Fonia setting).

## The living letter (C3)

```kotlin
val state = remember { LiveLetterState() }
val drive = remember { LetterDrive() }
LiveLetter("Ș", state, drive = drive, height = 200.dp)                  // hero sticker
LiveWord("șarpe", target = listOf(0..0), state = wordState, drive = drive) // practice word
StickerText("Bravo!", FoniaSticker.Parrot)                              // static sticker text / ★ ✓ →
state.cue(LetterCue.Correct)                                            // events
state.cue(LetterCue.Instruct(ErrorKind.PlaceSwap, heard = "S"))         // → comma-drop on Ș
```

Feed the drive from the audio engine once per snapshot (any thread, no allocation, no recomposition):

```kotlin
drive.listening = micOpen
drive.feed(level, speaking, sNess, fricative, voicing = pitchClarity, trill, blow, holdSeconds)
drive.inflation = x            // or: drive.inflateWithVoice = true (grows while speaking, POPs at 1)
drive.trembling = liveUnsure   // 0–1, the outline boils while the phone is unsure
```

`sNess` goes into `sharpness` (0 = Ș round, 1 = S sharp; NaN = ROND follows the inflation); on a comma letter the
comma also grows out of the letter as the sound becomes Ș.

### Animation names (`LetterCue`, `name` in parentheses)

Base (§5.1): `Breathe` (auto after 1 s calm), `Listen`, `Rest`, `Pop`, `Correct`, `Almost`, `Unsure`, `Retake`,
`Beat(strength)`, `DropIn`, `Thicken`, `Stretch`; word only: `Syllable(range, stressed)`, `SlotDrop(index)`.
Instructions (§5.2): `CommaDrop`, `CommaLift`, `SwapGlide(heard)` (+ ripple for R, press for L), `TrillRipple(uvular)`,
`VoicingWave`, `AirToCentre`, `TongueBehindTeeth`, `ClosureSnap`, `SagAndRefill`; `Instruct(kind, heard)` resolves
an `ErrorKind` to one of them (`LetterCue.resolve`). Verdicts and instructions hold their last frame until `Rest` or
the next verdict; `state.busy` says whether something is still moving.

## How it works

- `LetterEngine` / `LiveWordEngine` (pure Kotlin, JVM-tested): springs on the drive, cues as closed-form functions of
  time (`CueMotion`), Allo interpolation (`VariationInterpolator`), and emission through an `Affine` that carries the
  whole pose into `ComposePathSink` (the `PathSink` → Compose `Path` adapter, paths reused).
- `StickerPainter`: shadow (0.03, 0.09 em, black 20 %), rim, edge, face, Almost's liquid, a gloss crescent, props,
  particles (pool of 150), badge (✓ ? ↻ from Allo). Body and comma are drawn layer by layer so they never cut each
  other; fill is non-zero (variable-font overlaps and counters are right).
- The frame loop advances the engine and bumps a frame counter read **only** in `drawBehind`: animating causes zero
  recompositions (the lab shows the count), and the loop sleeps (10 Hz drive polling) when nothing moves.
- Reduced motion: holding cues jump to their last frame, Pop/Correct leave a static ✓, Retake only ↻, no particles,
  no tremble, drive sampled in 100 ms steps.

## Pitfalls

- One `LiveLetterState` per letter/word; cues sent before the first frame are queued.
- Hero layout: 1 em = 0.54 × box height, cap centre at 0.47; POP (1.70) overflows the box. Inside a
  `FoniaOverlayHost` the POP/Correct particles draw in the overlay, so a clipped scene box no longer cuts them.
- `LetterDrive` is annotated `@Stable` but is never observed: change the letter through it, never through state.
- Sets switch instantly (an RGB blend between complementary sets shows brown frames).
- `LiveWord` slots are reserved at wght 900 + rim so nothing moves while the target inflates: a visible gap around
  the target at rest is the price.

## Foni, art and motion (v2-foni)

```kotlin
FoniMascot(FoniMood.Talking, size = 120.dp, voiceLevel = { level }, lookAt = letterCentreInRoot) // the real 13-layer parrot
// FoniMood: Idle Listening Talking Thinking Cheering Encouraging Unsure Pointing Sleepy Waving (.description = TalkBack)
WordPicture("măr")                      // word / emoji / code → images/pictures (words.json); letter tile if none
SceneBackdrop("lesson", parallax = { Offset(pagerOffset, 0f) }) { /* content */ }  // page/tall/card/wide picked by shape
FoniaArtAssets.preload(context, listOf("scenes/scene-cold-page.jpg"))               // off-thread LRU loader
FoniaOverlayHost { screen() }           // unclipped top layer: LiveLetter's burst and CelebrationBurst draw here
CelebrationBurst(trigger = wins, Modifier.matchParentSize(), style = BurstStyle.Confetti)  // or overlay.burst(pointInRoot)
FoniaAnimatedScreen(key, nav = { a, b -> FoniaNav.Forward }) { … }  // FoniaTransitions.forward/back/modalOpen/modalClose/through
Modifier.pressBounce(interactionSource); Modifier.staggeredEntrance(index); rememberFoniaHaptics().success()
```

- Foni: moods are pure functions of time blended by a spring (`FoniRig`, JVM-tested); `voiceLevel` is read in the draw
  phase (zero recompositions); `lookAt` is in root coordinates, he turns to face it (Pointing: he presents it with the
  wing behind him). Reduced motion = one still pose per mood. Layers are region-decoded and downsampled (sample 2 ≤ 380 px).
- Assets: `assets/fonia/{foni,pictures,scenes}` from fonia-content 66c53b7 (7.5 MB; licences in `assets/fonia/licenses`).
  `python -I design/tools/build_word_index.py ../fonia-content` rebuilds `pictures/words.json` from spec/content/v2.
- `ContinueButton` / `PathNode` now press-bounce and tap haptics; `TongueBehindTeeth` is a side-view mouth badge kept
  inside the letter's box. Gallery: `:design-demo` (`--es page foni|pictures|scenes|motion`, `--ez demo true`, `--es cue tongue`).
