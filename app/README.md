# `:app` — the Fonia 2 shell (G1 + G3 + G4/G5 core + A3 fixture mode + M1: real judge, adult area, onboarding + M2: MIRROR, public speaking + M3: sound house, exercises, hold games, content v2, ZIPA, 3D mouth)

Kotlin + Compose, `tech.fonia.app`, no DI framework, no `INTERNET` (the manifest also strips media3's and
`:platform:whisper`'s `ACCESS_NETWORK_STATE` / `WAKE_LOCK` / `INTERNET`; merged debug APK: `RECORD_AUDIO`, `CAMERA` only,
check with `aapt2 dump permissions`). 64-bit ABIs only (`arm64-v8a`, `x86_64`: whisper.cpp and LiteRT). First start: the short onboarding, then PATH (or TEEN-HOME for "Doar eu" +
"Vorbit în public"). The letter lab is a debug entry (long-press the logo, or `--ez lab true`).

## Structure

| Where | What |
|---|---|
| `AppGraph.kt` | the whole object graph, by hand: audio director, judge, store, permissions, tunings. One per process (`FoniaApp`) |
| `MainActivity.kt` | edge-to-edge, `FoniaTheme`, lifecycle → `onForeground()` / `onBackground()` (suspend audio) / `release()` |
| `LaunchFlags.kt`, `TakeSources.kt` | debug launch extras; the seam for where a take's audio comes from |
| `src/debug/…/VariantHooks.kt` | `FixtureDeck`: WAVs from `spec/fixtures/audio` (debug assets only) instead of the mic; `faceFixture()` |
| `src/debug/…/FixtureFace.kt` | `--es face fixture`: still frames (a public-domain portrait + derived frames) through the REAL Face Landmarker pipeline (`StillFrameFeed`, `:platform:camera` debug source set) into `MouthTracker` |
| `src/release/…/VariantHooks.kt` | no flags, no fixtures, no lab |
| `shell/` | `Navigator` (back stack of `:core:navigation` keys, JVM-tested), `FoniaShell` (AnimatedContent host, demo script), `PermissionGateway` |
| `path/` | PATH: the road of the nine sounds, Foni placeholder, day count |
| `lesson/` | `LessonFlow` (steps/items, pure), `TakeEndDetector` (pure), `LessonController` (the attempt cycle), `LessonScreen` |
| `judge/` | `TakeJudging` (the seam: `TakeRequest` → `Judged`), `RealJudge` (`tech.fonia.judge.TakeJudge` over `assets/spec`), `JudgePolicy` (unlock policy + trace, pure), `ProvisionalJudge` (test double only), `TakeAnalysis` |
| `data/` | `AttemptLog` (profile, sessions, attempts + judge trace in `detail`, recordings, wipe), `AttemptView` (row projection), `CalibrationBook` / `CalibrationStore` (E4 bookkeeping), `AdultReport` („Cum merge”), `AppSettings` (onboarding, talker, reduce motion; SharedPreferences), `Wiper` („Șterge tot”), `Wav` |
| `adult/` | `AdultGate` (pure) + `AdultGateScreen`, ADULT-HOME, ADULT-CALIBRATE, ADULT-SETTINGS + licences, `AdultUi` (grown-shell page/card/row) |
| `onboarding/` | the short onboarding: ON-0 (who), ON-1 (adult card) or the "Doar eu" goal + voice, ON-2 (mic pre-prompt), ON-5 (pick a letter) |
| `teen/` | TEEN-HOME (grown shell): today's card → NO-UM, NO-UM, MINUTE (step on the 7-topic ladder), a quiet link to the sounds PATH, settings |
| `mirror/` | `MirrorScreen` (MIRROR: camera pre-prompt, rest, `MirrorView`/`LipTargetOverlay`, advice, glow + cheer, sound picker, „Am făcut”), `MirrorCoach` (pure: readings → one ≤ 8-word line + the ring; JVM-tested), `FaceFixture` (the debug face seam) |
| `speaking/` | `DirectorSpeakingAudio` (`SpeakingAudio` on the app's `AudioDirector`; JVM-tested with `FakeAudioDirector`), `SpeakingHost` (NO-UM / MINUTE in `FoniaTheme(Grown)`, mic pre-prompt, Whisper or acoustic-only), `SpeakingTakes` („Păstrează” → WAVs in no-backup storage) |
| `end/` | END |
| `text/T.kt` | every string (Romanian, ≤ 8 words for the child) + `Plural` |
| `ui/Common.kt` | Foni placeholder, speech bubble, sheet, dots, round buttons |

New design-system components (additive, `:core:designsystem/components`): `ContinueButton`, `QuietButton`,
`MicCapsule`, `PathNode`, `VerdictStamp`.

## The live band

`LessonScreen` runs one `withFrameNanos` loop → `LessonController.onFrame()`: the only `board.latest()` reader. It
feeds `LetterDrive.feed(...)` and two draw-only floats (hold ring, mic level); nothing read in composition changes per
frame. Hold: `inflation = 0.22 + 0.78 · hold/goal`, `Pop` cued once at the goal (silent: mic open). End of take:
voice end + 150 ms, step max, stalled source, or 8 s silence (`TakeEndDetector`).

## The real judge in the lesson

`AppGraph.judge = RealJudge(scope) { RealJudge.fromAssets(app.assets) }`: the spec (`spec/thresholds`, `spec/fusion`,
`spec/unlock.json`, `spec/feedback.json`, copied into `assets/spec` by the `specAssets` task of `build.gradle.kts`) is
parsed once on IO when the graph is built. After `closeMic()` the lesson calls `judge(TakeRequest, clip)`: `measure` +
`decide` (fast head; no phoneme model yet) on `Dispatchers.Default`, with a `RuntimeContext` from
`CalibrationStore.context` (talker group from settings; `adultPresent` = an adult thumb was given in this lesson or the
talker is an adult; calibration tier; `childOkOffset` / `childErrOffset`, capped so `tauErr + offset ≤ 0.97`; anchors;
the child's F0).

Policy (`JudgePolicy`, JVM-tested): a `NamedError` reaches the child only when the judge returns it; when its unlock
entry is `unlocked` with `evidenceLevel: "imitations"` it waits for the adult's thumb first (`ChildFacing.AdultFirst`,
Q3). A kind "în probă" (`adultNote.inTrial`) shows only in the lesson's adult strip (small caption „În probă: …”, never
lit) and in ADULT-HOME; a locked withheld kind is seen by nobody (trace only). `Unsure` lights the thumb row; `Retake`
never counts (child line and adult cause from `spec/feedback.json`). Today nothing is unlocked, so no error is named.

