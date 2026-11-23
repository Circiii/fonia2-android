package tech.fonia.mouth3d.render

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import tech.fonia.mouth3d.Mouth3DColors
import tech.fonia.mouth3d.Mouth3DData
import tech.fonia.mouth3d.Mouth3DFrameStats
import tech.fonia.mouth3d.MouthScene
import tech.fonia.mouth3d.SceneInputs
import tech.fonia.mouth3d.style.StylePalette
import tech.fonia.mouth3d.toStylePalette
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What the UI thread hands to the GL thread. All fields are read once per frame. */
internal class Mouth3DController {
    @Volatile var inputs: SceneInputs? = null
    @Volatile var colors: Mouth3DColors = Mouth3DColors.Default

    /** False while the screen is paused: no frames at all. */
    @Volatile var active: Boolean = true
    @Volatile var onStats: ((Mouth3DFrameStats) -> Unit)? = null
    @Volatile var onTooSlow: (() -> Unit)? = null
    @Volatile var onFailed: (() -> Unit)? = null
    private val lock = Any()
    private var dragYaw = 0f
    private var dragPitch = 0f
    private var dragging = false
    private var released = false
    internal var wake: (() -> Unit)? = null

    fun changed() { wake?.invoke() }

    fun drag(dYaw: Float, dPitch: Float) {
        synchronized(lock) { dragYaw += dYaw; dragPitch += dPitch; dragging = true }
        changed()
    }

    fun release() {
        synchronized(lock) { released = true; dragging = false }
        changed()
    }

    /** (dYaw, dPitch, releasedSinceLast) */
    fun takeDrag(): Triple<Float, Float, Boolean> = synchronized(lock) {
        val r = Triple(dragYaw, dragPitch, released)
        dragYaw = 0f; dragPitch = 0f; released = false
        r
    }

    val isDragging: Boolean get() = synchronized(lock) { dragging }
}

/**
 * The mouth's own GL thread for a TextureView: EGL 1.4 + an ES 3.0 context (4× MSAA when the GPU offers it), frames
 * from the Choreographer only while something moves, nothing while paused or without a surface. Survives a context
 * loss: everything is rebuilt from the CPU-side mesh on the next frame.
 */
