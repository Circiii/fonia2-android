package tech.fonia.features.diction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.domain.ContentSource
import tech.fonia.domain.Exercise
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.RepeatPrompt
import tech.fonia.domain.SoundID
import tech.fonia.lessons.LessonContent
import java.io.File

class DictionDrillsTests {
    private val content = FoniaContent.load(ContentSource.directory(File(System.getProperty("fonia.content"))))
    private val v2 = LessonContent.fromDirectory(File(System.getProperty("fonia.contentV2")))

    @Test
    fun everySoundGetsTheManualsLadder() {
        for (id in SoundID.entries) {
            val drills = DictionDrills.build(content.sound(id), v2, hasClip = { true }, hasPicture = { true })
            assertTrue("$id has ${drills.size} drills", drills.size in 6..8)
            assertEquals(drills.indices.toList(), drills.map { it.index })
            assertEquals("Încălzirea", drills.first().title)
            assertFalse(drills.first().judged)
            for (drill in drills) assertEquals(drill.trials.size, drill.chips.size)
        }
    }

    @Test
    fun theSyllableLadderGoesDirectThenReversed() {
        val drills = DictionDrills.build(content.sound(SoundID.S), v2, hasClip = { true }, hasPicture = { true })
        val ladder = drills.first { it.title == "Scara silabelor" }
        val texts = ladder.trials.map { ((it.exercise as Exercise.Repeat).prompt).text }
        assertTrue(texts.first().startsWith("s"))
        assertTrue(texts.last().endsWith("s"))
        assertTrue(ladder.trials.all { (it.exercise as Exercise.Repeat).prompt.level == RepeatPrompt.Level.Syllable })
    }

    @Test
    fun theTwisterHasATempoLadderAndPairsJudgeEachWordOnItsOwnSound() {
        val drills = DictionDrills.build(content.sound(SoundID.S), v2, hasClip = { true }, hasPicture = { true })
        val twister = drills.first { it.tempos != null }
        assertEquals(listOf(Tempo.Slow, Tempo.Medium, Tempo.Fast), twister.tempos)
        assertTrue(twister.trials.all { (it.exercise as Exercise.Twister).sound == SoundID.S })
        val pairs = drills.first { it.title == "Perechi" }
        val sounds = pairs.trials.map { (it.exercise as Exercise.Repeat).prompt.sound }
        assertTrue(SoundID.S in sounds && SoundID.Sh in sounds)
    }

    @Test
    fun drillsOpenOneAfterAnotherByHisOwnSuccess() {
        assertTrue(DictionDrills.isOpen(0, emptyMap()))
        assertFalse(DictionDrills.isOpen(1, emptyMap()))
        assertFalse(DictionDrills.isOpen(2, mapOf(0 to 1.0, 1 to 0.5)))
        assertTrue(DictionDrills.isOpen(2, mapOf(0 to 1.0, 1 to 0.6)))
        assertTrue("a drill once played stays open", DictionDrills.isOpen(3, mapOf(3 to 0.2)))
        assertEquals(1.0, DictionDrills.share(0, 0, drillIsJudged = false), 0.0)
        assertEquals(0.0, DictionDrills.share(0, 0, drillIsJudged = true), 0.0)
        assertEquals(0.75, DictionDrills.share(3, 4, drillIsJudged = true), 0.0)
    }

    @Test
    fun aPictureIsNamedByItsCodepoints() {
        assertEquals("1f6f7", DictionDrills.pictureName("🛷") { true })
        assertEquals(null, DictionDrills.pictureName("🛷") { false })
        assertEquals("2744", DictionDrills.pictureName("❄️") { true })
    }
}
