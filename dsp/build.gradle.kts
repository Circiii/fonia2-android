import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Tests read the shared content (JSON, clips) straight from the submodule.
    systemProperty("fonia.content", rootDir.resolve("content").absolutePath)
    // RealSpeechEvalTests caches decoded clips and writes its report here.
    systemProperty("fonia.dsp.build", layout.buildDirectory.get().asFile.absolutePath)
    // The real-speech evaluation holds every decoded clip in memory at once (about 120 MB).
    maxHeapSize = "1536m"
    testLogging {
        events("failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
