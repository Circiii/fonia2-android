import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // The sound judge (package tech.fonia.judge): shared names, the DSP detectors, and the live band's Clip.
    api(project(":core:model"))
    implementation(project(":engine:dsp"))
    implementation(project(":engine:live"))
    implementation(libs.kotlinx.serialization.json)
    // The opt-in evaluation's full head: ZIPA through :engine:phones on the desktop ONNX Runtime (test only; the
    // dependency points the other way in main code, :engine:phones -> :engine:judge).
    testImplementation(project(":engine:phones"))
    testImplementation(libs.onnxruntime.jvm)
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// ONNX Runtime 1.31 crashes on the old msvcp140.dll of Android Studio's JBR (see phones/build.gradle.kts): only when the
// opt-in evaluation runs ZIPA, the tests run on a copy of the JDK with the system's VC++ runtime, made as there.
val zipaJava: File? = run {
    val wanted = providers.gradleProperty("fonia.judge.zipa").orNull == "true"
    val system32 = File(System.getenv("SystemRoot") ?: "C:/Windows", "System32")
    if (!wanted || !System.getProperty("os.name").startsWith("Windows") || !File(system32, "msvcp140.dll").isFile) return@run null
    val vcRuntime = setOf("msvcp140.dll", "vcruntime140.dll", "vcruntime140_1.dll")
    val home = File(System.getProperty("java.home"))
    val copy = layout.buildDirectory.dir("test-jdk").get().asFile
    val java = File(copy, "bin/java.exe")
    if (!java.isFile) {
        copy.deleteRecursively()
        home.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(home)
            val replaced = relative.parent == "bin" && relative.name.lowercase() in vcRuntime
            if (!replaced) file.copyTo(File(copy, relative.path), overwrite = true)
        }
        for (name in vcRuntime) File(system32, name).takeIf { it.isFile }?.copyTo(File(copy, "bin/$name"), overwrite = true)
    }
    java
}

tasks.test {
    useJUnitPlatform()
    // Tests read the shared content (JSON, clips) straight from the submodule.
    systemProperty("fonia.content", providers.gradleProperty("fonia.content").orNull ?: rootDir.resolve("content").absolutePath)
    // The checked-in calibration file, the counterpart of iOS tools/eval_speech.
    systemProperty("fonia.speech.eval", projectDir.resolve("eval_speech").absolutePath)
    // The judge's data files (spec/thresholds, spec/fusion, spec/unlock.json, spec/feedback.json).
    systemProperty("fonia.spec", rootDir.resolve("spec").absolutePath)
    // The tests read spec/ at run time: a changed coefficient file must rerun them.
    inputs.dir(rootDir.resolve("spec/fusion")).withPropertyName("specFusion").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootDir.resolve("spec/thresholds")).withPropertyName("specThresholds").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("spec/unlock.json")).withPropertyName("specUnlock").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("spec/feedback.json")).withPropertyName("specFeedback").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("fonia.judge.build", layout.buildDirectory.get().asFile.absolutePath)
    // Opt-in evaluation on local audio (never committed): -Pfonia.judge.audio=<dir of WAVs>, -Pfonia.judge.voice=<content/voice>.
    providers.gradleProperty("fonia.judge.audio").orNull?.let { systemProperty("fonia.judge.audio", it) }
    providers.gradleProperty("fonia.judge.voice").orNull?.let { systemProperty("fonia.judge.voice", it) }
    // The opt-in evaluation reads audio Gradle does not track: never skip it as up to date.
    if (providers.gradleProperty("fonia.judge.audio").isPresent || providers.gradleProperty("fonia.judge.voice").isPresent) {
        outputs.upToDateWhen { false }
    }
    // -Pfonia.judge.zipa=true adds the full head (ZIPA from the Gradle cache, as :engine:phones' tests find it).
    providers.gradleProperty("fonia.judge.zipa").orNull?.let { systemProperty("fonia.judge.zipa", it) }
    systemProperty("fonia.models", gradle.gradleUserHomeDir.resolve("fonia-models").absolutePath)
    if (zipaJava != null) executable = zipaJava.absolutePath
    maxHeapSize = "1536m"
}
