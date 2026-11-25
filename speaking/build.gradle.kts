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
    // Public speaking (G11/G12, package tech.fonia.speaking): filler detection, CleanCut, the prosodic transcript.
    api(project(":core:model"))
    api(project(":engine:live")) // Clip
    api(project(":engine:judge")) // Transcriber, Transcript, TranscriptWord, SpeechLanguage (package tech.fonia.speech)
    api(project(":engine:face")) // FaceMeshFrame for the speech coach (tech.fonia.speaking.coach)
    implementation(project(":engine:dsp")) // analyzeClip (pitch track, syllable nuclei), analyzeEmphasis, WAV
    implementation(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The thresholds and the Romanian filler lexicon are data in spec/ (one source), shipped inside the jar.
tasks.processResources {
    from(rootProject.layout.projectDirectory.file("spec/thresholds/speaking.json")) { into("spec/thresholds") }
    // The speech coach: its thresholds and its prompt cards.
    from(rootProject.layout.projectDirectory.file("spec/thresholds/speech-coach.json")) { into("spec/thresholds") }
    from(rootProject.layout.projectDirectory.file("spec/speech/prompts.json")) { into("spec/speech") }
}

tasks.test {
    useJUnitPlatform()
    // The real-audio evaluation is opt-in by presence: it runs only when these files exist (never committed).
    systemProperty("fonia.speaking.audio", providers.gradleProperty("fonia.speaking.audio").getOrElse("C:/Users/circi/Desktop/HSIC/tmp/test_audio"))
    systemProperty("fonia.speaking.out", providers.gradleProperty("fonia.speaking.out").getOrElse("C:/Users/circi/Desktop/HSIC/output/fara_aaa"))
    systemProperty("fonia.root", rootDir.absolutePath)
    // The coach's pace evaluation on Romanian clips (opt-in by presence, never committed).
    providers.gradleProperty("fonia.coach.sweep").orNull?.let { systemProperty("fonia.coach.sweep", it) }
    // The real-video bench (opt-in): -Pfonia.coach.bench=C:/…/coach_bench [-Pfonia.coach.bench.only=name]
    providers.gradleProperty("fonia.coach.bench").orNull?.let { systemProperty("fonia.coach.bench", it) }
    providers.gradleProperty("fonia.coach.bench.envelope").orNull?.let { systemProperty("fonia.coach.bench.envelope", it) }
    providers.gradleProperty("fonia.coach.bench.only").orNull?.let { systemProperty("fonia.coach.bench.only", it) }
    systemProperty("fonia.coach.clips", providers.gradleProperty("fonia.coach.clips").getOrElse("C:/Users/circi/Desktop/HSIC/tmp/coach_clips"))
    // The evaluation reads and writes files outside the build: never skip or restore it from the cache.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    testLogging {
        events("failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
