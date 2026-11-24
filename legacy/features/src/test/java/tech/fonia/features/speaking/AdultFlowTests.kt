package tech.fonia.features.speaking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.design.Rating
import tech.fonia.domain.ContentSource
import tech.fonia.domain.Exercise
import tech.fonia.domain.FeedbackLevel
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.IntonationAsk
import tech.fonia.domain.Language
import tech.fonia.domain.RepeatPrompt
import tech.fonia.features.reading.CopyRules
import java.io.File
import java.util.UUID

/** "Compară", the topic ladder, the nerves tip, the strength said first and "ăă" against the person's own talks (iOS `AdultFlowTests`, `FillerTrendTests`). */
class AdultFlowTests {
    // Compară

    private fun offers(
        rating: Rating?,
        feedback: FeedbackLevel = FeedbackLevel.Teach,
        isChild: Boolean = false,
        hasTake: Boolean = true,
        keepsTake: Boolean = true,
        recorded: Boolean = true,
    ) = GuidedCompare.offers(rating, keepsTake, feedback, isChild, hasTake, recorded)

    @Test
    fun compareIsOfferedWhenThePhoneIsUnsure() {
        for (feedback in FeedbackLevel.entries) assertTrue("$feedback", offers(null, feedback = feedback))
    }

    @Test
    fun compareIsOfferedForANotYetOnlyWithFullFeedback() {
        assertTrue(offers(Rating.nearly))
        assertTrue(offers(Rating.notYet))
        assertFalse(offers(Rating.nearly, feedback = FeedbackLevel.Quick))
        assertFalse(offers(Rating.notYet, feedback = FeedbackLevel.Silent))
    }

    @Test
    fun compareIsNeverOfferedAfterAGoodTakeToAChildOrWithoutAnExample() {
        for (feedback in FeedbackLevel.entries) assertFalse(offers(Rating.yes, feedback = feedback))
        for (rating in listOf(null, Rating.nearly, Rating.notYet, Rating.yes)) assertFalse(offers(rating, isChild = true))
        assertFalse(offers(null, hasTake = false))
        assertFalse(offers(Rating.notYet, keepsTake = false))
        // The device voice is no example to copy.
        assertFalse(offers(Rating.notYet, recorded = false))
    }

    @Test
    fun everyCourseContrastHasItsQuestion() {
        val content = FoniaContent.load(ContentSource.directory(File(System.getProperty("fonia.content") ?: "../content")))
        for (contrast in content.english.contrasts) assertTrue(contrast.id, contrast.id in GuidedCompare.CONTRAST_QUESTIONS)
    }

    @Test
    fun aSentenceAsksWhatItsContrastListensFor() {
        val prompt = RepeatPrompt(text = "Put the pin in the bin.", language = Language.En, level = RepeatPrompt.Level.Sentence, englishContrast = "pin-bin")
        assertEquals(GuidedCompare.CONTRAST_QUESTIONS["pin-bin"], GuidedCompare.question(Exercise.Repeat(prompt)))
        val plain = RepeatPrompt(text = "Bună ziua.", language = Language.Ro, level = RepeatPrompt.Level.Sentence)
        assertEquals(GuidedCompare.ANY_DIFFERENCE, GuidedCompare.question(Exercise.Repeat(plain)))
    }

    @Test
    fun questionsAskAndNeverRule() {
        val all = GuidedCompare.CONTRAST_QUESTIONS.values + listOf(
            GuidedCompare.ANY_DIFFERENCE,
            GuidedCompare.question(Exercise.Intonation("Vii.", "Vii?", IntonationAsk.Question)),
            GuidedCompare.question(Exercise.Intonation("Vii.", "Vii?", IntonationAsk.Statement)),
            GuidedCompare.question(Exercise.Shadow("I walk to work.", Language.En)),
        )
        for (question in all) {
            assertTrue(question, question.endsWith("?"))
            assertTrue(question, question.split("?").filter { it.isNotBlank() }.size <= 2)
            assertEquals(question, emptyList<String>(), CopyRules.problems(question))
        }
    }

    // The topic ladder and the nerves tip

