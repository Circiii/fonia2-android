package tech.fonia.store

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("Mission follow-up")
class MissionCheckTests {
    private suspend fun given(store: TestStore, profile: Profile, sound: String = "s", at: Instant): MissionCheck {
        val session = store.addSession(profile, at = at)
        val mission = MissionCheck(profileId = profile.id, sessionId = session.id, sound = sound, givenAt = at, timeZone = Bucharest)
        store.repository.addMission(mission)
        return mission
    }

    private suspend fun count(store: TestStore): Int =
        store.database.writer.read { db -> db.fetchCount("SELECT COUNT(*) FROM missionCheck") }

    @Test
    @DisplayName("a mission from an earlier day waits for its question; one from today doesn't yet")
    fun pendingFromAnEarlierDay() = runTest {
        TestStore().use { store ->
            val profile = store.addChild()
            val mission = given(store, profile, at = bucharest(2026, 11, 23, 18))
            assertNull(store.repository.pendingMission(profile.id, today = "2026-11-23"))
            assertEquals(mission.id, store.repository.pendingMission(profile.id, today = "2026-11-24")?.id)
        }
    }

    @Test
    @DisplayName("the answer is kept with the mission's day and sound")
    fun answerIsStored() = runTest {
        TestStore().use { store ->
            val profile = store.addChild()
            val mission = given(store, profile, sound = "r", at = bucharest(2026, 11, 23, 18))
            store.repository.answerMission(mission.id, MissionCheck.Answer.Sometimes, at = bucharest(2026, 11, 24, 9))
            val answered = store.repository.missionAnswers(profile.id)
            assertEquals(1, answered.size)
            assertEquals("2026-11-23", answered.first().localDay)
            assertEquals("r", answered.first().sound)
            assertEquals(MissionCheck.Answer.Sometimes, answered.first().answer)
        }
    }

    @Test
    @DisplayName("the question comes once per mission, answered or skipped")
    fun askedOnce() = runTest {
        TestStore().use { store ->
            val profile = store.addChild()
            val first = given(store, profile, at = bucharest(2026, 11, 22, 18))
            store.repository.answerMission(first.id, MissionCheck.Answer.Yes, at = bucharest(2026, 11, 23, 9))
            assertNull(store.repository.pendingMission(profile.id, today = "2026-11-24"))

            val second = given(store, profile, at = bucharest(2026, 11, 23, 18))
            store.repository.answerMission(second.id, null, at = bucharest(2026, 11, 24, 9))
            assertNull(store.repository.pendingMission(profile.id, today = "2026-11-25"))
            // "Nu acum" keeps nothing to show.
            assertEquals(listOf(first.id), store.repository.missionAnswers(profile.id).map { it.id })
        }
    }

    @Test
    @DisplayName("only the latest mission is asked about; an older one left unasked is not brought back")
    fun onlyTheLatest() = runTest {
        TestStore().use { store ->
            val profile = store.addChild()
            given(store, profile, at = bucharest(2026, 11, 20, 18))
            val latest = given(store, profile, at = bucharest(2026, 11, 22, 18))
            store.repository.answerMission(latest.id, MissionCheck.Answer.NotYet, at = bucharest(2026, 11, 23, 9))
            assertNull(store.repository.pendingMission(profile.id, today = "2026-11-24"))
        }
    }

    @Test
    @DisplayName("one session gives one mission, however often it is noted")
    fun oneMissionPerSession() = runTest {
        TestStore().use { store ->
            val profile = store.addChild()
            val session = store.addSession(profile, at = bucharest(2026, 11, 23, 18))
            repeat(2) {
                store.repository.addMission(
                    MissionCheck(profileId = profile.id, sessionId = session.id, sound = "s", givenAt = bucharest(2026, 11, 23, 18), timeZone = Bucharest),
                )
            }
            assertEquals(1, count(store))
        }
    }

    @Test
    @DisplayName("a profile's missions go with the profile, and every mission goes with a wipe")
    fun deletion() = runTest {
        TestStore().use { store ->
            val ana = store.addChild(name = "Ana")
            val mihai = store.addChild(name = "Mihai")
            val anaMission = given(store, ana, at = bucharest(2026, 11, 23, 18))
            store.repository.answerMission(anaMission.id, MissionCheck.Answer.Yes, at = bucharest(2026, 11, 24, 9))
            given(store, mihai, at = bucharest(2026, 11, 23, 18))

            store.repository.deleteProfile(ana.id)
            assertTrue(store.repository.missionAnswers(ana.id).isEmpty())
            assertNotNull(store.repository.pendingMission(mihai.id, today = "2026-11-24"))

            store.repository.wipeAll()
            assertEquals(0, count(store))
        }
    }

    @Test
    @DisplayName("the report data carries the answered missions")
    fun practiceDataHasAnswers() = runTest {
        TestStore().use { store ->
            val profile = store.addChild()
            val mission = given(store, profile, at = bucharest(2026, 11, 23, 18))
            store.repository.answerMission(mission.id, MissionCheck.Answer.Yes, at = bucharest(2026, 11, 24, 9))
            val data = store.repository.practiceData(profile.id)
            assertEquals(listOf(MissionCheck.Answer.Yes), data.missionAnswers.map { it.answer })
        }
    }
}
