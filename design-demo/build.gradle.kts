import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Debug-only gallery of :core:designsystem (Foni, pictures, scenes, motion). Not shipped.
// adb shell am start -n tech.fonia.design.demo/.DemoActivity --es page foni|pictures|scenes|motion [--ez demo true]

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "tech.fonia.design.demo"
    compileSdk = 37
    defaultConfig {
        applicationId = "tech.fonia.design.demo"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-debug"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // The emulator is short of space: R8 even in debug keeps the gallery APK near the size of its art (~10 MB, not ~38).
    buildTypes {
        debug {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    packaging { resources.excludes += listOf("META-INF/**", "kotlin/**", "**.kotlin_builtins") }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
}
