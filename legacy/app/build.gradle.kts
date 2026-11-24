import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** The ABIs the new ML runs on, and so the only ones that get ONNX Runtime (see `packaging` below). */
val onnxRuntimeAbis = listOf("arm64-v8a", "x86_64")

/** The 32-bit ONNX Runtime libraries left out of the APK. */
val onnxRuntime32Bit = listOf("armeabi-v7a", "x86").flatMap { abi -> listOf("lib/$abi/libonnxruntime.so", "lib/$abi/libonnxruntime4j_jni.so") }

/** `-Pfonia.zipa=false`: no ZIPA model in the assets (an emulator with little space); the judge then uses its fast head. */
val withZipa = providers.gradleProperty("fonia.zipa").map { it != "false" }.getOrElse(true)

/** Where the instrumented check finds ZIPA on the device, put there by `zipaModelToDevice`. */
val zipaDeviceFile = "/data/local/tmp/fonia/zipa-small-crctc-500k/model.int8.onnx"

android {
    namespace = "tech.fonia.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "tech.fonia.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 20
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["foniaZipaModel"] = zipaDeviceFile
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Until a release key lives in the CI secrets, testers get debug-signed builds.
            signingConfig = signingConfigs.getByName("debug")
            // Phones only: Whisper and the new ML run on 64-bit ARM, and x86_64 is only for the emulator (debug builds).
            ndk { abiFilters += "arm64-v8a" }
        }
        debug {
            // `-Pfonia.abi=x86_64`: a debug build for one ABI only, small enough for an emulator with little storage.
            providers.gradleProperty("fonia.abi").orNull?.let { ndk { abiFilters += it } }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    androidResources {
        // MP3, PNG and JPG are already compressed; JSON stays small.
        // ZIPA must stay mappable (ml/README.md): compressed, it is copied (70 MB) on first run.
        noCompress += listOf("mp3", "onnx")
        if (!withZipa) ignoreAssetsPatterns += "*.onnx"
    }

    packaging {
        jniLibs {
            // ONNX Runtime runs the new ML (shadow mode), which runs where Whisper runs: 64-bit phones (arm64-v8a) and the
            // x86_64 emulator. Its 32-bit copies would only add size for processors that run neither; the app checks
            // the processor before it offers the model (MlHub.runsOnnxRuntime).
            excludes += onnxRuntime32Bit
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

/** Copies the parts of the content submodule the app reads (JSON, voices, images, fonts) into generated assets. */
abstract class ContentAssetsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val content: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        for (folder in listOf("json", "voice", "images", "fonts")) {
            content.get().asFile.resolve(folder).copyRecursively(out.resolve(folder), overwrite = true)
        }
    }
}

/**
 * The new take judge's data files (`spec/thresholds`, `spec/fusion`, `spec/unlock.json`, `spec/feedback.json`) into the
 * assets under `spec/`, as the lab app does: the judge reads exactly the committed versions.
 */
abstract class SpecAssetsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val specFiles: ConfigurableFileCollection

    @get:Internal
    abstract val specRoot: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile.resolve("spec")
        out.deleteRecursively()
        val root = specRoot.get().asFile
        specFiles.files.filter { it.isFile }.forEach { f -> f.copyTo(out.resolve(f.relativeTo(root).invariantSeparatorsPath), overwrite = true) }
    }
}

val specAssets = tasks.register<SpecAssetsTask>("specAssets") {
    val spec = rootDir.resolve("spec")
    specRoot.set(spec)
    specFiles.from(
        fileTree(spec.resolve("thresholds")) { include("*.json") },
        fileTree(spec.resolve("fusion")) { include("*.json") },
        spec.resolve("unlock.json"),
        spec.resolve("feedback.json"),
        // The diction training (v2-flow): the original v2 texts and their generated voice clips (about 11 MB).
        fileTree(spec.resolve("content/v2")) { include("**/*.json") },
        spec.resolve("content/voice/index.json"),
        fileTree(spec.resolve("content/voice/clips")) { include("*.m4a") },
    )
}

val contentAssets = tasks.register<ContentAssetsTask>("contentAssets") {
    content.set(layout.projectDirectory.dir("../../content"))
}

