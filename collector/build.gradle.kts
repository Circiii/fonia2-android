import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "tech.fonia.collector"
    compileSdk = 37

    defaultConfig {
        applicationId = "tech.fonia.collector"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // Phones are arm64; x86_64 is the emulator. Only :platform:audio carries native code (Oboe).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        debug {
            // R8 on the debug build too: the APK goes to phones by cable or Nearby Share, and unshrunk Compose and
            // Media3 make it 36 MB. It stays debuggable and debug-signed.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all { test ->
                test.systemProperty("fonia.collector.spec", rootDir.resolve("spec/collector").absolutePath)
                test.systemProperty("fonia.collector.src", projectDir.resolve("src/main").absolutePath)
            }
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// The prompt list lives in spec/collector (data, versioned with the plan) and is bundled as an asset at build time.
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory("../spec/collector")
    }
}

dependencies {
    implementation(project(":platform:audio"))
    implementation(project(":engine:dsp"))
    implementation(project(":core:model"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
}
