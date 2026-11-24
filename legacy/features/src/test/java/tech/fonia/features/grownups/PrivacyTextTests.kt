package tech.fonia.features.grownups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The privacy text says what the Android app keeps, in iOS's words where the two keep the same things. */
class PrivacyTextTests {
    private val all = PrivacyText.sections(withReading = true)
    private val text = (PrivacyText.summary + all.flatMap { listOf(it.title, it.body) }).joinToString("\n")

    @Test
    fun theFirstRunSaysThreeLines() {
        assertEquals(3, PrivacyText.summary.size)
        assertEquals("Vocea ta rămâne pe telefon.", PrivacyText.summary[0])
    }

    @Test
    fun whoAnswersAndWhereThePolicyIs() {
        assertEquals("Dulgheru Ștefan Alexandru", LegalInfo.OPERATOR_NAME)
        assertEquals("stdulgh@gmail.com", LegalInfo.CONTACT_EMAIL)
        assertEquals("https://www.getfonia.com/confidentialitate", LegalInfo.PRIVACY_URL)
        assertEquals("https://www.getfonia.com/ajutor", LegalInfo.SUPPORT_URL)
        val contact = all.last()
        assertEquals("Contact", contact.title)
        for (part in listOf(LegalInfo.OPERATOR_NAME, LegalInfo.CONTACT_EMAIL, LegalInfo.PRIVACY_URL)) assertTrue(part, contact.body.contains(part))
        assertFalse(text.contains("[["))
    }

    @Test
    fun noDownloadIsPromisedByAnAppWithoutInternet() {
        assertFalse(text.contains("huggingface"))
        assertFalse(text.contains("descărca"))
        assertTrue(text.contains("Fonia nu folosește internetul."))
    }

    @Test
    fun theTextHasNoMedicalWordsAndUsesCommaBelow() {
        val lower = text.lowercase()
        for (word in listOf("diagnostic", "terapie", "tratează", "risc", "severitate", "tulburare")) assertFalse(word, lower.contains(word))
        assertFalse("cedilla letters", text.any { it in "şţŞŢ" })
    }

    @Test
    fun theReadingProbesAreNamedOnlyWhereTheyAreShown() {
        assertTrue(all.any { it.title == "Probele de citire" })
        val release = PrivacyText.sections(withReading = false)
        assertFalse(release.any { it.title == "Probele de citire" })
        assertFalse(release.joinToString { it.body }.contains("raportul de citire"))
    }

    @Test
    fun theAppStillAsksForNoInternet() {
        val manifest = File("../app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("""android.permission.INTERNET" tools:node="remove""""))
    }
}
