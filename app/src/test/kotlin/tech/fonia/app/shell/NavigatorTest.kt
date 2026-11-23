package tech.fonia.app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.model.SoundId
import tech.fonia.navigation.EndKey
import tech.fonia.navigation.LabKey
import tech.fonia.navigation.LessonKey
import tech.fonia.navigation.PathKey
import tech.fonia.navigation.AdultGateKey
import tech.fonia.navigation.AdultHomeKey
import tech.fonia.navigation.OnboardingKey
import tech.fonia.navigation.TeenHomeKey

class NavigatorTest {
    private fun lesson(s: SoundId = SoundId.S) = LessonKey(s, sessionId = "x-${s.raw}")
    private fun end(s: SoundId = SoundId.S) = EndKey("x", s, 5, 4, 2800)

    @Test fun pathIsTheRootAndBackFromPathClosesTheApp() {
        val nav = Navigator()
        assertEquals(listOf(PathKey), nav.stack.toList())
        assertFalse(nav.back())
        assertEquals(listOf(PathKey), nav.stack.toList())
    }

    @Test fun aDeepStartGetsPathUnderIt() {
        assertEquals(listOf(PathKey, lesson()), Navigator(listOf(lesson())).stack.toList())
        assertEquals(listOf(LabKey), Navigator(listOf(LabKey)).stack.toList())
    }

    @Test fun endReplacesTheLessonAndBackFromEndLandsOnPath() {
        val nav = Navigator()
        nav.push(lesson())
        nav.finishLesson(end())
        assertEquals(listOf(PathKey, end()), nav.stack.toList())
        assertTrue(nav.back())
        assertEquals(listOf(PathKey), nav.stack.toList())
        assertFalse(nav.forward)
    }

    @Test fun againReplacesEndWithAFreshLesson() {
        val nav = Navigator()
        nav.push(lesson())
        nav.finishLesson(end())
        val next = lesson(SoundId.S).copy(sessionId = "y")
        nav.again(next)
        assertEquals(listOf(PathKey, next), nav.stack.toList())
        assertTrue(nav.forward)
    }

    @Test fun pushingALessonNeverStacksTwoLessons() {
        val nav = Navigator()
        nav.push(lesson(SoundId.S))
        nav.push(lesson(SoundId.Sh))
        assertEquals(listOf(PathKey, lesson(SoundId.Sh)), nav.stack.toList())
    }

    @Test fun homeDropsEverythingAbovePath() {
        val nav = Navigator(listOf(PathKey, LabKey))
        nav.home()
        assertEquals(listOf(PathKey), nav.stack.toList())
        val lab = Navigator(listOf(LabKey))
        lab.home()
        assertEquals(listOf(PathKey), lab.stack.toList())
    }

    @Test fun backFromAPlainScreenPopsOne() {
        val nav = Navigator()
        nav.push(LabKey)
        assertTrue(nav.back())
        assertEquals(PathKey, nav.top)
    }

    @Test fun gateIsReplacedByWhatItGuardsAndBackLandsOnPath() {
        val nav = Navigator()
        nav.push(AdultGateKey(AdultHomeKey))
        nav.replaceTop(AdultHomeKey)
        assertEquals(listOf(PathKey, AdultHomeKey), nav.stack.toList())
        assertTrue(nav.back())
        assertEquals(listOf(PathKey), nav.stack.toList())
    }

    @Test fun onboardingIsARootAndResetMovesToPath() {
        val nav = Navigator(listOf(OnboardingKey))
        assertEquals(listOf(OnboardingKey), nav.stack.toList())
        nav.reset(listOf(PathKey))
        assertEquals(listOf(PathKey), nav.stack.toList())
    }

    @Test fun homeFromATeenStackFindsPath() {
        val nav = Navigator(listOf(TeenHomeKey))
        nav.push(PathKey)
        nav.push(lesson())
        nav.finishLesson(end())
        nav.home()
        assertEquals(listOf(TeenHomeKey, PathKey), nav.stack.toList())
    }

    @Test fun endFromAnExerciseGoesBackToTheSoundHouse() {
        val nav = Navigator()
        nav.push(tech.fonia.navigation.SoundHouseKey(SoundId.Z))
        nav.push(lesson())
        nav.finishLesson(end())
        assertTrue(nav.back())
        assertEquals(listOf(PathKey, tech.fonia.navigation.SoundHouseKey(SoundId.Z)), nav.stack.toList())
    }

    @Test fun pickAnotherExerciseFindsOrMakesTheHouse() {
        val nav = Navigator()
        nav.push(lesson())
        nav.finishLesson(end(SoundId.R))
        nav.toHouse(SoundId.R)
        assertEquals(listOf(PathKey, tech.fonia.navigation.SoundHouseKey(SoundId.R)), nav.stack.toList())
    }
}