    @Test
    fun theLadderStartsOnTheMostPrivateTopicAndClimbsAStepAtATime() {
        var memory = SpeakingMemory()
        assertEquals(0, memory.todaysStep(7))
        memory = memory.finished(0)
        assertEquals(1, memory.todaysStep(7))
        memory = memory.finished(1)
        assertEquals(2, memory.todaysStep(7))
        // It stays on the top rung.
        assertEquals(6, SpeakingMemory().finished(6).todaysStep(7))
        // It picks up from any topic picked again.
        memory = SpeakingMemory().finished(4)
        assertEquals(5, memory.todaysStep(7))
        assertEquals(2, memory.finished(1).todaysStep(7))
        assertEquals(0, SpeakingMemory().todaysStep(0))
    }

    @Test
    fun theLadderStepsBackDownAndClimbsFromThere() {
        var memory = SpeakingMemory().finished(3).stepBack(7)
        assertEquals(3, memory.todaysStep(7))
        memory = memory.stepBack(7)
        assertEquals(2, memory.todaysStep(7))
        assertEquals(3, memory.finished(2).todaysStep(7))
        assertEquals(0, SpeakingMemory().stepBack(7).todaysStep(7))
    }

    @Test
    fun aTalkOffTheLadderLeavesTheStepButCounts() {
        val memory = SpeakingMemory().finished(2).finished(null)
        assertEquals(3, memory.todaysStep(7))
        assertEquals(2, memory.talksFinished)
    }

    @Test
    fun theNervesTipShowsForTheFirstTwoTalksOnly() {
        var memory = SpeakingMemory()
        assertTrue(memory.showsNervesTip)
        memory = memory.finished(null)
        assertTrue(memory.showsNervesTip)
        memory = memory.finished(0)
        assertFalse(memory.showsNervesTip)
    }

    @Test
    fun theMemoryIsKeptPerProfileUnderTheWipedPrefixAndComesBackAsSaved() {
        assertTrue(SpeakingMemory.key(UUID.randomUUID()).startsWith("fonia."))
        val memory = SpeakingMemory().finished(1, FillerSample(4, 75.0)).finished(null, FillerSample(0, 20.0)).stepBack(7)
        assertEquals(memory, SpeakingMemory.decode(memory.encode()))
        assertEquals(listOf(FillerSample(4, 75.0), FillerSample(0, 20.0)), SpeakingMemory.decode(memory.encode()).fillerTalks)
        // An older file, without the hesitations, keeps the place on the ladder.
        val old = SpeakingMemory.decode("""{"lastFinished":2,"talksFinished":4}""")
        assertEquals(2, old.lastFinished)
        assertNull(old.chosen)
        assertEquals(4, old.talksFinished)
        assertTrue(old.fillerTalks.isEmpty())
        assertEquals(3, old.todaysStep(7))
        assertEquals(3, SpeakingMemory.decode("""{"lastFinished":5,"chosen":3,"talksFinished":9}""").todaysStep(7))
        // Nothing stored, or something unreadable, starts over.
        assertEquals(SpeakingMemory(), SpeakingMemory.decode(""))
        assertEquals(SpeakingMemory(), SpeakingMemory.decode("{not json"))
        assertEquals(SpeakingMemory(), SpeakingMemory.load(UUID.randomUUID(), null))
    }

    @Test
    fun onlyTheLatestTwentyTalksAreKeptAndATalkWithoutASampleStillCounts() {
        var memory = SpeakingMemory()
        for (count in 0 until 30) memory = memory.finished(null, FillerSample(count, 60.0))
        assertEquals(SpeakingMemory.FILLER_TALKS_KEPT, memory.fillerTalks.size)
        assertEquals(10, memory.fillerTalks.first().count)
        assertEquals(29, memory.fillerTalks.last().count)
        assertEquals(30, memory.talksFinished)
        val one = SpeakingMemory().finished(0, FillerSample(2, 60.0)).finished(1)
        assertEquals(2, one.talksFinished)
        assertEquals(1, one.fillerTalks.size)
    }

    // What worked first

