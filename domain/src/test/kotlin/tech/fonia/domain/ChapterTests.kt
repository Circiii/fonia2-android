package tech.fonia.domain

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

// Port of FoniaDomainTests/ChapterTests.swift.

/**
 * The hero's story, one chapter per lesson. The chapters are optional in the content, so most of these tests use their
 * own small hero rather than whatever the writers have filled in so far.
 */
@DisplayName("Hero chapters")
class ChapterTests {
    private val withChapters = """
        {
          "name": "Sâsâilă",
          "picture": "hero-s",
          "says": "Sssss",
          "story": "Sâsâilă suflă ca un șarpe.",
          "chapters": {
            "meet": "Sâsâilă iese din iarbă.",
            "isolated": "Sâsâilă suflă aer rece.",
            "back": "   "
          }
        }
    """.trimIndent()

    private val withoutChapters =
        """{ "name": "Sâsâilă", "picture": "hero-s", "says": "Sssss", "story": "Sâsâilă suflă ca un șarpe." }"""

    private fun hero(json: String): SoundHero = Json.decodeFromString(SoundHero.serializer(), json)

    @Test
    fun chaptersAreReadByLessonKey() {
        val hero = hero(withChapters)
        assertEquals("Sâsâilă iese din iarbă.", hero.chapter("meet"))
        assertEquals("Sâsâilă suflă aer rece.", hero.chapter("isolated"))
    }

    @Test
    fun aMissingOrBlankChapterIsNone() {
        val hero = hero(withChapters)
        assertNull(hero.chapter("back"))
        assertNull(hero.chapter("pairs"))
    }

    @Test
    fun aHeroWithoutChaptersStillLoads() {
        val hero = hero(withoutChapters)
        assertNull(hero.chapters)
        assertNull(hero.chapter("meet"))
    }

    @Test
    fun everySoundLessonHasAChapterKey() {
        for (id in SoundID.entries) {
            for (lesson in Catalog(Fixture.content).unit(Fixture.content.sound(id)).lessons) {
                val key = assertNotNull(lesson.focus.lessonKey, lesson.id)
                assertEquals(lesson.id, "${id.rawValue}-$key")
            }
        }
    }

    @Test
    fun everySoundLessonInTheContentHasAChapter() {
        // fonia-content f3215a1 wrote a chapter for every lesson of every sound.
        for (id in SoundID.entries) {
            val sound = Fixture.content.sound(id)
            for (lesson in Catalog(Fixture.content).unit(sound).lessons) {
                assertNotNull(sound.chapter(lesson.id), lesson.id)
            }
        }
    }

    @Test
    fun noChapterSentenceIsLongerThanTwelveWords() {
        // port-android.md §1: iOS ContentTests checks the same cap.
        for (sound in Fixture.content.sounds) {
            for ((key, text) in sound.hero.chapters.orEmpty()) {
                for (sentence in text.split(Regex("(?<=[.!?…])\\s+")).filter { it.isNotBlank() }) {
                    val words = sentence.split(Regex("\\s+")).count { word -> word.any { it.isLetter() } }
                    assertTrue(words <= 12, "${sound.id.rawValue}-$key: $sentence")
                }
            }
        }
    }

    @Test
    fun otherSessionsHaveNoChapter() {
        val focuses = listOf(SessionFocus.Story(SoundID.S), SessionFocus.Voicing, SessionFocus.VoicingSay)
        assertTrue(focuses.all { it.lessonKey == null })
    }

    @Test
    fun aLessonIdOrItsSuffixFindsTheSameChapter() {
        val sound = Fixture.content.sound(SoundID.S).copy(hero = hero(withChapters))
        assertEquals("Sâsâilă suflă aer rece.", sound.chapter("s-isolated"))
        assertEquals("Sâsâilă suflă aer rece.", sound.chapter("isolated"))
        assertEquals("Sâsâilă suflă aer rece.", sound.chapter(SessionFocus.Sound(SoundID.S, Rung.Isolated)))
        assertEquals("Sâsâilă iese din iarbă.", sound.chapter(SessionFocus.Meet(SoundID.S)))
        assertNull(sound.chapter(SessionFocus.SyllablesBack(SoundID.S)))
    }
}
