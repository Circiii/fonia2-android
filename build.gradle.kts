import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.spotless)
}

// ktlint on the files changed since origin/main only: the code is not reformatted in bulk, and every file a
// change touches comes out formatted (`./gradlew spotlessApply`). The rules are set here because Spotless does
// not apply the ktlint_* switches of .editorconfig.
val ktlintRules = mapOf(
    "ktlint_code_style" to "android_studio",
    // Composables are named like types; tests may read as sentences.
    "ktlint_function_naming_ignore_when_annotated_with" to "Composable, Test",
    // Half the files have lines past 140 and ktlint cannot wrap them, so a touched file would always fail.
    "ktlint_standard_max-line-length" to "disabled",
    // Rules that fight the layout the code already has, as in Now in Android.
    "ktlint_standard_function-signature" to "disabled",
    "ktlint_standard_chain-method-continuation" to "disabled",
    "ktlint_standard_condition-wrapping" to "disabled",
    "ktlint_standard_class-signature" to "disabled",
    "ktlint_standard_function-expression-body" to "disabled",
    "ktlint_standard_multiline-expression-wrapping" to "disabled",
    "ktlint_standard_string-template-indent" to "disabled",
    "ktlint_standard_backing-property-naming" to "disabled",
    "ktlint_standard_property-naming" to "disabled",
)

spotless {
    ratchetFrom("origin/main")
    kotlin {
        target("**/src/**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintRules)
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintRules)
    }
}

// Every module's JVM tests in one task, as CI runs them: `test` on Kotlin modules, the debug unit tests on
// Android ones.
val unitTests = tasks.register("unitTests") {
    group = "verification"
    description = "Runs the JVM tests of every module."
}

subprojects {
    val module = path
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        unitTests.configure { dependsOn("$module:test") }
    }
    pluginManager.withPlugin("com.android.library") {
        unitTests.configure { dependsOn("$module:testDebugUnitTest") }
        // Android Lint reports only what is not already in the module's lint-baseline.xml.
        extensions.configure<LibraryExtension> { lint { baseline = file("lint-baseline.xml") } }
    }
    pluginManager.withPlugin("com.android.application") {
        unitTests.configure { dependsOn("$module:testDebugUnitTest") }
        extensions.configure<ApplicationExtension> { lint { baseline = file("lint-baseline.xml") } }
    }
}
