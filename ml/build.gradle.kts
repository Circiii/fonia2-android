import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
}

// :platform:ml (package E9, plan 02 §6): the phoneme model inside the APK, on onnxruntime-android, behind the judge's
// PhoneScorerPort. No download code and no INTERNET: the model is copied at build time from the PC cache.

android {
    namespace = "tech.fonia.ml"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // With -Pfonia.ml.modelOnDevice=true the instrumented test reads ZIPA from here (adb push it first, ml/README.md).
        testInstrumentationRunnerArguments["foniaZipaModel"] = "/data/local/tmp/fonia/zipa-small-crctc-500k/model.int8.onnx"
        consumerProguardFiles("consumer-rules.pro")
        // arm64-v8a for phones; x86_64 only so the emulator can run the instrumented test (plan 02 §6, ABI).
        // `-Pfonia.abi=x86_64`: one ABI only (as legacy/app), small enough for an emulator with little storage.
        ndk { abiFilters += providers.gradleProperty("fonia.abi").orNull?.let { listOf(it) } ?: listOf("arm64-v8a", "x86_64") }
    }
    // The model stays uncompressed so AssetModelFile can memory-map it (openFd). This covers the library's own test
    // APK; the app sets the same (ml/README.md).
    androidResources { noCompress += "onnx" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        // ONNX Runtime's 32-bit copies: neither ABI runs the model (plan 02 §6: armeabi-v7a is out).
        jniLibs.excludes += listOf("armeabi-v7a", "x86").flatMap { listOf("lib/$it/libonnxruntime.so", "lib/$it/libonnxruntime4j_jni.so") }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // The judge (PhoneScorerPort, JudgeRequest, Clip via :engine:live) and ZIPA + GOP (ZipaPhoneScorer).
    api(project(":engine:phones"))
    api(project(":engine:words"))
    api(project(":engine:judge"))
    implementation(project(":engine:live"))
    // The one ONNX Runtime of the APK: :engine:phones compiles against the desktop jar's identical ai.onnxruntime API.
    implementation(libs.onnxruntime.android)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

/**
 * Copies every model of `spec/models/manifest.json` from the PC cache (`~/.gradle/fonia-models/<cache>`) into generated
 * assets at `<asset>`, after checking size and SHA-256, plus the manifest itself (`models/manifest.json`, read by
 * ModelRegistry). Fails with what to do when a file is missing or different. Nothing is downloaded here.
 */
abstract class ModelAssetsTask : DefaultTask() {
    @get:InputFile
    abstract val manifest: RegularFileProperty

    /** The cache folder; its files are checked by hash in the action, so it is not a tracked input (70 MB). */
    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** False only for `-Pfonia.ml.modelOnDevice=true` (an emulator too full for a 120 MB test APK): manifest only. */
    @get:Input
    abstract val includeModels: Property<Boolean>

    /** Ids of optional models switched off for this build (`-Pfonia.roasr=false`). */
    @get:Input
    abstract val skipped: ListProperty<String>

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parse(manifest.get().asFile) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val models = root["models"] as List<Map<String, Any?>>
        val kept = ArrayList<Map<String, Any?>>()
        for (m in models.takeIf { includeModels.get() }.orEmpty()) {
            val id = m["id"] as String
            val source = File(cacheDir.get().asFile, m["cache"] as String)
            // An optional model is left out when its switch is off or its file is not in the cache: the app then
            // finds no entry for it and goes on without (RoAsrHost.create returns null).
            if (m["optional"] == true && (id in skipped.get() || !source.isFile)) {
                logger.lifecycle("Model $id is optional and is left out of this APK.")
                continue
            }
            kept += m
            val bytes = (m["bytes"] as Number).toLong()
            val sha = m["sha256"] as String
            if (!source.isFile) {
                throw GradleException(
                    "Model '$id' is missing from $source. The APK carries it but the build downloads nothing. Put the pinned " +
                        "file there once (${m["source"]}, $bytes bytes, SHA-256 $sha), e.g. by running " +
                        "`./gradlew :engine:phones:test` on a PC with network (its ModelCache fetches it into ~/.gradle/fonia-models).",
                )
            }
            if (source.length() != bytes) throw GradleException("Model '$id': $source has ${source.length()} bytes, the manifest pins $bytes. Replace it with the pinned file.")
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != sha) throw GradleException("Model '$id': $source has SHA-256 $actual, the manifest pins $sha. Replace it with the pinned file.")
            source.copyTo(File(out, m["asset"] as String), overwrite = true)
        }
        // The manifest inside the APK lists only what the APK carries.
        val inApk = if (includeModels.get()) root + mapOf("models" to kept) else root
        File(out, "models").mkdirs()
        File(out, "models/manifest.json").writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(inApk)), Charsets.UTF_8)
    }
}

