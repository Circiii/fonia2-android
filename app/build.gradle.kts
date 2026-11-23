import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.zip.ZipFile

/**
 * Copies the judge's data files (spec/thresholds, spec/fusion, spec/unlock.json, spec/feedback.json) into the APK
 * assets under `spec/`, so the real judge reads exactly the committed versions (plan 03 §7: read from the APK, never
 * changed on the phone).
 */
abstract class SpecAssetsTask : DefaultTask() {
    /** The folder inside the assets the files land in (`spec`, or `faces` for the debug face fixture). */
    @get:Input
    abstract val prefix: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val specFiles: ConfigurableFileCollection

    @get:Internal
    abstract val specRoot: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile.resolve(prefix.getOrElse("spec"))
        out.deleteRecursively()
        val root = specRoot.get().asFile
        specFiles.files.filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(root).invariantSeparatorsPath
            f.copyTo(out.resolve(rel), overwrite = true)
        }
    }
}

/**
 * One ONNX Runtime in the APK (ml/README.md, copied from legacy/app): each shipped ABI carries exactly one
 * `libonnxruntime.so`, no 32-bit ABI carries any, the runtime's telemetry provider is gone from the merged manifest and
 * so is `android.permission.INTERNET`. Finalizes every assemble.
 */
abstract class CheckOnnxRuntimeTask : DefaultTask() {
    @get:InputFiles
    abstract val apkFolder: DirectoryProperty

    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:Internal
    abstract val loader: Property<BuiltArtifactsLoader>

    @get:Input
    abstract val abis: ListProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val manifest = mergedManifest.get().asFile.readText()
        if (manifest.contains("ai.onnxruntime.TelemetryInitializer")) throw GradleException("merged manifest declares ONNX Runtime's telemetry provider")
        if (manifest.contains("android.permission.INTERNET")) throw GradleException("merged manifest asks for INTERNET: Fonia never does")
        val built = loader.get().load(apkFolder.get()) ?: throw GradleException("No APK in ${apkFolder.get()}")
        val lines = mutableListOf<String>()
        for (element in built.elements) {
            ZipFile(element.outputFile).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                val runtimes = names.filter { it.startsWith("lib/") && it.substringAfterLast('/') == "libonnxruntime.so" }
                val packaged = names.filter { it.startsWith("lib/") }.map { it.split('/')[1] }.toSet()
                for (abi in abis.get().filter { it in packaged }) {
                    val copies = runtimes.filter { it.startsWith("lib/$abi/") }
                    if (copies.size != 1) throw GradleException("${element.outputFile}: lib/$abi has ${copies.size} ONNX Runtime libraries $copies, not one")
                }
                val elsewhere = runtimes.filter { path -> abis.get().none { path.startsWith("lib/$it/") } }
                if (elsewhere.isNotEmpty()) throw GradleException("${element.outputFile}: ONNX Runtime for 32-bit ABIs: $elsewhere")
                val model = names.firstOrNull { it.endsWith(".onnx") }
                lines += "${File(element.outputFile).name}: " + runtimes.joinToString(", ") { "$it ${zip.getEntry(it).size} bytes" } +
                    "; model " + (model?.let { "$it ${zip.getEntry(it).size} bytes, method ${zip.getEntry(it).method}" } ?: "none (-Pfonia.zipa=false)")
            }
        }
        report.get().asFile.writeText(lines.joinToString(System.lineSeparator()) + System.lineSeparator())
    }
}

