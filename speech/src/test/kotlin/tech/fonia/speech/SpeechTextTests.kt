package tech.fonia.speech

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FoniaSpeechTests/SpeechTextTests.swift, suite "Text normalisation". */
@DisplayName("Text normalisation")
class SpeechTextTests {
    @ParameterizedTest
    @MethodSource("normalizations")
    @DisplayName("strips punctuation and case, keeps inner apostrophes and hyphens")
    fun normalizes(input: String, language: SpeechLanguage, expected: String) {
        assertEquals(expected, SpeechText.normalizedWord(input, language))
    }

    @Test
    @DisplayName("maps cedilla diacritics and number words")
    fun cedillasAndNumbers() {
        assertEquals("6", SpeechText.normalizedWord(CEDILLA_S + "ase", SpeechLanguage.RO))
        assertEquals("3", SpeechText.normalizedWord("Three"))
        assertEquals("3", SpeechText.normalizedWord("3"))
        assertEquals(listOf("i", "think", "3", "things"), SpeechText.words("I think " + EN_DASH + " three things!"))
    }

    @Test
    @DisplayName("measures spelling distance")
    fun distance() {
        assertEquals(0.0, SpeechText.wordDistance("ship", "ship"))
        assertTrue(abs(SpeechText.wordDistance("ship", "sheep") - 0.4) < 1e-9)
    }

    @Test
    @DisplayName("treats endings as near misses but never a changed first sound")
    fun nearMisses() {
        assertTrue(SpeechText.nearMiss(target = "things", heard = "thing"))
        assertTrue(SpeechText.nearMiss(target = "walk", heard = "walked"))
        assertFalse(SpeechText.nearMiss(target = "things", heard = "sings"))
        assertFalse(SpeechText.nearMiss(target = "three", heard = "tree"))
        assertFalse(SpeechText.nearMiss(target = "sheep", heard = "ship"))
    }

    @Test
    @DisplayName("aligns substitutions one to one")
    fun alignsSubstitutions() {
        val alignment = SpeechText.alignWords(target = listOf("i", "think", "3", "things"), heard = listOf("i", "sink", "tree", "sings"))
        assertEquals((0 until 4).map { WordAlignment(target = it, heard = it) }, alignment)
    }

    @Test
    @DisplayName("finds a dropped and an extra word")
    fun alignsGaps() {
        val alignment = SpeechText.alignWords(
            target = listOf("the", "sheep", "is", "on", "the", "ship"),
            heard = listOf("the", "the", "sheep", "is", "on", "ship"),
        )
        assertEquals(1, alignment.count { it.target == null })
        assertEquals(1, alignment.count { it.heard == null })
    }

    @Test
    @DisplayName("scores each candidate over its spellings, whole sentences as written")
    fun variants() {
        assertEquals(
            PairVariants(target = listOf("sheep", "Sheep", "Sheep."), rival = listOf("ship", "Ship", "Ship.")),
            SpeechText.pairVariants(target = "sheep", rival = "ship"),
        )
        assertEquals(
            PairVariants(target = listOf("The sheep is here."), rival = listOf("The ship is here.")),
            SpeechText.pairVariants(target = "The sheep is here.", rival = "The ship is here."),
        )
    }

    @Test
    @DisplayName("has no variants for stress pairs, which are the same text")
    fun stressPairs() {
        assertNull(SpeechText.pairVariants(target = "ANimal", rival = "aniMAL"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["um", "uhm", "uh", "erm", "hmm", "mmm", "ăă", "ăăă", "îî", "ââ"])
    @DisplayName("recognises hesitation sounds")
    fun hesitations(word: String) {
        assertTrue(SpeechText.isHesitationSound(word))
    }

    @ParameterizedTest
    @ValueSource(strings = ["us", "ham", "are", "mă", "am"])
    @DisplayName("does not mistake words for hesitation sounds")
    fun notHesitations(word: String) {
        assertFalse(SpeechText.isHesitationSound(word))
    }

    companion object {
        /** S with cedilla (U+015F), which Romanian text should not have but often does. */
        private val CEDILLA_S = String(Character.toChars(0x015F))
        private val EN_DASH = String(Character.toChars(0x2013))

        @JvmStatic
        fun normalizations(): List<Arguments> = listOf(
            Arguments.of("Ship.", SpeechLanguage.EN, "ship"),
            Arguments.of(String(Character.toChars(0x201C)) + "Don" + String(Character.toChars(0x2019)) + "t," + String(Character.toChars(0x201D)), SpeechLanguage.EN, "don't"),
            Arguments.of("Mi-ar", SpeechLanguage.RO, "mi-ar"),
        )
    }
}
