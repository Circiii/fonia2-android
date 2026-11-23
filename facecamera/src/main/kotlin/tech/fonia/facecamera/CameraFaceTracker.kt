package tech.fonia.facecamera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Observer
import tech.fonia.face.FaceMeshFrame
import tech.fonia.face.FaceObservationSink
import tech.fonia.face.FaceSourceError
import tech.fonia.face.FaceTracker
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The camera face tracker: CameraX frames from the front camera, analysed by the Face Landmarker models on LiteRT
 * ([FaceLandmarkerPipeline]), handed to the seam as [tech.fonia.face.FaceObservation]s. What ARKitFaceSource's ARSession is on iOS.
 *
 * - `ImageAnalysis` at 640×480 (4:3, the size the iOS Vision path asks for; the face region is resampled to 256×256
 *   anyway), RGBA, keep-only-latest: a frame that arrives while the last one is analysed is dropped.
 * - Frames are analysed in their sensor orientation; [RgbaFrame] samples them upright and unmirrored, which is the
 *   frame ARKit's portrait camera space describes. The preview is mirrored by PreviewView, as ARSCNView is.
 * - One process-wide camera thread ([FaceEngine]) owns the models; they load once and stay loaded, so the camera
 *   restarting at each coach step costs no model loading.
 * - The camera runs between [run] and [pause], and only while the screen hosting it is started: when the app goes to
 *   the background the camera closes and the face reads absent ([FaceObservationSink.onInterrupted]), then it opens
 *   again on return, as an ARKit session is interrupted and resumes.
 * - Nothing visual is kept: the pixels are copied into one reused buffer, read, and overwritten by the next frame.
 *
 * Control ([run], [pause], [requireCameraPermission]) belongs to the main thread, as FaceSource's does.
 */
