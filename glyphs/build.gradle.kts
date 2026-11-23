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
    // The Allo export (spec/glyphs) and, for the optional export determinism check, the source font.
    systemProperty("fonia.glyphs", rootDir.resolve("spec/glyphs").absolutePath)
    systemProperty(
        "allo.font",
        providers.environmentVariable("ALLO_FONT")
            .getOrElse("C:/Users/circi/Desktop/HSIC/vendor_fonts/allo/fonts/variable/AlloVariable.ttf"),
    )
    inputs.dir(rootDir.resolve("spec/glyphs"))
    testLogging {
        events("failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
