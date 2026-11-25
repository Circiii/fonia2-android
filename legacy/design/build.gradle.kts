import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "tech.fonia.design"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions {
        unitTests {
            all { test ->
                // ArtTests read the shared content (images/catalog.json, the fonts) from the submodule at the root, like :legacy:features.
                test.systemProperty("fonia.content", rootDir.resolve("content").absolutePath)
            }
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.animation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidsvg)
    // Allo, the living letter and the motion kit (:core:designsystem) inside his controls and type.
    implementation(project(":core:designsystem"))
    debugImplementation(libs.compose.ui.tooling)
    // Only for the debug gallery's Activity (`setContent`); the app ships this version already.
    debugImplementation("androidx.activity:activity-compose:1.13.0")
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