    private fun strength(focus: TalkFocus, pace: Boolean = false, pitch: Boolean = false, fewerFillers: Boolean = false, heldTime: Boolean = false) =
        TalkReport.strength(focus, pace, pitch, fewerFillers, heldTime)

    @Test
    fun theReportLeadsWithAMeasuredStrengthAndNeverPraisesWhatItIsAboutToFix() {
        assertNull(strength(TalkFocus.NotHeard, pace = true, pitch = true, fewerFillers = true, heldTime = true))
        assertEquals(TalkReport.Strength.PACE, strength(TalkFocus.Hesitations, pace = true))
        assertEquals(TalkReport.Strength.PITCH, strength(TalkFocus.None, pitch = true))
        assertEquals(TalkReport.Strength.FEWER_FILLERS, strength(TalkFocus.TooFast, fewerFillers = true))
        assertNotEquals(TalkReport.Strength.PACE, strength(TalkFocus.TooFast, pace = true))
        assertNotEquals(TalkReport.Strength.PACE, strength(TalkFocus.TooSlow, pace = true))
        assertNotEquals(TalkReport.Strength.PITCH, strength(TalkFocus.FlatPitch, pitch = true))
        assertNotEquals(TalkReport.Strength.FEWER_FILLERS, strength(TalkFocus.Hesitations, fewerFillers = true))
        assertEquals(TalkReport.Strength.HELD_TIME, strength(TalkFocus.PhrasesShort, heldTime = true))
        assertEquals(TalkReport.Strength.FINISHED, strength(TalkFocus.Time))
        assertEquals(TalkReport.Strength.FINISHED, strength(TalkFocus.FlatPitch))
    }

    // "ăă" against the person's own talks

    private fun minute(count: Int) = FillerSample(count, 60.0)

    @Test
    fun aRateNeedsThirtySecondsAndUsualIsTheMedianOfTheLastFive() {
        assertNull(FillerSample(5, 29.9).perMinute)
        assertEquals(10.0, FillerSample(5, 30.0).perMinute!!, 0.0)
        assertEquals(0.0, FillerSample(0, 90.0).perMinute!!, 0.0)
        assertEquals(8.0, FillerTrend.usualRate(listOf(minute(40), minute(40), minute(4), minute(12), minute(8), minute(6), minute(10)))!!, 0.0)
        assertEquals(7.0, FillerTrend.usualRate(listOf(minute(4), minute(6), minute(8), minute(10)))!!, 0.0)
        val short = FillerSample(9, 12.0)
        assertEquals(4.0, FillerTrend.usualRate(listOf(minute(2), short, minute(4), short, minute(6)))!!, 0.0)
        assertNull(FillerTrend.usualRate(listOf(short, short, short, minute(2), minute(4))))
        assertNull(FillerTrend.usualRate(emptyList()))
        assertNull(FillerTrend.usualRate(listOf(minute(3), minute(5))))
        assertEquals(4.0, FillerTrend.usualRate(listOf(minute(3), minute(5), minute(4)))!!, 0.0)
    }

    @Test
    fun theFirstTalksAndShortTalksHaveNoComparison() {
        assertEquals(FillerTrend.FirstTalks, FillerTrend.compare(6, 60.0, emptyList()))
        assertEquals(FillerTrend.FirstTalks, FillerTrend.compare(6, 60.0, listOf(minute(3), minute(5))))
        assertEquals(FillerTrend.FirstTalks, FillerTrend.compare(60, 60.0, emptyList()))
        assertEquals(FillerTrend.TooShort, FillerTrend.compare(9, 29.0, listOf(minute(3), minute(5), minute(4))))
        assertEquals(FillerTrend.TooShort, FillerTrend.compare(0, 10.0, emptyList()))
    }

