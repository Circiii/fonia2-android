package tech.fonia.features.speaking

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.domain.Language
import tech.fonia.features.reading.CopyRules
import tech.fonia.features.speaking.bac.BacContent
import tech.fonia.features.speaking.emphasis.EmphasisContent
import kotlin.random.Random

/**
 * The draft content of the two exercises, checked for what a machine can check before a teacher reads it: drafts are
 * marked as drafts, every sentence has its key word, every ticket has the form of the exam, the texts are long enough
 * to read for a minute and carry no digits (the recogniser writes numbers its own way), and the copy follows
 * `tools/lint_copy.py`, whose JSON check covers only `content/json`.
 */
class SpeakingContentTests {
    private fun strings(element: JsonElement): List<String> = when (element) {
        is JsonPrimitive -> if (element.isString) listOf(element.content) else emptyList()
        is JsonObject -> element.values.flatMap(::strings)
        is JsonArray -> element.flatMap(::strings)
    }

    private fun checkCopy(path: String) {
        val text = assetText(path)
        for (value in strings(Json.parseToJsonElement(text))) {
            assertEquals("$path: \"$value\"", emptyList<String>(), CopyRules.problems(value))
            assertFalse("$path: semicolon in \"$value\"", ';' in value)
        }
    }

    @Test
    fun emphasisSentencesAreADraftWithOneOrTwoKeyWordsEach() {
        val content = emphasisContent
        assertEquals("ciornă", content.status)
        assertNull(content.approvedBy)
        assertTrue(content.isDraft)
        assertTrue(content.notice, "Ciornă" in content.notice && "profesor" in content.notice)
        assertTrue(content.items(Language.Ro).size >= 12)
        assertTrue(content.items(Language.En).size >= 8)
        assertEquals(content.items.size, content.items.map { it.id }.toSet().size)
        for (item in content.items) {
            val keys = item.text.keyIndices
            assertTrue(item.id, keys.size in 1..2)
            assertTrue(item.id, item.text.words.size in 3..8)
            assertTrue(item.id, item.context!!.endsWith("?"))
            for (key in keys) assertTrue(item.id, item.text.words[key].bare.all { it.isLetter() })
            assertFalse(item.id, item.text.plain.any { it.isDigit() })
        }
        checkCopy(EmphasisContent.ASSET)
    }

    @Test
    fun ticketsHaveTheFormOfTheExam() {
        val content = bacContent
        assertEquals("ciornă", content.status)
        assertNull(content.approvedBy)
        assertTrue(content.notice, "Nu sunt bilete oficiale" in content.notice)
        assertEquals(30, content.preparationMinutes)
        assertEquals(listOf("utilizator mediu", "utilizator avansat", "utilizator experimentat"), content.levels.map { it.name })
        assertEquals(6, content.tickets.size)
        assertEquals((1..6).toList(), content.tickets.map { it.number })
        assertEquals(6, content.tickets.map { it.id }.toSet().size)
        for (ticket in content.tickets) {
            assertTrue(ticket.id, ticket.kind in setOf("text literar", "text nonliterar"))
            assertEquals(ticket.id, 3, ticket.tasks.size)
            // The last task asks for an opinion and for arguments.
            assertTrue(ticket.id, "argumente" in ticket.tasks[2])
            assertTrue(ticket.id, "opinia" in ticket.tasks[2] || "punctul de vedere" in ticket.tasks[2])
            val words = ticket.text.words.size
            assertTrue("${ticket.id}: $words words", words in 100..160)
            assertTrue(ticket.id, ticket.text.keyIndices.size in 3..6)
            assertTrue(ticket.id, ticket.text.sentences.size >= 6)
            assertFalse(ticket.id, ticket.text.plain.any { it.isDigit() })
        }
        assertEquals(2, content.tickets.count { it.kind == "text literar" })
        checkCopy(BacContent.ASSET)
    }

    @Test
    fun aNewTicketIsNotTheLastOne() {
        val content = bacContent
        val random = Random(7)
        repeat(50) {
            val last = content.tickets[it % content.tickets.size].id
            val drawn = content.draw(last, random)
            assertTrue(drawn.id != last)
        }
        assertEquals(6, (0 until 200).map { content.draw(null, random).id }.toSet().size)
    }
}