**Saved per attempt** (existing `attempt` table, no schema change): `machineOutcome`, `adultOutcome` (the thumb;
confirmed / overridden is derived from the two) and, in `detail` (the existing JSON payload column): `judgeTrace`
(verdict, error kind, heardAs, confidence, head, probabilities, topError, withheldKind, adultNote, features, capture,
segments, blockedKinds, notes, spec versions, micros, runtime), `adultView`, `anchor` (S/Ș measurements) and `f0Hz`. The
take's audio is a `recording` row (WAV bytes in the store's `.m4a`-named file; the decoder sniffs RIFF) linked by
`recordingId`; retention keeps the newest 3 per sound. Retakes keep no audio. No JSONL log was needed.

**Calibration** (`CalibrationBook`, rebuilt after every thumb): 👍 on a judged take = confirmed good; at 5 on a sound it
is „calibrat pe copil” (tier 1). Confirmed S/Ș takes become `TalkerCalibration` anchors; the child F0 is the median of
confirmed takes (≥ 3, child talker only); 3 "încă nu" on phone-Correct in the last 20 → `childOkOffset` 0.05; 👍 on
Almost / NamedError with a kind → `childErrOffset[kind]` += 0.05.

## MIRROR (M2)

- **From PATH** („Oglinda”): `openMirror(nav, sound)` pushes `MirrorKey(sound)` (the current sound). **From a lesson**
  (lips button) the mirror opens *inside* `LessonScreen` as an overlay, so the `LessonController` and its item survive:
  the audio is suspended while it is up (`suspendAll(Other)`: a running take is dropped and never counts), on „Am
  făcut” the audio resumes and a paused lesson continues by itself. In the lesson the mouth goes to the live band
  (`mouth.onMouth = graph::postMouth` → `LivePipeline.postMouth`).
