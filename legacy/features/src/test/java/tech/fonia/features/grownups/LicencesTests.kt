package tech.fonia.features.grownups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The licences page names everything third-party the app carries, and every text it points to is in the APK. */
class LicencesTests {
    /** The asset folders merged into the app: this module's, the live letters' (`:design`) and the fonts' (`:legacy:design`). */
    private val assetRoots = listOf("src/main/assets", "../../design/src/main/assets", "../design/src/main/assets").map(::File)

    @Test
    fun everyFileIsInTheAssets() {
        for (licence in Licence.all) {
            assertTrue(licence.name, licence.files.isNotEmpty())
            for (path in licence.files) {
                val found = assetRoots.firstOrNull { File(it, path).isFile }
                assertTrue("${licence.name}: $path", found != null && File(found, path).length() > 200)
            }
        }
    }

    @Test
    fun theListCoversWhatShips() {
        val names = Licence.all.joinToString("\n") { it.name + " " + it.notice }
        for (piece in listOf(
            "Allo", "Baloo 2", "ZIPA", "CC BY 4.0", "Infinite", "CC BY 3.0", "MakeHuman", "CC0", "MediaPipe", "LiteRT",
            "CameraX", "Oboe", "Media3", "ONNX Runtime", "whisper.cpp", "Whisper",
        )) {
            assertTrue(piece, piece in names)
        }
        assertEquals(Licence.all.size, Licence.all.map { it.id }.toSet().size)
    }

    @Test
    fun theAttributionsSayWhatCcAsksFor() {
        val zipa = Licence.withID("zipa")!!
        assertTrue("Jian Zhu" in zipa.notice && "huggingface.co/anyspeech/zipa-small-crctc-500k" in zipa.notice && "fără schimbări" in zipa.notice)
        val scan = Licence.withID("head-scan")!!
        assertTrue("Lee Perry-Smith" in scan.notice && "a refăcut" in scan.notice)
    }

    @Test
    fun paragraphsSplitOnBlankLines() {
        assertEquals(listOf("A b", "C", "D"), Licence.paragraphs(listOf("A b\n\n  C  \r\n\r\n", "D")))
    }
}
