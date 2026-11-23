package tech.fonia.store

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

/** Reads the first value of a stream, which is the current state. */
private suspend fun <Value> current(stream: Flow<Value>): Value? = stream.firstOrNull()

@DisplayName("Agreement")
class AgreementTests {
    private suspend fun add(
        store: TestStore,
        session: SessionRecord,
        sound: String? = "s",
        machine: Attempt.Outcome,
        adult: Attempt.Outcome?,
    ) {
        store.repository.addAttempt(
            Attempt(
                sessionId = session.id,
                profileId = session.profileId,
                exerciseKind = "repeat",
                sound = sound,
                machineOutcome = machine,
                adultOutcome = adult,
            ),
        )
    }

    @Test
    @DisplayName("counts agreement only where both gave a verdict")
    fun comparesVerdicts() = runTest {
        TestStore().use { store ->
            val profile = store.addChild()
            val session = store.addSession(profile)

            add(store, session, machine = Attempt.Outcome.Good, adult = Attempt.Outcome.Good)
            add(store, session, machine = Attempt.Outcome.Almost, adult = Attempt.Outcome.Almost)
            add(store, session, machine = Attempt.Outcome.NotYet, adult = Attempt.Outcome.NotYet)
            add(store, session, machine = Attempt.Outcome.Good, adult = Attempt.Outcome.NotYet)
            add(store, session, machine = Attempt.Outcome.Unclear, adult = Attempt.Outcome.Good)
            add(store, session, machine = Attempt.Outcome.NotJudged, adult = Attempt.Outcome.Almost)
            add(store, session, machine = Attempt.Outcome.Good, adult = null)
            add(store, session, machine = Attempt.Outcome.Good, adult = Attempt.Outcome.Unclear)
            add(store, session, machine = Attempt.Outcome.Good, adult = Attempt.Outcome.NotJudged)

            val rows = current(store.repository.agreementStream(profileId = profile.id))
            val row = assertNotNull(rows?.firstOrNull())
            assertEquals(1, rows.size)
            assertEquals("s", row.sound)
            assertEquals(4, row.compared)
            assertEquals(3, row.agreed)
            assertEquals(2, row.machineAbstained)
            assertEquals(0.75, row.agreementRate)
        }
    }

    @Test
    @DisplayName("two identical abstentions are not an agreement")
    fun abstentionsDoNotAgree() = runTest {
        TestStore().use { store ->
            val profile = store.addChild()
            val session = store.addSession(profile)
            add(store, session, machine = Attempt.Outcome.Unclear, adult = Attempt.Outcome.Unclear)
            add(store, session, machine = Attempt.Outcome.Unclear, adult = Attempt.Outcome.Good)

            val rows = current(store.repository.agreementStream(profileId = profile.id))
            val row = assertNotNull(rows?.firstOrNull())
            assertEquals(0, row.compared)
            assertEquals(0, row.agreed)
            assertEquals(1, row.machineAbstained)
            assertNull(row.agreementRate)
        }
    }

    @Test
    @DisplayName("sounds and profiles stay apart, and attempts with no sound are left out")
    fun separatesSoundsAndProfiles() = runTest {
        TestStore().use { store ->
            val ana = store.addChild(name = "Ana")
            val mihai = store.addChild(name = "Mihai")
            val anaSession = store.addSession(ana)
            val mihaiSession = store.addSession(mihai)

            add(store, anaSession, sound = "s", machine = Attempt.Outcome.Good, adult = Attempt.Outcome.Good)
            add(store, anaSession, sound = "r", machine = Attempt.Outcome.Good, adult = Attempt.Outcome.NotYet)
            add(store, anaSession, sound = null, machine = Attempt.Outcome.Good, adult = Attempt.Outcome.Good)
            add(store, mihaiSession, sound = "s", machine = Attempt.Outcome.NotYet, adult = Attempt.Outcome.Good)

            val rows = current(store.repository.agreementStream(profileId = ana.id))
            assertEquals(listOf("r", "s"), rows?.map { it.sound })
            assertEquals(listOf(0, 1), rows?.map { it.agreed })
            assertEquals(listOf(1, 1), rows?.map { it.compared })
        }
    }

    @Test
    @DisplayName("a profile with no judged attempts has no rows")
    fun emptyWithoutAdultVerdicts() = runTest {
        TestStore().use { store ->
            val profile = store.addChild()
            val session = store.addSession(profile)
            add(store, session, machine = Attempt.Outcome.Good, adult = null)
            val rows = current(store.repository.agreementStream(profileId = profile.id))
            assertEquals(emptyList(), rows)
        }
    }

    @Test
    @DisplayName("the adult's verdict overrides the machine's in the per-sound counts")
    fun outcomesPreferTheAdult() = runTest {
        TestStore().use { store ->
            val profile = store.addChild()
            val session = store.addSession(profile)
            add(store, session, machine = Attempt.Outcome.Good, adult = null)
            add(store, session, machine = Attempt.Outcome.Good, adult = Attempt.Outcome.NotYet)
            add(store, session, machine = Attempt.Outcome.Unclear, adult = Attempt.Outcome.Almost)
            add(store, session, machine = Attempt.Outcome.Unclear, adult = null)
            add(store, session, machine = Attempt.Outcome.NotJudged, adult = null)
            add(store, session, sound = "r", machine = Attempt.Outcome.Good, adult = null)

            val rows = current(store.repository.soundOutcomesStream(profileId = profile.id))
            assertEquals(
                listOf(
                    SoundOutcomeCounts(sound = "r", good = 1, almost = 0, notYet = 0, undecided = 0),
                    SoundOutcomeCounts(sound = "s", good = 1, almost = 1, notYet = 1, undecided = 2),
                ),
                rows,
            )
        }
    }
}

