package tech.fonia.features

import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.domain.Catalog
import tech.fonia.domain.ContentSource
import tech.fonia.domain.FoniaContent
import java.io.File

/**
 * The screens' half of `ArtTests.swift`: every unit and lesson in the course has its picture in the content the APK
 * ships (the rest of the suite is in `:design`). Runs on the JVM against the `content/` submodule.
 */
class ArtTests {
    private val root = File(System.getProperty("fonia.content") ?: error("fonia.content is not set"))
    private val catalog: String by lazy { File(root, "images/catalog.json").readText() }

    /** True when `images/catalog.json` names the image and its file is there, as `FoniaAssets` finds it. */
    private fun loads(name: String): Boolean {
        val entry = Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*\\{([^}]*)\\}").find(catalog) ?: return false
        val body = entry.groupValues[1]
        val folder = Regex("\"folder\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: return false
        val file = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: return false
        return File(root, "images/$folder/$file").isFile
    }

    @Test
    fun everyLessonHasItsPicture() {
        val content = FoniaContent.load(ContentSource.directory(root))
        val catalog = Catalog(content)
        for (unit in catalog.allUnits(order = content.sounds.map { it.id })) {
            for (name in listOfNotNull(unit.art) + unit.lessons.map { it.art }) {
                assertTrue("${unit.id}: $name", loads(name))
            }
        }
        for (name in listOf("art-mirror", "art-calendar", "candle", "flame", "choice-adult")) {
            assertTrue(name, loads(name))
        }
    }
}
