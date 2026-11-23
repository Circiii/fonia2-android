package tech.fonia.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `FoniaUITests.swift`, all nine journeys: the first run, the doors on Home, the tabs, the big button, a real session
 * from its teaching steps to its first listening exercise, the journal and the grown-ups area. Each test starts the
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
    fun testTheBigButtonAsksFirstThenOpensALesson() {
        reachChildHome()

        tap(button("Începe"))
        assertTrue(waitFor(button("Mă încălzesc")))
        assertFalse(exists(button("Mă opresc")))

        tap(button("Încep"))
        assertTrue(waitFor(button("Mă opresc"), 10))
    }

    @Test
    fun testTheWarmUpChoiceOpensALesson() {
        reachChildHome()

        tap(button("Începe"))
        assertTrue(waitFor(button("Mă încălzesc")))
        tap(button("Mă încălzesc"))
        assertTrue(waitFor(button("Mă opresc"), 10))
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
            "Povești" to "Șarpele Sâsâilă",
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

        assertTrue(waitFor(button("Sunt adult")))
        tap(button("Sunt adult"))
        assertTrue(waitFor(staticText("Notițe pentru logoped")))
    }

    @Test
    fun testSettingsOpenBehindTheDoorAndItShutsAgain() {
        reachChildHome()
        tap(button("Progres"))
        assertTrue(waitFor(button("Setări")))
        tap(button("Setări"))

        assertTrue(waitFor(button("Sunt adult")))
        assertFalse(exists(buttonStartingWith("Cum merge")))
        tap(button("Sunt adult"))

        assertTrue(waitFor(buttonStartingWith("Cum merge")))
        assertTrue(exists(buttonStartingWith("Notițe pentru logoped")))

        tap(button("Închide"))
        assertTrue(waitFor(button("Setări")))
        tap(button("Setări"))
        assertTrue(waitFor(button("Sunt adult")))
    }

    @Test
    fun testEveryPageBehindTheDoorOpens() {
        reachChildHome()
        tap(button("Progres"))
        assertTrue(waitFor(button("Setări")))
        tap(button("Setări"))
        assertTrue(waitFor(button("Sunt adult")))
        tap(button("Sunt adult"))

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
        assertTrue(waitFor(button("Mai târziu"), 15))
        tap(button("Mai târziu"))

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

        // An adult goes straight to the settings, with no door.
        tap(button("Progres"))
        assertTrue(waitFor(button("Setări")))
        tap(button("Setări"))
        assertTrue(waitFor(buttonStartingWith("Notițe pentru logoped")))
        assertFalse(exists(button("Sunt adult")))
    }
}