val modelAssets = tasks.register<ModelAssetsTask>("modelAssets") {
    manifest.set(rootProject.layout.projectDirectory.file("spec/models/manifest.json"))
    cacheDir.set(File(gradle.gradleUserHomeDir, "fonia-models"))
    outputDir.set(layout.buildDirectory.dir("generated/fonia-models"))
    includeModels.set(providers.gradleProperty("fonia.ml.modelOnDevice").map { it != "true" }.orElse(true))
    skipped.set(providers.gradleProperty("fonia.roasr").map { if (it == "false") listOf("roasr") else emptyList() }.orElse(emptyList()))
}

/**
 * ZIPA's own reference clip (LibriSpeech, CC BY 4.0) for the instrumented parity test, from the same cache when it is
 * there (the phones tests fetch it); absent → the test skips that check. Never committed.
 */
abstract class ReferenceClipTask : DefaultTask() {
    @get:Internal
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val file = source.get().asFile
        if (file.isFile) file.copyTo(File(out, "zipa-reference/sample.wav"), overwrite = true)
    }
}

val referenceClip = tasks.register<ReferenceClipTask>("zipaReferenceClip") {
    source.set(File(gradle.gradleUserHomeDir, "fonia-models/zipa-reference/sample.wav"))
    outputDir.set(layout.buildDirectory.dir("generated/zipa-reference"))
    outputs.upToDateWhen { false }
}

/** The judge's data files for the instrumented full-head check (JudgeOnDeviceTest), as the app bundles them. */
abstract class JudgeSpecAssetsTask : DefaultTask() {
    @get:InputDirectory
    abstract val specDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = File(outputDir.get().asFile, "spec")
        out.deleteRecursively()
        val spec = specDir.get().asFile
        for (rel in listOf("thresholds/population.json", "unlock.json", "feedback.json")) File(spec, rel).copyTo(File(out, rel), overwrite = true)
        File(spec, "fusion").listFiles { f -> f.extension == "json" }!!.forEach { it.copyTo(File(out, "fusion/${it.name}"), overwrite = true) }
        // Four of the prompt voice's words (content submodule) for a real take on the device; skipped without it.
        val voice = voiceDir.get().asFile
        val manifest = File(voice, "manifest.json")
        if (manifest.isFile) {
            @Suppress("UNCHECKED_CAST")
            val map = JsonSlurper().parse(manifest) as Map<String, Any?>
            for (w in listOf("sare", "șarpe", "rac", "ger")) {
                val rel = map["ro|ro|$w"] as? String ?: continue
                val f = File(voice, rel)
                if (f.isFile) f.copyTo(File(outputDir.get().asFile, "judge-takes/$w.mp3"), overwrite = true)
            }
        }
    }

    @get:Internal
    abstract val voiceDir: DirectoryProperty
}

val judgeSpecAssets = tasks.register<JudgeSpecAssetsTask>("judgeSpecAssets") {
    specDir.set(rootProject.layout.projectDirectory.dir("spec"))
    voiceDir.set(rootProject.layout.projectDirectory.dir("content/voice"))
    outputDir.set(layout.buildDirectory.dir("generated/judge-spec"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(modelAssets, ModelAssetsTask::outputDir)
        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(referenceClip, ReferenceClipTask::outputDir)
        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(judgeSpecAssets, JudgeSpecAssetsTask::outputDir)
    }
}
