package tech.fonia.face

enum class FaceSourceKind(val rawValue: String) {
    ARKIT("arkit"),
    VISION("vision"),
    FAKE("fake"),
}

sealed class FaceSourceError(message: String) : Exception(message) {
    data object Unsupported : FaceSourceError("unsupported")

    data object CameraDenied : FaceSourceError("camera denied")

    data object CameraUnavailable : FaceSourceError("camera unavailable")
}

/**
 * Something that fills a [FaceLevels] while it runs.
 *
 * Control belongs to the main thread, because a source starts and stops with the mirror on screen
 * (`@MainActor` on iOS; Kotlin cannot enforce it). The per-frame work runs on each source's own thread.
 *
 * `makePreferredFaceSource()` is not here: on Android the choice needs CameraX and MediaPipe, so it
 * lives with the camera source in :features.
 */
interface FaceSource {
    val kind: FaceSourceKind
    val isSupported: Boolean

    /** Whether `tongueOut` means anything. When false it stays at 0. */
    val canDetectTongue: Boolean

    /** Whether the gaze angles come from the eyes. When false they are only the head direction. */
    val canEstimateEyeGaze: Boolean

    /** Turns the camera on. Throws [FaceSourceError] if there is no camera or no permission. */
    suspend fun start(levels: FaceLevels)

    /** Turns the camera off and marks the face absent. Safe to call at any time. */
    fun stop()
}