/**
 * One ONNX Runtime in the APK (docs/PLAN.md §6): each ABI the new ML runs on carries exactly one `libonnxruntime*.so`
 * runtime, and no other ABI carries any. sherpa-onnx and Moonshine bring their own copies; if one of them comes in,
 * this fails until the PLAN's choice is made, instead of shipping two runtimes. It also fails if the runtime's 1DS
 * telemetry provider is back in the merged manifest (see AndroidManifest.xml). Runs after every assemble.
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
        val manifest = mergedManifest.get().asFile
        if (manifest.readText().contains("ai.onnxruntime.TelemetryInitializer")) {
            throw GradleException("$manifest declares ONNX Runtime's telemetry provider: remove it (tools:node=\"remove\" in app/src/main/AndroidManifest.xml)")
        }
        if (manifest.readText().contains("android.permission.INTERNET")) {
            throw GradleException("$manifest asks for INTERNET: Fonia never does (tools:node=\"remove\" in app/src/main/AndroidManifest.xml)")
        }
        val built = loader.get().load(apkFolder.get()) ?: throw GradleException("No APK in ${apkFolder.get()}")
        val lines = mutableListOf<String>()
        for (element in built.elements) {
            ZipFile(element.outputFile).use { zip ->
                val runtimes = zip.entries().asSequence().map { it.name }
                    .filter { it.startsWith("lib/") && it.substringAfterLast('/').let { name -> name.startsWith("libonnxruntime") && name.endsWith(".so") } }
                    .filter { !it.endsWith("/libonnxruntime4j_jni.so") }
                    .toList()
                val packaged = zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") }.map { it.split('/')[1] }.toSet()
                // An ABI the APK doesn't carry at all (a build for one device, an ABI split) asks for nothing.
                for (abi in abis.get().filter { it in packaged }) {
                    val copies = runtimes.filter { it.startsWith("lib/$abi/") }
                    if (copies.size != 1) throw GradleException("${element.outputFile}: lib/$abi has ${copies.size} ONNX Runtime libraries $copies, not one")
                }
                val elsewhere = runtimes.filter { path -> abis.get().none { path.startsWith("lib/$it/") } }
                if (elsewhere.isNotEmpty()) throw GradleException("${element.outputFile}: ONNX Runtime for ABIs without the new ML: $elsewhere")
                lines += "${File(element.outputFile).name}: " + runtimes.joinToString(", ") { "$it ${zip.getEntry(it).size} bytes" }
            }
        }
        report.get().asFile.writeText(lines.joinToString("\n") + "\n")
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(contentAssets, ContentAssetsTask::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(specAssets, SpecAssetsTask::outputDir)
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val check = tasks.register<CheckOnnxRuntimeTask>("check${name}OnnxRuntime") {
            apkFolder.set(variant.artifacts.get(SingleArtifact.APK))
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            loader.set(variant.artifacts.getBuiltArtifactsLoader())
            abis.set(onnxRuntimeAbis)
            report.set(layout.buildDirectory.file("reports/onnxruntime-${variant.name}.txt"))
        }
        tasks.matching { it.name == "assemble$name" }.configureEach { finalizedBy(check) }
    }
}

/**
 * Puts the pinned ZIPA model on the connected device for the instrumented check of shadow mode: from the copy the
 * :phones tests keep in Gradle's cache (`fonia-models/zipa-small-crctc-500k`), downloaded and SHA-256 checked if it
 * isn't there, pushed once (size checked). Inside the app the test installs it through the model manager, which checks
 * the hash again. Set ANDROID_SERIAL when several devices are connected.
 */
abstract class ZipaModelToDeviceTask : DefaultTask() {
    @get:Inject
    abstract val exec: ExecOperations

    @get:Internal
    abstract val adb: RegularFileProperty

    @get:Internal
    abstract val cache: RegularFileProperty

    @get:Input
    abstract val deviceFile: Property<String>

    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val bytes: Property<Long>

    @get:Input
    abstract val sha256: Property<String>

    @TaskAction
    fun run() {
        val adbPath = adb.get().asFile.absolutePath
        val size = ByteArrayOutputStream()
        exec.exec {
            commandLine(adbPath, "shell", "stat", "-c", "%s", deviceFile.get(), "2>/dev/null", "||", "echo", "-1")
            standardOutput = size
        }
        if (size.toString().trim() == bytes.get().toString()) return
        val local = cache.get().asFile
        if (!local.isFile || local.length() != bytes.get() || sha256Of(local) != sha256.get()) {
            logger.lifecycle("Downloading ${url.get()}")
            local.parentFile.mkdirs()
            val part = File(local.path + ".part")
            URI(url.get()).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it, 1 shl 20) } }
            check(part.length() == bytes.get() && sha256Of(part) == sha256.get()) { "${url.get()} does not match its pinned size and SHA-256" }
            part.renameTo(local)
        }
        logger.lifecycle("Pushing ${local.name} to ${deviceFile.get()}")
        exec.exec { commandLine(adbPath, "shell", "mkdir", "-p", deviceFile.get().substringBeforeLast('/')) }
        exec.exec { commandLine(adbPath, "push", local.absolutePath, deviceFile.get()) }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

// The same pins as ZipaModel.INT8 in :phones (phones/MODELS.md); ShadowSessionTests checks the installed file against
// ModelCatalog.ZIPA, so the two can't drift apart unnoticed.
val zipaModelToDevice = tasks.register<ZipaModelToDeviceTask>("zipaModelToDevice") {
    description = "Puts the pinned ZIPA-small int8 model at $zipaDeviceFile on the connected device."
    adb.set(androidComponents.sdkComponents.adb)
    cache.set(File(gradle.gradleUserHomeDir, "fonia-models/zipa-small-crctc-500k/model.int8.onnx"))
    deviceFile.set(zipaDeviceFile)
    url.set("https://huggingface.co/anyspeech/zipa-small-crctc-500k/resolve/dfe87c97662606fa6db90ad06f627d4363314744/model.int8.onnx")
    bytes.set(70_677_672L)
    sha256.set("d0e28b68164e8b1fbd6105100c01798828aa0855000ce9bbbd1a2cec233adf13")
}

tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach { dependsOn(zipaModelToDevice) }

dependencies {
    implementation(project(":legacy:features"))
    // The one ONNX Runtime of the APK (docs/PLAN.md §6): :phones compiles against its API, the app brings the runtime.
    implementation(libs.onnxruntime.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.junit4)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
    // The instrumented check of shadow mode installs ZIPA through the model manager and reads the pilot log.
    androidTestImplementation(project(":engine:phones"))
    androidTestImplementation(project(":engine:judge"))
    debugImplementation(libs.compose.ui.test.manifest)
}
