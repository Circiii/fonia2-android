package tech.fonia.glyphs

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportTest {
    private val dir = TestFont.dir

    private fun sha256(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun `exported files match the manifest written by the export`() {
        val lines = File(dir, "MANIFEST.sha256").readLines().filter { it.isNotBlank() }
        assertTrue(lines.size >= 59)
        for (line in lines) {
            val (hash, path) = line.split("  ", limit = 2)
            assertEquals(hash, sha256(File(dir, path)), "$path was changed after the export; re-run export_allo.py")
        }
        val listed = lines.map { it.substringAfter("  ") }.toSet()
        val onDisk = File(dir, "golden").listFiles()!!.map { "golden/${it.name}" }.toSet()
        assertEquals(onDisk, onDisk.intersect(listed), "golden/ holds files the export did not write")
    }

    @Test
    fun `README states the font SHA-256`() {
        assertTrue(File(dir, "README.md").readText().contains(TestFont.outlines.fontSha256))
        assertTrue(File(dir, "OFL.txt").readText().contains("SIL Open Font License"))
    }

    /** Re-runs the export in memory and compares bytes; skipped when uv or the font is not on this machine. */
    @Test
    fun `export is deterministic`() {
        val font = File(System.getProperty("allo.font") ?: "")
        assumeTrue(font.isFile, "source font not found (set ALLO_FONT)")
        val pb = ProcessBuilder("uv", "run", File(dir, "export_allo.py").absolutePath, "--font", font.absolutePath, "--check")
            .redirectErrorStream(true)
        val proc = try { pb.start() } catch (e: Exception) { null }
        assumeTrue(proc != null, "uv not on PATH")
        val output = proc!!.inputStream.bufferedReader().readText()
        assertTrue(proc.waitFor(5, TimeUnit.MINUTES))
        assertEquals(0, proc.exitValue(), output)
        assertTrue(output.contains("identical"), output)
    }
}
