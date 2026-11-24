package tech.fonia.features.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.store.Attempt

/** A stuck judge (Whisper, ZIPA) never freezes a lesson: past the limit the take is asked for again. */
class JudgeTimeoutTests {
    @Test
    fun theLimitIsAtLeastTwentySecondsAndTenOverTheLongestTake() {
        assertEquals(20_000L, JudgeTimeout.limitMs(5.0))
        assertEquals(20_000L, JudgeTimeout.limitMs(10.0))
        assertEquals(55_000L, JudgeTimeout.limitMs(45.0))
    }

    @Test
    fun aQuickJudgeGivesItsAnswerEvenWhenItIsNull() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val done = JudgeTimeout.firstWithin(1_000, scope) { null as String? }
        assertNotNull(done)
        assertNull(done!!.value)
        assertEquals(7, JudgeTimeout.firstWithin(1_000, scope) { 7 }?.value)
        scope.cancel()
    }

    @Test
    fun aBlockedNativeCallIsAbandonedAtTheLimit() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = System.nanoTime()
        // Thread.sleep stands for a JNI call that can't be interrupted.
        val late = JudgeTimeout.firstWithin(100, scope) { withContext(Dispatchers.IO) { Thread.sleep(3_000); 7 } }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertNull(late)
        assertTrue("waited $tookMs ms", tookMs < 1_500)
        scope.cancel()
    }

    @Test
    fun noTakeAndNoVerdictInTimeSayWhatToDoAndAreNeverScored() {
        for (v in listOf(VerdictWriter.listenFailed, VerdictWriter.judgeTimedOut)) {
            assertNull(v.rating)
            assertEquals(Attempt.Outcome.Unclear, v.outcome)
            assertFalse(v.keepsTake)
            val tip = v.tip!!
            assertTrue(tip, tip.trimEnd('.').split(' ').size <= 8)
        }
        assertEquals("Nu am putut asculta", VerdictWriter.listenFailed.title)
    }
}
