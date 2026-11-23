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

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parse(manifest.get().asFile) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val models = root["models"] as List<Map<String, Any?>>
        for (m in models.takeIf { includeModels.get() }.orEmpty()) {
            val id = m["id"] as String
            val source = File(cacheDir.get().asFile, m["cache"] as String)
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
        manifest.get().asFile.copyTo(File(out, "models/manifest.json"), overwrite = true)
    }
}

val modelAssets = tasks.register<ModelAssetsTask>("modelAssets") {
    manifest.set(rootProject.layout.projectDirectory.file("spec/models/manifest.json"))
    cacheDir.set(File(gradle.gradleUserHomeDir, "fonia-models"))
    outputDir.set(layout.buildDirectory.dir("generated/fonia-models"))
    includeModels.set(providers.gradleProperty("fonia.ml.modelOnDevice").map { it != "true" }.orElse(true))
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

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(modelAssets, ModelAssetsTask::outputDir)
        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(referenceClip, ReferenceClipTask::outputDir)
    }
}
