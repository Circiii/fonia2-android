package tech.fonia.face

// The seam a camera-backed source plugs into. On iOS, ARKitFaceSource reads seven blendshapes, the
// face transform and the look-at point from each ARFrame. On Android the next step implements
// [FaceTracker] with CameraX and MediaPipe Face Landmarker: its blendshape categories carry the same
// ARKit names (see [BlendshapeValues.fromScores]) and its facial transformation matrix gives the head
// pose. [TrackerFaceSource] and [FaceFrameReceiver] are ARKitFaceSource's control flow and frame
// handling, ported as they are, so only the camera and the model remain to write.

/**
 * One frame's face, in ARKit's terms (`ARKitFrameReceiver.reading(of:camera:)`).
 *
 * @param faceToCamera `camera.viewMatrix(for: .portrait) * face.transform`: the face's pose in
 *   portrait camera space, +z from the camera to the viewer, +y up, column-major.
 * @param lookAt `lookAtPoint`: where the eyes point, in face space. A tracker without eye gaze passes
 *   the face's own +z, (0, 0, 1), so the gaze is the head direction.
 */
data class FaceObservation(
    val blendshapes: BlendshapeValues,
    val faceToCamera: Float4x4,
    val lookAt: Float3,
) {
    fun toFrame(): FaceFrame = FaceFrame(blendshapes = blendshapes, faceToCamera = faceToCamera, lookAt = lookAt)
}

/** Where a tracker delivers, from its own thread. */
interface FaceObservationSink {
    /** Every camera frame: the first tracked face, or null when no face is tracked. */
    fun onFrame(observation: FaceObservation?)

    /** The camera stopped delivering (`sessionWasInterrupted`). */
    fun onInterrupted()

    /** Tracking failed (`session(_:didFailWithError:)`); the tracker logs it. */
    fun onFailure(error: Throwable)
}

/** The camera and the face model, which the Android step writes. */
interface FaceTracker {
    /** `ARFaceTrackingConfiguration.isSupported`: a front camera and a model that runs here. */
    val isSupported: Boolean
    val canDetectTongue: Boolean
    val canEstimateEyeGaze: Boolean

    /** `requireCameraPermission()`: returns once granted, asking if needed; throws [FaceSourceError.CameraDenied] otherwise. */
    suspend fun requireCameraPermission()

    /** Starts the camera from scratch (`run` with `.resetTracking`), one face, delivering to [sink]. */
    fun run(sink: FaceObservationSink)

    /** Stops the camera (`pause()`). Safe at any time. */
    fun pause()
}

/** `ARKitFrameReceiver`: publishing happens inside the lock, so no frame can land after [detach] has cleared the levels. */
class FaceFrameReceiver : FaceObservationSink {
    private val lock = Any()
    private var levels: FaceLevels? = null

    fun attach(target: FaceLevels) {
        synchronized(lock) { levels = target }
    }

    fun detach() {
        synchronized(lock) {
            levels?.publish(FaceFrame.ABSENT)
            levels = null
        }
    }

    override fun onFrame(observation: FaceObservation?) {
        val reading = observation?.toFrame() ?: FaceFrame.ABSENT
        synchronized(lock) { levels?.publish(reading) }
    }

    override fun onInterrupted() {
        synchronized(lock) { levels?.publish(FaceFrame.ABSENT) }
    }

    override fun onFailure(error: Throwable) {
        synchronized(lock) { levels?.publish(FaceFrame.ABSENT) }
    }
}

/** `ARKitFaceSource` over any [FaceTracker]. Nothing visual is kept or sent anywhere: only numbers leave a frame. */
class TrackerFaceSource(
    private val tracker: FaceTracker,
    override val kind: FaceSourceKind,
) : FaceSource {
    override val isSupported: Boolean
        get() = tracker.isSupported
    override val canDetectTongue: Boolean
        get() = tracker.canDetectTongue
    override val canEstimateEyeGaze: Boolean
        get() = tracker.canEstimateEyeGaze

    private val receiver = FaceFrameReceiver()

    @Volatile private var wantsCamera = false

    override suspend fun start(levels: FaceLevels) {
        if (!isSupported) throw FaceSourceError.Unsupported
        wantsCamera = true
        tracker.requireCameraPermission()
        // The mirror may have left the screen while the permission prompt was up.
        if (!wantsCamera) return
        receiver.attach(levels)
        tracker.run(receiver)
    }

    override fun stop() {
        wantsCamera = false
        tracker.pause()
        receiver.detach()
    }
}
