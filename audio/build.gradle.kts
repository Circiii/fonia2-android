import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

val contentDir: String = rootDir.resolve("content").absolutePath
val fixturesDir: String = projectDir.resolve("src/androidTest/assets/fixtures").absolutePath

android {
    namespace = "tech.fonia.audio"
    compileSdk = 37
    // r28 aligns native libraries to 16 KB by default.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                // Oboe's prefab package is built against the shared C++ runtime.
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        prefab = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        // The ring and capture-engine parity tests run twice: on the JVM with the Kotlin ring, and on a device
        // with FoniaAudioRT's native ring.
        getByName("test").kotlin.directories.add("src/sharedTest/kotlin")
        getByName("androidTest").kotlin.directories.add("src/sharedTest/kotlin")
    }

    testOptions {
        unitTests {
            // Logging and other framework stubs return defaults on the JVM instead of throwing.
            isReturnDefaultValues = true
            all { test ->
                test.systemProperty("fonia.content", contentDir)
                test.systemProperty("fonia.audio.fixtures", fixturesDir)
            }
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":engine:live"))
    implementation(project(":engine:dsp"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.media3.exoplayer)
    implementation("com.google.oboe:oboe:1.11.0")
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
