package tech.fonia.features.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

/**
 * A take is judged within a time limit, so a stuck Whisper or ZIPA call can never leave a lesson on "thinking" forever
 * (iOS `SpeakExercise.judgeTimeout`). Past the limit the screen says "Nu știu sigur" and offers the retake.
 */
object JudgeTimeout {
    /** Long enough for the slowest phone to judge the longest take, short enough that nobody waits on a frozen screen. */
    fun limitMs(maximumSeconds: Double): Long = (max(20.0, maximumSeconds + 10.0) * 1000).toLong()

    /** The value [work] gave, wrapped so a null result can be told apart from running out of time. */
    class Done<T>(val value: T)

    /**
     * Runs [work] in [scope] and waits for it at most [limitMs]. Blocking native calls can't be interrupted, so the
     * work is started apart and abandoned (cancelled) when the time is up, instead of being waited for.
     */
    suspend fun <T> firstWithin(limitMs: Long, scope: CoroutineScope, work: suspend () -> T): Done<T>? {
        val running = scope.async { Done(work()) }
        try {
            return withTimeoutOrNull(limitMs) { running.await() }
        } finally {
            if (!running.isCompleted) running.cancel()
        }
    }
}
