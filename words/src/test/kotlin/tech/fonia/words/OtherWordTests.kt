package tech.fonia.words

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OtherWordTests {
    private fun heard(text: String, confidence: Double = 0.9) =
        Heard(text.split(' ').filter { it.isNotEmpty() }.map { HeardWord(it, 0.0, 0.3, confidence) }, 0.4, confidence, 1.0)

    @Test
    fun anotherWordIsRefused() {
        assertTrue(OtherWord.isOther("pară", "r", heard("val")))
        assertTrue(OtherWord.isOther("rac", "r", heard("balon")))
        assertTrue(OtherWord.isOther("sanie", "s", heard("mașină")))
        assertTrue(OtherWord.isOther("lună", "l", heard("casa")))
    }

    @Test
    fun theSameWordWithAnotherSoundIsNotAnotherWord() {
        // The practised sound is the sound judge's business, never the recogniser's.
        assertFalse(OtherWord.isOther("rac", "r", heard("lac")))
        assertFalse(OtherWord.isOther("rac", "r", heard("iac")))
        assertFalse(OtherWord.isOther("rac", "r", heard("ac")))
        assertFalse(OtherWord.isOther("sanie", "s", heard("șanie")))
        assertFalse(OtherWord.isOther("țap", "ț", heard("tsap")))
        assertFalse(OtherWord.isOther("cer", "ce", heard("ser")))
        assertFalse(OtherWord.isOther("ger", "ge", heard("jer")))
        assertFalse(OtherWord.isOther("mure", "r", heard("mule")))
    }

    @Test
    fun aSmallSlipOrAnUnsureRecogniserRefusesNothing() {
        assertFalse(OtherWord.isOther("pară", "r", heard("para")))
        assertFalse(OtherWord.isOther("pară", "r", heard("pală")))
        assertFalse(OtherWord.isOther("mașină", "ș", heard("masina")))
        assertFalse(OtherWord.isOther("pară", "r", heard("val", confidence = 0.4)))
        assertFalse(OtherWord.isOther("pară", "r", Heard.nothing))
        // A breath or a filler beside the word does not make it another word.
        assertFalse(OtherWord.isOther("rac", "r", heard("ă rac")))
    }
}
