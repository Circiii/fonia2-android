package tech.fonia.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Journeys through the second half of the screens, which iOS has no UI test for: a whole session from the recorded
 * takes to its end screen, the sound guide, the mouth coach with a scripted face, and a public-speaking talk with its
 * report. Everything runs on the real engines; only the microphone is replaced by the recorded clips (`foniaFixtures`)
 * and the camera by a face held still (`foniaFace`).
 */
@RunWith(AndroidJUnit4::class)
class SecondHalfJourneys : Journeys() {
    /** Answers whatever the session asks of a person, so the run reaches its end on its own. */
    private fun answerIfAsked() {
        for (label in listOf("Bine", "Mai departe", "Am făcut", "Ne-am înțeles")) {
            if (exists(button(label))) {
                tap(button(label))
                return
            }
        }
    }

    @Test
    fun testASessionRunsFromTheTakesToItsEnd() {
        // Two spoken trials of the syllables rung, taken from the recorded clips and judged on the phone.
        launch {
            putExtra("foniaStart", "s:syllables")
            putExtra("foniaSpeakOnly", true)
            putExtra("foniaShort", true)
        }
        // A debug launch with `foniaStart` makes its own child (S, 6-7 ani) and skips the first run.
        assertTrue(waitFor(button("Mă opresc"), 15))

        // The recap: "Gata" next to "Încă una" when more is waiting, otherwise the session's own end button.
        val end = button("Gata") or button("Gata pe azi")
        val deadline = System.currentTimeMillis() + 60_000
        while (!exists(end) && System.currentTimeMillis() < deadline) {
            if (!waitFor(end, 3)) answerIfAsked()
        }
        assertTrue(exists(end))
        assertTrue(exists(staticText("Bravo!")) || exists(staticText("Ai exersat!")))
        tap(end)
        assertTrue(waitFor(button("Acasă"), 10))
        assertFalse(exists(button("Mă opresc")))
    }

    @Test
    fun testLeavingASessionAsksFirstOnceSomethingCounted() {
        launch {
            putExtra("foniaStart", "s:syllables")
            putExtra("foniaSpeakOnly", true)
        }
        assertTrue(waitFor(button("Mă opresc"), 15))
        // Nothing counted yet: the bar is empty and says so in words („Abia începem”, no percentages for a child).
        // Once a take is judged, it moves.
        val moved = SemanticsMatcher("the bar has moved") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Cât am lucrat azi") == true &&
                node.config.getOrNull(SemanticsProperties.StateDescription).let { it != null && it != "Abia începem" }
        }
        val deadline = System.currentTimeMillis() + 40_000
        while (!exists(moved) && System.currentTimeMillis() < deadline) {
            if (!waitFor(moved, 3)) answerIfAsked()
        }
        assertTrue(exists(moved))

        tap(button("Mă opresc"))
        assertTrue(waitFor(staticText("Te oprești aici?")))
        assertTrue(exists(staticText("Ce ai lucrat până acum se păstrează.")))
        tap(button("Mai rămân"))
        assertTrue(waitFor(button("Mă opresc")))
        assertFalse(exists(staticText("Te oprești aici?")))
    }

    @Test
    fun testTheSoundGuideShowsEachStepAndLeadsToTheMirror() {
        // The mirror at the end asks for the camera; a face held still stands in for it, as everywhere in these journeys,
        // so the system's permission dialog never covers the app.
        launch { putExtra("foniaFace", "jawOpen=0.1") }
        reachChildHome()
        tap(button("Cum spun?"))
        assertTrue(waitFor(buttonStartingWith("Cum spun S?")))
        tap(buttonStartingWith("Cum spun S?"))

        assertTrue(waitFor(staticText("Cum spun S?")))
        assertTrue(exists(staticText("Dacă nu iese")) || waitFor(staticText("Pentru adult"), 2))
        val second = buttonStartingWith("2, ")
        assertTrue(waitFor(second))
        tap(second)
        assertTrue(waitFor(second and SemanticsMatcher("selected") { it.config.getOrNull(SemanticsProperties.Selected) == true }))
        tap(button("Ascultă"))
        tap(button("Mai rar"))

        tap(button("Încearcă"))
        assertTrue(waitFor(staticText("Încearcă S")))
        goBack()
        assertTrue(waitFor(staticText("Cum spun S?")))
        goBack()
        assertTrue(waitFor(button("Cum spun?")) || waitFor(buttonStartingWith("Cum spun S?")))
    }

    @Test
    fun testTheMouthCoachFollowsAScriptedFace() {
        // A wide smile with the teeth close, held still: the first two steps of S pass on their own.
        launch {
            putExtra("foniaFace", "smile=0.8,jawOpen=0.1")
            putExtra("foniaRoute", "mirror:s")
        }
        // The page opens once the first run is over.
        makeChild()
        passCalibration()
        leaveCheckOffer()
        assertTrue(waitFor(staticText("Încearcă S"), 10))
        assertTrue(waitFor(element("Pasul 1 din 4")))
        // Smile, then teeth: the camera checks them.
        assertTrue(waitFor(element("Pasul 3 din 4"), 30))
        // The tongue is the learner's to say.
        tap(button("Am făcut"))
        assertTrue(waitFor(element("Pasul 4 din 4")))
        // The sound, from the recorded take: judged, or handed on with "Mai departe".
        val done = staticText("Așa se spune S")
        if (!waitFor(done, 30)) {
            tap(button("Mai departe"))
            assertTrue(waitFor(done, 10))
        }
        tap(button("Gata"))
        assertTrue(waitFor(button("Acasă"), 10))
    }

    @Test
    fun testATalkEndsInAReport() {
        launch()
        reachAdultHome()
        tap(button("Vorbit în public"))
        assertTrue(waitFor(staticText("Vorbit în public")))

        val drill = buttonStartingWith("Pauză în loc de")
        assertTrue(waitFor(drill))
        tap(drill)
        assertTrue(waitFor(button("Începem")))
        tap(button("Începem"))
        assertTrue(waitFor(button("Am terminat"), 10))
        // The recorded talk lasts a few seconds.
        waitFor(staticText("0:05"), 8)
        tap(button("Am terminat"))

        assertTrue(waitFor(button("A mers bine"), 30))
        tap(button("A mers bine"))
        assertTrue(waitFor(button("Încă o dată, mai scurt"), 10))
        assertTrue(exists(staticText("Ritmul")))

        tap(button("Încă o dată, mai scurt"))
        assertTrue(waitFor(button("Începem")))
        assertTrue(exists(staticText("Ai 40 de secunde. Oprește-te când ai terminat.")))
        tap(button("Închide"))
        assertTrue(waitFor(staticText("Vorbit în public")))
    }
}
