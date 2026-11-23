# :core:lessons (F2 + the v2 content)

Pure Kotlin/JVM (`tech.fonia.lessons`). It reads the Fonia 2 content in `spec/content/v2` and turns a request into
a deterministic 4-minute lesson plan (chapter 06 §2). No Android, no clock, no I/O beyond the loader.

## The content (`spec/content/v2`, schema 2: the contract with iOS)

| File | What |
|---|---|
| `sounds.json` | the nine `SoundId`s, their files, the warm-up library ids, `contentVersion` |
| `sounds/<slug>.json` | one per sound (`s sh z j ts ce ge r l`): hero, the 14 chapter lines (`open.<slug>.<rung>`), parent "how", warm-up refs, `errors` (keys into `spec/feedback.json`), `plan` counts, `items` |
| `twisters.json` | I4: original and folk (`folkRef`) twisters, `tempos: [slow, step]` |
| `foni_lines.json` | I5: Foni's lines by category; `fb.*` lines are copied from `spec/feedback.json` |
| `parent.json` | I8: six `ParentCard`s, ten tips, summary and reminder templates, privacy text, rung names |
| `lesson.json` | F2 template timings, block sizes per rung, takes, the 80-85 % adaptation (N11), review intervals |
| `content.lock.json` | sha256 of every file |

Item types (`type`): `listen` (word + Foni's mixed-up `foil`, `mimics` an `ErrorKind`), `hold` (isolated sound,
`mode` sustain/burst, a `prompt`), `syllable` (`shape` CV/VC/VCV), `word` (`pos`, `emoji`/`icon`, `otherSounds`,
`difficulty`, `ageBand`, `needsTherapistCheck`), `pair` (`a`, `b`, `contrast`), `sentence`, `story` (3 `pictures`,
3 `lines`), `mission`. Ids never change; everything is `review.status = "draft"` until a speech therapist approves.

## API

```kotlin
val content = LessonContent.load { path -> assets.open("content/v2/$path").bufferedReader().readText() }
// or LessonContent.fromDirectory(File(...)) / LessonContent.fromStrings(mapOf(path to json))
val plan: LessonPlan = LessonBuilder(content).build(
    LessonRequest(sound = SoundId.S, rung = Rung.WordsInitial, seed = profileSeed,
        sessionsCompleted = n, history = history, childAge = 5, workedSounds = setOf(SoundId.S),
        approvedItems = approvedByTherapist, daysSinceLast = days, oneMore = false))
// play plan.steps in order: kind, lineKeys (Foni), items (each: id, text, takes, model, variant, voices)
val next: LessonHistory = history.record(plan, results = listOf(ItemResult(id, success)), sessionIndex = n)
val voices = VoiceIndex.parse(assets.open("content/voice/index.json").bufferedReader().readText())
voices.file("w.sanie")  // "clips/w.sanie.m4a", or null for "human-needed" / missing clips
```

- **Asking for a plan.** The app keeps one `LessonHistory` per sound (item stats, last session's ids, recent
  attempts, block results) and passes it in. `Adaptation.assess` turns it into easier / steady / harder (or force
  it with `LessonRequest.adaptation`); the level moves the hold goal (1.0 / 1.5 / 3.0 s), the train length
  (2 / 3 / 5), the share of new words (15 / 30 / 40 %, 50 % on a rung's first session), the model before each item
  and the twister tempo. It never changes the rung: that is `Progression`'s job.
- **Plan shape.** `Opening` → `Warmup` → block 1 (rung below) → `Move` → block 2 (current rung) → block 3 (current
  rung mixed with passed positions, one `surprise` item at slot 5-7 with `countsForProgress = false`; the app may
  stop it at `cutAt` when attention drops) → `Final` (one sure item, "Ascultă-te", the adult's turn, "Încă una?").
  `oneMore = true` gives one block of 6 + final (~90 s); `daysSinceLast >= 7` gives two blocks of known items.
- **Reporting back.** After the session call `history.record(plan, results, sessionIndex)` and store the result.
  A miss resets the item's spaced-repetition box, so it comes back next session (then after 2, 4, 8, 16).
- **Voice keys.** Every `voices` entry, `lineKeys` entry and content `voice*` field is a key of
  `spec/content/voice/index.json` (`clips/<key>.m4a`, AAC mono, -19 LUFS). `human-needed` marks isolated sounds,
  syllables and Foni's mixed-up words (Q9): they need a human recording and resolve to `null` until it exists. Praise
  lines (`praise.<fact>.<n>`) are not in the plan: the app picks one by what happened (chapter 06 §7.1).

## Determinism and iOS parity

All randomness is SplitMix64 (`SeededRandom`, the same as `:core:domain`), seed
`request.seed ^ sessionsCompleted·0x9E3779B97F4A7C15 ^ fnv1a64("<sound>/<rung>")`. `spec/content/vectors/plans-v2.json`
holds 450 requests (9 sounds × 14 rungs × 3 seeds, plus "încă una", the long pause and the three adaptation states)
with their plans; `VectorsTest` replays them and the Swift test should read the same file. After an intended change:
`./gradlew :core:lessons:test -Pfonia.writeVectors=true`.

## Adding or changing content

1. Edit the authoring source `tools/content/content_src.py` (words as `"word emoji [V]"`, listen, pairs, sentences,
   stories, missions, twisters) or `tools/content/lines_src.py` (Foni's lines, parent texts). Original text only;
   nothing from Carmen Ivanov's book.
2. `uv run tools/content/build_content_v2.py` (derives ids, difficulty, otherSounds, ageBand, voice keys).
3. `uv run tools/content/check_content_v2.py` must end with 0 errors (positions by pronunciation, confusion
   partners in early rungs, pairs, lengths, banned words, cedilla, counts, feedback consistency, voice index).
4. Voice for new texts: `voice_plan.py --out plan.json`, synthesise each batch with the Foni voice (Matilda,
   `eleven_multilingual_v2`, `ro`), `voice_split.py`, `voice_check.py` (see spec/content/voice/LICENSE.md).
5. Bump `CONTENT_VERSION`, rebuild, rewrite the vectors, run `./gradlew :core:lessons:test`.
