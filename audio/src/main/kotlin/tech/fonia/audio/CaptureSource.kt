package tech.fonia.audio

/** Where a take comes from: the microphone, or a file in tests and in the recorded-take mode. */
interface CaptureSource {
    /** Starts writing mono float samples into [ring] and returns the real sample rate. */
    suspend fun start(ring: CaptureRing): Double

    /** The rate of the samples being written now. It can change when a route change rebuilds the stream. */
    val sampleRate: Double

    /** Stops writing. Nothing touches the ring after this returns. */
    suspend fun stop()
}

/** iOS `CaptureError`. Kotlin throws exceptions, so each case is one. */
sealed class CaptureError(message: String) : Exception(message) {
    class AlreadyRunning : CaptureError("A take is already running")

    class MicrophoneDenied : CaptureError("The microphone is not allowed")

    /** The route has no usable input, for example no microphone or an inactive session. */
    class NoInput : CaptureError("The route has no usable input")

    class UnsupportedInputFormat : CaptureError("The input format is not supported")
}
