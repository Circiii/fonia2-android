package tech.fonia.live

/**
 * A finished take, held by `:platform:audio` (docs/v2/02 §3.2: samples never travel on the bands). The pure twin of
 * `tech.fonia.audio.CapturedClip`; `:platform:audio` converts between them (`toClip()` / `toCapturedClip()`).
 */
class Clip(
    val samples: FloatArray,
    val sampleRate: Double,
    /** Samples lost because the analysis fell behind the source, or because a route change restarted the take. */
    val dropped: Int = 0,
    /** CLOCK_MONOTONIC of the first sample, 0 when unknown. */
    val startNanos: Long = 0L,
    /** The real input source (`unprocessed`, `voiceRecognition`, `fixture`, …), saved with each attempt. */
    val audioSource: String = "",
) {
    val duration: Double get() = if (sampleRate > 0) samples.size / sampleRate else 0.0

    val isEmpty: Boolean get() = samples.isEmpty()

    override fun equals(other: Any?): Boolean = other is Clip &&
        other.sampleRate == sampleRate && other.dropped == dropped && other.samples.contentEquals(samples)

    override fun hashCode(): Int = (samples.contentHashCode() * 31 + sampleRate.hashCode()) * 31 + dropped

    override fun toString(): String = "Clip(${samples.size} samples at $sampleRate Hz, dropped $dropped, source '$audioSource')"

    companion object {
        val Empty = Clip(FloatArray(0), 0.0)
    }
}
