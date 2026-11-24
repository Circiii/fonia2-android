package tech.fonia.features.pilot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.domain.ContentSource
import tech.fonia.domain.FoniaContent
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.AudioHub
import tech.fonia.features.app.NoAudioBackend
import tech.fonia.features.app.SpeechHub
import tech.fonia.store.FoniaDatabase
import tech.fonia.store.FoniaRepository
import java.io.File
import java.nio.file.Files

/** The pilot log is off until an adult switches it on (iOS 007bfcf), and "Șterge tot" switches it off again. */
class PilotSwitchTests {
    private val content = FoniaContent.load(ContentSource.directory(File(System.getProperty("fonia.content") ?: "../content")))

    private fun app(opened: () -> PilotLog?): AppModel {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val root = Files.createTempDirectory("fonia-pilot-switch").toFile()
        return AppModel(
            content = content,
            repository = FoniaRepository(FoniaDatabase.inMemory(recordingsDirectory = File(root, "Recordings"))),
            audio = AudioHub(voices = null, backend = NoAudioBackend(null), usesFixtures = true),
            speech = SpeechHub(model = null, scope = scope),
            scope = scope,
            openPilot = opened,
        )
    }

    private suspend fun AppModel.waitForPilot(): PilotLog = withTimeout(5_000) {
        while (pilot == null) delay(10)
        pilot!!
    }

    @Test
    fun aFamilyThatNeverOpensTheSettingsKeepsNoLog() {
        assertFalse(PilotSwitch.DEFAULT)
        var opens = 0
        val app = app { opens++; PilotLog.inMemory() }
        assertFalse(app.isPilotLogOn)
        assertNull(app.pilot)
        assertEquals(0, opens)
    }

    @Test
    fun switchingOnOpensTheLogAndSwitchingOffStopsIt() = runBlocking {
        val app = app { PilotLog.inMemory() }
        app.setPilotLog(true)
        assertTrue(app.isPilotLogOn)
        val log = app.waitForPilot()
        assertNotNull(log)
        app.setPilotLog(false)
        assertFalse(app.isPilotLogOn)
        assertNull(app.pilot)
    }

    @Test
    fun theWipeSwitchesTheLogOffAgain() = runBlocking {
        val app = app { PilotLog.inMemory() }
        app.setPilotLog(true)
        app.waitForPilot()
        app.wipeEverything()
        assertFalse(app.isPilotLogOn)
        assertNull(app.pilot)
    }
}
