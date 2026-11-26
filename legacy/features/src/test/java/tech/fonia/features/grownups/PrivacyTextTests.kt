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
    fun theFirstRunSaysFourLines() {
        assertEquals(4, PrivacyText.summary.size)
        assertEquals("Vocea ta rămâne pe telefon.", PrivacyText.summary[0])
        assertEquals(
            "După o zi, trimitem o dată un cod făcut la întâmplare, ca să știm câte instalări sunt. Îl poți opri din Setări.",
            PrivacyText.summary[2],
        )
        // An adult learner's setup page says the same line.
        assertEquals(PrivacyText.INSTALL_LINE, PrivacyText.summary[2])
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
    fun noDownloadIsPromisedAndTheInstallCodeIsTheOnlyThingSent() {
        assertFalse(text.contains("huggingface"))
        assertFalse(text.contains("descărca"))
        assertFalse(text.contains("Fonia nu folosește internetul."))
        val internet = all.single { it.title == "Internetul" }.body
        assertTrue(internet.contains("Pe internet trimite doar codul de la „Numărul de instalări”"))
        assertTrue(internet.contains("nimic altceva"))
    }

    /** iOS `PrivacyText`'s section, with "android" where iOS says "ios". */
    @Test
    fun theInstallCodeIsSaidAsOnIOS() {
        val section = all.single { it.title == "Numărul de instalări" }
        assertEquals(
            "La o zi după primul profil, Fonia trimite o dată un cod făcut la întâmplare pe telefon și cuvântul „android”, ca să știm cam câte instalări sunt. Codul nu e legat de niciun profil și nu spune nimic despre tine sau despre exerciții. Ajunge într-o bază de date Supabase din Frankfurt. Ca la orice conexiune, serverul vede atunci adresa IP a telefonului. Jurnalele tehnice ale serverului o țin câteva zile, iar în tabel nu o păstrăm. Până pleacă, îl poți opri din Setări, la „Numără instalarea”. „Șterge tot” scoate codul și alegerea de pe telefon, iar Fonia o ia de la capăt ca o instalare nouă.",
            section.body,
        )
        assertFalse(text.contains("„ios”"))
        val titles = all.map { it.title }
        assertEquals(titles.indexOf("Ștergerea") - 1, titles.indexOf("Numărul de instalări"))
        assertTrue(PrivacyText.sections(withReading = false).any { it.title == "Numărul de instalări" })
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
    fun theAppAsksForTheInternetButNotForTheNetworkState() {
        val manifest = File("../app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("""<uses-permission android:name="android.permission.INTERNET" />"""))
        assertFalse(manifest.contains("""android.permission.INTERNET" tools:node="remove""""))
        // Without the network state the model fetchers find no network: the install code stays the one thing sent.
        assertTrue(manifest.contains("""android.permission.ACCESS_NETWORK_STATE" tools:node="remove""""))
    }
}
