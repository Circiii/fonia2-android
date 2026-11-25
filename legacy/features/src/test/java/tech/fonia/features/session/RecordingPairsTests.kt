package tech.fonia.features.session

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.store.FoniaDatabase
import tech.fonia.store.FoniaRepository
import tech.fonia.store.Profile
import tech.fonia.store.Recording
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

/** The first take of a word stays pinned, as iOS `SessionRun.store` does, so the newest-three pruning keeps the pair. */
class RecordingPairsTests {
    private val profile = UUID.randomUUID()

    private fun take(label: String, sound: String? = "s", pinned: Boolean = false) =
        Recording(profileId = profile, label = label, sound = sound, duration = 1.0, pinned = pinned)

    @Test
    fun theFirstTakeOfAWordIsPinnedAndTheNextOnesAreNot() {
        assertTrue(RecordingPairs.pinsNewTake(emptyList(), "sare", "s"))
        assertFalse(RecordingPairs.pinsNewTake(listOf(take("sare", pinned = true)), "sare", "s"))
        // A word already there unpinned is not new either.
        assertFalse(RecordingPairs.pinsNewTake(listOf(take("sare")), "sare", "s"))
        // The same word for another sound is a first take of that sound.
        assertTrue(RecordingPairs.pinsNewTake(listOf(take("sare", sound = "r", pinned = true)), "sare", "s"))
    }

    @Test
    fun atMostFiveFirstTakesStayPinnedForEachSound() {
        assertEquals(5, RecordingPairs.FIRST_TAKES_KEPT)
        val four = (1..4).map { take("cuvânt $it", pinned = true) }
        assertTrue(RecordingPairs.pinsNewTake(four, "nou", "s"))
        val five = four + take("cuvânt 5", pinned = true)
        assertFalse(RecordingPairs.pinsNewTake(five, "nou", "s"))
        // Pinned takes of another sound don't use up this sound's five.
        val other = (1..5).map { take("vorbă $it", sound = "r", pinned = true) }
        assertTrue(RecordingPairs.pinsNewTake(other, "nou", "s"))
    }

    @Test
    fun theFirstTakesOutliveTheNewestThreePruning() = runBlocking {
        val root = Files.createTempDirectory("fonia-pairs").toFile()
        val repository = FoniaRepository(FoniaDatabase.inMemory(recordingsDirectory = root))
        val child = Profile(
            id = profile, kind = Profile.Kind.Child, ageBand = Profile.AgeBand.SixToSeven, voiceGroup = Profile.VoiceGroup.Child,
            color = "cold", targetSounds = listOf("s"), track = Profile.Track.Ro,
        )
        repository.saveProfile(child)
        var at = Instant.parse("2026-11-02T16:00:00Z")
        suspend fun record(label: String): Recording {
            val earlier = repository.recordings(profile)
            val recording = Recording(
                profileId = profile, ts = at, label = label, sound = "s", duration = 1.0,
                pinned = RecordingPairs.pinsNewTake(earlier, label, "s"),
            )
            repository.recordingFileURL(recording).writeText("take")
            repository.addRecording(recording, now = at)
            at = at.plusSeconds(60)
            return recording
        }

        // Seven new words, then each said again twice: far more than the three newest the store keeps.
        val words = (1..7).map { "cuvânt $it" }
        val firsts = words.map { record(it) }
        repeat(2) { for (word in words) record(word) }

        val kept = repository.recordings(profile)
        val pinned = kept.filter { it.pinned }
        // The first five words keep their first take; the sixth and seventh came after the five were used up.
        assertEquals(firsts.take(5).map { it.id }.toSet(), pinned.map { it.id }.toSet())
        // Besides them, only the newest three stay, and their files are still on disk.
        assertEquals(8, kept.size)
        for (recording in kept) assertTrue(repository.recordingFileURL(recording).isFile)
        assertFalse(kept.any { it.id == firsts[5].id || it.id == firsts[6].id })
    }
}
