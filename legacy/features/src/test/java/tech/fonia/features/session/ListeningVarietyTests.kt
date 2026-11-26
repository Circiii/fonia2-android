package tech.fonia.features.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.domain.AgeBand
import tech.fonia.domain.AttemptsSummary
import tech.fonia.domain.ContentSource
import tech.fonia.domain.Exercise
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.Rung
import tech.fonia.domain.SessionBuilder
import tech.fonia.domain.SessionFocus
import tech.fonia.domain.SessionRequest
import tech.fonia.domain.SoundID
import java.io.File
import kotlin.random.Random

/** Owner, 2026-11-24: "ce auzi se repetă de fiecare dată". Each run hears other items, the builder stays as on iOS. */
class ListeningVarietyTests {
    private val contentDir = File(System.getProperty("fonia.content") ?: error("fonia.content is not set"))
    private val content by lazy { FoniaContent.load(ContentSource.directory(contentDir)) }
    private val v2 by lazy {
        val dir = File(contentDir.absoluteFile.parentFile, "spec/content/v2")
        tech.fonia.lessons.LessonContent.load { File(dir, it).readText() }
    }
    private val variety by lazy { ListeningVariety(content, v2, hasClip = { true }, hasPicture = { false }) }

    private fun heard(focus: SessionFocus, seed: Long, recent: Set<String>): List<String> {
        val built = SessionBuilder(content).build(
            SessionRequest(age = AgeBand.TeenAdult, focus = focus, history = AttemptsSummary(0, emptySet()), seed = seed.toULong()),
        )
        return variety.vary(built, focus, recent, Random(seed)).trials.mapNotNull {
            when (val e = it.exercise) {
                is Exercise.Choose -> e.prompt.answer.text + "/" + e.prompt.other.text
                is Exercise.Hear -> if (e.has) e.item.text else null
                else -> null
            }
        }
    }

    @Test
    fun ceAiAuzitDrawsFromMoreThanItsSixPairsAndAvoidsTheLastRun() {
        assertTrue(variety.voicingPool().size > content.listening.voicingPairs.size)
        val first = heard(SessionFocus.Voicing, 1, emptySet())
        val recent = first.flatMap { it.split("/") }.toSet()
        val second = heard(SessionFocus.Voicing, 2, recent)
        assertEquals(first.size, second.size)
        val overlap = second.count { pair -> pair.split("/").any { it in recent } }
        assertTrue("second run repeats $overlap of ${second.size}: $first → $second", overlap < second.size / 2)
    }

    @Test
    fun aSoundsListeningChangesFromRunToRun() {
        val focus = SessionFocus.Sound(SoundID.Ge, Rung.Isolated)
        val runs = (1..4).map { heard(focus, it.toLong(), emptySet()) }
        assertTrue(runs.all { it.isNotEmpty() })
        assertNotEquals(runs[0], runs[1])
        assertTrue("GE's pool grew", variety.identificationPool(SoundID.Ge).size > 2)
    }

    @Test
    fun aSmallPoolIsDealtAgainWithoutBackToBackRepeats() {
        val drawn = variety.draw(listOf("a", "b"), 7, emptySet(), Random(3)) { setOf(it) }
        assertEquals(7, drawn.size)
        assertTrue(drawn.zipWithNext().none { (x, y) -> x == y })
    }
}
