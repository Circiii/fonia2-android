package tech.fonia.app.house

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.app.data.Ladder
import tech.fonia.app.data.SoundPractice
import tech.fonia.lessons.LessonContent
import tech.fonia.model.Rung
import tech.fonia.model.SoundId
import java.io.File

class ExerciseCatalogTest {
    private val content by lazy { LessonContent.fromDirectory(File(System.getProperty("fonia.root") ?: "..", "spec/content/v2")) }

    @Test fun everySoundHasElevenCardsFromItsOwnContent() {
        for (s in SoundId.entries) {
            val cards = ExerciseCatalog.forSound(s, ContentSummary.of(content, s), SoundPractice())
            assertEquals("$s: ${cards.map { it.exercise }}", ExerciseCatalog.ORDER, cards.map { it.exercise })
            assertTrue("$s counts", cards.all { it.count > 0 })
        }
    }

    @Test fun theHoldCardIsADifferentGamePerSound() {
        val titles = SoundId.entries.map { s -> ExerciseCatalog.forSound(s, ContentSummary.of(content, s), SoundPractice()).first { it.exercise == Exercise.Hold }.subtitle }
        assertEquals(9, titles.toSet().size)
        assertEquals("Sâsâilă se lungește", titles[0])
        assertEquals("Trenulețul Șușu", titles[1])
    }

    @Test fun otherCardsDifferByContent() {
        val words = SoundId.entries.map { s -> ContentSummary.of(content, s).examples[Exercise.Words] }
        assertEquals(9, words.toSet().size)
        val pairs = SoundId.entries.map { s -> ContentSummary.of(content, s).examples[Exercise.Pairs] }
        assertEquals(9, pairs.toSet().size)
    }

    @Test fun beforeTheAdultOnlyListeningHoldingAndTheViewersAreOpen() {
        val p = SoundPractice(rung = Rung.Isolated)
        val open = Exercise.entries.filter { ExerciseLocks.lockOf(it, p) == CardLock.Open }.toSet()
        assertEquals(setOf(Exercise.Listen, Exercise.Hold, Exercise.Mirror, Exercise.Mouth3D), open)
        assertEquals(CardLock.NeedsAdult, ExerciseLocks.lockOf(Exercise.Words, p))
    }

    @Test fun afterTheAdultEverythingReachedIsReplayableTheRestWaits() {
        val p = Ladder.confirmIsolated(SoundPractice()).copy(rung = Rung.WordsMedial)
        assertEquals(CardLock.Open, ExerciseLocks.lockOf(Exercise.Syllables, p))
        assertEquals(CardLock.Open, ExerciseLocks.lockOf(Exercise.Words, p))
        assertEquals(CardLock.NotYet, ExerciseLocks.lockOf(Exercise.Pairs, p))
        assertEquals(CardLock.NotYet, ExerciseLocks.lockOf(Exercise.Mission, p))
        assertEquals(Rung.WordsMedial, ExerciseLocks.rungFor(Exercise.Words, p))
    }

    @Test fun unlockAllOpensEveryCardEvenBeforeTheLock() {
        val p = SoundPractice(unlockAll = true)
        assertTrue(Exercise.entries.all { ExerciseLocks.lockOf(it, p) == CardLock.Open })
    }

    @Test fun dotsCountPlaysUpToThree() {
        val p = SoundPractice(plays = mapOf(Exercise.Hold to 5, Exercise.Listen to 1))
        val cards = ExerciseCatalog.forSound(SoundId.R, ContentSummary.of(content, SoundId.R), p)
        assertEquals(3, cards.first { it.exercise == Exercise.Hold }.dots)
        assertEquals(1, cards.first { it.exercise == Exercise.Listen }.dots)
    }
}