- **Camera permission:** the adult's pre-prompt (docs/v2/04 §11 text), then the system dialog launched by the screen;
  „Fără cameră” / a refusal → the sound's big letter, the shape in words, „Vreau să mă văd” (asks again only on that
  gesture; „Deschide setările” once refused for good). „Camera e pornită ✕” turns it off.
- **Coach** (`MirrorCoach`): quality hints first (after 600 ms; „Vino mai aproape.”, „Mai multă lumină.”), the rest
  („Fă o față liniștită, cu gura închisă.”, the ring fills), the camera's advice (`CameraAdvice.key` → `T.mirrorAdvice`,
  after 500 ms), „Așa! Ține-o așa.” on a match (green glow, the overlay's tick), and after ~1 s held (150 ms grace)
  one cheer: the small living letter's `Correct` cue + `Earcon.Star` through FxGate, „Bravo! Exact așa.”. The tongue
  note („Limba lucrează ascunsă. Uită-te la buze.”) shows for every pose with `visible.tongue == false` (all nine).
- The tongue detector is off here (`engine.tongueEnabled = false`). `FLAG_SECURE` in release, off in debug (Q12).
- `MirrorColors` from the tokens: target `accent`, matched `parrot`, lips `surface`, neutral `inkSecondary`.

## Public speaking (M2)

- `openSpeaking(nav)` (ADULT-SETTINGS „Vorbit în public”) → TEEN-HOME; TEEN-HOME is also the root for "Doar eu" +
  "Vorbit în public". Its rows push `NoUmKey(topic)` / `MinuteKey` (`:core:navigation`, additive).
- `SpeakingHost`: the microphone is asked before the screen starts (refusal → back), `FoniaTheme(shell = Grown)`,
  `graph.speakingAudio` (`DirectorSpeakingAudio`), `graph.transcriber()`.
- **`DirectorSpeakingAudio`**: `record` = `openMic(LiveTarget.AnySound)` (quiet rule, no noise reduction, mono at the
  capture rate) + a 20 ms poll of the board → `onLevel` (50/s; a stalled source reads as silence after 250 ms so the
  3 s rule ends a fixture take); `play` = `AudioDirector.play(PromptRef.Take(slice))`, refused while recording;
  `playbackSeconds` = time since the director entered `Speaking` − 0.12 s (PlaybackEngine has no position API; an
  estimate), NaN when idle.
- **„Păstrează”** (NO-UM) is the only write: `SpeakingTakes` → `no_backup/speaking/<noum_stamp>{.wav,_clean.wav,.json}`
  (thresholds version `speaking-1`); wiped by „Șterge tot”. MINUTE stores only its ladder step (`AppSettings.minuteRung`).
- **Whisper**: `SpeechModelStore(WHISPER_BASE, FoniaWhisper.defaultRoot(app), loader = WhisperCppLoader())` — no
  fetcher, the app never downloads. When the four files are in `no_backup/FoniaSpeech/openai_whisper-base/` the screens
  transcribe (verbatim, on the phone); otherwise `null` → acoustic-only (hesitations by sound; the transcript shows
  dots). „Șterge tot” keeps `FoniaSpeech` (no personal data). Not in the APK (148 MB). Copy it onto a debug install
  (streamed, no temporary copy; needs ~150 MB free on `/data`):

```bash
M=~/.gradle/caches/fonia-whisper/openai_whisper-base     # filled by :platform:whisper:whisperModelToDevice
D=no_backup/FoniaSpeech/openai_whisper-base
adb shell run-as tech.fonia.app mkdir -p $D
for f in generation_config.json tokenizer.json tokenizer_config.json ggml-base.bin; do
  adb exec-in run-as tech.fonia.app sh -c "cat > $D/$f" < "$M/$f"
done
adb shell run-as tech.fonia.app ls -l $D     # 2 762 / 2 480 466 / 282 683 / 147 951 465 bytes
```

  The last write finishes a few seconds after `adb` returns: list again until `ggml-base.bin` shows 147 951 465 bytes.

  (Git Bash: `export MSYS_NO_PATHCONV=1` first. A full emulator refuses `adb install` below ~400 MB free: remove
  `ggml-base.bin` before reinstalling.) `--ez whisper false` forces acoustic-only.


## M3: the sound house, the exercises, content v2, ZIPA, the 3D mouth

**Flow.** PATH node → „Casa sunetului” (`house/SoundHouseScreen`, `SoundHouseKey`): the sound's character (its hold
game's sticker) on a hill in the sound's colour (`soundColor`), the big living letter, „Hai să exersăm” (the guided
4-minute session) and 11 exercise cards (`ExerciseCatalog`, pure): Ascultă, Ține sunetul, Silabe, Cuvinte, Perechi,
Propoziții, Frământări, Poveste, Oglinda, Vezi gura, Misiunea de azi. „Continuă” on PATH = the last sound's guided
session. END: „Gata” (PATH), „Încă una?” (the same card again, or the ~90 s „Încă una” plan), „Alege alt exercițiu”
(`Navigator.toHouse`); back from END lands in the house when there is one.

**Locks (`ExerciseLocks`, JVM-tested).** Listen, Hold, Mirror, 3D mouth: always open. Every rung after `isolated` waits for
the adult (`SoundPractice.adultConfirmed`, the lock of chapter 06 §1) and for the ladder to reach it; reached = replayable.
A closed card is dimmed with a drawn padlock; a tap explains („Cheamă un adult” → AdultGate → `AdultSoundKey`).
ADULT-HOME lists the nine sounds → the adult's page: „Da, sună bine / Încă nu / Întreb logopedul”, „Deblochează tot pentru
acest sunet”, today's mission with „Am făcut-o”.

**Ladder (`data/PracticeBook.kt`: `Ladder`).** New sounds start at `isolated`. `isolated` never moves by itself: it
collects good takes (12) and waits for the adult; confirming moves to `syllables`. A guided session at another rung moves
one rung up at ≥ 5/6 good in its counted blocks (≥ 3 items). Never down.

**Practice book.** `PracticeState` (rung, lock, unlock-all, sessions, the `LessonHistory`, plays per card, days, today's
mission) in ONE JSON file `no_backup/practice/practice.json` (`PracticeStore`: file / memory; kotlinx tree API, no plugin).
`:core:data` has no table for the history and its schema is unchanged. Wiped by „Șterge tot”. Memory-only in the demo.

**Content (`content/ContentRepo`).** `contentAssets` copies `spec/content/v2/**.json` and `spec/content/voice/{index.json,
clips/*.m4a}` to `assets/content/…` (`noticeAssets`: the model NOTICEs to `assets/notices/…`); loaded once on IO.
`lesson/PlanRules.kt`: `PlanMaker.guided` = `LessonBuilder.build` at the sound's rung (seed `0x5EEDF0A1`, the history,
days since last); `PlanMaker.exercise` = one block of the card's rung (`oneMore` shape, no final; twister = slow then step).
Every step type is drawn (`LessonStages.kt`): Opening (character + letter), Warmup („Am făcut”), Listen (two cards, tap;
a wrong tap replays the right word), Hold (the game), Syllables (wagons hop per burst), Words (picture + `LiveWord`),
Pairs (both said, then the one to say gets the pink edge), Sentence/Twister (targets marked, tempo badge), Retell (the
letter walks the three pictures while Foni tells), Mission (the card flies to the adult), Move, Final. Foni's lines and
models play through `AudioDirector.play(PromptRef.Asset)`; a missing / human-needed clip = text only (quiet rule).
Twister and story are adult-judged (mic for the letter, thumb decides). Saved per attempt: `contentVersion`, `models`,
`itemId`, `itemType`, `zipa` (which head).

