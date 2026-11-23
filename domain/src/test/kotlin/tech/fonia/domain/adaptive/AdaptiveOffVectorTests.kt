package tech.fonia.domain.adaptive

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tech.fonia.domain.Fixture
import tech.fonia.domain.SessionVectors
import tech.fonia.domain.SoundID

/**
 * The hard constraint: with the adaptive mode off (the default) every session is the iOS one. The 276 requests of
 * `SessionVectors` go through the adaptive entry point and must give `ios-sessions.txt` byte for byte, as
 * `SessionVectorTests` does for the builder itself.
 */
@DisplayName("Adaptive: off is iOS")
class AdaptiveOffVectorTests {
    private val planner = AdaptivePlanner(Fixture.content)

    private val ios: String by lazy {
        val text = SessionVectors::class.java.getResourceAsStream("/ios-sessions.txt")?.use { it.readBytes().decodeToString() }
        assertNotNull(text, "src/test/resources/ios-sessions.txt holds the iOS export")
        text.replace("\r\n", "\n")
    }

    private fun render(profile: AdaptiveProfile?): String =
        SessionVectors.render(Fixture.builder, Fixture.content) { request -> AdaptiveSession.build(Fixture.builder, planner, request, profile) }

    @Test
    fun withNoAdaptiveStateEverySessionIsTheIOSOne() {
        assertEquals(ios, render(profile = null))
    }

    @Test
    fun withTheSwitchOffEverySessionIsTheIOSOneWhateverIsSaved() {
        // State left from an earlier pilot (modes set, a controller moved, estimates) changes nothing while it is off.
        val saved = SoundAdaptive(
            setting = ModeSetting.Generalization,
            challenge = ChallengeState(tech.fonia.domain.Rung.Pairs, Step(tech.fonia.domain.Rung.WordsInitial), kp = true),
            mastery = listOf(KeyMastery(ItemPosition.Initial, tech.fonia.domain.Rung.WordsInitial, 0.95, 50, listOf("2026-11-01", "2026-11-02"))),
        )
        assertEquals(ios, render(AdaptiveProfile(enabled = false, sounds = SoundID.entries.associateWith { saved })))
        assertEquals(ios, render(AdaptiveProfile()))
    }

    @Test
    fun withTheSwitchOnOnlyTheSoundSessionsChangeAndTheirDoseStays() {
        val on = render(AdaptiveProfile(enabled = true))
        assertNotEquals(ios, on)
        val iosSessions = sections(ios)
        val onSessions = sections(on)
        assertEquals(iosSessions.keys.toList(), onSessions.keys.toList(), "same requests, same order, same dose in every header")
        for ((header, trials) in iosSessions) {
            if (header.startsWith("# sound ")) continue
            assertEquals(trials, onSessions[header], header)
        }
        assertTrue(iosSessions.keys.count { it.startsWith("# sound ") && iosSessions[it] != onSessions[it] } > 0)
    }

    /** The rendered vectors cut at each `# name dose` header. */
    private fun sections(text: String): Map<String, List<String>> {
        val result = linkedMapOf<String, MutableList<String>>()
        var current: MutableList<String>? = null
        for (line in text.lines()) {
            if (line.startsWith("# ")) current = mutableListOf<String>().also { result[line] = it } else current?.add(line)
        }
        return result
    }
}
