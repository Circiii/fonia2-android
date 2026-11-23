package tech.fonia.store

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("Retention policy")
class RetentionPolicyTests {
    private val profileId = UUID.randomUUID()
    private val now = bucharest(2026, 11, 4, 12)

    private fun take(daysAgo: Int, sound: String? = "s", pinned: Boolean = false, profile: UUID? = null): Recording =
        Recording(
            profileId = profile ?: profileId,
            ts = now.addingTimeInterval(-daysAgo.toDouble() * 86_400),
            label = "take",
            sound = sound,
            duration = 1.0,
            pinned = pinned,
        )

    private fun deleted(
        takes: List<Recording>,
        policy: RecordingRetentionPolicy = RecordingRetentionPolicy(keepLatestPerSound = 3, maxAge = null),
    ): Set<UUID> = policy.takesToDelete(takes, now).map { it.id }.toSet()

    @Test
    @DisplayName("the defaults keep few takes for a limited time")
    fun defaultsAreSmall() {
        val policy = RecordingRetentionPolicy.default
        val ninetyDays = 90.0 * 86_400
        assertEquals(3, policy.keepLatestPerSound)
        assertEquals(ninetyDays, policy.maxAge)
    }

    @Test
    @DisplayName("nothing is deleted while a sound is under the limit")
    fun underLimit() {
        assertTrue(deleted(listOf(take(daysAgo = 1), take(daysAgo = 2), take(daysAgo = 3))).isEmpty())
    }

    @Test
    @DisplayName("only the newest takes survive, whatever order they arrive in")
    fun keepsNewest() {
        val old = take(daysAgo = 9)
        val older = take(daysAgo = 12)
        val kept = listOf(take(daysAgo = 1), take(daysAgo = 2), take(daysAgo = 3))
        assertEquals(setOf(old.id, older.id), deleted(listOf(old) + kept + listOf(older)))
    }

    @Test
    @DisplayName("each sound has its own limit")
    fun perSound() {
        val s = (1..4).map { take(daysAgo = it, sound = "s") }
        val r = (1..3).map { take(daysAgo = it, sound = "r") }
        assertEquals(setOf(s[3].id), deleted(s + r))
    }

    @Test
    @DisplayName("each profile has its own limit")
    fun perProfile() {
        val other = UUID.randomUUID()
        val mine = (1..4).map { take(daysAgo = it) }
        val theirs = (1..3).map { take(daysAgo = it, profile = other) }
        assertEquals(setOf(mine[3].id), deleted(mine + theirs))
    }

    @Test
    @DisplayName("takes with no sound count as one group")
    fun noSoundGroup() {
        val talks = (1..4).map { take(daysAgo = it, sound = null) }
        assertEquals(setOf(talks[3].id), deleted(talks))
    }

    @Test
    @DisplayName("a pinned take stays however old it is")
    fun pinnedStays() {
        val pinned = take(daysAgo = 400, pinned = true)
        val policy = RecordingRetentionPolicy(keepLatestPerSound = 1, maxAge = 86_400.0)
        assertTrue(deleted(listOf(pinned, take(daysAgo = 0)), policy = policy).isEmpty())
    }

    @Test
    @DisplayName("pinned takes use up the newest slots, so older unpinned ones go")
    fun pinnedCountTowardLimit() {
        val pinned = (1..3).map { take(daysAgo = it, pinned = true) }
        val older = take(daysAgo = 4)
        assertEquals(setOf(older.id), deleted(pinned + listOf(older)))
    }

    @Test
    @DisplayName("a take past its age goes even when it is the newest")
    fun expiry() {
        val policy = RecordingRetentionPolicy(keepLatestPerSound = 3, maxAge = 30.0 * 86_400)
        val expired = take(daysAgo = 31)
        assertEquals(setOf(expired.id), deleted(listOf(expired), policy = policy))
    }

    @Test
    @DisplayName("a take exactly at the age limit stays")
    fun expiryBoundary() {
        val policy = RecordingRetentionPolicy(keepLatestPerSound = 3, maxAge = 30.0 * 86_400)
        assertTrue(deleted(listOf(take(daysAgo = 30)), policy = policy).isEmpty())
    }

    @Test
    @DisplayName("with a limit of zero only pinned takes stay")
    fun zeroLimit() {
        val pinned = take(daysAgo = 1, pinned = true)
        val plain = take(daysAgo = 2)
        val policy = RecordingRetentionPolicy(keepLatestPerSound = 0, maxAge = null)
        assertEquals(setOf(plain.id), deleted(listOf(pinned, plain), policy = policy))
    }

    @Test
    @DisplayName("takes at the same moment are ranked the same way every time")
    fun stableOnTies() {
        val takes = (0 until 5).map { take(daysAgo = 1) }
        val first = deleted(takes)
        assertEquals(2, first.size)
        assertEquals(first, deleted(takes.reversed()))
    }