**Hold games (`holdgames/`).** `HoldGame` (sustain: seconds of good sound; burst: `Onsets`), one compact file per sound:
Snake (S, stretches along the path), Train (Ș, steam, rolls to the station), Bee (Z, flies only while voiced), Wind (J,
spins the pinwheel), Mouse (Ț, one hop per burst), Can (CE, one splash/flower per „ci”), Motor (GE, puffs + dial, drives
off), Tiger (R, roars with the trill, taps fill paw prints), Lion (L, one note per „la”, or per 0.5 s of „lll”). The
controller copies the board into one `HoldInput` per frame; `HoldStage` draws the scene in the draw phase and places the
`LiveLetter` in the layer phase from `tick` (no recomposition per frame). Tokens only, no red.

**ZIPA.** `AppGraph.zipa = ZipaHost.create(app)` + `ModelMemoryPolicy`; warm-up when LESSON opens, `trim()` when it
closes; `RealJudge(phones = PhoneScoring { zipa.score(clip, req) })`; `HeadChoice` (pure, JVM-tested with fakes): gate or
`fastFinal` → fast; else evidence in ≤ 600 ms → full head; null → fast. `noCompress += "onnx"`, 32-bit ORT excluded,
`check<Variant>OnnxRuntime` finalizes assemble (one `libonnxruntime.so` per ABI, no telemetry provider, no INTERNET) and
writes `build/reports/onnxruntime-<variant>.txt`. NOTICE on the licences screen. `-Pfonia.zipa=false` builds without
the model (asset ignored; the host fails to load → fast head); `-Pfonia.abi=x86_64` one ABI.
Full emulator check without the 194 MB APK (the emulator refuses it: < 500 MB would remain):
`./gradlew :app:assembleDebug -Pfonia.abi=x86_64 -Pfonia.zipa=false`, install, then copy the model where
`AssetModelFile` falls back to: `adb exec-in run-as tech.fonia.app sh -c "cat > no_backup/models/zipa/dfe87c976626/model.int8.onnx" < ~/.gradle/fonia-models/zipa-small-crctc-500k/model.int8.onnx`
(mkdir first; remove it before the next `adb install`). logcat `FoniaLesson … head full` = the full head decided.

