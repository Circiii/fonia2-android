import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

// Still images and MediaPipe reference outputs, shared by the JVM tests and the instrumented tests.
val facesDir: String = projectDir.resolve("src/androidTest/assets/faces").absolutePath
val modelsDir: String = projectDir.resolve("src/main/assets/models").absolutePath

android {
    namespace = "tech.fonia.facecamera"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    sourceSets {
        // Reading the fixtures and MediaPipe's reference outputs: the same code on the JVM and on a device.
        getByName("test").kotlin.directories.add("src/sharedTest/kotlin")
        getByName("androidTest").kotlin.directories.add("src/sharedTest/kotlin")
    }
    testOptions {
        unitTests.all { test ->
            test.systemProperty("fonia.facecamera.faces", facesDir)
            test.systemProperty("fonia.facecamera.models", modelsDir)
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":engine:face"))

    // LiteRT 2.3.2 (Apache-2.0), the TensorFlow Lite runtime, CPU with XNNPACK. Its classic Interpreter runs the
    // three Face Landmarker models; the MediaPipe Tasks library is not used. litert-api depends on Play's AI-pack
    // delivery (Google Play services, a download service): nothing here downloads models, so it stays out.
    implementation("com.google.ai.edge.litert:litert:2.3.2") {
        exclude(group = "com.google.android.play", module = "ai-delivery")
    }
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.compose.ui.test.manifest)
}
