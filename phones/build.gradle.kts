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
    // ZipaPhoneScorer implements the judge's PhoneScorerPort (the judge's full head); the judge itself needs no model.
    api(project(":engine:judge"))
    implementation(project(":engine:live")) // Clip
    implementation(libs.kotlinx.serialization.json)
    // The ONNX Runtime Java API is the same on the desktop JVM and on Android: compile against the desktop jar,
    // run tests with it, and let the app bring `onnxruntime-android` at run time.
    compileOnly(libs.onnxruntime.jvm)
    testImplementation(libs.onnxruntime.jvm)
    testImplementation(project(":engine:dsp"))
    testImplementation(project(":core:domain"))
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// ONNX Runtime 1.31 on Windows needs the VC++ runtime 14.40 or newer. The JetBrains Runtime of Android Studio ships
// msvcp140.dll 14.29 next to java.exe; the JVM loads it at start-up, ONNX Runtime then binds to it and crashes in
// OrtEnvironment (access violation in msvcp140.dll). So on Windows the tests run on a copy of the JDK running Gradle
// whose bin/ holds the system's (newer, backward-compatible) VC++ runtime instead. The copy is made while configuring,
// because the test task checks that its executable exists then; its absence is a configuration input, so after a
// clean it is made again. Android is not affected: onnxruntime-android links its own C++ runtime.
val system32 = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32")
val testJavaExecutable: File? =
    if (System.getProperty("os.name").startsWith("Windows") && File(system32, "msvcp140.dll").isFile) {
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
    } else {
        null
    }

tasks.test {
    useJUnitPlatform()
    systemProperty("fonia.content", providers.gradleProperty("fonia.content").orNull ?: rootDir.resolve("content").absolutePath)
    // Models are downloaded once into the Gradle user cache, never committed. `-Pfonia.models.offline=true` stops the
    // download; the model tests then skip with the reason.
    systemProperty("fonia.models", gradle.gradleUserHomeDir.resolve("fonia-models").absolutePath)
    providers.gradleProperty("fonia.coach.bench").orNull?.let { systemProperty("fonia.coach.bench", it) }
    systemProperty("fonia.models.offline", providers.gradleProperty("fonia.models.offline").getOrElse("false"))
    if (testJavaExecutable != null) executable = testJavaExecutable.absolutePath
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
