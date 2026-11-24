package tech.fonia.features.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.domain.ContentSource
import tech.fonia.domain.FoniaContent
import tech.fonia.store.FoniaDatabase
import tech.fonia.store.FoniaRepository
import tech.fonia.store.Profile
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/** The adult check (the new app's `AdultGateTest`), and when the app asks for it (iOS `needsAdultGate`). */
class AdultGateTests {
    @Test
    fun numbersAreWrittenInRomanian() {
        assertEquals("patruzeci și șapte", RomanianNumbers.words(47))
        assertEquals("douăzeci și unu", RomanianNumbers.words(21))
        assertEquals("nouăzeci și nouă", RomanianNumbers.words(99))
        assertEquals("șaizeci și opt", RomanianNumbers.words(68))
        assertEquals("cincisprezece", RomanianNumbers.words(15))
    }

    @Test
    fun theNumberIsBetween21And99AndNeverRound() {
        val gate = AdultGate(Random(7))
        repeat(500) {
            assertTrue(gate.number in 21..99)
            assertNotEquals(0, gate.number % 10)
            gate.check("0")
        }
    }

    @Test
    fun aWrongAnswerDrawsAnotherNumberTheRightOneOpens() {
        val gate = AdultGate(Random(1))
        val first = gate.number
        assertFalse(gate.check("${first + 1}"))
        assertFalse(gate.isOpen)
        assertNotEquals(first, gate.number)
        assertTrue(gate.check(" ${gate.number} "))
        assertTrue(gate.isOpen)
    }

    @Test
    fun theHoldLastsThreeSeconds() {
        val gate = AdultGate()
        assertFalse(gate.holdDone(2999))
        assertTrue(gate.holdDone(3000))
    }

    @Test
    fun itClosesAfterThreeMinutesInTheBackground() {
        var now = 0L
        val gate = AdultGate(Random(3)) { now }
        gate.check(gate.number.toString())
        gate.onBackground(); now += 179_000; gate.onForeground()
        assertTrue(gate.isOpen)
        gate.onBackground(); now += 180_000; gate.onForeground()
        assertFalse(gate.isOpen)
    }

    @Test
    fun aFixedNumberForTestsStaysAfterAWrongAnswer() {
        val gate = AdultGate(fixed = 47)
        assertEquals(47, gate.number)
        assertFalse(gate.check("48"))
        assertEquals(47, gate.number)
        assertTrue(gate.check("47"))
        assertTrue(AdultGate.canAsk(47))
        assertFalse(AdultGate.canAsk(40))
        assertFalse(AdultGate.canAsk(12))
    }

    @Test
    fun onlyAPhoneWithAChildAsksAndTheWipeClosesIt() = runBlocking {
        val content = FoniaContent.load(ContentSource.directory(File(System.getProperty("fonia.content") ?: "../content")))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val root = Files.createTempDirectory("fonia-gate").toFile()
        val app = AppModel(
            content = content,
            repository = FoniaRepository(FoniaDatabase.inMemory(recordingsDirectory = File(root, "Recordings"))),
            audio = AudioHub(voices = null, backend = NoAudioBackend(null), usesFixtures = true),
            speech = SpeechHub(model = null, scope = scope),
            scope = scope,
        )
        val adult = Profile(
            kind = Profile.Kind.Adult, ageBand = Profile.AgeBand.Adult, voiceGroup = Profile.VoiceGroup.Female,
            color = "cold", targetSounds = listOf("s"), track = Profile.Track.Ro,
        )
        val child = Profile(
            kind = Profile.Kind.Child, ageBand = Profile.AgeBand.SixToSeven, voiceGroup = Profile.VoiceGroup.Child,
            color = "cold", targetSounds = listOf("s"), track = Profile.Track.Ro,
        )
        val watching = scope.launch { app.start() }
        app.repository.saveProfile(adult)
        waitUntil { app.profiles.size == 1 }
        assertFalse(app.needsAdultGate)
        app.repository.saveProfile(child)
        waitUntil { app.profiles.size == 2 }
        assertTrue(app.needsAdultGate)
        app.gate.check(app.gate.number.toString())
        assertFalse(app.needsAdultGate)
        app.wipeEverything()
        assertFalse(app.gate.isOpen)
        watching.cancel()
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(10)
        assertTrue(condition())
    }
}
