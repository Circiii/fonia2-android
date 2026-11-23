# `:domain` parity with iOS `FoniaDomain`

Port of `Packages/FoniaKit/Sources/FoniaDomain` (16 Swift files) to `tech.fonia.domain`, one Kotlin file per Swift file,
plus `SwiftSemantics.kt` (Swift string semantics and raw-value decoding). The 145 Swift Testing functions of
`FoniaDomainTests` are ported one to one, with the same names, in files of the same names. `ParityGuardTests` and
`SessionVectorTests` are extra, Android-only checks.

## What is not identical, and what the port does instead

| What | Why | Instead |
|---|---|---|
| `FoniaContent.load()` / `load(from: Bundle)` | No `Bundle.module` on Android, and the JSON lives in the `content/` submodule | `FoniaContent.load(source: ContentSource)`. `ContentSource` maps a path relative to the content root (`json/sounds.json`, `voice/manifest.json`) to bytes or null. The app passes its assets, tests pass the submodule folder (`ContentSource.directory`). Same order of reads and the same `LoadError.MissingResource(name)` / `MissingSound(id)`. |
| `VoiceIndex(bundle:subdirectory: "VoiceClips")` | Same reason; the content repo calls the folder `voice/` | `VoiceIndex.load(source, subdirectory = "voice")`, still falling back to a top-level `manifest.json`, still throwing `LoadError.ManifestNotFound`. |
| `VoiceIndex.baseURL: URL?`, `clipURL(...) -> URL?` | Android assets have no file URL | `baseUrl: String?` and `clipUrl(...): String?` are paths relative to the `ContentSource` root (`"voice/ro/ro/….mp3"`); the app turns them into an asset or file location. `clipPath` is unchanged. |
| Errors and traps | Kotlin has exceptions, not `throws` plus traps | Decoding errors are `kotlinx.serialization.SerializationException` (Swift: `DecodingError`). Swift traps become exceptions: a missing sound after load (`IllegalStateException`), a repeated sound id (`Dictionary(uniqueKeysWithValues:)`, `IllegalStateException`), drawing from an empty deck (`removeFirst()`, `IndexOutOfBoundsException`), `below(n)` with `n <= 0` (`IllegalArgumentException`). Shipped content reaches none of them, as on iOS. |
| Swift `Int` (64-bit) | Kotlin's `Int` is 32-bit, and counts are `Int` in Android APIs | Counts, indices and `Plural` take `Int`. Every value a Kotlin `Int` can hold gives the iOS result: `sessionSeed` sign-extends like `UInt64(truncatingIfNeeded:)`, `Plural` widens to `Long` before `abs`. Counts past 2³¹ cannot be represented, and Kotlin wraps where Swift would trap on overflow (for example `establishCorrect`); neither is reachable. |
| `UInt64` seeds, generator, FNV-1a | — | `ULong` throughout, so wrapping `+`/`*`, logical `shr` and unsigned `%` are bit for bit Swift's `&+`, `&*`, `>>`, `%`. Pinned by the iOS vector (`SeededRandom(1).next()`) and reference SplitMix64 / FNV-1a values. |
| String equality | Swift compares `String` and `Character` by canonical equivalence, Kotlin by UTF-16 unit | The two agree on NFC text, and all content (JSON and manifest, keys and values) is NFC, which `ParityGuardTests` checks. Where text can come from outside the content, comparisons run on NFC: raw-value lookup and decoding, `warmup(id)`, `coachPlan(key)`, `contrast(id)`, `VoiceIndex` keys, `Viseme.forGrapheme`, `EnglishContrast.mark`, the deck's recent items. Data-class equality of exercises (the deck's "not twice in a row" check) stays code-unit based, which is the same on NFC content. `SeededRandom.hash` hashes the UTF-8 of the text as given, like Swift's `utf8` view. |
| `Character` iteration | Swift strings are collections of extended grapheme clusters | `String.characters()` splits with the regex `\X` (extended grapheme clusters on JDK 17+ and on Android's ICU regex). Used where Swift iterates or counts Characters: `isLong`, `split(separator:)` in `Catalog`, `letter.count`, `title.prefix(2)`, `VoiceIndex.normalised`, and the test helpers. The JVM's and Swift's Unicode tables differ in version, which only matters for new emoji or scripts. |
| `lowercased()` | Java's `toLowerCase` applies the final-sigma rule; Swift maps each scalar alone | `swiftLowercased` lowercases one scalar at a time with `Locale.ROOT` (Ș→ș, Ț→ț, Ă→ă, Â→â, Î→î, İ→i̇, no final sigma). |
| `Character.isWhitespace`, `isLetter` | Java's `isWhitespace` is a different set (leaves out U+00A0 and U+202F) | The Unicode `White_Space` table read from the first scalar, as Swift does; `isLetter` is `Character.isAlphabetic` of the first scalar. |
| Enums with associated values | No such enums in Kotlin | Sealed interfaces of data classes and data objects, which keep Swift's structural equality. Unlabelled values got names: `Exercise.How(target)`, `Repeat/Choose/Retell/Describe/Mission(prompt)`, `HowTarget.Sound(id)`, `HowTarget.English(contrast)`, `SustainTarget.Sound(id)`, `SessionFocus.Sound(id, rung)`, `Meet/SyllablesBack/Story(id)`, `Warmups(ids, breath)`, `English(focus)`, `Diction(area)`, `DictionLesson(lesson)`. |
| Raw-valued enums and `CaseIterable` | Kotlin enum names are identifiers | Entries in PascalCase (`SoundID.Sh`, `Rung.WordsInitial`), each with `rawValue` (the Swift raw value) and `fromRawValue()` (Swift `init?(rawValue:)`); `allCases` is `entries`, in the same order. `Rung` compares by declaration order, which is Swift's `allCases` order. |
| `var` fields on value types | Kotlin classes are references | `AttemptsSummary`, `ProgressionState`, `MouthParams` are immutable data classes; change one with `copy()`. `Progression` returns updated copies, as the Swift functions returned updated values. |
| `afterBlock` returns `(state:, step:)` | Kotlin has no labelled tuples | `Progression.BlockOutcome(state, step)`, which destructures as `val (state, step) = …`. |
| Key paths and keyword labels | `\.initial`, `in:`, `for:` are not Kotlin | `words(WordLists::initial, avoiding = …)`, `Catalog.unit(sound)`, `CoachSyllables.prompt(syllable)`, `PhonationNorms.seconds(age)`, `Progression.isLocked(rung, state)`, `Voice.defaultVoice(language)`. `SoundFix.when` is the backticked property `` `when` ``. |
| `Session.endButton` | Kotlin data classes compare constructor properties only | A body property. It is always "Gata pe azi", so equality is unchanged. |
| `Codable` on `Exercise`, `Trial`, `Session`, `AttemptsSummary`, `ProgressionState`, `MouthParams`, `TonguePos`… | Nothing serializes them on iOS (inventory §1.3) | Only the JSON content types are `@Serializable`. Their decoding follows `JSONDecoder` defaults: unknown keys ignored, every non-optional key required (`@Required` where the Kotlin constructor keeps Swift's memberwise defaults, as in `WordItem`), unknown raw values rejected, a UTF-8 BOM accepted. |
| `min(x, y)` on `Double` | `Math.min` treats -0.0 and NaN differently | Written as Swift defines it, `if (y < x) y else x`, for the hold times capped by the phonation norm. |

## Tests

| What | Why | Instead |
|---|---|---|
| `Pictures` suite reads `Pictures.xcassets` | fonia-content keeps the pictures as `images/pictures/<name>.png` and each imageset's `Contents.json` properties in `images/catalog.json` (`tools/sync_from_ios.py`) | Same checks on those files: a PNG and a catalog entry per picture, the catalog holds exactly the pictures in use, `1f40d` is `lossy`, names are lowercase code points. |
| `reportListsExactlyTheTextsWithNoClip` reads iOS `tools/missing-clips.txt` | The report is not part of fonia-content | A verbatim copy (line endings normalised) in `src/test/resources/missing-clips.txt`, from fonia-app `e587155`. When it changes on iOS, copy it again, or better, add it to fonia-content's sync. |
| `loadsFromABundleFolder` builds a `VoiceClips/` bundle | The Kotlin default folder is `voice/` | The test writes `voice/manifest.json` and checks the clip path ends in `voice/ro/ro/a.mp3`. |
| `#expect` keeps going after a failure | JUnit assertions stop at the first one | Same pass or fail for every test; only the failure report is shorter. |
| Parameterized `@Test(arguments:)` | — | JUnit `@ParameterizedTest` with the same arguments (cartesian products where Swift passes two collections). |

## Not verified here: whole sessions against iOS

The inventory (§13.8, §14.2 step 1) asks for whole sessions exported from iOS and compared element by element. There is
no Swift toolchain on this Windows machine, so that comparison has not run. What is checked: the generator against the
iOS vector and reference values, every iOS property test, and the order of draws, ported statement by statement.

`SessionVectorTests` holds the Android half. It renders 276 fixed requests plus `listeningCheck(seed: 5)`, one line per
trial, writes them to `domain/build/session-vectors/android-sessions.txt`, and compares them with
`src/test/resources/ios-sessions.txt` once that file exists (until then the test is skipped). To produce the file, add
this test to `Packages/FoniaKit/Tests/FoniaDomainTests` on a Mac, run `swift test --filter exportSessionVectors` in
`Packages/FoniaKit`, and copy `/tmp/ios-sessions.txt` into `domain/src/test/resources/`:

```swift
import Foundation
import Testing
@testable import FoniaDomain

@Test func exportSessionVectors() throws {
    let content = Fixture.content
    let builder = Fixture.builder
    var requests: [(String, SessionRequest)] = []
    for id in SoundID.allCases {
        for rung in Rung.allCases {
            for (age, completed) in [(AgeBand.fourToFive, 0), (.teenAdult, 3)] {
                requests.append(("sound \(id.rawValue) \(rung.rawValue) \(age.rawValue) \(completed)", SessionRequest(
                    age: age, focus: .sound(id, rung),
                    history: AttemptsSummary(sessionsCompleted: completed, recentItems: ["sanie"]),
                    seed: 42, avoidingSounds: [.r], includesOralWarmup: completed == 0
                )))
            }
        }
    }
    for id in SoundID.allCases {
        requests.append(("meet \(id.rawValue)", SessionRequest(age: .sixToSeven, focus: .meet(id), seed: 42)))
        requests.append(("story \(id.rawValue)", SessionRequest(age: .sixToSeven, focus: .story(id), seed: 42)))
        requests.append(("back \(id.rawValue)", SessionRequest(age: .sixToSeven, focus: .syllablesBack(id), seed: 42)))
    }
    requests.append(("voicing", SessionRequest(age: .sixToSeven, focus: .voicing, seed: 42)))
    requests.append(("voicingSay", SessionRequest(age: .sixToSeven, focus: .voicingSay, seed: 42)))
    requests.append(("warmups", SessionRequest(age: .sixToSeven, focus: .warmups(["lumanarea", "morisca"], breath: true), seed: 42)))
    for contrast in content.english.contrasts {
        let history = AttemptsSummary(sessionsCompleted: 1)
        let english: (EnglishFocus) -> SessionRequest = { SessionRequest(age: .teenAdult, focus: .english($0), history: history, seed: 11) }
        requests.append(("drill \(contrast.id)", english(.drill(contrast: contrast.id))))
        requests.append(("retest \(contrast.id)", english(.retest(contrast: contrast.id))))
        requests.append(("speaking \(contrast.id)", english(.speaking(contrast: contrast.id))))
        requests.append(("sentences \(contrast.id)", english(.sentences(contrast: contrast.id))))
    }
    for lesson in content.english.melody.lessons.indices {
        requests.append(("melody \(lesson)", SessionRequest(age: .teenAdult, focus: .english(.melody(lesson: lesson)), seed: 11)))
    }
    for area in DictionAreaID.allCases {
        requests.append(("diction \(area.rawValue)", SessionRequest(age: .teenAdult, focus: .diction(area), seed: 3, includesOralWarmup: true)))
    }
    for lesson in DictionLesson.allCases {
        requests.append(("lesson \(lesson.rawValue)", SessionRequest(age: .eightToTen, focus: .dictionLesson(lesson), seed: 3)))
    }

    func detail(_ exercise: Exercise) -> String {
        switch exercise {
        case .choose(let prompt): "\(prompt.other.text)|\(prompt.voice?.rawValue ?? "-")"
        case .hear(_, _, let has): "\(has)"
        case .sort(_, _, _, let answer): answer.rawValue
        case .sustain(_, let seconds, let role): "\(seconds)|\(role.rawValue)"
        case .repeat(let prompt): "\(prompt.level.rawValue)|\(prompt.rival ?? "-")"
        case .warmup(let id): id
        default: "-"
        }
    }
    func line(_ trial: Trial) -> String {
        "\(trial.block.kind.rawValue) \(trial.block.index) \(trial.feedback.rawValue) \(trial.prosody?.rawValue ?? "-") "
            + "\(trial.exercise.kind.rawValue) \(trial.exercise.itemText ?? "-") | \(detail(trial.exercise))"
    }

    var out = ""
    for (name, request) in requests {
        let session = builder.build(request)
        out += "# \(name) \(session.targetAttempts)\n"
        for trial in session.trials { out += line(trial) + "\n" }
    }
    out += "# listeningCheck 5\n"
    for trial in builder.listeningCheck(seed: 5) { out += line(trial) + "\n" }
    try out.write(toFile: "/tmp/ios-sessions.txt", atomically: true, encoding: .utf8)
}
```

A diff of the two files then points at the first request and trial where the draws part.

## Not a port: `tech.fonia.domain.adaptive`

The adaptive pilot of docs/PLAN.md §7.4 (modes, Challenge Point, feedback fading, BKT) is Android-only and lives in
its own package, so no ported file changes for it: `AdaptiveMaterial` reads each rung's two targets the way
`SessionBuilder.targets` does, and `AdaptivePlannerTests` checks them against the iOS sessions' target blocks. It is off
unless an adult switches it on for a profile; `AdaptiveOffVectorTests` renders the 276 requests above through its entry
point and compares them with `ios-sessions.txt` byte for byte, with no state and with state left from an earlier pilot.
