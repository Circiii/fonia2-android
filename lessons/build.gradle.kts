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
    api(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Tests read the v2 content and the golden vectors straight from spec/content.
    // The content, vectors and voice index are inputs: editing them re-runs the tests.
    inputs.dir(rootDir.resolve("spec/content")).withPropertyName("content").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("fonia.contentV2", rootDir.resolve("spec/content/v2").absolutePath)
    systemProperty("fonia.vectors", rootDir.resolve("spec/content/vectors").absolutePath)
    systemProperty("fonia.voice", rootDir.resolve("spec/content/voice").absolutePath)
    // -Pfonia.writeVectors=true rewrites spec/content/vectors/plans-v2.json from the current builder.
    systemProperty("fonia.writeVectors", (project.findProperty("fonia.writeVectors") ?: "false").toString())
    testLogging {
        events("failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
