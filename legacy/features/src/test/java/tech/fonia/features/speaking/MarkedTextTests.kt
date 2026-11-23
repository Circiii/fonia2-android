package tech.fonia.features.speaking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.speech.SpeechLanguage
import tech.fonia.speech.SpeechText

/** The text model shared by the emphasis sentences and the BAC tickets: key words, punctuation, sentences. */
class MarkedTextTests {
    @Test
    fun readsKeyWordsAndPunctuation() {
        val text = MarkedText.parse("Nu, trenul pleacă *azi*.")
        assertEquals(listOf("Nu,", "trenul", "pleacă", "azi."), text.words.map { it.shown })
        assertEquals(setOf(3), text.keyIndices)
        assertEquals(listOf(',', null, null, '.'), text.words.map { it.mark })
        assertEquals("Nu, trenul pleacă azi.", text.plain)
        assertEquals("azi", text.words[3].bare)
        assertEquals("azi", text.words[3].normalized(SpeechLanguage.RO))
    }

    @Test
    fun aMarkPastAClosingQuoteStillCounts() {
        val text = MarkedText.parse("„Lasă, că *trece*”, a spus ea.")
        assertEquals(listOf("„Lasă,", "că", "trece”,", "a", "spus", "ea."), text.words.map { it.shown })
        assertEquals(setOf(2), text.keyIndices)
        assertEquals(listOf(',', null, ',', null, null, '.'), text.words.map { it.mark })
        assertEquals(',', MarkedText.pauseMark("trece”,"))
        assertEquals('!', MarkedText.pauseMark("așteptăm!"))
        assertEquals(':', MarkedText.pauseMark("cunoscute:"))
        assertNull(MarkedText.pauseMark("„Lasă"))
    }

    @Test
    fun paragraphsAndSentences() {
        val text = MarkedText.parse("Dragi colegi,\nvă scriu în numele *clubului*. Vom învăța împreună.\nCu prietenie,\nIoana")
        assertEquals(4, text.paragraphs.size)
        assertTrue(text.words[1].endsParagraph)
        assertTrue(text.words[1].endsSentence)
        assertEquals(
            listOf("Dragi colegi,", "vă scriu în numele clubului.", "Vom învăța împreună.", "Cu prietenie,", "Ioana"),
            text.sentences.map { it.text },
        )
        assertEquals("Dragi colegi,\nvă scriu în numele clubului. Vom învăța împreună.\nCu prietenie,\nIoana", text.plain)
    }

    @Test
    fun theReaderCanMarkOtherKeyWords() {
        val text = MarkedText.parse("Avem *trei* idei pentru proiect.")
        val mine = text.withKeys(setOf(2, 4))
        assertEquals(setOf(2, 4), mine.keyIndices)
        assertEquals(text.plain, mine.plain)
        assertFalse(mine.words[1].isKey)
    }

    @Test
    fun everySentenceSplitsBackIntoItsWords() {
        // A sentence goes to the recogniser as its own text; its words must come back one to one with the text's.
        val texts = bacContent.tickets.map { it.text } + emphasisContent.items.map { it.text }
        for (text in texts) {
            for (sentence in text.sentences) {
                val again = SpeechText.rawWords(sentence.text).filter { SpeechText.normalizedWord(it, SpeechLanguage.RO).isNotEmpty() }
                assertEquals(sentence.text, text.words.subList(sentence.first, sentence.last + 1).map { it.shown }, again)
            }
            assertEquals(text.words.indices.toList(), text.sentences.flatMap { it.indices })
        }
    }
}
