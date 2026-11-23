package tech.fonia.facecamera

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug builds only (emulator fixtures, `:app`'s `--es face fixture`): runs still RGBA images through the same Face
 * Landmarker pipeline as the camera, on the camera thread, and hands each result to [tracker]'s `meshListener`
 * exactly as a camera frame would ([MouthTracker] attached to [tracker] cannot tell the difference). The camera
 * itself is never opened.
 *
 * Keep-only-latest like `ImageAnalysis`: [submit] returns false while the previous image is still being analysed.
 * The pixels are read in place: do not change the array after submitting it.
 */
class StillFrameFeed(context: Context, val tracker: CameraFaceTracker) {
    private val appContext = context.applicationContext
    private val busy = AtomicBoolean(false)
    private val resetNext = AtomicBoolean(true)

    /** Frames analysed so far (tests, logs). */
    @Volatile var framesAnalysed = 0
        private set

    /** The next image starts a fresh track (the detector runs again). */
    fun reset() = resetNext.set(true)

    /** Queues one upright, unmirrored RGBA image ([width] × [height], tightly packed). */
    fun submit(rgba: ByteArray, width: Int, height: Int, timestampNanos: Long = System.nanoTime()): Boolean {
        require(rgba.size >= width * height * 4) { "buffer too small" }
        if (!busy.compareAndSet(false, true)) return false
        FaceEngine.executor.execute {
            try {
                val engine = FaceEngine.pipeline(appContext)
                if (resetNext.getAndSet(false) || FaceEngine.run != RUN) {
                    engine.reset()
                    FaceEngine.run = RUN // a real camera run afterwards sees a different run and resets again
                }
                FaceEngine.frame.set(rgba, width, height, width * 4, 0, false)
                val found = engine.process(FaceEngine.frame, timestampNanos / 1000, stream = true)
                framesAnalysed++
                tracker.meshListener?.let { listener ->
                    FaceEngine.filler.fill(engine, FaceEngine.frame, timestampNanos, found, FaceEngine.mesh)
                    listener(FaceEngine.mesh)
                }
            } catch (e: Throwable) {
                Log.e("FoniaFace", "still frame failed: ${e.message}", e)
            } finally {
                busy.set(false)
            }
        }
        return true
    }

    private companion object {
        /** Never a camera run number (those count up from 1). */
        const val RUN = -7
    }
}
