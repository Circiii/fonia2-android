package tech.fonia.whisper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.fonia.speech.SpeechLanguage
import tech.fonia.speech.SpeechModelFile
import tech.fonia.speech.SpeechModelManifest
import tech.fonia.speech.SpeechText
import java.io.File
import java.security.MessageDigest

/**
 * The three tests of FoniaSpeechTests/SpeechModelStoreTests.swift that need the platform (speech/PARITY.md):
 * where the model lives, that backups skip it, and a real download. The other nine run on the JVM in :speech.
 */
@RunWith(AndroidJUnit4::class)
class SpeechModelStoreDeviceTests {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** A tiny model served from local files, so these tests need no network. */
    private fun makeSource(contents: Map<String, String>, folder: File): SpeechModelManifest {
        val served = File(folder, "served")
        val files = contents.entries.sortedBy { it.key }.map { (path, text) ->
            val file = File(served, path)
            file.parentFile!!.mkdirs()
            val data = text.toByteArray(Charsets.UTF_8)
            file.writeBytes(data)
            SpeechModelFile(url = file.toURI(), path = path, byteCount = data.size.toLong(), sha256 = sha256(data))
        }
        return SpeechModelManifest(name = "tiny", files = files)
    }

    private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private val contents = mapOf("Encoder.mlmodelc/weights/weight.bin" to "weights", "tokenizer.json" to "{}")

    @Test
    fun excludedFromBackup() {
        // "excludes its files from iCloud backup": on Android the store lives in noBackupFilesDir,
        // which Auto Backup and device-to-device transfer never copy.
        val folder = SpeechPaths.temporaryFolder()
        val store = FoniaWhisper.modelStore(context, manifest = makeSource(contents, folder))
        try {
            runBlocking { store.download() }
            assertTrue(store.isReady)
            val noBackup = context.noBackupFilesDir.canonicalPath + File.separator
            assertTrue(store.modelFolder.canonicalPath, store.modelFolder.canonicalPath.startsWith(noBackup))
            assertTrue(store.modelFolder.parentFile!!.canonicalPath.startsWith(noBackup))
        } finally {
            store.delete()
            folder.deleteRecursively()
        }
    }

    @Test
    fun defaultLocation() {
        // "defaults to Application Support"
        val store = FoniaWhisper.modelStore(context)
        assertTrue(store.modelFolder.path, store.modelFolder.path.endsWith("no_backup/FoniaSpeech/openai_whisper-base"))
    }

    @Test(timeout = 15L * 60L * 1000L)
    fun realDownload() {
        // "fetches the real model and verifies it": off by default, as on iOS.
        assumeTrue("pass -Pandroid.testInstrumentationRunnerArguments.foniaSpeechDownloadTest=1 to fetch 151 MB", SpeechPaths.runsDownload)
        val folder = SpeechPaths.temporaryFolder()
        try {
            val store = FoniaWhisper.modelStore(context, root = folder)
            val seen = mutableListOf<Double>()
            runBlocking { store.download { update -> synchronized(seen) { seen.add(update.fraction) } } }
            assertTrue(store.isReady)
            val fractions = synchronized(seen) { seen.toList() }
            assertEquals(1.0, fractions.last(), 0.0)
            // Mid-file updates arrive, so the bar moves while the 148 MB weights come down.
            assertTrue("${fractions.size} updates", fractions.size > 50)
            val engine = store.makeEngine()
            val heard = runBlocking { engine.transcribe(fixtureSamples("en-work"), language = SpeechLanguage.EN) }
            assertEquals(listOf("work"), SpeechText.words(heard.text))
            runBlocking { engine.unload() }
        } finally {
            folder.deleteRecursively()
        }
    }
}
