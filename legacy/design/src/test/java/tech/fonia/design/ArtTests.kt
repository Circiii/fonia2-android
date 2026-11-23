package tech.fonia.design

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The asset half of `FoniaFeaturesTests/ArtTests.swift`: every icon, scene shape, mascot pose, fixed
 * illustration and font the design system names is in the content the APK ships. (`everyLessonHasItsPicture`
 * needs the course from `:domain` and belongs with the screens.) Runs on the JVM against the `content/`
 * submodule, found through the `fonia.content` system property or next to this module.
 */
class ArtTests {
    private val content: File = File(System.getProperty("fonia.content") ?: "../content").absoluteFile
    private val catalog: String by lazy { File(content, "images/catalog.json").readText() }

    /** True when `images/catalog.json` names the image and its file is there. */
    private fun loads(name: String): Boolean {
        val entry = Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*\\{([^}]*)\\}").find(catalog) ?: return false
        val body = entry.groupValues[1]
        val folder = Regex("\"folder\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: return false
        val file = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: return false
        return File(content, "images/$folder/$file").isFile
    }

    @Test
    fun everyIconLoads() {
        for (icon in FoniaIcon.entries) assertTrue(icon.rawValue, loads(icon.rawValue))
    }

    @Test
    fun tabIconsLoad() {
        for (name in listOf("tab-home", "tab-progress") + FoniaIcon.tabs) assertTrue(name, loads(name))
    }

    @Test
    fun everySceneLoadsInEveryShape() {
        for (scene in SceneName.entries) {
            for (shape in listOf("", "-wide", "-tall")) {
                val name = "scene-${scene.rawValue}$shape"
                assertTrue(name, loads(name))
            }
        }
        for (field in Field.entries) {
            val name = "scene-${field.scene.rawValue}-page"
            assertTrue(name, loads(name))
        }
    }

    @Test
    fun sceneArtPicksTheShapeByAspect() {
        assertTrue(sceneImageName(SceneName.sounds, 2.9f) == "scene-sounds-wide")
        assertTrue(sceneImageName(SceneName.sounds, 2.5f) == "scene-sounds-wide")
        assertTrue(sceneImageName(SceneName.play, 1.89f) == "scene-play")
        assertTrue(sceneImageName(SceneName.play, 1.515f) == "scene-play")
        assertTrue(sceneImageName(SceneName.guide, 1.1f) == "scene-guide-tall")
    }

    @Test
    fun everyMascotPoseLoads() {
        for (pose in MascotPose.entries) assertTrue(pose.rawValue, loads("mascot-${pose.rawValue}"))
    }

    @Test
    fun everyParrotLayerLoads() {
        val layers = listOf(
            "leg-far", "tail", "leg-near", "body", "wing-shadow", "wing", "head",
            "eye-open", "eye-calm", "eye-smile", "mouth", "beak-low", "beak-up",
        )
        for (layer in layers) assertTrue(layer, loads("parrot-$layer"))
        assertTrue("icon-zzz", loads("icon-zzz"))
    }

    @Test
    fun fixedArtLoads() {
        for (name in listOf("art-mirror", "art-calendar", "candle", "flame", "choice-adult")) assertTrue(name, loads(name))
    }

    @Test
    fun fontsAreBundled() {
        val files = mapOf(
            UIWeight.regular to "Inter-Medium.ttf",
            UIWeight.medium to "Inter-SemiBold.ttf",
            UIWeight.semibold to "Inter-Bold.ttf",
            UIWeight.bold to "Inter-ExtraBold.ttf",
            UIWeight.black to "Inter-Black.ttf",
        )
        for ((weight, file) in files) {
            assertTrue(weight.rawValue, file.removeSuffix(".ttf") == weight.rawValue && File(content, "fonts/$file").isFile)
        }
        for (file in listOf("Andika-Regular.ttf", "Andika-SemiBold.ttf", "Andika-Bold.ttf")) {
            assertTrue(file, File(content, "fonts/$file").isFile)
        }
    }
}
