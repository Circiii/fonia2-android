package tech.fonia.live

import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LiveBoardTest {
    private fun fill(s: LiveSnapshot, n: Long) {
        s.seq = n
        s.captureNanos = n * 10
        s.publishNanos = n * 10 + 3
        s.level = n.toFloat()
        s.sNess = n.toFloat()
        s.holdSeconds = n.toFloat()
        s.pitchHz = n.toFloat()
        s.dropped = n.toInt()
        s.speaking = n % 2 == 0L
    }

    private fun consistent(s: LiveSnapshot): Boolean {
        val n = s.seq
        return s.captureNanos == n * 10 && s.publishNanos == n * 10 + 3 && s.level == n.toFloat() &&
            s.sNess == n.toFloat() && s.holdSeconds == n.toFloat() && s.pitchHz == n.toFloat() &&
            s.dropped == n.toInt() && s.speaking == (n % 2 == 0L)
    }

    @Test
    fun readerSeesTheNewestFrameAndTheSameInstanceUntilSomethingNew() {
        val board = LiveBoard()
        assertEquals(0L, board.latest().seq)
        for (n in 1L..3L) {
            fill(board.back(), n)
            board.publish()
        }
        val a = board.latest()
        assertEquals(3L, a.seq)
        assertSame(a, board.latest(), "nothing new: same slot")
        fill(board.back(), 4)
        board.publish()
        assertEquals(4L, board.latest().seq)
    }

    @Test
    fun aConcurrentReaderNeverSeesATornFrame() {
        val board = LiveBoard()
        val failure = AtomicReference<String?>(null)
        val frames = 300_000L
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        var reads = 0L
        val started = java.util.concurrent.CountDownLatch(1)
        val reader = Thread {
            var last = 0L
            started.countDown()
            while (!done.get()) {
                val s = board.latest()
                if (s.seq > 0 && !consistent(s)) failure.compareAndSet(null, "torn frame at seq ${s.seq}")
                if (s.seq < last) failure.compareAndSet(null, "seq went back: ${s.seq} after $last")
                last = s.seq
                reads++
            }
        }
        reader.start()
        started.await()
        for (n in 1L..frames) {
            fill(board.back(), n)
            board.publish()
        }
        done.set(true)
        reader.join()
        assertNull(failure.get())
        assertEquals(frames, board.latest().seq)
    }

    @Test
    fun aReaderRunsAlongsideTheRealPipeline() {
        val pipeline = LivePipeline()
        val failure = AtomicReference<String?>(null)
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val reader = Thread {
            var last = 0L
            while (!done.get()) {
                val s = pipeline.board.latest()
                if (s.seq < last) failure.compareAndSet(null, "seq went back")
                if (s.seq > 0 && s.publishNanos < s.captureNanos) failure.compareAndSet(null, "published before captured")
                if (s.sNess !in 0f..1f || s.level !in 0f..1f) failure.compareAndSet(null, "value out of range: $s")
                last = s.seq
            }
        }
        reader.start()
        val audio = Signals.concat(Signals.quiet(1.3), Signals.hiss(8500.0, 2.0), Signals.voiced(200.0, 1.0))
        LiveCapture.runToEnd(ArrayPcmSource(audio, Signals.RATE), pipeline, LiveTarget.Sibilant(SibilantSide.S, false))
        done.set(true)
        reader.join()
        assertNull(failure.get())
        val summary = pipeline.stats.summary()
        assertTrue(summary.hops > 400)
        println("Analysis per hop on this JVM: p50 ${summary.analysisP50Ms} ms, p95 ${summary.analysisP95Ms} ms, max ${summary.analysisMaxMs} ms")
    }
}
