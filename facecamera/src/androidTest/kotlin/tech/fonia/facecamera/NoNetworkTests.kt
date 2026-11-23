package tech.fonia.facecamera

import android.Manifest
import android.os.StrictMode
import android.os.strictmode.Violation
import android.system.Os
import androidx.camera.core.CameraSelector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tech.fonia.face.FaceObservation
import tech.fonia.face.FaceObservationSink
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * No telemetry: while the models load, analyse still images and a stream, and while the camera tracks, this process
 * opens no socket. Two nets: every file descriptor of the process is watched every few milliseconds for a new
 * `socket:[…]` (catches native code too, LiteRT included), and StrictMode reports any untagged socket the Java side
 * uses. Children use this app; their faces must not leave the phone, and neither may numbers about them.
 */
@RunWith(AndroidJUnit4::class)
class NoNetworkTests {
    @get:Rule
    val camera: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private fun sockets(): Set<String> {
        val fds = File("/proc/self/fd").listFiles() ?: return emptySet()
        val found = HashSet<String>()
        for (fd in fds) {
            val target = try {
                Os.readlink(fd.path)
            } catch (e: Exception) {
                continue
            }
            if (target.startsWith("socket:")) found.add(target)
        }
        return found
    }

    @Test(timeout = 120_000)
    fun noSocketWhileTracking() {
        val before = sockets()
        val seen = Collections.synchronizedSet(HashSet<String>())
        val violations = Collections.synchronizedList(ArrayList<Violation>())
        val watching = AtomicInteger(1)
        val watcher = Thread {
            while (watching.get() == 1) {
                seen.addAll(sockets())
                Thread.sleep(5)
            }
        }.apply { start() }
        val previous = StrictMode.getVmPolicy()
        val listener = Executors.newSingleThreadExecutor()
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder().detectUntaggedSockets().penaltyListener(listener) { violations.add(it) }.build(),
        )
        try {
            // The models, loaded from the bundle and run: five stills, then the 24-frame stream.
            val bytes = targetContext.assets.open(ModelBundle.ASSET).use { it.readBytes() }
            FaceLandmarkerPipeline(ModelBundle.parse(bytes)).use { pipeline ->
                for (name in FACE_NAMES) assertTrue(pipeline.process(AndroidFixtures.frame(name), 0, stream = false))
                val sequence = SequenceReference(AndroidFixtures.text("sequence.json"))
                val source = AndroidFixtures.argb("smile.png")
                for (k in sequence.offsets.indices) {
                    val frame = RgbaFrame().apply {
                        set(sequence.frame(k, source), sequence.frameWidth, sequence.frameHeight, sequence.frameWidth * 4, 0, false)
                    }
                    pipeline.process(frame, sequence.timestampsMs[k] * 1000, stream = true)
                }
            }
            // The camera, tracking for three seconds.
            var tracker = CameraFaceTracker(targetContext)
            if (!tracker.isSupported) tracker = CameraFaceTracker(targetContext, CameraSelector.LENS_FACING_BACK)
            if (tracker.isSupported) {
                val frames = AtomicInteger()
                val sink = object : FaceObservationSink {
                    override fun onFrame(observation: FaceObservation?) {
                        frames.incrementAndGet()
                    }

                    override fun onInterrupted() {}

                    override fun onFailure(error: Throwable) {}
                }
                InstrumentationRegistry.getInstrumentation().runOnMainSync { tracker.run(sink) }
                Thread.sleep(3_000)
                InstrumentationRegistry.getInstrumentation().runOnMainSync { tracker.pause() }
                report("no-network test: ${frames.get()} camera frames analysed")
                assertTrue("the camera delivered frames", frames.get() > 10)
            }
        } finally {
            watching.set(0)
            watcher.join()
            StrictMode.setVmPolicy(previous)
            listener.shutdown()
        }
        val opened = (seen + sockets()) - before
        report("sockets before: ${before.size}, new while tracking: $opened, StrictMode violations: ${violations.size}")
        assertEquals("sockets opened while tracking", emptySet<String>(), opened)
        assertEquals("untagged sockets", emptyList<Violation>(), violations.toList())
    }
}
