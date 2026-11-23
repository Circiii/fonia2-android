package tech.fonia.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

/**
 * An [AudioDirector] with no hardware, for JVM tests, previews and the app's fixture mode. It runs the same state
 * machine and [FxGate] as the real one and records a [timeline] of mic and sound intervals, so a test can prove
 * playback and capture never overlap.
 *
 * With [sourceFor], an open mic runs the real [LivePipeline] on a real analysis thread from that source (a WAV via
 * [WavPcmSource]), so the whole band works without a phone.
 */
class FakeAudioDirector(
    scope: CoroutineScope,
    clock: NanoClock = NanoClock.System,
    rule: QuietRule = QuietRule(),
    board: LiveBoard = LiveBoard(),
    config: LiveConfig = LiveConfig(),
    /** How long every prompt "plays". */
    var promptMillis: Long = 300,
    private val sourceFor: ((LiveTarget) -> PcmSource)? = null,
) : BaseAudioDirector(board, FxGate(rule, clock), clock, scope) {
    enum class Kind { Mic, Prompt, Earcon }

    /** [endNanos] is `Long.MAX_VALUE` while the interval is still open. */
    data class Interval(val kind: Kind, val startNanos: Long, val endNanos: Long, val label: String)

    val pipeline = LivePipeline(board, config)
    private val capture = LiveCapture(pipeline)
    private val intervals = ArrayList<Interval>()
    private var micStart = -1L

    /** Everything that happened, in start order. */
    val timeline: List<Interval> get() = synchronized(intervals) { intervals.sortedBy { it.startNanos } }

    /** Pairs of a mic interval and a sound interval that overlap (must be empty). */
    fun overlaps(): List<Pair<Interval, Interval>> {
        val all = timeline
        val mics = all.filter { it.kind == Kind.Mic }
        val sounds = all.filter { it.kind != Kind.Mic }
        return mics.flatMap { mic -> sounds.filter { it.startNanos < mic.endNanos && mic.startNanos < it.endNanos }.map { mic to it } }
    }

    override suspend fun startCapture(target: LiveTarget): CaptureInfo {
        micStart = clock.nanos()
        val source = sourceFor?.invoke(target)
        if (source != null) capture.start(source, target)
        return CaptureInfo(
            sampleRate = source?.sampleRate ?: 48_000.0,
            audioSource = source?.audioSource ?: "fake",
            requestedSource = "fake",
            isLowLatency = true,
            framesPerBurst = 96,
        )
    }

    override suspend fun stopCapture(): Clip {
        val clip = capture.stop()
        record(Interval(Kind.Mic, micStart, clock.nanos(), "mic"))
        return clip
    }

    override suspend fun playPrompt(prompt: PromptRef): Boolean {
        val start = clock.nanos()
        try {
            delay(promptMillis)
            return true
        } finally {
            record(Interval(Kind.Prompt, start, clock.nanos(), prompt.toString()))
        }
    }

    override fun playEarcon(earcon: Earcon) {
        val now = clock.nanos()
        record(Interval(Kind.Earcon, now, now + earcon.estimatedMillis * 1_000_000L, earcon.name))
    }

    override fun applyTarget(target: LiveTarget) = pipeline.setTarget(target)

    private fun record(interval: Interval) {
        synchronized(intervals) { intervals += interval }
    }
}
