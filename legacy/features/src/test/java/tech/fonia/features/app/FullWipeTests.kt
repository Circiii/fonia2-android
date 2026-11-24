package tech.fonia.features.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.domain.ContentSource
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.SoundID
import tech.fonia.features.check.CheckRecord
import tech.fonia.features.check.FileCheckStore
import tech.fonia.features.diction.FileDrillStore
import tech.fonia.features.pilot.PilotLog
import tech.fonia.features.session.TakeDot
import tech.fonia.features.session.VoiceStore
import tech.fonia.store.FoniaDatabase
import tech.fonia.store.FoniaRepository
import tech.fonia.store.Profile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant

/** "Șterge tot" takes everything Android keeps, and says what stayed instead of hiding it (iOS 007bfcf). */
class FullWipeTests {
    private val content = FoniaContent.load(ContentSource.directory(File(System.getProperty("fonia.content") ?: "../content")))

    @Test
    fun everyPartIsTriedAndTheOnesThatFailedAreNamed() = runBlocking {
        val ran = ArrayList<String>()
        val left = FullWipe.run(
            listOf(
                FullWipe.Part("a") { ran += "a" },
                FullWipe.Part("b") { ran += "b"; throw IOException("locked") },
                FullWipe.Part("c") { ran += "c" },
            ),
        )
        assertEquals(listOf("a", "b", "c"), ran)
        assertEquals(listOf("b"), left)
        assertTrue(FullWipe.leftLine(left).contains("b"))
    }

    @Test
    fun aFolderGoesWithAllItHoldsAndAMissingOneIsAlreadyGone() {
        val root = Files.createTempDirectory("fonia-wipe").toFile()
        val folder = File(root, "Fonia/Voice").apply { mkdirs() }
        File(folder, "personal-1.json").writeText("{}")
        File(folder, "deep/more.txt").apply { parentFile.mkdirs() }.writeText("x")
        FullWipe.deleteFolder(folder)
        assertFalse(folder.exists())
        FullWipe.deleteFolder(File(root, "never-made"))
    }

    @Test
    fun theWipeEmptiesEveryStoreAndReportsSettingsThatCouldNotBeCleared() = runBlocking {
        val root = Files.createTempDirectory("fonia-wipe-app").toFile()
        val folders = listOf("Voice", "Check", "Diction", "Reading", "Adaptive").map { File(root, "Fonia/$it") }
        val export = File(root, "cache/export").apply { mkdirs() }
        File(export, "fonia-copil.json").writeText("{}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var settingsCleared = false
        val app = AppModel(
            content = content,
            repository = FoniaRepository(FoniaDatabase.inMemory(recordingsDirectory = File(root, "Recordings"))),
            audio = AudioHub(voices = null, backend = NoAudioBackend(null), usesFixtures = true),
            speech = SpeechHub(model = null, scope = scope),
            scope = scope,
            pilot = PilotLog.inMemory(),
            voice = VoiceStore(folders[0]),
            checks = FileCheckStore(folders[1]),
            drills = FileDrillStore(folders[2]),
            localData = LocalData(
                profileFolders = folders,
                exportFolders = listOf(export),
                pilotFolder = File(root, "FoniaPilot"),
                // A settings file that can't be written is said, not hidden.
                clearSettings = { settingsCleared = true; false },
            ),
        )
        val child = Profile(
            kind = Profile.Kind.Child, ageBand = Profile.AgeBand.SixToSeven, voiceGroup = Profile.VoiceGroup.Child,
            color = "cold", targetSounds = listOf("s"), track = Profile.Track.Ro,
        )
        app.repository.saveProfile(child)
        app.voice.addDot(child.id, TakeDot(atMillis = 1, sound = "s", outcome = "correct"))
        app.voice.savePersonal(child.id)
        app.checks.add(child.id, CheckRecord(Instant.EPOCH, emptyMap()))
        app.drills.record(child.id, SoundID.S, 0, 1.0)
        File(folders[3], "${child.id}.json").apply { parentFile.mkdirs() }.writeText("{}")
        app.audio.effectsMuted = true

        val left = app.wipeEverything()

        assertEquals(listOf("alegerile din Setări"), left)
        assertTrue(settingsCleared)
        assertEquals(null, app.repository.profile(child.id))
        for (folder in folders) assertFalse("$folder", folder.exists())
        assertFalse(export.exists())
        assertTrue(app.checks.history(child.id).isEmpty())
        assertTrue(app.drills.best(child.id, SoundID.S).isEmpty())
        assertTrue(app.voice.dots(child.id).isEmpty())
        assertFalse(app.audio.effectsMuted)
        app.pilot?.close()
        Unit
    }

    @Test
    fun aDeletedProfileTakesItsDrills() = runBlocking {
        val folder = Files.createTempDirectory("fonia-drills").toFile()
        val drills = FileDrillStore(folder)
        val kept = java.util.UUID.randomUUID()
        val gone = java.util.UUID.randomUUID()
        drills.record(kept, SoundID.S, 0, 1.0)
        drills.record(gone, SoundID.S, 1, 0.5)
        drills.keepOnly(setOf(kept))
        assertEquals(mapOf(0 to 1.0), drills.best(kept, SoundID.S))
        assertTrue(drills.best(gone, SoundID.S).isEmpty())
    }
}