    @Test
    @DisplayName("an empty list gives an empty answer")
    fun empty() {
        assertTrue(deleted(emptyList()).isEmpty())
    }
}

@DisplayName("Retention in the store")
class RetentionStoreTests {
    @Test
    @DisplayName("adding a take over the limit removes the oldest row and file")
    fun addPrunes() = runTest {
        TestStore(retention = RecordingRetentionPolicy(keepLatestPerSound = 2, maxAge = null)).use { store ->
            val profile = store.addChild()
            val first = store.addTake(profile, at = bucharest(2026, 11, 1, 10))
            val second = store.addTake(profile, at = bucharest(2026, 11, 2, 10))
            val third = store.addTake(profile, at = bucharest(2026, 11, 3, 10))

            val rows = store.repository.recordings(profileId = profile.id)
            assertEquals(listOf(third.id, second.id), rows.map { it.id })
            assertFalse(store.fileExists(first))
            assertTrue(store.fileExists(second))
            assertTrue(store.fileExists(third))
        }
    }

    @Test
    @DisplayName("pruning a take keeps the attempt, its verdicts and the session's seed")
    fun pruneKeepsPractice() = runTest {
        TestStore(retention = RecordingRetentionPolicy(keepLatestPerSound = 1, maxAge = null)).use { store ->
            val profile = store.addChild()
            val session = store.addSession(profile, seed = 99)
            val take = store.addTake(profile, at = bucharest(2026, 11, 1, 10))
            val attempt = Attempt(
                sessionId = session.id,
                profileId = profile.id,
                exerciseKind = "repeat",
                sound = "s",
                machineOutcome = Attempt.Outcome.Good,
                adultOutcome = Attempt.Outcome.Almost,
                recordingId = take.id,
            )
            store.repository.addAttempt(attempt)

            store.addTake(profile, at = bucharest(2026, 11, 2, 10))

            val stored = assertNotNull(store.repository.attempts(sessionId = session.id).firstOrNull())
            assertNull(stored.recordingId)
            assertEquals(Attempt.Outcome.Good, stored.machineOutcome)
            assertEquals(Attempt.Outcome.Almost, stored.adultOutcome)
            assertEquals(99L, store.repository.sessions(profileId = profile.id).firstOrNull()?.seed)
        }
    }

    @Test
    @DisplayName("unpinning applies the limit straight away")
    fun unpinPrunes() = runTest {
        TestStore(retention = RecordingRetentionPolicy(keepLatestPerSound = 1, maxAge = null)).use { store ->
            val profile = store.addChild()
            val pinned = store.addTake(profile, at = bucharest(2026, 11, 1, 10), pinned = true)
            store.addTake(profile, at = bucharest(2026, 11, 2, 10))
            assertTrue(store.fileExists(pinned))

            store.repository.setPinned(recordingId = pinned.id, pinned = false, now = bucharest(2026, 11, 3, 10))

            assertFalse(store.fileExists(pinned))
            assertEquals(1, store.repository.recordings(profileId = profile.id).size)
        }
    }

    @Test
    @DisplayName("startup maintenance expires old takes on every profile")
    fun maintenanceExpires() = runTest {
        TestStore().use { store ->
            val ana = store.addChild(name = "Ana")
            val mihai = store.addChild(name = "Mihai")
            val old = store.addTake(ana, at = bucharest(2026, 1, 1, 10))
            val oldPinned = store.addTake(mihai, at = bucharest(2026, 1, 1, 10), pinned = true)
            val recent = store.addTake(mihai, at = bucharest(2026, 11, 1, 10))

            store.repository.runStartupMaintenance(now = bucharest(2026, 11, 4, 10))

            assertTrue(store.repository.recordings(profileId = ana.id).isEmpty())
            assertFalse(store.fileExists(old))
            assertTrue(store.fileExists(oldPinned))
            assertTrue(store.fileExists(recent))
        }
    }

    @Test
    @DisplayName("startup maintenance removes files no row points at")
    fun maintenanceSweepsOrphans() = runTest {
        TestStore().use { store ->
            val profile = store.addChild()
            val kept = store.addTake(profile, at = bucharest(2026, 11, 1, 10))

            val orphan = Recording(profileId = profile.id, label = "fără rând", duration = 1.0)
            store.repository.recordingFileURL(orphan).writeBytes(byteArrayOf(1))
            val gone = Recording(profileId = UUID.randomUUID(), label = "profil șters", duration = 1.0)
            val goneURL = store.repository.recordingFileURL(gone)
            goneURL.writeBytes(byteArrayOf(1))

            store.repository.runStartupMaintenance(now = bucharest(2026, 11, 4, 10))

            assertTrue(store.fileExists(kept))
            assertFalse(store.fileExists(orphan))
            assertFalse(goneURL.parentFile.exists())
        }
    }
}
