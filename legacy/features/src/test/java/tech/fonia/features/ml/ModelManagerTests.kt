package tech.fonia.features.ml

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tech.fonia.phones.ZipaModel
import tech.fonia.speech.LocalFileFetcher
import tech.fonia.speech.SpeechModelFile
import tech.fonia.speech.SpeechModelStoreError
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest

/** A model served from local files, as the Whisper store's tests do: no network. */
internal object LocalModels {
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A one-file model whose bytes sit in [served]; [hash] can lie, to test the check. */
    fun entry(served: File, id: String = "tiny", version: String = "1", content: ByteArray = "weights".toByteArray(), hash: String? = null): ModelEntry {
        served.mkdirs()
        val file = File(served, "$id-$version.bin").also { it.writeBytes(content) }
        return ModelEntry(
            id = id,
            version = version,
            license = "test",
            mainFile = "model.bin",
            files = listOf(SpeechModelFile(url = file.toURI(), path = "model.bin", byteCount = content.size.toLong(), sha256 = hash ?: sha256(content))),
        )
    }

    fun manager(root: File, vararg entries: ModelEntry) = ModelManager(root, entries.toList()) { LocalFileFetcher }

    fun temporaryFolder(): File = Files.createTempDirectory("fonia-models").toFile()
}

class ModelManagerTests {
    @Test
    fun zipaIsTheModelPinnedInPhones() {
        val zipa = ModelCatalog.ZIPA
        val file = zipa.files.single()
        assertEquals(ZipaModel.INT8.url, file.url.toString())
        assertEquals(ZipaModel.INT8.sha256, file.sha256)
        assertEquals(ZipaModel.INT8.bytes, file.byteCount)
        assertEquals("model.int8.onnx", zipa.mainFile)
        assertEquals("CC-BY-4.0", zipa.license)
        // The size the settings show before anything is fetched: 70,677,672 bytes, rounded up.
        assertEquals(71L, zipa.megabytes)
        assertEquals("zipa-small-crctc-500k-int8@dfe87c976626", zipa.label)
        // Fonia's own server later: same file name, same hash, another host.
        val mirrored = ModelCatalog.zipa(URI("https://models.example/zipa/")).files.single()
        assertEquals("https://models.example/zipa/model.int8.onnx", mirrored.url.toString())
        assertEquals(file.sha256, mirrored.sha256)
        assertEquals(listOf(zipa), ModelCatalog.all)
    }

    @Test
    fun downloadsChecksAndDeletes() = runBlocking {
        val root = LocalModels.temporaryFolder()
        val entry = LocalModels.entry(File(root.parentFile, root.name + "-served"))
        val manager = LocalModels.manager(root, entry)
        assertFalse(manager.isReady(entry))
        assertNull(manager.mainFile(entry))

        val seen = mutableListOf<Double>()
        manager.download(entry) { seen += it }
        assertTrue(manager.isReady(entry))
        assertEquals(File(File(File(root, "tiny"), "1"), "model.bin"), manager.mainFile(entry))
        assertEquals("weights", manager.mainFile(entry)!!.readText())
        assertEquals(1.0, seen.last(), 0.0)
        assertEquals(7L, manager.bytesOnDisk(entry))

        manager.delete(entry)
        assertFalse(manager.isReady(entry))
        assertFalse(File(root, "tiny").exists())
        assertEquals(0L, manager.bytesOnDisk(entry))
    }

    @Test
    fun aFileThatDoesNotMatchItsHashNeverCounts() = runBlocking {
        val root = LocalModels.temporaryFolder()
        val entry = LocalModels.entry(File(root.parentFile, root.name + "-served"), hash = "0".repeat(64))
        val manager = LocalModels.manager(root, entry)
        try {
            manager.download(entry)
            fail("a wrong hash must not install")
        } catch (error: SpeechModelStoreError.IntegrityCheckFailed) {
            assertEquals("model.bin", error.file)
        }
        assertFalse(manager.isReady(entry))
        assertNull(manager.mainFile(entry))
    }

    @Test
    fun theSweepKeepsOnlyWhatTheCatalogNames() = runBlocking {
        val root = LocalModels.temporaryFolder()
        val served = File(root.parentFile, root.name + "-served")
        val current = LocalModels.entry(served, version = "2")
        val manager = LocalModels.manager(root, current)
        manager.download(current)
        // An older version, an unfinished download of it, and a model the catalog dropped.
        File(root, "tiny/1").mkdirs()
        File(root, "tiny/1/model.bin").writeText("old")
        File(root, "tiny/1.partial").mkdirs()
        File(root, "gone/7").mkdirs()
        File(root, "gone/7/model.bin").writeText("x")
        File(root, "stray.bin").writeText("x")

        val removed = manager.sweep().map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
        assertEquals(setOf("tiny/1", "tiny/1.partial", "gone", "stray.bin"), removed)
        assertTrue(manager.isReady(current))
        assertEquals(setOf("2"), File(root, "tiny").list()!!.toSet())
    }
}
