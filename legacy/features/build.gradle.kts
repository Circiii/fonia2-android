import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "tech.fonia.features"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    sourceSets {
        // Synthetic speech with known word times and a scripted recogniser (the speaking exercises not on iOS): the
        // same code drives the JVM tests and the emulator tests.
        getByName("test").kotlin.directories.add("src/sharedTest/kotlin")
        getByName("androidTest").kotlin.directories.add("src/sharedTest/kotlin")
    }
    testOptions {
        unitTests {
            // PathModel keeps its values in Compose snapshot state; nothing else of Android runs in the JVM tests.
            isReturnDefaultValues = true
            all { test ->
                // The tests read the shared content (JSON) straight from the submodule, like :domain's.
                test.systemProperty("fonia.content", rootDir.resolve("content").absolutePath)
            }
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// The JVM tests open a real FoniaDatabase in memory, as PathModelTests does on iOS. An Android module gets the
// Android build of the bundled SQLite driver, whose native library only exists for Android, so the unit-test
// runtime takes the desktop build of the same version instead (the one :store's own tests run on).
configurations.configureEach {
    if (name.endsWith("UnitTestRuntimeClasspath")) {
        resolutionStrategy.dependencySubstitution {
            substitute(module("androidx.sqlite:sqlite-bundled-android")).using(module("androidx.sqlite:sqlite-bundled-jvm:2.7.1"))
            substitute(module("androidx.sqlite:sqlite-android")).using(module("androidx.sqlite:sqlite-jvm:2.7.1"))
        }
    }
}

/**
 * What the emulator tests of :features draw with (`src/androidTest`): the fonts, the image catalog with its icons and
 * Foni's pose on the adults' door, and the JSON content, copied from the submodule into the test APK's assets, as
 * :app's `contentAssets` does for the app. The voices, the scenes and the word pictures stay out.
 */
abstract class DeviceTestAssetsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fonts: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val json: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val icons: DirectoryProperty

    /** Only `mascot-easy` is copied from here: the pose on the adults' door; and the speaking page's scene. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val scenes: DirectoryProperty

    /** The art tiles of the rows, for the speaking page that opens the exercises not on iOS (about 0.9 MB). */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val art: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val catalog: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        fonts.get().asFile.copyRecursively(out.resolve("fonts"), overwrite = true)
        json.get().asFile.copyRecursively(out.resolve("json"), overwrite = true)
        icons.get().asFile.copyRecursively(out.resolve("images/icons"), overwrite = true)
        scenes.get().asFile.resolve("mascot-easy.png").copyTo(out.resolve("images/scenes/mascot-easy.png"), overwrite = true)
        for (name in listOf("scene-speaking.jpg", "scene-speaking-tall.jpg", "scene-speaking-wide.jpg")) {
            scenes.get().asFile.resolve(name).copyTo(out.resolve("images/scenes/$name"), overwrite = true)
        }
        art.get().asFile.copyRecursively(out.resolve("images/art"), overwrite = true)
        catalog.get().asFile.copyTo(out.resolve("images/catalog.json"), overwrite = true)
    }
}

val deviceTestAssets = tasks.register<DeviceTestAssetsTask>("deviceTestAssets") {
    val content = layout.projectDirectory.dir("../../content")
    fonts.set(content.dir("fonts"))
    json.set(content.dir("json"))
    icons.set(content.dir("images/icons"))
    scenes.set(content.dir("images/scenes"))
    art.set(content.dir("images/art"))
    catalog.set(content.file("images/catalog.json"))
}

// `-Pfonia.deviceTestAbi=x86_64` packs only that ABI's native libraries into the test APK, and leaves out the model
// runtimes no :features emulator test loads (LiteRT for the face, whisper.cpp): about 30 MB less, for an emulator
// short of space. Without it the test APK has everything, as before.
val deviceTestAbi: String? = providers.gradleProperty("fonia.deviceTestAbi").orNull

androidComponents {
    onVariants { variant ->
        variant.deviceTests.values.forEach { test ->
            test.sources.assets?.addGeneratedSourceDirectory(deviceTestAssets, DeviceTestAssetsTask::outputDir)
            if (deviceTestAbi != null) {
                val others = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").filter { it != deviceTestAbi }
                test.packaging.jniLibs.excludes.addAll(others.map { "lib/$it/**" })
                test.packaging.jniLibs.excludes.addAll(listOf("**/libLiteRt.so", "**/liblitert_jni.so", "**/libfonia_whisper*.so"))
            }
        }
    }
}

dependencies {
    api(project(":legacy:design"))
    api(project(":core:domain"))
    api(project(":engine:dsp"))
    api(project(":core:data"))
    api(project(":engine:judge"))
    api(project(":engine:face"))
    api(project(":platform:audio"))
    // The Whisper runtime behind SpeechHub (`FoniaWhisper.modelStore`), as WhisperKit sits behind FoniaSpeech on iOS.
    implementation(project(":platform:whisper"))
    implementation(project(":platform:camera"))
    // New ML next to the port (docs/PLAN.md §6): ZIPA phone scoring in shadow mode. The app brings the one ONNX Runtime
    // (`onnxruntime-android`); :phones only compiles against its API.
    implementation(project(":engine:phones"))
    // Fonia 2 improvements brought into the iOS-style app (decision N35): the living letter and the motion kit, the live
    // audio band, the new take judge's phone model, the 3D mouth, and the "Tu, fără ăăă" engine.
    implementation(project(":core:model"))
    implementation(project(":core:glyphs"))
    implementation(project(":core:designsystem"))
    implementation(project(":engine:live"))
    implementation(project(":platform:ml"))
    implementation(project(":core:mouth3d"))
    implementation(project(":engine:speaking"))
    // The pilot's event log is its own SQLite file, on the same bundled driver as :store (whose schema stays iOS's).
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    // The empty activity the Compose test rule puts the screens in.
    androidTestImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
