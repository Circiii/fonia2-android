package tech.fonia.facecamera

import android.Manifest
import androidx.camera.core.CameraSelector
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tech.fonia.face.FaceLevels
import tech.fonia.face.FaceObservation
import tech.fonia.face.FaceObservationSink
import tech.fonia.face.FaceSourceError
import tech.fonia.face.FaceSourceKind
import java.util.concurrent.atomic.AtomicInteger

/**
 * The tracker on a real camera. The test emulator (AVD sp_d) has no front camera, only the emulated back camera,
 * which shows a moving test pattern and no face; the tests run the tracker on whichever camera exists, so on a phone
 * they use the front one. What they check: frames flow, nothing arrives after pause, the screen going to the
 * background interrupts and the return resumes, a second run starts again.
 */
@RunWith(AndroidJUnit4::class)
class CameraFaceTrackerTests {
    @get:Rule
    val camera: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private fun tracker(): CameraFaceTracker {
        val front = CameraFaceTracker(targetContext)
        if (front.isSupported) return front
        val back = CameraFaceTracker(targetContext, CameraSelector.LENS_FACING_BACK)
        assumeTrue("no camera on this device", back.isSupported)
        return back
    }

    private class CountingSink : FaceObservationSink {
        val frames = AtomicInteger()
        val faces = AtomicInteger()
        val interruptions = AtomicInteger()
        val failures = AtomicInteger()

        override fun onFrame(observation: FaceObservation?) {
            frames.incrementAndGet()
            if (observation != null) faces.incrementAndGet()
        }

        override fun onInterrupted() {
            interruptions.incrementAndGet()
        }

        override fun onFailure(error: Throwable) {
            failures.incrementAndGet()
        }
    }

    private fun waitFor(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(20)
        }
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test(timeout = 90_000)
    fun framesFlowAndStopWithPause() {
        val tracker = tracker()
        val sink = CountingSink()
        val processing = mutableListOf<Long>()
        tracker.analysisListener = { synchronized(processing) { processing.add(it.processingNanos) } }
        onMain { tracker.run(sink) }
        waitFor { sink.frames.get() >= 30 }
        assertEquals(0, sink.failures.get())
        onMain { tracker.pause() }
        Thread.sleep(300)
        val atPause = sink.frames.get()
        Thread.sleep(700)
        assertEquals("no frame after pause", atPause, sink.frames.get())
        val median = synchronized(processing) { processing.sorted()[processing.size / 2] } / 1e6
        report("camera (${tracker.lensFacing}): ${sink.frames.get()} frames, ${sink.faces.get()} with a face, analysis median %.2f ms".format(median))

        // A second run starts from scratch.
        onMain { tracker.run(sink) }
        waitFor { sink.frames.get() >= atPause + 10 }
        onMain { tracker.pause() }
        tracker.analysisListener = null
    }

    @Test(timeout = 90_000)
    fun backgroundInterruptsAndReturnResumes() {
        val tracker = tracker()
        val sink = CountingSink()
        val host = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        onMain {
            host.registry.currentState = Lifecycle.State.RESUMED
            tracker.hostLifecycle = host.lifecycle
            tracker.run(sink)
        }
        waitFor { sink.frames.get() >= 10 }
        onMain { host.registry.currentState = Lifecycle.State.CREATED } // the app went to the background
        assertEquals(1, sink.interruptions.get())
        Thread.sleep(500)
        val stopped = sink.frames.get()
        Thread.sleep(700)
        assertEquals("no frame in the background", stopped, sink.frames.get())
        onMain { host.registry.currentState = Lifecycle.State.RESUMED }
        waitFor { sink.frames.get() >= stopped + 10 }
        onMain {
            tracker.pause()
            tracker.hostLifecycle = null
        }
        assertEquals(0, sink.failures.get())
    }

    @Test(timeout = 90_000)
    fun throughTheSource() {
        // TrackerFaceSource over the tracker, as the coach uses it: present follows the camera, stop marks absent.
        val tracker = tracker()
        val source = CameraFaceSource(tracker)
        val levels = FaceLevels()
        runBlocking { withContext(Dispatchers.Main) { source.start(levels) } }
        waitFor { tracker.framesAnalysed.get() >= 10 }
        onMain { source.stop() }
        assertFalse(levels.isPresent)
        Thread.sleep(500)
        assertFalse(levels.isPresent)
    }

    /**
     * `FakeSourceTests.simulatorFallback`: on an emulator with no front camera the preferred source is the scripted
     * one, and the camera source is unsupported and refuses to start. (The iOS test checks ARKit and Vision; Android
     * has one camera source.)
     */
    @Test
    fun simulatorFallback() {
        assumeTrue(
            "an emulator without a front camera",
            isEmulator() && !CameraFaceTracker.hasCamera(targetContext, CameraSelector.LENS_FACING_FRONT),
        )
        assertEquals(FaceSourceKind.FAKE, makePreferredFaceSource(targetContext).kind)
        val camera = CameraFaceSource(targetContext)
        assertEquals(FaceSourceKind.ARKIT, camera.kind)
        assertFalse(camera.isSupported)
        assertFalse(camera.canDetectTongue)
        assertTrue(camera.canEstimateEyeGaze)
        val error = assertThrows(FaceSourceError::class.java) { runBlocking { camera.start(FaceLevels()) } }
        assertSame(FaceSourceError.Unsupported, error)
        camera.stop()
    }
}