**3D mouth.** „Vezi gura” (`mouth/Mouth3DView.kt`, `Mouth3DKey`): rest ↔ pose loop, „Încă o dată”, the cut-away only when
`insideAvailable` (none approved yet → a note), caption from the pose spec, colours from tokens (`mouthColors`). MIRROR:
„Vezi gura” toggles a front-view card over the camera picture.

**Art seam.** `ui/ArtSeam.kt` is the only place that draws Foni (`Foni(mood…)`), a word picture (`WordPictureSlot`) or a
backdrop (`SceneSlot`); swap the design package's `FoniMascot` / `WordPicture` / `SceneBackdrop` in there.

**Launch flags (M3).** `--es screen house --es sound s`; `--es screen exercise --es exercise
hold|listen|syllables|words|pairs|sentence|twister|retell|mission|mirror|mouth3d --es sound <id>`; `--es screen adultsound
--es sound s [--ez gate true]`; `--es fixture auto` = per-sound fixtures (`<sound>_hold.wav`, `spec/fixtures/
make_hold_fixtures.py`; `sarah_s/sh` for words). `--ez demo true` now: onboarding → PATH → house S → Sâsâilă's game →
END → „Alege alt exercițiu” → PATH → house Ș → Șușu's train → END → MIRROR → adult → teen.

## Plug-in points

