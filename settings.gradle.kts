pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "fonia-android"

// Fonia 2 (branch v2). Project names follow docs/v2/00_PLAN_MASTER.md §3; the engine folders keep their old
// names so history and the iOS mapping stay readable. `folder` is given only where it differs from the name.
fun module(path: String, folder: String) {
    include(path)
    project(path).projectDir = file(folder)
    // The group project (":core", ":engine"…) holds nothing, but Gradle wants its folder to exist.
    val group = path.substringBeforeLast(':')
    project(group).projectDir = file("gradle/groups/${group.removePrefix(":")}")
}

// Pure Kotlin/JVM: everything that decides. Tested on the PC in seconds.
module(":core:model", "model") // SoundId, Verdict, ErrorKind, Rung: the shared names
module(":core:domain", "domain") // content, SessionBuilder, Progression, adaptive (FoniaDomain)
module(":core:data", "store") // SQLite, repositories, export (FoniaStore)
module(":core:glyphs", "glyphs") // Allo outlines and the contour interpolator behind LiveLetter
module(":core:navigation", "navigation") // screen keys, no AndroidX
module(":engine:dsp", "dsp") // FoniaDSP
module(":engine:live", "live") // LiveSnapshot, LiveBoard, LivePipeline, FxGate, the AudioDirector port
module(":engine:judge", "speech") // FoniaSpeech + the take judges and the fusion
module(":engine:phones", "phones") // ZIPA + restricted GOP (kept apart so the fast gate needs no model)
module(":engine:face", "face") // FoniaFace
module(":engine:session", "session") // the session reducer

// Android ports.
module(":platform:audio", "audio") // Oboe capture, playback (C++ through the NDK)
module(":platform:camera", "facecamera") // CameraX + Face Landmarker on LiteRT
module(":platform:whisper", "whisper") // whisper.cpp through the NDK

// The old look, kept compiling as a reference until the new shell replaces it (plan 02 §2, step 7).
module(":legacy:app", "legacy/app")
module(":legacy:features", "legacy/features")
module(":legacy:design", "legacy/design")

// Fonia Colector (B1): the recorder APK for the training data, an application of its own (tech.fonia.collector).
include(":collector")
// Fonia 2 shell and design system (packages D1 + C3 + C4).
module(":core:designsystem", "design") // tokens, FoniaTheme, LiveLetter / LiveWord / StickerText and the letter's motion
include(":app") // the new shell (for now: the letter lab)

// Fonia 2, public speaking (packages G11 "Tu, fără ăăă" and G12's prosodic transcript): pure engine + screens.
module(":engine:speaking", "speaking") // filler detection, CleanCut (the cleaned voice), the prosodic transcript
module(":feature:speaking", "feature/speaking") // NoUmScreen, MinuteScreen (no :platform:* dependency)

// Fonia 2, package E9 (judge part): the phoneme model in the APK. ZIPA on onnxruntime-android behind the judge's
// PhoneScorerPort; the model is copied from the PC cache at build time (no download, no INTERNET).
module(":platform:ml", "ml") // ModelHost, ZipaHost, AssetModelFile, ModelRegistry, ModelMemoryPolicy

// Fonia 2, packages H6–H8: the stylised 3D mouth (cut from Google GNM, own GL ES 3 renderer) and its debug demo APK.
module(":core:mouth3d", "mouth3d") // Mouth3D composable, mouth.fm3d reader, tongue rig, poses, 2D fallback
include(":mouth3d-demo") // debug-only viewer (tech.fonia.mouth3d.demo): sound picker, rest↔pose slider, inside view
// Fonia 2, content and lessons (packages I1 schema v2, I2, I4, I5, I8 content in spec/content; F2 lesson builder).
module(":core:lessons", "lessons") // content v2 model + loader, LessonBuilder (4-minute plan), voice index

// Fonia 2, design system gallery (Foni v2, word pictures, scenes, motion): debug-only APK tech.fonia.design.demo.
// adb shell am start -n tech.fonia.design.demo/.DemoActivity --es page foni|pictures|scenes|motion [--ez demo true]
include(":design-demo")
