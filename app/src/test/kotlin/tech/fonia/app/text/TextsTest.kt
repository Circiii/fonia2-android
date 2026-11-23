package tech.fonia.app.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.model.ErrorKind
import tech.fonia.model.SoundId
import java.io.File

class TextsTest {
    @Test fun plurals() {
        assertEquals("1 încercare", Plural.attempts(1))
        assertEquals("3 încercări", Plural.attempts(3))
        assertEquals("19 încercări", Plural.attempts(19))
        assertEquals("20 de încercări", Plural.attempts(20))
        assertEquals("101 încercări", Plural.attempts(101))
        assertEquals("1.000 de încercări", Plural.attempts(1000))
        assertEquals("0 încercări", Plural.attempts(0))
        assertEquals("o dată", Plural.times(1))
        assertEquals("de 5 ori", Plural.times(5))
        assertEquals("de 20 de ori", Plural.times(20))
        assertEquals("2,8 secunde", Plural.seconds(2.8))
        assertEquals("1 secundă", Plural.seconds(1.0))
        assertEquals("3 secunde", Plural.seconds(3.0))
    }

    @Test fun childLinesAreAtMostEightWords() {
        val lines = SoundId.entries.map(T::holdLine) +
            SoundId.entries.flatMap { s -> ErrorKind.entries.map { T.instruction(it, s) } } +
            listOf(T.wordsLine, T.almostLine, T.unsureLine, T.retakeLine, T.quietLine, T.pathPick, T.endDone, T.endShort) +
            SoundId.entries.flatMap { listOf(T.correctLine(it, true), T.correctLine(it, false)) }
        for (l in lines) {
            val words = l.split(Regex("\\s+")).count { w -> w.any { it.isLetter() } }
            assertTrue("'$l' has $words words", words <= 8)
        }
    }

    @Test fun noForbiddenWordsAndCommaBelowOnly() {
        val src = File(System.getProperty("fonia.root") ?: "..", "app/src/main/kotlin/tech/fonia/app/text/T.kt").readText()
            .lines()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/**") }
            .joinToString("\n") { it.substringBefore(" // ") }
        val forbidden = listOf(
            "greșit", "greșeal", "diagnostic", "terapi", "tratament", "tratează", "corectează", "clinic", "risc",
            "severitate", "tulburare", "scor", "procent", "screening", "evaluare", "ai pierdut", "grăbește",
        )
        val strings = Regex("\"([^\"]*)\"").findAll(src).map { it.groupValues[1].lowercase() }.toList()
        for (s in strings) for (f in forbidden) assertTrue("forbidden '$f' in \"$s\"", !s.contains(f))
        assertTrue("cedilla Ş/Ţ found", src.none { it in "ŞşŢţ" })
    }
}