class CameraFaceTracker internal constructor(
    context: Context,
    /** `CameraSelector.LENS_FACING_FRONT`; tests on an emulator without a front camera use the back one. */
    internal val lensFacing: Int,
    /** Tests: pretend the camera is (not) there. */
    private val cameraPresent: Boolean? = null,
) : FaceTracker {
    constructor(context: Context) : this(context, CameraSelector.LENS_FACING_FRONT)

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private val cameraLifecycle = CameraLifecycle()

    @Volatile private var sink: FaceObservationSink? = null
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var stateObserver: Observer<CameraState>? = null

    /** `ARFaceTrackingConfiguration.isSupported`: the camera exists and LiteRT ships for this CPU. */
    override val isSupported: Boolean
        get() = (cameraPresent ?: hasCamera(appContext, lensFacing)) && FaceEngine.abiSupported

    /** MediaPipe's blendshapes have no `tongueOut`: the seam gets 0 and the `.tongueOut` check waits for "Am făcut". */
    override val canDetectTongue: Boolean = false

    /** The gaze comes from the irises when they can be trusted, from the head direction otherwise ([FaceGeometry]). */
    override val canEstimateEyeGaze: Boolean = true

    /** Asks the person for the camera; [FaceCameraView] lends its Activity's permission launcher while on screen. */
    @Volatile var permissionPrompt: (suspend () -> Boolean)? = null

    /** The curves from model scores to ARKit's scale; identity until recalibrated (PARITY.md). */
    @Volatile var calibration: BlendshapeCalibration = BlendshapeCalibration.IDENTITY

    /** Every analysed frame, on the camera thread, for the recording tool. */
    @Volatile var analysisListener: ((FaceAnalysis) -> Unit)? = null

    /**
     * Every analysed frame as the H1 seam ([FaceMeshFrame]: lips, anchors, irises, 52 scores, pose, light, mouth
     * crop), on the camera thread. The frame object is reused: read it inside the callback. [MouthTracker] uses it.
     */
    @Volatile var meshListener: ((FaceMeshFrame) -> Unit)? = null

    /** The lifecycle of the screen showing the camera; the camera only runs while it is started. Main thread. */
    internal var hostLifecycle: Lifecycle?
        get() = cameraLifecycle.host
        set(value) {
            cameraLifecycle.host = value
        }

    /** Where the preview goes (PreviewView's surface); null shows nothing. Main thread. */
    internal var previewSurfaceProvider: Preview.SurfaceProvider? = null
        set(value) {
            if (field === value) return // a recomposition must not restart the preview
            field = value
            preview?.surfaceProvider = value
        }

    /** Frames analysed since the last [run], for tests. */
    internal val framesAnalysed = AtomicInteger()

    /** `requireCameraPermission()` (ARKitFaceSource.swift): returns once granted, asking if possible. */
    override suspend fun requireCameraPermission() {
        if (hasPermission()) return
        val prompt = permissionPrompt ?: throw FaceSourceError.CameraDenied
        if (!prompt() || !hasPermission()) throw FaceSourceError.CameraDenied
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Starts the camera from scratch (`run` with `.resetTracking`), delivering on the camera thread. */
    override fun run(sink: FaceObservationSink) {
        val run = generation.incrementAndGet()
        this.sink = sink
        framesAnalysed.set(0)
        FaceEngine.prepare(appContext)
        onMain {
            if (generation.get() != run) return@onMain
            val future = ProcessCameraProvider.getInstance(appContext)
            future.addListener({
                if (generation.get() != run) return@addListener
                try {
                    bind(future.get(), run, sink)
                } catch (e: Exception) {
                    fail(sink, e)
                }
            }, ContextCompat.getMainExecutor(appContext))
        }
    }

    /** Stops the camera (`pause()`). Safe at any time; no frame reaches the sink afterwards. */
    override fun pause() {
        generation.incrementAndGet()
        sink = null
        onMain { unbind() }
    }

    private fun bind(provider: ProcessCameraProvider, run: Int, sink: FaceObservationSink) {
        unbind()
        this.provider = provider
        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(ANALYSIS_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        analysis.setAnalyzer(FaceEngine.executor, Analyzer(run, sink))
        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build(),
            )
            .build()
        preview.surfaceProvider = previewSurfaceProvider
        this.analysis = analysis
        this.preview = preview
        cameraLifecycle.running = true
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val camera = provider.bindToLifecycle(cameraLifecycle, selector, preview, analysis)
        this.camera = camera
        val observer = Observer<CameraState> { state ->
            val error = state.error ?: return@Observer
            if (generation.get() != run) return@Observer
            if (error.type == CameraState.ErrorType.CRITICAL) {
                fail(sink, IllegalStateException("camera error ${error.code}", error.cause))
            } else {
                sink.onInterrupted()
            }
        }
        camera.cameraInfo.cameraState.observeForever(observer)
        stateObserver = observer
    }

    private fun unbind() {
        cameraLifecycle.running = false
        stateObserver?.let { camera?.cameraInfo?.cameraState?.removeObserver(it) }
        stateObserver = null
        analysis?.clearAnalyzer()
        val provider = provider
        if (provider != null) {
            val useCases = listOfNotNull(preview, analysis)
            if (useCases.isNotEmpty()) provider.unbind(*useCases.toTypedArray())
        }
        preview = null
        analysis = null
        camera = null
    }

    private fun fail(sink: FaceObservationSink, error: Throwable) {
        Log.e(TAG, "Face tracking failed: ${error.message}", error)
        sink.onFailure(error)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** Runs on [FaceEngine]'s thread, one frame at a time. */
    private inner class Analyzer(private val run: Int, private val sink: FaceObservationSink) : ImageAnalysis.Analyzer {
        private var failed = false

        override fun analyze(image: ImageProxy) {
            if (generation.get() != run || failed) {
                image.close()
                return
            }
            val start = System.nanoTime()
            val timestamp = image.imageInfo.timestamp
            try {
                try {
                    FaceEngine.copy(image)
                } finally {
                    image.close()
                }
                val engine = FaceEngine.pipeline(appContext)
                if (FaceEngine.run != run) {
                    engine.reset()
                    FaceEngine.run = run
                }
                val found = engine.process(FaceEngine.frame, timestamp / 1000, stream = true)
                val observation = if (found) engine.observation(calibration, FaceEngine.mapped) else null
                framesAnalysed.incrementAndGet()
                if (generation.get() != run) return
                sink.onFrame(observation)
                meshListener?.let { listener ->
                    FaceEngine.filler.fill(engine, FaceEngine.frame, timestamp, found, FaceEngine.mesh)
                    listener(FaceEngine.mesh)
                }
                analysisListener?.let { listener ->
                    val analysis = FaceEngine.analysis
                    val nanos = System.nanoTime() - start
                    if (observation != null) analysis.fill(engine, observation, timestamp, nanos) else analysis.clear(timestamp, nanos)
                    listener(analysis)
                }
            } catch (e: Throwable) {
                failed = true
                if (generation.get() == run) fail(sink, e)
            }
        }
    }

    /**
     * A LifecycleOwner for CameraX that is resumed only between [run] and [pause] and only while the host screen is
     * started. Main thread, as LifecycleRegistry requires.
     */
    private inner class CameraLifecycle : LifecycleOwner, LifecycleEventObserver {
        // Created on any thread (the tracker may be); first moved, always on the main thread, by update().
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        var running = false
            set(value) {
                field = value
                update()
            }

        var host: Lifecycle? = null
            set(value) {
                field?.removeObserver(this)
                field = value
                value?.addObserver(this)
                update()
            }

        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) = update()

        private fun update() {
            val hostState = host?.currentState ?: Lifecycle.State.RESUMED
            val target = when {
                !running -> Lifecycle.State.CREATED
                hostState.isAtLeast(Lifecycle.State.RESUMED) -> Lifecycle.State.RESUMED
                hostState.isAtLeast(Lifecycle.State.STARTED) -> Lifecycle.State.STARTED
                else -> Lifecycle.State.CREATED
            }
            val wasOn = registry.currentState.isAtLeast(Lifecycle.State.STARTED)
            registry.currentState = target
            // The app went to the background: the camera closes and the face is absent until it reopens.
            if (running && wasOn && !target.isAtLeast(Lifecycle.State.STARTED)) sink?.onInterrupted()
        }
    }

    companion object {
        private const val TAG = "FoniaFace"
        internal val ANALYSIS_SIZE = Size(640, 480)

        /** Whether a camera facing [lensFacing] (CameraX's constants, the same as Camera2's) exists. */
        internal fun hasCamera(context: Context, lensFacing: Int): Boolean {
            val manager = context.getSystemService(CameraManager::class.java) ?: return false
            return try {
                manager.cameraIdList.any { id ->
                    manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == lensFacing
                }
            } catch (e: Exception) {
                false
            }
        }
    }
}

/**
 * The camera thread and what lives on it: the models (loaded once per process), the reused frame buffer and the
 * analysis object. A single thread means one pipeline is never used by two cameras at once.
 */
internal object FaceEngine {
    /** LiteRT ships these in its AAR; 32-bit x86 is not among them. */
    private val LITERT_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64")

    val abiSupported: Boolean = Build.SUPPORTED_ABIS.any { it in LITERT_ABIS }

    val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread({
            // iOS runs the ARKit delegate queue at .userInteractive.
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            task.run()
        }, "tech.fonia.face.camera").apply { isDaemon = true }
    }

    // Camera thread only from here on.
    private var pipeline: FaceLandmarkerPipeline? = null
    private var loadError: Throwable? = null
    var run = 0
    val frame = RgbaFrame()
    private var pixels = ByteArray(0)
    val mapped = FloatArray(7)
    val analysis = FaceAnalysis()
    val mesh = FaceMeshFrame()
    val filler = MeshFrameFiller()
    private val matrix = Matrix()
    private val matrixValues = FloatArray(9)

    /** Loads the models in the background while the camera opens. */
    fun prepare(context: Context) {
        executor.execute {
            try {
                pipeline(context)
            } catch (e: Throwable) {
                // Reported by the first frame.
            }
        }
    }

    fun pipeline(context: Context): FaceLandmarkerPipeline {
        pipeline?.let { return it }
        loadError?.let { throw it }
        return try {
            FaceLandmarkerPipeline(ModelBundle.load(context)).also { pipeline = it }
        } catch (e: Throwable) {
            loadError = e
            throw e
        }
    }

    /** Copies an RGBA ImageProxy into the reused buffer and describes its orientation in [frame]. */
    fun copy(image: ImageProxy) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        check(plane.pixelStride == 4) { "RGBA expected" }
        val needed = rowStride * (image.height - 1) + image.width * 4
        if (pixels.size < needed) pixels = ByteArray(needed)
        buffer.rewind()
        buffer.get(pixels, 0, minOf(needed, buffer.remaining()))
        // A mirrored buffer would show up as a reflection in the sensor-to-buffer transform.
        matrix.set(image.imageInfo.sensorToBufferTransformMatrix)
        matrix.getValues(matrixValues)
        val mirrored = matrixValues[Matrix.MSCALE_X] * matrixValues[Matrix.MSCALE_Y] -
            matrixValues[Matrix.MSKEW_X] * matrixValues[Matrix.MSKEW_Y] < 0
        frame.set(pixels, image.width, image.height, rowStride, image.imageInfo.rotationDegrees, mirrored)
    }
}
