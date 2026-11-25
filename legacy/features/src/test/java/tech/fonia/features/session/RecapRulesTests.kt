package tech.fonia.features.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The end of a session: a child sees one word and the longest sound, never "N au ieșit bine din M". */
class RecapRulesTests {
    @Test
    fun theCountIsForAdultsOnly() {
        assertTrue(RecapRules.showsCount(isChild = false, attempts = 8, good = 5))
        assertFalse(RecapRules.showsCount(isChild = true, attempts = 8, good = 5))
    }

    @Test
    fun neverZeroOfN() {
        assertFalse(RecapRules.showsCount(isChild = false, attempts = 8, good = 0))
        assertFalse(RecapRules.showsCount(isChild = false, attempts = 0, good = 0))
    }

    @Test
    fun theLineForTheLockIsShortAndKind() {
        val words = RecapRules.WAITS_FOR_ADULT.trimEnd('.').split(" ")
        assertTrue(words.size <= 8)
    }

    @Test
    fun aLessonDoneToTheEndIsPraisedEvenWithNothingToSay() {
        assertEquals("Bravo!", RecapRules.title(attempts = 0, good = 0))
        assertEquals("Bravo!", RecapRules.title(attempts = 6, good = 2))
        assertEquals("Ai exersat!", RecapRules.title(attempts = 6, good = 0))
    }

    @Test
    fun whatComesNextNamesItsSound() {
        assertEquals("Urmează la R: Facem cunoștință", RecapRules.nextLine("Facem cunoștință", sound = "R"))
        assertEquals("Urmează: Lumânările", RecapRules.nextLine("Lumânările"))
        assertNull(RecapRules.nextLine(null, sound = "R"))
    }
}
