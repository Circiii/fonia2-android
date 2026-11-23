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

// The pose and camera thresholds (spec/poses, data versioned with the plan) ship as Java resources under poses/.
sourceSets {
    main {
        resources {
            srcDir(rootDir.resolve("spec"))
            include("poses/**")
        }
    }
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
    // Tests read the shared content (JSON, clips) straight from the submodule.
    systemProperty("fonia.content", rootDir.resolve("content").absolutePath)
    // MediaPipe's own landmarks of real photos (the camera module's fixtures), to check the mesh indices.
    systemProperty("fonia.facecamera.faces", rootDir.resolve("facecamera/src/androidTest/assets/faces").absolutePath)
}
