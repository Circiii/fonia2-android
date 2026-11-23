package tech.fonia.app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.model.SoundId
import tech.fonia.navigation.AdultHomeKey
import tech.fonia.navigation.AdultSettingsKey
import tech.fonia.navigation.FoniaKey
import tech.fonia.navigation.MinuteKey
import tech.fonia.navigation.MirrorKey
import tech.fonia.navigation.NoUmKey
import tech.fonia.navigation.PathKey
import tech.fonia.navigation.TeenHomeKey

/** M2: MIRROR from PATH, „Vorbit în public” → TEEN-HOME → NO-UM / MINUTE, and the debug start stacks' shapes. */
class SpeakingMirrorNavigationTest {
    @Test fun mirrorFromPathOpensForTheSoundAndBackReturnsToPath() {
        val nav = Navigator()
        openMirror(nav, SoundId.Sh)
        assertEquals(listOf(PathKey, MirrorKey(SoundId.Sh)), nav.stack.toList())
        assertEquals(FoniaKey.Shell.Kid, nav.top.shell)
        assertTrue(nav.back())
        assertEquals(listOf(PathKey), nav.stack.toList())
    }

    @Test fun speakingFromAdultSettingsGoesThroughTeenHome() {
        val nav = Navigator(listOf(PathKey, AdultHomeKey, AdultSettingsKey))
        openSpeaking(nav)
        assertEquals(TeenHomeKey, nav.top)
        nav.push(NoUmKey())
        assertEquals(FoniaKey.Shell.Teen, nav.top.shell)
        assertTrue(nav.back())
        assertEquals(TeenHomeKey, nav.top)
        assertTrue(nav.back())
        assertEquals(AdultSettingsKey, nav.top)
    }

    @Test fun teenHomeIsARootForDoarEu() {
        val nav = Navigator(listOf(TeenHomeKey))
        nav.push(MinuteKey)
        assertEquals(listOf(TeenHomeKey, MinuteKey), nav.stack.toList())
        assertTrue(nav.back())
        assertFalse(nav.back()) // back from TEEN-HOME closes the app
        assertEquals(listOf(TeenHomeKey), nav.stack.toList())
    }

    @Test fun debugStartStacksKeepTheirRoots() {
        // --es screen noum / minute / mirror
        assertEquals(listOf(TeenHomeKey, NoUmKey()), Navigator(listOf(TeenHomeKey, NoUmKey())).stack.toList())
        assertEquals(listOf(PathKey, MirrorKey(SoundId.Sh)), Navigator(listOf(MirrorKey(SoundId.Sh))).stack.toList())
    }

    @Test fun noUmKeysCarryTheirTopic() {
        assertEquals(NoUmKey("Ziua mea"), NoUmKey("Ziua mea"))
        assertTrue(NoUmKey() != NoUmKey("Ziua mea"))
    }
}
