package tech.fonia.speech

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * FoniaSpeechTests/SpeechModelStoreTests.swift, suite "Model store", without the parts that need
 * Android or the network: excludedFromBackup, defaultLocation and realDownload (speech/PARITY.md).
 */
@DisplayName("Model store")
class SpeechModelStoreTests {
    /** A tiny model served from local files, so these tests need no network. */
    private fun makeSource(contents: Map<String, String>, folder: File, corrupting: String? = null): SpeechModelManifest {
        val served = File(folder, "served")
        val files = mutableListOf<SpeechModelFile>()
        for ((path, text) in contents.entries.sortedWith { a, b -> SwiftString.compare(a.key, b.key) }) {
            val url = File(served, path)
            url.parentFile.mkdirs()
            val data = text.toByteArray(Charsets.UTF_8)
            url.writeBytes(data)
            val hash = sha256(if (path == corrupting) "something else".toByteArray(Charsets.UTF_8) else data)
            files.add(SpeechModelFile(url = url.toURI(), path = path, byteCount = data.size.toLong(), sha256 = hash))
        }
        return SpeechModelManifest(name = "tiny", files = files)
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private val contents = mapOf("Encoder.mlmodelc/weights/weight.bin" to "weights", "tokenizer.json" to "{}")

    /** Stands in for the platform runtime and fails the test if anything reaches it. */
    private object Unreachable : WhisperRuntimeLoader {
        override suspend fun parseTokenizer(modelFolder: File) {
            fail("the runtime was reached")
        }

        override suspend fun load(modelFolder: File): WhisperRuntime {
            fail("the runtime was reached")
        }

        override suspend fun prewarm(modelFolder: File) {
            fail("the runtime was reached")
        }
    }

    private fun <T> inFolder(body: (File) -> T): T {
        val folder = SpeechPaths.temporaryFolder()
        try {
            return body(folder)
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    @DisplayName("is not ready before a download and ready after one")
    fun downloads() = inFolder { folder ->
        val store = SpeechModelStore(manifest = makeSource(contents, folder), root = File(folder, "store"))
        assertFalse(store.isReady)
        runSuspend { store.download() }
        assertTrue(store.isReady)
        val weight = File(store.modelFolder, "Encoder.mlmodelc/weights/weight.bin")
        assertEquals("weights", weight.readText(Charsets.UTF_8))
    }

    @Test
    @DisplayName("reports progress up to the full size")
    fun progress() = inFolder { folder ->
        val manifest = makeSource(contents, folder)
        val store = SpeechModelStore(manifest = manifest, root = File(folder, "store"))
        val seen = mutableListOf<SpeechModelProgress>()
        runSuspend { store.download { update -> synchronized(seen) { seen.add(update) } } }
        val updates = synchronized(seen) { seen.toList() }
        assertEquals(0L, updates.firstOrNull()?.bytesDone)
        assertEquals(1.0, updates.lastOrNull()?.fraction)
        assertTrue(updates.all { it.bytesTotal == manifest.byteCount })
    }

    @Test
    @DisplayName("refuses a file whose hash does not match, and installs nothing")
    fun integrity() = inFolder { folder ->
        val manifest = makeSource(contents, folder, corrupting = "tokenizer.json")
        val store = SpeechModelStore(manifest = manifest, root = File(folder, "store"))
        val error = assertFailsWith<SpeechModelStoreError> { runSuspend { store.download() } }
        assertEquals(SpeechModelStoreError.IntegrityCheckFailed(file = "tokenizer.json"), error)
        assertFalse(store.isReady)
        assertFalse(store.modelFolder.exists())
    }

    @Test
    @DisplayName("keeps verified files from an interrupted download")
    fun resumes() = inFolder { folder ->
        var manifest = makeSource(contents, folder)
        val root = File(folder, "store")
        val store = SpeechModelStore(manifest = manifest, root = root)
        val last = manifest.files.size - 1
        val good = manifest.files[last]
        manifest = manifest.copy(files = manifest.files.mapIndexed { i, file -> if (i == last) file.copy(url = File(folder, "missing.bin").toURI()) else file })
        val failing = SpeechModelStore(manifest = manifest, root = root)
        assertFails { runSuspend { failing.download() } }
        // The first file is kept; remove its source to prove it is not fetched again.
        assertTrue(File(manifest.files[0].url).delete())
        manifest = manifest.copy(files = manifest.files.mapIndexed { i, file -> if (i == last) good else file })
        assertFalse(store.isReady)
        val retry = SpeechModelStore(manifest = manifest, root = root)
        runSuspend { retry.download() }
        assertTrue(retry.isReady)
    }

    @Test
    @DisplayName("deletes everything, and a second delete is harmless")
    fun deletes() = inFolder { folder ->
        val store = SpeechModelStore(manifest = makeSource(contents, folder), root = File(folder, "store"))
        runSuspend { store.download() }
        store.delete()
        assertFalse(store.isReady)
        assertFalse(store.modelFolder.exists())
        store.delete()
    }

    @Test
    @DisplayName("is not ready when a file is cut short")
    fun truncated() = inFolder { folder ->
        val store = SpeechModelStore(manifest = makeSource(contents, folder), root = File(folder, "store"))
        runSuspend { store.download() }
        File(store.modelFolder, "tokenizer.json").writeText("x")
        assertFalse(store.isReady)
    }

    @Test
    @DisplayName("refuses to prewarm or build an engine without the model")
    fun notInstalled() = inFolder { folder ->
        val store = SpeechModelStore(manifest = makeSource(contents, folder), root = File(folder, "store"), loader = Unreachable)
        assertEquals(SpeechEngineError.ModelNotInstalled, assertFailsWith<SpeechEngineError> { runSuspend { store.prewarm() } })
        assertEquals(SpeechEngineError.ModelNotInstalled, assertFailsWith<SpeechEngineError> { store.makeEngine() })
    }

    @Test
    @DisplayName("engine reports a missing model rather than reaching for the network")
    fun engineWithoutModel() = inFolder { folder ->
        val engine = WhisperEngine(modelFolder = folder, loader = Unreachable)
        assertEquals(SpeechEngineError.ModelNotInstalled, assertFailsWith<SpeechEngineError> { runSuspend { engine.load() } })
    }

    @Test
    @DisplayName("pins the Whisper base files, about 149 MB")
    fun manifest() {
        val manifest = SpeechModelManifest.WHISPER_BASE_FROM_HUGGING_FACE
        assertEquals(21, manifest.files.size)
        assertEquals(21, manifest.files.map { it.path }.toSet().size)
        assertTrue(manifest.files.all { it.sha256.length == 64 })
        assertTrue(manifest.files.any { it.path == "tokenizer.json" })
        assertEquals(149L, manifest.byteCount / 1_000_000)
    }
}
