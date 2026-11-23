package tech.fonia.collector.core

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The export archive: every file of one speaker folder plus `MANIFEST.sha256` at the root, in `sha256sum` format
 * (`<hex>  <path>`), so `sha256sum -c MANIFEST.sha256` checks it after unzipping. WAVs are stored, not deflated:
 * they barely compress and the phone writes the archive twice as fast.
 */
object Export {
    const val MANIFEST = "MANIFEST.sha256"

    class Result(val zip: File, val manifest: String, val fileCount: Int, val zipSha256: String)

    private class Digest(val sha256: String, val crc: Long, val size: Long)

    fun sha256(file: File): String = digest(file).sha256

    private fun digest(file: File): Digest {
        val sha = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        var size = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                sha.update(buffer, 0, n)
                crc.update(buffer, 0, n)
                size += n
            }
        }
        return Digest(sha.digest().toHex(), crc.value, size)
    }

    fun manifestText(entries: List<Pair<String, String>>): String =
        entries.sortedBy { it.first }.joinToString("") { (path, hex) -> "$hex  $path\n" }

    /** path → hex. */
    fun parseManifest(text: String): Map<String, String> = text.lineSequence().filter { it.isNotBlank() }.associate { line ->
        val hex = line.substringBefore("  ")
        val path = line.substringAfter("  ")
        path to hex
    }

    /** Files under [root]/[relativeDir], as paths relative to [root] with forward slashes, sorted. */
    fun listFiles(root: File, relativeDir: String): List<String> {
        val dir = File(root, relativeDir)
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted().toList()
    }

    /** Writes [out] with every file of [root]/[relativeDir] and the manifest, and a `<zip>.sha256` beside it. */
    fun writeZip(root: File, relativeDir: String, out: File): Result {
        val paths = listFiles(root, relativeDir)
        require(paths.isNotEmpty()) { "Nothing to export in $relativeDir" }
        out.parentFile?.mkdirs()
        val partial = File(out.path + ".part")
        val hashes = mutableListOf<Pair<String, String>>()
        ZipOutputStream(FileOutputStream(partial)).use { zip ->
            for (path in paths) {
                val file = File(root, path)
                val d = digest(file)
                hashes += path to d.sha256
                val entry = ZipEntry(path)
                entry.time = file.lastModified()
                if (path.endsWith(".wav")) {
                    entry.method = ZipEntry.STORED
                    entry.size = d.size
                    entry.compressedSize = d.size
                    entry.crc = d.crc
                } else {
                    entry.method = ZipEntry.DEFLATED
                }
                zip.putNextEntry(entry)
                file.inputStream().use { it.copyTo(zip, 1 shl 16) }
                zip.closeEntry()
            }
            val manifest = manifestText(hashes)
            zip.putNextEntry(ZipEntry(MANIFEST).apply { method = ZipEntry.DEFLATED })
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        if (out.exists()) out.delete()
        check(partial.renameTo(out)) { "Could not rename ${partial.name}" }
        val zipSha = sha256(out)
        File(out.path + ".sha256").writeText("$zipSha  ${out.name}\n")
        return Result(out, manifestText(hashes), paths.size, zipSha)
    }

    /** Re-reads an archive and checks every entry against its manifest. Returns the problems, empty when sound. */
    fun verifyZip(zipFile: File): List<String> {
        val problems = mutableListOf<String>()
        ZipFile(zipFile).use { zip ->
            val manifestEntry = zip.getEntry(MANIFEST) ?: return listOf("no $MANIFEST")
            val expected = parseManifest(zip.getInputStream(manifestEntry).readBytes().toString(Charsets.UTF_8))
            val seen = mutableSetOf<String>()
            for (entry in zip.entries()) {
                if (entry.name == MANIFEST || entry.isDirectory) continue
                seen += entry.name
                val sha = MessageDigest.getInstance("SHA-256")
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        sha.update(buffer, 0, n)
                    }
                }
                val hex = sha.digest().toHex()
                if (expected[entry.name] != hex) problems += "${entry.name}: hash differs"
            }
            for (missing in expected.keys - seen) problems += "$missing: missing"
        }
        return problems
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
