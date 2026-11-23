package tech.fonia.app.adult

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.app.data.AppSettings
import tech.fonia.app.data.Household
import tech.fonia.app.data.MemoryPrefs
import tech.fonia.app.data.Wiper
import tech.fonia.app.judge.Talker
import tech.fonia.model.SoundId
import java.nio.file.Files
import kotlin.random.Random

class AdultGateTest {
    @Test fun numbersAreWrittenInRomanian() {
        assertEquals("patruzeci și șapte", RomanianNumbers.words(47))
        assertEquals("douăzeci și unu", RomanianNumbers.words(21))
        assertEquals("nouăzeci și nouă", RomanianNumbers.words(99))
        assertEquals("șaizeci și opt", RomanianNumbers.words(68))
        assertEquals("cincisprezece", RomanianNumbers.words(15))
    }

    @Test fun theNumberIsBetween21And99AndNeverRound() {
        val gate = AdultGate(Random(7))
        repeat(500) {
            assertTrue(gate.number in 21..99)
            assertNotEquals(0, gate.number % 10)
            gate.check("0") // wrong: a new number
        }
    }

    @Test fun wrongAnswerDrawsAnotherNumberRightAnswerOpens() {
        val gate = AdultGate(Random(1))
        val first = gate.number
        assertFalse(gate.check("${first + 1}"))
        assertFalse(gate.isOpen)
        assertNotEquals(first, gate.number)
        assertTrue(gate.check(" ${gate.number} "))
        assertTrue(gate.isOpen)
    }

    @Test fun holdMustLastThreeSeconds() {
        val gate = AdultGate()
        assertFalse(gate.holdDone(2999))
        assertTrue(gate.holdDone(3000))
    }

    @Test fun closesAfterThreeMinutesInTheBackground() {
        var now = 0L
        val gate = AdultGate(Random(3)) { now }
        gate.check(gate.number.toString())
        gate.onBackground(); now += 179_000; gate.onForeground()
        assertTrue(gate.isOpen)
        gate.onBackground(); now += 180_000; gate.onForeground()
        assertFalse(gate.isOpen)
    }

    @Test fun wipeEmptiesEverythingAndKeepsTheStoreFolderForTheStore() = runBlocking {
        val prefs = MemoryPrefs()
        val settings = AppSettings(prefs).apply {
            finishOnboarding(Household.Self, Talker.AdultLow, SoundId.Sh)
            updateReduceMotion(true)
        }
        val gate = AdultGate(Random(5)).apply { check(number.toString()) }
        val cache = Files.createTempDirectory("cache").toFile().apply { resolve("thumb.png").writeText("x"); resolve("sub").mkdirs(); resolve("sub/a").writeText("y") }
        val noBackup = Files.createTempDirectory("nb").toFile().apply { resolve("Fonia").mkdirs(); resolve("Fonia/db").writeText("z"); resolve("events.jsonl").writeText("{}"); resolve("FoniaPilot").mkdirs(); resolve("FoniaPilot/pilot.sqlite").writeText("p") }
        var storeWiped = false
        var after = false
        Wiper({ storeWiped = true }, settings, gate, listOf(cache, noBackup), keep = setOf("Fonia"), afterWipe = { after = true }).wipe()
        assertTrue(storeWiped && after)
        assertEquals(0, cache.listFiles()!!.size)
        assertEquals(listOf("Fonia"), noBackup.listFiles()!!.map { it.name })
        assertFalse(gate.isOpen)
        assertFalse(settings.onboarded)
        assertEquals(Talker.Child, settings.talker)
        assertEquals(Household.ChildWithAdult, settings.household)
        assertFalse(settings.reduceMotion)
        assertNull(settings.chosenSound)
        assertNull(prefs.getString("onboarded"))
        // a fresh settings object over the same prefs starts from scratch too
        assertFalse(AppSettings(prefs).onboarded)
    }
}
