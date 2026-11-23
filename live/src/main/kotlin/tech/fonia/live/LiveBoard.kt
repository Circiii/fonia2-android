package tech.fonia.live

import java.util.concurrent.atomic.AtomicInteger

/**
 * Lock-free triple buffer between ONE writer (the analysis thread) and ONE reader (the main thread, once per frame).
 * The reader never sees a half-written frame, unlike the `@Volatile` fields of the old `LiveLevels`, and neither
 * side allocates or blocks.
 *
 * Writer: fill [back] completely, then [publish]. The back slot holds a frame from two publishes ago, so write every
 * field (or `copyFrom` a full frame), never patch it.
 *
 * Reader: call [latest] once per frame (in `withFrameNanos`) and keep using the returned instance for that frame
 * only. It stays unchanged until the reader's next [latest] call. A second reader thread is not supported: share
 * the instance the one reader got (the `LiveFrame` holder in the design system does that).
 */
class LiveBoard {
    private val slots = Array(3) { LiveSnapshot() }

    /** Bits 0–1: index of the middle slot; bit 2: the middle slot is newer than what the reader has. */
    private val shared = AtomicInteger(1)
    private var back = 0
    private var front = 2

    @Volatile private var publishedSeq = 0L

    /** The slot the writer fills next. Writer thread only. */
    fun back(): LiveSnapshot = slots[back]

    /** Hands the filled back slot to the reader. Writer thread only. */
    fun publish() {
        publishedSeq = slots[back].seq
        back = shared.getAndSet(back or FRESH) and INDEX
    }

    /** The newest published frame. Reader thread only. Returns the same instance until something new is published. */
    fun latest(): LiveSnapshot {
        if (shared.get() and FRESH != 0) front = shared.getAndSet(front) and INDEX
        return slots[front]
    }

    /** `seq` of the last publish, readable from any thread (for tests and the overlay). */
    val lastPublishedSeq: Long get() = publishedSeq

    private companion object {
        const val FRESH = 4
        const val INDEX = 3
    }
}