@DisplayName("Weekly aggregates")
class WeeklyAggregatesTests {
    private suspend fun addAttempt(store: TestStore, session: SessionRecord, sound: String?, at: Instant) {
        store.repository.addAttempt(
            Attempt(
                sessionId = session.id,
                profileId = session.profileId,
                ts = at,
                exerciseKind = "repeat",
                sound = sound,
                machineOutcome = Attempt.Outcome.Good,
                timeZone = Bucharest,
            ),
        )
    }

    @Test
    @DisplayName("attempts group by sound and Monday-based week")
    fun weeklyCounts() = runTest {
        TestStore().use { store ->
            val profile = store.addChild()
            val session = store.addSession(profile)

            // Monday and Sunday of one week, then the Monday after.
            addAttempt(store, session, sound = "s", at = bucharest(2026, 11, 2, 9))
            addAttempt(store, session, sound = "s", at = bucharest(2026, 11, 8, 20))
            addAttempt(store, session, sound = "r", at = bucharest(2026, 11, 4, 9))
            addAttempt(store, session, sound = "s", at = bucharest(2026, 11, 9, 9))
            addAttempt(store, session, sound = null, at = bucharest(2026, 11, 4, 9))

            val rows = current(store.repository.weeklyAttemptsStream(profileId = profile.id))
            assertEquals(
                listOf(
                    WeeklySoundCount(weekStart = "2026-11-02", sound = "r", attempts = 1),
                    WeeklySoundCount(weekStart = "2026-11-02", sound = "s", attempts = 2),
                    WeeklySoundCount(weekStart = "2026-11-09", sound = "s", attempts = 1),
                ),
                rows,
            )
        }
    }

    @Test
    @DisplayName("the week boundary falls at local midnight, even when UTC is still on Sunday")
    fun localMidnightBoundary() = runTest {
        TestStore().use { store ->
            val profile = store.addChild()
            val session = store.addSession(profile)

            addAttempt(store, session, sound = "s", at = bucharest(2026, 11, 8, 23, 30))
            addAttempt(store, session, sound = "s", at = bucharest(2026, 11, 9, 0, 30))

            val rows = current(store.repository.weeklyAttemptsStream(profileId = profile.id))
            assertEquals(listOf("2026-11-02", "2026-11-09"), rows?.map { it.weekStart })
        }
    }

    @Test
    @DisplayName("the day the clocks change stays in its own week")
    fun daylightSavingChange() = runTest {
        TestStore().use { store ->
            val profile = store.addChild()
            val session = store.addSession(profile)

            // Romania leaves summer time on Sunday 25 October 2026.
            addAttempt(store, session, sound = "s", at = bucharest(2026, 10, 25, 23, 30))
            addAttempt(store, session, sound = "s", at = bucharest(2026, 10, 26, 0, 30))

            val rows = current(store.repository.weeklyAttemptsStream(profileId = profile.id))
            assertEquals(listOf("2026-10-19", "2026-10-26"), rows?.map { it.weekStart })
        }
    }
}

// Swift's `.timeLimit(.minutes(1))` on the suite becomes the same limit on each `runTest`.
@DisplayName("Streams")
class StreamTests {
    @Test
    @DisplayName("the profiles stream starts with the current value and follows changes")
    fun profilesFollow() = runTest(timeout = 1.minutes) {
        TestStore().use { store ->
            val iterator = store.repository.profilesStream().iterator(backgroundScope)
            val initial = iterator.receive()
            assertEquals(emptyList(), initial)

            val profile = Profile.fixtureChild(name = "Ana")
            store.repository.saveProfile(profile)
            val afterInsert = iterator.receive()
            assertEquals(listOf(profile), afterInsert)

            store.repository.deleteProfile(id = profile.id)
            val afterDelete = iterator.receive()
            assertEquals(emptyList(), afterDelete)
        }
    }

    @Test
    @DisplayName("the agreement stream follows an adult's verdict")
    fun agreementFollows() = runTest(timeout = 1.minutes) {
        TestStore().use { store ->
            val profile = store.addChild()
            val session = store.addSession(profile)
            val attempt = Attempt(
                sessionId = session.id,
                profileId = profile.id,
                exerciseKind = "repeat",
                sound = "s",
                machineOutcome = Attempt.Outcome.Good,
            )
            store.repository.addAttempt(attempt)

            val iterator = store.repository.agreementStream(profileId = profile.id).iterator(backgroundScope)
            val initial = iterator.receive()
            assertEquals(emptyList(), initial)

            store.repository.setAdultOutcome(attemptId = attempt.id, outcome = Attempt.Outcome.Good)
            val judged = iterator.receive()
            assertEquals(1, judged.firstOrNull()?.agreed)
        }
    }

    @Test
    @DisplayName("streams also work on the on-disk pool")
    fun worksOnPool() = runTest(timeout = 1.minutes) {
        val folder = temporaryFolder("fonia-pool")
        try {
            FoniaDatabase.open(folder).use { database ->
                val repository = FoniaRepository(database)

                val iterator = repository.profilesStream().iterator(backgroundScope)
                val initial = iterator.receive()
                assertEquals(emptyList(), initial)

                val profile = Profile.fixtureChild(name = "Ana")
                repository.saveProfile(profile)
                val afterInsert = iterator.receive()
                assertEquals(listOf(profile), afterInsert)
            }
        } finally {
            folder.deleteRecursively()
        }
    }
}
