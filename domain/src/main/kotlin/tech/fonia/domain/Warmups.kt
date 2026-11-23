package tech.fonia.domain

import kotlinx.serialization.Serializable

/** An oral warm-up. These are short and optional, and nothing ever waits on them. */
@Serializable
data class Warmup(
    val id: String,
    val name: String,
    val say: String,
    val picture: String,
    val pose: Viseme,
    /** Second pose for alternating movements. */
    val poseB: Viseme? = null,
    val mode: Mode,
    val match: String? = null,
    val matchB: String? = null,
    val audio: String? = null,
    /** Hold time per repetition. Zero means an alternating movement counted in `reps`. */
    val holdMs: Int,
    val reps: Int,
) {
    @Serializable(with = Mode.RawSerializer::class)
    enum class Mode(override val rawValue: String) : StringRawValue {
        Camera("camera"),
        Audio("audio"),

        /** The movement cannot be seen, so the learner taps when done. */
        Self("self"),
        ;

        companion object {
            fun fromRawValue(rawValue: String): Mode? = entries.byRawValue(rawValue)
        }

        internal object RawSerializer : StringRawValueSerializer<Mode>("Warmup.Mode", { entries })
    }
}
