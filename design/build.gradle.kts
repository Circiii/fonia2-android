import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "tech.fonia.designsystem"
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
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

/** Bundles the Allo outline export (C1) as an asset: only the hero file, not the golden vectors. */
abstract class GlyphAssetsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val hero: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.resolve("glyphs").mkdirs()
        hero.get().asFile.copyTo(out.resolve("glyphs/allo-hero.json"), overwrite = true)
    }
}

val glyphAssets = tasks.register<GlyphAssetsTask>("glyphAssets") {
    hero.set(rootProject.layout.projectDirectory.file("spec/glyphs/allo-hero.json"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(glyphAssets, GlyphAssetsTask::outputDir)
    }
}

// The JVM tests read the repo (spec/glyphs for the outlines, spec/tokens for the generated JSON, the sources for the
// raw-colour scan). `-Pfonia.writeTokens=true` rewrites spec/tokens/fonia.tokens.json from FoniaTokens.kt.
val repoRoot: String = rootDir.absolutePath
val writeTokens: String = providers.gradleProperty("fonia.writeTokens").getOrElse("false")
android.testOptions.unitTests.all {
    it.systemProperty("fonia.root", repoRoot)
    it.systemProperty("fonia.writeTokens", writeTokens)
    it.inputs.dir(File(repoRoot, "spec/tokens"))
    it.inputs.file(File(repoRoot, "spec/glyphs/allo-hero.json"))
}

dependencies {
    api(project(":core:glyphs"))
    api(project(":core:model"))
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.foundation)
    api(libs.compose.animation)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit4)
}
