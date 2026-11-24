package tech.fonia.store

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The values the PDF report is drawn from: the export's pseudonymous copy. */
class PracticeDataTests {
    @Test
    @DisplayName("the report's data has no name, the name is replaced in notes, and notes come newest first")
    fun pseudonymous() = runTest {
        TestStore().use { store ->
            val ana = store.addChild(name = "Ana")
            val session = store.addSession(ana)
            store.repository.addAttempt(
                Attempt(
                    sessionId = session.id,
                    profileId = ana.id,
                    ts = bucharest(2026, 11, 4, 10, 5),
                    exerciseKind = "repeat",
                    sound = "s",
                    target = "Ana are sare",
                    machineOutcome = Attempt.Outcome.Good,
                    timeZone = Bucharest,
                ),
            )
            store.repository.saveNote(MoodlessNote(profileId = ana.id, localDay = "2026-11-03", text = "Ana a spus S bine."))
            store.repository.saveNote(MoodlessNote(profileId = ana.id, localDay = "2026-11-05", text = "Azi a obosit repede."))

            val data = store.repository.practiceData(ana.id)

            assertNull(data.profile.name)
            assertEquals(ExportDocument.ANONYMOUS_PROFILE_ID, data.profile.id)
            assertEquals(listOf("2026-11-05", "2026-11-03"), data.notes.map { it.localDay })
            assertEquals("${NameRedactor.PLACEHOLDER} a spus S bine.", data.notes.last().text)
            assertEquals("${NameRedactor.PLACEHOLDER} are sare", data.attempts.first().target)
            assertEquals(1, data.sessions.size)
            assertTrue(data.attempts.all { it.profileId == ExportDocument.ANONYMOUS_PROFILE_ID })
        }
    }

    @Test
    @DisplayName("an unknown profile is an error")
    fun unknownProfile() = runTest {
        TestStore().use { store ->
            assertFailsWith<FoniaStoreError.ProfileNotFound> { store.repository.practiceData(UUID.randomUUID()) }
        }
    }
}