- **Adult area:** `openAdult(graph, nav, key)` → `AdultGateKey(key)` unless the gate is open (or "Doar eu"); passing the
  gate replaces it with the key. The gate closes after 180 s in the background, at restart and at „Șterge tot”.

## Launch flags (debug builds only)

```bash
adb shell am start -S -n tech.fonia.app/.MainActivity --es fixture s --es screen lesson --es sound s
adb shell am start -S -n tech.fonia.app/.MainActivity --es fixture s --es screen lesson --es sound sh   # PlaceSwap
adb shell am start -S -n tech.fonia.app/.MainActivity --ez demo true      # hands-free film (~60 s)
adb shell am start -S -n tech.fonia.app/.MainActivity --es screen adult [--ez gate true]   # „Cum merge” (gate in front)
adb shell am start -S -n tech.fonia.app/.MainActivity --es screen calibrate     # or settings, teen, onboarding
adb shell am start -S -n tech.fonia.app/.MainActivity --es fixture s --es screen lesson --es sound sh --es step words --es talker adultHigh
adb shell am start -S -n tech.fonia.app/.MainActivity --es screen mirror --es sound sh --es face fixture   # MIRROR, still-image face
adb shell am start -S -n tech.fonia.app/.MainActivity --es screen mirror --es sound sh                    # MIRROR, the real camera
adb shell am start -S -n tech.fonia.app/.MainActivity --es screen noum --es fixture fillers               # NO-UM plays itself
adb shell am start -S -n tech.fonia.app/.MainActivity --es screen minute --es fixture fillers [--ez whisper false]
```

`fixture`: `s_hold`, `sh_hold`, `sarah_s`, `sarah_sh` (every take), or a family `s` / `sh` (`<f>_hold` for Hold,
`sarah_<f>` for Words). `screen`: `path`, `lesson`, `end`, `lab`, `onboarding`, `adult`, `calibrate`, `settings`, `teen`, `mirror`, `noum`,
`minute`. `face=fixture`: MIRROR's face from `FixtureFace` (a ~21 s loop: calm face → the rest, lips a little rounder →
„Rotunjește buzele.”, lips round → match + cheer for Ș, a far face → „Vino mai aproape.”, a dark one → „Mai multă
lumină.”; each frame's numbers in logcat `FoniaFaceFixture`). `fixture=fillers` (or any family during a speaking take):
`fillers.wav`; NO-UM then starts, analyses, shows the transcript and plays „fără ăăă” by itself. `whisper=false`.
`gate`: AdultGate in front of the adult screens. `sound`: `s ș/sh z j ț/ts ce ge r l`. `step=words`: the lesson starts at
Words. `talker`: `child`, `adultHigh`, `adultLow` (the fixture voice is an adult woman: with `child` the real judge sends
the S word back as `adultVoice`). `demo` (implies `fixture s`, `talker adultHigh`, `face fixture`, settings in memory; ~115 s): onboarding → PATH →
lesson S → END → PATH → MIRROR for Ș (face fixture, 22 s) → PATH → AdultGate (fills itself) → „Cum merge” →
ADULT-SETTINGS ("Doar eu" gets chosen) → TEEN-HOME → NO-UM on `fillers.wav` (transcript, „fără ăăă” played) → TEEN-HOME. `screen=onboarding` also keeps settings in memory. `lab`, `reduceMotion`. Use
`-S`: the graph is per process.
Fixtures: `python spec/fixtures/make_fixtures.py [dir with sarah_*.wav]` (the spoken ones are the FIRST word of the
list takes, so the word gate accepts them).

## Adding a screen

1. Add a key to `navigation/…/FoniaKey.kt` (pure data).
2. Draw it in `FoniaShell.Screen()`; navigate with `nav.push(key)`; give the screen its own `BackHandler` if back must
   be special (as LESSON does).
3. Take dependencies from `AppGraph` as constructor parameters; strings into `text/T.kt`; colours only from `FoniaTheme`.
4. Pure logic gets a JVM test in `src/test` (`./gradlew :app:testDebugUnitTest`).
