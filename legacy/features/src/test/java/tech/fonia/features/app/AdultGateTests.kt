package tech.fonia.features.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.domain.ContentSource
import tech.fonia.domain.FoniaContent
import tech.fonia.store.FoniaDatabase
import tech.fonia.store.FoniaRepository
import tech.fonia.store.Profile
import java.io.File
import java.nio.file.Files

/** The gate's multiplication and the adult check (iOS `GateQuestionTests`, `AdultGateTests`), and when the app asks for it. */
class AdultGateTests {
    @Test
    fun fourDifferentAnswersTheRightOneAmongThem() {
        for (a in GateQuestion.factors) for (b in GateQuestion.factors) {
            val question = GateQuestion(a, b)
            assertEquals(4, question.choices.size)
            assertEquals(4, question.choices.toSet().size)
            assertTrue(a * b in question.choices)
            assertTrue(question.choices.all { it > 0 })
        }
    }

    @Test
    fun theNearAnswersAreOneRowOrColumnOffFirst() {
        val question = GateQuestion(7, 8) { it }
        assertEquals(listOf(56, 63, 64, 49), question.choices)
        assertEquals("7 × 8", question.text)
    }

    @Test
    fun aFixedAnswerFindsItsQuestionAnswersInRisingOrder() {
        val question = GateQuestion.fixedAnswer(56)!!
        assertEquals(7, question.a)
        assertEquals(8, question.b)
        assertEquals(question.choices.sorted(), question.choices)
        assertEquals(listOf(49, 56, 63, 64), question.choices)
        assertNull(GateQuestion.fixedAnswer(47))
    }

    /** Hands out the given questions in turn. */
    private fun gate(pairs: List<Pair<Int, Int>>, clock: () -> Long = { 0L }): AdultGate {
        val queue = ArrayDeque(pairs)
        return AdultGate(draw = {
            val (a, b) = queue.removeFirstOrNull() ?: (9 to 9)
            GateQuestion(a, b) { it }
        }, clock = clock)
    }

    @Test
    fun startsClosedAndAsksAMultiplication() {
        val gate = gate(listOf(7 to 8))
        assertFalse(gate.isOpen)
        assertEquals("Cât face 7 × 8?", gate.prompt)
    }

    @Test
    fun theRightAnswerOpensIt() {
        val gate = gate(listOf(7 to 8))
        assertTrue(gate.answer(56))
        assertTrue(gate.isOpen)
        assertFalse(gate.isRetry)
    }

    @Test
    fun aWrongAnswerBringsADifferentQuestionASecondOneInARowAsksForARest() {
        val gate = gate(listOf(7 to 8, 7 to 8, 6 to 9, 8 to 8))
        val now = 1_000_000L
        assertFalse(gate.answer(63, now))
        assertTrue(gate.isRetry)
        assertEquals(54, gate.question.answer)
        assertFalse(gate.isResting(now))
        assertFalse(gate.answer(48, now))
        assertEquals(64, gate.question.answer)
        assertTrue(gate.isResting(now + AdultGate.REST_MILLIS - 1_000))
        // While resting, even the right answer waits.
        assertFalse(gate.answer(64, now + 1_000))
        assertTrue(gate.answer(64, now + AdultGate.REST_MILLIS + 1_000))
        assertTrue(gate.isOpen)
    }

    @Test
    fun aFixedQuestionStaysTheSameForUITests() {
        val gate = AdultGate(fixed = 56)
        assertFalse(gate.answer(12))
        assertEquals("7 × 8", gate.question.text)
        assertTrue(gate.answer(56, System.currentTimeMillis() + 60_000))
    }

    @Test
    fun aShortTripToTheBackgroundKeepsItOpenALongOneClosesIt() {
        var now = 1_000_000L
        val gate = gate(listOf(7 to 8, 6 to 7)) { now }
        gate.answer(56)
        val left = now
        gate.onBackground(); now = left + AdultGate.BACKGROUND_CLOSE_MILLIS - 1_000; gate.onForeground()
        assertTrue(gate.isOpen)
        now = left
        gate.onBackground(); now = left + AdultGate.BACKGROUND_CLOSE_MILLIS + 1_000; gate.onForeground()
        assertFalse(gate.isOpen)
        assertEquals(42, gate.question.answer)
    }

    @Test
    fun closeLocksItAgainAndClearsARest() {
        val gate = gate(listOf(7 to 8, 6 to 6, 6 to 7, 8 to 9))
        gate.answer(1)
        gate.answer(1)
        gate.close()
        assertFalse(gate.isOpen)
        assertFalse(gate.isRetry)
        assertFalse(gate.isResting())
    }

    @Test
    fun foniaGateFixesTheQuestionOnlyWhenItsAnswerIsInTheTable() {
        assertEquals(56, AdultGate.fixedNumber(56))
        assertNull(AdultGate.fixedNumber(47))
        assertNull(AdultGate.fixedNumber(null))
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
        app.gate.answer(app.gate.question.answer)
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
