package tech.fonia.audio

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The JNI surface of `libfonia_audio_rt.so` (src/main/cpp). FoniaJni.cpp binds these by name in
 * `JNI_OnLoad`, and calls [onCaptureError] from Oboe's error thread. On the JVM (unit tests) the library
 * cannot load, [isAvailable] is false, and [CaptureRing] falls back to its Kotlin twin.
 */
internal object NativeAudio {
    val isAvailable: Boolean = try {
        System.loadLibrary("fonia_audio_rt")
        true
    } catch (e: UnsatisfiedLinkError) {
        false
    }

    private val errorListeners = ConcurrentHashMap<Int, (Int) -> Unit>()
    private val nextListenerId = AtomicInteger(0)

    fun registerErrorListener(listener: (Int) -> Unit): Int {
        val id = nextListenerId.incrementAndGet()
        errorListeners[id] = listener
        return id
    }

    fun unregisterErrorListener(id: Int) {
        errorListeners.remove(id)
    }

    /** Called by FoniaJni.cpp when Oboe closed a stream after an error (a disconnect, mostly). */
    @JvmStatic
    fun onCaptureError(id: Int, error: Int) {
        errorListeners[id]?.invoke(error)
    }

    @JvmStatic external fun ringCreate(capacity: Int): Long

    @JvmStatic external fun ringDestroy(handle: Long)

    @JvmStatic external fun ringWrite(handle: Long, src: FloatArray, offset: Int, count: Int): Int

    @JvmStatic external fun ringRead(handle: Long, dst: FloatArray, offset: Int, count: Int): Int

    @JvmStatic external fun ringCapacity(handle: Long): Int

    @JvmStatic external fun ringAvailable(handle: Long): Int

    @JvmStatic external fun ringDropped(handle: Long): Long

    @JvmStatic external fun captureCreate(ring: Long, listenerId: Int): Long

    @JvmStatic external fun captureOpen(handle: Long, sampleRate: Int, inputPreset: Int, deviceId: Int, info: IntArray): Int

    @JvmStatic external fun captureStart(handle: Long): Int

    @JvmStatic external fun captureClose(handle: Long)

    @JvmStatic external fun captureState(handle: Long): Int

    @JvmStatic external fun captureDestroy(handle: Long)

    /** Oboe's input latency estimate in ms, or -1 when the stream cannot tell. */
    @JvmStatic external fun captureLatencyMillis(handle: Long): Double

    @JvmStatic external fun captureDeliver(handle: Long, interleaved: FloatArray, frames: Int, channels: Int): Int
}
