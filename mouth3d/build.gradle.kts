import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

// :core:mouth3d (packages H6–H8, docs/v2/04 §7–9): the stylised 3D mouth, a procedural closed "bust slice" (lips,
// teeth, tongue, cheeks) with a stencil-capped cut-away. The maths (geometry, poses, springs, orbit, scene) is pure
// Kotlin and tested on the JVM; the GL ES 3 renderer and the Compose wrapper are the only Android parts. Colours come
// in as parameters: no dependency on the design system.

android {
    namespace = "tech.fonia.mouth3d"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
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

/** Bundles spec/poses/mouth3d/\*.json as assets under `mouth3d/poses/`. */
abstract class Mouth3DAssetsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val poses: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val dir = out.resolve("mouth3d")
        dir.resolve("poses").mkdirs()
        poses.files.filter { it.isFile && it.extension == "json" }.forEach { it.copyTo(dir.resolve("poses/${it.name}"), overwrite = true) }
    }
}

val mouth3dAssets = tasks.register<Mouth3DAssetsTask>("mouth3dAssets") {
    poses.from(rootProject.layout.projectDirectory.dir("spec/poses/mouth3d").asFileTree.matching { include("*.json") })
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(mouth3dAssets, Mouth3DAssetsTask::outputDir)
    }
}

// The JVM tests read the repo's spec/ directly. `-Pmouth3d.previewDir=<dir>` also writes offline renders of every pose.
val repoRoot: String = rootDir.absolutePath
val previewDir: String = providers.gradleProperty("mouth3d.previewDir").getOrElse("")
val previewOnly: String = providers.gradleProperty("mouth3d.only").getOrElse("")
val previewClip: String = providers.gradleProperty("mouth3d.clip").getOrElse("")
val previewMesh: String = providers.gradleProperty("mouth3d.mesh").getOrElse("")
android.testOptions.unitTests.all {
    it.systemProperty("fonia.root", repoRoot)
    it.systemProperty("mouth3d.previewDir", previewDir)
    it.systemProperty("mouth3d.only", previewOnly)
    it.systemProperty("mouth3d.clip", previewClip)
    it.systemProperty("mouth3d.mesh", previewMesh)
    it.inputs.property("previewOnly", previewOnly)
    it.inputs.property("previewClip", previewClip)
    it.inputs.property("previewMesh", previewMesh)
    it.inputs.dir(File(repoRoot, "spec/poses/mouth3d"))
    it.inputs.property("previewDir", previewDir)
}

dependencies {
    api(project(":core:model"))
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit4)
}
