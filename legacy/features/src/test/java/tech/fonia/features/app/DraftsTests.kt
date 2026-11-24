package tech.fonia.features.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.domain.ContentSource
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.SoundID
import tech.fonia.features.IS_DEBUG_BUILD
import tech.fonia.features.grownups.PrivacyText
import tech.fonia.lessons.LessonContent
import tech.fonia.store.FoniaDatabase
import tech.fonia.store.FoniaRepository
import java.io.File

/** The drafts (Bac, the reading probes, "Cuvântul important", the diction training) stay out of a release build. */
class DraftsTests {
    private val content = FoniaContent.load(ContentSource.directory(File(System.getProperty("fonia.content") ?: "../content")))
    private val v2 = LessonContent.fromDirectory(File(System.getProperty("fonia.contentV2")))

    private fun app(arguments: LaunchArguments): AppModel {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return AppModel(
            content = content,
            repository = FoniaRepository(FoniaDatabase.inMemory(recordingsDirectory = java.nio.file.Files.createTempDirectory("fonia-drafts").toFile())),
            audio = AudioHub(voices = null, backend = NoAudioBackend(null), usesFixtures = true),
            speech = SpeechHub(model = null, scope = scope),
            scope = scope,
            arguments = arguments,
            lessonContent = v2,
            hasPicture = { true },
        )
    }

    @Test
    fun withoutExtrasTheBuildDecides() {
        assertEquals(IS_DEBUG_BUILD, LaunchArguments.none.drafts)
    }

    @Test
    fun hiddenDraftsHaveNoDictionTraining() {
        assertTrue(app(LaunchArguments(drafts = false)).drills(SoundID.S).isEmpty())
        assertTrue(app(LaunchArguments(drafts = true)).drills(SoundID.S).isNotEmpty())
    }

    @Test
    fun thePrivacyTextOnlyNamesTheReadingProbesWhenTheyShow() {
        val hidden = PrivacyText.sections(withReading = false).joinToString(" ") { it.title + " " + it.body }
        val shown = PrivacyText.sections(withReading = true).joinToString(" ") { it.title + " " + it.body }
        assertTrue("citire" !in hidden.lowercase())
        assertTrue("Probele de citire" in shown)
    }
}
