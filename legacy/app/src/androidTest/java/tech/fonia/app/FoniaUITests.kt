package tech.fonia.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `FoniaUITests.swift`: the first run, the doors on Home, the tabs, the big button, a real session from its teaching
 * steps to its first listening exercise, the journal, and the grown-ups area behind the adult check (`foniaGate`). Each test starts the
 * real app with `foniaResetData` (iOS `-foniaResetData`); in the iOS simulator a take comes from the recorded clip of
 * the target, and `foniaFixtures` does the same here (see [Journeys]).
 */
@RunWith(AndroidJUnit4::class)
class FoniaUITests : Journeys() {
    @Before
    fun setUp() {
        launch()
    }

    @Test
    fun testChildOpensOnHomeAndChoosesFromTheDoors() {
        reachChildHome()

        for (name in listOf("Acasă", "Progres", "Începe", "Sunete", "Încălzirea", "Jocuri", "Cum spun?", "Povești")) {
            assertTrue(name, exists(button(name)))
        }
        assertFalse(exists(button("Vorbit în public")))

        // Nothing starts on its own.
        assertFalse(exists(button("Mă opresc")))

        tap(button("Jocuri"))
        assertTrue(waitFor(button("Urechi de detectiv")))
        goBack()
        assertTrue(waitFor(button("Sunete")))
    }

    @Test
    fun testTheBigButtonOpensTheNextLesson() {
        reachChildHome()

        // One tap continues today's sound; the sheet with the warm-up shows only when nothing is left.
        tap(button("Începe"))
        assertTrue(waitFor(button("Mă opresc"), 10))
    }

    @Test
    fun testTheWarmUpDoorOpensItsExercises() {
        reachChildHome()

        tap(button("Încălzirea"))
        assertTrue(waitFor(button("Începem")))
        assertTrue(exists(button("Exercițiul")))
    }

    @Test
    fun testSessionMovesFromTeachingToListening() {
        reachChildHome()
        tap(button("Sunete"))

        val unit = button("Sunetul S")
        assertTrue(waitFor(unit))
        tap(unit)

        val lesson = buttonStartingWith("Doar sunetul")
        assertTrue(waitFor(lesson))
        tap(lesson)

        assertTrue(waitFor(button("Mai departe"), 10))
        tap(button("Mai departe"))

        assertTrue(waitFor(button("Încerc"), 10))
        tap(button("Încerc"))

        assertTrue(waitFor(button("Ascultă din nou"), 10))
    }

    @Test
    fun testEveryDoorOnHomeOpens() {
        reachChildHome()

        for ((door, inside) in listOf(
            "Sunete" to "Sunetele tale",
            "Încălzirea" to "Începem",
            "Cum spun?" to "Cum spun S?",
            "Povești" to "Sâsâilă",
        )) {
            tap(button(door))
            val found = exists(button(inside)) || exists(staticText(inside)) ||
                waitFor(button(inside), 5) || waitFor(staticText(inside), 1) || waitFor(buttonStartingWith(inside), 1)
            assertTrue(door, found)
            goBack()
            assertTrue(door, waitFor(button(door), 5))
        }
    }

    @Test
    fun testAJournalNoteAsksForAnAdult() {
        reachChildHome()
        tap(button("Progres"))
        assertTrue(waitFor(buttonStartingWith("Săptămâna asta")))
        tap(buttonStartingWith("Săptămâna asta"))

        val plus = button("Adaugă o notiță pentru logoped")
        assertTrue(waitFor(plus))
        tap(plus)

        passAdultCheck()
        assertTrue(waitFor(staticText("Notițe pentru logoped")))
    }

    @Test
    fun testSettingsOpenBehindTheCheckAndStayOpenWhileInUse() {
        reachChildHome()
        tap(button("Progres"))
        assertTrue(waitFor(button("Setări")))
        tap(button("Setări"))

        assertTrue(waitForAdultCheck())
        assertFalse(exists(buttonStartingWith("Cum merge")))
        passAdultCheck()

        assertTrue(waitFor(buttonStartingWith("Cum merge")))
        assertTrue(exists(buttonStartingWith("Notițe pentru logoped")))

        // Once passed it stays open while the app is in use (three minutes in the background close it: AdultGateTests).
        tap(button("Închide"))
        assertTrue(waitFor(button("Setări")))
        tap(button("Setări"))
        assertTrue(waitFor(buttonStartingWith("Cum merge")))
        assertFalse(exists(button(HOLD_LABEL)))
    }

    @Test
    fun testAWrongNumberBringsANewOneAndNoWrongWord() {
        reachChildHome()
        tap(button("Progres"))
        assertTrue(waitFor(button("Setări")))
        tap(button("Setări"))

        assertTrue(waitForAdultCheck())
        rule.onAllNodes(button(HOLD_LABEL)).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(waitFor(staticText("patruzeci și șapte")))
        tap(button("1"))
        tap(button("2"))
        assertTrue(waitFor(staticText("Un număr nou. Scrie-l cu cifre:")))
        assertFalse(exists(buttonStartingWith("Cum merge")))
        for (word in listOf("Greșit", "greșit")) assertFalse(exists(staticText(word)))
        tap(button("4"))
        tap(button("7"))
        assertTrue(waitFor(buttonStartingWith("Cum merge")))
    }

    @Test
    fun testEveryPageBehindTheCheckOpens() {
        reachChildHome()
        tap(button("Progres"))
        assertTrue(waitFor(button("Setări")))
        tap(button("Setări"))
        passAdultCheck()

        for ((row, title) in listOf(
            "Cum merge" to "Cum merge",
            "Notițe pentru logoped" to "Notițe pentru logoped",
            "Ce sunete exersează" to "Ce sunete exersează",
            "Cine exersează" to "Cine exersează",
        )) {
            val link = buttonStartingWith(row)
            assertTrue(row, waitFor(link))
            tap(link)
            assertTrue(title, waitFor(staticText(title)))
            goBack()
        }
    }

    @Test
    fun testAdultFirstRunReachesHome() {
        passWelcome()

        assertTrue(waitFor(button("Eu, Pronunție în engleză, dicție, vorbit în public")))
        tap(button("Eu, Pronunție în engleză, dicție, vorbit în public"))

        assertTrue(waitFor(button("Gata")))
        tap(button("Gata"))

        assertTrue(waitFor(button("Vorbit în public")))
        for (name in listOf("Acasă", "Progres", "Începe", "Engleză")) {
            assertTrue(name, exists(button(name)))
        }
        assertFalse(exists(button("Povești")))

        // Each door goes where it says.
        for ((door, title) in listOf("Engleză" to "Engleză", "Vorbit în public" to "Vorbit în public")) {
            tap(button(door))
            assertTrue(title, waitFor(staticText(title)))
            goBack()
            assertTrue(door, waitFor(button(door)))
        }

        // With no child on the phone the settings open straight away, with no check.
        tap(button("Progres"))
        assertTrue(waitFor(button("Setări")))
        tap(button("Setări"))
        assertTrue(waitFor(buttonStartingWith("Notițe")))
        assertFalse(exists(button(HOLD_LABEL)))
    }
}
