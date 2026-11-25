package tech.fonia.features.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** `ChildCountsTests.swift` (suite "Counts a child does not see"), with the same inputs and expectations. */
class ChildCountsTests {
    /** a child's today card and medals carry no count */
    @Test
    fun childSeesNoCount() {
        for ((done, total) in listOf(0 to 9, 3 to 9, 9 to 9)) {
            val shown = listOf(
                ChildCounts.todayCount(done, total, isChild = true),
                ChildCounts.todayLabel(done, total, isChild = true),
                ChildCounts.medalCaption(done, total, isChild = true),
                ChildCounts.medalValue(done, total, isChild = true),
            )
            for (text in shown.filterNotNull()) {
                assertFalse(text.contains(" din "))
                assertFalse(text.contains("/"))
                assertFalse(text.any { it.isDigit() })
            }
        }
    }

    /** an adult keeps the numbers */
    @Test
    fun adultKeepsNumbers() {
        assertEquals("3 din 9", ChildCounts.todayCount(3, 9, isChild = false))
        assertEquals("Ai terminat 3 din 9 lecții", ChildCounts.todayLabel(3, 9, isChild = false))
        assertEquals("3/9", ChildCounts.medalCaption(3, 9, isChild = false))
        assertEquals("3 din 9", ChildCounts.medalValue(3, 9, isChild = false))
    }
}
