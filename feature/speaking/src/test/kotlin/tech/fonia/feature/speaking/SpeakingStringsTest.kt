package tech.fonia.feature.speaking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Plan 01 §11: forbidden words, no red, comma-below Ș/Ț — over the strings and over the module's sources. */
class SpeakingStringsTest {
    private val forbidden = listOf(
        "diagnostic", "depistare", "screening", "evaluare", "terapie", "tratament", "tratează", "corectează", "clinic",
        "risc", "severitate", "tulburare", "scor", "notă", "procent", "greșit", "greșeală", "%",
    )

    private fun strings(): List<String> {
        val out = ArrayList<String>()
        for (f in SpeakingStrings::class.java.declaredFields) {
            f.isAccessible = true
            when (val v = f.get(SpeakingStrings)) {
                is String -> out.add(v)
                is List<*> -> v.filterIsInstance<String>().forEach { out.add(it) }
            }
        }
        out.add(SpeakingStrings.step(3, 7))
        return out
    }

    @Test
    fun noForbiddenWordsAndNoCedillas() {
        val all = strings()
        assertTrue(all.size > 30)
        for (s in all) {
            val low = s.lowercase()
            for (w in forbidden) assertFalse("'$w' in \"$s\"", Regex("(^|\\W)" + Regex.escape(w)).containsMatchIn(low))
            assertFalse("cedilla in \"$s\"", s.any { it in "şţŞŢ" })
        }
    }

    @Test
    fun sourcesUseOnlyThemeColours() {
        val dir = File("src/main/kotlin")
        if (!dir.exists()) return
        val raw = Regex("""Color\(0x|Color\(\s*red|#[0-9A-Fa-f]{6}|Color\.(Red|Green|Blue|Yellow|Magenta|Cyan|Gray|Black|White)|parseColor""")
        dir.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val text = f.readText()
            assertFalse("raw colour in ${f.name}", raw.containsMatchIn(text))
            assertFalse("cedilla in ${f.name}", text.any { it in "şţŞŢ" })
        }
    }
}
