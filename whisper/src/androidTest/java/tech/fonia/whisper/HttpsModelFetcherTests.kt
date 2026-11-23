package tech.fonia.whisper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.fonia.speech.SpeechModelStore
import java.io.File
import java.security.MessageDigest

/** Not iOS tests: the Android fetcher's own rules (unmetered networks only, Range resume). */
@RunWith(AndroidJUnit4::class)
class HttpsModelFetcherTests {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tokenizerConfig = WhisperCppModel.WHISPER_BASE_FROM_HUGGING_FACE.files.first { it.path == "tokenizer_config.json" }

    @Test
    fun refusesWithoutAnAllowedNetwork() {
        val folder = SpeechPaths.temporaryFolder()
        try {
            val fetcher = HttpsModelFetcher(File(folder, ".fetch"), NetworkPicker { null })
            try {
                runBlocking { fetcher.fetch(tokenizerConfig.url, allowsCellular = false) {} }
                fail("fetched without an allowed network")
            } catch (e: NoAllowedNetworkException) {
                assertFalse(e.allowsCellular)
            }
            // And the store reports it as a failed download, leaving nothing installed.
            val store = SpeechModelStore(WhisperCppModel.WHISPER_BASE_FROM_HUGGING_FACE, folder, fetcher)
            try {
                runBlocking { store.download() }
                fail("downloaded without an allowed network")
            } catch (e: NoAllowedNetworkException) {
                assertFalse(store.isReady)
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun picksAnUnmeteredNetworkOnTheEmulator() {
        // The emulator's network is Wi-Fi; a phone on mobile data alone would get null here.
        assumeTrue(SpeechPaths.runsDownload)
        assertTrue(ConnectivityNetworkPicker(context).pick(allowsCellular = false) != null)
    }

    @Test(timeout = 5L * 60L * 1000L)
    fun resumesFromWhereTheLastAttemptStopped() {
        assumeTrue("network test: pass foniaSpeechDownloadTest=1", SpeechPaths.runsDownload)
        val model = SpeechPaths.modelFolder ?: return assumeTrue("needs the cached model for the first half", false)
        val whole = File(model, "tokenizer_config.json").readBytes()
        val folder = SpeechPaths.temporaryFolder()
        try {
            val partialFolder = File(folder, ".fetch")
            val fetcher = HttpsModelFetcher(context, partialFolder)
            // What an interrupted attempt leaves behind: the first 100 000 bytes.
            partialFolder.mkdirs()
            HttpsModelFetcher.partialFile(partialFolder, tokenizerConfig.url).writeBytes(whole.copyOf(100_000))
            val written = mutableListOf<Long>()
            val fetched = runBlocking { fetcher.fetch(tokenizerConfig.url, allowsCellular = false) { written.add(it) } }
            assertEquals(200, fetched.status)
            assertEquals(100_000L, written.first())
            assertEquals(tokenizerConfig.byteCount, written.last())
            val bytes = fetched.file.readBytes()
            assertEquals(tokenizerConfig.sha256, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            assertFalse("the unfinished part is handed over, not left behind", partialFolder.exists())
            fetched.file.delete()
        } finally {
            folder.deleteRecursively()
        }
    }
}