/** `-Pfonia.zipa=false`: build without the ZIPA model (the judge then always uses the fast head). Default: with it. */
val withZipa = providers.gradleProperty("fonia.zipa").map { it != "false" }.getOrElse(true)

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "tech.fonia.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "tech.fonia.lab"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0-dev"
        // 64-bit only: whisper.cpp is built for these two, LiteRT has no 32-bit x86; phones since 2019 are arm64.
        // `-Pfonia.abi=x86_64`: one ABI only (an emulator with little free space), as :platform:ml.
        ndk { abiFilters += providers.gradleProperty("fonia.abi").orNull?.let { listOf(it) } ?: listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Until a release key lives in the CI secrets, testers get debug-signed builds.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    // ZIPA must stay mappable (ml/README.md): without this the model is copied (70 MB) on first run.
    androidResources {
        noCompress += "onnx"
        if (!withZipa) ignoreAssetsPatterns += "*.onnx"
    }
    packaging {
        // ONNX Runtime's 32-bit copies: neither ABI runs the model.
        jniLibs.excludes += listOf("armeabi-v7a", "x86").flatMap { listOf("lib/$it/libonnxruntime.so", "lib/$it/libonnxruntime4j_jni.so") }
    }

    sourceSets {
        // Fixture mode (A3): the WAVs that stand in for the microphone ship in debug builds only.
        getByName("debug").assets.directories.add(rootDir.resolve("spec/fixtures/audio").absolutePath)
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.systemProperty("fonia.root", rootDir.absolutePath) }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

val specAssets = tasks.register<SpecAssetsTask>("specAssets") {
    prefix.set("spec")
    val spec = rootDir.resolve("spec")
    specRoot.set(spec)
    specFiles.from(
        fileTree(spec.resolve("thresholds")) { include("*.json") },
        fileTree(spec.resolve("fusion")) { include("*.json") },
        spec.resolve("unlock.json"),
        spec.resolve("feedback.json"),
    )
}

/**
 * Content v2 and Foni's voice (`spec/content/v2`, `spec/content/voice` → `assets/content/…`), plus the model notices
 * the licences screen shows (`spec/models/NOTICE`, `spec/mouth3d/NOTICE` → `assets/notices/…`). Read once, off the
 * main thread, by `ContentRepo`.
 */
val contentAssets = tasks.register<SpecAssetsTask>("contentAssets") {
    prefix.set("content")
    val content = rootDir.resolve("spec/content")
    specRoot.set(content)
    specFiles.from(
        fileTree(content.resolve("v2")) { include("**/*.json") },
        fileTree(content.resolve("voice")) { include("index.json", "clips/*.m4a", "LICENSE.md") },
    )
}
val noticeAssets = tasks.register<SpecAssetsTask>("noticeAssets") {
    prefix.set("notices")
    specRoot.set(rootDir.resolve("spec"))
    specFiles.from(rootDir.resolve("spec/models/NOTICE"), rootDir.resolve("spec/mouth3d/NOTICE"))
}

/**
 * The debug face fixture (`--es face fixture`): a public-domain portrait already in the repo for the camera's
 * instrumented tests (`small_smile.png`, facecamera/MODELS.md), copied into the debug assets under `faces/`. Never in
 * release.
 */
val faceFixtureAssets = tasks.register<SpecAssetsTask>("faceFixtureAssets") {
    prefix.set("faces")
    val faces = rootDir.resolve("facecamera/src/androidTest/assets/faces")
    specRoot.set(faces)
    specFiles.from(listOf("small_smile.png").map { faces.resolve(it) })
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(specAssets, SpecAssetsTask::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(contentAssets, SpecAssetsTask::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(noticeAssets, SpecAssetsTask::outputDir)
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val check = tasks.register<CheckOnnxRuntimeTask>("check${name}OnnxRuntime") {
            apkFolder.set(variant.artifacts.get(SingleArtifact.APK))
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            loader.set(variant.artifacts.getBuiltArtifactsLoader())
            abis.set(listOf("arm64-v8a", "x86_64"))
            report.set(layout.buildDirectory.file("reports/onnxruntime-${variant.name}.txt"))
        }
        tasks.matching { it.name == "assemble$name" }.configureEach { finalizedBy(check) }
        if (variant.buildType == "debug") variant.sources.assets?.addGeneratedSourceDirectory(faceFixtureAssets, SpecAssetsTask::outputDir)
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:glyphs"))
    implementation(project(":core:model"))
    implementation(project(":core:navigation"))
    implementation(project(":core:data"))
    implementation(project(":engine:live"))
    implementation(project(":engine:judge"))
    implementation(project(":engine:dsp"))
    implementation(project(":platform:audio"))
    implementation(project(":platform:camera")) // MIRROR: CameraX + Face Landmarker (api :engine:face)
    implementation(project(":engine:face"))
    implementation(project(":feature:speaking")) // NO-UM, MINUTE (api :engine:speaking)
    implementation(project(":engine:speaking"))
    implementation(project(":platform:whisper")) // whisper.cpp, only when the model file is on the phone
    implementation(project(":core:lessons")) // content v2 + LessonBuilder + VoiceIndex
    implementation(project(":platform:ml")) // ZIPA on onnxruntime-android (the judge's full head)
    implementation(project(":core:mouth3d")) // „Vezi gura”
    implementation(libs.kotlinx.serialization.json) // the practice book (LessonHistory, rungs, days) as a JSON file
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
