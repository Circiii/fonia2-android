package tech.fonia.domain

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

// Port of FoniaDomainTests/SessionEndingTests.swift. Swift's two-argument parameterised tests are loops here.

/** A session ends on something easy, and the dose stays what it was. */
@DisplayName("Session ending")
class SessionEndingTests {
    private val builder = Fixture.builder
    private val content = Fixture.content

    private fun session(id: SoundID, rung: Rung, age: AgeBand = AgeBand.SixToSeven, seed: ULong = 7uL): Session =
        builder.build(SessionRequest(age = age, focus = SessionFocus.Sound(id, rung), seed = seed))

    @Test
    fun theLastPhoneItemIsTheEasyClosing() {
        for (id in SoundID.entries) for (rung in Rung.entries) {
            val built = session(id, rung)
            val last = assertNotNull(built.trials.lastOrNull { it.block.kind != SessionBlock.Kind.Mission }, "$id $rung")
            assertEquals(SessionBlock.Kind.Closing, last.block.kind, "$id $rung")
            assertTrue(last.countsAsAttempt, "$id $rung")
            assertNull(last.prosody, "$id $rung")
            assertEquals(1, built.trials.count { it.block.kind == SessionBlock.Kind.Closing }, "$id $rung")
        }
    }

    @Test
    fun theClosingKeepsTheDose() {
        for (age in AgeBand.entries) for (rung in Rung.entries) {
            val built = session(SoundID.R, rung, age = age)
            assertEquals(built.targetAttempts, built.attempts.size, "$age $rung")
        }
    }

    @Test
    fun theClosingComesBeforeTheMission() {
        for (id in SoundID.entries) {
            val kinds = session(id, Rung.Everyday).trials.takeLast(2).map { it.block.kind }
            assertEquals(listOf(SessionBlock.Kind.Closing, SessionBlock.Kind.Mission), kinds, "$id")
        }
    }

    @Test
    fun earlyRungsCloseOnTheIsolatedSound() {
        for (id in SoundID.entries) {
            val sound = content.sound(id)
            for (rung in listOf(Rung.Isolated, Rung.Syllables)) {
                val closing = assertNotNull(session(id, rung).trials.firstOrNull { it.block.kind == SessionBlock.Kind.Closing })
                assertTrue(closing.exercise in sound.isolatedPractice, "$id $rung")
            }
        }
    }

    @Test
    fun firstWordsCloseOnASyllable() {
        for (id in SoundID.entries) {
            val closing = assertNotNull(session(id, Rung.WordsInitial).trials.firstOrNull { it.block.kind == SessionBlock.Kind.Closing })
            val prompt = (closing.exercise as? Exercise.Repeat)?.prompt ?: error("expected a repeat, got ${closing.exercise.kind}")
            assertEquals(RepeatPrompt.Level.Syllable, prompt.level)
            assertTrue(prompt.text in content.sound(id).syllables.direct)
            assertEquals(FeedbackLevel.Teach, closing.feedback)
        }
    }

    @Test
    fun laterRungsCloseOnAFirstWordWithItsPicture() {
        val rungs = listOf(Rung.WordsMedial, Rung.WordsFinal, Rung.Clusters, Rung.Pairs, Rung.Sentences, Rung.Retell, Rung.Everyday)
        for (rung in rungs) {
            val built = session(SoundID.S, rung, age = AgeBand.EightToTen)
            val closing = assertNotNull(built.trials.firstOrNull { it.block.kind == SessionBlock.Kind.Closing })
            val prompt = (closing.exercise as? Exercise.Repeat)?.prompt ?: error("expected a repeat, got ${closing.exercise.kind}")
            assertEquals(RepeatPrompt.Level.Word, prompt.level)
            assertTrue(prompt.text in content.sound(SoundID.S).words.initial.map { it.text })
            assertNotNull(prompt.picture)
            assertEquals(FeedbackLevel.Quick, closing.feedback)
        }
    }

    @Test
    fun theClosingIsTheSameForTheSameRequest() {
        val first = session(SoundID.Ts, Rung.WordsMedial, seed = 3uL).trials.last()
        val second = session(SoundID.Ts, Rung.WordsMedial, seed = 3uL).trials.last()
        assertEquals(first, second)
    }

    @Test
    fun goodbyeIsOneShortLine() {
        assertTrue(Session.goodbye.isNotEmpty())
        assertFalse("—" in Session.goodbye)
        assertTrue(Session.goodbye.split(".").filter { it.isNotBlank() }.all { it.trim().split(" ").size < 8 })
    }

    @Test
    fun affricatesPractiseBurstsAtTheIsolatedRung() {
        // port-android.md §2.1: Ț, CE and GE say the last grapheme three times, joined with hyphens.
        val expected = mapOf(SoundID.Ts to "ț-ț-ț", SoundID.Ce to "ci-ci-ci", SoundID.Ge to "gi-gi-gi")
        for ((id, text) in expected) {
            val burst = Exercise.Repeat(RepeatPrompt(text = text, language = Language.Ro, sound = id, level = RepeatPrompt.Level.Syllable))
            assertEquals(listOf<Exercise>(burst), content.sound(id).isolatedPractice)
        }
        assertTrue(content.sound(SoundID.S).isolatedPractice.all { it is Exercise.Sustain })
        assertTrue(content.sound(SoundID.R).isolatedPractice.all { it is Exercise.Repeat })
    }
}
