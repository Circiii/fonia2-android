package tech.fonia.features.speaking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.features.speaking.bac.ConnectorRole
import tech.fonia.features.speaking.bac.Connectors
import tech.fonia.speech.Transcript
import tech.fonia.speech.TranscriptWord

/** The argued opinion read from its connectors with simple rules (V6): no model, and the reader can correct the count. */
class ArgumentsTests {
    private fun transcript(text: String): Transcript {
        val words = text.split(' ').filter { it.isNotBlank() }.mapIndexed { i, word -> TranscriptWord(word, i * 0.3, i * 0.3 + 0.25, 0.9) }
        return Transcript(text, words, 0.02, -3.0)
    }

    @Test
    fun theUsualStructure() {
        val count = Connectors.find(
            transcript(
                "În opinia mea, orele ar trebui să înceapă mai târziu. În primul rând, elevii ar dormi mai mult. " +
                    "În al doilea rând, ar fi mai atenți la prima oră, de exemplu la matematică. În concluzie, cred că merită încercat.",
            ),
        )
        assertEquals(2, count.arguments)
        assertTrue(count.hasThesis)
        assertTrue(count.hasConclusion)
        assertEquals(listOf("în opinia mea", "cred că"), count.phrases(ConnectorRole.Thesis))
        assertEquals(listOf("în primul rând", "în al doilea rând"), count.phrases(ConnectorRole.Argument))
        assertEquals(listOf("de exemplu"), count.phrases(ConnectorRole.Example))
        assertEquals(listOf("în concluzie"), count.phrases(ConnectorRole.Conclusion))
        val first = count.hits.first { it.role == ConnectorRole.Argument }
        assertEquals(10, first.word)
        assertEquals(3.0, first.start, 1e-9)
    }

    @Test
    fun aReasonBeforeAnyConnectorIsTheFirstArgument() {
        val count = Connectors.find(transcript("Cred că da, pentru că dormim prea puțin. De asemenea, dimineața nu ne putem concentra."))
        assertEquals(2, count.arguments)
        assertFalse(count.hasConclusion)
        // Two reasons in a row and no connector: counted once, and the reader corrects it.
        assertEquals(1, Connectors.find(transcript("Cred că da, pentru că dormim puțin și pentru că suntem obosiți.")).arguments)
    }

    @Test
    fun theRecogniserLeavingOutDiacriticsChangesNothing() {
        val count = Connectors.find(transcript("in opinia mea e bine. in primul rand e sanatos. in al doilea rand e ieftin. asadar merita."))
        assertEquals(2, count.arguments)
        assertTrue(count.hasThesis)
        assertEquals(listOf("așadar"), count.phrases(ConnectorRole.Conclusion))
    }

    @Test
    fun ambiguousConnectorsCountOnlyAtTheStartOfAClause() {
        assertEquals(0, Connectors.find(transcript("Eu citesc mai mult pe hârtie decât pe ecran.")).arguments)
        assertEquals(1, Connectors.find(transcript("Cartea e mai ușor de urmărit. Mai mult, nu obosește ochii.")).arguments)
        assertEquals(0, Connectors.find(transcript("Am primit ceva în plus la cumpărături.")).arguments)
        // The longest phrase wins: "nu în ultimul rând" is one connector, not two.
        val last = Connectors.find(transcript("Nu în ultimul rând, e mai ieftin."))
        assertEquals(listOf("nu în ultimul rând"), last.phrases(ConnectorRole.Argument))
        assertEquals(1, last.arguments)
    }

    @Test
    fun recordedFreeSpeechHasFillersButNoConnectors() {
        // "deci" and "practic" in the recorded clip are fillers there, and "deci" is no conclusion connector here.
        val take = Recorded.take("ro-speech")
        val count = Connectors.find(Transcript(take.words.joinToString(" ") { it.word }, take.words, 0.0, 0.0))
        assertEquals(0, count.arguments)
        assertFalse(count.hasThesis)
        assertFalse(count.hasConclusion)
    }

    @Test
    fun foldingKeepsLettersAndDropsMarks() {
        assertEquals("in", Connectors.fold("În"))
        assertEquals("asadar", Connectors.fold("așadar,"))
        assertEquals("intrucat", Connectors.fold("Întrucât"))
    }
}
