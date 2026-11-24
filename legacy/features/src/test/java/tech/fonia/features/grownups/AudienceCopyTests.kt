package tech.fonia.features.grownups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An adult is never sent to "an adult" nor spoken of as "the child" (iOS 77d976c); the credits name what ships. */
class AudienceCopyTests {
    @Test
    fun adultsGetTheirOwnLines() {
        val adult = listOf(
            AudienceCopy.notesTitle(false), AudienceCopy.addNoteLabel(false), AudienceCopy.exportTitle(false),
            AudienceCopy.exportDetail(false), AudienceCopy.calibrationDetail(false),
        ).joinToString(" ").lowercase()
        for (word in listOf("logoped", "copil", "un adult")) assertFalse(word, word in adult)
        assertEquals("Notițe pentru logoped", AudienceCopy.notesTitle(true))
        assertTrue("cu un adult lângă" in AudienceCopy.calibrationDetail(true))
        assertTrue("numele copilului" in AudienceCopy.exportDetail(true))
    }

    @Test
    fun theCreditsNameTheLibrariesTheScanAndTheRealSoundSource() {
        val text = Credits.lines.joinToString(" ")
        for (name in listOf("MediaPipe", "LiteRT", "CameraX", "Oboe", "Media3", "Lee Perry-Smith", "CC BY 3.0", "MakeHuman", "CC0", "ElevenLabs")) {
            assertTrue(name, name in text)
        }
        assertFalse("UI SFX" in text)
    }
}
