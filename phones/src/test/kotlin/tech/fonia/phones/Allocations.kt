package tech.fonia.phones

import java.lang.management.ManagementFactory

/**
 * Bytes this thread allocates while [body] runs, from the JVM's per-thread counter (as `:dsp`'s LiveAnalyzer tests
 * measure it). A 4 KB probe first proves the counter moves, so a zero can be trusted.
 */
internal fun countingAllocatedBytes(body: () -> Unit): Long {
    val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        ?: error("This JVM cannot count allocations per thread")
    check(bean.isThreadAllocatedMemorySupported) { "This JVM cannot count allocations per thread" }
    bean.isThreadAllocatedMemoryEnabled = true
    val probeStart = bean.currentThreadAllocatedBytes
    sink = ByteArray(4096)
    val probeEnd = bean.currentThreadAllocatedBytes
    check(probeEnd - probeStart >= 4096) { "The allocation counter did not see a 4 KB array" }
    val before = bean.currentThreadAllocatedBytes
    body()
    val after = bean.currentThreadAllocatedBytes
    return after - before
}

/** Runs [body] until a pass allocates nothing (the JIT may link call sites late), at most [passes] times. */
internal fun allocationAfterWarmUp(passes: Int = 20, body: () -> Unit): Long {
    var bytes = Long.MAX_VALUE
    repeat(passes) {
        bytes = countingAllocatedBytes(body)
        if (bytes == 0L) return 0L
    }
    return bytes
}

/** Keeps the probe allocation observable, so the JIT cannot remove it. */
@Volatile
private var sink: Any? = null