internal class Mouth3DThread(private val data: Mouth3DData, private val controller: Mouth3DController) :
    HandlerThread("Mouth3D-GL") {
    private lateinit var handler: Handler
    private val main = Handler(Looper.getMainLooper())
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var texture: SurfaceTexture? = null
    private var width = 0
    private var height = 0
    private var gl: StyleGl? = null
    private var scheduled = false
    private var lastNanos = 0L
    private val scene = MouthScene()
    private var palette: StylePalette? = null
    private var paletteOf: Mouth3DColors? = null
        private var statSum = 0.0
    private var statMax = 0.0
    private var statCount = 0
    private var slowRun = 0
    var msaa = 0; private set
    private var msaaUsed = 0

    private val frameCallback = Choreographer.FrameCallback { nanos -> scheduled = false; frame(nanos) }

    fun startThread() {
        start()
        handler = Handler(looper)
        controller.wake = { handler.post { schedule() } }
    }

    fun attach(st: SurfaceTexture, w: Int, h: Int) = handler.post {
        texture = st; width = w; height = h
        lastNanos = 0L
        schedule()
    }

    fun resize(w: Int, h: Int) = handler.post { width = w; height = h; schedule() }

    /** Blocks until the GL thread no longer uses the surface (TextureView releases it right after). */
    fun detach() {
        val latch = CountDownLatch(1)
        handler.post {
            destroySurface()
            texture = null
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
    }

    fun shutdown() {
        controller.wake = null
        handler.post {
            destroyAll()
            quitSafely()
        }
    }

    private fun schedule(delayMs: Long = 0L) {
        if (scheduled || !controller.active || texture == null || controller.inputs == null) return
        scheduled = true
        if (delayMs > 0) Choreographer.getInstance().postFrameCallbackDelayed(frameCallback, delayMs)
        else Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun frame(nanos: Long) {
        val input = controller.inputs ?: return
        if (!controller.active || texture == null) return
        val t0 = SystemClock.elapsedRealtimeNanos()
        if (!ensureContext()) return
        val dt = if (lastNanos == 0L) 0f else (nanos - lastNanos) / 1e9f
        lastNanos = nanos
        val (dy, dp, released) = controller.takeDrag()
        if (dy != 0f || dp != 0f) scene.orbit.drag(dy, dp)
        if (released || (!controller.isDragging && scene.orbit.dragging)) scene.orbit.release()
        val f = scene.step(dt, input)
        val pal = paletteFor(controller.colors)
        try {
            msaaUsed = gl!!.render(f, input.pose, pal, input.highlight, width, height, msaa)
        } catch (e: RuntimeException) {
            Log.e(TAG, "render failed", e)
            destroyAll()
            main.post { controller.onFailed?.invoke() }
            return
        }
        if (!EGL14.eglSwapBuffers(display, surface)) {
            val err = EGL14.eglGetError()
            Log.w(TAG, "eglSwapBuffers failed 0x${Integer.toHexString(err)}: rebuilding")
            if (err == EGL14.EGL_CONTEXT_LOST) { gl = null; context = EGL14.EGL_NO_CONTEXT }
            destroyAll()
            lastNanos = 0L
            schedule()
            return
        }
        val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        stats(ms)
        // only the idle moves (breath, air, glow): about 30 frames a second is plenty
        if (controller.isDragging || (f.animating && !f.ambient)) schedule()
        else if (f.animating) schedule(AMBIENT_DELAY_MS)
        else lastNanos = 0L
    }

    private fun stats(ms: Double) {
        statSum += ms; statCount++
        if (ms > statMax) statMax = ms
        if (ms > SLOW_MS) slowRun++ else slowRun = 0
        if (slowRun == SLOW_FRAMES) main.post { controller.onTooSlow?.invoke() }
        if (statCount == 60) {
            val s = Mouth3DFrameStats(averageMs = statSum / statCount, maxMs = statMax, frames = statCount, msaaSamples = msaaUsed)
            Log.i(TAG, "frame avg %.2f ms, max %.2f ms over %d frames, MSAA %d".format(s.averageMs, s.maxMs, s.frames, s.msaaSamples))
            main.post { controller.onStats?.invoke(s) }
            statSum = 0.0; statMax = 0.0; statCount = 0
        }
    }

    private fun paletteFor(c: Mouth3DColors): StylePalette {
        val p = palette
        if (p != null && paletteOf == c) return p
        return c.toStylePalette().also { palette = it; paletteOf = c }
    }

    private fun ensureContext(): Boolean {
        if (display == EGL14.EGL_NO_DISPLAY) {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val v = IntArray(2)
            if (!EGL14.eglInitialize(display, v, 0, v, 1)) return fail("eglInitialize")
        }
        if (context == EGL14.EGL_NO_CONTEXT) {
            config = chooseConfig(4) ?: chooseConfig(0) ?: return fail("no ES3 config")
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            if (context == EGL14.EGL_NO_CONTEXT) return fail("eglCreateContext")
            gl = null
        }
        if (surface == EGL14.EGL_NO_SURFACE) {
            surface = EGL14.eglCreateWindowSurface(display, config, texture, intArrayOf(EGL14.EGL_NONE), 0)
            if (surface == EGL14.EGL_NO_SURFACE) return fail("eglCreateWindowSurface")
        }
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return fail("eglMakeCurrent")
        if (gl == null) {
            val t = SystemClock.elapsedRealtime()
            gl = StyleGl().also { it.create() }
            Log.i(TAG, "GL resources ready in ${SystemClock.elapsedRealtime() - t} ms (MSAA $msaa)")
        }
        return true
    }

    private fun chooseConfig(samples: Int): EGLConfig? {
        val attrs = mutableListOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_STENCIL_SIZE, 8,
        )
        if (samples > 0) attrs += listOf(EGL14.EGL_SAMPLE_BUFFERS, 1, EGL14.EGL_SAMPLES, samples)
        attrs += EGL14.EGL_NONE
        val out = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attrs.toIntArray(), 0, out, 0, 1, n, 0) || n[0] == 0) return null
        msaa = samples
        return out[0]
    }

    private fun fail(what: String): Boolean {
        Log.e(TAG, "$what failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        destroyAll()
        main.post { controller.onFailed?.invoke() }
        return false
    }

    private fun destroySurface() {
        if (surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            surface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun destroyAll() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        if (context != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE && gl != null) {
            if (EGL14.eglMakeCurrent(display, surface, surface, context)) gl?.release()
        }
        gl = null
        destroySurface()
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        context = EGL14.EGL_NO_CONTEXT
        EGL14.eglTerminate(display)
        display = EGL14.EGL_NO_DISPLAY
    }

    companion object {
        const val TAG = "Mouth3D"
        const val SLOW_MS = 12.0
        const val SLOW_FRAMES = 30
        const val AMBIENT_DELAY_MS = 16L
    }
}
