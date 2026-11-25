package tech.fonia.features.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.domain.DictionLesson
import tech.fonia.domain.EnglishFocus
import tech.fonia.domain.Rung
import tech.fonia.domain.SessionFocus
import tech.fonia.domain.SoundID
import tech.fonia.domain.Stage
import tech.fonia.features.grownups.StageNames
import java.util.UUID

/** The short cards for the adult beside a child, which ones a profile has met, and the stage names (iOS `AdultCardsTests`). */
class AdultCardsTests {
    private val s = SoundID.entries.first()

    private val focuses: List<Pair<SessionFocus, AdultCardKind?>> = listOf(
        SessionFocus.Sound(s, Rung.Isolated) to AdultCardKind.IsolatedSound,
        SessionFocus.Sound(s, Rung.Syllables) to AdultCardKind.Syllables,
        SessionFocus.SyllablesBack(s) to AdultCardKind.Syllables,
        SessionFocus.Sound(s, Rung.WordsInitial) to AdultCardKind.Words,
        SessionFocus.Sound(s, Rung.WordsMedial) to AdultCardKind.Words,
        SessionFocus.Sound(s, Rung.WordsFinal) to AdultCardKind.Words,
        SessionFocus.Sound(s, Rung.Clusters) to AdultCardKind.Words,
        SessionFocus.Sound(s, Rung.Pairs) to AdultCardKind.Listening,
        SessionFocus.Sound(s, Rung.Sentences) to AdultCardKind.Sentences,
        SessionFocus.Sound(s, Rung.Retell) to AdultCardKind.Sentences,
        SessionFocus.Sound(s, Rung.Everyday) to AdultCardKind.Sentences,
        SessionFocus.Story(s) to AdultCardKind.Sentences,
        SessionFocus.Meet(s) to AdultCardKind.Listening,
        SessionFocus.Voicing to AdultCardKind.Listening,
        SessionFocus.VoicingSay to AdultCardKind.Listening,
        SessionFocus.Warmups(listOf("zambet"), breath = false) to null,
        SessionFocus.English(EnglishFocus.Drill("th")) to null,
        SessionFocus.DictionLesson(DictionLesson.Candles) to null,
    )

    @Test
    fun everyKindHasACardWithItsThreeParts() {
        assertEquals(6, AdultCardKind.entries.size)
        for (kind in AdultCardKind.entries) {
            val card = AdultCards.card(kind)
            assertEquals(kind, card.kind)
            for (line in listOf(card.title, card.target, card.goodTry, card.afterMiss)) assertTrue("$kind", line.isNotBlank())
            assertEquals(kind, AdultCardKind.fromRawValue(kind.rawValue))
        }
    }

    @Test
    fun aCardIsShortEnoughToReadInAboutTwentySeconds() {
        for (kind in AdultCardKind.entries) {
            val card = AdultCards.card(kind)
            val words = listOf(card.target, card.goodTry, card.afterMiss).joinToString(" ").split(Regex("\\s+")).size
            assertTrue("$kind has $words words", words <= 85)
        }
    }

    @Test
    fun noLineClaimsTherapyADiagnosisOrATreatment() {
        val words = listOf("terapie", "terapeut", "diagnostic", "tratament", "tratează", "vindec", "risc", "severitate", "tulburare")
        for (line in AdultCards.everyLine) {
            for (word in words) assertFalse(line, line.contains(word, ignoreCase = true))
        }
    }

    @Test
    fun noLineGivesAnAgeADoseOrAPromise() {
        for (line in AdultCards.everyLine) {
            assertFalse(line, line.any(Char::isDigit))
            for (word in listOf("garant", "sigur", "rezultat", "ani")) assertFalse(line, line.contains(" $word", ignoreCase = true))
        }
    }

    @Test
    fun aMissIsNeverCalledAMistakeAndNoMouthExercisesAreSuggested() {
        for (kind in AdultCardKind.entries) {
            val miss = AdultCards.card(kind).afterMiss
            for (word in listOf("greș", "corect", "nu e bine", "încă o dată")) assertFalse("$kind: $miss", miss.contains(word, ignoreCase = true))
            // It says what the adult does: say it again, go one step easier, stop after a few tries.
            assertTrue("$kind", miss.contains("câteva încercări", ignoreCase = true))
        }
        for (line in AdultCards.everyLine) {
            for (word in listOf("gimnastic", "linge", "umfl", "scoate limba", "exerciții pentru")) {
                assertFalse(line, line.contains(word, ignoreCase = true))
            }
        }
    }

    @Test
    fun theLettersWithACommaAreTheRomanianOnes() {
        // Ş ş Ţ ţ (cedilla) look the same on screen but read wrong with TalkBack and in search.
        for (line in AdultCards.everyLine) assertFalse(line, line.any { it in "ŞşŢţ" })
    }

    @Test
    fun everyReportForTheAdultSaysWhatItIsWithTheSameLine() {
        assertEquals(
            "Raportul arată ce s-a exersat și ce a auzit telefonul. Nu e o evaluare: concluziile le trage logopedul.",
            AdultCards.REPORT_DISCLAIMER,
        )
        assertTrue(AdultCards.REPORT_DISCLAIMER in AdultCards.everyLine)
    }

