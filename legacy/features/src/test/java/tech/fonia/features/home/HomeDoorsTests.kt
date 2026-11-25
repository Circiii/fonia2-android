package tech.fonia.features.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.features.app.AppRoute
import tech.fonia.store.Profile

/** The doors on Home, as iOS `HomeScreen.doors`: a child's five, and the adult's by track. */
class HomeDoorsTests {
    private fun profile(kind: Profile.Kind, track: Profile.Track = Profile.Track.Ro) = Profile(
        kind = kind,
        ageBand = if (kind == Profile.Kind.Child) Profile.AgeBand.SixToSeven else Profile.AgeBand.Adult,
        voiceGroup = if (kind == Profile.Kind.Child) Profile.VoiceGroup.Child else Profile.VoiceGroup.Female,
        color = "cold",
        track = track,
    )

    @Test
    fun aChildGetsTheSameFiveDoorsAsOnIos() {
        val doors = doors(profile(Profile.Kind.Child))
        assertEquals(listOf("Sunete", "Cum spun?", "Încălzirea", "Jocuri", "Povești"), doors.map { it.title })
        assertEquals(AppRoute.Guides, doors[1].route)
    }

    @Test
    fun aChildHasNoDoorToTheSpeechCoach() {
        for (track in Profile.Track.entries) {
            val routes = doors(profile(Profile.Kind.Child, track)).map { it.route }
            assertFalse(routes.any { it is AppRoute.SpeechCoach || it == AppRoute.Speaking || it == AppRoute.English })
        }
    }

    @Test
    fun anAdultKeepsPublicSpeaking() {
        for (track in Profile.Track.entries) {
            val doors = doors(profile(Profile.Kind.Adult, track))
            assertTrue(track.name, doors.any { it.title == "Vorbit în public" && it.route is AppRoute.SpeechCoach })
            assertFalse(track.name, doors.any { it.route == AppRoute.Guides })
        }
    }
}
