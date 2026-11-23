import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
}

/** Where the instrumented tests find the real model, as iOS's find it through FONIA_WHISPER_MODEL. */
val deviceModelFolder = "/data/local/tmp/fonia/openai_whisper-base"

android {
    namespace = "tech.fonia.whisper"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["foniaWhisperModel"] = deviceModelFolder
        // Lets the harness hand results.jsonl back: it lands in build/outputs/connected_android_test_additional_output.
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"
        consumerProguardFiles("consumer-rules.pro")
        // arm64-v8a for phones; x86_64 only so the emulator can run the instrumented tests.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":engine:judge"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation("androidx.test.services:storage:1.6.0")
    androidTestUtil("androidx.test.services:test-services:1.6.0")
}

/**
 * Puts the pinned model on the connected device for the instrumented tests: downloaded once into
 * Gradle's cache on this computer (SHA-256 checked), pushed once to [deviceModelFolder] (sizes checked).
 * The list repeats WhisperCppModel.WHISPER_BASE_FROM_HUGGING_FACE; WhisperIntegrationTests.harness
 * fails if the two ever disagree. Set ANDROID_SERIAL when several devices are connected.
 */
abstract class WhisperModelToDeviceTask : DefaultTask() {
    @get:Inject
    abstract val exec: ExecOperations

    @get:Internal
    abstract val adb: RegularFileProperty

    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:Input
    abstract val deviceDir: Property<String>

    /** One `path|url|bytes|sha256` per file. */
    @get:Input
    abstract val files: ListProperty<String>

    @TaskAction
    fun run() {
        val adbPath = adb.get().asFile.absolutePath
        for (entry in files.get()) {
            val (path, url, bytes, sha256) = entry.split("|")
            val remote = "${deviceDir.get()}/$path"
            val size = ByteArrayOutputStream()
            exec.exec {
                commandLine(adbPath, "shell", "stat", "-c", "%s", remote, "2>/dev/null", "||", "echo", "-1")
                standardOutput = size
            }
            if (size.toString().trim() == bytes) continue

            val local = cacheDir.get().asFile.resolve(path)
            if (!local.isFile || local.length() != bytes.toLong() || sha256Of(local) != sha256) {
                logger.lifecycle("Downloading $url")
                local.parentFile.mkdirs()
                val part = File(local.path + ".part")
                URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it, 1 shl 20) } }
                check(part.length() == bytes.toLong() && sha256Of(part) == sha256) { "$url does not match its pinned size and SHA-256" }
                part.renameTo(local)
            }
            logger.lifecycle("Pushing $path to $remote")
            exec.exec { commandLine(adbPath, "shell", "mkdir", "-p", deviceDir.get()) }
            exec.exec { commandLine(adbPath, "push", local.absolutePath, remote) }
        }
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

val whisperModelToDevice = tasks.register<WhisperModelToDeviceTask>("whisperModelToDevice") {
    description = "Puts the pinned Whisper model in $deviceModelFolder on the connected device."
    adb.set(androidComponents.sdkComponents.adb)
    cacheDir.set(File(gradle.gradleUserHomeDir, "caches/fonia-whisper/openai_whisper-base"))
    deviceDir.set(deviceModelFolder)
    files.set(
        listOf(
            "ggml-base.bin|https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.bin" +
                "|147951465|60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
            "generation_config.json|https://huggingface.co/argmaxinc/whisperkit-coreml/resolve/0f63a7800b00dd0226abd051b906c246e1907482/openai_whisper-base/generation_config.json" +
                "|2762|662a99e3db3067708549d04d5f141910eb455f0935aed0c9b06d707f44bfbcaf",
            "tokenizer.json|https://huggingface.co/openai/whisper-base/resolve/e37978b90ca9030d5170a5c07aadb050351a65bb/tokenizer.json" +
                "|2480466|27fc476bfe7f17299480be2273fc0608e4d5a99aba2ab5dec5374b4482d1a566",
            "tokenizer_config.json|https://huggingface.co/openai/whisper-base/resolve/e37978b90ca9030d5170a5c07aadb050351a65bb/tokenizer_config.json" +
                "|282683|2a4c4281cf9f51ac6ccc406fdc711a087afe6530f671fa7b80953edc498275ce",
        ),
    )
}

tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach { dependsOn(whisperModelToDevice) }