    @Test
    fun aCountInsideTheNoiseIsAboutTheSame() {
        val history = listOf(minute(6), minute(6), minute(6))
        // Usual 6 in a minute: the allowance is 1.5 × √6 = 3.67, so 3 to 9 are the same.
        for (count in 3..9) assertEquals("count $count", FillerTrend.Same(6.0), FillerTrend.compare(count, 60.0, history))
        assertEquals(FillerTrend.Fewer(6.0), FillerTrend.compare(2, 60.0, history))
        assertEquals(FillerTrend.More(6.0), FillerTrend.compare(10, 60.0, history))
        // Never under two: one stray "ăă" is not news.
        val rare = listOf(minute(0), minute(1), minute(0))
        assertEquals(FillerTrend.Same(0.0), FillerTrend.compare(2, 60.0, rare))
        assertEquals(FillerTrend.More(0.0), FillerTrend.compare(3, 60.0, rare))
    }

    @Test
    fun aLongerTalkIsComparedByRateAndUsualBelongsToThePerson() {
        val history = listOf(minute(6), minute(6), minute(6))
        assertEquals(FillerTrend.Same(6.0), FillerTrend.compare(17, 120.0, history))
        assertEquals(FillerTrend.More(6.0), FillerTrend.compare(18, 120.0, history))
        assertEquals(FillerTrend.Same(6.0), FillerTrend.compare(7, 120.0, history))
        assertEquals(FillerTrend.Fewer(6.0), FillerTrend.compare(6, 120.0, history))
        assertTrue(FillerTrend.compare(6, 120.0, listOf(minute(20), minute(20), minute(20))).isFewer)
        assertEquals(FillerTrend.More(2.0), FillerTrend.compare(12, 60.0, listOf(minute(2), minute(3), minute(2))))
        assertEquals(FillerTrend.Same(15.0), FillerTrend.compare(12, 60.0, listOf(minute(14), minute(15), minute(16))))
    }

    @Test
    fun twoTalksInARowOnlyAClearDropCounts() {
        assertEquals(FillerTrend.Direction.Lower, FillerTrend.change(minute(12), minute(4)))
        assertEquals(FillerTrend.Direction.Alike, FillerTrend.change(minute(12), minute(11)))
        assertEquals(FillerTrend.Direction.Higher, FillerTrend.change(minute(4), minute(12)))
        assertNull(FillerTrend.change(FillerSample(3, 10.0), minute(2)))
        assertNull(FillerTrend.change(minute(3), FillerSample(0, 10.0)))
    }

    @Test
    fun theReportSaysHowTheHesitationsCompareWithTheSpeakersUsual() {
        assertNull(TalkReport.fillerSentence(0, FillerTrend.FirstTalks, 0.0))
        assertEquals("Am numărat 3. E prea puțin timp ca să le compar cu alte dăți.", TalkReport.fillerSentence(3, FillerTrend.TooShort, null))
        assertEquals("Cam 6 pe minut. După câteva vorbiri îți arăt cum se schimbă la tine.", TalkReport.fillerSentence(6, FillerTrend.FirstTalks, 5.6))
        assertEquals("Cam 3 pe minut. Mai rar decât de obicei la tine, cam 7 pe minut.", TalkReport.fillerSentence(8, FillerTrend.Fewer(7.4), 3.2))
        assertEquals("Cam 7 pe minut. Cam cât de obicei la tine, cam 7 pe minut.", TalkReport.fillerSentence(8, FillerTrend.Same(7.4), 7.0))
        assertEquals("Cam 9 pe minut. Mai des decât de obicei la tine, cam 2 pe minut.", TalkReport.fillerSentence(8, FillerTrend.More(2.2), 9.1))
    }

    @Test
    fun noSentenceNamesALimitOrANorm() {
        val trends = listOf(FillerTrend.TooShort, FillerTrend.FirstTalks, FillerTrend.Fewer(8.0), FillerTrend.Same(5.0), FillerTrend.More(2.0))
        for (trend in trends) {
            for (count in listOf(1, 4, 12)) {
                val said = TalkReport.fillerSentence(count, trend, if (trend == FillerTrend.TooShort) null else 6.0) ?: ""
                assertEquals(said, emptyList<String>(), CopyRules.problems(said))
                for (word in listOf("normal", "limită", "prea multe", "prea des", "greșit", "bine", "rău", "slab")) {
                    assertFalse(said, said.lowercase().contains(word))
                }
            }
        }
        assertEquals(emptyList<String>(), CopyRules.problems(TalkReport.Strength.FEWER_FILLERS))
    }
}