    @Test
    fun theAdultIsToldWhereToFindTheCardAgain() {
        assertTrue(AdultCards.REOPEN_HINT.contains("Setări"))
        assertTrue(AdultCards.REOPEN_HINT.contains(AdultCards.SCREEN_TITLE))
    }

    @Test
    fun anAdultPractisingAloneGetsNoCard() {
        for (kind in AdultCardKind.entries) {
            assertNull(AdultCards.card(kind, isChild = false))
            assertNotNull(AdultCards.card(kind, isChild = true))
        }
        for ((focus, _) in focuses) assertNull("$focus", AdultCards.firstTimeCard(focus, isChild = false, seen = emptySet()))
        assertNull(AdultCards.firstTimeMirrorCard(isChild = false, seen = emptySet()))
    }

    @Test
    fun everyLevelAndEveryKindOfLessonMapsToTheRightCard() {
        for ((focus, expected) in focuses) assertEquals("$focus", expected, AdultCards.kind(focus))
        assertEquals(
            setOf(AdultCardKind.IsolatedSound, AdultCardKind.Syllables, AdultCardKind.Words, AdultCardKind.Listening, AdultCardKind.Sentences),
            Rung.entries.map(AdultCards::kind).toSet(),
        )
    }

    @Test
    fun theFirstStartOfAKindShowsItsCardAndTheSecondDoesNot() {
        val focus = SessionFocus.Sound(s, Rung.Syllables)
        assertEquals(AdultCardKind.Syllables, AdultCards.firstTimeCard(focus, isChild = true, seen = emptySet())?.kind)
        assertNull(AdultCards.firstTimeCard(focus, isChild = true, seen = setOf(AdultCardKind.Syllables)))
        // Another kind is still new.
        assertNotNull(AdultCards.firstTimeCard(focus, isChild = true, seen = setOf(AdultCardKind.Words)))
        // A lesson with no card never asks.
        assertNull(AdultCards.firstTimeCard(SessionFocus.Warmups(listOf("zambet"), breath = false), isChild = true, seen = emptySet()))
    }

    @Test
    fun theMirrorHasItsOwnCard() {
        assertEquals("Oglinda", AdultCards.card(AdultCardKind.Mirror).title)
        assertEquals(AdultCardKind.Mirror, AdultCards.firstTimeMirrorCard(isChild = true, seen = emptySet())?.kind)
        assertNull(AdultCards.firstTimeMirrorCard(isChild = true, seen = setOf(AdultCardKind.Mirror)))
    }

    @Test
    fun whatAProfileHasMetIsKeptUnderItsOwnKey() {
        val ana = UUID.randomUUID()
        val mihai = UUID.randomUUID()
        assertTrue(AdultCardsSeen.key(ana).startsWith("fonia."))
        assertTrue(AdultCardsSeen.key(ana) != AdultCardsSeen.key(mihai))
        val stored = AdultCardsSeen.encode(setOf(AdultCardKind.Words, AdultCardKind.Mirror))
        assertEquals("mirror,words", stored)
        assertEquals(setOf(AdultCardKind.Words, AdultCardKind.Mirror), AdultCardsSeen.decode(stored))
        assertEquals(emptySet<AdultCardKind>(), AdultCardsSeen.decode(""))
        // A value from a later version is ignored.
        assertEquals(setOf(AdultCardKind.Words), AdultCardsSeen.decode("words,something-new"))
        // Without preferences (the previews) nothing is met and nothing breaks.
        assertEquals(emptySet<AdultCardKind>(), AdultCardsSeen.seen(ana, null))
        AdultCardsSeen.markSeen(AdultCardKind.Words, ana, null)
        AdultCardsSeen.forget(ana, null)
    }

    @Test
    fun everyLevelHasOneOfTheFourStageNamesInOrder() {
        val expected = listOf(
            Rung.Isolated to "Emitere",
            Rung.Syllables to "Consolidare",
            Rung.WordsInitial to "Consolidare",
            Rung.WordsMedial to "Consolidare",
            Rung.WordsFinal to "Consolidare",
            Rung.Clusters to "Consolidare",
            Rung.Pairs to "Diferențiere",
            Rung.Sentences to "Automatizare",
            Rung.Retell to "Automatizare",
            Rung.Everyday to "Automatizare",
        )
        assertEquals(Rung.entries.size, expected.size)
        for ((rung, name) in expected) assertEquals("$rung", name, AdultCards.stageName(rung))
        for (stage in Stage.entries) assertTrue("$stage", StageNames.name(stage) in AdultCards.STAGE_NAMES)
        val positions = Rung.entries.map { AdultCards.STAGE_NAMES.indexOf(AdultCards.stageName(it)) }
        assertEquals(positions.sorted(), positions)
        assertEquals(AdultCards.STAGE_NAMES.indices.toSet(), positions.toSet())
        assertEquals("Etapa: Diferențiere.", AdultCards.stageLine(AdultCards.stageName(Rung.Pairs)))
    }
}
