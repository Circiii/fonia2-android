package tech.fonia.live

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The [AudioDirector] state machine, shared by the real and the fake director so the rules test covers both.
 * Subclasses only start/stop the hardware. All transitions run under one mutex; [suspendAll] is the only call
 * that acts without it (it cancels the playing prompt, then closes the mic under the mutex).
 */
abstract class BaseAudioDirector(
    final override val board: LiveBoard,
    val fxGate: FxGate,
    protected val clock: NanoClock,
    protected val scope: CoroutineScope,
) : AudioDirector {
    private val mutableState = MutableStateFlow<AudioState>(AudioState.Idle)
    final override val state: StateFlow<AudioState> = mutableState.asStateFlow()
    final override val micGate: MicGate get() = fxGate

    private val mutex = Mutex()
    @Volatile private var suspended: SuspendReason? = null
    @Volatile private var playJob: Job? = null
    private var settleJob: Job? = null
    private var capturing = false
    private var info: CaptureInfo? = null

    /** Starts capturing into [board]. Called only after [FxGate] marked the mic open. */
    protected abstract suspend fun startCapture(target: LiveTarget): CaptureInfo

    /** Stops capturing and returns the take. Never cancelled. */
    protected abstract suspend fun stopCapture(): Clip

    /** Plays [prompt] to its end; cancellation must stop the sound. */
    protected abstract suspend fun playPrompt(prompt: PromptRef): Boolean

    /** Fire-and-forget effect; already allowed by [FxGate]. */
    protected abstract fun playEarcon(earcon: Earcon)

    /** Applies a new target to the running capture. */
    protected abstract fun applyTarget(target: LiveTarget)

    final override suspend fun play(prompt: PromptRef): Boolean = mutex.withLock {
        if (suspended != null) return false
        if (capturing) closeLocked()
        settleJob?.cancel()
        while (true) {
            val wait = fxGate.tryBeginPlayback(clock.nanos())
            if (wait == 0L) break
            delay(millisCeil(wait))
            if (suspended != null) return false
        }
        if (suspended != null) {
            fxGate.playbackEnded(clock.nanos())
            return false
        }
        mutableState.value = AudioState.Speaking
        var ok = false
        try {
            ok = coroutineScope {
                val job = async { playPrompt(prompt) }
                playJob = job
                job.await()
            }
        } catch (e: CancellationException) {
            // Stopped by suspendAll: not played to the end. A cancelled caller rethrows.
            currentCoroutineContext().ensureActive()
            ok = false
        } finally {
            playJob = null
            fxGate.playbackEnded(clock.nanos())
            if (mutableState.value == AudioState.Speaking) {
                mutableState.value = AudioState.Settling
                settleJob = scope.launch {
                    delay(fxGate.rule.micOpenDelayMillis)
                    mutableState.compareAndSet(AudioState.Settling, AudioState.Idle)
                }
            }
        }
        ok
    }

    final override suspend fun openMic(target: LiveTarget): CaptureInfo = mutex.withLock {
        suspended?.let { throw AudioSuspendedException(it) }
        if (capturing) {
            applyTarget(target)
            return info!!
        }
        while (true) {
            val wait = fxGate.tryBeginMicOpen(clock.nanos())
            if (wait == 0L) break
            delay(millisCeil(wait))
            suspended?.let { throw AudioSuspendedException(it) }
        }
        settleJob?.cancel()
        val opened = try {
            startCapture(target)
        } catch (t: Throwable) {
            fxGate.micOpenFailed()
            throw t
        }
        capturing = true
        info = opened
        val reason = suspended
        if (reason != null) {
            // suspendAll arrived while the stream was starting.
            closeLocked()
            throw AudioSuspendedException(reason)
        }
        mutableState.value = AudioState.Listening
        opened
    }

    final override suspend fun closeMic(): Clip = mutex.withLock {
        if (!capturing) Clip.Empty else closeLocked()
    }

    final override fun earcon(e: Earcon) {
        if (suspended != null) return
        if (fxGate.tryEmit(e.estimatedMillis, clock.nanos())) playEarcon(e)
    }

    final override fun suspendAll(reason: SuspendReason) {
        suspended = reason
        mutableState.value = AudioState.Suspended(reason)
        playJob?.cancel()
        scope.launch {
            mutex.withLock {
                settleJob?.cancel()
                if (capturing) closeLocked()
            }
        }
    }

    final override fun resume() {
        suspended = null
        val current = mutableState.value
        if (current is AudioState.Suspended) mutableState.compareAndSet(current, AudioState.Idle)
    }

    final override fun setTarget(target: LiveTarget) = applyTarget(target)

    /** The info of the open mic, or of the last one. */
    val captureInfo: CaptureInfo? get() = info

    private suspend fun closeLocked(): Clip = withContext(NonCancellable) {
        try {
            stopCapture()
        } finally {
            capturing = false
            fxGate.micClosed(clock.nanos())
            mutableState.value = suspended?.let { AudioState.Suspended(it) } ?: AudioState.Idle
        }
    }

    private fun millisCeil(nanos: Long): Long = (nanos + 999_999L) / 1_000_000L
}
