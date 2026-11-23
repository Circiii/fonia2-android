import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Debug-only viewer for :core:mouth3d (packages H6–H8). Not shipped; the app shows Mouth3D itself.
// adb shell am start -n tech.fonia.mouth3d.demo/.DemoActivity --es sound sh --ez inside true --ez spin true

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "tech.fonia.mouth3d.demo"
    compileSdk = 37
    defaultConfig {
        applicationId = "tech.fonia.mouth3d.demo"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-debug"
        // No native code here; the filter keeps any transitive .so to the two ABIs we test on.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:mouth3d"))
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
}
