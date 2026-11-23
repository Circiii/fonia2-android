package tech.fonia.face

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Not iOS tests: the face checks and the hold arithmetic live in MouthCoach, which iOS only tests
 * by hand. These pin the numbers of inventory 7.9.1, 7.9.2 and the script deduction of 7.6.
 */
@DisplayName("Face checks and hold times")
class FaceCheckTests {
    @Test
    @DisplayName("each check passes just past its threshold and not on it")
    fun thresholds() {
        val cases = listOf(
            Triple(FaceCheck.SMILE, FaceFrame(smile = 0.43f), FaceFrame(smile = 0.42f)),
            Triple(FaceCheck.TEETH, FaceFrame(smile = 0.19f, jawOpen = 0.15f), FaceFrame(smile = 0.18f, jawOpen = 0.15f)),
            Triple(FaceCheck.TEETH, FaceFrame(smile = 0.19f, jawOpen = 0.15f), FaceFrame(smile = 0.19f, jawOpen = 0.16f)),
            Triple(FaceCheck.PUCKER, FaceFrame(pucker = 0.43f), FaceFrame(pucker = 0.42f)),
            Triple(FaceCheck.ROUND_CLOSE, FaceFrame(funnel = 0.39f, jawOpen = 0.24f), FaceFrame(funnel = 0.38f, jawOpen = 0.24f)),
            Triple(FaceCheck.ROUND_CLOSE, FaceFrame(pucker = 0.39f, jawOpen = 0.24f), FaceFrame(pucker = 0.39f, jawOpen = 0.25f)),
            Triple(FaceCheck.ROUND_LIGHT, FaceFrame(pucker = 0.25f), FaceFrame(pucker = 0.24f)),
            Triple(FaceCheck.ROUND_W, FaceFrame(funnel = 0.46f), FaceFrame(funnel = 0.45f)),
            Triple(FaceCheck.OPEN, FaceFrame(jawOpen = 0.23f), FaceFrame(jawOpen = 0.22f)),
            Triple(FaceCheck.WIDE_OPEN, FaceFrame(jawOpen = 0.43f), FaceFrame(jawOpen = 0.42f)),
            Triple(FaceCheck.TONGUE_OUT, FaceFrame(tongueOut = 0.31f), FaceFrame(tongueOut = 0.3f)),
            Triple(FaceCheck.RELAXED, FaceFrame(smile = 0.19f, funnel = 0.19f, jawOpen = 0.19f), FaceFrame(smile = 0.2f)),
            Triple(FaceCheck.RELAXED, FaceFrame(smile = 0.19f, funnel = 0.19f, jawOpen = 0.19f), FaceFrame(pucker = 0.2f)),
            Triple(FaceCheck.RELAXED, FaceFrame(smile = 0.19f, funnel = 0.19f, jawOpen = 0.19f), FaceFrame(jawOpen = 0.2f)),
            Triple(FaceCheck.LIP_BITE, FaceFrame(jawOpen = 0.11f, funnel = 0.24f), FaceFrame(jawOpen = 0.12f)),
            Triple(FaceCheck.LIP_BITE, FaceFrame(jawOpen = 0.11f, funnel = 0.24f), FaceFrame(funnel = 0.25f)),
        )
        for ((check, passes, fails) in cases) {
            assertTrue(check.matches(passes), "$check should pass $passes")
            assertFalse(check.matches(fails), "$check should not pass $fails")
        }
    }

    @Test
    @DisplayName("the checks are CoachFaceJudge case for case")
    fun rawValues() {
        val coachFaceJudge = listOf("smile", "teeth", "pucker", "roundClose", "roundLight", "open", "tongueOut", "roundW", "lipBite", "relaxed", "wideOpen")
        assertEquals(coachFaceJudge, FaceCheck.entries.map { it.rawValue })
        for (raw in coachFaceJudge) assertEquals(raw, FaceCheck.fromRawValue(raw)?.rawValue)
        assertEquals(null, FaceCheck.fromRawValue("frown"))
    }

    @Test
    @DisplayName("every face check in coach_plans.json is one the face module knows")
    fun coachPlans() {
        val content = assertNotNull(System.getProperty("fonia.content"), "the content submodule path")
        val json = File(content, "json/coach_plans.json").readText(Charsets.UTF_8)
        val faces = Regex(""""face"\s*:\s*"([^"]+)"""").findAll(json).map { it.groupValues[1] }.toList()
        // fonia-content 66c53b7 rewrote the mouth plans and no step asks for "lipBite" or "relaxed" any more
        // (18 → 16 face steps). The face module keeps both checks, so content may bring them back.
        assertEquals(16, faces.size)
        assertTrue(faces.all { FaceCheck.fromRawValue(it) != null }, "unknown check in $faces")
        assertEquals(FaceCheck.entries.map { it.rawValue }.toSet() - setOf("lipBite", "relaxed"), faces.toSet())
    }

    @Test
    @DisplayName("a one-second pose takes 17 good polls")
    fun holdsForASecond() {
        val hold = FaceHold(holdMs = 1000)
        assertEquals(1.0, hold.needed)
        repeat(16) { assertFalse(hold.poll(FaceCheck.SMILE, FaceFrame(smile = 0.9f))) }
        assertTrue(hold.held < 1)
        assertTrue(hold.poll(FaceCheck.SMILE, FaceFrame(smile = 0.9f)))
        assertTrue(hold.isDone)
        assertEquals(1.0, hold.held)
    }

    @Test
    @DisplayName("a miss takes back half a poll, never below zero")
    fun misses() {
        val hold = FaceHold(holdMs = 1000)
        hold.poll(FaceCheck.OPEN, FaceFrame(jawOpen = 0.5f))
        hold.poll(FaceCheck.OPEN, FaceFrame(jawOpen = 0.1f))
        assertTrue(kotlin.math.abs(hold.seconds - 0.03) < 1e-12)
        hold.poll(FaceCheck.OPEN, FaceFrame(jawOpen = 0.1f))
        hold.poll(FaceCheck.OPEN, FaceFrame(jawOpen = 0.1f))
        assertEquals(0.0, hold.seconds)
    }

    @Test
    @DisplayName("a short hold still asks for 0.6 s, and a hidden face never counts")
    fun floorsAndAbsence() {
        assertEquals(0.6, FaceHold(holdMs = 300).needed)
        val hold = FaceHold(holdMs = 1000)
        repeat(30) { hold.poll(FaceCheck.SMILE, FaceFrame(isPresent = false, smile = 0.9f)) }
        assertEquals(0.0, hold.seconds)
    }

    @Test
    @DisplayName("with the scripted face only open and lipBite pass on their own")
    fun scriptedFace() {
        val poll = FaceHold.POLL_INTERVAL.inWholeMilliseconds.toInt()
        val script = FakeFaceSource.SPEAKING_SCRIPT
        val stepMs = script[0].hold.inWholeMilliseconds.toInt()
        fun passesWithin(check: FaceCheck, seconds: Int): Boolean {
            val hold = FaceHold(holdMs = 1000)
            var t = 0
            while (t < seconds * 1000) {
                t += poll
                if (hold.poll(check, script[(t / stepMs) % script.size].frame)) return true
            }
            return false
        }
        val passing = FaceCheck.entries.filter { passesWithin(it, 60) }
        assertEquals(listOf(FaceCheck.OPEN, FaceCheck.LIP_BITE), passing)
        assertTrue(passesWithin(FaceCheck.OPEN, 10))
        assertTrue(passesWithin(FaceCheck.LIP_BITE, 10))
    }
}
